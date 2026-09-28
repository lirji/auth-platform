#!/usr/bin/env python3
"""隔离 P1-03 真实 HTTP 验收：真实 JAR/IdP/治理库，不调用共享服务写接口。"""
import argparse
import hashlib
import http.client
from http import HTTPStatus
from enum import Enum
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
import uuid

STARTUP_TIMEOUT = 45
HTTP_TIMEOUT = 10
SERVER_PORT = 18091
ADMIN_PORT = 18092
ISSUER = 'http://localhost:18090'
class Application(str, Enum):
    """固定宿主类型，不允许输入任意进程路径。"""
    SERVER = "server"
    ADMIN = "admin"


PROCESSES = []
CHECKS = []


def private(path, value):
    """本次证据目录独有，秘密不进入命令行、标准输出或版本控制。"""
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as file:
        file.write(value)


def read_private(path):
    if path.is_symlink() or path.stat().st_mode & 0o777 != 0o600:
        raise RuntimeError('private checkpoint required')
    return path.read_text()


def request(port, path, headers=(), body=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=HTTP_TIMEOUT)
    try:
        connection.putrequest('GET' if body is None else 'POST', path)
        for key, value in headers:
            connection.putheader(key, value)
        if body is not None:
            connection.putheader('Content-Type', 'application/json')
            connection.putheader('Content-Length', str(len(body)))
        connection.endheaders(body)
        response = connection.getresponse()
        raw = response.read(65537)
        return response.status, json.loads(raw) if raw else {}
    finally:
        connection.close()


def expect(name, port, path, headers=(), body=None, status=200, code=None):
    actual, result = request(port, path, headers, body)
    if actual != status or (code and (result.get('code') != code or set(result) != {'code', 'trace_id'})):
        raise RuntimeError('HTTP assertion failed: ' + name + ' status=' + str(actual))
    if code:
        uuid.UUID(result['trace_id'])
    CHECKS.append({'check': name, 'result': 'PASS'})
    return result


def start(jar, port, log, config=None, missing=False):
    """仅启动本任务回环进程；退出时只终止本工具持有的 Popen。"""
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))
    args = ['java', '-Xmx256m', '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=' + str(port)]
    if config or missing:
        args += ['--authz.governance.enabled=true']
    if config:
        args += ['--authz.governance.configuration=' + str(config)]
    descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        process = subprocess.Popen(args, stdout=output, stderr=subprocess.STDOUT)
    PROCESSES.append(process)
    deadline = time.monotonic() + STARTUP_TIMEOUT
    last_failure = "STARTING"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            if missing and process.returncode != 0:
                CHECKS.append({'check': jar.parent.parent.name + ' enabled missing config fails startup', 'result': 'PASS'})
                return process
            raise RuntimeError('owned application exited during startup; inspect private log')
        try:
            status, _ = request(port, '/actuator/health')
            if status == HTTPStatus.OK:
                if missing:
                    raise RuntimeError('enabled without config unexpectedly started')
                return process
        except (OSError, ValueError) as failure:
            # 启动探测允许暂时拒绝连接；超时保留类别且不回显连接/凭据。
            last_failure = type(failure).__name__
        time.sleep(0.25)
    raise RuntimeError('owned application startup timeout: ' + last_failure)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def props(values):
    if any('\n' in str(value) or '\r' in str(value) or '\\' in str(value) for value in values.values()):
        raise RuntimeError('unsupported fixture property character')
    return ''.join(key + '=' + str(value) + '\n' for key, value in values.items())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='.local/governance')
    args = parser.parse_args()
    root = Path(args.directory).resolve()
    database = json.loads(read_private(root / 'database.json'))
    if not re.fullmatch(r'auth_gov_p1_test_[a-f0-9]{12}', database['database']):
        raise RuntimeError('only owned isolated database accepted')
    fixture_dir = root / 'casdoor-isolated'
    fixture = json.loads(read_private(fixture_dir / 'casdoor.json'))
    tokens = json.loads(read_private(fixture_dir / 'tokens.json'))
    operations = json.loads(read_private(fixture_dir / 'management-client.json'))
    org = fixture['organization']
    if not re.fullmatch(r'gov-p1-[a-f0-9]{12}', org):
        raise RuntimeError('only owned identity fixture accepted')
    run = root / 'p1-03' / ('http-' + secrets.token_hex(6))
    run.mkdir(parents=True, mode=0o700)
    db_props = read_private(root / 'database.properties')
    jars = {kind: next(Path('auth-platform-' + kind + '/target').glob('auth-platform-' + kind + '-*.jar')) for kind in ['server', 'admin']}

    def authority(purpose):
        client = fixture['clients'][purpose]
        return {'issuer': ISSUER, 'jwks.uri': ISSUER + '/.well-known/jwks', 'audience': client['name'],
                'client.id': client['name'], 'client.secret': client['secret'],
                'version-probe.client.id': operations['client_id'], 'version-probe.client.secret': operations['client_secret']}

    service = secrets.token_urlsafe(48)
    server = {'service.count': 1, 'service.1.id': 'p103-smoke', 'service.1.application-id': 'smoke-business',
              'service.1.environment': 'isolated', 'service.1.operation': 'context.resolve',
              'service.1.credential-sha256': hashlib.sha256(service.encode()).hexdigest()}
    server.update({'service.1.user.' + key: value for key, value in authority('business').items()})
    private(run / 'admin.properties', db_props + props(authority('management')))
    private(run / 'server.properties', db_props + props(server))
    principal = str(uuid.UUID(bytes=hashlib.md5((org + ':principal').encode()).digest(), version=3))
    tenant, member = str(uuid.uuid4()), str(uuid.uuid4())
    command = {'command.id': str(uuid.uuid4()), 'operator.ref': 'http-smoke', 'tenant.id': tenant, 'tenant.code': 'http-' + tenant,
               'principal.id': principal, 'issuer': ISSUER, 'subject': fixture['users']['internal']['id'],
               'membership.id': member, 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'p103-http-fixture',
               'source.tenant.ref': tenant, 'source.subject.ref': 'internal'}
    private(run / 'bootstrap.properties', props(command))
    with os.fdopen(os.open(run / 'bootstrap.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
        result = subprocess.run(['java', '-Dloader.main=com.lrj.authz.governance.cli.GovernanceCli', '-cp', str(jars['server']),
                                 'org.springframework.boot.loader.launch.PropertiesLauncher', 'bootstrap',
                                 str(root / 'database.properties'), str(run / 'bootstrap.properties')],
                                stdout=output, stderr=subprocess.STDOUT, timeout=30)
    if result.returncode:
        raise RuntimeError('owned fixture bootstrap failed')
    CHECKS.append({'check': 'explicit CLI creates isolated membership', 'result': 'PASS'})
    internal = '/internal/governance/v1/context/resolve'
    me = '/api/governance/v1/me/memberships'
    for kind, port in [(Application.SERVER, SERVER_PORT), (Application.ADMIN, ADMIN_PORT)]:
        process = start(jars[kind], port, run / (kind + '-disabled.log'))
        if kind == Application.SERVER:
            expect('default off has no internal route', port, internal, body=b'{}', status=404)
        else:
            expect('default off old admin chain remains', port, me, status=401)
        stop(process)
        start(jars[kind], port, run / (kind + '-missing.log'), missing=True)
        start(jars[kind], port, run / (kind + '-enabled.log'), config=run / (kind + '.properties'))
    user = tokens['business']['access_token']
    admin = [('Authorization', 'Bearer ' + tokens['management']['access_token'])]
    headers = [('Authorization', 'Bearer ' + service), ('X-User-Access-Token', user)]
    body = json.dumps({'tenant_id': tenant, 'expected_membership_generation': 1}).encode()
    result = expect('me returns own membership', ADMIN_PORT, me, admin)
    assert member in [item['membership_id'] for item in result['memberships']]
    context = expect('dual identity current context', SERVER_PORT, internal, headers, body)
    assert context['principal_id'] == principal and context['membership_id'] == member
    assert context['tenant_id'] == tenant and context['membership_generation'] == 1
    assert context['application_id'] == 'smoke-business' and context['environment'] == 'isolated' and context['caller_service_id'] == 'p103-smoke'
    assert context['actor_type'] == 'HUMAN' and context['membership_version'] == 1 and context['principal_version'] == 1
    expect('admin missing bearer', ADMIN_PORT, me, status=401, code='INVALID_CREDENTIAL')
    expect('future governance route protected by filter', ADMIN_PORT, '/api/governance/v1/future', status=401, code='INVALID_CREDENTIAL')
    for label, token in [('ID token', tokens['management']['id_token']), ('wrong audience', user)]:
        expect('admin rejects ' + label, ADMIN_PORT, me, [('Authorization', 'Bearer ' + token)], status=401, code='INVALID_CREDENTIAL')
    expect('admin repeated authorization', ADMIN_PORT, me, admin + admin, status=401, code='INVALID_CREDENTIAL')
    expect('missing service before malformed body', SERVER_PORT, internal, [], b'bad', 401, 'INVALID_CREDENTIAL')
    expect('missing user', SERVER_PORT, internal, headers[:1], body, 401, 'INVALID_CREDENTIAL')
    expect('user cannot act as service', SERVER_PORT, internal, [('Authorization', 'Bearer ' + user), headers[1]], body, 401, 'INVALID_CREDENTIAL')
    expect('service cannot act as user', SERVER_PORT, internal, [headers[0], ('X-User-Access-Token', service)], body, 401, 'INVALID_CREDENTIAL')
    for purpose, field in [('business', 'id_token'), ('management', 'access_token')]:
        expect('internal rejects ' + purpose + '/' + field, SERVER_PORT, internal,
               [headers[0], ('X-User-Access-Token', tokens[purpose][field])], body, 401, 'INVALID_CREDENTIAL')
    for extra in ['principal_id', 'application_id', 'caller_service_id', 'environment']:
        expect('reject body ' + extra, SERVER_PORT, internal, headers,
               json.dumps({'tenant_id': tenant, extra: 'forged'}).encode(), 400, 'INVALID_ARGUMENT')
    expect('cross tenant', SERVER_PORT, internal, headers, json.dumps({'tenant_id': str(uuid.uuid4())}).encode(), 403, 'MEMBERSHIP_UNAVAILABLE')
    expect('stale generation', SERVER_PORT, internal, headers, json.dumps({'tenant_id': tenant, 'expected_membership_generation': 2}).encode(), 403, 'GENERATION_MISMATCH')
    expect('duplicate tenant field', SERVER_PORT, internal, headers, ('{"tenant_id":"' + tenant + '","tenant_id":"' + tenant + '"}').encode(), 400, 'INVALID_ARGUMENT')
    expect('oversized body', SERVER_PORT, internal, headers, b' ' * 4097, 400, 'INVALID_ARGUMENT')
    expect('repeated user header', SERVER_PORT, internal, headers + [headers[1]], body, 401, 'INVALID_CREDENTIAL')
    # 通过真实受控命令停用本次成员；不用直接 SQL 绕过版本、操作者与审计用例。
    private(run / 'lifecycle.properties', db_props + props({'lifecycle.operator-ref': 'p106-http-smoke', 'lifecycle.scope': tenant}))
    private(run / 'suspend.properties', props({'command.id': str(uuid.uuid4()), 'target.id': member,
                                             'expected.version': 1, 'reason': 'isolated HTTP suspension verification'}))
    with os.fdopen(os.open(run / 'suspend.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
        for attempt in range(2):
            result = subprocess.run(['java', '-Dloader.main=com.lrj.authz.governance.cli.LifecycleCli', '-cp', str(jars['server']),
                                     'org.springframework.boot.loader.launch.PropertiesLauncher', 'suspend-member',
                                     str(run / 'lifecycle.properties'), str(run / 'suspend.properties')],
                                    stdout=output, stderr=subprocess.STDOUT, timeout=30)
            if result.returncode:
                raise RuntimeError('owned controlled suspension or replay failed')
    CHECKS.append({'check': 'controlled CLI suspension and idempotent replay', 'result': 'PASS'})
    expect('old valid JWT rejected after suspension', SERVER_PORT, internal, headers, body, 403, 'MEMBERSHIP_UNAVAILABLE')
    result = expect('me excludes suspended membership', ADMIN_PORT, me, admin)
    assert member not in [item['membership_id'] for item in result['memberships']]
    facts = {'slices': ['P1-03', 'P1-06'], 'result': 'PASS', 'checks': CHECKS, 'shared_modified': False, 'evidence_directory': str(run)}
    (run / 'result.json').write_text(json.dumps(facts, indent=2) + '\n')
    print(json.dumps(facts, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, AssertionError, subprocess.SubprocessError) as failure:
        print(json.dumps({'result': 'FAIL', 'completed_checks': CHECKS, 'category': type(failure).__name__}))
        raise SystemExit('P1-03 HTTP smoke failed; inspect private logs, credentials not printed') from None
    finally:
        for owned_process in PROCESSES:
            stop(owned_process)
