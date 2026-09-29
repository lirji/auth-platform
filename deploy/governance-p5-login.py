#!/usr/bin/env python3
"""P5真实交互登录隔离运行器：独有客户端、精确回调、复制既有P5夹具配置；不注入浏览器Token。"""
import argparse, base64, importlib.util, json, os, secrets, shutil, subprocess, time, urllib.request
from pathlib import Path

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
h=module('context','governance-context-smoke.py')
pilot=module('pilot','governance-p5-commerce.py')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--fixture',required=True);parser.add_argument('--commerce',required=True);parser.add_argument('--oa',required=True);args=parser.parse_args()
    source=Path(args.fixture).resolve();commerce=Path(args.commerce).resolve();oa=Path(args.oa).resolve()
    if source.parent!=Path('.local/governance/p4').resolve() or not source.name.startswith('e2e-'):raise RuntimeError('only owned P5 fixture accepted')
    run=Path('.local/governance/p5')/('login-'+secrets.token_hex(6));run.mkdir(mode=0o700);run=run.resolve()
    fixture=json.loads(h.read_private(Path('.local/governance/p2/identity/casdoor.json')))
    ops=json.loads(h.read_private(Path('.local/governance/casdoor-isolated/management-client.json')))
    auth='Basic '+base64.b64encode((ops['client_id']+':'+ops['client_secret']).encode()).decode()
    clients={}
    for purpose,redirect in {'management':'http://127.0.0.1:15275/callback','business':'http://127.0.0.1:18605/iam/callback','oa':'http://127.0.0.1:15276/callback'}.items():
        name='p5-'+purpose+'-'+secrets.token_hex(6);secret=secrets.token_urlsafe(40);clients[purpose]={'name':name,'secret':secret}
        app={'owner':'admin','name':name,'displayName':'P5 isolated '+purpose,'organization':fixture['organization'],'clientId':name,'clientSecret':secret,'cert':'cert-built-in','isShared':False,'enablePassword':True,'enableSignUp':False,'enableSigninSession':True,'grantTypes':['authorization_code','refresh_token'],'redirectUris':[redirect],'signinMethods':[{'name':'Password','displayName':'Password','rule':'All'}],'providers':[],'expireInHours':1,'refreshExpireInHours':2,'tokenFormat':'JWT-Custom','tokenSigningMethod':'RS256','tokenFields':['Owner','Name','DisplayName']}
        req=urllib.request.Request(h.ISSUER+'/api/add-application',json.dumps(app).encode(),{'Authorization':auth,'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=15) as response: result=json.load(response)
        if result.get('status')!='ok':raise RuntimeError('owned IdP client creation failed')
    h.private(run/'clients.json',json.dumps(clients))
    def config(name):return dict(line.split('=',1) for line in h.read_private(source/name).splitlines() if '=' in line)
    admin=config('admin.properties');server=config('server.properties');oa_config=config('oa.properties')
    for prefix in ['', 'invitation.user.']:
        for key,value in {'audience':clients['management']['name'],'client.id':clients['management']['name'],'client.secret':clients['management']['secret']}.items():admin[prefix+key]=value
    for key,value in {'audience':clients['business']['name'],'client.id':clients['business']['name'],'client.secret':clients['business']['secret']}.items():server['service.1.user.'+key]=value
    oa_config['oa.security.audience']=clients['oa']['name'];oa_config['oa.flow.central-approval.callback-enabled']='false'
    for name,value in [('admin',admin),('server',server),('oa',oa_config)]:h.private(run/(name+'.properties'),h.props(value))
    for name in ['admin.jar','server.jar','commerce.jar']:shutil.copy2(source/name,run/name)
    shutil.copy2(oa/'oa-app/target/oa-app-0.1.0-SNAPSHOT.jar',run/'oa.jar')
    h.start(run/'admin.jar',18422,run/'admin.log',config=run/'admin.properties',access=True,requests=True,invitations=True,presentation=True,scope=True)
    server_process=h.start(run/'server.jar',18421,run/'server.log',config=run/'server.properties',access=True,scope=True)
    helper=pilot.CommercePilot.__new__(pilot.CommercePilot);helper.run=run;helper.h=h
    helper.start(['java','-Xmx384m','-jar',str(run/'oa.jar'),'--spring.config.additional-location=file:'+str(run/'oa.properties')],oa,'oa',18420,os.environ.copy())
    env=dict(os.environ,**json.loads(h.read_private(source/'commerce-environment.json')))
    command=['java','-Xmx384m','-jar',str(run/'commerce.jar'),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled=true','--commerce.iam.scope.enabled=true','--commerce.iam.store-read.configuration='+str(source/'consumer.properties')]
    helper.start(command,commerce,'commerce',18603,env)
    ui=dict(os.environ,AUTH_CONSOLE_UI_PORT='15275',OA_CONSOLE_PORT='15276',VITE_GOVERNANCE_TARGET='http://127.0.0.1:18422',VITE_CASDOOR_AUTHORITY=h.ISSUER,VITE_CASDOOR_CLIENT_ID=clients['management']['name'])
    helper.start(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--strictPort'],Path('auth-console'),'portal-vite',15275,ui)
    helper.start(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--strictPort'],oa/'oa-console','oa-vite',15276,dict(ui,VITE_AUTH_ENABLED='true',VITE_API_TARGET='http://127.0.0.1:18420',VITE_NOTIFY_TARGET='http://127.0.0.1:18420',VITE_CASDOOR_CLIENT_ID=clients['oa']['name']))
    business_env=dict(ui,VITE_IAM_ENABLED='true',VITE_IAM_AUTHORITY=h.ISSUER,VITE_IAM_CLIENT_ID=clients['business']['name'],VITE_IAM_PORTAL_URL='http://127.0.0.1:15275',VITE_IAM_ENVIRONMENT='test',COMMERCE_API_URL='http://127.0.0.1:18603')
    helper.start(['npm','run','dev','--','--port','18605'],commerce/'frontend','commerce-vite',18605,business_env)
    ui_data=json.loads(h.read_private(source/'commerce-ui.json'))
    h.private(run/'login.json',json.dumps({'tenant':ui_data['tenant'],'authority':h.ISSUER,'clients':{k:v['name'] for k,v in clients.items()},'users':fixture['users']}))
    h.private(run/'ui-environment.json',json.dumps({k:v for k,v in business_env.items() if k.startswith('VITE_')}))
    print('P5_LOGIN_RUN='+str(run),flush=True)
    # 仅接受本目录固定信号，便于在真实浏览器请求中检验依赖中断及恢复；不接受任意命令。
    deadline=time.monotonic()+2400
    while time.monotonic()<deadline and not (run/'stop').exists():
        if (run/'stop-server').exists() and server_process.poll() is None:h.stop(server_process);(run/'server-stopped').touch()
        if (run/'restart-server').exists() and server_process.poll() is not None:
            server_process=h.start(run/'server.jar',18421,run/'server-restarted.log',config=run/'server.properties',access=True,scope=True);(run/'server-restarted').touch()
            (run/'stop-server').unlink();(run/'restart-server').unlink()
        time.sleep(.5)
    print('P5 login owned processes stopping',flush=True)

if __name__=='__main__':
    try:main()
    finally:
        for process in reversed(h.PROCESSES):h.stop(process)
