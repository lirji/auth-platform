#!/usr/bin/env python3
"""专属W06真数据库验收：审批与应用独立，调拨读取/源发出/目标授权接收分别判权。"""
import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--wms', type=Path, required=True); parser.add_argument('--revoke', action='store_true')
    parser.add_argument('--resume-count', action='store_true', help='仅从已记录的真实DRAFT盘点检查点恢复')
    args = parser.parse_args(); os.umask(0o077)
    p = load('wms_controls_setup', 'governance-wms-provision.py'); h = load('wms_controls_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve(); state = json.loads(p.read_private(root / 'state.json')); p.validate_state(state)
    run = Path(p.read_private(root / 'latest-runtime.txt'))
    if run.parent != root: raise RuntimeError('必须是当前工具fixture')
    fixture = json.loads(p.read_private(run / 'fixture.json'))
    if fixture['phase'] != 'W06' or fixture['tenant'] != state['tenant']: raise RuntimeError('fixture分区不符')
    detail = json.loads(subprocess.check_output(['docker', 'inspect', fixture['container']], text=True))[0]
    if detail['Config']['Labels'].get('com.lrj.task') != 'WMS-AUTH-20261004': raise RuntimeError('fixture归属不符')
    tokens = json.loads(p.read_private(run / 'tokens.json')); manager = json.loads(p.read_private(run / 'manager-token.json'))['access_token']
    checks = []; part = {'tenant_id': state['tenant'], 'application_id': 'wms', 'environment': 'local'}
    uid = lambda: str(uuid.uuid4())

    def request(name, port, path, actor='operator', body=None, expected=200, key=None):
        token = manager if actor == 'manager' else tokens[actor]['access_token']
        headers = [('Authorization', 'Bearer ' + token)]
        if key: headers.append(('Idempotency-Key', key))
        status, value = h.request(port, path, headers, None if body is None else json.dumps(body).encode())
        if status != expected:
            p.private(run / ('controls-failure-' + uuid.uuid4().hex + '.json'), json.dumps({'check': name, 'status': status, 'body': value}))
            raise RuntimeError(name + ': unexpected HTTP ' + str(status))
        checks.append({'check': name, 'result': 'PASS', 'status': status})
        p.private(run / 'controls-progress.json', json.dumps(checks, ensure_ascii=False, indent=2)); return value

    def project():
        for kind in ['POLICY', 'DIRECTORY']:
            for _ in range(12):
                with (run / ('controls-project-' + kind + '.log')).open('a') as log:
                    result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli',
                        '-cp', str(Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()),
                        'org.springframework.boot.loader.launch.PropertiesLauncher', str(run / (kind + '.properties'))], stdout=log, stderr=subprocess.STDOUT, timeout=40)
                if result.returncode == 0: break
                if result.returncode != 2: raise RuntimeError('W06可靠投影失败')
                time.sleep(.25)
            else: raise RuntimeError('W06投影未ready')

    receipt = run / 'controls-grants.json'
    grants = json.loads(p.read_private(receipt)) if receipt.exists() else []
    if args.revoke:
        for grant in grants:
            if grant.get('revoked'): continue
            request('revoke only recorded controls demo source', 18546, '/api/governance/v1/access/revoke', 'manager',
                {**part, 'command_id': uid(), 'grant_id': grant['id'], 'expected_version': 1})
            grant['revoked'] = True; p.private(receipt, json.dumps(grants))
        project(); data = json.loads(p.read_private(run / 'controls-data.json'))
        request('same old approval token next count request denied', 18183, '/api/wms/v1/warehouses/WH-A/count-plans/' + data['plan_id'], 'denied', expected=403)
        request('same old receiver token next transfer request denied', 18185, '/api/wms/v1/transfers/' + data['transfer_id'], 'denied', expected=403)
        p.private(run / 'controls-revoke-result.json', json.dumps({'result': 'PASS', 'checks': checks, 'same_access_token': True}, ensure_ascii=False, indent=2))
        print('PASS: %d W06旧Token精准撤权检查。' % len(checks)); return
    if (receipt.exists() and not args.resume_count) or (run / 'controls-result.json').exists(): raise RuntimeError('不能重复运行当前fixture的控制验收')

    def grant_role(code, capabilities, warehouse):
        role = request('create immutable narrow role ' + code, 18546, '/api/governance/v1/access/roles', 'manager',
            {**part, 'command_id': uid(), 'role_code': code, 'role_version': 1, 'capabilities': capabilities})
        stamp = lambda seconds: (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
        grant = request('grant only recorded demo actor ' + code, 18546, '/api/governance/v1/access/scoped-grants', 'manager',
            {**part, 'command_id': uid(), 'member_id': p.uid('membership:denied'), 'member_generation': 1, 'role_id': role['id'],
             'scope_rule': {'version': 1, 'resource_type': 'wms_warehouse', 'clauses': [{'kind': 'SPECIFIED_RESOURCES', 'values': [warehouse], 'include_root': False}]},
             'source_id': 'wms-controls-' + run.name + '-' + code, 'valid_from': stamp(-5), 'valid_to': stamp(3600)}, expected=202)
        grants.append({'id': grant['id'], 'member_id': p.uid('membership:denied'), 'warehouse': warehouse, 'revoked': False})
        p.private(receipt, json.dumps(grants)); project()

    def sql(statement):
        result = subprocess.run(['docker', 'exec', '-i', fixture['container'], 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 --protocol=TCP -uroot --batch --skip-column-names'], input=statement, text=True, capture_output=True, timeout=15)
        if result.returncode: raise RuntimeError('fixture SQL验证失败')
        return result.stdout.strip()

    if args.resume_count:
        checks = json.loads(p.read_private(run / 'controls-progress.json'))
        if not checks or checks[-1]['check'] != 'real count created' or any(value['result'] != 'PASS' for value in checks):
            raise RuntimeError('没有可恢复的真实建盘点检查点')
        # 只读当前专属fixture，必须恰有一个本工具DRAFT计划和一个已应用调整；不猜测未知业务结果。
        pid = sql("SELECT id FROM wms_inventory.count_plan WHERE enterprise_id='ENT-DEMO' AND warehouse_id='WH-A' AND reason_code='IAM-TEST' AND status='DRAFT'")
        aid = sql("SELECT id FROM wms_inventory.warehouse_adjustment WHERE enterprise_id='ENT-DEMO' AND warehouse_id='WH-A' AND reason='IAM-TEST' AND state='APPLIED'")
        if not re.fullmatch(r'[a-f0-9-]{36}', pid) or not re.fullmatch(r'[a-f0-9-]{36}', aid): raise RuntimeError('检查点SQL不能唯一确认')
        path = '/api/wms/v1/warehouses/WH-A/count-plans/' + pid
    else:
        operations = json.loads((args.wms / 'docs/iam/operations.json').read_text())['operations']
        read_capabilities = {op['capability'] for op in operations if op['method'] == 'GET'}
        for op in operations:
            if op['slice'] != 'W06' or op['method'] != 'POST': continue
            path = re.sub(r'\{[^}]+}', 'missing', op['path'].replace('{warehouseId}', 'WH-A'))
            port = 18185 if any(value in path for value in ['/transfers', '/transfer-receipts', '/serial-transfer-receipts']) else 18183
            # Owner将任务规划POST绑定task.read；HTTP方法不能覆盖已经冻结的权限语义。
            if op['capability'] in read_capabilities:
                request('read command follows declared read authority and still validates DTO ' + op['capability'], port, path, 'reader-a', {}, 400, uid())
            else:
                request('reader cannot execute exact control ' + op['capability'], port, path, 'reader-a', {}, 403, uid())
            if '{warehouseId}' in op['path']:
                request('exact warehouse scope rejects foreign action ' + op['capability'], port, path.replace('WH-A', 'WH-B'),
                        'reader-a' if op['capability'] in read_capabilities else 'operator', {}, 403, uid())
        grant_role('wms-w06-approval-only', ['wms.adjustment.approve', 'wms.adjustment.read', 'wms.count.read'], 'WH-A')
        balance, before = sql("SELECT id,on_hand_qty FROM wms_inventory.stock_balance WHERE enterprise_id='ENT-DEMO' AND warehouse_id='WH-A' AND sku_id='SKU-STD' AND location_id='WH-A-STO' AND quality_code='GOOD'").split('\t')
        adjustment = request('actual independently approved adjustment created', 18183, '/api/wms/v1/warehouses/WH-A/adjustments',
            body={'balanceId': balance, 'deltaQty': '2', 'reason': 'IAM-TEST'}, expected=201, key=uid())
        aid = adjustment['id']; path = '/api/wms/v1/warehouses/WH-A/adjustments/' + aid
        approved = request('approval-only actor may approve actual adjustment', 18183, path + '/approvals', 'denied',
            {'decision': 'APPROVED', 'expectedVersion': adjustment['version']}, key=uid())
        request('approval cannot become application permission', 18183, path + '/applications', 'denied', {'expectedVersion': approved['version']}, 403, uid())
        apply_key = uid(); applied = request('separate application permission applies actual adjustment', 18183, path + '/applications', body={'expectedVersion': approved['version']}, expected=202, key=apply_key)
        request('same adjustment application key retains original replay', 18183, path + '/applications', body={'expectedVersion': approved['version']}, expected=202, key=apply_key)
        if Decimal(sql("SELECT on_hand_qty FROM wms_inventory.stock_balance WHERE id='" + balance + "'")) != Decimal(before) + 2: raise RuntimeError('调整重复记量或未应用')
        checks.append({'check': 'real adjustment SQL exactly plus two after replay', 'result': 'PASS'})
        plan = request('real count created', 18183, '/api/wms/v1/warehouses/WH-A/count-plans', body={'locationIds': ['WH-A-STO'], 'reason': 'IAM-TEST'}, expected=201, key=uid())
        pid = plan['id']; path = '/api/wms/v1/warehouses/WH-A/count-plans/' + pid
    request('count drains original lifecycle first', 18183, path + '/freeze-requests', body={'phase': 'QUIESCE'})
    frozen = request('count freezes only drained stock', 18183, path + '/freeze-requests', body={'phase': 'FREEZE'})
    for line in frozen['lines']: request('actual count snapshot observation', 18183, path + '/observations', body={'lineId': line['id'], 'qty': str(line['snapshotQty']), 'roundNo': 1}, key=uid())
    request('all observations reviewed', 18183, path + '/reviews', body={})
    request('approval-only actor approves real count', 18183, path + '/approvals', 'denied', {}, key=uid())
    request('count approval cannot borrow apply scope', 18183, path + '/applications', 'denied', {'lineId': frozen['lines'][0]['id']}, 403, uid())
    request('separate apply authority handles original count line', 18183, path + '/applications', body={'lineId': frozen['lines'][0]['id']}, expected=202, key=uid())
    tid, lid = uid(), uid()
    transfer = request('A source authority creates actual A-to-B transfer', 18185, '/api/wms/v1/transfers', body={'transferId': tid,
        'sourceWarehouseId': 'WH-A', 'targetWarehouseId': 'WH-B', 'lines': [{'lineId': lid, 'skuId': 'SKU-STD', 'plannedQty': '5'}]}, expected=201, key=tid)
    request('B-only reader may see destination leg', 18185, '/api/wms/v1/transfers/' + tid + '?warehouseId=WH-B', 'reader-b')
    listing = request('B-only real SQL list contains visible transfer', 18185, '/api/wms/v1/transfers', 'reader-b')
    if tid not in json.dumps(listing): raise RuntimeError('目的仓读取未包含原单')
    request('B-only read cannot query source context', 18185, '/api/wms/v1/transfers/' + tid + '?warehouseId=WH-A', 'reader-b', expected=403)
    request('source issue uses A exact action scope', 18185, '/api/wms/v1/transfers/' + tid + '/issues', body={'lineId': lid, 'qty': '2'}, expected=202, key=uid())
    request('A write cannot authorize B despite B read', 18185, '/api/wms/v1/transfers/' + tid + '/receipt-authorizations', body={'lineId': lid, 'quantity': '2'}, expected=403, key=uid())
    grant_role('wms-w06-target-receiver', ['wms.transfer.read', 'wms.transfer.authorize_receipt', 'wms.transfer.receive'], 'WH-B')
    request('target-only actor cannot issue source warehouse', 18185, '/api/wms/v1/transfers/' + tid + '/issues', 'denied', {'lineId': lid, 'qty': '1'}, 403, uid())
    authorization = request('B authority allocates actual receipt quota', 18185, '/api/wms/v1/transfers/' + tid + '/receipt-authorizations', 'denied', {'lineId': lid, 'quantity': '2'}, 201, uid())
    request('B authority receives using original quota/version', 18185, '/api/wms/v1/warehouses/WH-B/transfer-receipts', 'denied',
        {'transferId': tid, 'lineId': lid, 'authorizationId': authorization['authorizationId'], 'tokenVersion': authorization['tokenVersion'], 'qty': '2'}, 202, uid())
    real = request('actual transfer shows both physical legs', 18185, '/api/wms/v1/transfers/' + tid, 'reader-b')
    if Decimal(str(real['lines'][0]['issued_qty'])) != 2 or Decimal(str(real['lines'][0]['received_qty'])) != 2: raise RuntimeError('源目的累计不符')
    p.private(run / 'controls-data.json', json.dumps({'adjustment_id': aid, 'plan_id': pid, 'transfer_id': tid, 'line_id': lid}, indent=2))
    p.private(run / 'controls-result.json', json.dumps({'result': 'PASS', 'checks': checks, 'real_mysql': True, 'real_pkce': True,
        'physical_transfer_quantity': '2', 'inventory_transfer_completion_claimed': False}, ensure_ascii=False, indent=2))
    print('PASS: %d真实盘点/审批/应用/源目的调拨检查。' % len(checks))


if __name__ == '__main__':
    main()
