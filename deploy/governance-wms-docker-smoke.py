#!/usr/bin/env python3
"""当前源码Docker/HTTPS验收：只复用明确标记的测试库，不连接原WMS业务数据库。"""
import argparse
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--docker-directory', type=Path, required=True)
    parser.add_argument('--serve', action='store_true')
    args = parser.parse_args(); os.umask(0o077)
    def load(name, file):
        spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
        value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
    p = load('docker_wms_provision', 'governance-wms-provision.py'); h = load('docker_wms_http', 'governance-context-smoke.py')
    root = args.state_directory.resolve(); state = json.loads(p.read_private(root / 'state.json')); p.validate_state(state)
    run = Path(p.read_private(root / 'latest-runtime.txt')); target = args.docker_directory.resolve()
    if run.parent != root: raise RuntimeError('只能使用当前fixture')
    fixture = json.loads(p.read_private(run / 'fixture.json')); database = json.loads(p.read_private(run / 'database.json'))
    db = json.loads(subprocess.check_output(['docker', 'inspect', fixture['container']], text=True))[0]
    if db['Config']['Labels'].get('com.lrj.task') != 'WMS-AUTH-20261004': raise RuntimeError('测试库归属不符')
    images = json.loads(p.read_private(target / 'candidate-images.json'))
    server = json.loads(subprocess.check_output(['docker', 'inspect', 'auth-governance-wms-server'], text=True))[0]
    if server['Image'] != images['auth'] or server['Config']['Labels'].get('com.docker.compose.service') != 'governance-wms-server':
        raise RuntimeError('不能确认专属Auth HTTPS测试入口')
    output = target / ('fixture-' + run.name); output.mkdir(mode=0o700, exist_ok=True)
    if (output / 'owned-containers.json').exists(): raise RuntimeError('已有容器检查点，必须先核对状态，不能覆盖')
    tokens = json.loads(p.read_private(run / 'tokens.json')); environments = json.loads(p.read_private(run / 'environments.json'))
    created = []; checks = []
    def check(name, port, path, actor='operator', expected=200):
        headers = [('Authorization', 'Bearer ' + tokens[actor]['access_token'])] if actor else []
        actual, result = h.request(port, path, headers)
        if actual != expected: raise RuntimeError(name + ': HTTP ' + str(actual))
        checks.append({'check': name, 'result': 'PASS', 'status': actual}); return result
    try:
        # 未信任CA或证书主机不匹配必须失败，不能因本机环境关闭证书校验。
        try: urllib.request.urlopen('https://localhost:18545/actuator/health', timeout=5)
        except ssl.SSLCertVerificationError: pass
        except urllib.error.URLError as error:
            if not isinstance(error.reason, ssl.SSLCertVerificationError): raise
        else: raise RuntimeError('未知CA被错误信任')
        trusted = ssl.create_default_context(cafile=str(target / 'bootstrap/ca.pem'))
        with urllib.request.urlopen('https://localhost:18545/actuator/health', context=trusted, timeout=5) as response:
            if response.status != 200: raise RuntimeError('可信TLS入口不可用')
        try:
            with socket.create_connection(('127.0.0.1', 18545), timeout=5) as connection:
                trusted.wrap_socket(connection, server_hostname='not-in-wms-certificate.local')
        except ssl.SSLCertVerificationError: pass
        else: raise RuntimeError('错误主机名被错误信任')
        checks.append({'check': 'trusted CA succeeds; unknown CA and hostname mismatch fail', 'result': 'PASS'})
        subprocess.run(['docker', 'start', fixture['container']], check=True, stdout=subprocess.DEVNULL)
        network = 'wms-auth-docker-validation'
        detail = subprocess.run(['docker', 'network', 'inspect', network], capture_output=True, text=True)
        if detail.returncode:
            ids = subprocess.check_output(['docker', 'network', 'ls', '-q'], text=True).split()
            networks = json.loads(subprocess.check_output(['docker', 'network', 'inspect', *ids], text=True))
            used = [ipaddress.ip_network(value['Subnet']) for entry in networks for value in (entry['IPAM'].get('Config') or []) if value.get('Subnet')]
            # 默认地址池已用尽时也不清理别的网络；从已核对的不重叠专属/28中分配。
            subnet = next((ipaddress.ip_network('10.254.' + str(index) + '.0/28') for index in range(200, 255)
                if not any(ipaddress.ip_network('10.254.' + str(index) + '.0/28').overlaps(value) for value in used if value.version == 4)), None)
            if subnet is None: raise RuntimeError('没有不重叠的专属测试网段')
            subprocess.run(['docker', 'network', 'create', '--subnet', str(subnet), '--label', 'com.lrj.task=WMS-AUTH-20261004', network], check=True, stdout=subprocess.DEVNULL)
        elif json.loads(detail.stdout)[0]['Labels'].get('com.lrj.task') != 'WMS-AUTH-20261004': raise RuntimeError('测试网络归属不符')
        for service, port in [('inbound', 18181), ('outbound', 18182), ('inventory', 18183), ('serial-registry', 18184), ('fulfillment', 18185)]:
            env = environments[service].copy()
            for key in env:
                if key.endswith('_JDBC_URL'): env[key] = env[key].replace('127.0.0.1:', 'host.docker.internal:')
            env.update(WMS_BIND_ADDRESS='0.0.0.0', WMS_IAM_CONFIGURATION='/run/wms-central/central.properties',
                WMS_OIDC_JWK_SET_URI='http://host.docker.internal:18090/.well-known/jwks',
                JAVA_TOOL_OPTIONS='-Xms128m -Xmx384m -Djavax.net.ssl.trustStore=/run/wms-central/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit')
            if service != 'fulfillment':
                env.update(WMS_INTERNAL_OIDC_ISSUER='http://localhost:8000', WMS_INTERNAL_OIDC_CLIENT_ID='wms-platform',
                    WMS_INTERNAL_OIDC_JWK_SET_URI='http://host.docker.internal:8000/.well-known/jwks',
                    WMS_INTERNAL_OIDC_ALLOWED_SUBJECTS={'inbound': 'recon-worker', 'outbound': 'recon-worker', 'inventory': 'wms-fulfillment', 'serial-registry': 'inventory-worker'}[service])
            envfile = output / (service + '.env'); p.private(envfile, '\n'.join(key + '=' + value for key, value in env.items()) + '\n')
            name = 'wms-auth-docker-it-' + service
            if subprocess.run(['docker', 'inspect', name], capture_output=True).returncode == 0: raise RuntimeError('已有测试容器，不覆盖')
            volume = 'auth-platform-wms-' + ('serial' if service == 'serial-registry' else service) + '-credentials'
            subprocess.run(['docker', 'run', '-d', '--name', name, '--label', 'com.lrj.task=WMS-AUTH-20261004',
                '--network', network, '--network-alias', service, '--env-file', str(envfile), '-p', '127.0.0.1:' + str(port) + ':' + str(port),
                '--mount', 'type=volume,source=' + volume + ',target=/run/wms-central,readonly', images['java'],
                '/app/wms-' + service + '.jar', '--seata.enabled=false'], check=True, stdout=subprocess.DEVNULL)
            created.append(name); p.private(output / 'owned-containers.json', json.dumps(created))
            for _ in range(180):
                try:
                    if h.request(port, '/actuator/health/readiness')[0] == 200: break
                except (OSError, ValueError): pass
                time.sleep(.5)
            else: raise RuntimeError(service + ' Docker未ready')
            mode = subprocess.check_output(['docker', 'exec', name, 'stat', '-c', '%a %u', '/run/wms-central/central.properties'], text=True).strip()
            user = subprocess.check_output(['docker', 'exec', name, 'id', '-u'], text=True).strip()
            if mode != '600 10001' or user != '10001': raise RuntimeError('非root/独立0600文件不符')
            checks.append({'check': service + ' immutable image, non-root, own 0600 credential', 'result': 'PASS'})
        check('real container inventory SDK HTTPS', 18183, '/api/wms/v1/warehouses/WH-A/locations')
        check('real container inbound SDK HTTPS', 18181, '/api/wms/v1/warehouses/WH-A/inbound-orders')
        check('real container outbound SDK HTTPS', 18182, '/api/wms/v1/warehouses/WH-A/outbound-orders')
        check('real container fulfillment SDK HTTPS', 18185, '/api/wms/v1/fulfillments')
        check('real container serial unique SDK credential', 18184, '/api/wms/v1/me/access?warehouseId=WH-A')
        check('anonymous remains rejected', 18183, '/api/wms/v1/warehouses', None, 401)
        check('same revoked reader token remains denied over Docker TLS', 18183, '/api/wms/v1/warehouses/WH-A/locations', 'reader-a', 403)
        check('B reader cannot access A warehouse', 18183, '/api/wms/v1/warehouses/WH-A/locations', 'reader-b', 403)
        check('actual B reader own warehouse allowed', 18183, '/api/wms/v1/warehouses/WH-B/locations', 'reader-b')
        subprocess.run(['docker', 'pause', 'auth-governance-wms-server'], check=True, stdout=subprocess.DEVNULL)
        try:
            started = time.monotonic()
            value = check('Auth TLS unavailable refuses old operator token', 18183, '/api/wms/v1/warehouses/WH-A/locations', expected=503)
            if value.get('code') != 'AUTHORIZATION_UNAVAILABLE' or time.monotonic() - started > 12: raise RuntimeError('故障响应或期限不符')
            check('Auth TLS unavailable clears navigation', 18183, '/api/wms/v1/me/access?warehouseId=WH-A', expected=503)
        finally: subprocess.run(['docker', 'unpause', 'auth-governance-wms-server'], check=True, stdout=subprocess.DEVNULL)
        check('same token recovers after TLS Auth restored', 18183, '/api/wms/v1/warehouses/WH-A/locations')
        p.private(output / 'result.json', json.dumps({'result': 'PASS', 'checks': checks, 'real_tls': True,
            'original_business_database_used': False, 'images': images}, ensure_ascii=False, indent=2))
        print('PASS: %d当前Docker/TLS/独立凭据/故障检查。' % len(checks), flush=True)
        if args.serve:
            deadline = time.monotonic() + 3600
            while time.monotonic() < deadline and not (output / 'stop').exists(): time.sleep(.5)
    finally:
        for name in reversed(created): subprocess.run(['docker', 'stop', name], check=False, stdout=subprocess.DEVNULL, timeout=30)


if __name__ == '__main__':
    main()
