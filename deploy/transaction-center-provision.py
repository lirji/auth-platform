#!/usr/bin/env python3
"""幂等开通交易中心身份；仅修改专属应用，凭据保存在调用方指定的0600文件。"""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.parse
import urllib.request

BASE = os.environ.get('CASDOOR_URL', 'http://localhost:8000').rstrip('/')
ORG = 'transaction-center'
APP = 'transaction-center-console'
CLIENT = 'transaction-center'


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
    # 先落IAM数据库保护，再开通身份；保护失败时不继续发放可被篡改租户的账号。
    subprocess.run(['docker', 'exec', '-i', os.environ.get('AUTHZ_POSTGRES_CONTAINER', 'authz-postgres'),
        'psql', '-U', 'authz', '-d', 'spicedb', '-v', 'ON_ERROR_STOP=1'],
        input=Path(__file__).with_name('transaction-center-tenant-guard.sql').read_text(),
        text=True, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    credential_path = Path(os.environ['TRADE_IAM_CREDENTIALS'])
    if credential_path.exists():
        credentials = json.loads(credential_path.read_text())
    else:
        credentials = {'username': 'trade-demo-admin', 'password': secrets.token_urlsafe(24),
                       'client_secret': secrets.token_urlsafe(32)}
        credential_path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(credential_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(credentials, stream)
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
        ensure('organization', 'admin', ORG, {'displayName': '交易中心', 'passwordType': 'bcrypt',
            'passwordOptions': ['AtLeast6'], 'defaultApplication': APP,
            'accountItems': [{'name': 'Password', 'visible': True, 'viewRule': 'Self', 'modifyRule': 'Self'}]})
    organization = get('organization', 'admin', ORG)
    items = [item for item in organization.get('accountItems', []) if item['name'] != 'Properties']
    # tenant_id决定数据隔离，业务管理员不是IAM管理员，不能自行修改此属性。
    items.append({'name': 'Properties', 'visible': True, 'viewRule': 'Self', 'modifyRule': 'Admin'})
    ensure('organization', 'admin', ORG, {'defaultApplication': APP, 'accountItems': items})
    existing = get('application', 'admin', APP)
    if existing and (existing.get('organization') != ORG or existing.get('clientId') != CLIENT):
        raise RuntimeError('同名应用配置冲突，请人工核对')
    redirects = ['http://' + host + ':4180/auth/callback' for host in ['localhost', '127.0.0.1']]
    ensure('application', 'admin', APP, {'displayName': '交易中心运营台', 'organization': ORG,
        'clientId': CLIENT, 'clientSecret': existing['clientSecret'] if existing else credentials['client_secret'],
        'isShared': False, 'cert': 'cert-built-in', 'enablePassword': True, 'enableSignUp': False,
        'enableSigninSession': True, 'grantTypes': ['authorization_code', 'refresh_token', 'password'],
        'redirectUris': sorted(set((existing or {}).get('redirectUris', []) + redirects)),
        'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}],
        'providers': [], 'expireInHours': 1, 'refreshExpireInHours': 8,
        'tokenFormat': 'JWT-Custom', 'tokenSigningMethod': 'RS256',
        'tokenFields': ['Owner', 'Name', 'DisplayName', 'Properties.tenant_id'],
        'tokenAttributes': [{'name': 'scope', 'category': 'Existing Field', 'value': 'permissionNames', 'type': 'Array'}]})
    user = get('user', ORG, credentials['username'])
    if user and user.get('properties', {}).get('tenant_id') != '900001':
        raise RuntimeError('现有用户租户不匹配，拒绝覆盖')
    if not user:
        ensure('user', ORG, credentials['username'], {'displayName': '交易演示管理员', 'type': 'normal-user',
            'password': credentials['password'], 'signupApplication': APP, 'isAdmin': False,
            'properties': {'tenant_id': '900001'}})
    # 隔离验收身份也持久化，避免把无权限/其他租户场景伪装成前端请求头测试。
    fixture_specs = [('reader', '900001'), ('other-admin', '900002'), ('invalid-tenant', '0')]
    fixtures = credentials.setdefault('fixtures', {})
    for label,tenant in fixture_specs:
        fixtures.setdefault(label, {'username': 'trade-check-' + label, 'password': secrets.token_urlsafe(24)})
    os.chmod(credential_path, 0o600)
    credential_path.write_text(json.dumps(credentials))
    for label,tenant in fixture_specs:
        fixture = fixtures[label]
        current = get('user', ORG, fixture['username'])
        if current and current.get('properties', {}).get('tenant_id') != tenant:
            raise RuntimeError('验收用户租户冲突，拒绝覆盖')
        if not current:
            ensure('user', ORG, fixture['username'], {'displayName': '交易隔离验收 ' + label,
                'type': 'normal-user', 'password': fixture['password'], 'signupApplication': APP,
                'isAdmin': False, 'properties': {'tenant_id': tenant}})
    role = get('role', ORG, 'trade-admin') or {}
    ensure('role', ORG, 'trade-admin', {'displayName': '交易管理员', 'isEnabled': True,
        'users': sorted(set(role.get('users', []) + [ORG + '/' + credentials['username'], ORG + '/' + fixtures['other-admin']['username'], ORG + '/' + fixtures['invalid-tenant']['username']]))})
    perm = get('permission', ORG, 'trade.admin') or {}
    ensure('permission', ORG, 'trade.admin', {'displayName': '交易后台管理', 'isEnabled': True,
        'model': 'built-in/user-model-built-in', 'resourceType': 'Custom', 'resources': [ORG],
        'actions': ['Read'], 'effect': 'Allow',
        'roles': sorted(set(perm.get('roles', []) + [ORG + '/trade-admin']))})
    application = get('application', 'admin', APP)
    token = request('login/oauth/access_token', {'grant_type': 'password', 'client_id': CLIENT,
        'client_secret': application['clientSecret'], 'username': credentials['username'],
        'password': credentials['password'], 'scope': 'openid profile'}, form=True)['access_token']
    payload = token.split('.')[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
    safe = {key: claims.get(key) for key in ['iss', 'aud', 'scope', 'tenant_id']}
    print(json.dumps(safe, ensure_ascii=False))
    assert claims['aud'] == [CLIENT] and claims['tenant_id'] == '900001'
    assert 'trade.admin' in claims.get('scope', [])
    print('Casdoor身份开通完成；签名及API鉴权由交易中心冒烟验证。')


if __name__ == '__main__':
    main()
