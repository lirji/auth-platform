#!/usr/bin/env python3
"""真实PKCE/治理身份边界；只启动本工具持有的回环Auth server，不产生业务授权。"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import secrets
import urllib.parse
import urllib.request
import uuid


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


provision = module('wms_setup', 'governance-wms-provision.py')
h = module('governance_http_fixture', 'governance-context-smoke.py')


def pkce(state, user):
    """通过IdP正式授权码交换；返回的两种Token不能混用，不自己签发身份。"""
    def call(path, data, form=False):
        raw = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        try:
            with urllib.request.urlopen(urllib.request.Request(provision.ISSUER + '/api/' + path, raw,
                {'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'}), timeout=10) as response:
                result = json.loads(response.read(65537))
        except (OSError, ValueError):
            raise RuntimeError('PKCE身份请求失败') from None
        if result.get('error') or result.get('status') == 'error':
            raise RuntimeError('PKCE身份被拒绝')
        return result
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    redirect = provision.REDIRECTS[1]
    query = urllib.parse.urlencode({'clientId': provision.CLIENT, 'responseType': 'code', 'redirectUri': redirect,
        'scope': 'openid profile', 'state': secrets.token_urlsafe(24), 'nonce': secrets.token_urlsafe(24),
        'code_challenge_method': 'S256', 'code_challenge': challenge})
    login = call('login?' + query, {'type': 'code', 'organization': provision.ORGANIZATION,
        'username': user['name'], 'password': user['password'], 'application': provision.CLIENT, 'signinMethod': 'Password'})
    tokens = call('login/oauth/access_token', {'grant_type': 'authorization_code', 'client_id': provision.CLIENT,
        'code': login['data'], 'code_verifier': verifier, 'redirect_uri': redirect}, True)
    if not tokens.get('access_token') or not tokens.get('id_token') or tokens['access_token'] == tokens['id_token']:
        raise RuntimeError('发行方没有提供可区分的Access/ID Token')
    return tokens


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--port', type=int, default=18545)
    args = parser.parse_args()
    root = args.state_directory.resolve()
    state = json.loads(provision.read_private(root / 'state.json')); provision.validate_state(state)
    run = root / ('identity-smoke-' + secrets.token_hex(4)); run.mkdir(mode=0o700)
    tokens = pkce(state, state['users']['reader-a'])
    provision.private(run / 'tokens.json', json.dumps(tokens))
    jar = Path('auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar').resolve()
    try:
        h.start(jar, args.port, run / 'server.log', config=root / 'runtime/server.properties',
                access=True, scope=True, navigation=True)
        headers = [('Authorization', 'Bearer ' + state['services']['inventory']),
                   ('X-User-Access-Token', tokens['access_token'])]
        body = {'tenant_id': state['tenant'], 'expected_membership_generation': 1}
        prefix = '/internal/governance/v1/context/resolve'
        context = h.expect('real WMS Access Token resolves exact configured app/enterprise tenant', args.port,
            prefix, headers, json.dumps(body).encode())
        if (context['tenant_id'], context['application_id'], context['environment'], context['membership_id']) != (
                state['tenant'], 'wms', 'local', provision.uid('membership:reader-a')):
            raise RuntimeError('固定中央上下文不一致')
        h.expect('ID Token is not business identity', args.port, prefix,
            [('Authorization', headers[0][1]), ('X-User-Access-Token', tokens['id_token'])],
            json.dumps(body).encode(), 401, 'INVALID_CREDENTIAL')
        h.expect('foreign tenant denied', args.port, prefix, headers,
            json.dumps({**body, 'tenant_id': str(uuid.uuid4())}).encode(), 403, 'MEMBERSHIP_UNAVAILABLE')
        h.expect('stale member generation denied', args.port, prefix, headers,
            json.dumps({**body, 'expected_membership_generation': 2}).encode(), 403, 'GENERATION_MISMATCH')
        h.expect('forged app in request denied', args.port, prefix, headers,
            json.dumps({**body, 'application_id': 'commerce'}).encode(), 400, 'INVALID_ARGUMENT')
        h.expect('wrong service credential denied', args.port, prefix,
            [('Authorization', 'Bearer ' + secrets.token_urlsafe(48)), headers[1]], json.dumps(body).encode(), 401, 'INVALID_CREDENTIAL')
        provision.private(run / 'result.json', json.dumps({'result': 'PASS', 'checks': h.CHECKS,
            'actual_app': context['application_id'], 'actual_tenant': context['tenant_id'],
            'business_grants_created': 0, 'production_deployed': False}, ensure_ascii=False, indent=2))
        print('PASS: %d个真实PKCE/中央身份边界检查；凭据和证据保存在私密检查点。' % len(h.CHECKS))
    finally:
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
