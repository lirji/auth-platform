#!/usr/bin/env python3
"""实际旧IdP机器与新旧Docker Owner兼容验收；仅连接已标记的WMS测试数据库。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--docker-directory', type=Path, required=True)
    args = parser.parse_args(); os.umask(0o077)
    def load(name, filename):
        spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
        value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
    p = load('machine_smoke_private', 'governance-wms-provision.py')
    h = load('machine_smoke_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve(); target = args.docker_directory.resolve()
    run = Path(p.read_private(root / 'latest-runtime.txt'))
    if run.parent != root: raise RuntimeError('只能验收当前fixture')
    fixture = json.loads(p.read_private(run / 'fixture.json'))
    detail = json.loads(subprocess.check_output(['docker', 'inspect', fixture['container']]))[0]
    if detail['Config']['Labels'].get('com.lrj.task') != 'WMS-AUTH-20261004': raise RuntimeError('数据库归属不符')
    output = target / ('machine-smoke-' + uuid.uuid4().hex[:12]); output.mkdir(mode=0o700)
    images = json.loads(p.read_private(target / 'candidate-images.json'))
    environments = json.loads(p.read_private(run / 'environments.json'))
    tokens = {subject: json.loads(p.read_private(target / 'machines' / (subject + '-token.json')))['access_token']
              for subject in ['inventory-worker', 'recon-worker', 'wms-fulfillment']}
    human = json.loads(p.read_private(run / 'tokens.json'))['operator']['access_token']
    checks = []; created = []
    def check(name, port, path, subject='inventory-worker', body=None, expected=200, key=None, enterprise='ENT-DEMO'):
        headers = [('Authorization', 'Bearer ' + (human if subject == 'human' else tokens[subject])), ('X-Wms-Enterprise-Id', enterprise)]
        if key: headers.append(('Idempotency-Key', key))
        status, value = h.request(port, path, headers, None if body is None else json.dumps(body).encode())
        if status != expected:
            p.private(output / 'failure.json', json.dumps({'check': name, 'status': status, 'body': value}))
            raise RuntimeError(name + ': HTTP ' + str(status))
        checks.append({'check': name, 'result': 'PASS', 'status': status}); return value
    def start(service, port, image, legacy=False):
        env = environments[service].copy()
        for key in env:
            if key.endswith('_JDBC_URL'): env[key] = env[key].replace('127.0.0.1:', 'host.docker.internal:')
        env.update(WMS_HTTP_PORT=str(port), WMS_BIND_ADDRESS='0.0.0.0',
                   JAVA_TOOL_OPTIONS='-Xms128m -Xmx384m', WMS_IAM_CONFIGURATION='/run/wms-central/central.properties',
                   WMS_OIDC_JWK_SET_URI='http://host.docker.internal:18090/.well-known/jwks')
        if legacy: env.update(WMS_IAM_ENABLED='false', WMS_OIDC_ISSUER='http://localhost:8000', WMS_OIDC_CLIENT_ID='wms-platform',
                             WMS_OIDC_JWK_SET_URI='http://host.docker.internal:8000/.well-known/jwks')
        else: env.update(WMS_RECONCILIATION_WINDOW_ENABLED='true', WMS_RECONCILIATION_ALLOWED_SUBJECTS='recon-worker',
                         WMS_INTERNAL_OIDC_ISSUER='http://localhost:8000', WMS_INTERNAL_OIDC_CLIENT_ID='wms-platform',
                         WMS_INTERNAL_OIDC_JWK_SET_URI='http://host.docker.internal:8000/.well-known/jwks', WMS_INTERNAL_OIDC_ALLOWED_SUBJECTS='recon-worker')
        envfile = output / (service + '.env'); p.private(envfile, '\n'.join(k + '=' + v for k, v in env.items()) + '\n')
        name = 'wms-auth-machine-' + service + '-' + output.name[-12:]
        command = ['docker', 'run', '-d', '--name', name, '--label', 'com.lrj.task=WMS-AUTH-20261004',
                   '--network', 'wms-auth-docker-validation', '--env-file', str(envfile), '-p', '127.0.0.1:' + str(port) + ':' + str(port)]
        if not legacy: command += ['--mount', 'type=volume,source=auth-platform-wms-' + service + '-credentials,target=/run/wms-central,readonly']
        subprocess.run(command + [image, '/app/wms-' + service + '.jar', '--seata.enabled=false'], check=True, stdout=subprocess.DEVNULL)
        created.append(name); p.private(output / 'owned-containers.json', json.dumps(created))
        for _ in range(180):
            try:
                if h.request(port, '/actuator/health/readiness')[0] == 200: return
            except (OSError, ValueError): pass
            time.sleep(.5)
        raise RuntimeError(service + ' 未ready')
    try:
        serial = 'IAM-W07-' + uuid.uuid4().hex.upper(); operation = str(uuid.uuid4())
        body = {'warehouseId': 'WH-A', 'skuId': 'SKU-SN', 'serial': serial, 'operationId': operation}
        base = '/internal/wms/v1/serial-identities'; claim_key = str(uuid.uuid4()); activate_key = str(uuid.uuid4())
        check('real old IdP machine claims in central mode', 18184, base + '/claims', body=body, key=claim_key)
        check('same machine claim key replays', 18184, base + '/claims', body=body, key=claim_key)
        check('real old IdP machine activates original operation', 18184, base + '/activations', body=body, key=activate_key)
        query = base + '?' + urllib.parse.urlencode({'skuId': 'SKU-SN', 'serial': serial})
        current = check('machine reads real active identity', 18184, query)
        if current['state'] != 'ACTIVE' or current['ownerWarehouseId'] != 'WH-A': raise RuntimeError('登记实际状态不符')
        check('foreign warehouse rejected before machine write', 18184, base + '/claims', body={**body, 'warehouseId': 'WH-C'}, expected=403, key=str(uuid.uuid4()))
        check('wrong enterprise rejected by actual Owner', 18184, query, enterprise='ENT-OTHER', expected=403)
        check('other machine not in serial whitelist', 18184, query, subject='recon-worker', expected=401)
        check('central human cannot enter machine chain', 18184, query, subject='human', expected=401)
        check('old machine cannot enter public central API', 18184, '/api/wms/v1/me/access', expected=401)
        check('TCC machine trusted only on internal inventory chain', 18183, '/internal/wms/unknown-machine-test', subject='wms-fulfillment', expected=404)
        check('TCC machine cannot enter public inventory', 18183, '/api/wms/v1/warehouses', subject='wms-fulfillment', expected=401)
        for service, port in [('inbound', 18191), ('outbound', 18192)]:
            start(service, port, images['java'])
            window = '/internal/wms/v1/warehouses/WH-A/reconciliation-windows/IAM-' + uuid.uuid4().hex
            cutoff = '2000-01-01T00:00:00Z'
            value = check(service + ' real machine freezes actual empty source window', port, window, 'recon-worker', {'cutoff': cutoff})
            if value['state'] != 'COMPLETE' or value['factCount'] != 0: raise RuntimeError('来源窗口事实不符')
            check(service + ' real machine reads original source evidence', port, window + '/facts?cutoff=' + urllib.parse.quote(cutoff), 'recon-worker')
            check(service + ' machine foreign warehouse denied', port, window.replace('WH-A', 'WH-C'), 'recon-worker', {'cutoff': cutoff}, 403)
            check(service + ' other machine rejected', port, window, 'inventory-worker', {'cutoff': cutoff}, 401)
        baseline = json.loads(p.read_private(target / 'baseline-containers.json'))
        old_image = next(row['Image'] for row in baseline if row['Name'] == '/wms-local-serial-registry-1')
        start('serial-registry', 18194, old_image, legacy=True)
        previous = check('previous deployed image reads new committed registry row without DB rollback', 18194, query)
        if previous != current: raise RuntimeError('新旧Owner返回不兼容')
        check('previous image same original activation key replays', 18194, base + '/activations', body=body, key=activate_key)
        check('current central image remains readable after legacy coexistence', 18184, query)
        sql = "SELECT state,owner_warehouse_id FROM wms_serial_registry.serial_registry WHERE enterprise_id='ENT-DEMO' AND normalized_serial='" + serial + "'; SELECT COUNT(*) FROM wms_serial_registry.serial_http_command WHERE enterprise_id='ENT-DEMO' AND command_id IN ('" + claim_key + "','" + activate_key + "');"
        # root口令由已标记测试容器注入；不把密码放命令行或异常日志，应用口令不能当root口令。
        actual = subprocess.check_output(['docker', 'exec', '-i', fixture['container'], 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h127.0.0.1 --protocol=TCP -uroot -N -B'], input=sql.encode(), stderr=subprocess.DEVNULL).decode().strip()
        if actual != 'ACTIVE\tWH-A\n2': raise RuntimeError('实际SQL登记/幂等命令不符')
        checks.append({'check': 'real MySQL one active identity and exactly two original command rows', 'result': 'PASS'})
        p.private(output / 'result.json', json.dumps({'result': 'PASS', 'checks': checks, 'real_old_idp': True, 'old_image': old_image,
            'current_image': images['java'], 'original_business_database_used': False, 'tcc_business_completion_claimed': False}, ensure_ascii=False, indent=2))
        print('PASS: %d真实机器Owner、SQL、新旧制品兼容检查；未宣称TCC业务完成。' % len(checks), flush=True)
    finally:
        for name in reversed(created): subprocess.run(['docker', 'stop', name], check=False, stdout=subprocess.DEVNULL, timeout=30)


if __name__ == '__main__':
    main()
