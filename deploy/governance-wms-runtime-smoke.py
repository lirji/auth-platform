#!/usr/bin/env python3
"""WMS真实身份/范围/SQL验收：仅创建专属MySQL测试容器和受控local-wms演示授权。"""
import argparse
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import uuid
import zipfile


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


p = module('wms_provision', 'governance-wms-provision.py')
i = module('wms_identity', 'governance-wms-identity-smoke.py')
h = module('wms_context', 'governance-context-smoke.py')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wms', type=Path, required=True)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--phase', choices=['W04', 'W05', 'W06'], default='W04')
    parser.add_argument('--serve', action='store_true')
    args = parser.parse_args(); os.umask(0o077)
    wms = args.wms.resolve(); state_root = args.state_directory.resolve()
    state = json.loads(p.read_private(state_root / 'state.json')); p.validate_state(state)
    run = state_root / ('runtime-' + args.phase.lower() + '-' + secrets.token_hex(6)); run.mkdir(mode=0o700)
    p.private(state_root / 'latest-runtime.txt', str(run))
    # 必须验证当前安全制品确实进入各服务，拒绝宿主旧JAR形成假验收。
    security = wms / 'wms-security/target/wms-security-0.1.0-SNAPSHOT.jar'
    relevant = list((wms / 'wms-security/src/main').rglob('*'))
    if not security.is_file() or security.stat().st_mtime < max(file.stat().st_mtime for file in relevant if file.is_file()):
        raise RuntimeError('需要先成功构建当前WMS安全源码')
    for service in ['inventory', 'inbound', 'outbound', 'fulfillment', 'serial-registry']:
        jar = wms / ('wms-' + service) / 'target' / ('wms-' + service + '-0.1.0-SNAPSHOT.jar')
        with zipfile.ZipFile(jar) as archive:
            if archive.read('BOOT-INF/lib/wms-security-0.1.0-SNAPSHOT.jar') != security.read_bytes():
                raise RuntimeError('WMS服务包含旧安全制品：' + service)
    uid = lambda: str(uuid.uuid4())
    stamp = lambda seconds: (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')
    admin = Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()
    server = Path('auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar').resolve()
    checks = []

    def cli(name, configuration):
        package = 'com.lrj.authz.admin.governance.' if name == 'ReliableProjectionCli' else 'com.lrj.authz.governance.cli.'
        with (run / (name + '-' + secrets.token_hex(4) + '.log')).open('w') as log:
            result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=' + package + name, '-cp', str(admin),
                                     'org.springframework.boot.loader.launch.PropertiesLauncher', str(configuration)], stdout=log, stderr=subprocess.STDOUT, timeout=40)
        return result.returncode

    def project():
        # 每次只处理本任务固定分区；持久投影可能需多个批次，未ready不能视为允许。
        for kind in ['POLICY', 'DIRECTORY']:
            for _ in range(12):
                code = cli('ReliableProjectionCli', run / (kind + '.properties'))
                if code == 0: break
                if code != 2: raise RuntimeError('WMS可靠投影失败')
                time.sleep(.25)
            else: raise RuntimeError('WMS可靠投影未就绪')

    def check(name, port, path, token=None, body=None, status=200, key=None):
        headers = [('Authorization', 'Bearer ' + token)] if token else []
        if key: headers.append(('Idempotency-Key', key))
        actual, result = h.request(port, path, headers, None if body is None else json.dumps(body).encode())
        if actual != status: raise RuntimeError(name + ': unexpected HTTP status ' + str(actual))
        checks.append({'check': name, 'result': 'PASS', 'status': actual}); return result

    database = p.properties(p.read_private(state_root / 'bootstrap/database.properties'))
    graph = p.properties(p.read_private(Path('.local/governance/p3/graph/graph.properties')))
    configuration = p.properties(p.read_private(Path('.local/docker-governance/runtime/admin.properties')))
    # 管理身份沿用既有发行方和管理客户端，不把业务audience当管理权限。
    p.write_properties(run / 'admin.properties', {**database, **graph, **{key: value for key, value in configuration.items()
        if key in {'issuer', 'jwks.uri', 'audience', 'client.id', 'client.secret', 'version-probe.client.id', 'version-probe.client.secret'}}})
    for kind in ['POLICY', 'DIRECTORY']:
        p.write_properties(run / (kind + '.properties'), {**database, **graph, 'access.tenant': state['tenant'],
            'access.application': 'wms', 'access.environment': 'local', 'projection.kind': kind})
    fixture = json.loads(p.read_private(Path('.local/governance/p2/identity/casdoor.json')))
    manager = i.pkce(state, fixture['users']['internal'], client=configuration['client.id'],
                     organization=fixture['organization'], redirect='http://localhost:5273/callback')['access_token']
    tokens = {name: i.pkce(state, user) for name, user in state['users'].items()}
    p.private(run / 'tokens.json', json.dumps(tokens))
    part = {'tenant_id': state['tenant'], 'application_id': 'wms', 'environment': 'local'}
    grants = []; container = None
    try:
        h.start(admin, 18546, run / 'admin.log', config=run / 'admin.properties', access=True)
        check('enable explicit WMS strict partition', 18546, '/api/governance/v1/access/enable-strict', manager,
              {**part, 'command_id': uid()}, 202)
        operations = json.loads((wms / 'docs/iam/operations.json').read_text())['operations']
        phases = {'W04', *({'W05'} if args.phase in {'W05', 'W06'} else set()), *({'W06'} if args.phase == 'W06' else set())}
        for resource in ['wms_warehouse', 'wms_enterprise']:
            for write in [False, True]:
                capabilities = sorted({operation['capability'] for operation in operations if operation['resource_type'] == resource
                    and operation['slice'] in phases and (operation['method'] != 'GET') == write})
                if not capabilities: continue
                role = check('create immutable ' + resource + (' writer' if write else ' reader'), 18546,
                    '/api/governance/v1/access/roles', manager, {**part, 'command_id': uid(), 'role_code': 'wms-' + args.phase.lower()
                    + ('-write-' if write else '-read-') + resource.replace('wms_', ''), 'role_version': 1, 'capabilities': capabilities})
                for user in (['operator'] if write else ['reader-a', 'reader-b', 'operator']):
                    warehouses = ['WH-A', 'WH-B'] if user == 'operator' and not write else ['WH-B' if user == 'reader-b' else 'WH-A']
                    grant = check('grant explicit ' + user + ' ' + resource, 18546, '/api/governance/v1/access/scoped-grants', manager,
                        {**part, 'command_id': uid(), 'member_id': p.uid('membership:' + user), 'member_generation': 1,
                         'role_id': role['id'], 'scope_rule': {'version': 1, 'resource_type': resource, 'clauses': [{'kind':
                          'SPECIFIED_RESOURCES' if resource == 'wms_warehouse' else 'TENANT_ALL', 'values': warehouses if resource == 'wms_warehouse' else [],
                          'include_root': False}]}, 'source_id': 'wms-runtime-' + run.name + '-' + user + '-' + role['id'],
                         'valid_from': stamp(-5), 'valid_to': stamp(3600)}, 202)
                    grants.append({'user': user, 'write': write, 'resource': resource, 'id': grant['id']})
                    # 启动后续服务可能失败，授权回执必须在每次提交后立即保存，不能等所有服务ready。
                    p.private(run / 'grants.json', json.dumps(grants))
        project()
        h.start(server, 18545, run / 'server.log', config=state_root / 'runtime/server.properties', access=True, scope=True, navigation=True)
        # 现有种子保护拒绝共享dev_infra；专属测试实例承载真实SQL且不触及现有业务卷。
        container = 'wms-auth-it-' + secrets.token_hex(6); mysql_password = secrets.token_urlsafe(32)
        p.private(run / 'mysql.env', 'MYSQL_ROOT_PASSWORD=' + mysql_password + '\n')
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0)); mysql_port = probe.getsockname()[1]
        subprocess.run(['docker', 'run', '-d', '--name', container, '--label', 'com.lrj.task=WMS-AUTH-20261004',
            '--env-file', str(run / 'mysql.env'), '-p', '127.0.0.1:' + str(mysql_port) + ':3306', 'mysql:8.4.11'], check=True, stdout=subprocess.DEVNULL)

        def mysql(statement):
            result = subprocess.run(['docker', 'exec', '-i', container, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'],
                input=statement, text=True, capture_output=True, timeout=15)
            if result.returncode: raise RuntimeError('专属WMS测试数据库不可用')
            return result.stdout.strip()

        for _ in range(120):
            try: mysql('SELECT 1'); break
            except RuntimeError: time.sleep(.5)
        else: raise RuntimeError('专属WMS数据库启动超时')
        p.private(run / 'database.json', json.dumps({'container': container, 'port': mysql_port, 'password': mysql_password}))
        services = [('inventory', 18183, 'com.lrj.wms.inventory.masterdata.SeedLocal'), ('inbound', 18181, 'com.lrj.wms.inbound.seed.SeedInbound'),
                    ('outbound', 18182, 'com.lrj.wms.outbound.seed.SeedOutbound'), ('fulfillment', 18185, 'com.lrj.wms.fulfillment.seed.SeedFulfillment'),
                    ]
        environments = {}
        for name, port, seed in services:
            db = 'wms_' + name.replace('-', '_')
            mysql('CREATE DATABASE ' + db + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
            jar = wms / ('wms-' + name) / 'target' / ('wms-' + name + '-0.1.0-SNAPSHOT.jar')
            url = 'jdbc:mysql://127.0.0.1:' + str(mysql_port) + '/' + db + '?allowPublicKeyRetrieval=true&useSSL=false'
            env_name = 'SERIAL' if name == 'serial-registry' else name.upper().replace('-', '_')
            env = {**os.environ, 'WMS_' + env_name + '_JDBC_URL': url,
                   'WMS_' + env_name + '_DB_USER': 'root', 'WMS_' + env_name + '_DB_PASSWORD': mysql_password,
                   'WMS_HTTP_PORT': str(port), 'WMS_OIDC_ISSUER': p.ISSUER, 'WMS_OIDC_CLIENT_ID': p.CLIENT,
                   'WMS_IAM_ENABLED': 'true', 'WMS_IAM_CONFIGURATION': str(state_root / 'runtime' / (name + '.properties'))}
            environments[name] = {key: value for key, value in env.items() if key.startswith('WMS_')}
            if seed:
                with (run / (name + '-seed.log')).open('w') as log:
                    result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=' + seed, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                        env={**env, 'WMS_SEED_JDBC_URL': url, 'WMS_SEED_DB_USER': 'root', 'WMS_SEED_DB_PASSWORD': mysql_password,
                             'WMS_SEED_WAREHOUSES': 'WH-A,WH-B'}, stdout=log, stderr=subprocess.STDOUT, timeout=60)
                if result.returncode: raise RuntimeError('WMS种子失败：' + name)
            with (run / (name + '.log')).open('w') as log:
                process = subprocess.Popen(['java', '-Xmx384m', '-jar', str(jar), '--server.address=127.0.0.1', '--seata.enabled=false'],
                                           cwd=wms, env=env, stdout=log, stderr=subprocess.STDOUT)
            h.PROCESSES.append(process)
            for _ in range(180):
                if process.poll() is not None: raise RuntimeError('WMS服务启动失败：' + name)
                try:
                    if h.request(port, '/actuator/health')[0] == 200: break
                except (OSError, ValueError): pass
                time.sleep(.25)
            else: raise RuntimeError('WMS服务启动超时：' + name)
        p.private(run / 'environments.json', json.dumps(environments))
        p.private(run / 'grants.json', json.dumps(grants))
        p.private(run / 'manager-token.json', json.dumps({'access_token': manager}))
        ta, tb, operator = (tokens[name]['access_token'] for name in ['reader-a', 'reader-b', 'operator'])
        wha = check('reader A lists only warehouse A from real Owner SQL', 18183, '/api/wms/v1/warehouses', ta)
        if 'WH-A' not in json.dumps(wha) or 'WH-B' in json.dumps(wha): raise RuntimeError('仓列表越权')
        whb = check('reader B lists only warehouse B from real Owner SQL', 18183, '/api/wms/v1/warehouses', tb)
        if 'WH-B' not in json.dumps(whb) or 'WH-A' in json.dumps(whb): raise RuntimeError('仓列表越权')
        check('anonymous rejected', 18183, '/api/wms/v1/warehouses', status=401)
        check('ID token rejected', 18183, '/api/wms/v1/warehouses', tokens['reader-a']['id_token'], status=401)
        check('member without grant rejected', 18183, '/api/wms/v1/warehouses', tokens['denied']['access_token'], status=403)
        check('cross warehouse locations rejected', 18183, '/api/wms/v1/warehouses/WH-B/locations', ta, status=403)
        check('warehouse A real locations', 18183, '/api/wms/v1/warehouses/WH-A/locations', ta)
        check('stock filtered real SQL warehouse A', 18183, '/api/wms/v1/inventory?warehouseIds=WH-A', ta)
        view = check('central本人 navigation and warehouse use real graph', 18183, '/api/wms/v1/me/access?warehouseId=WH-A', ta)
        if view.get('mode') != 'CENTRAL' or view.get('warehouseIds') != ['WH-A'] or not view.get('menus'): raise RuntimeError('本人响应不一致')
        denied = check('ungranted member shows no accessible menus', 18183, '/api/wms/v1/me/access?warehouseId=WH-A', tokens['denied']['access_token'])
        if denied.get('menus') or denied.get('capabilities') or denied.get('warehouseIds'): raise RuntimeError('无授权身份菜单泄漏')
        p.private(run / 'fixture.json', json.dumps({'phase': args.phase, 'tenant': state['tenant'], 'grants': grants,
            'manager_token': manager, 'users': state['users'], 'authority': p.ISSUER, 'client': p.CLIENT, 'container': container}))
        p.private(run / 'result.json', json.dumps({'result': 'PASS', 'checks': checks, 'real_mysql': True, 'real_pkce': True,
                                                'runtime_deployed': False, 'production_deployed': False}, ensure_ascii=False, indent=2))
        print('PASS: %d真实身份/授权图/Owner SQL检查；专属验收进程已准备。' % len(checks), flush=True)
        if args.serve:
            deadline = time.monotonic() + 7200
            while time.monotonic() < deadline and not (run / 'stop').exists(): time.sleep(.5)
    finally:
        for process in reversed(h.PROCESSES): h.stop(process)
        if container:
            subprocess.run(['docker', 'stop', container], check=False, stdout=subprocess.DEVNULL, timeout=30)


if __name__ == '__main__':
    main()
