#!/usr/bin/env python3
"""MG07真实Commerce双身份导航验收；仅创建专用库和本次回环进程，不修改原业务分区。"""
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import uuid
import zipfile

ROOT=Path(__file__).resolve().parents[1]
COMMERCE=ROOT.parent/'commerce-platform'
ADMIN_PORT=21762
SERVER_PORT=21761
COMMERCE_PORT=21763
PROCESS_TIMEOUT_SECONDS=60

def module(name,file):
    """复用已验证的真实PKCE及受控私密文件，避免复制身份协议。"""
    spec=importlib.util.spec_from_file_location(name,ROOT/'deploy'/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

def main(ui=False):
    """新库使用独有名称，任何错误保留检查点与日志，只关闭自有Popen。"""
    os.umask(0o077);h=module('nav_context','governance-context-smoke.py');a=module('nav_access','governance-access-smoke.py')
    run=ROOT/'.local/menu-role-governance'/('mg07-'+uuid.uuid4().hex[:12]);run.mkdir(parents=True,mode=0o700)
    subprocess.run(['python3','deploy/governance-test-db.py','--directory',str(run/'database')],cwd=ROOT,check=True,stdout=subprocess.DEVNULL,timeout=PROCESS_TIMEOUT_SECONDS)
    fixture=json.loads(h.read_private(ROOT/'.local/governance/p2/identity/casdoor.json'))
    ops=json.loads(h.read_private(ROOT/'.local/governance/casdoor-isolated/management-client.json'))
    graph=dict(line.split('=',1) for line in h.read_private(ROOT/'.local/governance/p3/graph/graph.properties').splitlines() if line and not line.startswith('#') and '=' in line)
    db=h.read_private(run/'database/database.properties');database=json.loads(h.read_private(run/'database/database.json'))['database']
    admin=ROOT/'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar';server=ROOT/'auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar'
    for jar in [admin,server]:
        with zipfile.ZipFile(jar) as archive:
            if archive.read('BOOT-INF/lib/auth-platform-governance-0.1.0-SNAPSHOT.jar')!=(ROOT/'auth-platform-governance/target/auth-platform-governance-0.1.0-SNAPSHOT.jar').read_bytes():raise RuntimeError('current nested governance artifact required')
    commerce_jar=COMMERCE/'commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(commerce_jar) as archive:
        if archive.read('BOOT-INF/lib/auth-platform-sdk-0.1.0-SNAPSHOT.jar')!=(ROOT/'auth-platform-sdk/target/auth-platform-sdk-0.1.0-SNAPSHOT.jar').read_bytes():raise RuntimeError('current nested SDK artifact required')
    def uid():return str(uuid.uuid4())
    def stamp(seconds):return (datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=seconds)).isoformat().replace('+00:00','Z')
    def cli(name,args):
        package='com.lrj.authz.admin.governance.' if name.endswith('ProjectionCli') else 'com.lrj.authz.governance.cli.'
        log=run/(name+'-'+uuid.uuid4().hex+'.log')
        with log.open('w') as stream:
            result=subprocess.run(['java','-Xmx256m','-Dloader.main='+package+name,'-cp',str(admin),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,args)],stdout=stream,stderr=subprocess.STDOUT,timeout=PROCESS_TIMEOUT_SECONDS)
        if result.returncode:raise RuntimeError('owned fixture CLI failed: '+name)
    tenant,owner,member,member_principal=uid(),uid(),uid(),uid();owner_member=uid()
    for kind,principal,membership in [('internal',owner,owner_member),('external',member_principal,member)]:
        values={'command.id':uid(),'operator.ref':'mg07-fixture','tenant.id':tenant,'tenant.code':'mg07-'+tenant,'principal.id':principal,'issuer':h.ISSUER,
                'subject':fixture['users'][kind]['id'],'membership.id':membership,'valid.from':'2020-01-01T00:00:00Z','source.system':'mg07-fixture','source.tenant.ref':tenant,'source.subject.ref':kind}
        file=run/(kind+'.properties');h.private(file,h.props(values));cli('GovernanceCli',['bootstrap',run/'database/database.properties',file])
    catalog={'catalog.application':'commerce','catalog.owner-principal':owner,'catalog.entry-origin':'http://127.0.0.1:'+str(COMMERCE_PORT),'catalog.operator':'mg07-fixture',
             'catalog.command':uid(),'catalog.owner-issuer':h.ISSUER,'catalog.owner-subject':fixture['users']['internal']['id']}
    h.private(run/'catalog.properties',db+h.props(catalog));cli('CatalogCli',['register',run/'catalog.properties','configured'])
    read,write='commerce.product.read','commerce.product.update'
    manifest={'schema_version':'1','application':'commerce','manifest_version':1,'capabilities':[{'code':read,'resource_type':'product','risk_level':'NORMAL'},{'code':write,'resource_type':'product','risk_level':'HIGH'}],
              'menus':[{'code':'group.products','parent':None,'route':'/admin','any_of':[write],'label':'商品经营','position':0},
                       {'code':'page.products','parent':'group.products','route':'/operations/products','any_of':[read],'label':'商品档案','position':1},
                       {'code':'page.private','parent':'group.products','route':'/operations/private','any_of':[write],'label':'商品维护','position':2},
                       {'code':'page.collaboration','parent':None,'route':'/collaboration/products','any_of':[read],'label':'门店协作','position':3}]}
    if ui:
        # 真实当前Git声明只发布到本工具新建库，不能重复原部署Owner发布。
        subprocess.run(['node','frontend/scripts/export-menu-catalog.mjs','--version','1','--output',str(run/'source-candidate.json')],cwd=COMMERCE,check=True,timeout=PROCESS_TIMEOUT_SECONDS,stdout=subprocess.DEVNULL)
        manifest=json.loads(h.read_private(run/'source-candidate.json'))['manifest']
    h.private(run/'manifest.json',json.dumps(manifest));cli('CatalogCli',['publish',run/'catalog.properties',run/'manifest.json'])
    access={'access.tenant':tenant,'access.application':'commerce','access.environment':'test','access.manager':owner_member,'access.generation':1,'access.capabilities':read+','+write,
            'access.max-duration-seconds':3600,'access.operator':'mg07-fixture','access.command':uid(),**graph}
    for kind in ['POLICY','DIRECTORY']:h.private(run/(kind+'.properties'),db+h.props({**access,'projection.kind':kind}))
    cli('AccessBootstrapCli',[run/'POLICY.properties'])
    management=fixture['clients']['management'];business=fixture['clients']['business']
    authority={'issuer':h.ISSUER,'jwks.uri':h.ISSUER+'/.well-known/jwks','audience':management['name'],'client.id':management['name'],'client.secret':management['secret'],
               'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
    h.private(run/'admin.properties',db+h.props({**authority,**graph,'scope.graph.http':graph['graph.http'],'scope.graph.key':graph['graph.key']}))
    service,unapproved=secrets.token_urlsafe(48),secrets.token_urlsafe(48)
    server_config={'service.count':2,'access.check.callers':'commerce-nav','scope.check.callers':'commerce-nav,commerce-unapproved','scope.owner.commerce':'product',
                   'navigation.callers':'commerce-nav',**graph,'scope.graph.http':graph['graph.http'],'scope.graph.key':graph['graph.key']}
    for number,name,key in [(1,'commerce-nav',service),(2,'commerce-unapproved',unapproved)]:
        prefix='service.'+str(number)+'.';server_config.update({prefix+'id':name,prefix+'application-id':'commerce',prefix+'environment':'test',prefix+'operation':'context.resolve',prefix+'credential-sha256':hashlib.sha256(key.encode()).hexdigest()})
        server_config.update({prefix+'user.'+k:v for k,v in {**authority,'audience':business['name'],'client.id':business['name'],'client.secret':business['secret']}.items()})
    h.private(run/'server.properties',db+h.props(server_config));checks=[]
    def check(name,port,path,headers=(),payload=None,status=200,code=None):
        actual,result=h.request(port,path,headers,None if payload is None else json.dumps(payload).encode())
        if actual!=status or(code and result.get('code')!=code):raise RuntimeError(name+': unexpected status '+str(actual))
        checks.append({'check':name,'result':'PASS'});return result
    def mysql(statement):
        result=subprocess.run(['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names'],input=statement,text=True,capture_output=True,timeout=PROCESS_TIMEOUT_SECONDS)
        if result.returncode:raise RuntimeError('owned MySQL fixture operation failed; inspect private evidence')
        return result.stdout.strip()
    def pg(statement):
        result=subprocess.run(['docker','exec','-i','dev-infra-postgres16-1','sh','-c','exec psql -U "$POSTGRES_USER" -d '+database+' -v ON_ERROR_STOP=1 -At'],input=statement,text=True,capture_output=True,timeout=PROCESS_TIMEOUT_SECONDS)
        if result.returncode:raise RuntimeError('owned PG read failed')
        return result.stdout.strip()
    try:
        h.start(admin,ADMIN_PORT,run/'admin.log',config=run/'admin.properties',access=True)
        manager=a.token(h.ISSUER,fixture,'management','internal');user=a.token(h.ISSUER,fixture,'business','external');manager_business=a.token(h.ISSUER,fixture,'business','internal')
        part={'tenant_id':tenant,'application_id':'commerce','environment':'test'};mh=[('Authorization','Bearer '+manager)]
        check('enable strict isolated partition',ADMIN_PORT,'/api/governance/v1/access/enable-strict',mh,{**part,'command_id':uid()},202)
        role=check('create fixed isolated reader',ADMIN_PORT,'/api/governance/v1/access/roles',mh,{**part,'command_id':uid(),'role_code':'reader','role_version':1,'capabilities':[read]})
        grant=check('create isolated scoped source',ADMIN_PORT,'/api/governance/v1/access/scoped-grants',mh,{**part,'command_id':uid(),'member_id':member,'member_generation':1,'role_id':role['id'],
                    'scope_rule':{'version':1,'resource_type':'product','clauses':[{'kind':'SPECIFIED_STORES','values':['S1'],'include_root':False}]},'source_id':uid(),'valid_from':stamp(-1),'valid_to':stamp(1800)},202)
        for kind in ['POLICY','DIRECTORY']:cli('ReliableProjectionCli',[run/(kind+'.properties')])
        h.start(server,SERVER_PORT,run/'server.log',config=run/'server.properties',access=True,scope=True,navigation=True)
        path='/internal/governance/v1/access/navigation';headers=[('Authorization','Bearer '+service),('X-User-Access-Token',user)];request={'tenant_id':tenant,'expected_membership_generation':1,'request_id':uid()}
        before=pg("SELECT row_to_json(t)::text FROM auth_governance.access_grant t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.role_version t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.application_catalog t ORDER BY application_id; SELECT count(*) FROM auth_governance.execution_reference;")
        view=check('real business audience and scope return本人',SERVER_PORT,path,headers,request)
        expected=['group.catalog','menu.operations.products','menu.collaboration.products','products'] if ui else ['group.products','page.products','page.collaboration']
        if view['state']!='AVAILABLE' or view['capability_hints']!=[read] or [menu['code'] for menu in view['menus']]!=expected or view['menus'][0]['route'] is not None:raise RuntimeError('wrong navigation facts')
        check('anonymous refused',SERVER_PORT,path,(),request,401,'INVALID_CREDENTIAL')
        check('unapproved registered caller refused',SERVER_PORT,path,[('Authorization','Bearer '+unapproved),('X-User-Access-Token',user)],request,403,'ACCESS_DENIED')
        check('management audience refused',SERVER_PORT,path,[('Authorization','Bearer '+service),('X-User-Access-Token',manager)],request,401,'INVALID_CREDENTIAL')
        check('principal injection refused',SERVER_PORT,path,headers,{**request,'principal_id':owner},400,'INVALID_ARGUMENT')
        check('environment injection refused',SERVER_PORT,path,headers,{**request,'environment':'prod'},400,'INVALID_ARGUMENT')
        check('generation mismatch refused',SERVER_PORT,path,headers,{**request,'expected_membership_generation':2},403,'GENERATION_MISMATCH')
        check('foreign tenant refused',SERVER_PORT,path,headers,{**request,'tenant_id':uid()},403,'MEMBERSHIP_UNAVAILABLE')
        empty=check('manager membership grants no implicit business navigation',SERVER_PORT,path,[('Authorization','Bearer '+service),('X-User-Access-Token',manager_business)],request)
        if empty['state']!='NO_ACCESS' or empty['menus']:raise RuntimeError('manager obtained business navigation')
        if before!=pg("SELECT row_to_json(t)::text FROM auth_governance.access_grant t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.role_version t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.application_catalog t ORDER BY application_id; SELECT count(*) FROM auth_governance.execution_reference;"):raise RuntimeError('navigation mutated auth facts')
        suffix=secrets.token_hex(6);mysql_db='commerce_nav_'+suffix;mysql_user='nav_'+suffix;password=secrets.token_hex(24);local='nav-'+suffix
        mysql(f"CREATE DATABASE {mysql_db} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE USER '{mysql_user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {mysql_db}.* TO '{mysql_user}'@'%';")
        env=dict(os.environ,COMMERCE_DB_URL=f'jdbc:mysql://127.0.0.1:43306/{mysql_db}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',COMMERCE_DB_USER=mysql_user,COMMERCE_DB_PASSWORD=password,
                 COMMERCE_ADDRESS_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),COMMERCE_PORT=str(COMMERCE_PORT),COMMERCE_WORKERS_ENABLED='false',COMMERCE_SANDBOX_ENABLED='false')
        h.private(run/'commerce-environment.json',json.dumps({k:v for k,v in env.items() if k.startswith('COMMERCE_')}))
        h.private(run/'consumer.properties',h.props({'central.url':'http://127.0.0.1:'+str(SERVER_PORT),'central.credential':service,'central.application':'commerce','central.environment':'test'}))
        def start_commerce(label,environment):
            """migration owner只指向本工具新建数据库；运行节点随后使用受限账号。"""
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',COMMERCE_PORT))
            with (run/(label+'.log')).open('w') as log:
                process=subprocess.Popen(['java','-Xmx384m','-jar',str(commerce_jar),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled=true','--commerce.iam.navigation.enabled=true','--commerce.iam.scope.enabled='+str(ui).lower(),'--commerce.iam.store-read.configuration='+str(run/'consumer.properties')],cwd=COMMERCE,env=environment,stdout=log,stderr=subprocess.STDOUT)
            h.PROCESSES.append(process)
            for _ in range(120):
                if process.poll() is not None:raise RuntimeError('owned Commerce startup failed: '+label)
                try:
                    if h.request(COMMERCE_PORT,'/actuator/health')[0]==200:return process
                except (OSError,ValueError):pass
                time.sleep(.25)
            raise RuntimeError('owned Commerce startup timed out: '+label)
        # binlog开启时既有触发器迁移需要迁移Owner；不授SUPER给运行账号，也不改共享全局开关。
        root_password=subprocess.run(['docker','exec','dev-infra-mysql84-1','sh','-c','printf %s "$MYSQL_ROOT_PASSWORD"'],capture_output=True,text=True,check=True,timeout=PROCESS_TIMEOUT_SECONDS).stdout
        if not root_password or not env['COMMERCE_DB_URL'].startswith('jdbc:mysql://127.0.0.1:43306/'+mysql_db+'?'):raise RuntimeError('owned migration target required')
        migration=start_commerce('commerce-migration-owner',dict(env,COMMERCE_DB_USER='root',COMMERCE_DB_PASSWORD=root_password))
        h.stop(migration);root_password=None
        start_commerce('commerce',env)
        bh=[('Authorization','Bearer '+user),('X-Tenant-Id',tenant)];business_path='/v1/operations/navigation'
        check('missing explicit local mapping refused',COMMERCE_PORT,business_path,bh,status=403,code='FORBIDDEN')
        mysql(f"USE {mysql_db}; INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{hashlib.sha256(secrets.token_bytes(48)).hexdigest()}','{local}','operator','OPERATOR',UTC_TIMESTAMP()+INTERVAL 1 HOUR); INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES('{tenant}','{member_principal}','{member}',1,'{local}','operator','mg07-isolated');")
        result=check('actual Commerce navigation uses SDK and owned MySQL binding',COMMERCE_PORT,business_path,bh)
        if result.get('state')!='AVAILABLE' or result.get('capabilityHints')!=[read] or result.get('context',{}).get('principalId')!=member_principal:raise RuntimeError('Commerce navigation DTO mismatch')
        check('Commerce anonymous refused',COMMERCE_PORT,business_path,status=401,code='UNAUTHENTICATED')
        check('Commerce wrong audience refused',COMMERCE_PORT,business_path,[('Authorization','Bearer '+manager),('X-Tenant-Id',tenant)],status=401,code='UNAUTHENTICATED')
        check('Commerce injection query refused',COMMERCE_PORT,business_path+'?principal_id='+owner,bh,status=400,code='INVALID_ARGUMENT')
        check('Commerce old generation refused',COMMERCE_PORT,business_path+'?expected_membership_generation=2',bh,status=403,code='FORBIDDEN')
        browser=None
        if ui:
            mysql(f"""USE {mysql_db};
INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{hashlib.sha256(secrets.token_bytes(48)).hexdigest()}','{local}','manager','OPERATOR',UTC_TIMESTAMP()+INTERVAL 1 HOUR);
INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES('{tenant}','{owner}','{owner_member}',1,'{local}','manager','mg08-isolated');
INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES('{local}','M','导航验收商家');
INSERT INTO store_record(tenant_id,store_id,merchant_id,name) VALUES('{local}','S1','M','授权门店'),('{local}','S2','M','未授权门店');
INSERT INTO catalog_product(tenant_id,product_id,store_id,title,category,brand) VALUES('{local}','P1','S1','导航验收商品','日用','验收品牌'),('{local}','P2','S2','保密商品','日用','其他品牌');""")
            helper=module('navigation_ui','governance-business-navigation-ui.py')
            browser=helper.NavigationUi(h,run,fixture,tenant,user,manager_business,manager)
            browser.browser('active')
            writer=check('create isolated second capability role',ADMIN_PORT,'/api/governance/v1/access/roles',mh,{**part,'command_id':uid(),'role_code':'writer','role_version':1,'capabilities':[write]})
            second=check('create isolated second scoped source',ADMIN_PORT,'/api/governance/v1/access/scoped-grants',mh,{**part,'command_id':uid(),'member_id':member,'member_generation':1,'role_id':writer['id'],
                'scope_rule':{'version':1,'resource_type':'product','clauses':[{'kind':'SPECIFIED_STORES','values':['S1'],'include_root':False}]},'source_id':uid(),'valid_from':stamp(-1),'valid_to':stamp(1800)},202)
            for kind in ['POLICY','DIRECTORY']:cli('ReliableProjectionCli',[run/(kind+'.properties')])
            browser.browser('multi')
            check('revoke isolated second capability',ADMIN_PORT,'/api/governance/v1/access/revoke',mh,{**part,'command_id':uid(),'grant_id':second['id'],'expected_version':1})
            for kind in ['POLICY','DIRECTORY']:cli('ReliableProjectionCli',[run/(kind+'.properties')])
        check('revoke fixture source',ADMIN_PORT,'/api/governance/v1/access/revoke',mh,{**part,'command_id':uid(),'grant_id':grant['id'],'expected_version':1})
        check('pending policy does not preserve old menus',COMMERCE_PORT,business_path,bh,status=503,code='UNAVAILABLE')
        if browser:browser.browser('pending')
        for kind in ['POLICY','DIRECTORY']:cli('ReliableProjectionCli',[run/(kind+'.properties')])
        if check('after revocation real navigation is empty',COMMERCE_PORT,business_path,bh)['state']!='NO_ACCESS':raise RuntimeError('revoked menus retained')
        if browser:browser.browser('revoked')
        h.stop(next(p for p in h.PROCESSES if p.args and str(server) in p.args))
        check('dependency failure has no old navigation fallback',COMMERCE_PORT,business_path,bh,status=503,code='UNAVAILABLE')
        if browser:browser.browser('outage');browser.browser('finish');browser.shared()
        h.private(run/'http-result.json',json.dumps({'status':'PASS','checks':checks,'scope':'new owned PG/MySQL only','business_navigation_writes':0,'original_application_writes':0},ensure_ascii=False,indent=2))
        print('PASS: real Auth/SDK/Commerce navigation checks='+str(len(checks))+'; evidence='+str(run))
    finally:
        h.private(run/'checks-progress.json',json.dumps(checks,ensure_ascii=False,indent=2))
        for process in h.PROCESSES:h.stop(process)

if __name__=='__main__':main()
