#!/usr/bin/env python3
"""只为当前专属fixture的无授权演示成员授予企业SKU读权，验证其不能成为仓权。"""
import argparse
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import uuid


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--revoke', action='store_true')
    args = parser.parse_args(); os.umask(0o077)
    p = load('wms_enterprise_setup', 'governance-wms-provision.py')
    h = load('wms_enterprise_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve(); state = json.loads(p.read_private(root / 'state.json')); p.validate_state(state)
    run = Path(p.read_private(root / 'latest-runtime.txt'))
    if run.parent != root or not run.name.startswith('runtime-w04-'): raise RuntimeError('必须是当前W04专属fixture')
    fixture = json.loads(p.read_private(run / 'fixture.json'))
    if fixture['phase'] != 'W04' or fixture['tenant'] != state['tenant']: raise RuntimeError('专属分区不一致')
    manager = json.loads(p.read_private(run / 'manager-token.json'))['access_token']
    token = json.loads(p.read_private(run / 'tokens.json'))['denied']['access_token']; checks = []
    part = {'tenant_id': state['tenant'], 'application_id': 'wms', 'environment': 'local'}

    def request(name, port, path, credential, body=None, expected=200):
        status, value = h.request(port, path, [('Authorization', 'Bearer ' + credential)],
                                  None if body is None else json.dumps(body).encode())
        if status != expected: raise RuntimeError(name + ': unexpected HTTP ' + str(status))
        checks.append({'check': name, 'status': status, 'result': 'PASS'}); return value

    def project():
        # 只投影本任务分区，不操作其他应用的状态或租约。
        jar = Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()
        import subprocess
        import time
        for kind in ['POLICY', 'DIRECTORY']:
            for _ in range(12):
                with (run / ('enterprise-project-' + kind + '.log')).open('a') as log:
                    result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli',
                        '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(run / (kind + '.properties'))],
                        stdout=log, stderr=subprocess.STDOUT, timeout=40)
                if result.returncode == 0: break
                if result.returncode != 2: raise RuntimeError('企业读权可靠投影失败')
                time.sleep(.25)
            else: raise RuntimeError('企业读权投影未ready')

    receipt = run / 'enterprise-grant.json'
    if args.revoke:
        grant = json.loads(p.read_private(receipt))
        if grant.get('revoked') or grant['member_id'] != p.uid('membership:denied'): raise RuntimeError('不能重复或越界撤权')
        request('revoke only recorded enterprise demo source', 18546, '/api/governance/v1/access/revoke',
                manager, {**part, 'command_id': str(uuid.uuid4()), 'grant_id': grant['id'], 'expected_version': 1})
        grant['revoked'] = True; p.private(receipt, json.dumps(grant)); project()
        request('same token enterprise read immediately rejected', 18183, '/api/wms/v1/skus', token, expected=403)
        p.private(run / 'enterprise-revoke-result.json', json.dumps({'result': 'PASS', 'checks': checks}, ensure_ascii=False, indent=2))
    else:
        if receipt.exists(): raise RuntimeError('当前fixture已执行企业读权验收')
        view = request('ungranted demo starts empty', 18183, '/api/wms/v1/me/access', token)
        if view['capabilities'] or view['warehouseIds']: raise RuntimeError('演示成员已有其他授权，不能作为企业-only证据')
        role = request('create immutable enterprise SKU-only role', 18546, '/api/governance/v1/access/roles', manager,
            {**part, 'command_id': str(uuid.uuid4()), 'role_code': 'wms-w04-enterprise-sku-only', 'role_version': 1,
             'capabilities': ['wms.masterdata.read.enterprise']})
        stamp = lambda seconds: (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
        member = p.uid('membership:denied')
        grant = request('grant only enterprise SKU reader', 18546, '/api/governance/v1/access/scoped-grants', manager,
            {**part, 'command_id': str(uuid.uuid4()), 'member_id': member, 'member_generation': 1, 'role_id': role['id'],
             'scope_rule': {'version': 1, 'resource_type': 'wms_enterprise', 'clauses': [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]},
             'source_id': 'wms-enterprise-only-' + run.name, 'valid_from': stamp(-5), 'valid_to': stamp(3600)}, 202)
        p.private(receipt, json.dumps({'id': grant['id'], 'member_id': member, 'revoked': False})); project()
        request('enterprise SKU actual SQL read allowed', 18183, '/api/wms/v1/skus', token)
        request('enterprise read cannot enumerate warehouses', 18183, '/api/wms/v1/warehouses', token, expected=403)
        request('enterprise read cannot query warehouse A', 18183, '/api/wms/v1/warehouses/WH-A/locations', token, expected=403)
        view = request('navigation contains enterprise capability without warehouses', 18183, '/api/wms/v1/me/access', token)
        if view['warehouseIds'] or view['capabilities'] != ['wms.masterdata.read.enterprise']: raise RuntimeError('企业读权被放大')
        p.private(run / 'enterprise-result.json', json.dumps({'result': 'PASS', 'checks': checks}, ensure_ascii=False, indent=2))
    print('PASS: %d企业共享资源真实授权检查。' % len(checks))


if __name__ == '__main__':
    main()
