#!/usr/bin/env python3
"""P1-05 隔离 HTTP/CLI 验收，使用独立 IdP 夹具，不绑定 P1-02 的固定未绑定用户。"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import uuid

spec = importlib.util.spec_from_file_location('context_smoke', Path(__file__).with_name('governance-context-smoke.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
ACCEPT = '/api/governance/v1/invitations/accept'
ME = '/api/governance/v1/me/memberships'
CONTEXT = '/internal/governance/v1/context/resolve'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='.local/governance')
    parser.add_argument('--fixture-directory', default='.local/governance/p1-05-idp')
    args = parser.parse_args()
    root, fixture_dir = Path(args.directory).resolve(), Path(args.fixture_directory).resolve()
    if fixture_dir == root / 'casdoor-isolated':
        raise RuntimeError('P1-02 unbound fixture must be preserved')
    database = json.loads(base.read_private(root / 'database.json'))
    fixture = json.loads(base.read_private(fixture_dir / 'casdoor.json'))
    tokens = json.loads(base.read_private(fixture_dir / 'tokens.json'))
    operations = json.loads(base.read_private(root / 'casdoor-isolated' / 'management-client.json'))
    if not re.fullmatch(r'auth_gov_p1_test_[a-f0-9]{12}', database['database']):
        raise RuntimeError('owned isolated database required')
    if not re.fullmatch(r'gov-p1-[a-f0-9]{12}', fixture['organization']):
        raise RuntimeError('owned identity namespace required')
    run = root / 'p1-05' / ('http-' + secrets.token_hex(6))
    run.mkdir(parents=True, mode=0o700)
    db = base.read_private(root / 'database.properties')
    jars = {kind: next(Path('auth-platform-' + kind + '/target').glob('auth-platform-' + kind + '-*.jar'))
            for kind in ['admin', 'server']}
    client = fixture['clients']['external']
    authority = {'issuer': base.ISSUER, 'jwks.uri': base.ISSUER + '/.well-known/jwks',
                 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'],
                 'version-probe.client.id': operations['client_id'],
                 'version-probe.client.secret': operations['client_secret']}
    admin_config = dict(authority)
    admin_config.update({'invitation.user.' + key: value for key, value in authority.items()})
    base.private(run / 'admin.properties', db + base.props(admin_config))
    service = secrets.token_urlsafe(48)
    server = {'service.count': 1, 'service.1.id': 'p105-smoke', 'service.1.application-id': 'invitation-smoke',
              'service.1.environment': 'isolated', 'service.1.operation': 'context.resolve',
              'service.1.credential-sha256': hashlib.sha256(service.encode()).hexdigest()}
    server.update({'service.1.user.' + key: value for key, value in authority.items()})
    base.private(run / 'server.properties', db + base.props(server))

    def cli(kind, action, config, command, proof=None, expected=0):
        """每次日志为私密新文件；只检查退出状态，不将日志内容打印到控制台。"""
        log = run / ('cli-' + secrets.token_hex(6) + '.log')
        args = ['java', '-Dloader.main=com.lrj.authz.governance.cli.' + kind, '-cp', str(jars['server']),
                'org.springframework.boot.loader.launch.PropertiesLauncher', action, str(config), str(command)]
        if proof:
            args.append(str(proof))
        with os.fdopen(os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
            completed = subprocess.run(args, stdout=output, stderr=subprocess.STDOUT, timeout=30)
        if completed.returncode != expected:
            raise RuntimeError('controlled CLI result mismatch: ' + kind + '/' + action)

    tenant, sponsor = str(uuid.uuid4()), str(uuid.uuid4())
    # 同一独立 IdP 夹具再次运行时复用其显式主体，新建隔离企业与员工成员。
    principal = str(uuid.uuid5(uuid.NAMESPACE_URL, fixture['organization'] + ':internal'))
    bootstrap = {'command.id': str(uuid.uuid4()), 'operator.ref': 'p105-http', 'tenant.id': tenant,
                 'tenant.code': 'invite-' + tenant, 'principal.id': principal, 'issuer': base.ISSUER,
                 'subject': fixture['users']['internal']['id'], 'membership.id': sponsor,
                 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'p105-http-fixture',
                 'source.tenant.ref': tenant, 'source.subject.ref': 'internal'}
    base.private(run / 'bootstrap.properties', base.props(bootstrap))
    cli('GovernanceCli', 'bootstrap', root / 'database.properties', run / 'bootstrap.properties')
    base.private(run / 'invitation.properties', db + base.props({'invitation.operator-ref': 'p105-http',
                 'invitation.tenant-id': tenant, 'invitation.sponsor-membership-id': sponsor}))
    base.private(run / 'lifecycle.properties', db + base.props({'lifecycle.operator-ref': 'p105-http', 'lifecycle.scope': tenant}))

    def issue(label, subject=None):
        invitation = str(uuid.uuid4())
        now = datetime.now(timezone.utc)
        command = {'command.id': str(uuid.uuid4()), 'invitation.id': invitation, 'issuer': base.ISSUER,
                   'subject': subject or fixture['users']['external']['id'], 'member.kind': 'PARTNER',
                   'expires.at': (now + timedelta(minutes=10)).isoformat(),
                   'membership.valid.to': (now + timedelta(days=1)).isoformat(), 'reason': 'isolated invitation verification'}
        command_path, token_path = run / (label + '.properties'), run / (label + '-proof.properties')
        base.private(command_path, base.props(command))
        cli('InvitationCli', 'issue', run / 'invitation.properties', command_path, token_path)
        before = base.read_private(token_path)
        cli('InvitationCli', 'issue', run / 'invitation.properties', command_path, token_path)
        if base.read_private(token_path) != before:
            raise RuntimeError('idempotent CLI changed proof')
        proof = dict(line.split('=', 1) for line in before.splitlines())
        base.CHECKS.append({'check': label + ' CLI issue replay retains private proof', 'result': 'PASS'})
        return {'invitation_id': invitation, 'token': proof['token']}

    def lifecycle(action, member, version):
        path = run / ('lifecycle-' + secrets.token_hex(6) + '.properties')
        base.private(path, base.props({'command.id': str(uuid.uuid4()), 'target.id': member,
                                     'expected.version': version, 'reason': 'isolated lifecycle verification'}))
        cli('LifecycleCli', action, run / 'lifecycle.properties', path)
        cli('LifecycleCli', action, run / 'lifecycle.properties', path)
        base.CHECKS.append({'check': action + ' CLI and replay', 'result': 'PASS'})

    user = [('Authorization', 'Bearer ' + tokens['external']['access_token'])]
    dual = [('Authorization', 'Bearer ' + service), ('X-User-Access-Token', tokens['external']['access_token'])]
    first = issue('first')
    body = json.dumps(first).encode()
    # 先证明邀请开关关闭时，未绑定用户没有通用治理入口；独立开关不能越过主开关。
    process = base.start(jars['admin'], base.ADMIN_PORT, run / 'off.log', run / 'admin.properties')
    # 首次未绑定为 403；重跑已绑定时路由不存在，框架可能经旧 /error 链返回 401 或 404。
    status, response = base.request(base.ADMIN_PORT, ACCEPT, user, body)
    if status not in (401, 403, 404):
        raise RuntimeError('disabled invitation route became available')
    base.CHECKS.append({'check': 'invitation switch disabled rejects acceptance', 'result': 'PASS'})
    base.stop(process)
    base.start(jars['admin'], base.ADMIN_PORT, run / 'enabled.log', run / 'admin.properties', invitations=True)
    base.start(jars['server'], base.SERVER_PORT, run / 'server.log', run / 'server.properties')
    base.expect('invitation missing bearer', base.ADMIN_PORT, ACCEPT, body=body, status=401, code='INVALID_CREDENTIAL')
    for label, token in [('ID token', tokens['external']['id_token']), ('wrong audience', tokens['management']['access_token'])]:
        base.expect('invitation rejects ' + label, base.ADMIN_PORT, ACCEPT, [('Authorization', 'Bearer ' + token)], body, 401, 'INVALID_CREDENTIAL')
    base.expect('repeated authorization rejected', base.ADMIN_PORT, ACCEPT, user + user, body, 401, 'INVALID_CREDENTIAL')
    base.expect('unknown body field rejected', base.ADMIN_PORT, ACCEPT, user,
                json.dumps(dict(first, principal_id='forged')).encode(), 400, 'INVALID_ARGUMENT')
    base.expect('oversized invitation body', base.ADMIN_PORT, ACCEPT, user, b' ' * 4097, 400, 'INVALID_ARGUMENT')
    base.expect('wrong invitation proof', base.ADMIN_PORT, ACCEPT, user,
                json.dumps(dict(first, token=secrets.token_urlsafe(32))).encode(), 401, 'INVALID_CREDENTIAL')
    wrong_target = issue('wrong-target', fixture['users']['internal']['id'])
    base.expect('exact subject required', base.ADMIN_PORT, ACCEPT, user, json.dumps(wrong_target).encode(), 401, 'INVALID_CREDENTIAL')
    revoked = issue('revoked')
    path = run / 'revoke.properties'
    base.private(path, base.props({'command.id': str(uuid.uuid4()), 'invitation.id': revoked['invitation_id'],
                                 'expected.version': 1, 'reason': 'isolated revoke'}))
    cli('InvitationCli', 'revoke', run / 'invitation.properties', path)
    cli('InvitationCli', 'revoke', run / 'invitation.properties', path)
    base.expect('revoked invitation denied', base.ADMIN_PORT, ACCEPT, user, json.dumps(revoked).encode(), 401, 'INVALID_CREDENTIAL')
    accepted = base.expect('external identity accepts invitation', base.ADMIN_PORT, ACCEPT, user, body)
    member = accepted['membership_id']
    assert accepted['membership_generation'] == 1 and accepted['membership_status'] == 'ACTIVE'
    replay = base.expect('accept replay is same membership', base.ADMIN_PORT, ACCEPT, user, body)
    assert replay['membership_id'] == member and replay['membership_generation'] == 1
    result = base.expect('external me lists accepted membership', base.ADMIN_PORT, ME, user)
    assert any(item['membership_id'] == member and item['member_kind'] == 'PARTNER' for item in result['memberships'])
    context_body = json.dumps({'tenant_id': tenant, 'expected_membership_generation': 1}).encode()
    context = base.expect('external current context', base.SERVER_PORT, CONTEXT, dual, context_body)
    assert context['membership_id'] == member and context['actor_type'] == 'HUMAN'
    second = issue('second')
    base.expect('active membership cannot be silently expanded', base.ADMIN_PORT, ACCEPT, user,
                json.dumps(second).encode(), 409, 'BINDING_CONFLICT')
    lifecycle('leave-external-member', member, 1)
    base.expect('left membership no longer resolves', base.SERVER_PORT, CONTEXT, dual, context_body, 403, 'MEMBERSHIP_UNAVAILABLE')
    rejoined = base.expect('rejoin keeps member and increments generation', base.ADMIN_PORT, ACCEPT, user, json.dumps(second).encode())
    assert rejoined['membership_id'] == member and rejoined['membership_generation'] == 2
    base.expect('old invite cannot restore old generation', base.ADMIN_PORT, ACCEPT, user, body, 403, 'GENERATION_MISMATCH')
    base.expect('old business context generation rejected', base.SERVER_PORT, CONTEXT, dual, context_body, 403, 'GENERATION_MISMATCH')
    lifecycle('suspend-member', member, 3)
    base.expect('suspended member cannot replay accepted invite', base.ADMIN_PORT, ACCEPT, user,
                json.dumps(second).encode(), 403, 'MEMBERSHIP_UNAVAILABLE')
    pending = issue('sponsor-stop')
    lifecycle('suspend-member', sponsor, 1)
    base.expect('stopped sponsor blocks pending invitation', base.ADMIN_PORT, ACCEPT, user,
                json.dumps(pending).encode(), 403, 'MEMBERSHIP_UNAVAILABLE')
    # 验证 CLI/应用日志均没有令牌原文，读取仅在进程内比较，不输出秘密。
    forbidden = [tokens['external']['access_token'], tokens['external']['id_token'], service,
                 first['token'], second['token'], pending['token']]
    for log in run.glob('*.log'):
        content = base.read_private(log)
        if any(secret in content for secret in forbidden):
            raise RuntimeError('secret appeared in owned logs')
    base.CHECKS.append({'check': 'logs contain no tested bearer or invitation proof', 'result': 'PASS'})
    facts = {'slice': 'P1-05', 'result': 'PASS', 'checks': base.CHECKS,
             'evidence_directory': str(run), 'shared_modified': False}
    (run / 'result.json').write_text(json.dumps(facts, indent=2) + '\n')
    print(json.dumps(facts, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, AssertionError, KeyError, subprocess.SubprocessError) as failure:
        print(json.dumps({'result': 'FAIL', 'completed_checks': base.CHECKS,
                          'category': type(failure).__name__, 'errno': getattr(failure, 'errno', None)}))
        raise SystemExit('P1-05 smoke failed; inspect private logs, credentials not printed') from None
    finally:
        for process in base.PROCESSES:
            base.stop(process)
