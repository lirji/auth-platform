#!/usr/bin/env python3
"""MG17自有专库、真实IdP和图；OA目录仅独立HTTP契约夹具，不写原业务来源。"""
import argparse
import datetime
import hashlib
import http.client
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import struct
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADMIN_PORT, UI_PORT = 21826, 21827
DIRECTORY_ORG, DEPENDENCY_UNAVAILABLE = "ORG", "DEPENDENCY_UNAVAILABLE"
CLI_TIMEOUT, BROWSER_TIMEOUT, MAX_RESPONSE = 40, 240, 2 * 1024 * 1024


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    """每次新建专库；只停止本工具持有的进程，失败和成功证据均保留。"""
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--issuer', choices=['http://localhost:18094'], default='http://localhost:18094')
    parser.add_argument('--identity-fixture', type=Path, default=ROOT / '.local/menu-role-governance/mg12-idp/identity/casdoor.json')
    parser.add_argument('--management-config', type=Path, default=ROOT / '.local/menu-role-governance/mg12-idp/casdoor-isolated/management-client.json')
    args = parser.parse_args()
    h = module('mg17_context', 'governance-context-smoke.py'); h.ISSUER = args.issuer
    run = ROOT / '.local/menu-role-governance' / ('mg17-http-' + uuid.uuid4().hex[:12]); run.mkdir(mode=0o700, parents=True)
    subprocess.run(['python3', 'deploy/governance-test-db.py', '--directory', str(run / 'database')], cwd=ROOT, stdout=subprocess.DEVNULL, check=True, timeout=30)
    db = h.read_private(run / 'database/database.properties')
    database = json.loads(h.read_private(run / 'database/database.json'))['database']
    fixture = json.loads(h.read_private(args.identity_fixture)); ops = json.loads(h.read_private(args.management_config))
    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        for name in ('protocol', 'governance'):
            filename = 'auth-platform-' + name + '-0.1.0-SNAPSHOT.jar'
            if archive.read('BOOT-INF/lib/' + filename) != (ROOT / ('auth-platform-' + name) / 'target' / filename).read_bytes():
                raise RuntimeError('current embedded artifact required')

    def uid(): return str(uuid.uuid4())
    def stamp(seconds=0): return (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=seconds)).replace(microsecond=123456).isoformat().replace('+00:00', 'Z')
    def cli(name, values, allowed=(0,)):
        log = run / (name + '-' + uuid.uuid4().hex[:8] + '.log')
        package = 'com.lrj.authz.admin.governance.' if name == 'ReliableProjectionCli' else 'com.lrj.authz.governance.cli.'
        with log.open('w') as out:
            result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=' + package + name, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, values)], stdout=out, stderr=subprocess.STDOUT, timeout=CLI_TIMEOUT)
        if result.returncode not in allowed: raise RuntimeError('owned CLI failed: ' + name)
        return result.returncode, log
    def pg(sql):
        result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=10)
        if result.returncode: raise RuntimeError('owned SQL assertion failed')
        return result.stdout.strip()

    tenant, owner, manager, recipient = uid(), uid(), uid(), uid()
    app = 'mg17-' + uuid.uuid4().hex[:12]; partition = dict(tenant_id=tenant, application_id=app, environment='test')
    user = fixture['users']['internal']
    person = {'command.id': uid(), 'operator.ref': 'mg17-fixture', 'tenant.id': tenant, 'tenant.code': 'mg17-' + tenant, 'principal.id': owner, 'issuer': h.ISSUER, 'subject': user['id'], 'membership.id': manager, 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg17-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'owner'}
    h.private(run / 'owner.properties', h.props(person)); cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'owner.properties'])
    target = {**person, 'command.id': uid(), 'principal.id': uid(), 'membership.id': recipient, 'subject': fixture['users']['external']['id'], 'source.subject.ref': 'recipient'}
    h.private(run / 'recipient.properties', h.props(target)); cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'recipient.properties'])
    catalog = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'https://mg17.example', 'catalog.operator': 'mg17-fixture', 'catalog.command': uid(), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': user['id']}
    h.private(run / 'catalog.properties', db + h.props(catalog)); cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    caps = [app + '.read']
    h.private(run / 'manifest.json', json.dumps(dict(schema_version='1', application=app, manifest_version=1, capabilities=[dict(code=caps[0], resource_type='store', risk_level='NORMAL')], menus=[])))
    cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    delegation = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': manager, 'access.generation': '1', 'access.capabilities': caps[0], 'access.max-duration-seconds': '3600', 'access.operator': 'mg17-fixture', 'access.command': uid()}
    h.private(run / 'access.properties', db + h.props(delegation)); cli('AccessBootstrapCli', [run / 'access.properties'])
    client = fixture['clients']['management']
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret'], 'read-timeout-ms': '5000'}
    graph = dict(line.split('=', 1) for line in h.read_private(ROOT / '.local/governance/p3/graph/graph.properties').splitlines() if line and not line.startswith('#') and '=' in line)
    authority.update({'graph.http': graph['graph.http'], 'graph.key': graph['graph.key'], 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key'], 'portal.diagnostic.count': '1', 'portal.diagnostic.1.tenant-id': tenant, 'portal.diagnostic.1.application-id': app, 'portal.diagnostic.1.environment': 'test', 'portal.diagnostic.1.membership-id': manager, 'portal.diagnostic.1.generation': '1'})
    h.private(run / 'admin.properties', db + h.props(authority))
    # 目录夹具独立进程边界，仅模拟正式HTTP契约，不声称连接真实OA引擎。
    source = 'mg17-' + uuid.uuid4().hex[:12]; directory_id, credential = uid(), secrets.token_urlsafe(36)
    events = []; ack = {'sequence': 0, 'fingerprint': None}
    def text(v):
        if v is None: return struct.pack('>i', -1)
        b = v.encode(); return struct.pack('>i', len(b)) + b
    def event(kind, aggregate, version, status='ACTIVE', org='1'):
        if kind == DIRECTORY_ORG:
            payload = dict(employee=None, organization=dict(org_id=aggregate, parent_id=None, status=status), snapshot=None)
            raw = text(kind) + text(aggregate) + text(None) + text(status)
        else:
            a = dict(id='1', org_id=org, type='PRIMARY', leader=False, valid_from='2020-01-01', valid_to=None)
            payload = dict(employee=dict(employee_id=aggregate, user_id=target['subject'], status=status, assignments=[a], reporting_lines=[]), organization=None, snapshot=None)
            raw = text(kind) + text(aggregate) + text(target['subject']) + text(status) + struct.pack('>i', 1) + text(a['id']) + text(org) + text('PRIMARY') + b'\x00' + text(a['valid_from']) + text(None) + struct.pack('>i', 0)
        value = dict(schema_version=1, event_id=uid(), source=source, environment='test', source_tenant_ref='1', partition_sequence=len(events) + 1, aggregate_type=kind, aggregate_id=aggregate, aggregate_version=version, occurred_at=stamp(), snapshot_id=None, payload=payload, payload_hash=hashlib.sha256(raw).hexdigest())
        events.append(value)
        return value
    def fingerprint(e):
        raw = struct.pack('>q', e['schema_version']) + b''.join(text(e[k]) for k in ('event_id', 'source', 'environment', 'source_tenant_ref')) + struct.pack('>q', e['partition_sequence']) + text(e['aggregate_type']) + text(e['aggregate_id']) + struct.pack('>q', e['aggregate_version']) + text(e['occurred_at']) + text(None) + text(e['payload_hash'])
        return hashlib.sha256(raw).hexdigest()
    def state(): return dict(source=source, environment='test', source_tenant_ref='1', last_sequence=len(events), acked_sequence=ack['sequence'], acked_fingerprint=ack['fingerprint'], backlog=len(events) - ack['sequence'])
    class DirectoryFixture(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def reply(self, value, status=200):
            data = json.dumps(value).encode(); self.send_response(status); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def do_GET(self):
            if self.headers.get('Authorization') != 'Bearer ' + credential: return self.reply({}, 403)
            url = urllib.parse.urlparse(self.path)
            if url.path.endswith('/status'): return self.reply(state())
            if url.path.endswith('/events'):
                q = urllib.parse.parse_qs(url.query); after, limit = int(q['after_sequence'][0]), int(q['limit'][0]); return self.reply({'events': events[after:after + min(limit, 100)]})
            return self.reply({}, 404)
        def do_POST(self):
            if self.headers.get('Authorization') != 'Bearer ' + credential or not self.path.endswith('/ack'): return self.reply({}, 403)
            n = int(self.headers.get('Content-Length', '0'))
            if n < 1 or n > 1024: return self.reply({}, 400)
            v = json.loads(self.rfile.read(n)); seq = v['sequence']
            if seq < ack['sequence'] or seq > len(events) or v['fingerprint'] != fingerprint(events[seq - 1]): return self.reply({}, 409)
            ack.update(v); return self.reply(state())
    directory_server = ThreadingHTTPServer(('127.0.0.1', 0), DirectoryFixture)
    threading.Thread(target=directory_server.serve_forever, daemon=True).start()
    dc = {'directory.id': directory_id, 'directory.source': source, 'directory.environment': 'test', 'directory.source-tenant-ref': '1', 'directory.tenant-id': tenant, 'directory.issuer': h.ISSUER, 'directory.endpoint': 'http://127.0.0.1:' + str(directory_server.server_port) + '/internal/directory/v1', 'directory.credential': credential, 'directory.operator-ref': 'mg17-fixture', 'directory.business-zone': 'UTC', 'directory.command-id': uid()}
    h.private(run / 'directory.properties', db + h.props(dc))
    vite = None
    try:
        cli('DirectoryImportCli', ['register', run / 'directory.properties']); cli('DirectoryImportCli', ['clock', run / 'directory.properties'])
        event('ORG', '1', 1); event('ORG', '2', 1); event('EMPLOYEE', '1', 1); cli('DirectoryImportCli', ['pull', run / 'directory.properties'])
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True)
        auth = module('mg17_auth', 'governance-access-smoke.py'); token = auth.token(h.ISSUER, fixture, 'management', 'internal'); other = auth.token(h.ISSUER, fixture, 'management', 'external')
        transport_retries = []; cache_headers = []
        def request(name, endpoint, payload=None, status=200, code=None, bearer=token):
            for attempt in range(3):
                connection = http.client.HTTPConnection('127.0.0.1', ADMIN_PORT, timeout=10)
                try:
                    headers = {'Authorization': 'Bearer ' + bearer} if bearer else {}
                    if payload is not None: headers['Content-Type'] = 'application/json'
                    connection.request('GET' if payload is None else 'POST', '/api/governance/v1' + endpoint, None if payload is None else json.dumps(payload), headers)
                    response = connection.getresponse(); actual = response.status; raw = response.read(MAX_RESPONSE + 1)
                    if len(raw) > MAX_RESPONSE: raise RuntimeError('owned HTTP response exceeded bound')
                    result = json.loads(raw) if raw else {}
                    if actual == HTTPStatus.SERVICE_UNAVAILABLE and status != HTTPStatus.SERVICE_UNAVAILABLE and result.get('code') == DEPENDENCY_UNAVAILABLE and attempt < 2:
                        transport_retries.append({'check': name, 'attempt': attempt + 1}); time.sleep(1); continue
                    if actual != status or code and (result.get('code') != code or set(result) != {'code', 'trace_id'}): raise RuntimeError('HTTP assertion failed: ' + name + ' status=' + str(actual))
                    if endpoint.startswith('/access/personnel-impact') and actual == HTTPStatus.OK:
                        cache_headers.append(response.getheader('Cache-Control')); assert cache_headers[-1] == 'no-store'
                    h.CHECKS.append({'check': name, 'result': 'PASS'}); h.private(run / ('check-' + str(len(h.CHECKS)) + '.json'), json.dumps(h.CHECKS)); return result
                finally: connection.close()
        request('strict isolated partition', '/access/enable-strict', {**partition, 'command_id': uid()}, 202)
        role = request('fixed role', '/access/roles', {**partition, 'command_id': uid(), 'role_code': 'reader', 'role_version': 1, 'capabilities': caps})
        query = '/access/personnel-impact?' + urllib.parse.urlencode({**partition, 'membership_id': recipient})
        request('anonymous cannot diagnose', query, status=401, code='INVALID_CREDENTIAL', bearer=None)
        request('ordinary beneficiary cannot diagnose', query, status=403, code='ACCESS_DENIED', bearer=other)
        request('missing target uniform refusal', '/access/personnel-impact?' + urllib.parse.urlencode({**partition, 'membership_id': uid()}), status=403, code='ACCESS_DENIED')
        first = request('real first directory facts', query); assert first['target']['membership_id'] == recipient and first['directory_sources'][0]['source_sync'] == 'UNKNOWN'
        groups = request('real directory groups', '/access/groups?' + urllib.parse.urlencode(partition)); group = next(g for g in groups if g['org_ref'] == '1')
        rule = dict(version=1, resource_type='store', clauses=[dict(kind='SPECIFIED_STORES', values=['S1'], include_root=False)])
        group_grant = request('real dynamic group source', '/access/group-grants', {**partition, 'command_id': uid(), 'group_id': group['id'], 'role_id': role['id'], 'scope_rule': rule, 'source_id': uid(), 'valid_from': stamp(-2), 'valid_to': stamp(1800)}, 202)
        revoked = None
        for n in range(101):
            grant = request('actual personal source ' + str(n + 1), '/access/grants', {**partition, 'command_id': uid(), 'member_id': recipient, 'member_generation': 1, 'role_id': role['id'], 'scope': 'TENANT_ALL', 'source_id': uid(), 'valid_from': stamp(-2), 'valid_to': stamp(1800)}, 202)
            if n == 0:
                revoked = grant['id']; request('actual old source strict revoke', '/access/strict-revoke', {**partition, 'command_id': uid(), 'grant_id': revoked, 'expected_version': 1}, 202)
        for version in range(2, 107): event('EMPLOYEE', '1', version, org='2' if version % 2 == 0 else '1')
        cli('DirectoryImportCli', ['pull', run / 'directory.properties']); cli('DirectoryImportCli', ['pull', run / 'directory.properties'])
        for kind in ('POLICY', 'DIRECTORY'):
            path = run / ('project-' + kind + '.properties'); h.private(path, db + h.props(delegation) + h.props(graph) + 'projection.kind=' + kind + '\n')
            for _ in range(8):
                code, log = cli('ReliableProjectionCli', [path], (0, 2))
                if code == 0: break
                if not any('result=' + value in log.read_text() for value in ('APPLIED', 'BUSY', 'RETRY_WAIT', 'RECOVERED')): raise RuntimeError('unexpected actual projection state')
                time.sleep(.25)
            else: raise RuntimeError('projection not ready within bounded steps')
        current = request('complete paged history after actual move and projection', query); assert len(current['changes']) == 100 and len(current['sources']) == 100 and current['complete']
        second = request('second stable source and change pages', query + '&' + urllib.parse.urlencode({'change_cursor': current['next_change_cursor'], 'source_cursor': current['next_source_cursor']})); assert len(second['changes']) == 8 and len(second['sources']) == 2
        assert pg('SELECT count(*) FROM auth_governance.access_grant') == '102'
        frozen = pg("SELECT jsonb_agg(to_jsonb(g) ORDER BY id)::text FROM auth_governance.access_grant g")
        harness = ROOT / 'auth-console/.local/menu-role-governance' / run.name; harness.mkdir(mode=0o700, parents=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';import {AuthProvider} from 'react-oidc-context';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {MemoryRouter,Routes,Route} from 'react-router-dom';import {oidcSettings,userManager} from '../../../src/auth/oidcConfig';import GovernancePage from '../../../src/pages/GovernancePage';import GovernancePersonnelPage from '../../../src/pages/GovernancePersonnelPage';import {GovernanceGrantDiagnostic} from '../../../src/pages/GovernancePermissionsPage';import '../../../src/styles/global.css';
const f=(window as any).__MG17;const ordinary=new URLSearchParams(location.search).get('kind')==='ordinary';await userManager.storeUser(new User({access_token:ordinary?f.other:f.token,token_type:'Bearer',profile:{sub:ordinary?f.otherSubject:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));const diagnostic=new URLSearchParams(location.search).get('kind')==='diagnostic';createRoot(document.getElementById('root')!).render(<AuthProvider {...oidcSettings} automaticSilentRenew={false}><QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/governance/'+(diagnostic?'diagnostic':'personnel')+'?'+new URLSearchParams({tenant:f.partition.tenant_id,application:f.partition.application_id,environment:f.partition.environment,member:f.member,grant:f.revoked})]}><Routes><Route element={<GovernancePage/>}><Route path='/governance/personnel' element={<GovernancePersonnelPage/>}/><Route path='/governance/diagnostic' element={<GovernanceGrantDiagnostic/>}/></Route></Routes></MemoryRouter></QueryClientProvider></AuthProvider>);""")
        payload = {'token': token, 'other': other, 'subject': user['id'], 'otherSubject': fixture['users']['external']['id'], 'issuer': h.ISSUER, 'partition': partition, 'member': recipient, 'revoked': revoked, 'group': group_grant['id'], 'admin': 'http://127.0.0.1:' + str(ADMIN_PORT), 'ui': 'http://127.0.0.1:' + str(UI_PORT) + '/.local/menu-role-governance/' + run.name + '/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as out: vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(UI_PORT), '--strictPort'], cwd=ROOT / 'auth-console', stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if vite.poll() is not None: raise RuntimeError('owned Vite exited')
            try:
                with urllib.request.urlopen(payload['ui'], timeout=1) as response:
                    if response.status == HTTPStatus.OK: break
            except OSError: time.sleep(.2)
        else: raise RuntimeError('owned Vite not ready')
        with (run / 'browser.log').open('w') as out:
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-personnel-impact-ui.mjs'), str(run)], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT, env={**os.environ, 'PLAYWRIGHT_MODULE': str(ROOT.parent / 'commerce-platform/frontend/node_modules/@playwright/test')})
        if result.returncode: raise RuntimeError('MG17 browser failed; private evidence retained')
        assert pg("SELECT jsonb_agg(to_jsonb(g) ORDER BY id)::text FROM auth_governance.access_grant g") == frozen
        event('EMPLOYEE', '1', 106, 'LEFT', '2'); code, _ = cli('DirectoryImportCli', ['pull', run / 'directory.properties'], (2,)); assert code == 2
        quarantined = request('actual directory conflict remains isolated with unchanged current facts', query); assert quarantined['directory_sources'][0]['quarantined'] and quarantined['directory_sources'][0]['conflict_reasons'] == ['VERSION_CHANGED'] and quarantined['directories'][0]['facts']['status'] == 'ACTIVE'
        request('old cursor refuses changed current basis', query + '&change_cursor=' + urllib.parse.quote(current['next_change_cursor']), status=409, code='VERSION_CONFLICT')
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': h.CHECKS, 'transport_retries': transport_retries, 'cache_headers': cache_headers, 'original_business_grant_writes': 0, 'owned_sources_after_browser': 'UNCHANGED', 'directory_provider': 'HTTP_CONTRACT_FIXTURE_NOT_REAL_OA_ENGINE', 'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}))
        print('PASS: MG17 actual HTTP=' + str(len(h.CHECKS)) + '; evidence=' + str(run))
    finally:
        directory_server.shutdown(); directory_server.server_close()
        if vite is not None: h.stop(vite)
        for process in h.PROCESSES: h.stop(process)


if __name__ == '__main__': main()
