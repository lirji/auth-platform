#!/usr/bin/env python3
"""P6所选商城租户隔离演练；只写本工具新建库，原commerce_local保持只读。"""
import argparse, base64, hashlib, http.client, importlib.util, json, os, secrets, shutil, socket, subprocess, time, uuid, urllib.parse, zipfile
from datetime import datetime, timedelta, timezone
from http import HTTPStatus
from pathlib import Path

MERCHANT_RESOURCE_TYPE = 'merchant'
MEMBER_RESOURCE_TYPE = 'commerce_member'
MEMBER_POLICY_RESOURCE_TYPE = 'commerce_member_policy'
POINT_OFFER_RESOURCE_TYPE = 'point_offer'
COUPON_DEFINITION_RESOURCE_TYPE = 'coupon_definition'
ENTITLEMENT_DEFINITION_RESOURCE_TYPE = 'entitlement_definition'
ENTITLEMENT_RESOURCE_TYPE = 'entitlement'
ENTITLEMENT_CAPABILITIES = {'entitlement_definition.read':ENTITLEMENT_DEFINITION_RESOURCE_TYPE,'entitlement_definition.create':ENTITLEMENT_DEFINITION_RESOURCE_TYPE,'entitlement.read':ENTITLEMENT_RESOURCE_TYPE,'entitlement.resolve':ENTITLEMENT_RESOURCE_TYPE}
COUPON_DEFINITION_CAPABILITIES = ('coupon_definition.read','coupon_definition.create')
OFFER_CAPABILITIES = ('point_offer.read','point_offer.define','point_offer.status.update')
POINTS_CAPABILITIES = {'points.policy.read':MEMBER_POLICY_RESOURCE_TYPE,'points.policy.publish':MEMBER_POLICY_RESOURCE_TYPE,'points.read':MEMBER_RESOURCE_TYPE,'points.adjust':MEMBER_RESOURCE_TYPE,'points.expire':MEMBER_RESOURCE_TYPE}
CYCLE_CAPABILITIES = {'member_cycle.policy.read':MEMBER_POLICY_RESOURCE_TYPE,'member_cycle.policy.publish':MEMBER_POLICY_RESOURCE_TYPE,'member_cycle.read':MEMBER_RESOURCE_TYPE,'member_cycle.evaluate':MEMBER_RESOURCE_TYPE,'cycle_benefit.read':MEMBER_POLICY_RESOURCE_TYPE,'cycle_benefit.define':MEMBER_POLICY_RESOURCE_TYPE,'cycle_benefit.grant':MEMBER_RESOURCE_TYPE}
BEHAVIOR_CAPABILITIES = ('member_behavior.read','member_behavior.update','member_behavior.rebuild')
TAG_CAPABILITIES = ('member_tag.read','member_tag.define','member_tag.assign')
GROWTH_CAPABILITIES = {'growth.policy.read':MEMBER_POLICY_RESOURCE_TYPE,'growth.policy.publish':MEMBER_POLICY_RESOURCE_TYPE,'growth.read':MEMBER_RESOURCE_TYPE,'growth.adjust':MEMBER_RESOURCE_TYPE,'growth.recalculate':MEMBER_RESOURCE_TYPE}


def module(name, path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


def verify_auth_runtime(root):
    # Maven增量repackage可能复用旧嵌套依赖；在创建隔离资源前核验实际运行制品。
    for application in ('admin','server'):
        runtime=root/f'auth-platform-{application}/target/auth-platform-{application}-0.1.0-SNAPSHOT.jar'
        with zipfile.ZipFile(runtime) as archive:
            for module in ('protocol','core','governance'):
                name=f'auth-platform-{module}-0.1.0-SNAPSHOT.jar'
                current=root/f'auth-platform-{module}/target'/name
                if hashlib.sha256(archive.read('BOOT-INF/lib/'+name)).digest()!=hashlib.sha256(current.read_bytes()).digest():
                    raise RuntimeError(f'{application} runtime contains stale {module}; rebuild with -Dmaven.jar.forceCreation=true package')


def rehearse(args, isolation=None):
    root=Path(__file__).resolve().parents[1];commerce=Path(args.commerce_root).resolve()
    migration=module('p6_snapshot',root/'deploy/governance-p6-migration.py')
    up=module('p6_access',root/'deploy/governance-access-smoke.py');h=up.h
    selected=json.loads((root/'docs/implementation/oa-auth/phase-6/P6_SELECTED_UNIT.json').read_text())
    snapshot,_=migration.read_json(root/selected['source_snapshot'],selected['source_sha256'])
    source=selected['source_tenant'];migration.identifier(source)
    suffix=secrets.token_hex(6);run=root/'.local/governance/p6'/('rehearsal-'+suffix);run.mkdir(mode=0o700)
    if isolation is None:
        subprocess.run(['python3',str(root/'deploy/governance-test-db.py'),'--directory',str(run/'database')],check=True,stdout=subprocess.DEVNULL)
        dbfile=run/'database/database.properties'
        fixture_path=root/'.local/governance/p2/identity/casdoor.json';management_path=root/'.local/governance/casdoor-isolated/management-client.json'
    else:
        dbfile=isolation.db['config'];h.ISSUER=isolation.issuer
        fixture_path=isolation.run/'identity/casdoor.json';management_path=isolation.management_config
        h.private(run/'identity-evidence.json',json.dumps({'mode':'DEDICATED_IDP_AND_PG','resources':str(isolation.run),'issuer':isolation.issuer}))
    db=h.read_private(dbfile)
    fixture=json.loads(h.read_private(fixture_path));ops=json.loads(h.read_private(management_path))
    graph=h.read_private(root/'.local/governance/p3/graph/graph.properties');legacy=h.read_private(root/'.local/governance/p2/graph/graph.properties')
    jars={k:root/f'auth-platform-{k}/target/auth-platform-{k}-0.1.0-SNAPSHOT.jar' for k in ('admin','server')}
    local_jar=run/'commerce.jar';shutil.copy2(commerce/'commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar',local_jar)
    tenant=str(uuid.uuid5(uuid.NAMESPACE_URL,'p6-isolated:'+source));env='local-p6';partition={'tenant_id':tenant,'application_id':'commerce','environment':env}
    checks=[];processes=[]
    def uid():return str(uuid.uuid4())
    def now(seconds=0):return (datetime.now(timezone.utc)+timedelta(seconds=seconds)).isoformat(timespec='milliseconds').replace('+00:00','Z')
    def record(name):
        checks.append({'check':name,'result':'PASS'});h.private(run/('checkpoint-%03d.json'%len(checks)),json.dumps(checks,ensure_ascii=False))
    def cli(name,*arguments,expected=0):
        package='com.lrj.authz.admin.governance.' if name=='ReliableProjectionCli' else 'com.lrj.authz.governance.cli.'
        file=run/(name+'-'+secrets.token_hex(4)+'.log')
        with os.fdopen(os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            result=subprocess.run(['java','-Dloader.main='+package+name,'-cp',str(jars['admin']),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,arguments)],stdout=log,stderr=subprocess.STDOUT,timeout=45)
        if result.returncode!=expected:raise RuntimeError('CLI '+name+' unexpected exit '+str(result.returncode)+' private log '+file.name)
    def request(port,path,headers=(),body=None):
        c=http.client.HTTPConnection('127.0.0.1',port,timeout=35)
        try:
            data=None if body is None else json.dumps(body).encode();hs=dict(headers)
            if data is not None:hs['Content-Type']='application/json'
            c.request('GET' if data is None else 'POST',path,data,hs);r=c.getresponse();raw=r.read(1048577)
            if len(raw)>1048576:raise RuntimeError('response too large')
            return r.status,json.loads(raw) if raw else {}
        finally:c.close()
    def expect(name,port,path,headers=(),body=None,status=200):
        actual,value=request(port,path,headers,body)
        if actual!=status:
            h.private(run/'failed-response.json',json.dumps({'check':name,'status':actual,'code':value.get('code'),'message':value.get('message')} if isinstance(value,dict) else {'check':name,'status':actual}))
            raise RuntimeError(name+': expected '+str(status)+' got '+str(actual)+' code='+str(value.get('code') if isinstance(value,dict) else 'array'))
        record(name);return value
    sql_cmd=['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names']
    database='commerce_iam_p6_'+suffix
    def sql(statement,allow_failure=False):
        # 库名仅由工具随机生成；这里没有指向原运行商城的写入口。
        result=subprocess.run(sql_cmd,input='USE '+database+';\n'+statement,text=True,capture_output=True,timeout=20)
        if result.returncode and not allow_failure:raise RuntimeError('owned MySQL operation failed')
        return result.returncode,result.stdout.strip()
    def q(value):
        if value is None:return 'NULL'
        if type(value) in (int,bool):return str(int(value))
        return "CONVERT(0x"+str(value).encode().hex()+" USING utf8mb4)"
    def insert(table,row):
        code,_=sql('INSERT INTO '+table+'('+','.join(row)+') VALUES('+','.join(q(v) for v in row.values())+');');return code
    dbuser='iam_p6_'+suffix;password=secrets.token_hex(24)
    create=subprocess.run(sql_cmd,input=f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;CREATE USER '{dbuser}'@'%' IDENTIFIED BY '{password}';GRANT ALL ON {database}.* TO '{dbuser}'@'%';",text=True,capture_output=True,timeout=20)
    if create.returncode:raise RuntimeError('owned MySQL create failed')
    inspect=json.loads(subprocess.check_output(['docker','inspect','dev-infra-mysql84-1'],text=True))[0]
    owner_password=next(v.split('=',1)[1] for v in inspect['Config']['Env'] if v.startswith('MYSQL_ROOT_PASSWORD='))
    runtime=dict(os.environ,COMMERCE_DB_URL=f'jdbc:mysql://127.0.0.1:43306/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',COMMERCE_DB_USER=dbuser,COMMERCE_DB_PASSWORD=password,COMMERCE_ADDRESS_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),COMMERCE_PORT='18661',COMMERCE_WORKERS_ENABLED='false',COMMERCE_SANDBOX_ENABLED='false')
    h.private(run/'commerce-environment.json',json.dumps({k:v for k,v in runtime.items() if k.startswith('COMMERCE_')}))
    def start_commerce(label,migrate=False):
        with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',18661))
        process_env=dict(runtime)
        if migrate:process_env.update(SPRING_FLYWAY_USER='root',SPRING_FLYWAY_PASSWORD=owner_password)
        with os.fdopen(os.open(run/(label+'.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            p=subprocess.Popen(['java','-Xmx512m','-jar',str(local_jar),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled=true','--commerce.iam.catalog.enabled=true','--commerce.iam.employee.enabled='+str(args.inventory).lower(),'--commerce.iam.store-read.configuration='+str(run/'consumer.properties')],env=process_env,stdout=log,stderr=subprocess.STDOUT)
        processes.append(p)
        for _ in range(90):
            if p.poll() is not None:raise RuntimeError('commerce startup failed: '+label)
            try:
                if request(18661,'/actuator/health')[0]==200:return p
            except OSError:pass
            time.sleep(.25)
        raise RuntimeError('commerce startup timeout')
    try:
        members={};principals={}
        for kind in ('internal','external'):
            principals[kind]=str(uuid.uuid5(uuid.NAMESPACE_URL,'p6:'+fixture['users'][kind]['id']));members[kind]=uid()
            values={'command.id':uid(),'operator.ref':'p6-isolated-fixture','tenant.id':tenant,'tenant.code':'p6-'+tenant,'principal.id':principals[kind],'issuer':h.ISSUER,'subject':fixture['users'][kind]['id'],'membership.id':members[kind],'valid.from':'2020-01-01T00:00:00Z','source.system':'p6-isolated-fixture','source.tenant.ref':source,'source.subject.ref':kind}
            file=run/(kind+'.properties');h.private(file,h.props(values));cli('GovernanceCli','bootstrap',dbfile,file)
        catalog={'catalog.application':'commerce','catalog.owner-principal':principals['internal'],'catalog.entry-origin':'http://127.0.0.1:18661','catalog.operator':'p6-fixture','catalog.command':uid(),'catalog.owner-issuer':h.ISSUER,'catalog.owner-subject':fixture['users']['internal']['id']}
        h.private(run/'catalog.properties',db+h.props(catalog));cli('CatalogCli','register',run/'catalog.properties','configured')
        manifest={'schema_version':'1','application':'commerce','manifest_version':1,'capabilities':[{'code':'commerce.catalog.operate','resource_type':'store','risk_level':'HIGH'}],'menus':[]}
        if args.inventory:
            manifest['capabilities'] += [{'code':'commerce.inventory.'+action,'resource_type':'store','risk_level':'NORMAL' if action=='read' else 'HIGH'} for action in ('read','receive')]
        if args.directory:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':'merchant' if code.startswith('merchant.') else 'store','risk_level':'HIGH' if code.endswith('.create') else 'NORMAL'} for code in ('merchant.read','merchant.create','store.directory.read','store.create')]
        if args.member:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':MEMBER_RESOURCE_TYPE,'risk_level':'HIGH'} for code in ('member.read','member.create','member.profile.update','member.status.update')]
        if args.growth:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':kind,'risk_level':'HIGH'} for code,kind in GROWTH_CAPABILITIES.items()]
        if args.tags:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':MEMBER_RESOURCE_TYPE,'risk_level':'HIGH'} for code in TAG_CAPABILITIES]
        if args.behavior:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':MEMBER_RESOURCE_TYPE,'risk_level':'HIGH'} for code in BEHAVIOR_CAPABILITIES]
        if args.cycles:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':kind,'risk_level':'HIGH'} for code,kind in CYCLE_CAPABILITIES.items()]
        if args.points:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':kind,'risk_level':'HIGH'} for code,kind in POINTS_CAPABILITIES.items()]
        if args.offers:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':POINT_OFFER_RESOURCE_TYPE,'risk_level':'NORMAL' if code.endswith('.read') else 'HIGH'} for code in OFFER_CAPABILITIES]
        if args.coupon_definitions:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':COUPON_DEFINITION_RESOURCE_TYPE,'risk_level':'NORMAL' if code.endswith('.read') else 'HIGH'} for code in COUPON_DEFINITION_CAPABILITIES]
        if args.entitlements:
            manifest['capabilities'] += [{'code':'commerce.'+code,'resource_type':kind,'risk_level':'HIGH'} for code,kind in ENTITLEMENT_CAPABILITIES.items()]
        h.private(run/'manifest.json',json.dumps(manifest));cli('CatalogCli','publish',run/'catalog.properties',run/'manifest.json')
        access={'access.tenant':tenant,'access.application':'commerce','access.environment':env,'access.manager':members['internal'],'access.generation':1,'access.capabilities':'commerce.catalog.operate','access.max-duration-seconds':3600,'access.operator':'p6-fixture','access.command':uid()}
        if args.inventory:access['access.capabilities'] += ',commerce.inventory.read,commerce.inventory.receive'
        if args.directory:access['access.capabilities'] += ',commerce.merchant.read,commerce.merchant.create,commerce.store.directory.read,commerce.store.create'
        if args.member:access['access.capabilities'] += ',commerce.member.read,commerce.member.create,commerce.member.profile.update,commerce.member.status.update'
        if args.growth:access['access.capabilities'] += ''.join(',commerce.'+code for code in GROWTH_CAPABILITIES)
        if args.tags:access['access.capabilities'] += ''.join(',commerce.'+code for code in TAG_CAPABILITIES)
        if args.behavior:access['access.capabilities'] += ''.join(',commerce.'+code for code in BEHAVIOR_CAPABILITIES)
        if args.cycles:access['access.capabilities'] += ''.join(',commerce.'+code for code in CYCLE_CAPABILITIES)
        if args.points:access['access.capabilities'] += ''.join(',commerce.'+code for code in POINTS_CAPABILITIES)
        if args.offers:access['access.capabilities'] += ''.join(',commerce.'+code for code in OFFER_CAPABILITIES)
        if args.coupon_definitions:access['access.capabilities'] += ''.join(',commerce.'+code for code in COUPON_DEFINITION_CAPABILITIES)
        if args.entitlements:access['access.capabilities'] += ''.join(',commerce.'+code for code in ENTITLEMENT_CAPABILITIES)
        h.private(run/'access.properties',db+h.props(access));cli('AccessBootstrapCli',run/'access.properties')
        def authority(kind):
            c=fixture['clients'][kind];return {'issuer':h.ISSUER,'jwks.uri':h.ISSUER+'/.well-known/jwks','audience':c['name'],'client.id':c['name'],'client.secret':c['secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
        graph_settings=''.join('scope.'+line+'\n' for line in graph.splitlines() if line.startswith('graph.'))
        h.private(run/'admin.properties',db+legacy+graph_settings+h.props(authority('management')))
        service=secrets.token_urlsafe(48);server={'service.count':1,'service.1.id':'commerce-p6','service.1.application-id':'commerce','service.1.environment':env,'service.1.operation':'context.resolve','service.1.credential-sha256':hashlib.sha256(service.encode()).hexdigest(),'access.check.callers':'commerce-p6','scope.check.callers':'commerce-p6','execution.callers':'commerce-p6','scope.owner.commerce':'store,product'}
        if args.directory:server['scope.owner.commerce']='store,product,merchant'
        if args.member:server['scope.owner.commerce'] += ','+MEMBER_RESOURCE_TYPE
        if args.growth:server['scope.owner.commerce'] += ','+MEMBER_POLICY_RESOURCE_TYPE
        if args.offers:server['scope.owner.commerce'] += ','+POINT_OFFER_RESOURCE_TYPE
        if args.coupon_definitions:server['scope.owner.commerce'] += ','+COUPON_DEFINITION_RESOURCE_TYPE
        if args.entitlements:server['scope.owner.commerce'] += ','+ENTITLEMENT_DEFINITION_RESOURCE_TYPE+','+ENTITLEMENT_RESOURCE_TYPE
        server.update({'service.1.user.'+k:v for k,v in authority('business').items()});h.private(run/'server.properties',db+legacy+graph_settings+h.props(server))
        h.private(run/'consumer.properties',h.props({'central.url':'http://127.0.0.1:18161','central.credential':service,'central.application':'commerce','central.environment':env}))
        admin_token=up.token(h.ISSUER,fixture,'management','internal');user_token=up.token(h.ISSUER,fixture,'business','external')
        admin=[('Authorization','Bearer '+admin_token)];user=[('Authorization','Bearer '+user_token),('X-Tenant-Id',tenant)];dual=[('Authorization','Bearer '+service),('X-User-Access-Token',user_token)]
        h.private(run/'tokens.json',json.dumps({'admin':admin_token,'user':user_token}))
        h.start(jars['admin'],18162,run/'admin.log',config=run/'admin.properties',access=True,scope=True)
        auth=h.start(jars['server'],18161,run/'server.log',config=run/'server.properties',access=True,scope=True,executions=True)
        prefix='/api/governance/v1/access'
        expect('enable strict partition',18162,prefix+'/enable-strict',admin,{**partition,'command_id':uid(),'kind':None},202)
        role=expect('explicit full CATALOG role',18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'p6-catalog','role_version':1,'capabilities':['commerce.catalog.operate']})['id']
        app=start_commerce('commerce-migration-owner',True);h.stop(app);app=start_commerce('commerce-application-user')
        record('migrate with owner then restart without owner credential')
        rows={t:[r for r in values if r['tenant_id']==source] for t,values in snapshot['tables'].items()}
        for table in ('merchant_record','store_record'):
            for row in rows[table]:insert(table,{**row,'name':'P6 source snapshot'})
        source_tokens={}
        for row in rows['platform_credential']:
            old_token=secrets.token_urlsafe(48);source_tokens[row['actor_id']]=old_token
            insert('platform_credential',{**row,'token_hash':hashlib.sha256(old_token.encode()).hexdigest()})
        for row in rows['store_operator_grant']:insert('store_operator_grant',{**row,'reason':'P6 readonly snapshot clone'})
        store=rows['store_operator_grant'][0]['resource_id'];other='p6-outside';merchant=rows['store_record'][0]['merchant_id']
        insert('store_record',{'tenant_id':source,'store_id':other,'merchant_id':merchant,'name':'P6 outside proof'})
        legacy_token=secrets.token_urlsafe(48);admin_local=secrets.token_urlsafe(48)
        for actor,role_name,token in [('p6-proof-operator','OPERATOR',legacy_token),('p6-proof-admin','ADMIN',admin_local)]:
            insert('platform_credential',{'tenant_id':source,'actor_id':actor,'role':role_name,'token_hash':hashlib.sha256(token.encode()).hexdigest(),'expires_at':now(3600).replace('T',' ').replace('Z','')})
        insert('store_operator_grant',{'tenant_id':source,'grant_id':'p6-proof-grant','actor_id':'p6-proof-operator','resource_type':'STORE','resource_id':store,'permission':'CATALOG','reason':'separate synthetic positive proof'})
        insert('central_store_identity_binding',{'auth_tenant_id':tenant,'principal_id':principals['external'],'membership_id':members['external'],'generation':1,'tenant_id':source,'actor_id':'p6-proof-operator','created_by':'p6-isolated-proof'})
        insert('catalog_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'state':'SHADOW'})
        mapping={'format':'p6-commerce-mapping/v1','source_sha256':selected['source_sha256'],'source_tenant':source,'target':partition,'evaluation_at':now(),'actors':{r['actor_id']:{'principal_id':principals['internal'],'membership_id':members['internal'],'generation':1,'identity_evidence':'isolated synthetic principal for preserved-denial ledger; not a production human assertion'} for r in rows['store_operator_grant']},'permissions':{'CATALOG':{'capabilities':['commerce.catalog.operate'],'approved_by':'user-option-2','decision_ref':'CONTRACTS_P6_MIGRATION.md full CATALOG'}}}
        report=migration.dry_run(snapshot,selected['source_sha256'],mapping)
        if report['counts']!={'QUARANTINED_MAPPING':0,'PRESERVE_DENIAL':len(rows['store_operator_grant']),'IMPORT_CANDIDATE':0}:raise RuntimeError('actual source denial did not remain denied')
        h.private(run/'mapping.json',json.dumps(mapping));h.private(run/'dry-run.json',json.dumps(report));record('real snapshot mapped without renewing expired credentials')
        h.private(run/'import.properties',db+h.props({'migration.tenant':tenant,'migration.environment':env,'migration.issuer':h.ISSUER,'migration.subject':fixture['users']['internal']['id']}))
        scope={'version':1,'resource_type':'store','clauses':[{'kind':'SPECIFIED_STORES','values':[store],'include_root':False}]}
        items=[{'source_id':x['source_record'],'source_version':x['source_version'],'member_id':members['internal'],'generation':1,'role_id':role,'scope':x['scope_rule'],'valid_from':None,'valid_to':None,'deny':True,'reason':','.join(x['deny_constraints'])} for x in report['records']]
        items.append({'source_id':'synthetic-positive','source_version':0,'member_id':members['external'],'generation':1,'role_id':role,'scope':scope,'valid_from':now(-2),'valid_to':now(3000),'deny':False,'reason':'explicit finite synthetic rehearsal only'})
        unit='commerce-p6-'+hashlib.sha256(source.encode()).hexdigest()[:16]
        batch={'unit_id':unit,'sequence':1,'snapshot_hash':selected['source_sha256'],'mapping_hash':migration.digest(mapping),'partition':partition,'items':items}
        h.private(run/'batch-1.json',json.dumps(batch));cli('MigrationImportCli',run/'import.properties',run/'batch-1.json');cli('MigrationImportCli',run/'import.properties',run/'batch-1.json');record('real denial and separate finite proof imported idempotently')
        def projection():
            for kind in ('POLICY','DIRECTORY'):
                file=run/('projection-'+secrets.token_hex(4)+'.properties');h.private(file,db+graph+h.props({**access,'projection.kind':kind}));cli('ReliableProjectionCli',file)
        projection()
        def central_check(s):return {'check':{'tenant_id':tenant,'expected_membership_generation':1,'request_id':uid(),'capability':'commerce.catalog.operate','resource_type':'store'},'facts':{'tenant_id':tenant,'resource_type':'store','resource_id':s,'resource_version':0,'owner_principal_id':None,'department_id':None,'department_ancestors':[],'store_id':s,'supplier_id':None}}
        shadow=[]
        for s,expected in ((store,True),(other,False)):
            old,_=request(18661,'/v1/operations/products?storeId='+s,[('Authorization','Bearer '+legacy_token)])
            new,value=request(18161,'/internal/governance/v1/access/check-resource',dual,central_check(s))
            row={'store':s,'legacy_decision':'ALLOW' if old==200 else 'DENY' if old==403 else 'ERROR','central_decision':value.get('decision') if new==200 else 'ERROR'};shadow.append(row)
        if not migration.compare_shadow(shadow)['ready']:raise RuntimeError('shadow discrepancy blocks cutover')
        for discrepancy in ({'legacy_decision':'ALLOW','central_decision':'DENY'},{'legacy_decision':'DENY','central_decision':'ALLOW'},{'legacy_decision':'ERROR','central_decision':'ERROR'}):
            if migration.compare_shadow([discrepancy])['ready']:raise RuntimeError('shadow invalid comparison accepted')
        record('shadow inverse differences and dependency failures block cutover')
        h.private(run/'shadow.json',json.dumps(shadow));record('shadow positive and negative comparison; no OR decision')
        # 先冻结、再确认不可写；中央切换只发生在本工具新库。
        sql('UPDATE catalog_authority_route SET frozen=TRUE,version=version+1 WHERE tenant_id='+q(source)+" AND state='SHADOW' AND version=1;")
        code,_=sql('UPDATE store_operator_grant SET active=FALSE,version=version+1 WHERE tenant_id='+q(source)+';',True)
        if code==0:raise RuntimeError('legacy direct SQL was not frozen')
        record('old grant direct SQL frozen before authority cutover')
        sql("UPDATE catalog_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND state='SHADOW' AND frozen=TRUE AND version=2;")
        if sql('SELECT state FROM catalog_authority_route WHERE tenant_id='+q(source))[1]!='CENTRAL':raise RuntimeError('cutover CAS failed')
        record('single authority CAS switched only isolated selected tenant')
        for original in rows['store_operator_grant']:
            expect('original expired source identity remains denied',18661,'/v1/operations/products?storeId='+original['resource_id'],[('Authorization','Bearer '+source_tokens[original['actor_id']])],status=401)
        expect('old admin cannot bypass central guard',18661,'/v1/operations/products?storeId='+store,[('Authorization','Bearer '+admin_local)],status=403)
        expect('central cross-store denied',18661,'/v1/operations/products?storeId='+other,user,status=403)
        def browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-p6-catalog.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real central catalog browser '+phase)
        if args.browser:
            if isolation is not None:
                # 只调整本轮新建IdP应用，补固定回调以真实走浏览器PKCE；不改共享客户端。
                idp=module('p6_browser_idp',root/'deploy/governance-casdoor-fixture.py');idp.BASE=h.ISSUER
                management='Basic '+base64.b64encode((ops['client_id']+':'+ops['client_secret']).encode()).decode()
                query=urllib.parse.urlencode({'id':'admin/'+fixture['clients']['business']['name']})
                app_config=idp.request('get-application?'+query,authorization=management)['data']
                app_config.update(redirectUris=['http://127.0.0.1:18665/iam/callback'],enablePassword=True,enableSigninSession=True,signinMethods=[{'name':'Password','displayName':'Password','rule':'All'}])
                app_config['grantTypes']=sorted(set(app_config.get('grantTypes',[]))|{'authorization_code'})
                idp.request('update-application?'+query,app_config,authorization=management)
            h.private(run/'catalog-ui.json',json.dumps({'token':user_token,'authority':h.ISSUER,'client':fixture['clients']['business']['name'],'tenant':tenant,'store':store,'other':other,'interactive':isolation is not None,'user':fixture['users']['external'] if isolation else None}))
            pilot_class=module('p6_browser_start',root/'deploy/governance-p5-commerce.py').CommercePilot
            pilot=pilot_class.__new__(pilot_class)
            pilot.run,pilot.h=run,h
            pilot.start(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','18665','--strictPort'],commerce/'frontend','catalog-vite',18665,dict(os.environ,VITE_IAM_ENABLED='true',VITE_IAM_AUTHORITY=h.ISSUER,VITE_IAM_CLIENT_ID=fixture['clients']['business']['name'],COMMERCE_API_URL='http://127.0.0.1:18661'))
        def post(name,path,body,key=None):return expect(name,18661,'/v1/operations/'+path,user+[('Idempotency-Key',key or uid())],body)
        post('central create product','products',{'productId':'p6-product','storeId':store,'title':'P6 product','category':'C','brand':'B'})
        sku=post('central create sku','skus',{'skuId':'p6-sku','productId':'p6-product','storeId':store,'title':'P6 sku','unitPrice':'10.00','specifications':[{'name':'size','value':'S'}]})
        sku=post('central publish and price','skus/p6-sku',{'storeId':store,'expectedVersion':sku['revision'],'title':'P6 sku','unitPrice':'11.00','status':'ACTIVE','reason':'P6 publish'})
        post('central channel price','skus/p6-sku/channel-prices',{'storeId':store,'channel':'MINI_APP','expectedVersion':0,'unitPrice':'9.00','validFrom':now(-2),'validTo':now(600),'active':True,'reason':'P6 price'})
        post('central category','catalog-categories',{'categoryId':'p6-category','storeId':store,'parentId':None,'name':'P6 category'})
        post('central specification template','specification-templates',{'templateId':'p6-template','version':1,'storeId':store,'name':'P6 template','fields':[{'name':'size','values':['S','M']}]})
        post('central merchandising','products/p6-product/merchandising',{'storeId':store,'expectedVersion':0,'categoryId':'p6-category','templateId':None,'templateVersion':None,'description':'P6 details','images':[],'reason':'P6 metadata'})
        post('central barcode','skus/p6-sku/barcode',{'storeId':store,'expectedVersion':0,'barcode':'12345678','reason':'P6 barcode'})
        browser('active')
        def inventory_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce03-inventory.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real inventory browser '+phase)
        if args.inventory:
            # 只对本轮隔离库显式接管库存；CATALOG角色不自动获得库存能力。
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'INVENTORY','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='INVENTORY' AND state='SHADOW' AND version=1;")
            inventory_path='/v1/admin/inventory?storeId='+store
            receipt={'storeId':store,'skuId':'p6-sku','quantity':7}
            expect('CATALOG does not imply inventory read',18661,inventory_path,user,status=403)
            expect('old ADMIN cannot bypass inventory route',18661,inventory_path,[('Authorization','Bearer '+admin_local)],status=403)
            def inventory_grant(action):
                role_id=expect('explicit inventory '+action+' role',18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce03-inventory-'+action,'role_version':1,'capabilities':['commerce.inventory.'+action]})['id']
                return expect('finite isolated inventory '+action+' grant',18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role_id,'scope_rule':scope,'source_id':'ce03-'+action,'valid_from':now(-2),'valid_to':now(600)},202)['id']
            def inventory_ready(action):
                # 新授权允许双水位保守DENY；仅对只读就绪探测重试，撤权和业务写入均不重试。
                observations=[];stable=0
                for attempt in range(40):
                    body=central_check(store);body['check']['capability']='commerce.inventory.'+action
                    status,value=request(18161,'/internal/governance/v1/access/check-resource',dual,body)
                    decision=value.get('decision') if status==HTTPStatus.OK else value.get('code')
                    observation={'status':status,'decision':decision}
                    ready=status==HTTPStatus.OK and decision=='ALLOW'
                    if ready:
                        # 单次resource ALLOW不保证两个水位后的执行复核已经稳定；只签发/检查引用，不重试库存写入。
                        issue_status,ref=request(18161,'/internal/governance/v1/access/executions',dual,{'check':body['check'],'expires_at':now(45)})
                        observation['issue_status']=issue_status
                        if issue_status==HTTPStatus.OK:
                            body['check']['request_id']=uid()
                            check_status,checked=request(18161,'/internal/governance/v1/access/execution-check',dual,{'execution_id':ref['execution_id'],'resource':body})
                            decision=checked.get('decision') if check_status==HTTPStatus.OK else checked.get('code')
                            observation.update(execution_status=check_status,execution_decision=decision)
                            ready=check_status==HTTPStatus.OK and decision=='ALLOW'
                        else:
                            decision=ref.get('code');observation['issue_decision']=decision;ready=False
                    observations.append(observation)
                    if not ready and decision not in ('DENY','ACCESS_DENIED','AUTHZ_STATE_NOT_READY'):raise RuntimeError('unexpected inventory readiness response: '+str(decision))
                    stable=stable+1 if ready else 0
                    if stable>=4:
                        h.private(run/('inventory-'+action+'-readiness.json'),json.dumps(observations));record('inventory '+action+' resource and execution checks reach stable readiness');return
                    time.sleep(.25)
                h.private(run/('inventory-'+action+'-readiness.json'),json.dumps(observations))
                raise RuntimeError('inventory '+action+' grant not ready within bounded probes')
            inventory_grant('read');projection();inventory_ready('read')
            expect('inventory read is scoped before pagination',18661,inventory_path,user)
            expect('inventory read cannot receive',18661,'/v1/admin/inventory/receipts',user+[('Idempotency-Key',uid())],receipt,403)
            inventory_browser('readonly')
            writer=inventory_grant('receive');projection();inventory_ready('receive')
            receipt_headers=user+[('Idempotency-Key',uid())]
            first=expect('real central inventory receive',18661,'/v1/admin/inventory/receipts',receipt_headers,receipt)
            replay=expect('inventory command retries once',18661,'/v1/admin/inventory/receipts',receipt_headers,receipt)
            if first['available']!=7 or replay!=first:raise RuntimeError('inventory idempotency failed')
            inventory_browser('write')
            inventory_expected = 10 if args.browser else 7
            inventory_audits = 2 if args.browser else 1
            expect('inventory cross store denied',18661,'/v1/admin/inventory?storeId='+other,user,status=403)
            expect('inventory cross tenant denied',18661,inventory_path,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            if sql('SELECT COUNT(*) FROM employee_command_identity WHERE tenant_id='+q(source)+' AND principal_id='+q(principals['external'])+' AND membership_id='+q(members['external'])+' AND generation=1')[1]!=str(inventory_audits):raise RuntimeError('inventory identity audit not atomic')
            record('inventory command audit binds central principal member generation')
            expect('revoke inventory receive independently',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':writer,'expected_version':1});projection()
            expect('revoked inventory write and old receipt are denied',18661,'/v1/admin/inventory/receipts',receipt_headers,receipt,403)
            stocks=expect('inventory read survives independent write revocation',18661,inventory_path,user)
            if len(stocks)!=1 or stocks[0]['available']!=inventory_expected:raise RuntimeError('revoked inventory changed stock')
            inventory_browser('revoked-write')
        def directory_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce03-directory.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real directory browser '+phase)
        if args.directory:
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'DIRECTORY','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='DIRECTORY' AND state='SHADOW' AND version=1;")
            merchant=sql('SELECT merchant_id FROM store_record WHERE tenant_id='+q(source)+' AND store_id='+q(store))[1]
            def directory_grant(code,full=False):
                resource='merchant' if code.startswith('merchant.') else 'store'
                role_id=expect('explicit directory role '+code+str(full),18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce03-'+code.replace('.','-')+('-full' if full else '-limited'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                rule={'version':1,'resource_type':resource,'clauses':[{'kind':'TENANT_ALL' if full else 'SPECIFIED_RESOURCES' if resource==MERCHANT_RESOURCE_TYPE else 'SPECIFIED_STORES','values':[] if full else [merchant if resource==MERCHANT_RESOURCE_TYPE else store],'include_root':False}]}
                return expect('finite directory grant '+code+str(full),18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role_id,'scope_rule':rule,'source_id':'ce03-'+code+str(full),'valid_from':now(-2),'valid_to':now(600)},202)['id']
            def execution_ready(code, phase='initial', resource_type=None):
                observations=[];stable=0
                for attempt in range(40):
                    check={'tenant_id':tenant,'expected_membership_generation':1,'request_id':uid(),'capability':'commerce.'+code,'resource_type':resource_type or ('merchant' if code.startswith('merchant.') else 'store')}
                    status,ref=request(18161,'/internal/governance/v1/access/executions',dual,{'check':check,'expires_at':now(45)})
                    result={'issue_status':status};decision=ref.get('code')
                    if status==HTTPStatus.OK:
                        check['request_id']=uid();status,plan=request(18161,'/internal/governance/v1/access/execution-scope',dual,{'execution_id':ref['execution_id'],'check':check})
                        decision=plan.get('decision') if status==HTTPStatus.OK else plan.get('code');result.update(scope_status=status,decision=decision)
                    observations.append(result)
                    allowed=status==HTTPStatus.OK and decision=='ALLOW';stable=stable+1 if allowed else 0
                    if stable>=4:
                        h.private(run/('execution-'+code+'-'+phase+'-ready.json'),json.dumps(observations));record('employee scope ready '+code);return
                    if not allowed and decision not in ('DENY','ACCESS_DENIED','AUTHZ_STATE_NOT_READY'):raise RuntimeError('unexpected employee readiness decision '+str(decision))
                    time.sleep(.25)
                h.private(run/('execution-'+code+'-'+phase+'-ready.json'),json.dumps(observations));raise RuntimeError('employee scope not ready '+code)
            expect('CATALOG and inventory do not imply directory',18661,'/v1/admin/merchants',user,status=403)
            expect('old ADMIN cannot bypass directory route',18661,'/v1/admin/stores',[('Authorization','Bearer '+admin_local)],status=403)
            directory_readers={code:directory_grant(code) for code in ('merchant.read','store.directory.read')}
            projection()
            for code in ('merchant.read','store.directory.read'):execution_ready(code)
            merchants=expect('merchant directory scoped before LIMIT',18661,'/v1/admin/merchants?limit=1',user)
            stores=expect('store directory scoped before LIMIT',18661,'/v1/admin/stores?limit=1',user)
            if len(merchants)!=1 or merchants[0]['merchantId']!=merchant or len(stores)!=1 or stores[0]['storeId']!=store:raise RuntimeError('directory range failed')
            if args.browser:
                f=json.loads((run/'catalog-ui.json').read_text());f['merchant']=merchant;h.private(run/'directory-ui.json',json.dumps(f))
                directory_browser('readonly')
            expect('directory foreign tenant denied',18661,'/v1/admin/stores',[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            for code in ('merchant.create','store.create'):directory_grant(code)
            projection()
            create_merchant={'merchantId':'ce03-new-merchant','name':'CE03 merchant'};create_store={'storeId':'ce03-new-store','merchantId':'ce03-new-merchant','name':'CE03 store'}
            expect('specified merchant grant cannot create',18661,'/v1/admin/merchants',user+[('Idempotency-Key',uid())],create_merchant,403)
            expect('specified store grant cannot create',18661,'/v1/admin/stores',user+[('Idempotency-Key',uid())],create_store,403)
            merchant_writer=directory_grant('merchant.create',True);directory_grant('store.create',True);projection()
            for code in ('merchant.create','store.create'):execution_ready(code)
            directory_browser('write')
            if args.browser:
                for table,rid in [('merchant_record','merchant_id'),('store_record','store_id')]:
                    if sql('SELECT count(*) FROM '+table+' WHERE tenant_id='+q(source)+' AND '+rid+"='ce03-ui-"+('merchant' if table=='merchant_record' else 'store')+"'")[1]!='1':raise RuntimeError('directory UI result duplicated or missing')
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_id IN ('ce03-ui-merchant','ce03-ui-store')")[1]!='2':raise RuntimeError('directory UI audit not exactly once')
                record('directory browser commands commit exactly once with two audits')
            merchant_headers=user+[('Idempotency-Key',uid())]
            created=expect('whole tenant creates merchant',18661,'/v1/admin/merchants',merchant_headers,create_merchant)
            if expect('directory idempotent command replay',18661,'/v1/admin/merchants',merchant_headers,create_merchant)!=created:raise RuntimeError('directory replay changed result')
            expect('whole tenant creates store under actual merchant',18661,'/v1/admin/stores',user+[('Idempotency-Key',uid())],create_store)
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='merchant' AND resource_id='ce03-new-merchant' AND store_id IS NULL")[1]!='1':raise RuntimeError('merchant identity audit incorrect')
            record('directory audit binds actual merchant with no fake store')
            expect('directory create foreign parent rejected',18661,'/v1/admin/stores',user+[('Idempotency-Key',uid())],{**create_store,'storeId':'ce03-no-store','merchantId':'missing-merchant'},404)
            expect('revoke directory create independently',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':merchant_writer,'expected_version':1});projection()
            expect('directory revoked create cannot replay receipt',18661,'/v1/admin/merchants',merchant_headers,create_merchant,403)
            expect('directory read survives create revocation',18661,'/v1/admin/merchants',user)
            directory_browser('revoked-write')
            if args.browser:
                for code,gid in directory_readers.items():
                    expect('revoke directory read '+code,18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':gid,'expected_version':1})
                projection();execution_ready('store.create','create-only')
                directory_browser('create-only')
        def member_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-member.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real member browser '+phase)
        if args.member:
            if args.browser:h.private(run/'member-ui.json',(run/'catalog-ui.json').read_text())
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'MEMBER_PROFILE','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='MEMBER_PROFILE' AND state='SHADOW' AND version=1;")
            expect('directory does not imply member access',18661,'/v1/admin/members',user,status=403)
            expect('old ADMIN cannot bypass member authority',18661,'/v1/admin/members',[('Authorization','Bearer '+admin_local)],status=403)
            def member_grant(code):
                role=expect('explicit member role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                rule={'version':1,'resource_type':MEMBER_RESOURCE_TYPE,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]}
                gid=expect('finite member grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':rule,'source_id':'ce04-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,resource_type=MEMBER_RESOURCE_TYPE);return gid
            member_grant('member.create')
            member_browser('create-only')
            body={'memberId':'ce04-member','actorId':'ce04-customer','displayName':'CE04 member','memberLevel':'BASIC'}
            create_headers=user+[('Idempotency-Key',uid())]
            created=expect('member create without read',18661,'/v1/admin/members',create_headers,body)
            if expect('member create receipt replay',18661,'/v1/admin/members',create_headers,body)!=created:raise RuntimeError('member creation receipt changed')
            expect('member create does not imply read',18661,'/v1/admin/members',user,status=403)
            profile_grant=member_grant('member.profile.update')
            member_browser('profile')
            profile_headers=user+[('Idempotency-Key',uid())];profile={'expectedVersion':0,'value':'Updated member','reason':'CE04 isolated update'}
            changed=expect('member profile capability updates actual owner',18661,'/v1/admin/members/ce04-member/profile',profile_headers,profile)
            if changed['version']!=1 or expect('member profile idempotent retry',18661,'/v1/admin/members/ce04-member/profile',profile_headers,profile)!=changed:raise RuntimeError('member profile receipt mismatch')
            expect('member profile cannot change status',18661,'/v1/admin/members/ce04-member/status',user+[('Idempotency-Key',uid())],{'expectedVersion':1,'value':'CLOSED','reason':'denied'},403)
            member_grant('member.read')
            member_rows=expect('member tenant list',18661,'/v1/admin/members',user)
            expected_members={'ce04-member','ce04-ui-member'} if args.browser else {'ce04-member'}
            if {row['memberId'] for row in member_rows}!=expected_members:raise RuntimeError('member list scope incorrect')
            member_browser('read')
            if len(expect('member real owner history',18661,'/v1/admin/members/ce04-member/history',user))!=1:raise RuntimeError('member history missing')
            expect('member foreign target denied',18661,'/v1/admin/members/foreign-member/history',user,status=404)
            expect('member foreign tenant denied',18661,'/v1/admin/members',[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            member_grant('member.status.update')
            member_browser('status')
            closed=expect('member closes with independent status capability',18661,'/v1/admin/members/ce04-member/status',user+[('Idempotency-Key',uid())],{'expectedVersion':1,'value':'CLOSED','reason':'CE04 close'})
            if closed['status']!='CLOSED' or closed['version']!=2:raise RuntimeError('member terminal state incorrect')
            expect('closed member cannot reopen',18661,'/v1/admin/members/ce04-member/status',user+[('Idempotency-Key',uid())],{'expectedVersion':2,'value':'ACTIVE','reason':'reopen denied'},409)
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member' AND resource_id='ce04-member' AND store_id IS NULL")[1]!='3':raise RuntimeError('member identity audit not atomic or duplicated')
            record('member create profile and status identity audits exactly once')
            expect('revoke member profile independently',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':profile_grant,'expected_version':1});projection()
            expect('revoked member profile cannot replay old receipt',18661,'/v1/admin/members/ce04-member/profile',profile_headers,profile,403)
            expect('member read survives profile revocation',18661,'/v1/admin/members',user)
            member_browser('revoked')
            if args.browser:
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member' AND resource_id='ce04-ui-member'")[1]!='3':raise RuntimeError('member browser commands not exactly once')
                record('member browser three effects and three audits exactly once')
        def growth_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-growth.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real growth browser '+phase)
        if args.growth:
            if args.browser:h.private(run/'growth-ui.json',(run/'catalog-ui.json').read_text())
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'MEMBER_GROWTH','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='MEMBER_GROWTH' AND state='SHADOW' AND version=1;")
            base='/v1/admin/member-growth';target='ce04-growth-member'
            expect('growth actual member created by independent core capability',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':target,'actorId':'ce04-growth-customer','displayName':'Growth fixture','memberLevel':'BASIC'})
            if args.browser:expect('growth UI actual member created',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':'ce04-growth-ui-member','actorId':'ce04-growth-ui-customer','displayName':'Growth browser fixture','memberLevel':'BASIC'})
            expect('core member does not imply growth policy',18661,base+'/policies',user,status=403)
            expect('legacy ADMIN cannot bypass growth authority',18661,base+'/policies',[('Authorization','Bearer '+admin_local)],status=403)
            def growth_grant(code):
                kind=GROWTH_CAPABILITIES[code]
                role=expect('explicit growth role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                rule={'version':1,'resource_type':kind,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]}
                gid=expect('finite growth grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':rule,'source_id':'ce04-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,resource_type=kind);return gid
            growth_grant('growth.policy.publish')
            policy={'version':1,'effectiveFrom':now(-1),'growthPerYuan':'1.00','levels':[{'code':'BASIC','minimumGrowth':0},{'code':'SILVER','minimumGrowth':100}]}
            policy_headers=user+[('Idempotency-Key',uid())]
            published=expect('growth policy publish without read',18661,base+'/policies',policy_headers,policy)
            if expect('growth policy receipt retry',18661,base+'/policies',policy_headers,policy)!=published:raise RuntimeError('growth policy receipt changed')
            expect('growth policy publish does not imply read',18661,base+'/policies',user,status=403)
            growth_browser('publish-only')
            growth_grant('growth.policy.read')
            if len(expect('growth policy independent read',18661,base+'/policies',user))!=(2 if args.browser else 1):raise RuntimeError('growth policy count incorrect')
            adjust_grant=growth_grant('growth.adjust')
            adjustment={'expectedVersion':0,'delta':150,'reason':'CE04 isolated growth calibration'};adjust_headers=user+[('Idempotency-Key',uid())]
            adjusted=expect('growth adjust without wallet read',18661,base+'/'+target+'/adjust',adjust_headers,adjustment)
            if adjusted['growth']!=150 or adjusted['version']!=1 or adjusted['memberLevel']!='SILVER':raise RuntimeError('growth adjustment effect incorrect')
            if expect('growth adjust stable receipt retry',18661,base+'/'+target+'/adjust',adjust_headers,adjustment)!=adjusted:raise RuntimeError('growth adjustment repeated')
            expect('growth adjustment does not imply wallet read',18661,base+'/'+target,user,status=403)
            expect('growth adjustment does not imply recalculate',18661,base+'/'+target+'/recalculate',user+[('Idempotency-Key',uid())],{},403)
            growth_browser('adjust')
            growth_grant('growth.read')
            wallet=expect('growth real member wallet read',18661,base+'/'+target,user)
            if wallet['growth']!=150:raise RuntimeError('growth wallet mismatch')
            if len(expect('growth ledger exactly one adjustment',18661,base+'/'+target+'/ledger',user))!=1:raise RuntimeError('growth ledger duplicated')
            expect('growth missing actual owner rejected',18661,base+'/foreign-member',user,status=404)
            expect('growth foreign auth tenant denied',18661,base+'/'+target,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            growth_browser('read')
            growth_grant('growth.recalculate');recalc_headers=user+[('Idempotency-Key',uid())]
            recalculated=expect('growth independent recalculation',18661,base+'/'+target+'/recalculate',recalc_headers,{})
            if recalculated['growth']!=150 or recalculated['version']!=2:raise RuntimeError('growth recalculation effect incorrect')
            if expect('growth recalculation receipt retry',18661,base+'/'+target+'/recalculate',recalc_headers,{})!=recalculated:raise RuntimeError('growth recalculation repeated')
            growth_browser('recalculate')
            if args.browser:
                ui_wallet=expect('growth UI actual wallet after single adjustment and recalculation',18661,base+'/ce04-growth-ui-member',user)
                if ui_wallet['growth']!=250 or ui_wallet['version']!=2:raise RuntimeError('growth UI wallet duplicated or incorrect')
                if len(expect('growth UI unknown retry commits only one ledger entry',18661,base+'/ce04-growth-ui-member/ledger',user))!=1:raise RuntimeError('growth UI ledger duplicated')
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_policy' AND resource_id='growth-policy-2'")[1]!='1':raise RuntimeError('growth UI policy audit incorrect')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability LIKE 'commerce.growth.%' AND store_id IS NULL")[1]!=('6' if args.browser else '3'):raise RuntimeError('growth identity audit count incorrect')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_policy' AND resource_id='growth-policy-1'")[1]!='1':raise RuntimeError('policy audit must bind actual version')
            record('growth policy adjustment recalculation commit exactly once with actual audit types')
            expect('growth revoke adjustment independently',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':adjust_grant,'expected_version':1});projection()
            expect('revoked growth adjustment cannot replay old receipt',18661,base+'/'+target+'/adjust',adjust_headers,adjustment,403)
            expect('growth read survives adjustment revocation',18661,base+'/'+target,user)
            growth_browser('revoked')
        def tag_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-tags.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real tag browser '+phase)
        if args.tags:
            if args.browser:h.private(run/'tag-ui.json',(run/'catalog-ui.json').read_text())
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'MEMBER_TAG','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='MEMBER_TAG' AND state='SHADOW' AND version=1;")
            base='/v1/admin/member-tags';target='ce04-tag-member'
            expect('tag actual member created independently',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':target,'actorId':'ce04-tag-customer','displayName':'Tag fixture','memberLevel':'BASIC'})
            if args.browser:expect('tag UI actual member created',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':'ce04-tag-ui-member','actorId':'ce04-tag-ui-customer','displayName':'Tag browser fixture','memberLevel':'BASIC'})
            expect('growth does not imply tag access',18661,base,user,status=403)
            expect('legacy ADMIN cannot bypass tag authority',18661,base,[('Authorization','Bearer '+admin_local)],status=403)
            def tag_grant(code):
                role=expect('explicit tag role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                rule={'version':1,'resource_type':MEMBER_RESOURCE_TYPE,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]}
                gid=expect('finite tag grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':rule,'source_id':'ce04-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,resource_type=MEMBER_RESOURCE_TYPE);return gid
            tag_grant('member_tag.define')
            definition={'tagId':'ce04-tag','name':'Isolated reviewed tag'};define_headers=user+[('Idempotency-Key',uid())]
            defined=expect('tag definition without read',18661,base,define_headers,definition)
            if expect('tag definition receipt retry',18661,base,define_headers,definition)!=defined:raise RuntimeError('tag definition receipt changed')
            expect('tag definition does not imply read',18661,base,user,status=403)
            assignment={'tagId':'ce04-tag','active':True,'expectedVersion':0,'reason':'CE04 isolated classification'}
            expect('tag definition does not imply assignment',18661,base+'/'+target+'/assign',user+[('Idempotency-Key',uid())],assignment,403)
            tag_browser('define-only')
            assignment_grant=tag_grant('member_tag.assign');assign_headers=user+[('Idempotency-Key',uid())]
            assigned=expect('tag assignment without read',18661,base+'/'+target+'/assign',assign_headers,assignment)
            if not assigned['active'] or assigned['version']!=1:raise RuntimeError('tag assignment effect incorrect')
            if expect('tag assignment stable retry',18661,base+'/'+target+'/assign',assign_headers,assignment)!=assigned:raise RuntimeError('tag assignment repeated')
            expect('tag assignment does not imply read',18661,base+'/'+target+'/assignments',user,status=403)
            tag_browser('assign')
            tag_grant('member_tag.read')
            expected_definitions=[definition]+([{'tagId':'ce04-ui-tag','name':'页面会员标签'}] if args.browser else [])
            if expect('tag independent dictionary read',18661,base,user)!=expected_definitions:raise RuntimeError('tag dictionary mismatch')
            if expect('tag independent member assignments',18661,base+'/'+target+'/assignments',user)!=[assigned]:raise RuntimeError('tag association mismatch')
            expect('tag missing actual owner rejected',18661,base+'/foreign-member/assignments',user,status=404)
            expect('tag foreign auth tenant denied',18661,base,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            expect('tag stale association version conflicts',18661,base+'/'+target+'/assign',user+[('Idempotency-Key',uid())],assignment,409)
            revoked=expect('tag deactivation preserves history',18661,base+'/'+target+'/assign',user+[('Idempotency-Key',uid())],{**assignment,'active':False,'expectedVersion':1,'reason':'Remove isolated classification'})
            if revoked['active'] or revoked['version']!=2:raise RuntimeError('tag deactivation incorrect')
            restored=expect('tag reactivation increments version',18661,base+'/'+target+'/assign',user+[('Idempotency-Key',uid())],{**assignment,'expectedVersion':2,'reason':'Restore reviewed classification'})
            if not restored['active'] or restored['version']!=3:raise RuntimeError('tag reactivation incorrect')
            tag_browser('read')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability LIKE 'commerce.member_tag.%' AND store_id IS NULL")[1]!=('7' if args.browser else '4'):raise RuntimeError('tag audit count incorrect')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_tag' AND resource_id='ce04-tag'")[1]!='1':raise RuntimeError('tag definition audit pretends member')
            if args.browser:
                ui_tags=expect('tag UI inactive association retains version two',18661,base+'/ce04-tag-ui-member/assignments',user)
                if len(ui_tags)!=1 or ui_tags[0]['active'] or ui_tags[0]['version']!=2:raise RuntimeError('tag UI assignment effect incorrect')
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_tag' AND resource_id='ce04-ui-tag'")[1]!='1':raise RuntimeError('tag UI definition repeated')
            record('tag API and optional UI effects have exactly one audit per successful command')
            expect('revoke tag assignment independently',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':assignment_grant,'expected_version':1});projection()
            expect('revoked tag assignment cannot replay receipt',18661,base+'/'+target+'/assign',assign_headers,assignment,403)
            expect('tag read survives assignment revocation',18661,base+'/'+target+'/assignments',user)
            tag_browser('revoked')
        def behavior_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-behavior.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real behavior browser '+phase)
        if args.behavior:
            if args.browser:h.private(run/'behavior-ui.json',(run/'catalog-ui.json').read_text())
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'MEMBER_BEHAVIOR','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='MEMBER_BEHAVIOR' AND state='SHADOW' AND version=1;")
            base='/v1/admin/member-behavior';target='ce04-behavior-member'
            expect('behavior actual member created independently',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':target,'actorId':'ce04-behavior-customer','displayName':'Behavior fixture','memberLevel':'BASIC'})
            if args.browser:expect('behavior UI actual member created',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':'ce04-behavior-ui-member','actorId':'ce04-behavior-ui-customer','displayName':'Behavior browser fixture','memberLevel':'BASIC'})
            expect('tags do not imply behavior access',18661,base+'/'+target,user,status=403)
            expect('legacy ADMIN cannot bypass behavior authority',18661,base+'/'+target,[('Authorization','Bearer '+admin_local)],status=403)
            def behavior_grant(code):
                role=expect('explicit finite '+code+' role',18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                grant=expect('grant independent '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':{'version':1,'resource_type':MEMBER_RESOURCE_TYPE,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},'source_id':'ce04-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,resource_type=MEMBER_RESOURCE_TYPE);return grant
            update_grant=behavior_grant('member_behavior.update')
            change={'expectedVersion':0,'birthday':'02-29','journeyEnabled':False,'reason':'CE04 independent preference'};change_headers=user+[('Idempotency-Key',uid())]
            profile=expect('behavior update without read',18661,base+'/'+target+'/profile',change_headers,change)
            if profile!={'birthday':'02-29','journeyEnabled':False,'version':1}:raise RuntimeError('behavior profile effect incorrect')
            if expect('behavior stable update retry',18661,base+'/'+target+'/profile',change_headers,change)!=profile:raise RuntimeError('behavior repeated update')
            expect('behavior update does not imply read',18661,base+'/'+target,user,status=403)
            expect('behavior update does not imply rebuild',18661,base+'/rebuild',user+[('Idempotency-Key',uid())],{'after':'','limit':1},403)
            expect('behavior invalid birthday denied',18661,base+'/'+target+'/profile',user+[('Idempotency-Key',uid())],{**change,'birthday':'02-30','expectedVersion':1},400)
            expect('behavior stale profile rejected',18661,base+'/'+target+'/profile',user+[('Idempotency-Key',uid())],change,409)
            behavior_browser('update-only')
            # 隔离客户凭据只用于原本人接口，中央员工不取得客户角色。
            customer_token=secrets.token_urlsafe(48)
            insert('platform_credential',{'tenant_id':source,'actor_id':'ce04-behavior-customer','role':'MEMBER','token_hash':hashlib.sha256(customer_token.encode()).hexdigest(),'expires_at':now(600).replace('T',' ').replace('Z','')})
            customer=[('Authorization','Bearer '+customer_token)]
            own=expect('behavior customer self access survives employee cutover',18661,'/v1/members/me/behavior',customer)
            if own['profile']!=profile:raise RuntimeError('customer preference mismatch')
            expect('customer cannot use employee behavior API',18661,base+'/'+target,customer,status=403)
            expect('customer behavior records actual published SKU',18661,'/v1/members/me/behavior/events',customer+[('Idempotency-Key',uid())],{'eventId':'ce04-behavior-event','kind':'BROWSE','storeId':store,'skuId':'p6-sku'})
            rebuild_grant=behavior_grant('member_behavior.rebuild')
            # 明示隔离历史订单/成长来源种子；不声称执行过真实支付或履约。
            at=now().replace('T',' ').replace('Z','')
            for order in ('ce04-behavior-order-a','ce04-behavior-order-b'):
                insert('order_record',{'tenant_id':source,'order_id':order,'member_id':target,'store_id':store,'merchant_id':merchant,'quote_id':uid(),'payable':'20.00','status':'COMPLETED','payment_kind':'CHANNEL_REQUIRED','version':1,'created_at':at,'expires_at':at,'items_json':'[]','address_cipher':'isolated fixture','address_key_version':1})
            insert('member_growth_order',{'tenant_id':source,'order_id':'ce04-behavior-order-a','member_id':target,'paid':'20.00','completed':True,'policy_version':0,'growth_rate':0,'net_spend':'12.00'})
            behavior_batch={'after':'','limit':1};batch_key=uid();batch_headers=user+[('Idempotency-Key',batch_key)]
            first=expect('behavior rebuild without read',18661,base+'/rebuild',batch_headers,behavior_batch)
            if first!={'next':'ce04-behavior-order-a','scanned':1,'done':False}:raise RuntimeError('behavior first cursor incorrect')
            if expect('behavior rebuild stable retry',18661,base+'/rebuild',batch_headers,behavior_batch)!=first:raise RuntimeError('behavior rebuild repeated')
            second=expect('behavior rebuild next source without growth',18661,base+'/rebuild',user+[('Idempotency-Key',uid())],{'after':first['next'],'limit':1})
            if second!={'next':'ce04-behavior-order-b','scanned':1,'done':False}:raise RuntimeError('behavior second cursor incorrect')
            empty=expect('behavior empty batch receipt',18661,base+'/rebuild',user+[('Idempotency-Key',uid())],{'after':second['next'],'limit':1})
            if empty!={'next':second['next'],'scanned':0,'done':True}:raise RuntimeError('behavior empty cursor incorrect')
            expect('behavior rebuild remains bounded',18661,base+'/rebuild',user+[('Idempotency-Key',uid())],{'after':'','limit':51},400)
            expect('behavior rebuild does not imply read',18661,base+'/'+target,user,status=403)
            behavior_browser('rebuild')
            behavior_grant('member_behavior.read')
            detail=expect('behavior independent detail from actual sources',18661,base+'/'+target,user)
            if detail['profile']!=profile or detail['facts']['browse30']!=1 or detail['facts']['completedOrders30']!=1 or detail['facts']['netSpend30']!='12.00':raise RuntimeError('behavior facts mismatch')
            events=expect('behavior independent event cursor',18661,base+'/'+target+'/events?after=0&limit=1',user)
            if len(events)!=1 or events[0]['eventId']!='ce04-behavior-event':raise RuntimeError('behavior event missing')
            if expect('behavior next event cursor is empty',18661,base+'/'+target+'/events?after='+str(events[0]['sequenceId'])+'&limit=1',user)!=[]:raise RuntimeError('behavior cursor repeated')
            expect('behavior missing actual owner rejected',18661,base+'/foreign-member',user,status=404)
            expect('behavior foreign auth tenant denied',18661,base+'/'+target,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            behavior_browser('read')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability LIKE 'commerce.member_behavior.%'")[1]!=('7' if args.browser else '4'):raise RuntimeError('behavior audit count incorrect')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_behavior_batch' AND resource_id=command_key")[1]!=('4' if args.browser else '3'):raise RuntimeError('behavior batch audit pretends member')
            if sql("SELECT count(*) FROM member_behavior_order WHERE tenant_id="+q(source)+" AND member_id="+q(target))[1]!='1':raise RuntimeError('behavior projection repeated')
            if args.browser:
                ui_detail=expect('behavior UI final profile has version two',18661,base+'/ce04-behavior-ui-member',user)
                if ui_detail['profile']!={'birthday':'03-15','journeyEnabled':True,'version':2}:raise RuntimeError('behavior UI profile effect incorrect')
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability='commerce.member_behavior.update' AND resource_type='commerce_member' AND resource_id='ce04-behavior-ui-member'")[1]!='2':raise RuntimeError('behavior UI profile duplicated')
            record('behavior API and optional UI commands have actual member or durable batch audit exactly once')
            for grant in (update_grant,rebuild_grant):expect('revoke independent behavior write',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':grant,'expected_version':1})
            projection()
            expect('revoked behavior profile cannot replay receipt',18661,base+'/'+target+'/profile',change_headers,change,403)
            expect('revoked behavior rebuild cannot replay receipt',18661,base+'/rebuild',batch_headers,behavior_batch,403)
            expect('behavior read survives write revocation',18661,base+'/'+target,user)
            behavior_browser('revoked')
        def cycle_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-cycles.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=150)
            record('real cycle browser '+phase)
        if args.cycles:
            if args.browser:
                ui=json.loads((run/'catalog-ui.json').read_text());ui.update(policyAt=now(3600),bundleUntil=now(604800));h.private(run/'cycle-ui.json',json.dumps(ui))
            for family in ('MEMBER_CYCLE','CYCLE_BENEFIT'):
                insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':family,'state':'SHADOW'})
                sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family="+q(family)+" AND state='SHADOW' AND version=1;")
            cycle_base='/v1/admin/member-cycles';benefit_base='/v1/admin/member-cycle-benefits';cycle_member='ce04-cycle-member'
            for target_id in (cycle_member,'ce04-cycle-system-member'):
                expect('cycle actual member created '+target_id,18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':target_id,'actorId':target_id+'-customer','displayName':'Cycle fixture','memberLevel':'BASIC'})
            if args.browser:
                for ui_member in ('ce04-cycle-ui-member','ce04-cycle-ui-empty','ce04-cycle-unassessed-member'):
                    expect('cycle UI actual member '+ui_member,18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':ui_member,'actorId':ui_member+'-customer','displayName':'Cycle browser fixture','memberLevel':'BASIC'})
            expect('behavior does not imply cycle policy',18661,cycle_base+'/policies',user,status=403)
            expect('legacy ADMIN cannot bypass cycle authority',18661,cycle_base+'/'+cycle_member,[('Authorization','Bearer '+admin_local)],status=403)
            def cycle_grant(code):
                role=expect('explicit cycle role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                kind=CYCLE_CAPABILITIES[code]
                grant=expect('finite cycle grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':{'version':1,'resource_type':kind,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},'source_id':'ce04-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,resource_type=kind);return grant
            publish_grant=cycle_grant('member_cycle.policy.publish')
            cycle_policy={'version':1,'effectiveFrom':now(-1),'periodDays':7,'levels':[{'code':'BASIC','minimumGrowth':0},{'code':'GOLD','minimumGrowth':100}]}
            cycle_policy_headers=user+[('Idempotency-Key',uid())]
            published=expect('cycle publish without policy read',18661,cycle_base+'/policies',cycle_policy_headers,cycle_policy)
            if expect('cycle policy same key retry',18661,cycle_base+'/policies',cycle_policy_headers,cycle_policy)!=published:raise RuntimeError('cycle policy duplicate')
            expect('cycle publish does not imply read',18661,cycle_base+'/policies',user,status=403)
            expect('cycle period boundary rejected',18661,cycle_base+'/policies',user+[('Idempotency-Key',uid())],{**cycle_policy,'version':2,'periodDays':0},400)
            cycle_browser('publish-only')
            evaluate_grant=cycle_grant('member_cycle.evaluate');cycle_evaluate_headers=user+[('Idempotency-Key',uid())]
            view=expect('cycle evaluate without read',18661,cycle_base+'/'+cycle_member+'/evaluate',cycle_evaluate_headers,{})
            if not view['enabled'] or view['policyVersion']!=1 or view['memberLevel']!='BASIC':raise RuntimeError('cycle actual assessment mismatch')
            if expect('cycle evaluate same key retry',18661,cycle_base+'/'+cycle_member+'/evaluate',cycle_evaluate_headers,{})!=view:raise RuntimeError('cycle evaluation repeated')
            expect('cycle evaluate does not imply read',18661,cycle_base+'/'+cycle_member,user,status=403)
            expect('cycle missing member Owner denied',18661,cycle_base+'/missing-cycle-member/evaluate',user+[('Idempotency-Key',uid())],{},404)
            cycle_browser('evaluate')
            # 权益定义尚属CE05，隔离准备调用现有真实Owner API，不冒充已迁移员工能力。
            expect('cycle isolated entitlement source definition',18661,'/v1/admin/entitlement-definitions',[('Authorization','Bearer '+admin_local),('Idempotency-Key',uid())],{'benefitId':'ce04-cycle-tea','version':1,'storeId':store,'name':'Cycle fixture tea','units':2,'quota':50,'validFrom':now(-60),'validTo':now(864000),'validityDays':7})
            define_grant=cycle_grant('cycle_benefit.define')
            cycle_bundle={'bindingId':'ce04-cycle-bundle','policyVersion':1,'level':'BASIC','storeId':store,'validUntil':now(604800),'benefits':[{'benefitId':'ce04-cycle-tea','version':1}]};cycle_bundle_headers=user+[('Idempotency-Key',uid())]
            defined=expect('cycle benefit define without cycle policy read',18661,benefit_base,cycle_bundle_headers,cycle_bundle)
            if expect('cycle benefit definition same key retry',18661,benefit_base,cycle_bundle_headers,cycle_bundle)!=defined:raise RuntimeError('cycle bundle duplicate')
            expect('cycle benefit define does not imply benefit read',18661,benefit_base+'?policyVersion=1',user,status=403)
            cycle_browser('define-only')
            grant_grant=cycle_grant('cycle_benefit.grant');cycle_award_headers=user+[('Idempotency-Key',uid())]
            award=expect('cycle benefit grant without member cycle read',18661,benefit_base+'/'+cycle_member+'/grant',cycle_award_headers,{})
            if len(award['grants'])!=1 or award['grants'][0]['status']!='REQUESTED':raise RuntimeError('cycle award not durably accepted')
            if expect('cycle benefit grant same key retry',18661,benefit_base+'/'+cycle_member+'/grant',cycle_award_headers,{})!=award:raise RuntimeError('cycle award duplicate')
            if args.browser:
                # 明示隔离周期贡献种子：GOLD没有当前策略礼包，用真实空回执验证UI，不宣称发生了支付。
                insert('member_cycle_contribution',{'tenant_id':source,'source_id':'ce04-cycle-ui-empty-fixture','member_id':'ce04-cycle-ui-empty','occurred_at':now().replace('T',' ').replace('Z',''),'contribution':150})
            cycle_browser('grant')
            for code in ('member_cycle.policy.read','member_cycle.read','cycle_benefit.read'):cycle_grant(code)
            history=expect('cycle independent policy history',18661,cycle_base+'/policies?after=0&limit=1',user)
            if history!=[published] or expect('cycle policy next cursor empty',18661,cycle_base+'/policies?after='+('2' if args.browser else '1')+'&limit=1',user)!=[]:raise RuntimeError('cycle policy cursor mismatch')
            if expect('cycle actual member snapshot',18661,cycle_base+'/'+cycle_member,user)!=view:raise RuntimeError('cycle member read mismatch')
            if expect('cycle independent bundle read',18661,benefit_base+'?policyVersion=1',user)!=[defined]:raise RuntimeError('cycle bundle read mismatch')
            cycle_browser('read')
            expect('cycle foreign tenant denied',18661,cycle_base+'/'+cycle_member,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            expect('cycle system member assessed before employee revoke',18661,cycle_base+'/ce04-cycle-system-member/evaluate',user+[('Idempotency-Key',uid())],{})
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND (capability LIKE 'commerce.member_cycle.%' OR capability LIKE 'commerce.cycle_benefit.%')")[1]!=('10' if args.browser else '5'):raise RuntimeError('cycle identity audit duplicate')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_cycle_benefit' AND resource_id='ce04-cycle-bundle'")[1]!='1':raise RuntimeError('cycle bundle audit wrong target')
            if args.browser:
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_cycle_benefit' AND resource_id='ce04-cycle-ui-bundle'")[1]!='1':raise RuntimeError('cycle UI bundle duplicate')
                if sql("SELECT count(*) FROM benefit_grant WHERE tenant_id="+q(source)+" AND benefit_id='ce04-cycle-tea' AND member_id='ce04-cycle-ui-member'")[1]!='1':raise RuntimeError('cycle UI grant duplicated')
                if sql("SELECT count(*) FROM benefit_grant WHERE tenant_id="+q(source)+" AND member_id='ce04-cycle-ui-empty'")[1]!='0':raise RuntimeError('cycle empty receipt invented benefit')
            record('cycle API and optional UI commands have actual target audits exactly once')
            for grant in (publish_grant,evaluate_grant,define_grant,grant_grant):expect('revoke independent cycle write',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':grant,'expected_version':1})
            projection()
            expect('revoked cycle publish cannot replay receipt',18661,cycle_base+'/policies',cycle_policy_headers,cycle_policy,403)
            expect('revoked cycle evaluate cannot replay receipt',18661,cycle_base+'/'+cycle_member+'/evaluate',cycle_evaluate_headers,{},403)
            expect('revoked cycle definition cannot replay receipt',18661,benefit_base,cycle_bundle_headers,cycle_bundle,403)
            expect('revoked cycle grant cannot replay receipt',18661,benefit_base+'/'+cycle_member+'/grant',cycle_award_headers,{},403)
            # 系统事件消费使用既有可信车道，员工撤权不取消已承诺周期权益。
            # 浏览器种子增加了注册/考核事件；每次pump最多5条，固定4次只能推进20条。
            # 按本次实际周期事件完成状态有界推进，仍用下方真实权益数和身份审计数验收。
            for _ in range(12):
                expect('trusted cycle event pump after employee revoke',18661,'/v1/admin/events/pump',[('Authorization','Bearer '+admin_local)],{})
                pending=sql("SELECT count(*) FROM platform_event WHERE tenant_id="+q(source)+" AND event_type='member.cycle.assessed.v1' AND status<>'DELIVERED'")[1]
                if pending=='0':break
            else:raise RuntimeError('cycle events did not complete within bounded pump budget')
            if sql("SELECT count(*) FROM benefit_grant WHERE tenant_id="+q(source)+" AND benefit_id='ce04-cycle-tea' AND member_id IN ('ce04-cycle-member','ce04-cycle-system-member')")[1]!='2':raise RuntimeError('system cycle event stopped or duplicated')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability='commerce.cycle_benefit.grant'")[1]!=('3' if args.browser else '1'):raise RuntimeError('system cycle worker impersonated employee')
            record('trusted cycle events complete without employee grants and do not duplicate awards')
            expect('cycle read survives write revoke',18661,cycle_base+'/'+cycle_member,user)
            expect('cycle benefit read survives write revoke',18661,benefit_base+'?policyVersion=1',user)
            cycle_browser('revoked')
        def points_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-points.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=150)
            record('real points browser '+phase)
        if args.points:
            if args.browser:
                ui=json.loads((run/'catalog-ui.json').read_text());ui.update(policyAt=now(3600));h.private(run/'points-ui.json',json.dumps(ui))
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'MEMBER_POINTS','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='MEMBER_POINTS' AND state='SHADOW' AND version=1;")
            points_base='/v1/admin/member-points';points_member='ce04-points-member'
            expect('points actual member created independently',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':points_member,'actorId':'ce04-points-customer','displayName':'Points fixture','memberLevel':'BASIC'})
            if args.browser:
                expect('points UI actual member',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':'ce04-points-ui-member','actorId':'ce04-points-ui-customer','displayName':'Points browser fixture','memberLevel':'BASIC'})
            expect('cycle permissions do not imply points read',18661,points_base+'/'+points_member,user,status=403)
            expect('legacy ADMIN cannot bypass points authority',18661,points_base+'/'+points_member,[('Authorization','Bearer '+admin_local)],status=403)
            def points_grant(code):
                role=expect('explicit points role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                kind=POINTS_CAPABILITIES[code]
                grant=expect('finite points grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':{'version':1,'resource_type':kind,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},'source_id':'ce04-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,resource_type=kind);return grant
            points_publish_grant=points_grant('points.policy.publish')
            points_policy={'version':1,'effectiveFrom':now(-1),'earnPerYuan':'1.00','expiryDays':30,'spendEnabled':True,'pointsPerYuan':100,'maxDeductionBps':5000};points_policy_headers=user+[('Idempotency-Key',uid())]
            published=expect('points publish without policy read',18661,points_base+'/policies',points_policy_headers,points_policy)
            if expect('points policy original key retry',18661,points_base+'/policies',points_policy_headers,points_policy)!=published:raise RuntimeError('points policy duplicated')
            expect('points publish does not imply read',18661,points_base+'/policies',user,status=403)
            expect('points policy precision rejected',18661,points_base+'/policies',user+[('Idempotency-Key',uid())],{**points_policy,'version':2,'earnPerYuan':'1.001'},400)
            points_browser('publish-only')
            points_adjust_grant=points_grant('points.adjust');points_adjustment={'expectedVersion':0,'delta':150,'reason':'explicit isolated manual correction'};points_adjust_headers=user+[('Idempotency-Key',uid())]
            adjusted=expect('points adjustment without wallet read',18661,points_base+'/'+points_member+'/adjust',points_adjust_headers,points_adjustment)
            if adjusted['available']!=150 or adjusted['version']!=1:raise RuntimeError('points adjustment amount mismatch')
            if expect('points adjustment original key retry',18661,points_base+'/'+points_member+'/adjust',points_adjust_headers,points_adjustment)!=adjusted:raise RuntimeError('points duplicate adjustment')
            expect('points adjust does not imply read',18661,points_base+'/'+points_member,user,status=403)
            expect('points stale account version rejected',18661,points_base+'/'+points_member+'/adjust',user+[('Idempotency-Key',uid())],points_adjustment,409)
            expect('points missing actual Owner denied',18661,points_base+'/missing-points-member/adjust',user+[('Idempotency-Key',uid())],points_adjustment,404)
            points_browser('adjust')
            points_expire_grant=points_grant('points.expire');points_expire_headers=user+[('Idempotency-Key',uid())]
            # 只在自有演练库把本次积分批次置为到期，未等待30天，不宣称业务真实到期。
            sql("UPDATE member_point_lot SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE tenant_id="+q(source)+" AND member_id="+q(points_member))
            expired=expect('points expiry without wallet read',18661,points_base+'/'+points_member+'/expire',points_expire_headers,{})
            if expired['available']!=0 or expired['version']!=2:raise RuntimeError('points expiry did not archive actual lot')
            if expect('points expiry original key retry',18661,points_base+'/'+points_member+'/expire',points_expire_headers,{})!=expired:raise RuntimeError('points duplicate expiry')
            if args.browser:
                sql("UPDATE member_point_lot SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE tenant_id="+q(source)+" AND member_id='ce04-points-ui-member'")
            points_browser('expire')
            for code in ('points.policy.read','points.read'):points_grant(code)
            next_policy={**points_policy,'version':2,'effectiveFrom':now(3600)}
            expect('points immutable future policy',18661,points_base+'/policies',user+[('Idempotency-Key',uid())],next_policy)
            if expect('points first policy cursor',18661,points_base+'/policies?after=0&limit=1',user)!=[published] or expect('points terminal policy cursor',18661,points_base+'/policies?after='+str(3 if args.browser else 2)+'&limit=1',user)!=[]:raise RuntimeError('points policy cursor mismatch')
            if expect('points actual expired wallet',18661,points_base+'/'+points_member,user)!=expired:raise RuntimeError('points wallet mismatch')
            ledger=expect('points actual immutable ledger',18661,points_base+'/'+points_member+'/ledger',user)
            if [e['action'] for e in ledger]!=['ADJUST','EXPIRE']:raise RuntimeError('points ledger duplicate or missing expiry')
            if expect('points next ledger cursor',18661,points_base+'/'+points_member+'/ledger?after='+str(ledger[-1]['sequenceId'])+'&limit=1',user)!=[]:raise RuntimeError('points ledger cursor mismatch')
            expect('points foreign tenant denied',18661,points_base+'/'+points_member,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            customer_token='p6-points-'+secrets.token_hex(20)
            insert('platform_credential',{'token_hash':hashlib.sha256(customer_token.encode()).hexdigest(),'tenant_id':source,'actor_id':'ce04-points-customer','role':'MEMBER','expires_at':now(600).replace('T',' ').replace('Z','')})
            if expect('points customer own wallet remains local',18661,'/v1/members/me/points',[('Authorization','Bearer '+customer_token)])!=expired:raise RuntimeError('points customer wallet mismatch')
            expect('points customer cannot use employee entry',18661,points_base+'/'+points_member,[('Authorization','Bearer '+customer_token)],status=403)
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability LIKE 'commerce.points.%' AND store_id IS NULL")[1]!=str(7 if args.browser else 4):raise RuntimeError('points audit count mismatch')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_policy' AND resource_id='points-policy-1'")[1]!='1':raise RuntimeError('points policy wrong audit target')
            if args.browser:
                ui_ledger=expect('points UI immutable ledger',18661,points_base+'/ce04-points-ui-member/ledger',user)
                if [entry['action'] for entry in ui_ledger]!=['ADJUST','EXPIRE']:raise RuntimeError('points UI duplicate ledger')
                ui_wallet=expect('points UI actual expired wallet',18661,points_base+'/ce04-points-ui-member',user)
                if ui_wallet['available']!=0 or ui_wallet['version']!=2:raise RuntimeError('points UI wallet mismatch')
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='commerce_member_policy' AND resource_id='points-policy-3'")[1]!='1':raise RuntimeError('points UI policy audit mismatch')
                for capability in ('commerce.points.adjust','commerce.points.expire'):
                    if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability="+q(capability)+" AND resource_id='ce04-points-ui-member'")[1]!='1':raise RuntimeError('points UI member audit mismatch')
            points_browser('read')
            record('points commands have exactly one actual target audit each')
            for grant in (points_publish_grant,points_adjust_grant,points_expire_grant):expect('revoke independent points write',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':grant,'expected_version':1})
            projection()
            expect('revoked points policy cannot replay receipt',18661,points_base+'/policies',points_policy_headers,points_policy,403)
            expect('revoked points adjustment cannot replay receipt',18661,points_base+'/'+points_member+'/adjust',points_adjust_headers,points_adjustment,403)
            expect('revoked points expiry cannot replay receipt',18661,points_base+'/'+points_member+'/expire',points_expire_headers,{},403)
            expect('points read survives write revocation',18661,points_base+'/'+points_member,user)
            points_browser('revoked')
        def offer_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce04-offers.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=150)
            record('real point offer browser '+phase)
        if args.offers:
            if args.browser:
                ui=json.loads((run/'catalog-ui.json').read_text());ui.update({'from':now(60),'to':now(1800),'futureFrom':now(3600),'futureTo':now(5400)});h.private(run/'offers-ui.json',json.dumps(ui))
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'POINT_OFFER','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='POINT_OFFER' AND state='SHADOW' AND version=1;")
            offer_base='/v1/admin/point-offers';offer_member='ce04-offer-member';offer_customer='ce04-offer-customer'
            expect('offer actual customer member',18661,'/v1/admin/members',user+[('Idempotency-Key',uid())],{'memberId':offer_member,'actorId':offer_customer,'displayName':'Offer fixture','memberLevel':'BASIC'})
            local_admin=[('Authorization','Bearer '+admin_local)]
            coupon={'definitionId':'ce04-offer-coupon','version':1,'storeId':store,'name':'Points-only fixture','minimumSpend':'0.00','discountAmount':'5.00','validFrom':now(-60),'validTo':now(7200),'quota':1,'stackable':True,'platformFundingBps':10000,'issuanceMode':'SOURCE_ONLY'}
            expect('offer actual controlled asset',18661,'/v1/admin/coupon-definitions',local_admin+[('Idempotency-Key',uid())],coupon)
            if args.browser:
                expect('offer UI actual entitlement asset',18661,'/v1/admin/entitlement-definitions',local_admin+[('Idempotency-Key',uid())],{'benefitId':'ce04-offer-entitlement','version':1,'storeId':store,'name':'UI points entitlement','units':1,'quota':10,'validFrom':now(-60),'validTo':now(7200),'validityDays':7})
            expect('points permissions do not imply offer read',18661,offer_base+'?storeId='+store,user,status=403)
            expect('legacy ADMIN cannot bypass offer management',18661,offer_base+'?storeId='+store,local_admin,status=403)
            expect('legacy ADMIN cannot bypass through customer catalogue',18661,'/v1/point-offers?storeId='+store,local_admin,status=403)
            def offer_grant(code,kind=POINT_OFFER_RESOURCE_TYPE):
                role=expect('explicit offer role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce04-offer-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                grant=expect('finite offer grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':{'version':1,'resource_type':kind,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},'source_id':'ce04-offer-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,phase='offer-fixture',resource_type=kind);return grant
            offer_define_grant=offer_grant('point_offer.define')
            definition={'offerId':'ce04-offer-a','storeId':store,'name':'Points offer A','kind':'COUPON','assetId':coupon['definitionId'],'assetVersion':1,'points':100,'quota':10,'perMemberLimit':2,'validFrom':now(-1),'validTo':now(1800)}
            define_headers=user+[('Idempotency-Key',uid())]
            offer_a=expect('offer define without directory read',18661,offer_base,define_headers,definition)
            if expect('offer definition original key retry',18661,offer_base,define_headers,definition)!=offer_a:raise RuntimeError('offer definition duplicated')
            expect('offer define does not imply read',18661,offer_base+'?storeId='+store,user,status=403)
            offer_b=expect('second actual offer',18661,offer_base,user+[('Idempotency-Key',uid())],{**definition,'offerId':'ce04-offer-b','name':'Points offer B'})
            expect('offer missing actual store denied',18661,offer_base,user+[('Idempotency-Key',uid())],{**definition,'offerId':'ce04-offer-invalid','storeId':'missing-offer-store'},404)
            offer_browser('define-only')
            offer_status_grant=offer_grant('point_offer.status.update');offer_status={'expectedVersion':0,'active':False,'reason':'explicit isolated pause'};status_headers=user+[('Idempotency-Key',uid())]
            paused=expect('offer status without directory read',18661,offer_base+'/ce04-offer-a/status',status_headers,offer_status)
            if paused['status']!='INACTIVE' or paused['version']!=1:raise RuntimeError('offer status not committed')
            if expect('offer status original key retry',18661,offer_base+'/ce04-offer-a/status',status_headers,offer_status)!=paused:raise RuntimeError('offer status duplicated')
            expect('offer stale version rejected',18661,offer_base+'/ce04-offer-a/status',user+[('Idempotency-Key',uid())],offer_status,409)
            expect('offer missing actual Owner denied',18661,offer_base+'/missing-offer/status',user+[('Idempotency-Key',uid())],offer_status,404)
            offer_browser('status')
            offer_grant('point_offer.read')
            if expect('offer stable first cursor',18661,offer_base+'?storeId='+store+'&limit=1',user)!=[paused]:raise RuntimeError('offer first cursor mismatch')
            if expect('offer stable second cursor',18661,offer_base+'?storeId='+store+'&after=ce04-offer-a&limit=1',user)!=[offer_b]:raise RuntimeError('offer second cursor mismatch')
            if expect('offer terminal cursor',18661,offer_base+'?storeId='+store+'&after='+('ce04-offer-ui-d' if args.browser else 'ce04-offer-b')+'&limit=1',user)!=[]:raise RuntimeError('offer terminal cursor mismatch')
            expect('offer foreign tenant denied',18661,offer_base+'?storeId='+store,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            offer_browser('read')
            # 此临时人工校准授权仅用于隔离客户兑换夹具，单独创建并随后撤销，不借旧ADMIN绕过积分族。
            funding_grant=offer_grant('points.adjust',MEMBER_RESOURCE_TYPE)
            expect('offer customer actual points fixture',18661,'/v1/admin/member-points/'+offer_member+'/adjust',user+[('Idempotency-Key',uid())],{'expectedVersion':0,'delta':300,'reason':'isolated offer redemption fixture'})
            for grant in (offer_define_grant,offer_status_grant,funding_grant):expect('revoke independent offer fixture write',18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':grant,'expected_version':1})
            projection()
            expect('revoked offer define cannot replay receipt',18661,offer_base,define_headers,definition,403)
            expect('revoked offer status cannot replay receipt',18661,offer_base+'/ce04-offer-a/status',status_headers,offer_status,403)
            expect('offer read survives write revocation',18661,offer_base+'?storeId='+store,user)
            offer_browser('revoked')
            customer_token='p6-offer-'+secrets.token_hex(20)
            insert('platform_credential',{'token_hash':hashlib.sha256(customer_token.encode()).hexdigest(),'tenant_id':source,'actor_id':offer_customer,'role':'MEMBER','expires_at':now(600).replace('T',' ').replace('Z','')})
            offer_customer_headers=[('Authorization','Bearer '+customer_token)];redeem_headers=offer_customer_headers+[('Idempotency-Key',uid())]
            if expect('offer customer sees active catalogue only',18661,'/v1/point-offers?storeId='+store,offer_customer_headers)!=[offer_b]:raise RuntimeError('customer catalogue leaked paused offer')
            receipt=expect('customer redeems after employee write revoke',18661,'/v1/point-offers/ce04-offer-b/redeem',redeem_headers,{})
            if expect('customer original redemption key retry',18661,'/v1/point-offers/ce04-offer-b/redeem',redeem_headers,{})!=receipt:raise RuntimeError('customer redemption duplicated')
            expect('asset exhausted cannot charge second redemption',18661,'/v1/point-offers/ce04-offer-b/redeem',offer_customer_headers+[('Idempotency-Key',uid())],{},409)
            wallet=expect('customer points remain exact after asset failure',18661,'/v1/members/me/points',offer_customer_headers)
            if wallet['available']!=200:raise RuntimeError('failed redemption charged customer')
            if expect('customer actual redemption receipt',18661,'/v1/point-redemptions',offer_customer_headers)!=[receipt]:raise RuntimeError('redemption receipt mismatch')
            expect('customer cannot define offers',18661,offer_base,offer_customer_headers+[('Idempotency-Key',uid())],definition,403)
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability LIKE 'commerce.point_offer.%' AND resource_type='point_offer' AND store_id IS NULL")[1]!=str(6 if args.browser else 3):raise RuntimeError('offer audit count mismatch')
            if sql("SELECT issued FROM benefit_point_offer WHERE tenant_id="+q(source)+" AND offer_id='ce04-offer-b'")[1]!='1':raise RuntimeError('offer quota not atomic')
            if sql("SELECT count(*) FROM benefit_point_redemption WHERE tenant_id="+q(source)+" AND member_id="+q(offer_member))[1]!='1':raise RuntimeError('offer duplicate redemption')
            if args.browser:
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='point_offer' AND resource_id='ce04-offer-ui-c'")[1]!='2':raise RuntimeError('offer UI coupon audit mismatch')
                if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type='point_offer' AND resource_id='ce04-offer-ui-d'")[1]!='1':raise RuntimeError('offer UI entitlement audit mismatch')
                if sql("SELECT CONCAT(status,':',version) FROM benefit_point_offer WHERE tenant_id="+q(source)+" AND offer_id='ce04-offer-ui-c'")[1]!='INACTIVE:1':raise RuntimeError('offer UI status mismatch')
                if sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.kind')) FROM benefit_point_offer WHERE tenant_id="+q(source)+" AND offer_id='ce04-offer-ui-d'")[1]!='ENTITLEMENT':raise RuntimeError('offer UI asset kind mismatch')
            record('offer commands have exact actual identity audits and one atomic customer redemption')
        def coupon_definition_browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce05-coupon-definitions.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=150)
            record('real coupon definition browser '+phase)
        if args.coupon_definitions:
            if args.browser:
                ui=json.loads((run/'catalog-ui.json').read_text());ui.update({'from':now(-60),'to':now(7200)});h.private(run/'coupon-definitions-ui.json',json.dumps(ui))
            insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':'COUPON_DEFINITION','state':'SHADOW'})
            sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family='COUPON_DEFINITION' AND state='SHADOW' AND version=1;")
            cd_base='/v1/admin/coupon-definitions'
            expect('other capabilities do not imply coupon definition read',18661,cd_base+'?storeId='+store,user,status=403)
            expect('legacy ADMIN cannot bypass coupon definition management',18661,cd_base+'?storeId='+store,local_admin,status=403)
            expect('legacy ADMIN cannot bypass shared coupon catalogue',18661,'/v1/coupon-definitions?storeId='+store,local_admin,status=403)
            def coupon_definition_grant(code,kind=COUPON_DEFINITION_RESOURCE_TYPE):
                role=expect('explicit coupon definition role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce05-cd-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                grant=expect('finite coupon definition grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':{'version':1,'resource_type':kind,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},'source_id':'ce05-cd-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,phase='coupon-definition-fixture',resource_type=kind);return grant
            cd_create_grant=coupon_definition_grant('coupon_definition.create')
            cd_input={'definitionId':'ce05-coupon-a','version':1,'storeId':store,'name':'Coupon definition A','minimumSpend':'0.00','discountAmount':'5.00','validFrom':now(-60),'validTo':now(7200),'quota':1,'stackable':False,'platformFundingBps':None,'issuanceMode':None,'validityDays':None}
            cd_headers=user+[('Idempotency-Key',uid())]
            cd_a=expect('coupon definition create without read',18661,cd_base,cd_headers,cd_input)
            if cd_a['content']['issuanceMode']!='PUBLIC' or cd_a['content']['platformFundingBps']!=0 or cd_a['content']['stackable']:raise RuntimeError('coupon definition defaults or boolean changed')
            if expect('coupon definition original key retry',18661,cd_base,cd_headers,cd_input)!=cd_a:raise RuntimeError('coupon definition duplicated')
            expect('coupon definition create does not imply read',18661,cd_base+'?storeId='+store,user,status=403)
            cd_a2=expect('coupon definition new controlled version',18661,cd_base,user+[('Idempotency-Key',uid())],{**cd_input,'version':2,'issuanceMode':'SOURCE_ONLY','validityDays':7})
            cd_b=expect('coupon definition second public resource',18661,cd_base,user+[('Idempotency-Key',uid())],{**cd_input,'definitionId':'ce05-coupon-b','issuanceMode':'PUBLIC'})
            expect('coupon definition duplicate immutable version',18661,cd_base,user+[('Idempotency-Key',uid())],cd_input,409)
            expect('coupon definition sub-cent precision rejected',18661,cd_base,user+[('Idempotency-Key',uid())],{**cd_input,'definitionId':'ce05-invalid-money','discountAmount':'0.001'},400)
            expect('coupon definition missing actual Owner',18661,cd_base,user+[('Idempotency-Key',uid())],{**cd_input,'definitionId':'ce05-invalid-store','storeId':'missing-coupon-store'},404)
            coupon_definition_browser('create-only')
            coupon_definition_grant('coupon_definition.read')
            if expect('coupon definition latest version cursor',18661,cd_base+'?storeId='+store+'&after=ce05-&limit=1',user)!=[cd_a2]:raise RuntimeError('coupon latest version cursor mismatch')
            if expect('coupon definition stable second cursor',18661,cd_base+'?storeId='+store+'&after=ce05-coupon-a&limit=1',user)!=[cd_b]:raise RuntimeError('coupon second cursor mismatch')
            if expect('coupon definition terminal cursor',18661,cd_base+'?storeId='+store+'&after='+('ce05-coupon-ui-d' if args.browser else 'ce05-coupon-b')+'&limit=1',user)!=[]:raise RuntimeError('coupon terminal cursor mismatch')
            coupon_definition_browser('read')
            expect('coupon definition foreign tenant denied',18661,cd_base+'?storeId='+store,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            # 为受控发放建立真实兑换入口；此临时商品权限单独授予并撤销，券目录权限不替代它。
            cd_offer_grant=coupon_definition_grant('point_offer.define',POINT_OFFER_RESOURCE_TYPE)
            expect('coupon definition actual controlled fulfillment binding',18661,offer_base,user+[('Idempotency-Key',uid())],{'offerId':'ce05-coupon-offer','storeId':store,'name':'Controlled coupon fulfillment','kind':'COUPON','assetId':'ce05-coupon-a','assetVersion':2,'points':100,'quota':1,'perMemberLimit':1,'validFrom':now(-1),'validTo':now(1800)})
            for gid in (cd_create_grant,cd_offer_grant):expect('revoke coupon definition fixture write '+gid,18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':gid,'expected_version':1})
            projection()
            expect('revoked coupon definition cannot replay receipt',18661,cd_base,cd_headers,cd_input,403)
            expect('coupon definition read survives create revoke',18661,cd_base+'?storeId='+store,user)
            coupon_definition_browser('revoked')
            visible=expect('customer public coupon catalogue remains local',18661,'/v1/coupon-definitions?storeId='+store,offer_customer_headers)
            expected_public=['ce05-coupon-b']+(['ce05-coupon-ui-c'] if args.browser else [])
            if [v['content']['definitionId'] for v in visible if v['content']['definitionId'].startswith('ce05-')]!=expected_public:raise RuntimeError('customer coupon catalogue leaked controlled latest or old public version')
            claim_headers=offer_customer_headers+[('Idempotency-Key',uid())]
            cd_claim=expect('customer claims public coupon after employee revoke',18661,'/v1/coupons/ce05-coupon-b/1/claim',claim_headers,{})
            if expect('customer coupon claim original retry',18661,'/v1/coupons/ce05-coupon-b/1/claim',claim_headers,{})!=cd_claim:raise RuntimeError('customer public claim duplicated')
            expect('customer cannot claim controlled coupon',18661,'/v1/coupons/ce05-coupon-a/2/claim',offer_customer_headers+[('Idempotency-Key',uid())],{},409)
            cd_redeem_headers=offer_customer_headers+[('Idempotency-Key',uid())]
            cd_receipt=expect('controlled coupon fulfillment after employee revoke',18661,'/v1/point-offers/ce05-coupon-offer/redeem',cd_redeem_headers,{})
            if expect('controlled coupon fulfillment original retry',18661,'/v1/point-offers/ce05-coupon-offer/redeem',cd_redeem_headers,{})!=cd_receipt:raise RuntimeError('controlled coupon duplicated')
            if expect('coupon fulfillment actual customer wallet',18661,'/v1/members/me/points',offer_customer_headers)['available']!=100:raise RuntimeError('coupon fulfillment balance mismatch')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability='commerce.coupon_definition.create' AND resource_type='coupon_definition' AND store_id IS NULL")[1]!=str(5 if args.browser else 3):raise RuntimeError('coupon definition exact audit mismatch')
            if sql("SELECT SUM(issued) FROM benefit_coupon_definition WHERE tenant_id="+q(source)+" AND definition_id LIKE 'ce05-%'")[1]!='2':raise RuntimeError('coupon actual issuance mismatch')
            if args.browser:
                for definition_id,expected in (('ce05-coupon-ui-c','PUBLIC:0:0:0:1.25:0'),('ce05-coupon-ui-d','SOURCE_ONLY:1:10000:7:1.25:0')):
                    if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND capability='commerce.coupon_definition.create' AND resource_type='coupon_definition' AND resource_id="+q(definition_id))[1]!='1':raise RuntimeError('coupon UI actual identity audit mismatch')
                    if sql("SELECT CONCAT(issuance_mode,':',stackable,':',platform_funding_bps,':',COALESCE(validity_days,0),':',CAST(discount_amount AS DECIMAL(14,2)),':',issued) FROM benefit_coupon_definition WHERE tenant_id="+q(source)+" AND definition_id="+q(definition_id))[1]!=expected:raise RuntimeError('coupon UI persisted fields mismatch')
            record('exact coupon definition audits and two customer assets are atomic without employee impersonation')
        def entitlement_browser(kind,phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-ce05-entitlements.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_KIND=kind,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=150)
            record('real entitlement browser '+kind+' '+phase)
        if args.entitlements:
            if args.browser:
                ui=json.loads((run/'catalog-ui.json').read_text());ui.update({'from':now(-60),'to':now(7200)});h.private(run/'entitlements-ui.json',json.dumps(ui))
            for family in ('ENTITLEMENT_DEFINITION','ENTITLEMENT'):
                insert('employee_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'family':family,'state':'SHADOW'})
                sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND family="+q(family)+" AND state='SHADOW' AND version=1;")
            ed_base='/v1/admin/entitlement-definitions';ent_base='/v1/admin/entitlements'
            for path in (ed_base+'?storeId='+store,ent_base):
                expect('other capabilities do not imply entitlement read '+path,18661,path,user,status=403)
                expect('legacy ADMIN cannot bypass entitlement management '+path,18661,path,local_admin,status=403)
            def entitlement_grant(code,kind=None):
                kind=kind or ENTITLEMENT_CAPABILITIES[code]
                role=expect('explicit entitlement role '+code,18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'ce05-e-'+code.replace('.','-'),'role_version':1,'capabilities':['commerce.'+code]})['id']
                grant=expect('finite entitlement grant '+code,18162,prefix+'/scoped-grants',admin,{**partition,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':role,'scope_rule':{'version':1,'resource_type':kind,'clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},'source_id':'ce05-e-'+code,'valid_from':now(-2),'valid_to':now(600)},202)['id']
                projection();execution_ready(code,phase='entitlement-fixture',resource_type=kind);return grant
            ed_create_grant=entitlement_grant('entitlement_definition.create')
            ed_input={'benefitId':'ce05-entitlement-a','version':1,'storeId':store,'name':'Central entitlement A','units':5,'quota':10,'validFrom':now(-60),'validTo':now(7200),'validityDays':7}
            ed_headers=user+[('Idempotency-Key',uid())]
            ed_a=expect('entitlement definition create without read',18661,ed_base,ed_headers,ed_input)
            if expect('entitlement definition original key retry',18661,ed_base,ed_headers,ed_input)!=ed_a:raise RuntimeError('entitlement definition duplicated')
            expect('entitlement definition create does not imply read',18661,ed_base+'?storeId='+store,user,status=403)
            ed_a2=expect('entitlement latest version changes business store',18661,ed_base,user+[('Idempotency-Key',uid())],{**ed_input,'version':2,'storeId':other})
            ed_b=expect('entitlement definition second actual resource',18661,ed_base,user+[('Idempotency-Key',uid())],{**ed_input,'benefitId':'ce05-entitlement-b'})
            expect('entitlement duplicate immutable version',18661,ed_base,user+[('Idempotency-Key',uid())],ed_input,409)
            expect('entitlement units retain original validation',18661,ed_base,user+[('Idempotency-Key',uid())],{**ed_input,'benefitId':'ce05-invalid-entitlement','units':0},400)
            expect('entitlement missing actual store rejected',18661,ed_base,user+[('Idempotency-Key',uid())],{**ed_input,'benefitId':'ce05-missing-owner','storeId':'missing'},404)
            entitlement_browser('definitions','write-only')
            entitlement_grant('entitlement_definition.read')
            if expect('entitlement latest global version cannot leak old store',18661,ed_base+'?storeId='+store+'&after=ce05-&limit=1',user)!=[ed_b]:raise RuntimeError('entitlement definition old-store version leaked')
            if expect('entitlement latest version actual store',18661,ed_base+'?storeId='+other+'&after=ce05-&limit=1',user)!=[ed_a2]:raise RuntimeError('entitlement definition latest version missing')
            if expect('entitlement definition terminal cursor',18661,ed_base+'?storeId='+store+'&after='+('ce05-entitlement-ui-c' if args.browser else 'ce05-entitlement-b')+'&limit=1',user):raise RuntimeError('entitlement definition cursor unstable')
            entitlement_browser('definitions','read')
            expect('definition read does not imply grant read',18661,ent_base,user,status=403)
            # 明确的隔离欠项夹具只验证中央处理；真实退款/冲正链路由MySQL业务回归覆盖。
            ent_ids=['ce05-grant:a','ce05-grant:b']
            for gid in ent_ids+(['ce05-ui-grant:c','ce05-ui-grant:d'] if args.browser else []):
                insert('benefit_grant',{'tenant_id':source,'grant_id':gid,'order_id':gid+'-order','member_id':offer_member,'benefit_id':ed_input['benefitId'],'benefit_version':1,'expires_at':now(7200).replace('T',' ').removesuffix('Z'),'name':'Explicit isolated compensation fixture','status':'COMPENSATION_REQUIRED','units':5,'remaining_units':0,'debt_units':2,'version':0,'source_type':'ORDER','source_id':gid+'-order'})
            h.private(run/'entitlement-fixture-origin.json',json.dumps({'compensation_origin':'EXPLICIT_ISOLATED_SQL_PENDING_DEBT','refund_http_claimed':False,'customer_fulfillment':'REAL_POINT_REDEMPTION_OUTBOX_CONSUMER'}))
            ent_resolve_grant=entitlement_grant('entitlement.resolve');ent_receipts=[]
            for gid,conclusion in zip(ent_ids,('RECOVERED','WRITTEN_OFF')):
                path=ent_base+'/'+urllib.parse.quote(gid,safe='')+'/resolve';headers=user+[('Idempotency-Key',uid())];body={'resolution':conclusion,'reference':'ce05-'+conclusion.lower()+'-proof'}
                receipt=expect('actual entitlement '+conclusion+' without read',18661,path,headers,body)
                if receipt['status']!='COMPENSATED' or receipt['debtUnits']!=0 or receipt['remainingUnits']!=0 or receipt['version']!=1:raise RuntimeError('entitlement compensation state mismatch')
                if expect('entitlement resolve original key '+conclusion,18661,path,headers,body)!=receipt:raise RuntimeError('entitlement compensation duplicated')
                expect('new key cannot repeat compensation '+conclusion,18661,path,user+[('Idempotency-Key',uid())],body,409)
                ent_receipts.append((path,headers,body,receipt))
            expect('resolve does not imply instance read',18661,ent_base,user,status=403)
            entitlement_browser('instances','write-only')
            entitlement_grant('entitlement.read')
            if expect('entitlement actual grant stable cursor',18661,ent_base+'?after=ce05-&limit=1',user)!=[ent_receipts[0][3]]:raise RuntimeError('entitlement first cursor mismatch')
            if expect('entitlement second grant stable cursor',18661,ent_base+'?after='+urllib.parse.quote(ent_ids[0],safe='')+'&limit=1',user)!=[ent_receipts[1][3]]:raise RuntimeError('entitlement second cursor mismatch')
            expect('entitlement foreign tenant denied',18661,ent_base,[('Authorization','Bearer '+user_token),('X-Tenant-Id',uid())],status=403)
            entitlement_browser('instances','read')
            eo_grant=entitlement_grant('point_offer.define',POINT_OFFER_RESOURCE_TYPE)
            expect('entitlement actual customer fulfillment offer',18661,offer_base,user+[('Idempotency-Key',uid())],{'offerId':'ce05-entitlement-offer','storeId':store,'name':'Entitlement customer proof','kind':'ENTITLEMENT','assetId':ed_input['benefitId'],'assetVersion':1,'points':50,'quota':1,'perMemberLimit':1,'validFrom':now(-1),'validTo':now(1800)})
            redeem_headers=offer_customer_headers+[('Idempotency-Key',uid())]
            redemption=expect('customer entitlement redemption accepted',18661,'/v1/point-offers/ce05-entitlement-offer/redeem',redeem_headers,{})
            for gid in (ed_create_grant,ent_resolve_grant,eo_grant):expect('revoke independent entitlement fixture write '+gid,18162,prefix+'/revoke',admin,{**partition,'command_id':uid(),'grant_id':gid,'expected_version':1})
            projection();expect('revoked entitlement create cannot replay',18661,ed_base,ed_headers,ed_input,403)
            for path,headers,body,_ in ent_receipts:expect('revoked entitlement resolve cannot replay '+path,18661,path,headers,body,403)
            expect('entitlement read survives write revoke',18661,ent_base,user)
            expect('entitlement definition read survives write revoke',18661,ed_base+'?storeId='+store,user)
            entitlement_browser('definitions','revoked');entitlement_browser('instances','revoked')
            # 已受理的可靠事件在撤权后继续；有界轮询实际状态，不凭固定推进次数假定完成。
            deadline=time.monotonic()+8
            while True:
                expect('trusted entitlement event pump after revoke',18661,'/v1/admin/events/pump',local_admin,{})
                wallet=expect('actual customer entitlement wallet',18661,'/v1/entitlements',offer_customer_headers)
                actual=[v for v in wallet if v['benefitId']==ed_input['benefitId'] and v['sourceType']=='POINTS']
                if actual and actual[0]['status']=='AVAILABLE':break
                if time.monotonic()>=deadline:raise RuntimeError('entitlement customer fulfillment did not complete')
                time.sleep(.15)
            if expect('customer entitlement redemption original key',18661,'/v1/point-offers/ce05-entitlement-offer/redeem',redeem_headers,{})!=redemption:raise RuntimeError('entitlement redemption duplicated')
            consume_headers=offer_customer_headers+[('Idempotency-Key',uid())];consume_path='/v1/entitlements/'+actual[0]['grantId']+'/consume'
            consumed=expect('customer consumes after employee revoke',18661,consume_path,consume_headers,{'units':2})
            if consumed['remainingUnits']!=3 or expect('customer consumption original key',18661,consume_path,consume_headers,{'units':2})!=consumed:raise RuntimeError('entitlement consumption duplicated')
            expect('customer entitlement cannot overconsume',18661,consume_path,offer_customer_headers+[('Idempotency-Key',uid())],{'units':4},409)
            if expect('entitlement customer actual points balance',18661,'/v1/members/me/points',offer_customer_headers)['available']!=50:raise RuntimeError('entitlement customer points mismatch')
            if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type IN ('entitlement_definition','entitlement') AND store_id IS NULL")[1]!=str(8 if args.browser else 5):raise RuntimeError('entitlement exact audit count mismatch')
            for gid,conclusion in zip(ent_ids,('RECOVERED','WRITTEN_OFF')):
                if sql("SELECT count(*) FROM benefit_ledger WHERE tenant_id="+q(source)+" AND grant_id="+q(gid)+" AND action="+q(conclusion)+" AND units=2 AND balance=0")[1]!='1':raise RuntimeError('entitlement compensation exact ledger mismatch')
            if sql("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id="+q(source)+" AND grant_id="+q(actual[0]['grantId'])+" AND action='CONSUME'")[1]!='1':raise RuntimeError('entitlement consumption duplicated in SQL')
            if args.browser:
                for gid,kind in [('ce05-entitlement-ui-c','entitlement_definition'),('ce05-ui-grant:c','entitlement'),('ce05-ui-grant:d','entitlement')]:
                    if sql("SELECT count(*) FROM employee_command_identity WHERE tenant_id="+q(source)+" AND resource_type="+q(kind)+" AND resource_id="+q(gid))[1]!='1':raise RuntimeError('entitlement UI actual audit mismatch')
                for gid,conclusion in [('ce05-ui-grant:c','RECOVERED'),('ce05-ui-grant:d','WRITTEN_OFF')]:
                    if sql("SELECT count(*) FROM benefit_ledger WHERE tenant_id="+q(source)+" AND grant_id="+q(gid)+" AND action="+q(conclusion)+" AND units=2 AND balance=0")[1]!='1':raise RuntimeError('entitlement UI compensation ledger mismatch')
                if sql("SELECT CONCAT(units,':',quota,':',validity_days,':',reserved,':',issued) FROM benefit_definition WHERE tenant_id="+q(source)+" AND benefit_id='ce05-entitlement-ui-c'")[1]!='7:15:30:0:0':raise RuntimeError('entitlement UI definition fields mismatch')
            record('exact entitlement identity audits and explicit debt fixtures with real customer fulfillment')
        def job(name,price,revision):return post(name,'catalog-jobs',{'jobId':uid(),'storeId':store,'name':name,'action':'PRICE','runAt':None,'deadline':now(600),'targets':[{'skuId':'p6-sku','expectedRevision':revision,'unitPrice':price}],'reason':'P6 durable proof'})
        queued=job('queued process recovery','12.00',sku['revision']);h.stop(app);app=start_commerce('commerce-resumed')
        expect('background reference survives process restart',18661,'/v1/operations/catalog-jobs/pump?storeId='+store,user,{})
        current=expect('read committed task effect',18661,'/v1/operations/skus?storeId='+store,user)[0]
        if current['unitPrice']!='12.00':raise RuntimeError('task did not commit expected price')
        revoked_job=job('queued before source revoke','20.00',current['revision'])
        delta={**batch,'sequence':2,'snapshot_hash':hashlib.sha256(b'p6-explicit-synthetic-revoke').hexdigest(),'items':[{**items[-1],'source_version':1,'deny':True,'reason':'synthetic source revoke'}]}
        h.private(run/'batch-2.json',json.dumps(delta));cli('MigrationImportCli',run/'import.properties',run/'batch-2.json');cli('MigrationImportCli',run/'import.properties',run/'batch-2.json');projection()
        h.stop(app);runtime['COMMERCE_WORKERS_ENABLED']='true';app=start_commerce('commerce-revoked-background')
        deadline=time.monotonic()+12
        revoked_id=revoked_job['definition']['jobId']
        while time.monotonic()<deadline:
            value=sql('SELECT cursor_index,attempts FROM catalog_operation_job WHERE tenant_id='+q(source)+' AND job_id='+q(revoked_id))[1].split('\t')
            if len(value)==2 and int(value[1])>0:
                if int(value[0])!=0:raise RuntimeError('revoked task advanced')
                record('real background worker stops revoked execution reference');break
            time.sleep(.25)
        else:raise RuntimeError('background revocation was not exercised')
        expect('central denies after imported revocation' ,18661,'/v1/operations/products?storeId='+store,user,status=403)
        browser('revoked')
        if args.inventory:expect('inventory does not inherit CATALOG revocation',18661,inventory_path,user)
        cli('MigrationImportCli',run/'import.properties',run/'batch-1.json',expected=2);record('old snapshot cannot resurrect newer tombstone')
        # 安全回退只停止，不把中央撤权后仍为true的旧授权重新变成权威。
        sql("UPDATE catalog_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id="+q(source)+" AND state='CENTRAL';")
        expect('safe rollback blocks old admin',18661,'/v1/operations/products?storeId='+store,[('Authorization','Bearer '+admin_local)],status=403)
        code,_=sql("UPDATE catalog_authority_route SET state='LEGACY',frozen=FALSE,ever_central=FALSE,version=version+1 WHERE tenant_id="+q(source),True)
        if code==0:raise RuntimeError('unsafe rollback accepted')
        record('database rejects rollback that discards central denials')
        sql("UPDATE catalog_authority_route SET state='CENTRAL',version=version+1 WHERE tenant_id="+q(source)+" AND state='STOPPED';")
        expect('resumed central retains denial',18661,'/v1/operations/products?storeId='+store,user,status=403)
        h.stop(auth);expect('central outage does not use legacy allow',18661,'/v1/operations/products?storeId='+store,user,status=503)
        browser('outage')
        if args.inventory:
            expect('inventory dependency outage fails closed',18661,inventory_path,user,status=503)
            inventory_browser('outage')
        if args.directory:
            expect('directory auth outage fails closed',18661,'/v1/admin/stores',user,status=503)
            directory_browser('outage')
        if args.member:
            expect('member auth outage fails closed',18661,'/v1/admin/members',user,status=503)
            member_browser('outage')
        if args.growth:
            expect('growth auth outage fails closed',18661,'/v1/admin/member-growth/ce04-growth-member',user,status=503)
            growth_browser('outage')
        if args.tags:
            expect('tag auth outage fails closed',18661,'/v1/admin/member-tags',user,status=503)
            tag_browser('outage')
        if args.behavior:
            expect('behavior auth outage fails closed',18661,'/v1/admin/member-behavior/ce04-behavior-member',user,status=503)
            behavior_browser('outage')
        if args.cycles:
            expect('cycle auth outage fails closed',18661,'/v1/admin/member-cycles/ce04-cycle-member',user,status=503)
            expect('cycle benefit auth outage fails closed',18661,'/v1/admin/member-cycle-benefits?policyVersion=1',user,status=503)
            cycle_browser('outage')
        if args.points:
            expect('points auth outage fails closed',18661,'/v1/admin/member-points/ce04-points-member',user,status=503)
            points_browser('outage')
        if args.offers:
            expect('offer auth outage fails closed',18661,'/v1/admin/point-offers?storeId='+store,user,status=503)
            expect('offer customer catalogue remains local during central outage',18661,'/v1/point-offers?storeId='+store,offer_customer_headers)
            offer_browser('outage')
        if args.coupon_definitions:
            expect('coupon definition auth outage fails closed',18661,cd_base+'?storeId='+store,user,status=503)
            expect('customer coupon catalogue during central outage',18661,'/v1/coupon-definitions?storeId='+store,offer_customer_headers)
            coupon_definition_browser('outage')
        if args.entitlements:
            expect('entitlement definition central outage fails closed',18661,ed_base+'?storeId='+store,user,status=503)
            expect('entitlement instance central outage fails closed',18661,ent_base,user,status=503)
            expect('customer entitlement wallet remains local in outage',18661,'/v1/entitlements',offer_customer_headers)
            entitlement_browser('definitions','outage');entitlement_browser('instances','outage')
        if sql('SELECT active FROM store_operator_grant WHERE tenant_id='+q(source)+" AND grant_id='p6-proof-grant'")[1]!='1':raise RuntimeError('legacy fixture unexpectedly changed')
        if sql('SELECT unit_price FROM catalog_sku WHERE tenant_id='+q(source)+" AND sku_id='p6-sku'")[1] not in ('12.00','12.0000'):raise RuntimeError('revoked task modified product')
        result={'result':'PASS','scope':'isolated local rehearsal only','source_tenant':source,'source_sha256':selected['source_sha256'],'database':database,'auth_config':str(dbfile),'run':str(run),'checks':checks,'real_source_records':len(items)-1,'synthetic_positive_records':1,'shadow':shadow,'runtime_switched':False,'production_ready':False,'inventory_checked':args.inventory,'directory_checked':args.directory,'member_checked':args.member,'growth_checked':args.growth,'tags_checked':args.tags,'behavior_checked':args.behavior,'cycles_checked':args.cycles,'points_checked':args.points,'offers_checked':args.offers,'coupon_definitions_checked':args.coupon_definitions,'entitlements_checked':args.entitlements,'identity_mode':'DEDICATED_IDP_AND_PG' if isolation else 'EXISTING_IDP_SHARED_PG','commerce_jar_sha256':hashlib.sha256(local_jar.read_bytes()).hexdigest()}
        h.private(run/'result.json',json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({'result':'PASS','checks':len(checks),'evidence':str(run/'result.json')}))
    finally:
        for p in processes+h.PROCESSES:h.stop(p)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--commerce-root',default='../commerce-platform')
    parser.add_argument('--browser',action='store_true')
    parser.add_argument('--entitlements',action='store_true',help='finite entitlement definition and actual grant management; includes coupon regression')
    parser.add_argument('--coupon-definitions',action='store_true',help='finite coupon definition creation/read and customer issuance; includes offer regression')
    parser.add_argument('--offers',action='store_true',help='finite point offer management and customer redemption; includes points regression')
    parser.add_argument('--points',action='store_true',help='finite points policy and wallet rehearsal; includes cycles regression')
    parser.add_argument('--cycles',action='store_true',help='finite cycle policy and benefit rehearsal; includes behavior regression')
    parser.add_argument('--behavior',action='store_true',help='finite member behavior rehearsal; includes tag regression')
    parser.add_argument('--tags',action='store_true',help='finite member tag rehearsal; includes growth regression')
    parser.add_argument('--growth',action='store_true',help='finite growth policy and account rehearsal; includes member regression')
    parser.add_argument('--member',action='store_true',help='finite member core rehearsal; includes directory regression')
    parser.add_argument('--directory',action='store_true',help='finite directory read/create rehearsal; includes inventory regression')
    parser.add_argument('--inventory',action='store_true',help='explicit finite inventory roles in owned rehearsal only')
    parser.add_argument('--isolated-identity',action='store_true')
    parser.add_argument('--identity-subnet',help='explicit unused RFC1918 /24 when Docker default pools are exhausted')
    args=parser.parse_args()
    if args.identity_subnet and not args.isolated_identity:parser.error('--identity-subnet requires --isolated-identity')
    if args.entitlements:args.coupon_definitions=True
    if args.coupon_definitions:args.offers=True
    if args.offers:args.points=True
    if args.points:args.cycles=True
    if args.cycles:args.behavior=True
    if args.behavior:args.tags=True
    if args.tags:args.growth=True
    if args.growth:args.member=True
    if args.member:args.directory=True
    if args.directory:args.inventory=True
    verify_auth_runtime(Path(__file__).resolve().parents[1])
    isolation=None
    try:
        if args.isolated_identity:
            # 复用P7已验证的自有资源生命周期；这里只准备身份/数据库，不运行容量或恢复测试。
            root=Path(__file__).resolve().parents[1]
            isolation=module('p6_identity_runtime',root/'deploy/governance-p7-rehearsal.py').Rehearsal(1,True,True)
            isolation.prepare_database();isolation.prepare_identity(args.identity_subnet)
            subprocess.run(['python3',str(root/'deploy/governance-casdoor-fixture.py'),'--base',isolation.issuer,'--phase','tokens','--directory',str(isolation.run/'identity'),'--management-config',str(isolation.management_config)],check=True,stdout=subprocess.DEVNULL)
        rehearse(args,isolation)
    finally:
        if isolation:isolation.close()


if __name__=='__main__':
    try:main()
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as error:
        raise SystemExit('P6 rehearsal stopped: '+str(error))
