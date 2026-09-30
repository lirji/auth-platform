#!/usr/bin/env python3
"""P7本地有界加固验收。只写新建隔离库/夹具，所有证据0600；不接管运行商城。"""
import argparse, base64, collections, concurrent.futures, hashlib, http.client, http.server, importlib.util, json, math, os, secrets, socket, subprocess, threading, time, urllib.parse, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from http import HTTPStatus

ROOT=Path(__file__).resolve().parents[1]
PG='auth-gov-p7-pending'
PG_PORT=45433
GRAPH_IMAGE='authzed/spicedb@sha256:aa96009a0477f8a759149823407d47ad16d1a74390bae3102b3b0b7143502764'

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

up=module('p7_access',ROOT/'deploy/governance-access-smoke.py');h=up.h

def uid():return str(uuid.uuid4())
def now(seconds=0):return (datetime.now(timezone.utc)+timedelta(seconds=seconds)).isoformat(timespec='milliseconds').replace('+00:00','Z')

def percentile(values,p):
    """固定nearest-rank口径，保留失败样本延迟，避免只统计成功请求。"""
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)]

class GraphProxy:
    """仅转发本次图端点；故障在代理注入，不停共享SpiceDB。"""
    def __init__(self,port,target):
        self.failed=False;self.target=target;self.calls=0;owner=self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                size=int(self.headers.get('Content-Length','0'))
                if size>262144:self.send_error(413);return
                body=self.rfile.read(size);owner.calls+=1
                if owner.failed:
                    self.send_response(503);self.end_headers();self.wfile.write(b'{}');return
                conn=http.client.HTTPConnection('127.0.0.1',target,timeout=5)
                try:
                    conn.request('POST',self.path,body,{'Authorization':self.headers.get('Authorization',''),'Content-Type':'application/json'})
                    r=conn.getresponse();data=r.read(1048577);self.send_response(r.status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(data)
                finally:conn.close()
            def log_message(self,*args):pass
        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',port),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(timeout=5)

class IdentityProxy:
    """本工具JVM专用HTTP代理，模拟IdP故障，不停止共享Casdoor。"""
    def __init__(self,port,identity_port=18090):
        self.identity_port=identity_port
        self.failed=False;self.introspection_fault=False;self.responses=collections.Counter();self.lock=threading.Lock();owner=self
        class Handler(http.server.BaseHTTPRequestHandler):
            def route(self):
                url=urllib.parse.urlsplit(self.path)
                if url.scheme!='http' or url.hostname not in ('localhost','127.0.0.1') or url.port not in (owner.identity_port,18543,18545,18546,18547):self.send_error(403);return
                size=int(self.headers.get('Content-Length','0'))
                if size>262144:self.send_error(413);return
                body=self.rfile.read(size) if size else None
                if owner.failed and url.port==owner.identity_port:self.send_response(503);self.end_headers();self.wfile.write(b'{}');return
                if owner.introspection_fault and url.path=='/api/login/oauth/introspect':
                    self.send_response(HTTPStatus.OK);self.end_headers();self.wfile.write(b'{"status":"error","msg":"isolated dependency fault"}');return
                conn=http.client.HTTPConnection(url.hostname,url.port,timeout=5)
                try:
                    headers={k:v for k,v in self.headers.items() if k.lower() not in ('host','connection','proxy-connection')}
                    conn.request(self.command,url.path+('?' + url.query if url.query else ''),body,headers)
                    r=conn.getresponse();data=r.read(1048577)
                    if url.path=='/api/login/oauth/introspect':
                        # 只保留有限类别计数，禁止记录Token、用户事实或上游错误文本。
                        try:
                            facts=json.loads(data)
                            if not isinstance(facts,dict):category='MALFORMED'
                            elif facts.get('status')=='error' or 'error' in facts:category='ERROR_ENVELOPE'
                            elif facts.get('active') is True:category='ACTIVE'
                            elif facts.get('active') is False:category='INACTIVE'
                            else:category='MALFORMED'
                        except ValueError:category='MALFORMED'
                        if r.status!=HTTPStatus.OK:category='HTTP_ERROR'
                        with owner.lock:owner.responses[category]+=1
                    self.send_response(r.status);self.send_header('Content-Type',r.getheader('Content-Type','application/json'));self.end_headers();self.wfile.write(data)
                finally:conn.close()
            do_GET=route
            do_POST=route
            def log_message(self,*args):pass
        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',port),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(timeout=5)

class Rehearsal:
    """固定本地资源与私有恢复检查点，输入不接受任意生产库。"""
    def __init__(self,samples,isolated_identity=False,current_only=False):
        self.source_hash=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();self.samples=samples;self.suffix=secrets.token_hex(6);self.run=ROOT/'.local/governance/p7'/self.suffix;self.run.mkdir(parents=True,mode=0o700)
        self.checks=[];self.processes=[];self.containers=[];self.proxy=None;self.identity_proxy=None;self.started=now()
        self.isolated_identity=isolated_identity;self.current_only=current_only
        self.identity_port=18094 if isolated_identity else 18090;self.issuer='http://localhost:'+str(self.identity_port)
        self.management_config=ROOT/'.local/governance/casdoor-isolated/management-client.json'
        self.network=None
        self.jars={k:ROOT/f'auth-platform-{k}/target/auth-platform-{k}-0.1.0-SNAPSHOT.jar' for k in ('server','admin')}
    def prepare_database(self):
        """在main的finally保护内创建资源，启动失败也停止本次已创建容器。"""
        global PG
        PG='auth-gov-p7-pg-'+self.suffix
        pg_env=self.run/'postgres.env';h.private(pg_env,'POSTGRES_USER=p7owner\nPOSTGRES_PASSWORD='+secrets.token_hex(24)+'\nPOSTGRES_DB=postgres\n')
        image=subprocess.check_output(['docker','inspect','dev-infra-postgres16-1','--format','{{.Config.Image}}'],text=True).strip()
        subprocess.run(['docker','run','-d','--name',PG,'--label','auth-p7-run='+self.suffix,'--cpus=1','--memory=512m','--env-file',str(pg_env),'-p',f'127.0.0.1:{PG_PORT}:5432',image],check=True,stdout=subprocess.DEVNULL);self.containers.append(PG)
        # 初始化临时服务只监听Unix socket；等正式TCP服务，避免开库撞上初始化关闭窗口。
        for attempt in range(100):
            if subprocess.run(['docker','exec',PG,'pg_isready','-h','127.0.0.1','-U','p7owner','-d','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
            time.sleep(.2)
        else:raise RuntimeError('owned PostgreSQL startup timeout')
        self.db=self.database('authority');self.fixture=None
    def record(self,name,**detail):
        self.checks.append({'check':name,'result':'PASS',**detail});h.private(self.run/f'checkpoint-{len(self.checks):03}.json',json.dumps(self.checks))
    def database(self,label):
        directory=self.run/label
        subprocess.run(['python3',str(ROOT/'deploy/governance-test-db.py'),'--directory',str(directory),'--container',PG,'--port',str(PG_PORT)],check=True,stdout=subprocess.DEVNULL)
        spec=json.loads(h.read_private(directory/'database.json'));spec['config']=directory/'database.properties';return spec
    def sql(self,spec,statement,expected=0):
        result=subprocess.run(['docker','exec','-i',PG,'sh','-c','exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At','p7',spec['database']],input=statement,text=True,capture_output=True,timeout=25)
        if result.returncode!=expected:raise RuntimeError('owned SQL operation failed; no payload printed')
        return result.stdout.strip()
    def cli(self,name,*args):
        package='com.lrj.authz.admin.governance.' if name=='ReliableProjectionCli' else 'com.lrj.authz.governance.cli.'
        with os.fdopen(os.open(self.run/(name+'-'+secrets.token_hex(4)+'.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            result=subprocess.run(['java','-Dloader.main='+package+name,'-cp',str(self.jars['admin']),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,args)],stdout=log,stderr=subprocess.STDOUT,timeout=45)
        if result.returncode:raise RuntimeError('CLI failed: '+name+'; inspect private log')
    def graph(self,label,port):
        spec=self.database(label);key=secrets.token_urlsafe(48);name='auth-gov-p7-'+self.suffix+'-'+label
        env=self.run/(label+'.env');h.private(env,'SPICEDB_DATASTORE_ENGINE=postgres\nSPICEDB_DATASTORE_CONN_URI=postgres://'+spec['username']+':'+spec['password']+'@host.docker.internal:'+str(PG_PORT)+'/'+spec['database']+'?sslmode=disable\nSPICEDB_GRPC_PRESHARED_KEY='+key+'\n')
        with os.fdopen(os.open(self.run/(label+'-migration.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            subprocess.run(['docker','run','--rm','--env-file',str(env),GRAPH_IMAGE,'datastore','migrate','head'],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
        subprocess.run(['docker','run','-d','--name',name,'--label','auth-p7-run='+self.suffix,'--cpus=1','--memory=512m','--env-file',str(env),'-p',f'127.0.0.1:{port}:8443',GRAPH_IMAGE,'serve','--http-enabled','--http-addr=:8443','--grpc-addr=:50051','--datastore-conn-pool-read-max-open=4','--datastore-conn-pool-read-min-open=0','--datastore-conn-pool-write-max-open=2','--datastore-conn-pool-write-min-open=0'],check=True,stdout=subprocess.DEVNULL);self.containers.append(name)
        headers=[('Authorization','Bearer '+key)]
        for attempt in range(100):
            try:
                status,_=h.request(port,'/v1/schema/write',headers,json.dumps({'schema':(ROOT/'auth-platform-core/src/main/resources/schemas/governance-p3.zed').read_text()}).encode())
                if status==HTTPStatus.OK:break
            except OSError:pass
            time.sleep(.2)
        else:raise RuntimeError('new graph not ready')
        return {'port':port,'key':key,'config':f'graph.http=http://127.0.0.1:{port}\ngraph.key={key}\n','database':spec['database']}
    def start(self,kind,port,config,label,jar=None):
        with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',port))
        args=['java','-Xmx256m','-jar',str(jar or self.jars[kind]),'--server.address=127.0.0.1','--server.port='+str(port),'--server.tomcat.threads.max=32','--server.tomcat.max-connections=64','--server.tomcat.accept-count=16','--authz.governance.enabled=true','--authz.governance.configuration='+str(config),'--authz.governance.access.enabled=true','--authz.governance.scope.enabled=true']
        if kind==h.Application.SERVER:
            args[1:1]=['-Dhttp.proxyHost=127.0.0.1','-Dhttp.proxyPort=18548','-Dhttp.nonProxyHosts=']
            args+=['--authz.governance.executions.enabled=true','--management.server.address=127.0.0.1','--management.server.port='+str(port+100),'--management.endpoints.web.exposure.include=health,metrics','--authz.server.security.enabled=true','--authz.spicedb.endpoint=http://127.0.0.1:9']
        env=dict(os.environ,AUTHZ_SERVER_TOKEN=secrets.token_urlsafe(48))
        with os.fdopen(os.open(self.run/(label+'.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,env=env)
        self.processes.append(p)
        for attempt in range(180):
            if p.poll() is not None:raise RuntimeError('owned '+label+' exited; inspect private log')
            try:
                status,_=h.request(port+100 if kind==h.Application.SERVER else port,'/actuator/health')
                if status==HTTPStatus.OK:return p
            except (OSError,ValueError):pass
            time.sleep(.25)
        raise RuntimeError('owned startup timed out: '+label)
    def call(self,port,path,body=None,headers=None,status=200):
        actual,value=h.request(port,path,headers or self.admin,None if body is None else json.dumps(body).encode())
        if actual!=status:raise RuntimeError('HTTP '+path+' expected '+str(status)+' got '+str(actual)+' code='+str(value.get('code')))
        return value
    def check(self,port,store='store-live',headers=None,status=200):
        body={'check':{'tenant_id':self.tenant,'expected_membership_generation':1,'request_id':uid(),'capability':'commerce.catalog.operate','resource_type':'store'},'facts':{'tenant_id':self.tenant,'resource_type':'store','resource_id':store,'resource_version':0,'owner_principal_id':None,'department_id':None,'department_ancestors':[],'store_id':store,'supplier_id':None}}
        return self.call(port,'/internal/governance/v1/access/check-resource',body,headers or self.dual,status)
    def await_new_grant(self,port,store):
        """P3允许双水位短暂保守DENY；只探测新授权就绪，不重试容量样本或撤权断言。"""
        started=time.monotonic();observations=[]
        for attempt in range(12):
            try:decision=self.check(port,store)['decision']
            except RuntimeError as failure:
                if 'code=AUTHZ_STATE_NOT_READY' not in str(failure):raise
                decision='AUTHZ_STATE_NOT_READY'
            if decision not in ('ALLOW','DENY','AUTHZ_STATE_NOT_READY'):raise RuntimeError('unexpected new grant readiness result')
            observations.append(decision)
            if decision=='ALLOW':return {'instance':port,'observations':observations,'elapsed_ms':round((time.monotonic()-started)*1000,3)}
            if attempt<11:time.sleep(.25)
        raise RuntimeError('new grant did not converge within bounded readiness probes')
    def projection(self,db=None,graph=None):
        for kind in ('POLICY','DIRECTORY'):
            file=self.run/('projection-'+secrets.token_hex(4)+'.properties');h.private(file,h.read_private((db or self.db)['config'])+(graph or self.graph1)['config']+h.props({**self.access,'projection.kind':kind,'jdbc.maximum-pool-size':2}));self.cli('ReliableProjectionCli',file)
    def prepare_identity(self):
        """身份服务使用本次PG与私有网络；不修改现有18090实例或共享数据库。"""
        if not self.isolated_identity:return
        self.network='auth-p7-'+self.suffix
        subprocess.run(['docker','network','create','--label','auth-p7-run='+self.suffix,self.network],check=True,stdout=subprocess.DEVNULL)
        subprocess.run(['docker','network','connect',self.network,PG],check=True)
        spec=self.database('identity-database')
        name=spec['database'].replace('auth_gov_p1_test_','auth-gov-casdoor-p1-')
        self.containers.append(name) # 预先登记唯一自有名称，子工具启动中途失败也可收尾。
        with os.fdopen(os.open(self.run/'identity-runtime.log',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            subprocess.run(['python3',str(ROOT/'deploy/governance-casdoor-isolation.py'),'--directory',str(spec['config'].parent),'--postgres-container',PG,'--postgres-host',PG,'--network',self.network,'--port',str(self.identity_port)],check=True,stdout=log,stderr=subprocess.STDOUT,timeout=120)
        self.management_config=spec['config'].parent/'casdoor-isolated/management-client.json'
        result=json.loads((self.management_config.parent/'runtime-result.json').read_text())
        self.record('dedicated identity instance uses owned PostgreSQL and private network',identity_port=self.identity_port,identity_image=result['image'],network=self.network,database=result['database'])
    def setup(self):
        self.prepare_database();self.prepare_identity()
        subprocess.run(['python3',str(ROOT/'deploy/governance-casdoor-fixture.py'),'--base',self.issuer,'--phase','tokens','--directory',str(self.run/'identity'),'--management-config',str(self.management_config)],check=True,stdout=subprocess.DEVNULL)
        self.fixture=json.loads(h.read_private(self.run/'identity/casdoor.json'));ops=json.loads(h.read_private(self.management_config))
        self.idp_tool=module('p7_idp_'+self.suffix,ROOT/'deploy/governance-casdoor-fixture.py');self.idp_tool.BASE=self.issuer
        self.idp_auth='Basic '+base64.b64encode((ops['client_id']+':'+ops['client_secret']).encode()).decode()
        self.change_signer('a')
        self.tenant=uid();self.partition={'tenant_id':self.tenant,'application_id':'commerce','environment':'p7-'+self.suffix};self.members={};principals={}
        for kind in ('internal','external'):
            principals[kind]=uid();self.members[kind]=uid();file=self.run/(kind+'.properties')
            h.private(file,h.props({'command.id':uid(),'operator.ref':'p7-fixture','tenant.id':self.tenant,'tenant.code':'p7-'+self.suffix,'principal.id':principals[kind],'issuer':self.issuer,'subject':self.fixture['users'][kind]['id'],'membership.id':self.members[kind],'valid.from':'2020-01-01T00:00:00Z','source.system':'p7-fixture','source.tenant.ref':self.tenant,'source.subject.ref':kind}));self.cli('GovernanceCli','bootstrap',self.db['config'],file)
        db=h.read_private(self.db['config']);catalog=self.run/'catalog.properties'
        h.private(catalog,db+h.props({'catalog.application':'commerce','catalog.owner-principal':principals['internal'],'catalog.entry-origin':'http://127.0.0.1:18661','catalog.operator':'p7-fixture','catalog.command':uid(),'catalog.owner-issuer':self.issuer,'catalog.owner-subject':self.fixture['users']['internal']['id']}));self.cli('CatalogCli','register',catalog,'configured')
        manifest=self.run/'manifest.json';h.private(manifest,json.dumps({'schema_version':'1','application':'commerce','manifest_version':1,'capabilities':[{'code':'commerce.catalog.operate','resource_type':'store','risk_level':'HIGH'}],'menus':[]}));self.cli('CatalogCli','publish',catalog,manifest)
        self.access={'access.tenant':self.tenant,'access.application':'commerce','access.environment':self.partition['environment'],'access.manager':self.members['internal'],'access.generation':1,'access.capabilities':'commerce.catalog.operate','access.max-duration-seconds':3600,'access.operator':'p7-fixture','access.command':uid()}
        file=self.run/'access.properties';h.private(file,db+h.props(self.access));self.cli('AccessBootstrapCli',file)
        self.graph1=self.graph('graph-original',18545);self.proxy=GraphProxy(18547,18545);self.identity_proxy=IdentityProxy(18548,self.identity_port)
        self.legacy=h.read_private(ROOT/'.local/governance/p2/graph/graph.properties')
        def authority(kind):
            c=self.fixture['clients'][kind];return {'issuer':self.issuer,'jwks.uri':self.issuer+'/.well-known/'+c['name']+'/jwks','audience':c['name'],'client.id':c['name'],'client.secret':c['secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret'],'maximum-concurrent':8}
        self.user_authority=authority('business');self.management_authority=authority('management')
        self.service=secrets.token_urlsafe(48);self.server_settings={'service.count':1,'service.1.id':'commerce-p7','service.1.application-id':'commerce','service.1.environment':self.partition['environment'],'service.1.operation':'context.resolve','service.1.credential-sha256':hashlib.sha256(self.service.encode()).hexdigest(),'access.check.callers':'commerce-p7','scope.check.callers':'commerce-p7','execution.callers':'commerce-p7','scope.owner.commerce':'store,product'}
        self.server_settings.update({'service.1.user.'+k:v for k,v in self.user_authority.items()})
        self.admin=[('Authorization','Bearer '+up.token(self.issuer,self.fixture,'management','internal'))];self.user=up.token(self.issuer,self.fixture,'business','external');self.dual=[('Authorization','Bearer '+self.service),('X-User-Access-Token',self.user)]
        # 权限按用途拆分：检查只读权威+插入引用；管理DML无DDL；迁移Owner只留给CLI。
        self.reader='p7_read_'+self.suffix;self.reader_password=secrets.token_hex(24);self.manager='p7_manage_'+self.suffix;self.manager_password=secrets.token_hex(24)
        self.sql(self.db,f"CREATE ROLE {self.reader} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{self.reader_password}'; CREATE ROLE {self.manager} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{self.manager_password}'; REVOKE ALL ON DATABASE {self.db['database']} FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT CONNECT ON DATABASE {self.db['database']} TO {self.reader},{self.manager}; GRANT USAGE ON SCHEMA auth_governance TO {self.reader},{self.manager}; GRANT SELECT ON ALL TABLES IN SCHEMA auth_governance TO {self.reader}; GRANT INSERT ON auth_governance.execution_reference TO {self.reader}; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA auth_governance TO {self.manager}; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA auth_governance TO {self.manager};")
        self.reader_db=h.props({'jdbc.url':f'jdbc:postgresql://127.0.0.1:{PG_PORT}/'+self.db['database'],'jdbc.username':self.reader,'jdbc.password':self.reader_password,'jdbc.maximum-pool-size':6})
        manager_db=h.props({'jdbc.url':f'jdbc:postgresql://127.0.0.1:{PG_PORT}/'+self.db['database'],'jdbc.username':self.manager,'jdbc.password':self.manager_password,'jdbc.maximum-pool-size':4})
        graph_scope='scope.graph.http=http://127.0.0.1:18547\nscope.graph.key='+self.graph1['key']+'\n'
        self.server_config=self.run/'server.properties';h.private(self.server_config,self.reader_db+self.legacy+graph_scope+h.props(self.server_settings))
        admin_config=self.run/'admin.properties';h.private(admin_config,manager_db+self.legacy+graph_scope+h.props(self.management_authority))
        self.admin_process=self.start('admin',18172,admin_config,'admin');self.a=self.start('server',18170,self.server_config,'server-a');self.baseline_jar=self.jars['server'] if self.current_only else ROOT/'.local/governance/p7-baseline-source/auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar';self.b=self.start('server',18171,self.server_config,'server-b-current' if self.current_only else 'server-b-p6',self.baseline_jar)
        prefix='/api/governance/v1/access'
        self.call(18172,prefix+'/enable-strict',{**self.partition,'command_id':uid(),'kind':None},status=202)
        self.role=self.call(18172,prefix+'/roles',{**self.partition,'command_id':uid(),'role_code':'p7-catalog','role_version':1,'capabilities':['commerce.catalog.operate']})['id'];self.grants={}
        for store,seconds in [('store-live',3000),('store-revoke',3000),('store-expire',45)]:
            body={**self.partition,'command_id':uid(),'member_id':self.members['external'],'member_generation':1,'role_id':self.role,'scope_rule':{'version':1,'resource_type':'store','clauses':[{'kind':'SPECIFIED_STORES','values':[store],'include_root':False}]},'source_id':'p7-'+store,'valid_from':now(-2),'valid_to':now(seconds)}
            self.grants[store]=self.call(18172,prefix+'/scoped-grants',body,status=202)['id']
        self.projection()
    def run_checks(self):
        for port in (18170,18171):assert self.check(port)['decision']=='ALLOW'
        self.record('two current auth JVMs allow current scoped grant' if self.current_only else 'two separate auth JVMs with P6 and P7 artifacts allow current scoped grant',pids=[self.a.pid,self.b.pid],baseline_ref='CURRENT_SAME_ARTIFACT' if self.current_only else 'c8df1b19d309580d277021089c0a837143fec7b0',baseline_jar_sha256=hashlib.sha256(self.baseline_jar.read_bytes()).hexdigest(),current_jar_sha256=hashlib.sha256(self.jars['server'].read_bytes()).hexdigest())
        assert self.check(18170,'store-outside')['decision']=='DENY';self.check(18170,headers=[('Authorization','Bearer '+secrets.token_urlsafe(48)),('X-User-Access-Token',self.user)],status=401)
        self.record('out-of-scope deny and unknown service authn distinct')
        for role in (self.reader,self.manager):
            assert self.sql(self.db,f"SELECT has_schema_privilege('{role}','auth_governance','CREATE');")=='f'
        assert self.sql(self.db,f"SELECT has_table_privilege('{self.reader}','auth_governance.access_grant','UPDATE');")=='f'
        self.sql(self.db,f'SET ROLE {self.reader}; UPDATE auth_governance.access_grant SET version=version WHERE false;',expected=3)
        self.record('reader cannot mutate grants; reader and manager cannot DDL')
        self.call(18170,'/v1/check',{},headers=[('Authorization','Bearer invalid')],status=401)
        self.call(18170,'/actuator/metrics',headers=[('Accept','application/json')],status=404)
        self.record('legacy API authenticated and management metrics on separate loopback port')
        self.proxy.failed=True;self.check(18170,status=503);self.proxy.failed=False
        assert self.check(18171)['decision']=='ALLOW';self.record('graph outage is ERROR and recovery does not use cached allow')
        self.identity_proxy.failed=True;self.check(18170,status=503);self.identity_proxy.failed=False
        assert self.check(18170)['decision']=='ALLOW';self.record('Casdoor outage rejects even previously accepted token; introspection has no positive cache')
        self.identity_proxy.introspection_fault=True
        try:
            assert self.check(18170,status=503)['code']=='DEPENDENCY_UNAVAILABLE'
            assert self.check(18171,status=503 if self.current_only else 401)['code']==('DEPENDENCY_UNAVAILABLE' if self.current_only else 'INVALID_CREDENTIAL')
        finally:self.identity_proxy.introspection_fault=False
        assert self.check(18170)['decision']=='ALLOW'
        self.record('HTTP200 issuer error envelope is dependency failure on both current instances; recovery rechecks' if self.current_only else 'HTTP200 issuer error envelope is dependency failure on fixed instance; P6 reproduces misclassification; recovery rechecks')
        self.sql(self.db,f"REVOKE CONNECT ON DATABASE {self.db['database']} FROM {self.reader}; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='{self.db['database']}' AND usename='{self.reader}';")
        try:self.check(18170,status=503)
        finally:self.sql(self.db,f"GRANT CONNECT ON DATABASE {self.db['database']} TO {self.reader};")
        assert self.check(18171)['decision']=='ALLOW';self.record('owned reader database outage fails closed; reconnect restores current checks')
        metric=self.call(18270,'/actuator/metrics/authz.governance.requests',headers=[('Accept','application/json')]);tags={x['tag']:x['values'] for x in metric['availableTags']}
        assert {'ALLOW','DENY','AUTHN','ERROR'}.issubset(tags['outcome']);assert set(tags)=={'operation','outcome'}
        self.record('live metrics separate ALLOW DENY AUTHN ERROR without tenant/user labels',tags=tags)
        h.stop(self.admin_process)
        assert self.check(18171)['decision']=='ALLOW';self.record('management stopped; daily checks continue without OA or admin call')
        self.admin_process=self.start('admin',18172,self.run/'admin.properties','admin-resumed')
        measurements=[]
        for concurrency in (1,4,8):
            before=self.identity_proxy.responses.copy()
            def one(index):
                start=time.monotonic();store='store-live' if index%5 else 'store-outside';port=18170+index%2
                try:
                    value=self.check(port,store);result=value['decision'];assert result==('ALLOW' if store=='store-live' else 'DENY')
                except (RuntimeError,OSError,AssertionError) as failure:
                    return {'ms':round((time.monotonic()-start)*1000,3),'result':'ERROR','instance':port,'failure_type':type(failure).__name__,'code':str(failure).split('code=')[-1] if 'code=' in str(failure) else 'TRANSPORT_OR_PROTOCOL'}
                return {'ms':round((time.monotonic()-start)*1000,3),'result':result,'instance':port}
            start=time.monotonic()
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:rows=list(executor.map(one,range(self.samples)))
            elapsed=time.monotonic()-start;values=[r['ms'] for r in rows]
            measurements.append({'concurrency':concurrency,'requests':len(rows),'elapsed_seconds':round(elapsed,3),'throughput_rps':round(len(rows)/elapsed,3),'p50_ms':percentile(values,.5),'p95_ms':percentile(values,.95),'p99_ms':percentile(values,.99),'errors':sum(r['result']=='ERROR' for r in rows),'error_codes':dict(collections.Counter(r['code'] for r in rows if r['result']=='ERROR')),'introspection_responses':dict(self.identity_proxy.responses-before)})
            h.private(self.run/f'load-{concurrency}.json',json.dumps(rows))
        self.load={'fixture':'synthetic: 1 tenant, 1 app, 2 members, 3 direct scoped grants, 4 resource IDs; 80% hot allowed/20% denied','instances':2,'instance_mode':'CURRENT_ONLY' if self.current_only else 'P6_P7_MIXED','identity_mode':'DEDICATED_IDP_AND_PG' if self.isolated_identity else 'EXISTING_IDP_SHARED_PG','samples':measurements,'production_slo':'UNDEFINED','includes':'HTTP + live Casdoor introspection + DB snapshots + graph + response; no retries'}
        self.record('bounded load measured including all errors',measurements=measurements)
        body={**self.partition,'command_id':uid(),'member_id':self.members['external'],'member_generation':1,'role_id':self.role,'scope_rule':{'version':1,'resource_type':'store','clauses':[{'kind':'SPECIFIED_STORES','values':['store-mixed'],'include_root':False}]},'source_id':'p7-mixed-revoke','valid_from':now(-2),'valid_to':now(300)}
        mixed=self.call(18172,'/api/governance/v1/access/scoped-grants',body,status=202)['id'];self.projection()
        readiness=[self.await_new_grant(port,'store-mixed') for port in (18170,18171)]
        self.record('new grant converges within bounded probes before strict revoke assertion',readiness=readiness)
        self.call(18172,'/api/governance/v1/access/revoke',{**self.partition,'command_id':uid(),'grant_id':mixed,'expected_version':1});self.projection()
        for port in (18170,18171):
            value=self.check(port,'store-mixed');assert value['decision']=='DENY',f'completed revoke on {port}: '+str(value.get('decision'))
        self.record('both current processes enforce completed revocation' if self.current_only else 'P6 and P7 mixed-version processes both enforce completed revocation')
        self.a.kill();self.a.wait(timeout=10);assert self.check(18171)['decision']=='ALLOW';self.a=self.start('server',18170,self.server_config,'server-a-restarted');assert self.check(18170)['decision']=='ALLOW'
        self.record('kill one auth JVM; other remains usable; restarted instance rechecks')
    def idp(self,path,body=None,allow_error=False):
        return self.idp_tool.request(path,body,self.idp_auth,allow_error=allow_error)
    def change_signer(self,label,secret=None):
        # 只变更本次新建business应用；不触碰cert-built-in或其他应用。
        name='p7-cert-'+self.suffix+'-'+label;org=self.fixture['organization'];client=self.fixture['clients']['business']
        self.idp('add-cert',{'owner':org,'name':name,'displayName':'P7 isolated rotation','scope':'JWT','type':'x509','cryptoAlgorithm':'RS256','bitSize':2048,'expireInYears':1})
        path='get-application?'+urllib.parse.urlencode({'id':'admin/'+client['name']});app=self.idp(path)['data']
        if app['organization']!=org or not client['name'].endswith(org.removeprefix('gov-p1-')):raise RuntimeError('rotation fixture ownership mismatch')
        app['cert']=name
        if secret:app['clientSecret']=secret
        self.idp('update-application?'+urllib.parse.urlencode({'id':'admin/'+client['name']}),app)
    def rotation(self):
        old=self.service;new=secrets.token_urlsafe(48);self.server_settings['service.1.credential-sha256']=hashlib.sha256(new.encode()).hexdigest()
        graph_scope='scope.graph.http=http://127.0.0.1:18547\nscope.graph.key='+self.graph1['key']+'\n'
        config=self.run/'server-service-rotated.properties';h.private(config,self.reader_db+self.legacy+graph_scope+h.props(self.server_settings))
        new_headers=[('Authorization','Bearer '+new),('X-User-Access-Token',self.user)]
        h.stop(self.a);self.a=self.start('server',18170,config,'service-rotate-a')
        assert self.check(18170,headers=new_headers)['decision']=='ALLOW';self.check(18170,status=401)
        assert self.check(18171)['decision']=='ALLOW'
        self.record('rolling service rotation has explicit old-instance grace window')
        h.stop(self.b);self.b=self.start('server',18171,config,'service-rotate-b');self.service=new;self.dual=new_headers
        for port in (18170,18171):
            self.check(port,headers=[('Authorization','Bearer '+old),('X-User-Access-Token',self.user)],status=401)
            assert self.check(port)['decision']=='ALLOW'
        self.record('old service credential rejected by every current instance after retirement')
        old_user=self.user;client=self.fixture['clients']['business'];old_secret=client['secret'];new_secret=secrets.token_urlsafe(36)
        self.change_signer('b',new_secret);client['secret']=new_secret
        self.user=up.token(self.issuer,self.fixture,'business','external');self.dual=[('Authorization','Bearer '+new),('X-User-Access-Token',self.user)]
        self.server_settings['service.1.user.client.secret']=new_secret
        config=self.run/'server-identity-rotated.properties';h.private(config,self.reader_db+self.legacy+graph_scope+h.props(self.server_settings))
        for name,port in [('a',18170),('b',18171)]:
            h.stop(getattr(self,name));setattr(self,name,self.start('server',port,config,'identity-rotate-'+name))
            assert self.check(port)['decision']=='ALLOW'
            self.check(port,headers=[('Authorization','Bearer '+new),('X-User-Access-Token',old_user)],status=401)
        conn=http.client.HTTPConnection('localhost',self.identity_port,timeout=5)
        try:
            auth='Basic '+base64.b64encode((client['name']+':'+old_secret).encode()).decode()
            conn.request('POST','/api/login/oauth/introspect',urllib.parse.urlencode({'token':self.user,'token_type_hint':'access_token'}),{'Authorization':auth,'Content-Type':'application/x-www-form-urlencoded'})
            response=conn.getresponse();value=json.loads(response.read(65537))
            if response.status==HTTPStatus.OK and value.get('active') is True:raise RuntimeError('retired OAuth secret still introspects')
        finally:conn.close()
        self.server_config=config
        h.private(self.run/'identity-rotation.json',json.dumps({'service_credential':new,'fixture':self.fixture,'current_user_token':self.user}))
        self.record('real Casdoor signing certificate and client secret rotated; retired token and secret rejected')
    def recovery(self):
        # 一致备份位于有限实验事件之前；之后仅有明确持久化的撤销命令，日志范围可完整枚举。
        backup=self.run/'authority-before-revoke.dump';started=time.monotonic()
        with os.fdopen(os.open(backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb') as out:
            subprocess.run(['docker','exec',PG,'pg_dump','-U','p7owner','-d',self.db['database'],'--format=custom','--no-owner','--no-acl','--schema=auth_governance'],stdout=out,stderr=subprocess.PIPE,check=True,timeout=30)
        backup_hash=hashlib.sha256(backup.read_bytes()).hexdigest()
        event={**self.partition,'command_id':uid(),'grant_id':self.grants['store-revoke'],'expected_version':1}
        h.private(self.run/'recovery-delta.json',json.dumps({'format':'p7-bounded-recovery-delta/v1','backup_sha256':backup_hash,'events':[event],'complete_for_fixture':True,'captured_at':now()}))
        self.call(18172,'/api/governance/v1/access/revoke',event);self.projection()
        for port in (18170,18171):assert self.check(port,'store-revoke')['decision']=='DENY'
        self.record('revocation is observed by both instances before backup restore')
        snapshot=self.sql(self.db,(ROOT/'deploy/governance-p7-observe.sql').read_text());h.private(self.run/'operational-snapshot.txt',snapshot)
        self.record('bounded readonly queue and fence snapshot captured without identities')
        target=self.database('restored-authority')
        with backup.open('rb') as data:
            subprocess.run(['docker','exec','-i',PG,'pg_restore','-U','p7owner','--role='+target['username'],'-d',target['database'],'--no-owner','--no-acl','--single-transaction'],stdin=data,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True,timeout=45)
        self.record('consistent pg_dump restored into new owned database; original database preserved',backup_sha256=backup_hash)
        # 离线恢复专用操作，不删除历史operation或receipt；旧执行者未获新图凭据。
        self.sql(target,"""BEGIN;
UPDATE auth_governance.policy_partition SET desired_epoch=desired_epoch+1,applied_epoch=0,state='UPDATING',zed_token=NULL;
UPDATE auth_governance.directory_fence SET desired_epoch=desired_epoch+1,applied_epoch=0,state='UPDATING',zed_token=NULL;
UPDATE auth_governance.projection_operation SET state='SUPERSEDED' WHERE state='PENDING';
UPDATE auth_governance.projection_stream SET worker_id=NULL,lease_generation=lease_generation+1,lease_until='-infinity',target_epoch=0,scan_cursor='',confirmed_batch=0,marker=NULL,zed_token=NULL,failures=0,next_attempt_at=clock_timestamp();
UPDATE auth_governance.access_grant SET zed_token=NULL;
COMMIT;""")
        assert self.sql(target,"SELECT count(*) FROM auth_governance.policy_partition WHERE state='READY' OR zed_token IS NOT NULL;")=='0'
        assert self.sql(target,"SELECT count(*) FROM auth_governance.directory_fence WHERE state='READY' OR zed_token IS NOT NULL;")=='0'
        # 缺少delta证明时工具不开放流量；本次完整范围只有所存的一个命令。
        delta=json.loads(h.read_private(self.run/'recovery-delta.json'))
        if delta['backup_sha256']!=backup_hash or not delta['complete_for_fixture'] or delta['events']!=[event]:raise RuntimeError('recovery delta incomplete')
        self.graph2=self.graph('graph-restored',18546)
        restored_db=h.read_private(target['config']);scope=''.join('scope.'+line+'\n' for line in self.graph2['config'].splitlines())
        admin=self.run/'restore-admin.properties';h.private(admin,restored_db+self.legacy+scope+h.props(self.management_authority));self.start('admin',18174,admin,'restore-admin')
        for item in delta['events']:
            self.call(18174,'/api/governance/v1/access/revoke',item)
            self.call(18174,'/api/governance/v1/access/revoke',item)
        self.record('backup-after revocation replayed idempotently before graph rebuild')
        config=self.run/'restore-server.properties';h.private(config,restored_db+self.legacy+scope+h.props(self.server_settings));self.start('server',18173,config,'restore-server')
        self.check(18173,status=503);self.record('restored authority remains fail-closed while new graph has no watermark')
        old_tokens=self.sql(self.db,"SELECT zed_token FROM auth_governance.policy_partition UNION SELECT zed_token FROM auth_governance.directory_fence;").splitlines()
        self.projection(target,self.graph2)
        new_tokens=self.sql(target,"SELECT zed_token FROM auth_governance.policy_partition UNION SELECT zed_token FROM auth_governance.directory_fence;").splitlines()
        assert new_tokens and all(x and x not in old_tokens for x in new_tokens)
        readiness_retries=0
        for attempt in range(12):
            try:
                assert self.check(18173,'store-live')['decision']=='ALLOW';break
            except RuntimeError as failure:
                if 'code=AUTHZ_STATE_NOT_READY' not in str(failure):raise
                readiness_retries+=1;time.sleep(.25)
        else:raise RuntimeError('restored HTTP readiness did not converge')
        assert self.check(18173,'store-revoke')['decision']=='DENY'
        assert self.check(18173,'store-expire')['decision']=='DENY'
        self.recovery_result={'backup_sha256':backup_hash,'restore_elapsed_seconds':round(time.monotonic()-started,3),'delta_events':1,'scope':'closed synthetic fixture with complete single post-backup revoke; not production RPO','new_graph_database':self.graph2['database'],'new_watermarks':len(new_tokens),'readiness_retries':readiness_retries,'production_rto_rpo':'UNDEFINED'}
        self.record('new graph watermarks established; active allows, revoked and expired remain denied',recovery=self.recovery_result)
    def finish(self):
        result={'result':'PASS','scope':'P7 local isolated baseline, not production accepted','source_sha256':self.source_hash,'identity_mode':'DEDICATED_IDP_AND_PG' if self.isolated_identity else 'EXISTING_IDP_SHARED_PG','instance_mode':'CURRENT_ONLY' if self.current_only else 'P6_P7_MIXED','started_at':self.started,'finished_at':now(),'run':str(self.run),'checks':self.checks,'capacity':self.load,'recovery':getattr(self,'recovery_result',None),'refs':{name:subprocess.check_output(['git','-C',str(path),'rev-parse','HEAD'],text=True).strip() for name,path in [('auth',ROOT),('commerce',ROOT.parent/'commerce-platform'),('oa',ROOT.parent/'oa-platform')]}}
        h.private(self.run/'result.json',json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({'result':'PASS','checks':len(self.checks),'evidence':str(self.run/'result.json')}))
    def close(self):
        for process in self.processes:h.stop(process)
        if self.proxy:self.proxy.close()
        if self.identity_proxy:self.identity_proxy.close()
        for name in reversed(self.containers):subprocess.run(['docker','stop','-t','3',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--samples',type=int,default=48);parser.add_argument('--isolated-identity',action='store_true',help='create owned Casdoor18094 and use owned PostgreSQL');parser.add_argument('--current-only',action='store_true',help='use current artifact for both JVMs');args=parser.parse_args()
    if not 16<=args.samples<=200:parser.error('samples must be bounded in [16,200]')
    test=Rehearsal(args.samples,args.isolated_identity,args.current_only)
    try:test.setup();test.run_checks();test.rotation();test.recovery();test.finish()
    finally:test.close()

if __name__=='__main__':
    try:main()
    except (OSError,ValueError,RuntimeError,AssertionError,subprocess.SubprocessError) as error:raise SystemExit('P7 stopped: '+type(error).__name__+' '+str(error))
