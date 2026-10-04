#!/usr/bin/env python3
"""MG14-B自有PG／真实IdP／图及可信审批协议夹具；不写原业务授权。"""
import argparse
import base64
import datetime
import importlib.util
from http import HTTPStatus
import json
import hashlib
import hmac
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADMIN_PORT, UI_PORT, OA_PORT = 21821, 21822, 21823
CLI_TIMEOUT, BROWSER_TIMEOUT, STARTUP_TIMEOUT = 40, 240, 30
PG_TIMEOUT, CALLBACK_TIMEOUT = 10, 45


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    """只创建本轮隔离数据，进程finally回收，秘密仅0600文件。"""
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--issuer', choices=['http://localhost:18090', 'http://localhost:18094'], default='http://localhost:18094')
    parser.add_argument('--identity-fixture', type=Path, default=ROOT / '.local/menu-role-governance/mg12-idp/identity/casdoor.json')
    parser.add_argument('--management-config', type=Path, default=ROOT / '.local/menu-role-governance/mg12-idp/casdoor-isolated/management-client.json')
    parser.add_argument('--tokens-file', type=Path)
    args = parser.parse_args()
    h = module('mg13_context', 'governance-context-smoke.py')
    h.ISSUER = args.issuer
    run = ROOT / '.local/menu-role-governance' / ('mg14b-http-' + uuid.uuid4().hex[:12])
    run.mkdir(mode=0o700, parents=True)
    subprocess.run(['python3', 'deploy/governance-test-db.py', '--directory', str(run / 'database')], cwd=ROOT, stdout=subprocess.DEVNULL, check=True, timeout=STARTUP_TIMEOUT)
    database = json.loads(h.read_private(run / 'database/database.json'))['database']
    db = h.read_private(run / 'database/database.properties')
    fixture = json.loads(h.read_private(args.identity_fixture))
    ops = json.loads(h.read_private(args.management_config))
    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        for name in ('protocol', 'governance'):
            filename = 'auth-platform-' + name + '-0.1.0-SNAPSHOT.jar'
            if archive.read('BOOT-INF/lib/' + filename) != (ROOT / ('auth-platform-' + name) / 'target' / filename).read_bytes():
                raise RuntimeError('current embedded artifact required')

    def uid():
        return str(uuid.uuid4())

    def stamp(seconds):
        return (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')

    def cli(name, args):
        log = run / (name + '-' + uuid.uuid4().hex[:8] + '.log')
        with log.open('w') as out:
            result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.governance.cli.' + name, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, args)], stdout=out, stderr=subprocess.STDOUT, timeout=CLI_TIMEOUT)
        if result.returncode:
            raise RuntimeError('owned CLI failed: ' + name)

    def pg(sql):
        result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=PG_TIMEOUT)
        if result.returncode:
            raise RuntimeError('owned PG assertion failed')
        return result.stdout.strip()

    tenant, owner, manager, recipient = uid(), uid(), uid(), uid()
    app = 'mg13-' + uuid.uuid4().hex[:12]
    partition = dict(tenant_id=tenant, application_id=app, environment='test')
    user = fixture['users']['internal']
    person = {'command.id': uid(), 'operator.ref': 'mg13-fixture', 'tenant.id': tenant, 'tenant.code': 'mg13-' + tenant, 'principal.id': owner, 'issuer': h.ISSUER, 'subject': user['id'], 'membership.id': manager, 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg13-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'owner'}
    h.private(run / 'owner.properties', h.props(person))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'owner.properties'])
    target = {**person, 'command.id': uid(), 'principal.id': uid(), 'membership.id': recipient, 'subject': fixture['users']['external']['id'], 'source.subject.ref': 'recipient'}
    h.private(run / 'recipient.properties', h.props(target))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'recipient.properties'])
    catalog = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'https://mg13.example', 'catalog.operator': 'mg13-fixture', 'catalog.command': uid(), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': user['id']}
    h.private(run / 'catalog.properties', db + h.props(catalog))
    cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    caps = [app + '.read', app + '.write', app + '.merchant']
    manifest = dict(schema_version='1', application=app, manifest_version=1, capabilities=[dict(code=c, resource_type='merchant' if i == 2 else 'store', risk_level='NORMAL') for i, c in enumerate(caps)], menus=[])
    h.private(run / 'manifest.json', json.dumps(manifest))
    cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    delegation = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': manager, 'access.generation': '1', 'access.capabilities': ','.join(caps), 'access.max-duration-seconds': '3600', 'access.operator': 'mg13-fixture', 'access.command': uid()}
    h.private(run / 'access.properties', db + h.props(delegation))
    cli('AccessBootstrapCli', [run / 'access.properties'])
    client = fixture['clients']['management']
    # 本机共享宿主存在实测慢响应；只给本轮隔离目标选择既有配置上限，不改变产品2秒默认。
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret'], 'read-timeout-ms': '5000'}
    graph = dict(line.split('=', 1) for line in h.read_private(ROOT / '.local/governance/p3/graph/graph.properties').splitlines() if line and not line.startswith('#') and '=' in line)
    authority.update({'graph.http': graph['graph.http'], 'graph.key': graph['graph.key'], 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key']})
    approval_key, fixture_key = secrets.token_urlsafe(36), secrets.token_urlsafe(36)
    authority.update({'approval.tenant': tenant, 'approval.application': app, 'approval.environment': 'test', 'approval.inbound-key': approval_key, 'approval.allow-loopback-http': 'true'})
    worker = run / 'approval-worker.properties'
    h.private(worker, db + h.props({'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'approval.oa-url': 'http://127.0.0.1:' + str(OA_PORT), 'approval.outbound-key': approval_key}))
    h.private(run / 'admin.properties', db + h.props(authority))
    vite, oa_server = None, None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True, requests=True)
        if True:
            auth = module('mg14a_auth', 'governance-access-smoke.py')
            token, other = auth.token(h.ISSUER, fixture, 'management', 'internal'), auth.token(h.ISSUER, fixture, 'management', 'external')
            h.private(run / 'tokens.json', json.dumps({'management': {'access_token': token}, 'external': {'access_token': other}}))
        else:
            tokens = json.loads(h.read_private(args.tokens_file or args.identity_fixture.parent / 'tokens.json'))
            token, other = tokens['management']['access_token'], tokens['external']['access_token']
        for key, expected in ((token, user['id']), (other, fixture['users']['external']['id'])):
            claims = json.loads(base64.urlsafe_b64decode(key.split('.')[1] + '==='))
            if claims.get('iss') != h.ISSUER or claims.get('sub') != expected or claims.get('exp', 0) <= time.time() + 240:
                raise RuntimeError('current owned PKCE tokens required')
        headers = [('Authorization', 'Bearer ' + token)]
        transport_retries = []

        def request(name, endpoint, payload=None, status=200, code=None, credential=headers, raw=None):
            body = raw if raw is not None else None if payload is None else json.dumps(payload).encode()
            # 只有依赖503才有界重试完全相同的幂等命令／查询；不掩盖业务拒绝或改动载荷。
            for attempt in range(3):
                actual, result = h.request(ADMIN_PORT, endpoint if endpoint.startswith('/api/') else '/api/governance/v1/access' + endpoint, credential, body)
                if actual == HTTPStatus.SERVICE_UNAVAILABLE and status != HTTPStatus.SERVICE_UNAVAILABLE and result.get('code') == 'DEPENDENCY_UNAVAILABLE' and attempt < 2:
                    transport_retries.append({'check': name, 'attempt': attempt + 1, 'status': actual, 'code': result['code']})
                    h.private(run / 'transport-retries.json', json.dumps(transport_retries))
                    time.sleep(1)
                    continue
                if actual != status or (code and (result.get('code') != code or set(result) != {'code', 'trace_id'})):
                    raise RuntimeError('HTTP assertion failed: ' + name + ' status=' + str(actual))
                if code:
                    uuid.UUID(result['trace_id'])
                h.CHECKS.append({'check': name, 'result': 'PASS'})
                break
            h.private(run / ('checks-' + str(len(h.CHECKS)) + '.json'), json.dumps(h.CHECKS))
            return result

        request('strict isolated partition', '/enable-strict', {**partition, 'command_id': uid()}, 202)
        roles = [request('fixed role version ' + str(i + 1), '/roles', {**partition, 'command_id': uid(), 'role_code': 'reader', 'role_version': i + 1, 'capabilities': selected}) for i, selected in enumerate((caps[:2], caps[:1], [caps[0], caps[2]]))]
        member_headers = [('Authorization', 'Bearer ' + other)]
        requests_root = '/api/governance/v1/requests'
        query = '?' + urllib.parse.urlencode(partition)

        def admin_cli(name, config):
            log = run / (name + '-' + uid() + '.log')
            with log.open('w') as out:
                result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.' + name, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(config)], stdout=out, stderr=subprocess.STDOUT, timeout=CLI_TIMEOUT)
            if result.returncode:
                raise RuntimeError('owned consumer failed: ' + name)

        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"

        # 仅本轮测试库保存OA协议夹具实例；不是生产OA引擎或手写中央APPROVED。
        pg("CREATE TABLE auth_governance.test_mg14b_oa_instance(request_id text PRIMARY KEY, request_json text NOT NULL, instance_id text NOT NULL); COMMENT ON TABLE auth_governance.test_mg14b_oa_instance IS '本轮隔离OA协议夹具持久实例，非真实审批引擎'; COMMENT ON COLUMN auth_governance.test_mg14b_oa_instance.request_id IS '中央固定申请业务键'; COMMENT ON COLUMN auth_governance.test_mg14b_oa_instance.request_json IS '真实签名启动请求的固定快照'; COMMENT ON COLUMN auth_governance.test_mg14b_oa_instance.instance_id IS '夹具持久实例标识';")

        class OaFixture(BaseHTTPRequestHandler):
            """真实HTTP适配和HMAC边界夹具，实例持久化自有PG，不供产品页面取假数据。"""
            def log_message(self, *_):
                pass

            def send(self, status, payload):
                body = json.dumps(payload).encode()
                self.send_response(status); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

            def do_POST(self):
                try:
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= 65536:
                        self.send(400, {}); return
                    wire = self.rfile.read(size); body = json.loads(wire)
                    if self.path == '/decide':
                        if self.headers.get('X-Test-Fixture') != fixture_key or body.get('outcome') not in ('APPROVED', 'REJECTED'):
                            self.send(403, {}); return
                        uuid.UUID(body['request_id'])
                        stored = pg('SELECT request_json FROM auth_governance.test_mg14b_oa_instance WHERE request_id=' + quote(body['request_id']))
                        if not stored:
                            self.send(404, {}); return
                        start = json.loads(stored)
                        instance = pg('SELECT instance_id FROM auth_governance.test_mg14b_oa_instance WHERE request_id=' + quote(body['request_id']))
                        event = dict(event_id=uid(), event_type='ACCESS_REQUEST_DECIDED', schema_version=1, producer='oa-platform', **{k: start[k] for k in ('tenant_id', 'application_id', 'environment', 'request_id', 'request_version', 'snapshot_hash', 'policy_id', 'policy_version', 'approver_membership_id', 'approver_generation')}, approval_instance_id=instance, aggregate_version=1, outcome=body['outcome'], decision_time=stamp(0), correlation_id=uid())
                        event_wire = json.dumps(event, separators=(',', ':')).encode()
                        meta = 'oa-platform:test:' + str(int(time.time())) + ':' + uid()
                        signature = meta + ':' + hmac.new(approval_key.encode(), meta.encode() + b'\nPOST\n/internal/oa-approval/v1/events\n' + event_wire, hashlib.sha256).hexdigest()
                        actual, receipt = h.request(ADMIN_PORT, '/internal/oa-approval/v1/events', [('X-Approval-Signature', signature)], event_wire)
                        if actual != 202:
                            raise RuntimeError('owned signed callback failed')
                        admin_cli('ApprovalDecisionCli', worker)
                        self.send(200, {'event_id': event['event_id'], 'status': 'CONSUMED'}); return
                    if self.path not in ('/internal/iam-approval/v1/start', '/internal/iam-approval/v1/lookup'):
                        self.send(404, {}); return
                    parts = self.headers.get('X-Approval-Signature', '').split(':')
                    if len(parts) != 5 or parts[:2] != ['auth-platform', 'test'] or abs(time.time() - int(parts[2])) > 60:
                        self.send(401, {}); return
                    meta = ':'.join(parts[:4]); expected = hmac.new(approval_key.encode(), meta.encode() + b'\nPOST\n' + self.path.encode() + b'\n' + wire, hashlib.sha256).hexdigest()
                    if not hmac.compare_digest(parts[4], expected) or any(body.get(k) != v for k, v in partition.items()):
                        self.send(403, {}); return
                    uuid.UUID(body['request_id'])
                    saved = pg('SELECT request_json FROM auth_governance.test_mg14b_oa_instance WHERE request_id=' + quote(body['request_id']))
                    if not saved and self.path.endswith('/lookup'):
                        self.send(404, {}); return
                    if not saved:
                        pg('INSERT INTO auth_governance.test_mg14b_oa_instance VALUES(' + quote(body['request_id']) + ',' + quote(wire.decode()) + ',' + quote(uid()) + ') ON CONFLICT DO NOTHING')
                        saved = pg('SELECT request_json FROM auth_governance.test_mg14b_oa_instance WHERE request_id=' + quote(body['request_id']))
                    frozen = json.loads(saved)
                    if frozen['snapshot_hash'] != body['snapshot_hash'] or frozen['request_version'] != body['request_version']:
                        self.send(409, {}); return
                    instance = pg('SELECT instance_id FROM auth_governance.test_mg14b_oa_instance WHERE request_id=' + quote(body['request_id']))
                    self.send(200, {k: frozen[k] for k in ('request_id', 'request_version', 'snapshot_hash')} | {'approval_instance_id': instance})
                except Exception:
                    self.send(500, {'code': 'FIXTURE_FAILED'})

        oa_server = ThreadingHTTPServer(('127.0.0.1', OA_PORT), OaFixture)
        thread = threading.Thread(target=oa_server.serve_forever, daemon=True); thread.start()

        def decide(request_id, outcome='APPROVED'):
            admin_cli('ApprovalStartCli', worker)
            req = urllib.request.Request('http://127.0.0.1:' + str(OA_PORT) + '/decide', data=json.dumps({'request_id': request_id, 'outcome': outcome}).encode(), headers={'Content-Type': 'application/json', 'X-Test-Fixture': fixture_key})
            with urllib.request.urlopen(req, timeout=CALLBACK_TIMEOUT) as response:
                assert response.status == HTTPStatus.OK

        policies = []
        rule = {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES', 'values': ['S1'], 'include_root': False}]}
        for i, role in enumerate(roles[:2]):
            policies.append(request('register fixed policy ' + str(i + 1), requests_root + '/policies', {**partition, 'command_id': uid(), 'role_id': role['id'], 'scope_rule': rule, 'max_duration_seconds': 1800, 'approver_membership_id': manager, 'approver_generation': 1, 'policy_version': i + 1}))
        originals = []
        for i in range(3):
            r = request('self original request ' + str(i + 1), requests_root, {**partition, 'command_id': uid(), 'policy_id': policies[0]['id'], 'valid_from': stamp(0), 'valid_to': stamp(1200), 'reason': 'MG14B原审批' + str(i + 1)}, 202, credential=member_headers)
            decide(r['id']); originals.append(request('read actual approved original ' + str(i + 1), requests_root + '/' + r['id'] + query, credential=member_headers))
        projection = []
        for kind in ('POLICY', 'DIRECTORY'):
            config = run / ('projection-' + kind.lower() + '.properties')
            h.private(config, db + h.props({'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'projection.kind': kind, **graph}))
            projection.append(config)

        def project():
            for config in projection:
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    log = run / ('project-' + uid() + '.log')
                    with log.open('w') as out:
                        result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli', '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(config)], stdout=out, stderr=subprocess.STDOUT, timeout=STARTUP_TIMEOUT)
                    if result.returncode == 0:
                        break
                    if result.returncode != 2 or not re.search(r'result=(BUSY|APPLIED|RETRY_WAIT|RECOVERED)\b', log.read_text()):
                        raise RuntimeError('owned projection failed')
                    time.sleep(.5)
                else:
                    raise RuntimeError('owned projection READY timeout')
        project()
        request('anonymous request denied', requests_root + query, status=401, code='INVALID_CREDENTIAL', credential=[])
        source = originals[0]
        execution = request('original real ACTIVE and exact version', requests_root + '/' + source['id'] + '/execution' + query, credential=member_headers)
        assert execution['display_state'] == 'ACTIVE' and execution['grant_version'] == 1
        compatible = request('self real compatible migration policies', requests_root + '/' + source['id'] + '/role-migration-policies' + query, credential=member_headers)
        assert [p['id'] for p in compatible['items']] == [policies[1]['id']]
        migration = {**partition, 'command_id': uid(), 'policy_id': policies[1]['id'], 'old_grant_id': source['grant_id'], 'expected_version': 1, 'reason': '新版重新审批'}
        request('cannot borrow another beneficiary', requests_root + '/role-migration', migration, 403, 'ACCESS_DENIED')
        request('cannot inject expiry', requests_root + '/role-migration', {**migration, 'valid_to': stamp(3600)}, 400, 'INVALID_ARGUMENT', credential=member_headers)
        replacement = request('persist new fixed approval request', requests_root + '/role-migration', migration, 202, credential=member_headers)
        assert replacement['valid_to'] == source['valid_to'] and replacement['scope_rule'] == source['scope_rule'] and replacement['grant_id'] is None
        assert request('repeat same upgrade command', requests_root + '/role-migration', migration, 202, credential=member_headers)['id'] == replacement['id']
        preview_input = {**partition, 'old_role_id': roles[0]['id'], 'new_role_id': roles[1]['id'], 'grant_ids': [source['grant_id']]}
        assert 'OA_APPROVAL_REQUIRED' in request('new approval required before switch', '/role-migration-preview', preview_input)['items'][0]['reasons']
        decide(replacement['id'])
        waiting = request('fresh APPROVED waits without Grant', requests_root + '/' + replacement['id'] + '/execution' + query, credential=member_headers)
        assert waiting['display_state'] == 'MIGRATION_WAITING' and waiting['grant_id'] is None
        assessed = request('preview binds actual new approval', '/role-migration-preview', preview_input)['items'][0]
        assert assessed['eligible'] and assessed['replacement_request_id'] == replacement['id']
        selection = {'grant_id': source['grant_id'], 'expected_version': 1, 'valid_to': source['valid_to'], 'scope_hash': assessed['scope_hash'], 'replacement_request_id': replacement['id']}
        create = {**partition, 'command_id': uid(), 'old_role_id': roles[0]['id'], 'new_role_id': roles[1]['id'], 'grants': [selection]}
        task = request('freeze explicit new approval task', '/role-migrations', create, 202)
        assert task['items'][0]['source_type'] == 'OA_REQUEST'
        request('same approved task command', '/role-migrations', create, 202)
        def advance(name):
            nonlocal task
            e = task['items'][0]
            task = request(name, '/role-migrations/' + task['id'] + '/advance', {**partition, 'command_id': uid(), 'item_id': e['id'], 'expected_version': e['version']}, 202)
        advance('commit original OA revoke')
        advance('cannot grant before real OA revoke proof'); assert task['items'][0]['new_grant'] is None
        project(); advance('confirm actual old OA revoke'); advance('grant exact fresh OA source')
        next_grant = task['items'][0]['new_grant']; assert next_grant['source_type'] == 'OA_REQUEST' and next_grant['source_id'] == replacement['id'] + ':single:1' and next_grant['valid_to'] == source['valid_to']
        project(); advance('real OA new receipt completes task'); assert task['state'] == 'COMPLETED'
        request('old cancellation preserves already new approved source', requests_root + '/' + source['id'] + '/cancel', {**partition, 'command_id': uid(), 'state_version': source['state_version']}, 202, credential=member_headers)
        assert request('new source remains actual ACTIVE after old cancel', requests_root + '/' + replacement['id'] + '/execution' + query, credential=member_headers)['display_state'] == 'ACTIVE'

        harness = ROOT / 'auth-console/.local/menu-role-governance' / run.name
        harness.mkdir(mode=0o700, parents=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';import {AuthProvider} from 'react-oidc-context';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {MemoryRouter,Routes,Route} from 'react-router-dom';import {oidcSettings,userManager} from '../../../src/auth/oidcConfig';import GovernancePage from '../../../src/pages/GovernancePage';import GovernanceAccessPage from '../../../src/pages/GovernanceAccessPage';import GovernanceRequestsPage from '../../../src/pages/GovernanceRequestsPage';import '../../../src/styles/global.css';
const f=(window as any).__MG14B;await userManager.storeUser(new User({access_token:f.token,token_type:'Bearer',profile:{sub:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+900}));createRoot(document.getElementById('root')!).render(<AuthProvider {...oidcSettings} automaticSilentRenew={false}><QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[f.route]}><Routes><Route element={<GovernancePage/>}><Route path='/governance/roles' element={<GovernanceAccessPage view='roles'/>}/><Route path='/governance/requests' element={<GovernanceRequestsPage/>}/></Route></Routes></MemoryRouter></QueryClientProvider></AuthProvider>);
""")
        payload = {'token': token, 'member_token': other, 'subject': user['id'], 'member_subject': fixture['users']['external']['id'], 'issuer': h.ISSUER, 'partition': partition, 'roles': roles, 'policies': policies, 'originals': originals, 'jar': str(jar), 'worker': str(worker), 'projection': list(map(str, projection)), 'fixture': 'http://127.0.0.1:' + str(OA_PORT), 'fixture_key': fixture_key, 'admin': 'http://127.0.0.1:' + str(ADMIN_PORT), 'ui': 'http://127.0.0.1:' + str(UI_PORT) + '/.local/menu-role-governance/' + run.name + '/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as out:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(UI_PORT), '--strictPort'], cwd=ROOT / 'auth-console', stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(payload['ui'], timeout=1):
                    break
            except OSError:
                if vite.poll() is not None:
                    raise RuntimeError('owned Vite failed')
                time.sleep(.2)
        else:
            raise RuntimeError('owned Vite startup timeout')
        with (run / 'browser.log').open('w') as out:
            result = subprocess.run(['node', '../deploy/governance-menu-oa-migration-ui.mjs', str(run)], cwd=ROOT / 'auth-console', env={**os.environ, 'PLAYWRIGHT_MODULE': str(ROOT.parent / 'commerce-platform/frontend/node_modules/@playwright/test')}, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT)
        if result.returncode:
            raise RuntimeError('MG14B browser failed; private evidence retained')
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': h.CHECKS, 'original_business_grant_writes': 0, 'graph': 'REAL_CAS_PROJECTION', 'oa_engine': 'SIGNED_HTTP_CONTRACT_FIXTURE_NOT_REAL_OA_ENGINE', 'transport_retries': transport_retries}))
        print('PASS MG14B HTTP=' + str(len(h.CHECKS)) + '; evidence=' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        if oa_server is not None:
            oa_server.shutdown(); oa_server.server_close()
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
