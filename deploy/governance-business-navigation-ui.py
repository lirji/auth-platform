#!/usr/bin/env python3
"""MG08实际界面验证：复用MG07隔离库，所有常规数据来自真实接口。"""
import importlib.util
import http.client
from http import HTTPStatus
import json
import os
from pathlib import Path
import socket
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
COMMERCE=ROOT.parent/'commerce-platform'
UI_PORT=21764

class NavigationUi:
    """保留同一浏览器跨撤权与断连阶段，验证旧菜单确实清空。"""
    def __init__(self,h,run,fixture,tenant,user,manager_business,manager):
        self.h=h;self.run=run;self.number=0
        h.private(run/'navigation-ui.json',json.dumps({'authority':h.ISSUER,'client':fixture['clients']['business']['name'],'tenant':tenant,'user_token':user,'manager_business_token':manager_business,'wrong_audience_token':manager}))
        env=dict(os.environ,VITE_IAM_ENABLED='true',VITE_IAM_AUTHORITY=h.ISSUER,VITE_IAM_CLIENT_ID=fixture['clients']['business']['name'],VITE_IAM_ENVIRONMENT='test',COMMERCE_API_URL='http://127.0.0.1:21763',NAV_UI_RUN=str(run),NAV_PLAYWRIGHT_MODULE=str(COMMERCE/'frontend/node_modules/@playwright/test'))
        self.env=env
        with socket.socket() as probe:probe.bind(('127.0.0.1',UI_PORT))
        with (run/'navigation-vite.log').open('w') as log:
            vite=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',str(UI_PORT),'--strictPort'],cwd=COMMERCE/'frontend',env=env,stdout=log,stderr=subprocess.STDOUT)
        h.PROCESSES.append(vite)
        for _ in range(120):
            if vite.poll() is not None:raise RuntimeError('owned navigation Vite failed')
            try:
                connection=http.client.HTTPConnection('127.0.0.1',UI_PORT,timeout=2)
                try:
                    connection.request('GET','/@vite/client');response=connection.getresponse();response.read(65536)
                    if response.status==HTTPStatus.OK:break
                finally:connection.close()
            except OSError:pass
            time.sleep(.25)
        else:raise RuntimeError('owned navigation Vite timeout')
        with (run/'navigation-browser.log').open('w') as log:
            self.browser_process=subprocess.Popen(['node','deploy/governance-business-navigation-ui.mjs'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        h.PROCESSES.append(self.browser_process)

    def browser(self,phase):
        """有界命令／回执只存在本轮0700证据目录，不传凭据到命令行。"""
        self.number+=1;number=self.number
        temporary=self.run/'navigation-command.tmp';self.h.private(temporary,json.dumps({'number':number,'phase':phase}));os.replace(temporary,self.run/'navigation-command.json')
        result=self.run/f'navigation-result-{number}.json'
        for _ in range(240):
            if result.exists():
                value=json.loads(self.h.read_private(result))
                if value['status']!='PASS':raise RuntimeError('navigation browser phase failed: '+phase)
                return value
            if self.browser_process.poll() is not None:raise RuntimeError('navigation browser exited without phase receipt: '+phase)
            time.sleep(.25)
        raise RuntimeError('navigation browser phase timeout: '+phase)

    def shared(self):
        """既有36路由公开DTO回归仅证明客户端兼容，单独记录，不充当SSO授权。"""
        env=dict(self.env,COMMERCE_CENTRAL_UI_URL='http://127.0.0.1:'+str(UI_PORT),COMMERCE_CENTRAL_AUTHORITY=self.env['VITE_IAM_AUTHORITY'],COMMERCE_CENTRAL_CLIENT=self.env['VITE_IAM_CLIENT_ID'],COMMERCE_EVIDENCE_DIR=str(self.run/'shared-ui'))
        with (self.run/'shared-ui.log').open('w') as log:
            result=subprocess.run(['node','node_modules/@playwright/test/cli.js','test','tests/central-workspace.spec.ts'],cwd=COMMERCE/'frontend',env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        if result.returncode:raise RuntimeError('shared central UI fixture regression failed; inspect private log')

if __name__=='__main__':
    spec=importlib.util.spec_from_file_location('navigation_runtime',ROOT/'deploy/governance-business-navigation-runtime.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.main(ui=True)
