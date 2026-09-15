#!/usr/bin/env python3
"""幂等开通 WMS Casdoor 身份（层①+②）。不写 SpiceDB，凭据只写入调用方指定的 0600 文件。"""
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


def builtin_app():
    """按应用名读取 built-in client，避免 Casdoor 重建后旧 client_id 失效。"""
    env_cid = os.environ.get('CASDOOR_BUILTIN_CLIENT_ID', '').strip()
    env_secret = os.environ.get('CASDOOR_BUILTIN_CLIENT_SECRET', '').strip()
    container = os.environ.get('AUTHZ_POSTGRES_CONTAINER', 'authz-postgres')
    client_id = env_cid or os.environ.get('BUILTIN_CID', '').strip()
    if not client_id:
        client_id = subprocess.check_output(
            ['docker', 'exec', container, 'psql', '-U', 'authz', '-d', 'spicedb', '-Atc',
             "select client_id from application where name='app-built-in' and owner='admin'"],
            text=True).strip()
    secret = env_secret or subprocess.check_output(
        ['docker', 'exec', container, 'psql', '-U', 'authz', '-d', 'spicedb', '-Atc',
         f"select client_secret from application where client_id='{client_id}'"],
        text=True).strip()
    if not client_id or not secret:
        raise RuntimeError('无法读取 Casdoor built-in 应用凭据')
    return client_id, secret


def main():
    credential_path = Path(os.environ['WMS_IAM_CREDENTIALS'])
    if credential_path.exists():
        credentials = json.loads(credential_path.read_text())
    else:
        credentials = {
            'client_id': CLIENT,
            'client_secret': secrets.token_urlsafe(32),
            'users': {
                'wms-wh-a': {'username': 'wms-wh-a', 'password': secrets.token_urlsafe(24), 'warehouses': 'WH-A'},
                'wms-wh-b': {'username': 'wms-wh-b', 'password': secrets.token_urlsafe(24), 'warehouses': 'WH-B'},
                'wms-ops': {'username': 'wms-ops', 'password': secrets.token_urlsafe(24), 'warehouses': 'WH-A,WH-B'},
                'wms-denied': {'username': 'wms-denied', 'password': secrets.token_urlsafe(24), 'warehouses': ''},
            },
        }
        credential_path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(credential_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(credentials, stream)
    builtin_cid, secret = builtin_app()
    admin = request('login/oauth/access_token', {
        'grant_type': 'password',
        'username': os.environ.get('CASDOOR_ADMIN', 'admin'),
        'password': os.environ.get('CASDOOR_ADMIN_PW', '123'),
        'client_id': builtin_cid,
        'client_secret': secret,
        'scope': 'openid',
    }, form=True)['access_token']

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
        ensure('organization', 'admin', ORG, {
            'displayName': 'WMS',
            'passwordType': 'bcrypt',
            'passwordOptions': ['AtLeast6'],
            'defaultApplication': APP,
            'accountItems': [{'name': 'Password', 'visible': True, 'viewRule': 'Self', 'modifyRule': 'Self'}],
        })
    organization = get('organization', 'admin', ORG)
    items = [item for item in organization.get('accountItems', []) if item['name'] != 'Properties']
    items.append({'name': 'Properties', 'visible': True, 'viewRule': 'Self', 'modifyRule': 'Admin'})
    ensure('organization', 'admin', ORG, {'defaultApplication': APP, 'accountItems': items})
    existing = get('application', 'admin', APP)
    if existing and (existing.get('organization') != ORG or existing.get('clientId') != CLIENT):
        raise RuntimeError('同名应用配置冲突，请人工核对')
    redirects = ['http://127.0.0.1:18190/auth/callback', 'http://localhost:18190/auth/callback']
    extra_ports = {UI_PORT, '18180'}
    for host in ['localhost', '127.0.0.1']:
        for port in sorted(extra_ports, key=int):
            redirects.append(f'http://{host}:{port}/callback')
            redirects.append(f'http://{host}:{port}/login')
    ensure('application', 'admin', APP, {
        'displayName': 'WMS 资源服务器',
        'organization': ORG,
        'clientId': CLIENT,
        'clientSecret': existing['clientSecret'] if existing else credentials['client_secret'],
        'isShared': False,
        'cert': 'cert-built-in',
        'enablePassword': True,
        'enableSignUp': False,
        'enableSigninSession': True,
        'grantTypes': ['authorization_code', 'refresh_token', 'password'],
        'redirectUris': sorted(set((existing or {}).get('redirectUris', []) + redirects)),
        'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}],
        'providers': [],
        'expireInHours': 1,
        'refreshExpireInHours': 8,
        'tokenFormat': 'JWT-Custom',
        'tokenSigningMethod': 'RS256',
        'tokenFields': ['Owner', 'Name', 'DisplayName', 'Properties.enterprise_id', 'Properties.warehouses'],
        'tokenAttributes': [{'name': 'scope', 'category': 'Existing Field', 'value': 'permissionNames', 'type': 'Array'}],
    })
    os.chmod(credential_path, 0o600)
    credential_path.write_text(json.dumps(credentials, ensure_ascii=False, indent=2))
    for key, spec in credentials['users'].items():
        current = get('user', ORG, spec['username'])
        properties = {'enterprise_id': ENTERPRISE, 'warehouses': spec.get('warehouses', '')}
        if current:
            current_props = current.get('properties') or {}
            if current_props.get('enterprise_id') not in (None, '', ENTERPRISE):
                raise RuntimeError('现有用户企业不匹配，拒绝覆盖')
            ensure('user', ORG, spec['username'], {'properties': properties, 'signupApplication': APP})
        else:
            ensure('user', ORG, spec['username'], {
                'displayName': key,
                'type': 'normal-user',
                'password': spec['password'],
                'signupApplication': APP,
                'isAdmin': False,
                'properties': properties,
            })
    operators = [ORG + '/' + credentials['users']['wms-wh-a']['username'],
                 ORG + '/' + credentials['users']['wms-wh-b']['username'],
                 ORG + '/' + credentials['users']['wms-ops']['username']]
    role = get('role', ORG, 'wms-operator') or {}
    ensure('role', ORG, 'wms-operator', {
        'displayName': 'WMS 作业员',
        'isEnabled': True,
        'users': sorted(set(role.get('users', []) + operators)),
    })
    for permission in ('masterdata.read', 'masterdata.write'):
        perm = get('permission', ORG, permission) or {}
        ensure('permission', ORG, permission, {
            'displayName': permission,
            'isEnabled': True,
            'model': 'built-in/user-model-built-in',
            'resourceType': 'Custom',
            'resources': [ORG],
            'actions': ['Read'] if permission.endswith('read') else ['Write'],
            'effect': 'Allow',
            'roles': sorted(set(perm.get('roles', []) + [ORG + '/wms-operator'])),
        })
    application = get('application', 'admin', APP)
    probe = credentials['users']['wms-wh-a']
    token = request('login/oauth/access_token', {
        'grant_type': 'password',
        'client_id': CLIENT,
        'client_secret': application['clientSecret'],
        'username': probe['username'],
        'password': probe['password'],
        'scope': 'openid profile',
    }, form=True)['access_token']
    payload = token.split('.')[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
    assert CLIENT in claims.get('aud', []) or claims.get('aud') == CLIENT
    assert claims.get('enterprise_id') == ENTERPRISE
    assert 'WH-A' in str(claims.get('warehouses', ''))
    assert 'masterdata.read' in claims.get('scope', [])
    print(json.dumps({
        'issuer': BASE,
        'jwk_set_uri': BASE + '/.well-known/jwks',
        'client_id': CLIENT,
        'organization': ORG,
    }, ensure_ascii=False))
    print('WMS Casdoor 身份开通完成；口令只在 WMS_IAM_CREDENTIALS 文件中。')


if __name__ == '__main__':
    main()
