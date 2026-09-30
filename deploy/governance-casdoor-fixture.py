#!/usr/bin/env python3
"""仅本机 Casdoor：创建本任务专用身份/客户端，通过授权码 S256 PKCE 取得私密测试凭据。"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request
import uuid
from enum import Enum

BASE = 'http://localhost:8000'
REDIRECT = 'http://127.0.0.1:18290/governance-fixture/callback'
REQUEST_TIMEOUT_SECONDS = 10


class FixturePhase(str, Enum):
    """Token 适配和整体登录升级是分别验收的阶段，不能隐式跳过负例。"""
    TOKENS = 'tokens'
    CODE_FLOW = 'code-flow'

    def __str__(self):
        return self.value


def private_json(path, value):
    """临时文件同样私密，原子替换本工具自己的检查点。"""
    temp = path.with_suffix('.writing')
    descriptor = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
    os.replace(temp, path)


def request(path, data=None, authorization=None, form=False, allow_error=False):
    """凭据只出现在 TLS/本机 HTTP 的 Header/body，失败不回显服务端原文或 URL 参数。"""
    headers = {'Accept-Language': 'en'}
    if authorization:
        headers['Authorization'] = authorization
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE + '/api/' + path, data, headers), timeout=REQUEST_TIMEOUT_SECONDS) as response:
            result = json.load(response)
    except urllib.error.HTTPError as failure:
        if allow_error:
            return {'http_status': failure.code, 'rejected': True}
        raise RuntimeError('fixture request failed: ' + path.split('?')[0]) from None
    if not allow_error and (result.get('status') == 'error' or result.get('error')):
        raise RuntimeError('fixture operation failed: ' + path.split('?')[0])
    return result


def main():
    global BASE
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='.local/governance')
    parser.add_argument('--base', default=BASE, choices=['http://localhost:8000', 'http://localhost:18090', 'http://localhost:18094'])
    parser.add_argument('--management-config')
    parser.add_argument('--phase', type=FixturePhase, choices=list(FixturePhase), default=FixturePhase.CODE_FLOW,
                        help='tokens only verifies P1-02 fixtures; code-flow also checks login upgrade gates')
    args = parser.parse_args()
    BASE = args.base
    directory = Path(args.directory).resolve()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    path = directory / 'casdoor.json'
    if path.exists():
        if path.is_symlink() or path.stat().st_mode & 0o777 != 0o600:
            raise RuntimeError('fixture checkpoint must be private')
        spec = json.loads(path.read_text())
    else:
        suffix = secrets.token_hex(6)
        spec = {'organization': 'gov-p1-' + suffix, 'clients': {}, 'users': {}}
        for purpose in ['management', 'business', 'expired']:
            spec['clients'][purpose] = {'name': 'gov-p1-' + purpose + '-' + suffix,
                                        'secret': secrets.token_urlsafe(36)}
        for kind in ['internal', 'external']:
            spec['users'][kind] = {'name': 'p1-' + kind, 'id': str(uuid.uuid4()), 'password': secrets.token_urlsafe(24)}
        private_json(path, spec)
    if not spec['organization'].startswith('gov-p1-'):
        raise RuntimeError('unknown fixture namespace')
    # 额外格式只创建新夹具客户端，避免把已验证客户端原位改成另一种配置。
    for purpose in ['standard', 'full', 'external']:
        if purpose not in spec['clients']:
            suffix = spec['organization'].removeprefix('gov-p1-')
            spec['clients'][purpose] = {'name': 'gov-p1-' + purpose + '-' + suffix,
                                       'secret': secrets.token_urlsafe(36)}
            private_json(path, spec)
    # 复用现有已知管理应用读取方法；不登录、不猜测管理员密码，不执行其开通主函数。
    if args.management_config:
        credential_path = Path(args.management_config)
        if credential_path.is_symlink() or credential_path.stat().st_mode & 0o777 != 0o600:
            raise RuntimeError('private management configuration required')
        credential = json.loads(credential_path.read_text())
        client_id, client_secret = credential['client_id'], credential['client_secret']
    else:
        if BASE != 'http://localhost:8000':
            raise RuntimeError('isolated instance requires its own management configuration')
        module_spec = importlib.util.spec_from_file_location('existing_casdoor_credentials', Path(__file__).with_name('wms-platform-provision.py'))
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        client_id, client_secret = module.builtin_app()
    admin = 'Basic ' + base64.b64encode((client_id + ':' + client_secret).encode()).decode()

    def get(kind, owner, name):
        return request('get-' + kind + '?' + urllib.parse.urlencode({'id': owner + '/' + name}), authorization=admin).get('data')

    def create(kind, owner, name, fields, identity_fields):
        current = get(kind, owner, name)
        if current:
            if any(current.get(key) != fields[key] for key in identity_fields):
                raise RuntimeError('owned fixture conflict: ' + kind)
            return current
        request('add-' + kind, dict(fields, owner=owner, name=name), admin)
        return get(kind, owner, name)

    org = spec['organization']
    create('organization', 'admin', org, {'displayName': 'Governance isolated P1 fixture', 'passwordType': 'bcrypt',
                                         'passwordOptions': ['AtLeast8'], 'accountItems': []}, ['passwordType'])
    for purpose, client in spec['clients'].items():
        fields = {'displayName': 'P1 isolated ' + purpose, 'organization': org, 'clientId': client['name'],
                  'clientSecret': client['secret'], 'cert': 'cert-built-in', 'isShared': False,
                  'enablePassword': True, 'enableSignUp': False, 'enableSigninSession': False,
                  'grantTypes': ['authorization_code', 'refresh_token'], 'redirectUris': [REDIRECT],
                  'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}],
                  'providers': [], 'expireInHours': -1 if purpose == 'expired' else 1,
                  'refreshExpireInHours': 2,
                  'tokenFormat': 'JWT-Standard' if purpose == 'standard' else 'JWT' if purpose == 'full' else 'JWT-Custom',
                  'tokenSigningMethod': 'RS256',
                  'tokenFields': ['Owner', 'Name', 'DisplayName']}
        create('application', 'admin', client['name'], fields,
               ['organization', 'clientId', 'clientSecret', 'grantTypes', 'redirectUris', 'tokenFormat', 'expireInHours'])
    for kind, user in spec['users'].items():
        create('user', org, user['name'], {'id': user['id'], 'displayName': 'P1 ' + kind, 'type': 'normal-user',
                                        'password': user['password'], 'isAdmin': False,
                                        'signupApplication': spec['clients']['management']['name']}, ['id', 'isAdmin'])

    def authorize(client, user, redirect=REDIRECT):
        """每个负例独立签发 code，避免因先前请求消费 code 得到伪通过。"""
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        state, nonce = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
        params = {'clientId': client['name'], 'responseType': 'code', 'redirectUri': redirect,
                  'scope': 'openid', 'state': state, 'nonce': nonce,
                  'code_challenge_method': 'S256', 'code_challenge': challenge}
        login = request('login?' + urllib.parse.urlencode(params),
                        {'type': 'code', 'organization': org, 'username': user['name'],
                         'password': user['password'], 'application': client['name'], 'signinMethod': 'Password'})
        code = login.get('data')
        if not isinstance(code, str) or not code:
            raise RuntimeError('authorization code not returned')
        return code, verifier, nonce

    def exchange(client, code, verifier, redirect=REDIRECT, allow_error=False):
        return request('login/oauth/access_token', {'grant_type': 'authorization_code', 'client_id': client['name'],
                       'code': code, 'code_verifier': verifier, 'redirect_uri': redirect}, form=True, allow_error=allow_error)

    tokens = {}
    for purpose, client in spec['clients'].items():
        user = spec['users']['external' if purpose == 'external' else 'internal']
        code, verifier, nonce = authorize(client, user)
        response = exchange(client, code, verifier)
        if not response.get('access_token') or not response.get('id_token'):
            raise RuntimeError('missing token response fields')
        tokens[purpose] = response
        tokens[purpose]['expected_nonce'] = nonce
        id_claims = json.loads(base64.urlsafe_b64decode(response['id_token'].split('.')[1] + '==='))
        if BASE.endswith(':18090') and id_claims.get('nonce') != nonce:
            raise RuntimeError('OIDC nonce mismatch')
    private_json(directory / 'tokens.json', tokens)
    facts = []
    for purpose, client in spec['clients'].items():
        auth = 'Basic ' + base64.b64encode((client['name'] + ':' + client['secret']).encode()).decode()
        for token_type in ['access_token', 'id_token']:
            result = request('login/oauth/introspect', {'token': tokens[purpose][token_type], 'token_type_hint': 'access_token'}, auth, form=True)
            facts.append({'client': purpose, 'presented_field': token_type, 'active': result.get('active'),
                          'token_type': result.get('token_type'), 'client_id_present': 'client_id' in result,
                          'claims_present': sorted(result), 'tokens_equal': tokens[purpose]['access_token'] == tokens[purpose]['id_token']})
    (directory / 'token-probe.json').write_text(json.dumps(facts, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(facts, ensure_ascii=False, indent=2))
    if args.phase == FixturePhase.TOKENS:
        return
    flow = []
    client, user = spec['clients']['management'], spec['users']['internal']
    for defect in ['wrong-verifier', 'wrong-redirect', 'reused-code']:
        code, verifier, _ = authorize(client, user)
        if defect == 'reused-code':
            if not exchange(client, code, verifier).get('access_token'):
                raise RuntimeError('first code exchange failed')
        result = exchange(client, code, secrets.token_urlsafe(48) if defect == 'wrong-verifier' else verifier,
                          REDIRECT + '?unexpected=1' if defect == 'wrong-redirect' else REDIRECT, allow_error=True)
        rejected = not result.get('access_token') and bool(result.get('rejected') or result.get('error') or result.get('status') == 'error')
        flow.append({'defect': defect, 'rejected': rejected})
    (directory / 'code-flow-probe.json').write_text(json.dumps(flow, indent=2) + '\n')
    print(json.dumps(flow, indent=2))
    if BASE.endswith(':18090') and any(not item['rejected'] for item in flow):
        raise RuntimeError('required authorization-code boundary failed; inspect code-flow-probe.json')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, urllib.error.URLError):
        raise SystemExit('FAIL: P1 Casdoor fixture; no credentials printed; inspect owned checkpoint locally')
