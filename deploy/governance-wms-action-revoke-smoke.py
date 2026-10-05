#!/usr/bin/env python3
"""撤销工具自己记录的operator仓级写来源，使用同一旧Token验证新动作和重试均拒绝。"""
import argparse
from decimal import Decimal
import importlib.util
import json
import os
from pathlib import Path
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
    p = load('wms_write_revoke_setup', 'governance-wms-provision.py'); h = load('wms_write_revoke_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve(); state = json.loads(p.read_private(root / 'state.json')); p.validate_state(state)
    run = Path(p.read_private(root / 'latest-runtime.txt'))
    if run.parent != root or (run / 'action-revoke-result.json').exists(): raise RuntimeError('仅允许当前未执行撤权的工具fixture')
    fixture = json.loads(p.read_private(run / 'fixture.json'))
    if fixture['phase'] not in {'W05', 'W06'} or fixture['tenant'] != state['tenant']: raise RuntimeError('fixture分区不符')
    tokens = json.loads(p.read_private(run / 'tokens.json')); token = tokens['operator']['access_token']
    manager = json.loads(p.read_private(run / 'manager-token.json'))['access_token']; checks = []
    data = json.loads(p.read_private(run / 'action-data.json')); command = json.loads(p.read_private(run / 'pda-command.json'))
    path = '/api/wms/v1/warehouses/WH-A/inbound-orders/' + data['inbound_order_id'] + '/receipts'

    def request(name, port, path, credential, body=None, expected=200, key=None):
        headers = [('Authorization', 'Bearer ' + credential)]
        if key: headers.append(('Idempotency-Key', key))
        status, value = h.request(port, path, headers, None if body is None else json.dumps(body).encode())
        if status != expected: raise RuntimeError(name + ': unexpected HTTP ' + str(status))
        checks.append({'check': name, 'result': 'PASS', 'status': status}); return value

    # PDA真实写入后的累计数量必须为3；撤权只能拒绝新请求，不能伪称撤销已提交的实物事实。
    def received():
        result = subprocess.run(['docker', 'exec', '-i', fixture['container'], 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'], text=True,
            input="SELECT received_physical_qty FROM wms_inbound.inbound_line WHERE enterprise_id='ENT-DEMO' AND warehouse_id='WH-A' AND id='" + data['inbound_line_id'] + "'", capture_output=True, timeout=15)
        if result.returncode: raise RuntimeError('fixture SQL不可用')
        return Decimal(result.stdout.strip())
    if received() != Decimal('3'): raise RuntimeError('当前PDA收货实物事实不符')
    checks.append({'check': 'real PDA receipt exactly persisted after prior replay', 'result': 'PASS'})
    request('old operator token read starts available', 18181, '/api/wms/v1/warehouses/WH-A/inbound-orders/' + data['inbound_order_id'], token)
    part = {'tenant_id': state['tenant'], 'application_id': 'wms', 'environment': 'local'}
    for evidence in sorted(root.glob('runtime-*/grants.json')):
        grants = json.loads(p.read_private(evidence))
        for grant in grants:
            if grant['user'] != 'operator' or not grant['write'] or grant['resource'] != 'wms_warehouse' or grant.get('revoked'): continue
            request('revoke only owned warehouse write source', 18546, '/api/governance/v1/access/revoke', manager,
                {**part, 'command_id': str(uuid.uuid4()), 'grant_id': grant['id'], 'expected_version': 1})
            grant['revoked'] = True; p.private(evidence, json.dumps(grants))
    for kind in ['POLICY', 'DIRECTORY']:
        for _ in range(12):
            with (run / ('action-revoke-project-' + kind + '.log')).open('a') as log:
                result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli',
                    '-cp', str(Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()),
                    'org.springframework.boot.loader.launch.PropertiesLauncher', str(run / (kind + '.properties'))], stdout=log, stderr=subprocess.STDOUT, timeout=40)
            if result.returncode == 0: break
            if result.returncode != 2: raise RuntimeError('写撤权投影失败')
            time.sleep(.25)
        else: raise RuntimeError('写撤权投影未ready')
    request('same old token cannot retry accepted receipt after revoke', 18181, path, token, command['request'], 403, command['idempotency_key'])
    request('same old token cannot create new receipt after revoke', 18181, path, token, command['request'], 403, uuid.uuid4().hex)
    request('read role preserved after independent write revocation', 18181, '/api/wms/v1/warehouses/WH-A/inbound-orders/' + data['inbound_order_id'], token)
    if received() != Decimal('3'): raise RuntimeError('撤权错误改变实物事实或拒绝后仍记量')
    view = request('same old token PDA menu removed while read retained', 18183, '/api/wms/v1/me/access?warehouseId=WH-A', token)
    if 'wms.inbound.receive' in view['capabilities'] or not view['capabilities'] or any(menu.get('route') == '/pda/receive' for menu in view['menus']):
        raise RuntimeError('独立写撤权后权限提示不符')
    p.private(run / 'action-revoke-result.json', json.dumps({'result': 'PASS', 'checks': checks, 'same_access_token': True,
                'committed_physical_quantity_preserved': '3'}, ensure_ascii=False, indent=2))
    print('PASS: %d真实PDA SQL/旧Token写撤权检查。' % len(checks))


if __name__ == '__main__':
    main()
