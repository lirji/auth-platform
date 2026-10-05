#!/usr/bin/env python3
"""真实中央身份的入出库/履约动作与SQL幂等验收，仅操作当前工具专属fixture。"""
import argparse
from decimal import Decimal
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import uuid


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--wms', type=Path, required=True)
    args = parser.parse_args(); os.umask(0o077)
    p = load('wms_action_setup', 'governance-wms-provision.py'); h = load('wms_action_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve(); run = Path(p.read_private(root / 'latest-runtime.txt'))
    if run.parent != root or (run / 'actions-result.json').exists(): raise RuntimeError('必须使用尚未执行动作验收的当前fixture')
    fixture = json.loads(p.read_private(run / 'fixture.json'))
    if fixture['phase'] not in {'W05', 'W06'} or not re.fullmatch(r'wms-auth-it-[a-f0-9]{12}', fixture['container']):
        raise RuntimeError('必须是本工具创建的W05/W06专属fixture')
    details = json.loads(subprocess.check_output(['docker', 'inspect', fixture['container']], text=True))[0]
    if details['Config']['Labels'].get('com.lrj.task') != 'WMS-AUTH-20261004': raise RuntimeError('测试容器归属不符')
    tokens = json.loads(p.read_private(run / 'tokens.json')); checks = []

    def check(name, port, path, user='operator', body=None, status=200, key=None):
        headers = [('Authorization', 'Bearer ' + tokens[user]['access_token'])]
        if key: headers.append(('Idempotency-Key', key))
        actual, result = h.request(port, path, headers, None if body is None else json.dumps(body).encode())
        if actual != status:
            p.private(run / ('action-failure-' + uuid.uuid4().hex + '.json'), json.dumps({'check': name, 'status': actual, 'body': result}))
            raise RuntimeError(name + ': unexpected HTTP ' + str(actual))
        checks.append({'check': name, 'result': 'PASS', 'status': actual})
        p.private(run / 'actions-progress.json', json.dumps(checks, ensure_ascii=False, indent=2))
        return result

    def sql(statement):
        result = subprocess.run(['docker', 'exec', '-i', fixture['container'], 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'],
            input=statement, text=True, capture_output=True, timeout=15)
        if result.returncode: raise RuntimeError('fixture SQL验证失败')
        return result.stdout.strip()

    operations = json.loads((args.wms / 'docs/iam/operations.json').read_text())['operations']
    # 同名scope中的企业/仓类型按Owner绑定逐一检查；只读规划入口沿用源契约，不谎称所有POST均为写权。
    for op in operations:
        if op['slice'] != 'W05' or op['method'] != 'POST': continue
        path = re.sub(r'\{[^}]+}', 'missing', op['path'].replace('{warehouseId}', 'WH-A'))
        port = 18185 if path.startswith('/api/wms/v1/fulfillments') else 18181 if any(part in path for part in ['inbound-orders', 'putaways']) else 18182
        check('read role denies exact action ' + op['capability'], port, path, 'reader-a', {}, 403, uuid.uuid4().hex)
        if '{warehouseId}' in op['path']:
            check('A write cannot borrow B read ' + op['capability'], port, path.replace('WH-A', 'WH-B'), body={}, status=403, key=uuid.uuid4().hex)
    order_key = 'iam-inb-' + uuid.uuid4().hex
    body = {'sourceSystem': 'IAM-TEST', 'externalNo': order_key, 'ownerId': 'OWNER-SELF',
            'lines': [{'lineId': order_key + '-L1', 'externalLineId': order_key + '-L1', 'skuId': 'SKU-STD', 'expectedQty': '5', 'unit': 'EA'}]}
    created = check('central operator creates actual inbound order', 18181, '/api/wms/v1/warehouses/WH-A/inbound-orders', body=body, status=201, key=order_key)
    order_id = created['orderId']; line_id = order_key + '-L1'
    receipt_key = 'iam-rcv-' + uuid.uuid4().hex
    receipt = {'lineId': line_id, 'qty': '2', 'receiptPartId': receipt_key}
    first = check('central receive retains accepted 202 protocol', 18181, '/api/wms/v1/warehouses/WH-A/inbound-orders/' + order_id + '/receipts', body=receipt, status=202, key=receipt_key)
    replay = check('same receive key replays original result', 18181, '/api/wms/v1/warehouses/WH-A/inbound-orders/' + order_id + '/receipts', body=receipt, status=202, key=receipt_key)
    p.private(run / 'receive-replay.json', json.dumps({'first': first, 'replay': replay}, ensure_ascii=False, indent=2))
    # 原协议显式区分首次与重放，命令/效果/状态必须相同；不能把正常replayed标志变化当成业务重复。
    if first.get('replayed') is not False or replay.get('replayed') is not True or \
            {key: value for key, value in first.items() if key != 'replayed'} != {key: value for key, value in replay.items() if key != 'replayed'}:
        raise RuntimeError('收货重放命令、效果或状态变化')
    if Decimal(sql("SELECT received_physical_qty FROM wms_inbound.inbound_line WHERE enterprise_id='ENT-DEMO' AND warehouse_id='WH-A' AND external_line_id='" + line_id + "'")) != Decimal('2'):
        raise RuntimeError('收货未持久化或重放重复记量')
    checks.append({'check': 'real inbound SQL exact received quantity after replay', 'result': 'PASS'})
    outbound_key = 'iam-ob-' + uuid.uuid4().hex
    outbound = {'allocationId': outbound_key + '-ALLOC', 'attemptId': outbound_key + '-ATT', 'ownerId': 'OWNER-SELF',
                'authorizationId': outbound_key + '-AUTH', 'lines': [{'orderLineId': outbound_key + '-L1', 'skuId': 'SKU-STD', 'qty': '3', 'baseUnit': 'EA'}]}
    order = check('central warehouse execute creates pending outbound', 18182, '/api/wms/v1/warehouses/WH-A/outbound-orders', body=outbound, status=201, key=outbound_key)
    check('outbound actual detail authorized', 18182, '/api/wms/v1/warehouses/WH-A/outbound-orders/' + order['id'])
    check('valid pick permission cannot bypass original TCC authorization', 18182, '/api/wms/v1/warehouses/WH-A/outbound-orders/' + order['id'] + '/pick-tasks',
          body={'orderLineId': outbound_key + '-L1', 'sourceLocationId': 'WH-A-STO', 'stagingLocationId': 'WH-A-STG', 'qty': '1'}, status=409, key=uuid.uuid4().hex)
    fulfillment_key = 'iam-ff-' + uuid.uuid4().hex
    ff = {'sourceSystem': 'IAM-TEST', 'sourceOrderNo': fulfillment_key, 'ownerId': 'OWNER-SELF', 'strategyVersion': 1,
          'lines': [{'sourceLineId': fulfillment_key + '-L1', 'skuId': 'SKU-STD', 'requestedQty': '3', 'baseUnit': 'EA'}]}
    created_ff = check('enterprise create does not require unrelated warehouse write', 18185, '/api/wms/v1/fulfillments', body=ff, status=201, key=fulfillment_key)
    check('real fulfillment persisted and readable', 18185, '/api/wms/v1/fulfillments/' + created_ff['id'])
    check('central public identity cannot use legacy serial machine protocol', 18184,
          '/internal/wms/v1/serial-identities/claims', body={'warehouseId': 'WH-A', 'skuId': 'SKU-STD', 'serial': 'IAM-PRIVATE', 'operationId': uuid.uuid4().hex},
          status=401, key=uuid.uuid4().hex)
    p.private(run / 'action-data.json', json.dumps({'inbound_order_id': order_id, 'inbound_line_id': line_id,
          'outbound_order_id': order['id'], 'fulfillment_id': created_ff['id']}, ensure_ascii=False, indent=2))
    p.private(run / 'actions-result.json', json.dumps({'result': 'PASS', 'checks': checks, 'real_mysql': True,
          'real_pkce': True, 'phase': fixture['phase'], 'inventory_completion_claimed': False}, ensure_ascii=False, indent=2))
    print('PASS: %d真实中央入出库/履约/SQL检查。' % len(checks))


if __name__ == '__main__':
    main()
