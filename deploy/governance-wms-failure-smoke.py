#!/usr/bin/env python3
"""只暂停本任务持有的回环Auth进程，并撤销本任务reader-a演示授权。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import uuid


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    args = parser.parse_args(); os.umask(0o077)
    p = load('wms_failure_setup', 'governance-wms-provision.py')
    h = load('wms_failure_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve()
    run = Path(p.read_private(root / 'latest-runtime.txt'))
    if run.parent != root or not run.name.startswith('runtime-') or (run / 'failure-result.json').exists():
        raise RuntimeError('只允许当前未执行故障验收的专属fixture')
    state = json.loads(p.read_private(root / 'state.json')); p.validate_state(state)
    fixture = json.loads(p.read_private(run / 'fixture.json'))
    tokens = json.loads(p.read_private(run / 'tokens.json'))
    token = tokens['reader-a']['access_token']; operator = tokens['operator']['access_token']
    checks = []

    def check(name, path, status, access_token=token, body=None, port=18183):
        actual, result = h.request(port, path, [('Authorization', 'Bearer ' + access_token)],
                                   None if body is None else json.dumps(body).encode())
        if actual != status: raise RuntimeError(name + ': unexpected HTTP ' + str(actual))
        checks.append({'check': name, 'result': 'PASS', 'status': actual})
        return result

    check('same old access token initially allowed', '/api/wms/v1/warehouses/WH-A/locations', 200)
    # 严格确认父进程是本任务fixture，只暂停对应Popen；finally恢复，绝不暂停共享Docker服务。
    processes = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,args='], text=True).splitlines()
    rows = [line.strip().split(None, 2) for line in processes]
    owners = {row[0] for row in rows if len(row) == 3 and Path(row[2].split()[0]).name.lower().startswith('python')
              and 'deploy/governance-wms-runtime-smoke.py' in row[2] and '--serve' in row[2]}
    matches = [int(row[0]) for row in rows if len(row) == 3 and row[1] in owners
               and Path(row[2].split()[0]).name == 'java' and '--server.port=18545' in row[2]
               and str(root / 'runtime/server.properties') in row[2]]
    if len(matches) != 1: raise RuntimeError('不能唯一确认本工具拥有的Auth server')
    pid = matches[0]
    os.kill(pid, signal.SIGSTOP)
    try:
        started = time.monotonic()
        result = check('paused Auth refuses old token without fallback', '/api/wms/v1/warehouses/WH-A/locations', 503)
        if result.get('code') != 'AUTHORIZATION_UNAVAILABLE' or time.monotonic() - started > 12:
            raise RuntimeError('故障错误或截止时间不符')
        check('paused Auth本人 unavailable', '/api/wms/v1/me/access?warehouseId=WH-A', 503)
    finally:
        os.kill(pid, signal.SIGCONT)
    check('recovered Auth allows same old token', '/api/wms/v1/warehouses/WH-A/locations', 200)
    part = {'tenant_id': state['tenant'], 'application_id': 'wms', 'environment': 'local'}
    ids = {grant['id'] for evidence in root.glob('runtime-*/grants.json')
           for grant in json.loads(p.read_private(evidence)) if grant['user'] == 'reader-a'}
    revoked = {grant_id for evidence in root.glob('runtime-*/failure-result.json')
               for grant_id in json.loads(p.read_private(evidence)).get('revoked_ids', [])}
    ids -= revoked
    # 旧验收重跑可能留下仍有效的独立来源，全部撤销工具自己记录的来源，不能只删最新来源伪称撤权。
    for grant_id in sorted(ids):
        check('revoke owned reader-a source', '/api/governance/v1/access/revoke', 200,
              fixture['manager_token'], {**part, 'command_id': str(uuid.uuid4()), 'grant_id': grant_id, 'expected_version': 1}, 18546)
    for kind in ['POLICY', 'DIRECTORY']:
        for _ in range(12):
            with (run / ('failure-project-' + kind + '.log')).open('a') as log:
                result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli',
                    '-cp', str(Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()),
                    'org.springframework.boot.loader.launch.PropertiesLauncher', str(run / (kind + '.properties'))],
                    stdout=log, stderr=subprocess.STDOUT, timeout=40)
            if result.returncode == 0: break
            if result.returncode != 2: raise RuntimeError('撤权投影失败')
            time.sleep(.25)
        else: raise RuntimeError('撤权投影未ready')
    check('same old token next request denied after revocation', '/api/wms/v1/warehouses/WH-A/locations', 403)
    view = check('same old token menus removed', '/api/wms/v1/me/access?warehouseId=WH-A', 200)
    if view['warehouseIds'] or view['capabilities'] or view['menus']: raise RuntimeError('撤权后本人提示仍有权限')
    check('operator unaffected by reader revocation', '/api/wms/v1/warehouses/WH-A/locations', 200, operator)
    p.private(run / 'failure-result.json', json.dumps({'result': 'PASS', 'checks': checks,
              'same_access_token': True, 'revoked_ids': sorted(ids), 'shared_services_stopped': False}, ensure_ascii=False, indent=2))
    print('PASS: %d真实旧令牌/撤权/故障恢复检查。' % len(checks))


if __name__ == '__main__':
    main()
