#!/usr/bin/env python3
"""受控建立 local-wms 身份/目录；只使用既有治理 CLI，不自动创建业务 Grant。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.parse
import urllib.request
import uuid

ISSUER = 'http://localhost:18090'
ORGANIZATION = 'local-wms'
ENTERPRISE = 'ENT-DEMO'
APPLICATION = 'wms'
CLIENT = 'wms-central'
SERVICES = ('inventory', 'inbound', 'outbound', 'fulfillment', 'serial-registry')
USERS = ('reader-a', 'reader-b', 'operator', 'denied')
REDIRECTS = ('http://localhost:18180/callback', 'http://127.0.0.1:18180/callback')


def uid(label):
    """稳定归属和命令键保证恢复时不会新增第二套组织或恢复撤销的成员。"""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'wms-central:local-wms:' + label))


def read_private(path):
    """只消费明确的既有私密文件，缺失不能猜测或重新生成旧凭据。"""
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o777 != 0o600:
        raise ValueError('需要已有0600私密配置')
    if path.stat().st_size > 65536:
        raise ValueError('私密配置超限')
    return path.read_text()


def properties(text):
    """读取本项目生成的有限properties形式；不支持隐式配置覆盖。"""
    values = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        if '=' not in line:
            raise ValueError('配置字段无效')
        key, value = line.split('=', 1)
        if key in values:
            raise ValueError('配置字段重复')
        values[key] = value
    return values


def private(path, text):
    """原子更新本工具拥有的文件；拒绝链接，秘密不进stdout或命令行。"""
    if path.is_symlink():
        raise ValueError('拒绝链接配置')
    temporary = path.with_name(path.name + '.writing-' + secrets.token_hex(4))
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
        output.write(text)
    os.replace(temporary, path)


def write_properties(path, values):
    if any('\n' in str(v) or '\r' in str(v) for v in values.values()):
        raise ValueError('配置值无效')
    private(path, ''.join(k + '=' + str(v) + '\n' for k, v in values.items()))


def new_state():
    """新业务身份只属于local-wms，不复制旧业务用户、Token权限或仓范围。"""
    return {'organization': ORGANIZATION, 'enterprise': ENTERPRISE, 'application': APPLICATION,
            'tenant': uid('tenant'), 'client_secret': secrets.token_urlsafe(40),
            'users': {name: {'name': 'wms-' + name, 'id': uid('idp-user:' + name),
                             'password': secrets.token_urlsafe(24)} for name in USERS},
            'services': {name: secrets.token_urlsafe(48) for name in SERVICES}}


def validate_state(state):
    """检查点换组织、企业、主体或服务集合必须失败，不能覆盖原绑定。"""
    if (state.get('organization'), state.get('enterprise'), state.get('application'), state.get('tenant')) != (
            ORGANIZATION, ENTERPRISE, APPLICATION, uid('tenant')):
        raise ValueError('组织/企业绑定冲突')
    if set(state.get('users', {})) != set(USERS) or set(state.get('services', {})) != set(SERVICES):
        raise ValueError('明确身份集合冲突')
    for name, user in state['users'].items():
        if user.get('id') != uid('idp-user:' + name) or user.get('name') != 'wms-' + name or not user.get('password'):
            raise ValueError('主体绑定冲突')
    if len(set(state['services'].values())) != len(SERVICES) or any(len(v) < 32 for v in state['services'].values()):
        raise ValueError('服务凭据绑定冲突')


def request(endpoint, authorization, body=None):
    """只操作兼容本机发行方；失败仅报告固定端点，不回显响应和凭据。"""
    headers = {'Authorization': authorization, 'Content-Type': 'application/json'}
    data = None if body is None else json.dumps(body).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(ISSUER + '/api/' + endpoint, data, headers), timeout=10) as response:
            raw = response.read(262145)
            if len(raw) > 262144:
                raise ValueError('发行方响应超限')
            result = json.loads(raw)
    except (OSError, ValueError):
        raise RuntimeError('发行方操作失败：' + endpoint.split('?')[0]) from None
    if result.get('status') != 'ok' or result.get('error'):
        raise RuntimeError('发行方操作失败：' + endpoint.split('?')[0])
    return result.get('data')


def ensure_identity(state, management):
    """只新增固定命名空间；存在的配置不一致时拒绝，绝不重置密码或扩权。"""
    authorization = 'Basic ' + base64.b64encode((management['client_id'] + ':' + management['client_secret']).encode()).decode()
    def create(kind, owner, name, fields, identity_fields):
        current = request('get-' + kind + '?' + urllib.parse.urlencode({'id': owner + '/' + name}), authorization)
        if current is not None:
            if any(current.get(key) != fields[key] for key in identity_fields):
                raise ValueError('已有WMS发行方配置冲突：' + kind)
            return
        request('add-' + kind, authorization, dict(fields, owner=owner, name=name))
    create('organization', 'admin', ORGANIZATION,
           {'displayName': 'WMS 本地组织', 'passwordType': 'bcrypt', 'passwordOptions': ['AtLeast8'],
            'defaultApplication': CLIENT, 'accountItems': []}, ('passwordType', 'defaultApplication'))
    create('application', 'admin', CLIENT,
           {'displayName': 'WMS 仓储作业台', 'organization': ORGANIZATION, 'clientId': CLIENT,
            'clientSecret': state['client_secret'], 'cert': 'cert-built-in', 'isShared': False,
            'enablePassword': True, 'enableSignUp': False, 'enableSigninSession': True,
            'grantTypes': ['authorization_code', 'refresh_token'], 'redirectUris': list(REDIRECTS),
            'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}],
            'providers': [], 'expireInHours': 1, 'refreshExpireInHours': 2,
            'tokenFormat': 'JWT-Custom', 'tokenSigningMethod': 'RS256',
            'tokenFields': ['Owner', 'Name', 'DisplayName', 'Properties.enterprise_id']},
           ('organization', 'clientId', 'clientSecret', 'grantTypes', 'redirectUris', 'tokenFormat', 'tokenFields'))
    for user in state['users'].values():
        create('user', ORGANIZATION, user['name'],
               {'id': user['id'], 'displayName': user['name'], 'type': 'normal-user', 'password': user['password'],
                'isAdmin': False, 'signupApplication': CLIENT, 'properties': {'enterprise_id': ENTERPRISE}},
               ('id', 'isAdmin', 'signupApplication', 'properties'))


def prepare_configuration(state, database, graph, management):
    """独立中央调用方只绑定wms/local，不能请求选择app或借用旧管理凭据。"""
    callers = ','.join('wms-' + service for service in SERVICES)
    values = {**database, **graph, 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key'],
              'service.count': len(SERVICES), 'access.check.callers': callers, 'scope.check.callers': callers,
              'scope.owner.wms': 'wms_warehouse,wms_enterprise', 'navigation.callers': callers}
    for index, service in enumerate(SERVICES, 1):
        prefix = 'service.' + str(index) + '.'
        values.update({prefix + 'id': 'wms-' + service, prefix + 'application-id': APPLICATION,
                       prefix + 'environment': 'local', prefix + 'operation': 'context.resolve',
                       prefix + 'credential-sha256': hashlib.sha256(state['services'][service].encode()).hexdigest()})
        values.update({prefix + 'user.' + key: value for key, value in {
            'issuer': ISSUER, 'jwks.uri': ISSUER + '/.well-known/jwks', 'audience': CLIENT,
            'client.id': CLIENT, 'client.secret': state['client_secret'],
            'version-probe.client.id': management['client_id'],
            'version-probe.client.secret': management['client_secret']}.items()})
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--governance-directory', type=Path, default=Path('.local/docker-governance'))
    parser.add_argument('--identity-directory', type=Path, default=Path('.local/governance'))
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    root = args.state_directory.resolve(); root.mkdir(parents=True, exist_ok=True, mode=0o700); root.chmod(0o700)
    seed = root / 'bootstrap'; seed.mkdir(exist_ok=True, mode=0o700)
    runtime = root / 'runtime'; runtime.mkdir(exist_ok=True, mode=0o700)
    state_path = root / 'state.json'
    if state_path.exists():
        state = json.loads(read_private(state_path))
    else:
        state = new_state(); private(state_path, json.dumps(state, ensure_ascii=False, indent=2))
    validate_state(state)
    governance = args.governance_directory.resolve()
    database = properties(read_private(governance / 'bootstrap/database.properties'))
    owner = properties(read_private(governance / 'bootstrap/catalog.properties'))
    management = json.loads(read_private(args.identity_directory / 'casdoor-isolated/management-client.json'))
    graph = properties(read_private(args.identity_directory / 'p3/graph/graph.properties'))
    manifest = json.loads(args.manifest.read_text())
    if manifest.get('application') != APPLICATION or manifest.get('manifest_version') != 1:
        raise ValueError('需要已验收WMS源目录版本1')
    ensure_identity(state, management)
    jar = Path('auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar').resolve()
    def cli(name, *inputs):
        with os.fdopen(os.open(root / (name + '.log'), os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600), 'w') as output:
            result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.governance.cli.' + name,
                                     '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                                     *map(str, inputs)], stdout=output, stderr=subprocess.STDOUT, timeout=60)
        if result.returncode:
            raise RuntimeError('受控CLI失败：' + name)
    write_properties(seed / 'database.properties', database)
    identities = {'manager': {'principal': owner['catalog.owner-principal'], 'subject': owner['catalog.owner-subject']},
                  **{name: {'principal': uid('principal:' + name), 'subject': user['id']} for name, user in state['users'].items()}}
    for name, identity in identities.items():
        path = seed / (name + '.properties')
        write_properties(path, {'command.id': uid('bootstrap:' + name), 'operator.ref': 'wms-local-setup',
                               'tenant.id': state['tenant'], 'tenant.code': ORGANIZATION,
                               'principal.id': identity['principal'], 'issuer': ISSUER, 'subject': identity['subject'],
                               'membership.id': uid('membership:' + name), 'valid.from': '2020-01-01T00:00:00Z',
                               'source.system': 'wms-local', 'source.tenant.ref': ENTERPRISE, 'source.subject.ref': name})
        cli('GovernanceCli', 'bootstrap', seed / 'database.properties', path)
    catalog = {**database, 'catalog.application': APPLICATION, 'catalog.owner-principal': owner['catalog.owner-principal'],
               'catalog.owner-issuer': ISSUER, 'catalog.owner-subject': owner['catalog.owner-subject'],
               'catalog.entry-origin': 'http://localhost:18180', 'catalog.operator': 'wms-local-setup',
               'catalog.command': uid('catalog-v1')}
    write_properties(seed / 'catalog.properties', catalog)
    cli('CatalogCli', 'register', seed / 'catalog.properties', 'configured')
    cli('CatalogCli', 'publish', seed / 'catalog.properties', args.manifest.resolve())
    access = {**database, 'access.tenant': state['tenant'], 'access.application': APPLICATION,
              'access.environment': 'local', 'access.manager': uid('membership:manager'), 'access.generation': 1,
              'access.capabilities': ','.join(cap['code'] for cap in manifest['capabilities']),
              'access.max-duration-seconds': 86400, 'access.operator': 'wms-local-setup', 'access.command': uid('delegation')}
    write_properties(seed / 'access.properties', access); cli('AccessBootstrapCli', seed / 'access.properties')
    write_properties(runtime / 'server.properties', prepare_configuration(state, database, graph, management))
    for service in SERVICES:
        write_properties(runtime / (service + '.properties'), {
            'central.base-url': 'http://127.0.0.1:18545', 'central.service-credential': state['services'][service],
            'central.tenant-id': state['tenant'], 'central.enterprise-id': ENTERPRISE, 'central.application': APPLICATION,
            'central.environment': 'local', 'central.organization': ORGANIZATION, 'central.issuer': ISSUER,
            'central.client-id': CLIENT, 'central.connect-timeout-ms': 1000, 'central.timeout-ms': 5000,
            'central.maximum-concurrent': 8})
    private(root / 'context.json', json.dumps({'tenant': state['tenant'], 'organization': ORGANIZATION,
        'enterprise': ENTERPRISE, 'application': APPLICATION, 'environment': 'local',
        'members': {name: uid('membership:' + name) for name in identities},
        'owner_principal': owner['catalog.owner-principal'], 'owner_subject': owner['catalog.owner-subject']}, indent=2))
    private(root / 'ACCESS.md', '# WMS 本地中央身份\n\n组织 local-wms，企业 ENT-DEMO；入口 http://localhost:18180/login\n\n' +
        '\n'.join('- ' + u['name'] + '：' + u['password'] for u in state['users'].values()) +
        '\n\n尚未创建任何业务 Grant；业务运行开关尚未切换。管理使用原 Auth 管理者，选择 local-wms / wms / local。\n')
    print('PASS: local-wms → ENT-DEMO，%d个明确成员、%d能力/%d菜单和%d个独立服务配置已准备；未创建业务Grant，运行未切换。' % (
        len(identities), len(manifest['capabilities']), len(manifest['menus']), len(SERVICES)))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as failure:
        raise SystemExit('FAIL: WMS受控引导未完成；检查私密证据和配置类型，未重置既有环境。') from None
