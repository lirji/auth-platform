#!/usr/bin/env python3
"""MG15自有PG／真实IdP／CAS图与页面验证；不访问原业务数据库或迁移原Grant。"""
import argparse
import datetime
import importlib.util
from http import HTTPStatus
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADMIN_PORT, UI_PORT = 21817, 21818
CLI_TIMEOUT, BROWSER_TIMEOUT, STARTUP_TIMEOUT = 40, 240, 30


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    """只创建本轮隔离数据，进程finally回收，秘密仅0600文件。"""
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--issuer', choices=['http://localhost:18090', 'http://localhost:18094'], default='http://localhost:18090')
    parser.add_argument('--identity-fixture', type=Path, default=ROOT / '.local/governance/p2/identity/casdoor.json')
    parser.add_argument('--management-config', type=Path, default=ROOT / '.local/governance/casdoor-isolated/management-client.json')
    args = parser.parse_args()
    h = module('mg15_context', 'governance-context-smoke.py')
    h.ISSUER = args.issuer
    run = ROOT / '.local/menu-role-governance' / ('mg15-http-' + uuid.uuid4().hex[:12])
    run.mkdir(mode=0o700, parents=True)
    subprocess.run(['python3', 'deploy/governance-test-db.py', '--directory', str(run / 'database')], cwd=ROOT, stdout=subprocess.DEVNULL, check=True, timeout=30)
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
        result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=10)
        if result.returncode:
            raise RuntimeError('owned PG assertion failed')
        return result.stdout.strip()

    tenant, owner, manager, recipient = uid(), uid(), uid(), uid()
    app = 'mg15-' + uuid.uuid4().hex[:12]
    partition = dict(tenant_id=tenant, application_id=app, environment='test')
    user = fixture['users']['internal']
    person = {'command.id': uid(), 'operator.ref': 'mg15-fixture', 'tenant.id': tenant, 'tenant.code': 'mg15-' + tenant, 'principal.id': owner, 'issuer': h.ISSUER, 'subject': user['id'], 'membership.id': manager, 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg15-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'owner'}
    h.private(run / 'owner.properties', h.props(person))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'owner.properties'])
    target = {**person, 'command.id': uid(), 'principal.id': uid(), 'membership.id': recipient, 'subject': fixture['users']['external']['id'], 'source.subject.ref': 'recipient'}
    h.private(run / 'recipient.properties', h.props(target))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'recipient.properties'])
    catalog = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'https://mg15.example', 'catalog.operator': 'mg15-fixture', 'catalog.command': uid(), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': user['id']}
    h.private(run / 'catalog.properties', db + h.props(catalog))
    cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    caps = [app + '.read', app + '.write', app + '.merchant']
    manifest = dict(schema_version='1', application=app, manifest_version=1, capabilities=[dict(code=c, resource_type='merchant' if i == 2 else 'store', risk_level='NORMAL') for i, c in enumerate(caps)], menus=[])
    h.private(run / 'manifest.json', json.dumps(manifest))
    cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    delegation = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': manager, 'access.generation': '1', 'access.capabilities': ','.join(caps), 'access.max-duration-seconds': '3600', 'access.operator': 'mg15-fixture', 'access.command': uid()}
    h.private(run / 'access.properties', db + h.props(delegation))
    cli('AccessBootstrapCli', [run / 'access.properties'])
    client = fixture['clients']['management']
    # 本机共享宿主存在实测慢响应；只给本轮隔离目标选择既有配置上限，不改变产品2秒默认。
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret'], 'read-timeout-ms': '5000'}
    graph = dict(line.split('=', 1) for line in h.read_private(ROOT / '.local/governance/p3/graph/graph.properties').splitlines() if line and not line.startswith('#') and '=' in line)
    authority.update({'graph.http': graph['graph.http'], 'graph.key': graph['graph.key'], 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key']})
    h.private(run / 'admin.properties', db + h.props(authority))
    # 第二个真实HUMAN是分区管理者，但不是应用Owner。
    h.private(run / 'manager-access.properties', db + h.props({**delegation, 'access.manager': recipient, 'access.command': uid()}))
    cli('AccessBootstrapCli', [run / 'manager-access.properties'])
    vite = None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True)
        auth = module('mg15_auth', 'governance-access-smoke.py')
        token = auth.token(h.ISSUER, fixture, 'management', 'internal')
        other = auth.token(h.ISSUER, fixture, 'management', 'external')
        h.private(run / 'tokens.json', json.dumps({'management': token, 'external': other}))
        headers = [('Authorization', 'Bearer ' + token)]
        transport_retries = []

        def request(name, endpoint, payload=None, status=200, code=None, credential=headers, raw=None):
            body = raw if raw is not None else None if payload is None else json.dumps(payload).encode()
            for attempt in range(3):
                actual, result = h.request(ADMIN_PORT, '/api/governance/v1' + endpoint, credential, body)
                if actual == HTTPStatus.SERVICE_UNAVAILABLE and status != HTTPStatus.SERVICE_UNAVAILABLE and result.get('code') == 'DEPENDENCY_UNAVAILABLE' and attempt < 2:
                    transport_retries.append({'check': name, 'attempt': attempt + 1, 'status': actual})
                    time.sleep(1)
                    continue
                if actual != status or code and (result.get('code') != code or set(result) != {'code', 'trace_id'}):
                    raise RuntimeError('HTTP assertion failed: ' + name + ' status=' + str(actual))
                h.CHECKS.append({'check': name, 'result': 'PASS'})
                h.private(run / ('checks-' + str(len(h.CHECKS)) + '.json'), json.dumps(h.CHECKS))
                return result

        role_body = {**partition, 'command_id': uid(), 'role_code': 'reader', 'role_version': 1, 'capabilities': caps[:1]}
        role = request('original role', '/access/roles', role_body)
        grant_body = {**partition, 'command_id': uid(), 'member_id': recipient, 'member_generation': 1, 'role_id': role['id'], 'source_id': uid(), 'scope_rule': {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES', 'values': ['S1'], 'include_root': False}]}, 'valid_from': stamp(-1), 'valid_to': stamp(1200)}
        grant = request('original pending source', '/access/scoped-grants', grant_body, 202)
        frozen = {table: pg('SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY ' + key + ")::text,'[]') FROM auth_governance." + table + ' t') for table, key in [('role_version', 'id'), ('access_grant', 'id'), ('grant_scope', 'grant_id'), ('application_manifest', 'version')]}
        path = '/catalog/capability-lifecycle'
        command = dict(application_id=app, capability=caps[0], state='DEPRECATED', expected_version=0, reason='隔离API停止新增', command_id=uid())
        request('anonymous rejects', path, command, 401, 'INVALID_CREDENTIAL', [])
        request('ordinary manager is not Owner', path, command, 403, 'ACCESS_DENIED', [('Authorization', 'Bearer ' + other)])
        request('actor spoof rejected', path, {**command, 'actor': owner}, 400, 'INVALID_ARGUMENT')
        request('unknown state rejected', path, {**command, 'state': 'RETIRED'}, 400, 'INVALID_ARGUMENT')
        missing = dict(command); del missing['expected_version']
        request('missing expected version rejected', path, missing, 400, 'INVALID_ARGUMENT')
        request('blank reason rejected', path, {**command, 'reason': ''}, 400, 'INVALID_ARGUMENT')
        request('unknown capability denied', path, {**command, 'capability': app + '.missing'}, 403, 'ACCESS_DENIED')
        request('same initial ACTIVE cannot create fake version', path, {**command, 'state': 'ACTIVE'}, 409, 'VERSION_CONFLICT')
        committed = request('Owner deprecates current version', path, command)
        assert committed['current']['state'] == 'DEPRECATED' and committed['receipt']['after_version'] == 1
        query = '/access/published-catalog?' + urllib.parse.urlencode(partition)
        directory = request('actual catalog has metadata and distinct non-disabled state', query)
        cap = next(c for c in directory['capabilities'] if c['code'] == caps[0])
        assert cap['lifecycle_state'] == 'DEPRECATED' and cap['lifecycle_version'] == 1 and not cap['grantable'] and not cap['disabled'] and directory['lifecycle_owner']
        request('new role API cannot bypass', '/access/roles', {**role_body, 'command_id': uid(), 'role_version': 2}, 409, 'CAPABILITY_DEPRECATED')
        request('new grant API cannot bypass old role', '/access/scoped-grants', {**grant_body, 'command_id': uid(), 'source_id': uid()}, 409, 'CAPABILITY_DEPRECATED')
        assert request('old successful role replays', '/access/roles', role_body)['id'] == role['id']
        assert request('old successful grant replays unchanged', '/access/scoped-grants', grant_body, 202)['id'] == grant['id']
        assert request('same lifecycle command original receipt', path, command) == committed
        request('different payload same command conflicts', path, {**command, 'reason': '另一原因'}, 409, 'COMMAND_CONFLICT')
        restored = request('explicit Owner restore', path, {**command, 'state': 'ACTIVE', 'expected_version': 1, 'reason': '显式恢复新增', 'command_id': uid()})
        replay = request('old deprecation replay returns current plus immutable receipt', path, command)
        assert replay['current'] == restored['current'] and replay['receipt'] == committed['receipt']
        harness = ROOT / 'auth-console/.local/menu-role-governance' / run.name
        harness.mkdir(mode=0o700, parents=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';import {AuthProvider} from 'react-oidc-context';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {MemoryRouter,Routes,Route} from 'react-router-dom';
import {oidcSettings,userManager} from '../../../src/auth/oidcConfig';import GovernancePage from '../../../src/pages/GovernancePage';import GovernanceCatalogPage from '../../../src/pages/GovernanceCatalogPage';import '../../../src/styles/global.css';
const f=(window as any).__MG15;const manager=new URLSearchParams(location.search).get('kind')==='manager';await userManager.storeUser(new User({access_token:manager?f.other:f.token,token_type:'Bearer',profile:{sub:manager?f.otherSubject:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));
createRoot(document.getElementById('root')!).render(<AuthProvider {...oidcSettings} automaticSilentRenew={false}><QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/governance/catalog?'+new URLSearchParams({tenant:f.partition.tenant_id,application:f.partition.application_id,environment:f.partition.environment,capability:f.capabilities[1]})]}><Routes><Route element={<GovernancePage/>}><Route path='/governance/catalog' element={<GovernanceCatalogPage/>}/></Route></Routes></MemoryRouter></QueryClientProvider></AuthProvider>);
""")
        payload = {'token': token, 'other': other, 'subject': user['id'], 'otherSubject': fixture['users']['external']['id'], 'issuer': h.ISSUER, 'partition': partition, 'capabilities': caps, 'admin': 'http://127.0.0.1:' + str(ADMIN_PORT), 'ui': 'http://127.0.0.1:' + str(UI_PORT) + '/.local/menu-role-governance/' + run.name + '/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as out:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(UI_PORT), '--strictPort'], cwd=ROOT / 'auth-console', stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + STARTUP_TIMEOUT
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
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-menu-capability-lifecycle-ui.mjs'), str(run)], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT)
        if result.returncode:
            raise RuntimeError('MG15 browser failed; sanitized private evidence retained')
        for table, key in [('role_version', 'id'), ('access_grant', 'id'), ('grant_scope', 'grant_id'), ('application_manifest', 'version')]:
            assert frozen[table] == pg('SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY ' + key + ")::text,'[]') FROM auth_governance." + table + ' t')
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': h.CHECKS, 'fixed_original_tables': 'UNCHANGED', 'original_business_grant_writes': 0, 'transport_retries': transport_retries}))
        print('PASS: MG15 real HTTP=' + str(len(h.CHECKS)) + '; evidence=' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
