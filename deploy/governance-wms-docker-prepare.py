#!/usr/bin/env python3
"""将已确认的local-wms身份配置转换为本机Docker私密启动资料，不启动服务或改业务数据。"""
import argparse
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--target-directory', type=Path, required=True)
    parser.add_argument('--wms-original', type=Path, required=True)
    args = parser.parse_args(); os.umask(0o077)
    spec = importlib.util.spec_from_file_location('wms_docker_provision', Path(__file__).with_name('governance-wms-provision.py'))
    p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)
    root = args.state_directory.resolve(); state = json.loads(p.read_private(root / 'state.json')); p.validate_state(state)
    target = args.target_directory.resolve()
    if target.exists(): raise RuntimeError('目标已有资料，须复用或另指定新检查点，不覆盖TLS/回退凭据')
    services = ['inventory', 'inbound', 'outbound', 'fulfillment', 'serial-registry']
    original = args.wms_original.resolve()
    env = p.read_private(original / '.env')
    # 记录旧制品和挂载用于正常回退；完整inspect含私密env，只存600文件。
    names = ['wms-local-' + name + '-1' for name in services + ['console']]
    baseline = json.loads(subprocess.check_output(['docker', 'inspect', *names], text=True))
    if any(row['Config']['Labels'].get('com.docker.compose.project') != 'wms-local' for row in baseline):
        raise RuntimeError('目标不是已批准wms-local')
    # 先完成只读目标核验，避免错误Graph绑定留下无法复用的半套私密材料。
    graph_container = json.loads(subprocess.check_output(['docker', 'inspect', 'auth-governance-graph'], text=True))[0]
    ports = graph_container['NetworkSettings']['Ports'].get('8443/tcp') or []
    if not graph_container['State']['Running'] or not any(item['HostIp'] == '127.0.0.1' and item['HostPort'] == '18544' for item in ports):
        raise RuntimeError('既有Graph18544绑定或状态不符')
    graph_ip = graph_container['NetworkSettings']['Networks']['bridge']['IPAddress']
    if ipaddress.ip_address(graph_ip).version != 4: raise RuntimeError('Graph必须为核对后的IPv4')
    target.mkdir(mode=0o700, parents=True); bootstrap = target / 'bootstrap'; bootstrap.mkdir(mode=0o700)
    p.private(target / 'baseline-containers.json', json.dumps(baseline))
    # 不把密码作为命令行参数；openssl/keytool读取独立600口令文件。
    password = secrets.token_urlsafe(32); p.private(bootstrap / 'tls-password', password)
    certificate = '''[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=WMS Local Auth CA
[ext]
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
'''
    p.private(bootstrap / 'ca.cnf', certificate)
    p.private(bootstrap / 'server.cnf', '''[req]
distinguished_name=dn
prompt=no
[dn]
CN=host.docker.internal
''')
    p.private(bootstrap / 'server.ext', '''basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,DNS:host.docker.internal,IP:127.0.0.1
''')
    commands = [
        ['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '365', '-config', 'ca.cnf', '-keyout', 'ca.key', '-out', 'ca.pem'],
        ['openssl', 'req', '-new', '-newkey', 'rsa:3072', '-nodes', '-config', 'server.cnf', '-keyout', 'server.key', '-out', 'server.csr'],
        ['openssl', 'x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key', '-CAcreateserial', '-days', '90', '-extfile', 'server.ext', '-out', 'server.pem'],
        ['openssl', 'pkcs12', '-export', '-in', 'server.pem', '-inkey', 'server.key', '-certfile', 'ca.pem', '-name', 'wms-local', '-out', 'server.p12', '-passout', 'file:tls-password'],
        ['keytool', '-importcert', '-noprompt', '-alias', 'wms-local-ca', '-file', 'ca.pem', '-keystore', 'truststore.p12', '-storetype', 'PKCS12', '-storepass', 'changeit'],
    ]
    with (target / 'tls-prepare.log').open('w') as log:
        for command in commands: subprocess.run(command, cwd=bootstrap, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
    for file in bootstrap.iterdir(): file.chmod(0o600)
    values = p.properties(p.read_private(root / 'runtime/server.properties'))
    for key in list(values):
        if key == 'jdbc.url':
            values[key] = values[key].replace('127.0.0.1:45432', 'host.docker.internal:45432').replace('localhost:45432', 'host.docker.internal:45432')
    p.write_properties(bootstrap / 'server.properties', values)
    p.write_properties(bootstrap / 'application.properties', {
        'server.address': '0.0.0.0', 'server.port': '18545', 'server.ssl.enabled': 'true',
        'server.ssl.key-store': 'file:/run/wms-auth/server.p12', 'server.ssl.key-store-password': password,
        'server.ssl.key-store-type': 'PKCS12', 'server.ssl.key-alias': 'wms-local',
        'authz.governance.enabled': 'true', 'authz.governance.configuration': '/run/wms-auth/server.properties',
        'authz.governance.access.enabled': 'true', 'authz.governance.scope.enabled': 'true', 'authz.governance.navigation.enabled': 'true',
        'authz.server.security.enabled': 'true', 'authz.server.security.token': secrets.token_urlsafe(48),
    })
    for service in services:
        values = p.properties(p.read_private(root / 'runtime' / (service + '.properties')))
        values['central.base-url'] = 'https://host.docker.internal:18545'
        p.write_properties(bootstrap / (service + '.properties'), values)
    projections = target / 'projections'; projections.mkdir(mode=0o700)
    # 既有投影器显式扫描目录，追加WMS两分区后正常重启；不另建授权事实来源。
    for kind in ['POLICY', 'DIRECTORY']:
        values = {key: value for key, value in p.properties(p.read_private(bootstrap / 'server.properties')).items()
                  if key.startswith('jdbc.') or key.startswith('graph.')}
        values.update({'access.tenant': state['tenant'], 'access.application': 'wms', 'access.environment': 'local', 'projection.kind': kind})
        p.write_properties(projections / ('wms-' + kind.lower() + '.properties'), values)
    # 原相对机器凭据目录必须保持原宿主来源，不随任务worktree位置变化。
    paths = {}
    for row in baseline:
        for mount in row['Mounts']:
            if mount['Destination'] == '/run/wms/recon-tokens': paths['WMS_RECON_TOKEN_HOST_DIR'] = mount['Source']
            if mount['Destination'] == '/run/wms/fulfillment-tokens': paths['WMS_FULFILLMENT_TOKEN_HOST_DIR'] = mount['Source']
    p.private(target / 'wms-runtime.env', env.rstrip() + '\n' + '\n'.join(key + '=' + value for key, value in paths.items()) + '\n')
    # 绑定原Graph本体而非Docker Desktop宿主端口；不创建或修改其网络/数据。
    p.private(target / 'graph-container-binding.json', json.dumps({'container_id': graph_container['Id'],
        'image': graph_container['Image'], 'ipv4': graph_ip, 'internal_port': 8443}, indent=2))
    p.private(target / 'governance-graph-peer.conf', 'set $graph_upstream http://' + graph_ip + ':8443;\n')
    p.private(target / 'auth-runtime.env', 'WMS_AUTH_BOOTSTRAP_DIR=' + str(bootstrap) + '\nWMS_AUTH_GRAPH_IPV4=' + graph_ip + '\n')
    p.private(target / 'preparation-result.json', json.dumps({'result': 'PASS', 'tenant': state['tenant'], 'organization': 'local-wms',
        'enterprise': 'ENT-DEMO', 'tls': True, 'consumer_credentials': 5, 'business_data_modified': False, 'deployed': False}, indent=2))
    print('PASS: 本机HTTPS及五独立凭据准备完成；未启动服务或改业务数据。')


if __name__ == '__main__':
    main()
