#!/usr/bin/env python3
"""MG12真实HTTP及React页面验收：新建自有PG，仅构造SQL资格，不宣称图ALLOW。"""
import datetime
import argparse
import base64
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADMIN_PORT = 21812
UI_PORT = 21815
CLI_TIMEOUT = 40
BROWSER_TIMEOUT = 180
STARTUP_TIMEOUT = 20


def module(name, file):
    """沿用已有真实PKCE和独有进程工具，不加载原业务数据库。"""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def resume_browser(args, h, auth):
    """恢复已完成HTTP的自有夹具，只读重验页面，不重放角色或Grant写入。"""
    original = args.resume_run.resolve()
    if original.parent != ROOT / '.local/menu-role-governance' or not re.fullmatch(r'mg12-http-[a-f0-9]{12}', original.name):
        raise RuntimeError('owned MG12 run required')
    payload = json.loads(h.read_private(original / 'browser.private.json'))
    if payload['issuer'] != args.issuer:
        raise RuntimeError('fixed fixture issuer required')
    run = original / ('browser-resume-' + uuid.uuid4().hex[:12])
    run.mkdir(mode=0o700)
    fixture = json.loads(h.read_private(args.identity_fixture))
    # PKCE已在独立IdP夹具阶段实测；复验复用仍有效的真实管理Token，避免重复密码登录。
    tokens = json.loads(h.read_private(args.identity_fixture.parent / 'tokens.json'))
    token = tokens['management']['access_token']
    claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))
    if claims.get('iss') != args.issuer or claims.get('sub') != fixture['users']['internal']['id'] or claims.get('aud') != [fixture['clients']['management']['name']] or claims.get('exp', 0) <= time.time() + BROWSER_TIMEOUT:
        raise RuntimeError('owned current management fixture token required')
    payload['token'] = token
    h.private(run / 'browser.private.json', json.dumps(payload))
    database = json.loads(h.read_private(original / 'database/database.json'))['database']
    if not re.fullmatch(r'auth_gov_p1_test_[a-f0-9]{12}', database):
        raise RuntimeError('owned database required')
    before = json.loads(h.read_private(original / 'readonly-before.json'))
    def facts():
        result = {}
        for table in before:
            if table not in ('access_grant', 'grant_scope', 'role_version', 'grant_projection', 'command_record', 'audit_event'):
                raise RuntimeError('fixed readonly table required')
            sql = "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]') FROM auth_governance." + table + ' t'
            query = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=10)
            if query.returncode:
                raise RuntimeError('owned readonly SQL failed')
            result[table] = query.stdout.strip()
        return result
    if facts() != before:
        raise RuntimeError('original fixture facts changed; do not resume')
    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    vite = None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=original / 'admin.properties', access=True, presentation=True, scope=True)
        with (run / 'vite.log').open('w') as out:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(UI_PORT), '--strictPort'], cwd=ROOT / 'auth-console', stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + STARTUP_TIMEOUT
        while time.monotonic() < deadline:
            if vite.poll() is not None:
                raise RuntimeError('owned Vite failed')
            try:
                with urllib.request.urlopen(payload['ui'], timeout=1):
                    break
            except OSError:
                time.sleep(.2)
        else:
            raise RuntimeError('owned Vite startup timeout')
        with (run / 'browser.log').open('w') as out:
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-menu-role-migration-ui.mjs'), str(run)], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT)
        if result.returncode:
            raise RuntimeError('readonly browser recovery failed; private evidence retained')
        after = facts()
        h.private(run / 'readonly-after.json', json.dumps(after))
        if before != after:
            raise RuntimeError('page changed governance data')
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': json.loads(h.read_private(original / 'checks-43.json')), 'readonly_tables': list(before), 'unchanged': True, 'original_grant_writes': 0, 'graph_state': 'UNKNOWN', 'http_source_run': str(original)}))
        print('PASS: MG12 readonly recovery; evidence=' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        for process in h.PROCESSES:
            h.stop(process)


def main():
    """整个运行只写新隔离库；凭据仅进入0700目录下的0600文件。"""
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--issuer', choices=['http://localhost:18090', 'http://localhost:18094'], default='http://localhost:18090')
    parser.add_argument('--identity-fixture', type=Path, default=ROOT / '.local/governance/p2/identity/casdoor.json')
    parser.add_argument('--management-config', type=Path, default=ROOT / '.local/governance/casdoor-isolated/management-client.json')
    parser.add_argument('--resume-run', type=Path)
    args = parser.parse_args()
    h = module('mg12_context', 'governance-context-smoke.py')
    h.ISSUER = args.issuer
    auth = module('mg12_auth', 'governance-access-smoke.py')
    if args.resume_run:
        resume_browser(args, h, auth)
        return
    run = ROOT / '.local/menu-role-governance' / ('mg12-http-' + uuid.uuid4().hex[:12])
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
    app = 'mg12-' + uuid.uuid4().hex[:12]
    partition = dict(tenant_id=tenant, application_id=app, environment='test')
    user = fixture['users']['internal']
    person = {'command.id': uid(), 'operator.ref': 'mg12-fixture', 'tenant.id': tenant, 'tenant.code': 'mg12-' + tenant, 'principal.id': owner, 'issuer': h.ISSUER, 'subject': user['id'], 'membership.id': manager, 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg12-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'owner'}
    h.private(run / 'owner.properties', h.props(person))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'owner.properties'])
    target = {**person, 'command.id': uid(), 'principal.id': uid(), 'membership.id': recipient, 'subject': fixture['users']['external']['id'], 'source.subject.ref': 'recipient'}
    h.private(run / 'recipient.properties', h.props(target))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'recipient.properties'])
    catalog = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'https://mg12.example', 'catalog.operator': 'mg12-fixture', 'catalog.command': uid(), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': user['id']}
    h.private(run / 'catalog.properties', db + h.props(catalog))
    cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    caps = [app + '.read', app + '.write', app + '.merchant']
    manifest = dict(schema_version='1', application=app, manifest_version=1, capabilities=[dict(code=c, resource_type='merchant' if i == 2 else 'store', risk_level='NORMAL') for i, c in enumerate(caps)], menus=[])
    h.private(run / 'manifest.json', json.dumps(manifest))
    cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    delegation = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': manager, 'access.generation': '1', 'access.capabilities': ','.join(caps), 'access.max-duration-seconds': '3600', 'access.operator': 'mg12-fixture', 'access.command': uid()}
    h.private(run / 'access.properties', db + h.props(delegation))
    cli('AccessBootstrapCli', [run / 'access.properties'])
    client = fixture['clients']['management']
    # 本机共享宿主存在实测慢响应；只给本轮隔离目标选择既有配置上限，不改变产品2秒默认。
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret'], 'read-timeout-ms': '5000'}
    graph = dict(line.split('=', 1) for line in h.read_private(ROOT / '.local/governance/p3/graph/graph.properties').splitlines() if line and not line.startswith('#') and '=' in line)
    authority.update({'graph.http': graph['graph.http'], 'graph.key': graph['graph.key'], 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key']})
    h.private(run / 'admin.properties', db + h.props(authority))
    vite = None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True)
        token = auth.token(h.ISSUER, fixture, 'management', 'internal')
        other = auth.token(h.ISSUER, fixture, 'management', 'external')
        headers = [('Authorization', 'Bearer ' + token)]

        def request(name, endpoint, payload=None, status=200, code=None, credential=headers, raw=None):
            body = raw if raw is not None else None if payload is None else json.dumps(payload).encode()
            result = h.expect(name, ADMIN_PORT, '/api/governance/v1/access' + endpoint, credential, body, status, code)
            h.private(run / ('checks-' + str(len(h.CHECKS)) + '.json'), json.dumps(h.CHECKS))
            return result

        request('strict isolated partition', '/enable-strict', {**partition, 'command_id': uid()}, 202)
        roles = [request('fixed role version ' + str(i + 1), '/roles', {**partition, 'command_id': uid(), 'role_code': 'reader', 'role_version': i + 1, 'capabilities': selected}) for i, selected in enumerate((caps[:2], caps[:1], [caps[0], caps[2]]))]
        grants = []
        for i in range(23):
            rule = {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES' if i == 0 else 'TENANT_ALL', 'values': ['S1'] if i == 0 else [], 'include_root': False}]}
            grants.append(request('fixed scoped source ' + str(i + 1), '/scoped-grants', {**partition, 'command_id': uid(), 'member_id': recipient, 'member_generation': 1, 'role_id': roles[0]['id'], 'source_id': uid(), 'scope_rule': rule, 'valid_from': stamp(-2), 'valid_to': stamp(900)}, 202))
        # SQL状态只用于只读资格验证，保留实际投影UNKNOWN，未调用图写接口。
        pg("UPDATE auth_governance.access_grant SET state='ACTIVE',zed_token='mg12-sql-fixture'; UPDATE auth_governance.access_grant SET source_type='OA_REQUEST' WHERE id='" + grants[1]['id'] + "';")
        tables = ('access_grant', 'grant_scope', 'role_version', 'grant_projection', 'command_record', 'audit_event')

        def facts():
            return {table: pg("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]') FROM auth_governance." + table + ' t') for table in tables}

        before = facts()
        h.private(run / 'readonly-before.json', json.dumps(before))
        preview = {**partition, 'old_role_id': roles[0]['id'], 'new_role_id': roles[1]['id'], 'grant_ids': [grants[0]['id']]}
        endpoint = '/role-migration-preview'
        query = '/role-migration-grants?' + urllib.parse.urlencode({**partition, 'old_role_id': roles[0]['id']})
        first = request('bounded twenty reference rows', query)
        assert len(first['items']) == 20 and first['next_cursor']
        last = request('stable next reference cursor', query + '&after=' + first['next_cursor'])
        assert len(last['items']) == 3 and last['next_cursor'] is None
        r = request('exact scope source deadline shrinking preview', endpoint, preview)
        assert r['eligible_count'] == 1 and r['projection_status'] == 'UNKNOWN' and r['items'][0]['scope_rule']['clauses'][0]['values'] == ['S1'] and r['items'][0]['grant']['valid_to'] == grants[0]['valid_to']
        request('anonymous preview denied', endpoint, preview, 401, 'INVALID_CREDENTIAL', [])
        request('different HUMAN preview denied', endpoint, preview, 403, 'ACCESS_DENIED', [('Authorization', 'Bearer ' + other)])
        request('foreign partition denied', endpoint, {**preview, 'environment': 'production'}, 403, 'ACCESS_DENIED')
        request('missing grant whole batch denied', endpoint, {**preview, 'grant_ids': [grants[0]['id'], uid()]}, 403, 'ACCESS_DENIED')
        request('duplicate selected UUID rejected', endpoint, {**preview, 'grant_ids': [grants[0]['id']] * 2}, 400, 'INVALID_ARGUMENT')
        request('more than fifty rejected', endpoint, {**preview, 'grant_ids': [uid() for _ in range(51)]}, 400, 'INVALID_ARGUMENT')
        request('actor injection rejected', endpoint, {**preview, 'operator': 'victim'}, 400, 'INVALID_ARGUMENT')
        request('duplicate JSON field rejected', endpoint, raw=(json.dumps(preview)[:-1] + ',"environment":"test"}').encode(), status=400, code='INVALID_ARGUMENT')
        request('trailing JSON rejected', endpoint, raw=json.dumps(preview).encode() + b' {}', status=400, code='INVALID_ARGUMENT')
        request('duplicate query rejected', query + '&environment=test', status=400, code='INVALID_ARGUMENT')
        request('unknown query rejected', query + '&operator=victim', status=400, code='INVALID_ARGUMENT')
        grown = request('added capability requires original authorization', endpoint, {**preview, 'new_role_id': roles[2]['id']})
        assert not grown['items'][0]['eligible'] and 'REQUIRES_SEPARATE_AUTHORIZATION' in grown['items'][0]['reasons']
        approval = request('OA source never converted into DIRECT', endpoint, {**preview, 'grant_ids': [grants[1]['id']]})
        assert 'UNSUPPORTED_SOURCE' in approval['items'][0]['reasons'] and approval['items'][0]['grant']['source_type'] == 'OA_REQUEST'
        harness = ROOT / 'auth-console/.local/menu-role-governance' / run.name
        harness.mkdir(mode=0o700, parents=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';import {AuthProvider} from 'react-oidc-context';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {MemoryRouter,Routes,Route} from 'react-router-dom';
import {oidcSettings,userManager} from '../../../src/auth/oidcConfig';import GovernancePage from '../../../src/pages/GovernancePage';import GovernanceAccessPage from '../../../src/pages/GovernanceAccessPage';import '../../../src/styles/global.css';
const f=(window as any).__MG12;await userManager.storeUser(new User({access_token:f.token,token_type:'Bearer',profile:{sub:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));
createRoot(document.getElementById('root')!).render(<AuthProvider {...oidcSettings} automaticSilentRenew={false}><QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/governance/roles?'+new URLSearchParams({tenant:f.partition.tenant_id,application:f.partition.application_id,environment:f.partition.environment,q:'reader'})]}><Routes><Route element={<GovernancePage/>}><Route path='/governance/roles' element={<GovernanceAccessPage view='roles'/>}/></Route></Routes></MemoryRouter></QueryClientProvider></AuthProvider>);
""")
        payload = {'token': token, 'subject': user['id'], 'issuer': h.ISSUER, 'partition': partition, 'roles': roles, 'grants': grants, 'admin': 'http://127.0.0.1:' + str(ADMIN_PORT), 'ui': 'http://127.0.0.1:' + str(UI_PORT) + '/.local/menu-role-governance/' + run.name + '/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as out:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(UI_PORT), '--strictPort'], cwd=ROOT / 'auth-console', stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + STARTUP_TIMEOUT
        while time.monotonic() < deadline:
            if vite.poll() is not None:
                raise RuntimeError('owned Vite failed')
            try:
                with urllib.request.urlopen(payload['ui'], timeout=1):
                    break
            except OSError:
                time.sleep(.2)
        else:
            raise RuntimeError('owned Vite startup timeout')
        with (run / 'browser.log').open('w') as out:
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-menu-role-migration-ui.mjs'), str(run)], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT)
        if result.returncode:
            raise RuntimeError('MG12 browser failed; private evidence retained')
        after = facts()
        h.private(run / 'readonly-after.json', json.dumps(after))
        if before != after:
            raise RuntimeError('read-only page changed persisted governance facts')
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': h.CHECKS, 'readonly_tables': list(tables), 'unchanged': True, 'original_grant_writes': 0, 'graph_state': 'UNKNOWN'}))
        print('PASS: MG12 real HTTP=' + str(len(h.CHECKS)) + '; evidence=' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
