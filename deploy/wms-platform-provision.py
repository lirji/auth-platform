#!/usr/bin/env python3
"""幂等开通 WMS 身份；仅修改专属应用，凭据保存在调用方指定的 0600 文件。"""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.parse
import urllib.request

BASE = os.environ.get('CASDOOR_URL', 'http://localhost:8000').rstrip('/')
ORG = 'wms-platform'
APP = 'wms-platform'
CLIENT = 'wms-platform'
ENTERPRISE = 'ENT-DEMO'
UI_PORT = os.environ.get('WMS_UI_PORT', '18180')
USERS = (
    ('wms-ops', 'WMS 演示运营', 'WH-A,WH-B'),
    ('wms-wh-a', 'WMS 仅仓 A', 'WH-A'),
    ('wms-wh-b', 'WMS 仅仓 B', 'WH-B'),
    ('wms-denied', 'WMS 无仓验收', ''),
)
# 与 OpenAPI oidc / 公开作业 TSV 对齐；permission name 会进 JWT scope。
SCOPES = (
    'adjustment.apply', 'adjustment.approve', 'adjustment.create', 'adjustment.read',
    'count.create', 'count.freeze', 'count.read', 'count.record',
    'fulfillment.cancel', 'fulfillment.create', 'fulfillment.execute', 'fulfillment.read',
    'inbound.create', 'inbound.putaway', 'inbound.read', 'inbound.receive',
    'inventory.command', 'inventory.permit', 'inventory.read', 'inventory.tcc.try',
    'job.read', 'job.retry',
    'masterdata.read', 'masterdata.write',
    'messaging.read', 'messaging.recover',
    'operation.read',
    'outbound.pack', 'outbound.pick', 'outbound.read', 'outbound.ship',
    'quality.inspect',
    'recon.evidence', 'recon.export', 'recon.read', 'recon.remediate',
    'serial.registry.read', 'serial.registry.write',
    'stock.audit', 'stock.hold', 'stock.move', 'stock.read', 'stock.releaseHold',
    'task.claim', 'task.read',
    'transfer.authorizeReceipt', 'transfer.create', 'transfer.read', 'transfer.receive',
)


def claim_list(value):
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str) and value.strip():
        return value.replace(',', ' ').split()
    return []


def decode_claims(token):
    payload = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))


def builtin_app():
    """按应用名读取 built-in client，避免 Casdoor 重建后旧 client_id 失效。"""
    container = os.environ.get('AUTHZ_POSTGRES_CONTAINER', 'authz-postgres')
    client_id = os.environ.get('BUILTIN_CID')
    if not client_id:
        client_id = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', 'authz', '-d', 'spicedb',
            '-Atc', "select client_id from application where name='app-built-in' and owner='admin'"], text=True).strip()
    secret = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', 'authz', '-d', 'spicedb',
        '-Atc', f"select client_secret from application where client_id='{client_id}'"], text=True).strip()
    if not client_id or not secret:
        raise RuntimeError('无法读取 Casdoor built-in 应用凭据')
    return client_id, secret


def request(path, data=None, token=None, form=False):
    """错误只报告端点，避免服务错误回显凭据。"""
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
    with urllib.request.urlopen(urllib.request.Request(BASE + '/api/' + path, data, headers), timeout=30) as response:
        result = json.load(response)
    if result.get('status') == 'error' or result.get('error'):
        raise RuntimeError('Casdoor操作失败: ' + path.split('?')[0])
    return result


def main():
    """首次随机生成专用账号口令；重复执行不重置已有用户密码。"""
    credential_path = Path(os.environ['WMS_IAM_CREDENTIALS'])
    created = not credential_path.exists()
    if created:
        credentials = {
            'issuer': BASE,
            'client_id': CLIENT,
            'username': 'wms-ops',
            'password': secrets.token_urlsafe(24),
            'client_secret': secrets.token_urlsafe(32),
            'fixtures': {},
        }
        for name, _label, _warehouses in USERS[1:]:
            credentials['fixtures'][name] = {'username': name, 'password': secrets.token_urlsafe(24)}
        credential_path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(credential_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(credentials, stream)
    else:
        credentials = json.loads(credential_path.read_text())
    builtin_cid, secret = builtin_app()
    admin = request('login/oauth/access_token', {'grant_type': 'password',
        'username': os.environ.get('CASDOOR_ADMIN', 'admin'),
        'password': os.environ.get('CASDOOR_ADMIN_PW', '123'),
        'client_id': builtin_cid, 'client_secret': secret, 'scope': 'openid'}, form=True)['access_token']

    def get(kind, owner, name):
        return request('get-' + kind + '?' + urllib.parse.urlencode({'id': owner + '/' + name}), token=admin).get('data')

    def ensure(kind, owner, name, fields):
        current = get(kind, owner, name)
        payload = dict(current or {}, owner=owner, name=name)
        payload.update(fields)
        endpoint = ('update-' + kind + '?' + urllib.parse.urlencode({'id': owner + '/' + name})) if current else 'add-' + kind
        request(endpoint, payload, admin)
        return payload

    if not get('organization', 'admin', ORG):
        ensure('organization', 'admin', ORG, {'displayName': 'WMS', 'passwordType': 'bcrypt',
            'passwordOptions': ['AtLeast6'], 'defaultApplication': APP,
            'accountItems': [{'name': 'Password', 'visible': True, 'viewRule': 'Self', 'modifyRule': 'Self'}]})
    organization = get('organization', 'admin', ORG)
    items = [item for item in organization.get('accountItems', []) if item['name'] != 'Properties']
    items.append({'name': 'Properties', 'visible': True, 'viewRule': 'Self', 'modifyRule': 'Admin'})
    ensure('organization', 'admin', ORG, {'defaultApplication': APP, 'accountItems': items})
    existing = get('application', 'admin', APP)
    if existing and (existing.get('organization') != ORG or existing.get('clientId') != CLIENT):
        raise RuntimeError('同名应用配置冲突，请人工核对')
    redirects = []
    extra_ports = {UI_PORT, '18180', '4181'}
    for host in ['localhost', '127.0.0.1']:
        for port in sorted(extra_ports, key=int):
            redirects.append(f'http://{host}:{port}/callback')
            redirects.append(f'http://{host}:{port}/login')
            redirects.append(f'http://{host}:{port}')
        redirects.append(f'http://{host}:18190/auth/callback')
    ensure('application', 'admin', APP, {'displayName': 'WMS 仓储管理台', 'organization': ORG,
        'clientId': CLIENT, 'clientSecret': existing['clientSecret'] if existing else credentials['client_secret'],
        'isShared': False, 'cert': 'cert-built-in', 'enablePassword': True, 'enableSignUp': False,
        'enableSigninSession': True, 'grantTypes': ['authorization_code', 'refresh_token', 'password'],
        'redirectUris': sorted(set((existing or {}).get('redirectUris', []) + redirects)),
        'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}],
        'providers': [], 'expireInHours': 1, 'refreshExpireInHours': 8,
        'tokenFormat': 'JWT-Custom', 'tokenSigningMethod': 'RS256',
        'tokenFields': ['Owner', 'Name', 'DisplayName', 'Properties.enterprise_id', 'Properties.warehouses'],
        'tokenAttributes': [{'name': 'scope', 'category': 'Existing Field', 'value': 'permissionNames', 'type': 'Array'}]})
    passwords = {USERS[0][0]: credentials['password']}
    fixtures = credentials.setdefault('fixtures', {})
    for name, _label, _warehouses in USERS[1:]:
        fixtures.setdefault(name, {'username': name, 'password': secrets.token_urlsafe(24)})
        passwords[name] = fixtures[name]['password']
    credentials['issuer'] = BASE
    credentials['client_id'] = CLIENT
    credentials['username'] = 'wms-ops'
    os.chmod(credential_path, 0o600)
    credential_path.write_text(json.dumps(credentials))
    for name, label, warehouses in USERS:
        current = get('user', ORG, name)
        expected = {'enterprise_id': ENTERPRISE, 'warehouses': warehouses}
        if current and current.get('properties', {}) != expected:
            if current.get('properties', {}).get('enterprise_id') not in (None, ENTERPRISE):
                raise RuntimeError('现有用户企业不匹配，拒绝覆盖: ' + name)
        if not current:
            ensure('user', ORG, name, {'displayName': label, 'type': 'normal-user',
                'password': passwords[name], 'signupApplication': APP, 'isAdmin': False,
                'properties': expected})
        else:
            ensure('user', ORG, name, {'displayName': label, 'signupApplication': APP,
                'properties': expected})
        if created or not current:
            try:
                request('set-password', {'userOwner': ORG, 'userName': name, 'oldPassword': '',
                    'newPassword': passwords[name]}, admin, form=True)
            except RuntimeError:
                # add-user 已带密码时 Casdoor 可能报“与当前相同”；不阻断开通。
                pass
    operator_users = [ORG + '/wms-ops', ORG + '/wms-wh-a', ORG + '/wms-wh-b']
    ensure('role', ORG, 'wms-operator', {'displayName': 'WMS 作业员', 'isEnabled': True,
        'users': operator_users})
    for scope in SCOPES:
        ensure('permission', ORG, scope, {
            'displayName': scope, 'isEnabled': True, 'state': 'Approved',
            'model': 'built-in/user-model-built-in', 'resourceType': 'Custom',
            'resources': [ORG], 'actions': ['Read'], 'effect': 'Allow',
            'roles': [ORG + '/wms-operator'], 'users': [],
        })
    application = get('application', 'admin', APP)
    token = request('login/oauth/access_token', {'grant_type': 'password', 'client_id': CLIENT,
        'client_secret': application['clientSecret'], 'username': credentials['username'],
        'password': credentials['password'], 'scope': 'openid profile'}, form=True)['access_token']
    claims = decode_claims(token)
    scopes = claim_list(claims.get('scope'))
    safe = {key: claims.get(key) for key in ['iss', 'aud', 'enterprise_id', 'warehouses']}
    safe['scope_count'] = len(scopes)
    safe['scope'] = scopes
    print(json.dumps(safe, ensure_ascii=False))
    assert claims['aud'] == [CLIENT] and claims.get('enterprise_id') == ENTERPRISE
    assert claims.get('warehouses') == 'WH-A,WH-B'
    for required in ('inbound.create', 'inbound.receive', 'outbound.pick', 'fulfillment.create',
                     'transfer.create', 'count.create', 'job.read', 'recon.read', 'masterdata.read',
                     'inventory.read', 'messaging.read', 'messaging.recover',
                     'serial.registry.read', 'serial.registry.write', 'recon.evidence'):
        assert required in scopes, required
    denied = request('login/oauth/access_token', {'grant_type': 'password', 'client_id': CLIENT,
        'client_secret': application['clientSecret'], 'username': 'wms-denied',
        'password': passwords['wms-denied'], 'scope': 'openid profile'}, form=True)['access_token']
    denied_scopes = claim_list(decode_claims(denied).get('scope'))
    assert 'inbound.create' not in denied_scopes
    print('Casdoor WMS 身份开通完成；issuer=' + BASE + ' client_id=' + CLIENT)


if __name__ == '__main__':
    main()
