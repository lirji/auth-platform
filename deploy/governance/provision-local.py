#!/usr/bin/env python3
"""固定本地 Docker 治理环境引导；通过项目 CLI 迁移和写入，不直接改业务表。"""
from pathlib import Path
import argparse,hashlib,json,os,secrets,uuid,subprocess,urllib.request,urllib.parse,base64
# 此入口仅显式使用已有私密本地身份夹具；不生成或重置用户密码。
parser=argparse.ArgumentParser(description='初始化固定本地治理库、管理客户端与 commerce 示例')
parser.add_argument('--identity-directory',type=Path)
parser.add_argument('--output-directory',type=Path)
parser.add_argument('--runtime-env',type=Path)
parser.add_argument('--activate',action='store_true',help='通过真实 PKCE 登录，仅启用本地分区严格投影')
args=parser.parse_args()
if args.runtime_env:
 env=dict(line.split('=',1) for line in args.runtime_env.read_text().splitlines() if '=' in line and not line.startswith('#'))
 args.identity_directory=Path(env['GOVERNANCE_IDENTITY_DIR'])
 args.output_directory=Path(env['GOVERNANCE_STATE_DIR'])
if not args.identity_directory or not args.output_directory:parser.error('identity-directory/output-directory or runtime-env required')
source=args.identity_directory.resolve()
root=args.output_directory.resolve();root.mkdir(mode=0o700,exist_ok=True)
os.chdir(Path(__file__).resolve().parents[2])
runtime=root/'runtime';runtime.mkdir(mode=0o700,exist_ok=True)
(runtime/'projections').mkdir(mode=0o700,exist_ok=True)
seed=root/'bootstrap';seed.mkdir(mode=0o700,exist_ok=True)
def private(p,s):
 fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as output:output.write(s)
 p.chmod(0o600)
def props(p):return dict(l.split('=',1) for l in p.read_text().splitlines() if '=' in l and not l.startswith('#'))
def write(p,d):private(p,''.join(str(k)+'='+str(v)+'\n' for k,v in d.items()))
def uid(label):return str(uuid.uuid5(uuid.NAMESPACE_URL,'auth-docker-governance:'+label))
statefile=root/'state.json'
if statefile.exists():state=json.loads(statefile.read_text())
else:
 state={'db_password':secrets.token_urlsafe(36),'client_secret':secrets.token_urlsafe(40),'approval_key':secrets.token_urlsafe(36)};private(statefile,json.dumps(state))
fixture=json.loads((source/'p2/identity/casdoor.json').read_text());ops=json.loads((source/'casdoor-isolated/management-client.json').read_text())
issuer='http://localhost:18090';client='auth-console-docker'
basic='Basic '+base64.b64encode((ops['client_id']+':'+ops['client_secret']).encode()).decode()
if args.activate:
 # 使用 Casdoor 正式授权码 + PKCE API；不注入 JWT，也不修改用户密码或应用 grantTypes。
 user=fixture['users']['internal'];redirect='http://localhost:5273/callback'
 verifier=secrets.token_urlsafe(48)
 challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
 query=urllib.parse.urlencode(dict(clientId=client,responseType='code',redirectUri=redirect,scope='openid',state=secrets.token_urlsafe(20),nonce=secrets.token_urlsafe(20),code_challenge_method='S256',code_challenge=challenge))
 data=json.dumps(dict(type='code',organization=fixture['organization'],username=user['name'],password=user['password'],application=client,signinMethod='Password')).encode()
 req=urllib.request.Request(issuer+'/api/login?'+query,data,{'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=15) as r:code=json.load(r)['data']
 data=urllib.parse.urlencode(dict(grant_type='authorization_code',client_id=client,code=code,code_verifier=verifier,redirect_uri=redirect)).encode()
 req=urllib.request.Request(issuer+'/api/login/oauth/access_token',data,{'Content-Type':'application/x-www-form-urlencoded'})
 with urllib.request.urlopen(req,timeout=15) as r:token=json.load(r)['access_token']
 part={'tenant_id':uid('tenant'),'application_id':'commerce','environment':'local','command_id':uid('enable-strict')}
 req=urllib.request.Request('http://localhost:5273/api/governance/v1/access/enable-strict',json.dumps(part).encode(),{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=15) as r:assert r.status==202
 print('Strict local governance projection activated through authenticated API.')
 raise SystemExit(0)

def idp(path,data=None):
 req=urllib.request.Request(issuer+path,None if data is None else json.dumps(data).encode(),{'Authorization':basic,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=15) as r:return json.load(r)
result=idp('/api/get-application?'+urllib.parse.urlencode({'id':'admin/'+client}))
if result.get('data') is None:
 app={'owner':'admin','name':client,'displayName':'Auth Docker Governance','organization':fixture['organization'],'clientId':client,'clientSecret':state['client_secret'],'cert':'cert-built-in','isShared':False,'enablePassword':True,'enableSignUp':False,'enableSigninSession':True,'grantTypes':['authorization_code','refresh_token'],'redirectUris':['http://localhost:5273/callback'],'signinMethods':[{'name':'Password','displayName':'Password','rule':'All'}],'providers':[],'expireInHours':1,'refreshExpireInHours':2,'tokenFormat':'JWT-Custom','tokenSigningMethod':'RS256','tokenFields':['Owner','Name','DisplayName']}
 assert idp('/api/add-application',app)['status']=='ok'
else:assert result['data']['clientSecret']==state['client_secret']
# 只创建固定名称且当前不存在的专用库/角色，不覆盖已有密码、不删除共享数据。
def sql(q):
 r=subprocess.run(['docker','exec','-i','dev-infra-postgres16-1','psql','-U','devinfra','-d','postgres','-v','ON_ERROR_STOP=1','-At'],input=q,text=True,capture_output=True)
 if r.returncode:raise RuntimeError('database provisioning failed')
 return r.stdout.strip()
if sql("SELECT 1 FROM pg_roles WHERE rolname='auth_governance';")!='1':sql("CREATE ROLE auth_governance LOGIN PASSWORD '"+state['db_password']+"';")
if sql("SELECT 1 FROM pg_database WHERE datname='auth_governance';")!='1':sql('CREATE DATABASE auth_governance OWNER auth_governance;')
db={'jdbc.url':'jdbc:postgresql://127.0.0.1:45432/auth_governance','jdbc.username':'auth_governance','jdbc.password':state['db_password'],'jdbc.maximum-pool-size':4}
write(seed/'database.properties',db)
jar=Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()
def cli(name,*args):
 with (root/(name+'.log')).open('a') as log:
  r=subprocess.run(['java','-Xmx256m','-Dloader.main=com.lrj.authz.governance.cli.'+name,'-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,args)],stdout=log,stderr=subprocess.STDOUT,timeout=60)
  if r.returncode:raise RuntimeError(name+' failed; inspect local log')
tenant=uid('tenant');owner=uid('internal-principal');member=uid('internal-member')
for identity in ['internal','external']:
 values={'command.id':uid(identity+'-bootstrap'),'operator.ref':'docker-local-setup','tenant.id':tenant,'tenant.code':'local-commerce','principal.id':uid(identity+'-principal'),'issuer':issuer,'subject':fixture['users'][identity]['id'],'membership.id':uid(identity+'-member'),'valid.from':'2020-01-01T00:00:00Z','source.system':'docker-local','source.tenant.ref':tenant,'source.subject.ref':identity}
 write(seed/(identity+'.properties'),values);cli('GovernanceCli','bootstrap',seed/'database.properties',seed/(identity+'.properties'))
cat={**db,'catalog.application':'commerce','catalog.owner-principal':owner,'catalog.entry-origin':'http://localhost:8602','catalog.operator':'docker-local-setup','catalog.command':uid('catalog'),'catalog.owner-issuer':issuer,'catalog.owner-subject':fixture['users']['internal']['id']}
write(seed/'catalog.properties',cat);cli('CatalogCli','register',seed/'catalog.properties','configured')
capabilities=['commerce.store.read','commerce.store.manage','commerce.product.read']
manifest={'schema_version':'1','application':'commerce','manifest_version':1,'capabilities':[{'code':c,'resource_type':'store' if '.store.' in c else 'product','risk_level':'NORMAL'} for c in capabilities],'menus':[{'code':'stores','parent':None,'route':'/stores','any_of':['commerce.store.read']},{'code':'products','parent':None,'route':'/products','any_of':['commerce.product.read']}]}
private(seed/'manifest.json',json.dumps(manifest));cli('CatalogCli','publish',seed/'catalog.properties',seed/'manifest.json')
access={**db,'access.tenant':tenant,'access.application':'commerce','access.environment':'local','access.manager':member,'access.generation':1,'access.capabilities':','.join(capabilities),'access.max-duration-seconds':86400,'access.operator':'docker-local-setup','access.command':uid('delegation')}
write(seed/'access.properties',access);cli('AccessBootstrapCli',seed/'access.properties')
containerdb={**db,'jdbc.url':db['jdbc.url'].replace('127.0.0.1','host.docker.internal')}
graph=props(source/'p3/graph/graph.properties')
authority={'issuer':issuer,'jwks.uri':issuer+'/.well-known/jwks','audience':client,'client.id':client,'client.secret':state['client_secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
admin={**containerdb,**authority,**graph,'scope.graph.http':graph['graph.http'],'scope.graph.key':graph['graph.key'],'approval.tenant':tenant,'approval.application':'commerce','approval.environment':'local','approval.inbound-key':state['approval_key'],'approval.allow-loopback-http':'true',**{'invitation.user.'+k:v for k,v in authority.items()},'portal.invitation.count':1,'portal.diagnostic.count':1}
for prefix in ['portal.invitation.1.','portal.diagnostic.1.']:
 admin.update({prefix+k:v for k,v in {'tenant-id':tenant,'application-id':'commerce','environment':'local','membership-id':member,'generation':1}.items()})
admin.update({'portal.invitation.1.max-invitation-seconds':3600,'portal.invitation.1.max-membership-seconds':86400})
write(runtime/'admin.properties',admin)
for kind in ['POLICY','DIRECTORY']:write(runtime/'projections'/(kind.lower()+'.properties'),{**access,**containerdb,**graph,'projection.kind':kind})
write(root/'runtime.env',{'GOVERNANCE_RUNTIME_DIR':str(runtime),'GOVERNANCE_CLIENT_ID':client,'AUTH_CONSOLE_UI_PORT':5273,'GOVERNANCE_IDENTITY_DIR':str(source),'GOVERNANCE_STATE_DIR':str(root)})
private(root/'ACCESS.md',f"# 本地 Auth 授权管理\n\n入口：http://localhost:5273/governance/access?tenant={tenant}&application=commerce&environment=local\n\n组织：local-commerce，应用：commerce，环境：local。\n\n管理员账号：{fixture['users']['internal']['name']}\n密码：{fixture['users']['internal']['password']}\n\n示例成员账号：{fixture['users']['external']['name']}\n密码：{fixture['users']['external']['password']}\n\n成员 ID：{uid('external-member')}，generation=1\n\n本地库 auth_governance，使用 dev_infra PostgreSQL 45432；完整私密配置位于 runtime/。这是本地授权验证分区，现有 8602 电商服务仍是旧版本，不会因本页面设置权限而自动完成统一鉴权接管。\n")
private(root/'context.json',json.dumps({'tenant':tenant,'manager':member,'member':uid('external-member'),'application':'commerce','environment':'local'}))
print('Local governance database/client/bootstrap ready; credentials saved privately.')
