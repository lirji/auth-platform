#!/usr/bin/env python3
"""真实页面验收启动器：复用P2隔离身份与数据，仅停止本次持有的进程。"""
import argparse, importlib.util, json, os, socket, subprocess, time
from pathlib import Path


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--fixture',required=True)
    parser.add_argument('--playwright-module',required=True)
    args=parser.parse_args()
    root=Path(args.fixture).resolve()
    spec=importlib.util.spec_from_file_location('smoke',Path('deploy/governance-context-smoke.py'))
    h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
    identity=json.loads(h.read_private(root.parent/'identity/casdoor.json'))
    output=root/'ui';output.mkdir(mode=0o700)
    env=dict(os.environ,AUTH_CONSOLE_UI_PORT='15273',VITE_GOVERNANCE_TARGET='http://127.0.0.1:18102',
             VITE_CASDOOR_AUTHORITY=h.ISSUER,VITE_CASDOOR_CLIENT_ID=identity['clients']['management']['name'],
             P2_UI_FIXTURE=str(root),P2_PLAYWRIGHT_MODULE=str(Path(args.playwright_module).resolve()))
    process=None
    try:
        for kind,port,presentation in [('admin',18102,True),('server',18101,False)]:
            jar=next(Path('auth-platform-'+kind+'/target').glob('auth-platform-'+kind+'-*.jar'))
            h.start(jar,port,output/(kind+'.log'),config=root/(kind+'.properties'),access=True,presentation=presentation)
        with socket.socket() as guard: guard.bind(('127.0.0.1',15273))
        with os.fdopen(os.open(output/'vite.log',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            process=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--strictPort'],cwd='auth-console',env=env,stdout=log,stderr=subprocess.STDOUT)
        for _ in range(60):
            if process.poll() is not None: raise RuntimeError('console startup failed')
            try:
                with socket.create_connection(('127.0.0.1',15273),timeout=1): break
            except OSError: time.sleep(.5)
        else: raise RuntimeError('console startup timeout')
        subprocess.run(['node','deploy/governance-ui-smoke.mjs'],env=env,check=True,timeout=180)
    finally:
        if process: h.stop(process)
        for child in h.PROCESSES: h.stop(child)


if __name__=='__main__':main()
