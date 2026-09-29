#!/usr/bin/env python3
"""复用已建隔离P5夹具重验当前UI；每次保留独立日志，刷新真实用户Token。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time

spec = importlib.util.spec_from_file_location('p5', Path(__file__).with_name('governance-p5-smoke.py'))
p5 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p5)
h = p5.h


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--playwright-module', required=True)
    args = parser.parse_args()
    fixture_root = Path(args.fixture).resolve()
    root = Path('.local/governance').resolve()
    run = root / 'p5' / ('retest-' + secrets.token_hex(5))
    run.mkdir(mode=0o700)
    (root / 'p5/latest-ui.txt').write_text(str(run))
    identity = json.loads(h.read_private(root / 'p2/identity/casdoor.json'))
    fixture = json.loads(h.read_private(fixture_root / 'fixture.json'))
    fixture['manager_token'] = p5.a.token(h.ISSUER, identity, 'management', 'internal')
    fixture['member_token'] = p5.a.token(h.ISSUER, identity, 'management', 'external')
    h.private(run / 'fixture.json', json.dumps(fixture))
    jar = run / 'admin.jar'
    shutil.copy2(next(Path('auth-platform-admin/target').glob('auth-platform-admin-*.jar')), jar)
    process = None
    try:
        # 旧试验留下的PENDING需要真实执行器收敛，不能直接SQL改成ACTIVE。
        for number in range(2):
            for kind in ['POLICY', 'DIRECTORY']:
                with os.fdopen(os.open(run / (str(number) + '-' + kind + '.log'), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w') as log:
                    subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli', '-cp', str(jar),
                                    'org.springframework.boot.loader.launch.PropertiesLauncher', str(fixture_root / (str(number) + '-' + kind + '.properties'))], check=True, stdout=log, stderr=subprocess.STDOUT, timeout=45)
        h.start(jar, p5.ADMIN_PORT, run / 'admin.log', config=fixture_root / 'admin.properties', access=True, presentation=True, scope=True, invitations=True, requests=True)
        env = dict(os.environ, AUTH_CONSOLE_UI_PORT=str(p5.UI_PORT), VITE_GOVERNANCE_TARGET='http://127.0.0.1:' + str(p5.ADMIN_PORT),
                   VITE_CASDOOR_AUTHORITY=h.ISSUER, VITE_CASDOOR_CLIENT_ID=identity['clients']['management']['name'], P5_UI_FIXTURE=str(run), P5_PLAYWRIGHT_MODULE=str(Path(args.playwright_module).resolve()))
        with socket.socket() as guard: guard.bind(('127.0.0.1', p5.UI_PORT))
        with os.fdopen(os.open(run / 'vite.log', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w') as log:
            process = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--strictPort'], cwd='auth-console', env=env, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(60):
            try:
                with socket.create_connection(('127.0.0.1', p5.UI_PORT), timeout=1): break
            except OSError: time.sleep(.5)
        else: raise RuntimeError('console startup timeout')
        subprocess.run(['node', 'deploy/governance-p5-shell.mjs'], env=env, check=True, timeout=240)
        print('PASS P5 UI retest: ' + str(run))
    finally:
        if process: h.stop(process)
        for child in h.PROCESSES: h.stop(child)


if __name__ == '__main__':
    main()
