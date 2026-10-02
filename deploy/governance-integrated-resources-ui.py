#!/usr/bin/env python3
"""IR-01真实HTTP/PKCE/SQL演练：专属PG身份、IdP组织和回环端口，永不写共享业务库。"""
import argparse, base64, hashlib, http.client, importlib.util, json, os, secrets, socket, subprocess, time, urllib.parse, urllib.request, uuid, zipfile
from pathlib import Path
from enum import Enum
from http import HTTPStatus
class FixtureUser(str, Enum):
    OWNER = "owner"
    NARROW = "narrow"
    ORDINARY = "ordinary"
ROOT=Path(__file__).resolve().parents[1]
ISSUER='http://localhost:18090'
ADMIN=21662
UI=21665

def private(path,content):
    """证据和凭据只进0600独有文件；失败保留原运行目录。"""
    with os.fdopen(os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:f.write(content)
def read(path):
    if path.is_symlink() or path.stat().st_mode & 0o777 != 0o600:raise RuntimeError('private file required')
    return path.read_text()
def props(values):return ''.join(k+'='+str(v)+'\n' for k,v in values.items())
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--database-directory',required=True);parser.add_argument('--management-config',required=True);parser.add_argument('--graph-config',required=True);parser.add_argument('--playwright-module',required=True);parser.add_argument('--resume-prepared');parser.add_argument('--resume-browser');parser.add_argument('--resume-verify');args=parser.parse_args()
    dbdir=Path(args.database_directory).resolve();db=json.loads(read(dbdir/'database.json'))
    if not db['database'].startswith('auth_gov_p1_test_') or not db['username'].startswith('auth_gov_p1_'):raise RuntimeError('owned database required')
    for port in (ADMIN,UI):
        with socket.socket() as s:
            s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',port))
    resume=args.resume_prepared or args.resume_browser or args.resume_verify
    run=Path(resume).resolve() if resume else ROOT/'.local/integrated-resource-selector'/('runtime-'+secrets.token_hex(6))
    if resume:
        if run.parent!=ROOT/'.local/integrated-resource-selector' or not run.name.startswith('runtime-') or (args.resume_prepared and (run/'browser.json').exists()) or (args.resume_browser and not (run/'browser.json').exists()):raise RuntimeError('only prepared owned run can resume')
    else:run.mkdir(mode=0o700)
    checks=[];processes=[]
    if args.resume_verify:
        previous=[json.loads(read(path)) for path in run.glob("checkpoint-*.json")]
        checks=max(previous,key=len) if previous else []
    def record(name):checks.append({'check':name,'result':'PASS'});private(run/f'checkpoint-{len(checks):03}-{secrets.token_hex(3)}.json',json.dumps(checks))
    ops=json.loads(read(Path(args.management_config)));auth='Basic '+base64.b64encode((ops['client_id']+':'+ops['client_secret']).encode()).decode()
    def idp(path,data=None,form=False,authorization=auth):
        headers={'Authorization':authorization,'Accept-Language':'en'}
        if data is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json';data=(urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        with urllib.request.urlopen(urllib.request.Request(ISSUER+'/api/'+path,data,headers),timeout=15) as r:value=json.load(r)
        if value.get('status')=='error' or value.get('error'):raise RuntimeError('owned IdP operation failed '+path.split('?')[0])
        return value
    def sql(statement):
        r=subprocess.run(['docker','exec','-i','dev-infra-postgres16-1','psql','-U',db['username'],'-d',db['database'],'-At','-v','ON_ERROR_STOP=1'],input=statement,text=True,capture_output=True,timeout=20)
        if r.returncode:private(run/f'sql-failure-{uuid.uuid4()}.log',r.stderr);raise RuntimeError('owned SQL failed')
        return r.stdout.strip()
    jar=ROOT/'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as z:
        for module in ('protocol','core','governance'):
            local=ROOT/f'auth-platform-{module}/target/auth-platform-{module}-0.1.0-SNAPSHOT.jar'
            if z.read('BOOT-INF/lib/'+local.name)!=local.read_bytes():raise RuntimeError('stale Auth runtime dependency '+module)
    record('current packaged runtime nested dependencies match worktree artifacts')
    def cli(name,*inputs):
        log=run/(name+'-'+secrets.token_hex(4)+'.log')
        with log.open('wb') as output:r=subprocess.run(['java','-Dloader.main=com.lrj.authz.governance.cli.'+name,'-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,inputs)],stdout=output,stderr=subprocess.STDOUT,timeout=35)
        log.chmod(0o600)
        if r.returncode:raise RuntimeError('owned CLI failed '+name)
    if resume:
        saved=json.loads(read(run/'identity.json'));org=saved['organization'];client=saved['client'];users=saved['users']
        bootstrap=dict(line.split('=',1) for line in read(run/'owner-bootstrap.properties').splitlines() if '=' in line)
        tenant=bootstrap['tenant.id'];p={'tenant_id':tenant,'application_id':'commerce','environment':'test'}
        database=read(dbdir/'database.properties');manifest=json.loads(read(run/'manifest-v1.json'));caps=manifest['capabilities']
    else:
        suffix=secrets.token_hex(6);org='ir01-'+suffix;client={'name':'ir01-console-'+suffix,'secret':secrets.token_urlsafe(40)}
        users={kind:{'id':str(uuid.uuid4()),'name':'ir01-'+kind,'password':secrets.token_urlsafe(24),'principal':str(uuid.uuid4()),'member':str(uuid.uuid4())} for kind in ('owner','narrow','ordinary')}
        tenant=str(uuid.uuid4());p={'tenant_id':tenant,'application_id':'commerce','environment':'test'}
        private(run/'identity.json',json.dumps({'organization':org,'client':client,'users':users}))
        idp('add-organization',{'owner':'admin','name':org,'displayName':'IR-01 dedicated acceptance','passwordType':'bcrypt','passwordOptions':['AtLeast8'],'accountItems':[]})
        idp('add-application',{'owner':'admin','name':client['name'],'displayName':'IR-01 dedicated console','organization':org,'clientId':client['name'],'clientSecret':client['secret'],'cert':'cert-built-in','isShared':False,'enablePassword':True,'enableSignUp':False,'enableSigninSession':False,'grantTypes':['authorization_code','refresh_token'],'redirectUris':[f'http://127.0.0.1:{UI}/callback'],'signinMethods':[{'name':'Password','displayName':'Password','rule':'All'}],'providers':[],'expireInHours':1,'refreshExpireInHours':2,'tokenFormat':'JWT-Custom','tokenSigningMethod':'RS256','tokenFields':['Owner','Name','DisplayName']})
        database=read(dbdir/'database.properties')
        for kind,user in users.items():
            idp('add-user',{'owner':org,'name':user['name'],'id':user['id'],'displayName':'IR-01 '+kind,'type':'normal-user','password':user['password'],'isAdmin':False,'signupApplication':client['name']})
            command={'command.id':str(uuid.uuid4()),'operator.ref':'ir01-fixture','tenant.id':tenant,'tenant.code':'ir01-'+suffix,'principal.id':user['principal'],'issuer':ISSUER,'subject':user['id'],'membership.id':user['member'],'valid.from':'2020-01-01T00:00:00Z','source.system':'ir01-fixture','source.tenant.ref':tenant,'source.subject.ref':kind}
            config=run/(kind+'-bootstrap.properties');private(config,props(command));cli('GovernanceCli','bootstrap',dbdir/'database.properties',config)
        catalog_config=run/'catalog.properties';private(catalog_config,database+props({'catalog.application':'commerce','catalog.owner-principal':users['owner']['principal'],'catalog.entry-origin':'http://127.0.0.1:21661','catalog.operator':'ir01-fixture','catalog.command':str(uuid.uuid4()),'catalog.owner-issuer':ISSUER,'catalog.owner-subject':users['owner']['id']}))
        cli('CatalogCli','register',catalog_config,'configured')
        # 只发布已实际实现且当前协议支持的能力；这些真实路由不赋予命令隐含权。
        caps=[{'code':'commerce.member.read','resource_type':'commerce_member','risk_level':'HIGH'},{'code':'commerce.member.create','resource_type':'commerce_member','risk_level':'HIGH'},{'code':'commerce.merchant.read','resource_type':'merchant','risk_level':'NORMAL'},{'code':'commerce.store.directory.read','resource_type':'store','risk_level':'NORMAL'}]
        manifest={'schema_version':'1','application':'commerce','manifest_version':1,'capabilities':caps,'menus':[]}
        manifest_file=run/'manifest-v1.json';private(manifest_file,json.dumps(manifest));cli('CatalogCli','publish',catalog_config,manifest_file)
        for kind in ('owner','narrow'):
            config=run/(kind+'-delegation.properties');private(config,database+props({'access.tenant':tenant,'access.application':'commerce','access.environment':'test','access.manager':users[kind]['member'],'access.generation':1,'access.capabilities':','.join(c['code'] for c in caps if kind==FixtureUser.OWNER or c['code']=='commerce.member.read'),'access.max-duration-seconds':3600 if kind==FixtureUser.OWNER else 600,'access.operator':'ir01-fixture','access.command':str(uuid.uuid4())}));cli('AccessBootstrapCli',config)
    graph_values=dict(line.split('=',1) for line in read(Path(args.graph_config)).splitlines() if '=' in line)
    graph_config=props({**graph_values,'scope.graph.http':graph_values['graph.http'],'scope.graph.key':graph_values['graph.key']})
    config=run/('admin-'+secrets.token_hex(3)+'.properties');private(config,database+graph_config+props({'issuer':ISSUER,'jwks.uri':ISSUER+'/.well-known/jwks','audience':client['name'],'client.id':client['name'],'client.secret':client['secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret'],'approval.tenant':tenant,'approval.application':'commerce','approval.environment':'test','approval.inbound-key':secrets.token_urlsafe(48)}))
    def request_http(path,token=None,payload=None):
        c=http.client.HTTPConnection('127.0.0.1',ADMIN,timeout=15);headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        c.request('GET' if payload is None else 'POST',path,body=None if payload is None else json.dumps(payload),headers=headers);r=c.getresponse();raw=r.read();status=r.status;rh=dict(r.getheaders());c.close();return status,json.loads(raw) if raw else None,rh
    def token(user):
        verifier=secrets.token_urlsafe(48);challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=');redirect=f'http://127.0.0.1:{UI}/callback'
        query=urllib.parse.urlencode({'clientId':client['name'],'responseType':'code','redirectUri':redirect,'scope':'openid profile','state':secrets.token_urlsafe(24),'nonce':secrets.token_urlsafe(24),'code_challenge_method':'S256','code_challenge':challenge})
        code=idp('login?'+query,{'type':'code','organization':org,'username':user['name'],'password':user['password'],'application':client['name'],'signinMethod':'Password'},authorization='')['data']
        return idp('login/oauth/access_token',{'grant_type':'authorization_code','client_id':client['name'],'code':code,'code_verifier':verifier,'redirect_uri':redirect},form=True,authorization='')['access_token']
    try:
        logfile=(run/('admin-'+secrets.token_hex(3)+'.log')).open('wb');proc=subprocess.Popen(['java','-Xmx256m','-jar',str(jar),'--server.address=127.0.0.1','--server.port='+str(ADMIN),'--authz.governance.enabled=true','--authz.governance.access.enabled=true','--authz.governance.presentation.enabled=true','--authz.governance.scope.enabled=true','--authz.governance.requests.enabled=true','--authz.governance.configuration='+str(config)],cwd=ROOT,stdout=logfile,stderr=subprocess.STDOUT);processes.append(proc)
        for _ in range(180):
            try:
                if request_http('/actuator/health')[0]==HTTPStatus.OK:break
            except (OSError,ValueError):pass
            if proc.poll() is not None:raise RuntimeError('owned admin startup failed')
            time.sleep(.25)
        else:raise RuntimeError('owned admin startup timeout')
        tokens={kind:token(user) for kind,user in users.items()};private(run/('tokens-'+secrets.token_hex(3)+'.json'),json.dumps(tokens));q=urllib.parse.urlencode(p);endpoint='/api/governance/v1/access/published-catalog?'+q
        if args.resume_verify:
            if json.loads(read(run/"browser-result.json"))["status"]!="PASS":raise RuntimeError("successful actual browser evidence required")
            artifact=json.loads(read(max(run.glob("artifacts-*.json"),key=lambda path:path.stat().st_mtime_ns)))
            if artifact["jar_sha256"]!=hashlib.sha256(jar.read_bytes()).hexdigest() or any(hashlib.sha256((ROOT/path).read_bytes()).hexdigest()!=digest for path,digest in artifact["frontend_sources"].items()):raise RuntimeError("source artifact changed since successful browser run")
        elif args.resume_browser:
            # 只恢复尚未产生UI命令的专属fixture；成功写入后的失败必须另建隔离运行。
            counts=sql("SELECT (SELECT count(*) FROM auth_governance.role_version WHERE tenant_id='%s') || ',' || (SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id='%s') || ',' || (SELECT count(*) FROM auth_governance.request_policy WHERE tenant_id='%s');"%(tenant,tenant,tenant))
            if counts!='2,0,0':raise RuntimeError('UI writes exist; prepared browser resume forbidden')
            if (run/'browser-progress.json').exists():private(run/('browser-progress-'+secrets.token_hex(3)+'.json'),(run/'browser-progress.json').read_text())
            fixture=json.loads(read(run/'browser.json'))
        else:
            assert request_http(endpoint)[0]==HTTPStatus.UNAUTHORIZED
            assert request_http(endpoint,tokens['ordinary'])[0]==HTTPStatus.FORBIDDEN
            for kind in ('owner','narrow'):
                status,data,headers=request_http(endpoint,tokens[kind]);assert status==HTTPStatus.OK and data['menus']==[] and headers.get('Cache-Control')=='no-store'
            record('actual HTTP authentication and zero published menus; no-store and management-only read')
            old=sql("SELECT count(*) FROM auth_governance.role_version WHERE tenant_id='%s';"%tenant)
            menus=[{'code':'member','parent':None,'route':None,'any_of':[]},{'code':'members','parent':'member','route':'/operations/members','any_of':['commerce.member.read','commerce.member.create']},{'code':'directory','parent':None,'route':'/operations/directory','any_of':['commerce.merchant.read','commerce.store.directory.read']}]
            next_manifest={**manifest,'manifest_version':2,'menus':menus}
            # 真实HTTP Owner发布必须带X-Command-Id；不用绕过身份的SQL伪造快照。
            conn=http.client.HTTPConnection('127.0.0.1',ADMIN,timeout=15);conn.request('POST','/api/governance/v1/catalog/publish',json.dumps(next_manifest),{'Authorization':'Bearer '+tokens['owner'],'Content-Type':'application/json','X-Command-Id':str(uuid.uuid4())});resp=conn.getresponse();assert resp.status==HTTPStatus.OK;resp.read();conn.close()
            owner=request_http(endpoint,tokens['owner'])[1];narrow=request_http(endpoint,tokens['narrow'])[1]
            assert owner['menus']==narrow['menus'] and len(owner['menus'])==3 and owner['manifest_version']==2 and len(owner['capabilities'])==4
            assert [c['code'] for c in narrow['capabilities'] if c['grantable']]==['commerce.member.read']
            assert sql("SELECT count(*) FROM auth_governance.role_version WHERE tenant_id='%s';"%tenant)==old
            assert request_http(endpoint.replace('environment=test','environment=prod'),tokens['owner'])[0]==HTTPStatus.FORBIDDEN
            record('real Owner publishes fixed actual menu routes; narrow ceiling flags differ without implied Grant')
            role=request_http('/api/governance/v1/access/roles',tokens['owner'],{**p,'command_id':str(uuid.uuid4()),'role_code':'member_reader','role_version':1,'capabilities':['commerce.member.read']})[1]
            mixed=request_http('/api/governance/v1/access/roles',tokens['owner'],{**p,'command_id':str(uuid.uuid4()),'role_code':'mixed_reader','role_version':1,'capabilities':['commerce.member.read','commerce.store.directory.read']})[1]
            strict=request_http('/api/governance/v1/access/enable-strict',tokens['owner'],{**p,'command_id':str(uuid.uuid4())});assert strict[0]==HTTPStatus.ACCEPTED
            record('actual HTTP role versions and strict partition prerequisites created')
            fixture={'run':str(run),'origin':f'http://127.0.0.1:{UI}','admin':f'http://127.0.0.1:{ADMIN}','authority':ISSUER,'client':client['name'],'users':users,'partition':p,'role':role,'mixed_role':mixed,'catalog':owner}
            private(run/'browser.json',json.dumps(fixture))
        if not args.resume_verify:
            env=dict(os.environ,VITE_CASDOOR_AUTHORITY=ISSUER,VITE_CASDOOR_CLIENT_ID=client['name'])
            with (run/('frontend-build-'+secrets.token_hex(3)+'.log')).open('wb') as log:subprocess.run(['npm','run','build'],cwd=ROOT/'auth-console',env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
            sources={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (ROOT/'auth-console/src').rglob('*') if f.is_file()}
            assets={str(f.relative_to(ROOT/'auth-console/dist')):hashlib.sha256(f.read_bytes()).hexdigest() for f in (ROOT/'auth-console/dist').rglob('*') if f.is_file()}
            private(run/('artifacts-'+secrets.token_hex(3)+'.json'),json.dumps({'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'frontend_sources':sources,'frontend_assets':assets}))
            script=ROOT/'deploy/governance-integrated-resources-static.mjs';output=(run/('static-'+secrets.token_hex(3)+'.log')).open('wb');processes.append(subprocess.Popen(['node',str(script)],cwd=ROOT,env=dict(env,IR_UI=str(UI),IR_ADMIN=str(ADMIN)),stdout=output,stderr=subprocess.STDOUT))
            for _ in range(80):
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{UI}/healthz',timeout=1) as r:
                        if r.status==HTTPStatus.OK:break
                except OSError:pass
                time.sleep(.1)
            browser_log=run/('browser-'+secrets.token_hex(3)+'.log')
            result=subprocess.run(['node',str(ROOT/'deploy/governance-integrated-resources-ui.mjs')],cwd=ROOT,env=dict(env,IR_RUN=str(run),IR_ATTEMPT=secrets.token_hex(3),IR_PLAYWRIGHT_MODULE=args.playwright_module),stdout=browser_log.open('wb'),stderr=subprocess.STDOUT,timeout=240)
            if result.returncode:raise RuntimeError('actual browser failed; inspect '+str(browser_log))
        state=json.loads(sql("SELECT json_build_object('roles',(SELECT json_agg(row_to_json(r)) FROM auth_governance.role_version r WHERE r.tenant_id='%s'),'grants',(SELECT json_agg(row_to_json(g)) FROM auth_governance.access_grant g WHERE g.tenant_id='%s'),'scopes',(SELECT json_agg(row_to_json(s)) FROM auth_governance.grant_scope s WHERE s.tenant_id='%s'),'policies',(SELECT json_agg(row_to_json(p)) FROM auth_governance.request_policy p WHERE p.tenant_id='%s'));"%(tenant,tenant,tenant,tenant)))
        private(run/('final-sql-'+secrets.token_hex(3)+'.json'),json.dumps(state));assert len(state['roles'])==3 and len(state['grants'])==1 and len(state['scopes'])==1 and len(state['policies'])==1
        created=next(r for r in state['roles'] if r['role_code']=='ir_ui_reader');assert json.loads(created['capabilities_json'])==['commerce.member.read']
        grant,scope,policy=state['grants'][0],state['scopes'][0],state['policies'][0]
        expected={'version':1,'resourceType':'commerce_member','clauses':[{'kind':'TENANT_ALL','values':[],'includeRoot':False}]}
        assert scope['resource_type']=='commerce_member' and scope['grant_id']==grant['id'] and grant['membership_id']==users['ordinary']['member']
        assert grant['role_id']==policy['role_id']==created['id'] and json.loads(scope['rule_json'])==json.loads(policy['scope_json'])==expected
        assert policy['approver_membership_id']==users['owner']['member'] and policy['enabled'] is True
        record('UI commands persist exact selected capabilities, fixed role/scope and one Grant/policy')
        sql("UPDATE auth_governance.access_delegation SET enabled=false WHERE membership_id='%s';"%users['narrow']['member']);assert request_http(endpoint,tokens['narrow'])[0]==HTTPStatus.FORBIDDEN
        record('real delegated manager revocation rejects subsequent published catalog read')
        private(run/'result.json',json.dumps({'status':'PASS','checks':checks,'browser_result':'browser-result.json','visual_review':'PENDING','partition':p}));print('IR_RUN='+str(run));print('PASS HTTP/PKCE/SQL; visual image review still required')
    finally:
        for proc in reversed(processes):
            if proc.poll() is None:
                proc.terminate()
                try:proc.wait(timeout=10)
                except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=5)
if __name__=='__main__':main()
