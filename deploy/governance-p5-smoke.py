#!/usr/bin/env python3
"""P5真实门户验收：独立治理数据库、真实Casdoor/图、浏览器，不替换API响应。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


h = module('p5_http', 'governance-context-smoke.py')
a = module('p5_identity', 'governance-access-smoke.py')
ADMIN_PORT = 18522
UI_PORT = 15275


def uid():
    return str(uuid.uuid4())


def properties(path):
    return dict(line.split('=', 1) for line in h.read_private(path).splitlines() if '=' in line)


def timestamp(seconds):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--playwright-module', required=True)
    args = parser.parse_args()
    root = Path('.local/governance').resolve()
    base = root / 'p5'
    run = base / ('shell-' + secrets.token_hex(5))
    run.mkdir(mode=0o700, parents=True)
    (base / 'latest-shell.txt').write_text(str(run))
    subprocess.run(['python3', 'deploy/governance-test-db.py', '--container', 'auth-governance-p4-postgres-1', '--port', '15434', '--directory', str(run / 'database')], check=True, stdout=subprocess.DEVNULL)
    db = h.read_private(run / 'database/database.properties')
    fixture = json.loads(h.read_private(root / 'p2/identity/casdoor.json'))
    ops = json.loads(h.read_private(root / 'casdoor-isolated/management-client.json'))
    graph = properties(root / 'p3/graph/graph.properties')
    jar = run / 'admin.jar'
    shutil.copy2(next(Path('auth-platform-admin/target').glob('auth-platform-admin-*.jar')), jar)

    def cli(name, params):
        package = 'com.lrj.authz.admin.governance.' if name.endswith('ProjectionCli') else 'com.lrj.authz.governance.cli.'
        with os.fdopen(os.open(run / (name + '-' + secrets.token_hex(3) + '.log'), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w') as log:
            subprocess.run(['java', '-Xmx256m', '-Dloader.main=' + package + name, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, params)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=45)

    tenants = [uid(), uid()]
    owner = str(uuid.uuid5(uuid.NAMESPACE_URL, 'p5:' + fixture['users']['internal']['id']))
    owners = []
    app = 'commerce'
    capabilities = [app + '.product.read', app + '.product.export']
    for number, tenant in enumerate(tenants):
        member = uid(); owners.append(member)
        values = {'command.id': uid(), 'operator.ref': 'p5-fixture', 'tenant.id': tenant, 'tenant.code': 'p5-enterprise-' + str(number) + '-' + run.name,
                  'principal.id': owner, 'issuer': h.ISSUER, 'subject': fixture['users']['internal']['id'], 'membership.id': member,
                  'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'p5-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'internal'}
        path = run / ('owner-' + str(number) + '.properties'); h.private(path, h.props(values))
        cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', path])
    cat = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'http://127.0.0.1:18605', 'catalog.operator': 'p5-fixture',
           'catalog.command': uid(), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': fixture['users']['internal']['id']}
    h.private(run / 'catalog.properties', db + h.props(cat)); cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    manifest = {'schema_version': '1', 'application': app, 'manifest_version': 1,
                'capabilities': [{'code': c, 'resource_type': 'product', 'risk_level': 'NORMAL' if c.endswith('read') else 'HIGH'} for c in capabilities],
                'menus': [{'code': 'products', 'parent': None, 'route': '/collaboration/products', 'any_of': [capabilities[0]]}]}
    (run / 'manifest.json').write_text(json.dumps(manifest)); cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    for number, tenant in enumerate(tenants):
        values = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': owners[number], 'access.generation': 1,
                  'access.capabilities': ','.join(capabilities), 'access.max-duration-seconds': 7200, 'access.operator': 'p5-fixture', 'access.command': uid(), **graph}
        for kind in ['POLICY', 'DIRECTORY']:
            h.private(run / (str(number) + '-' + kind + '.properties'), db + h.props({**values, 'projection.kind': kind}))
        cli('AccessBootstrapCli', [run / (str(number) + '-POLICY.properties')])
    management = fixture['clients']['management']
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': management['name'], 'client.id': management['name'],
                 'client.secret': management['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret']}
    # 回调入口只绑定首个试点分区；随机密钥与显式loopback不影响共享环境。
    callback = {'approval.tenant': tenants[0], 'approval.application': app, 'approval.environment': 'test', 'approval.inbound-key': secrets.token_urlsafe(36), 'approval.allow-loopback-http': 'true'}
    h.private(run / 'admin.properties', db + h.props({**authority, **graph, **callback, 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key'], **{'invitation.user.' + k: v for k, v in authority.items()}}))
    process = None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True, invitations=True, requests=True)
        manager = a.token(h.ISSUER, fixture, 'management', 'internal')
        external = a.token(h.ISSUER, fixture, 'management', 'external')
        headers = [('Authorization', 'Bearer ' + manager)]
        def request(name, route, payload=None, token=manager, status=200):
            return h.expect(name, ADMIN_PORT, '/api/governance/v1' + route, [('Authorization', 'Bearer ' + token)], None if payload is None else json.dumps(payload).encode(), status)
        members = []
        for number, tenant in enumerate(tenants):
            part = {'tenant_id': tenant, 'application_id': app, 'environment': 'test'}
            invitation = uid()
            h.private(run / ('invitation-authority-' + str(number) + '.properties'), db + h.props({'invitation.operator-ref': 'p5-fixture', 'invitation.tenant-id': tenant, 'invitation.sponsor-membership-id': owners[number]}))
            h.private(run / ('invitation-' + str(number) + '.properties'), h.props({'command.id': uid(), 'invitation.id': invitation, 'issuer': h.ISSUER, 'subject': fixture['users']['external']['id'], 'member.kind': 'PARTNER', 'expires.at': timestamp(600), 'membership.valid.to': timestamp(7200), 'reason': 'P5 store collaboration'}))
            proof = run / ('proof-' + str(number) + '.properties')
            cli('InvitationCli', ['issue', run / ('invitation-authority-' + str(number) + '.properties'), run / ('invitation-' + str(number) + '.properties'), proof])
            accepted = request('accept real partner invitation', '/invitations/accept', {'invitation_id': invitation, 'token': properties(proof)['token']}, external)
            members.append(accepted['membership_id'])
            request('enable strict partition', '/access/enable-strict', {**part, 'command_id': uid()}, status=202)
            role = request('create fixed product role', '/access/roles', {**part, 'command_id': uid(), 'role_code': 'product-reader', 'role_version': 1, 'capabilities': [capabilities[0]]})
            request('grant specified stores', '/access/scoped-grants', {**part, 'command_id': uid(), 'member_id': members[-1], 'member_generation': 1, 'role_id': role['id'],
                    'scope_rule': {'version': 1, 'resource_type': 'product', 'clauses': [{'kind': 'SPECIFIED_STORES', 'values': ['STORE-A' if number == 0 else 'STORE-B'], 'include_root': False}]},
                    'source_id': 'p5-query-' + uid(), 'valid_from': timestamp(-1), 'valid_to': timestamp(3600)}, status=202)
            for kind in ['POLICY', 'DIRECTORY']: cli('ReliableProjectionCli', [run / (str(number) + '-' + kind + '.properties')])
        h.private(run / 'fixture.json', json.dumps({'tenants': tenants, 'owners': owners, 'members': members, 'application': app, 'environment': 'test', 'manager_token': manager, 'member_token': external}))
        env = dict(os.environ, AUTH_CONSOLE_UI_PORT=str(UI_PORT), VITE_GOVERNANCE_TARGET='http://127.0.0.1:' + str(ADMIN_PORT),
                   VITE_CASDOOR_AUTHORITY=h.ISSUER, VITE_CASDOOR_CLIENT_ID=management['name'], P5_UI_FIXTURE=str(run), P5_PLAYWRIGHT_MODULE=str(Path(args.playwright_module).resolve()))
        with socket.socket() as guard: guard.bind(('127.0.0.1', UI_PORT))
        with os.fdopen(os.open(run / 'vite.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as log:
            process = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--strictPort'], cwd='auth-console', env=env, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(60):
            if process.poll() is not None: raise RuntimeError('console startup failed')
            try:
                with socket.create_connection(('127.0.0.1', UI_PORT), timeout=1): break
            except OSError: time.sleep(.5)
        else: raise RuntimeError('console startup timeout')
        subprocess.run(['node', 'deploy/governance-p5-shell.mjs'], env=env, check=True, timeout=180)
        (run / 'http-result.json').write_text(json.dumps(h.CHECKS, indent=2))
        print('PASS P5 shell: ' + str(run))
    finally:
        if process: h.stop(process)
        for child in h.PROCESSES: h.stop(child)


if __name__ == '__main__':
    main()
