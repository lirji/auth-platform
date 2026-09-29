#!/usr/bin/env python3
"""复用独有P5登录客户端在18606验证真实打包SPA；不依赖Vite代理，不触及共享客户端。"""
import argparse,base64,importlib.util,json,os,shutil,subprocess,time,urllib.parse,urllib.request
from pathlib import Path
spec=importlib.util.spec_from_file_location('login',Path(__file__).with_name('governance-p5-login.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);h=m.h

def main():
 p=argparse.ArgumentParser();p.add_argument('--login-run',required=True);p.add_argument('--fixture',required=True);p.add_argument('--commerce',required=True);args=p.parse_args()
 run=Path(args.login_run).resolve();source=Path(args.fixture).resolve();commerce=Path(args.commerce).resolve()
 if run.parent!=Path('.local/governance/p5').resolve() or not run.name.startswith('login-'):raise RuntimeError('owned login run required')
 clients=json.loads(h.read_private(run/'clients.json'));ops=json.loads(h.read_private(Path('.local/governance/casdoor-isolated/management-client.json')))
 auth='Basic '+base64.b64encode((ops['client_id']+':'+ops['client_secret']).encode()).decode();query=urllib.parse.urlencode({'id':'admin/'+clients['business']['name']})
 with urllib.request.urlopen(urllib.request.Request(h.ISSUER+'/api/get-application?'+query,headers={'Authorization':auth}),timeout=15) as response:app=json.load(response)['data']
 redirect='http://127.0.0.1:18606/iam/callback'
 if redirect not in app['redirectUris']:app['redirectUris'].append(redirect)
 with urllib.request.urlopen(urllib.request.Request(h.ISSUER+'/api/update-application?'+query,json.dumps(app).encode(),{'Authorization':auth,'Content-Type':'application/json'}),timeout=15) as response:assert json.load(response)['status']=='ok'
 env=dict(os.environ,**json.loads(h.read_private(run/'ui-environment.json')))
 with open(run/'pack-build.log','w') as log:
  subprocess.run(['npm','run','build'],cwd=commerce/'frontend',env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
  subprocess.run(['mvn','-q','-pl','commerce-app','-am','package','-Pwith-ui','-DskipTests'],cwd=commerce,stdout=log,stderr=subprocess.STDOUT,check=True)
 jar=run/'commerce-packaged.jar';shutil.copy2(commerce/'commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar',jar)
 env=dict(os.environ,**{**json.loads(h.read_private(source/'commerce-environment.json')),'COMMERCE_PORT':'18606'})
 helper=m.pilot.CommercePilot.__new__(m.pilot.CommercePilot);helper.run=run;helper.h=h
 def start(enabled,label):
  helper.start(['java','-Xmx384m','-jar',str(jar),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled='+str(enabled).lower(),'--commerce.iam.scope.enabled='+str(enabled).lower(),'--commerce.iam.store-read.configuration='+str(source/'consumer.properties')],commerce,label,18606,env)
  return h.PROCESSES[-1]
 proc=start(True,'packaged');(run/'packaged-ready').touch();print('PACKAGED_READY',flush=True)
 deadline=time.monotonic()+1200
 while time.monotonic()<deadline and not (run/'packaged-stop').exists():
  if (run/'disable-pilot').exists() and not (run/'pilot-disabled').exists():
   h.stop(proc);proc=start(False,'packaged-disabled');(run/'pilot-disabled').touch()
  time.sleep(.5)
if __name__=='__main__':
 try:main()
 finally:
  for process in reversed(h.PROCESSES):h.stop(process)
