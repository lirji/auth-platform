#!/usr/bin/env python3
"""MG13自有PG／真实IdP／CAS图与页面验证；不访问原业务数据库或迁移原Grant。"""
import argparse
import base64
import datetime
import importlib.util
from http import HTTPStatus
import json
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
ADMIN_PORT, UI_PORT = 21813, 21816
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
    parser.add_argument('--tokens-file', type=Path)
    parser.add_argument('--group', action='store_true', help='MG14-A独立组来源验收；不修改既有业务库')
    args = parser.parse_args()
    global ADMIN_PORT, UI_PORT
    if args.group:
        ADMIN_PORT, UI_PORT = 21819, 21820
    h = module('mg13_context', 'governance-context-smoke.py')
    h.ISSUER = args.issuer
    run = ROOT / '.local/menu-role-governance' / (('mg14a-http-' if args.group else 'mg13-http-') + uuid.uuid4().hex[:12])
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
    h.private(run / 'admin.properties', db + h.props(authority))
    vite = None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True)
        if args.group:
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
                actual, result = h.request(ADMIN_PORT, '/api/governance/v1/access' + endpoint, credential, body)
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
        group = None
        if args.group:
            # 仅本轮自有数据库夹具；真实目录事件／退组语义另由实际PG＋图IT验证。
            source, group = uid(), uid()
            pg("INSERT INTO auth_governance.directory_source(id,source,environment,source_tenant_ref,tenant_id,issuer,business_zone) VALUES('" + source + "','mg14-fixture','test','1','" + tenant + "','" + h.ISSUER + "','UTC')")
            pg("INSERT INTO auth_governance.directory_group(id,source_id,tenant_id,org_ref,active) VALUES('" + group + "','" + source + "','" + tenant + "','1',true)")
            periods = json.dumps([{'id': '1', 'org_id': '1', 'type': 'PRIMARY', 'valid_from': '2020-01-01', 'valid_to': None}])
            pg("INSERT INTO auth_governance.directory_group_member(group_id,tenant_id,membership_id,generation,current,periods) VALUES('" + group + "','" + tenant + "','" + recipient + "',1,true,'" + periods + "'::jsonb)")
        grants = []
        for i in range(6):
            rule = {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES' if i == 0 else 'TENANT_ALL', 'values': ['S1'] if i == 0 else [], 'include_root': False}]}
            command = {**partition, 'command_id': uid(), 'role_id': roles[0]['id'], 'source_id': uid(), 'scope_rule': rule, 'valid_from': stamp(-2), 'valid_to': stamp(1200)}
            endpoint = '/group-grants' if group and i in (0, 4) else '/scoped-grants'
            command.update({'group_id': group} if endpoint == '/group-grants' else {'member_id': recipient, 'member_generation': 1})
            grants.append(request('fixed scoped source ' + str(i + 1), endpoint, command, HTTPStatus.ACCEPTED))
        if graph['graph.http'] != 'http://127.0.0.1:18544' or not re.fullmatch(r'auth_gov_p1_test_[a-f0-9]{12}', database):
            raise RuntimeError('fixed test graph and owned database required')
        projection = []
        for kind in ('POLICY', 'DIRECTORY'):
            config = run / ('projection-' + kind.lower() + '.properties')
            h.private(config, db + h.props({**delegation, **graph, 'projection.kind': kind}))
            projection.append(config)

        def project():
            for config in projection:
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    log = run / ('project-' + uuid.uuid4().hex[:8] + '.log')
                    with log.open('w') as out:
                        result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli', '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(config)], stdout=out, stderr=subprocess.STDOUT, timeout=30)
                    if result.returncode == 0:
                        break
                    # 沿用真实租约／退避，不改数据库时间或通过重置计数绕过失败。
                    if result.returncode != 2 or not re.search(r'result=(BUSY|APPLIED|RETRY_WAIT|RECOVERED)\b', log.read_text()):
                        raise RuntimeError('owned real projection failed or blocked')
                    time.sleep(.5)
                else:
                    raise RuntimeError('owned real projection did not confirm READY within budget')

        project()
        # 仅不支持来源的隔离夹具，不伪造OA历史批准；该来源不进入迁移任务。
        pg("UPDATE auth_governance.access_grant SET source_type='OA_REQUEST' WHERE id='" + grants[1]['id'] + "'")
        original_roles = pg('SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM auth_governance.role_version r')
        original_scopes = pg('SELECT jsonb_agg(to_jsonb(s) ORDER BY grant_id)::text FROM auth_governance.grant_scope s')
        original_fields = pg("SELECT jsonb_agg(to_jsonb(g)-ARRAY['state','version','zed_token','updated_at'] ORDER BY id)::text FROM auth_governance.access_grant g")
        h.private(run / 'original-fields.json', json.dumps({'roles': original_roles, 'scopes': original_scopes, 'grants': original_fields}))
        preview_input = {**partition, 'old_role_id': roles[0]['id'], 'new_role_id': roles[1]['id'], 'grant_ids': [grants[-1]['id']]}
        report = request('real ACTIVE exact preview', '/role-migration-preview', preview_input)
        selected = report['items'][0]
        create = {**partition, 'command_id': uid(), 'old_role_id': roles[0]['id'], 'new_role_id': roles[1]['id'], 'grants': [{'grant_id': selected['grant']['id'], 'expected_version': selected['grant']['version'], 'valid_to': selected['grant']['valid_to'], 'scope_hash': selected['scope_hash']}]}
        root = '/role-migrations'
        request('anonymous task rejected', root, create, 401, 'INVALID_CREDENTIAL', [])
        request('ordinary member task rejected', root, create, 403, 'ACCESS_DENIED', [('Authorization', 'Bearer ' + other)])
        request('stale expected version rejected', root, {**create, 'grants': [{**create['grants'][0], 'expected_version': 2}]}, 409, 'VERSION_CONFLICT')
        request('duplicate collection rejected', root, {**create, 'grants': create['grants'] * 2}, 400, 'INVALID_ARGUMENT')
        request('actor injection rejected', root, {**create, 'actor': 'owner'}, 400, 'INVALID_ARGUMENT')
        task = request('create freezes but does not revoke', root, create, 202)
        assert task['items'][0]['state'] == 'READY_TO_REVOKE' and task['items'][0]['new_grant'] is None
        assert request('repeat original create command', root, create, 202)['id'] == task['id']
        query = '?' + urllib.parse.urlencode(partition)
        request('foreign environment task detail denied', root + '/' + task['id'] + '?' + urllib.parse.urlencode({**partition, 'environment': 'production'}), status=403, code='ACCESS_DENIED')
        request('duplicate task query rejected', root + query + '&environment=test', status=400, code='INVALID_ARGUMENT')
        request('unknown task query rejected', root + query + '&actor=owner', status=400, code='INVALID_ARGUMENT')

        def advance(name):
            nonlocal task
            item = task['items'][0]
            payload = {**partition, 'command_id': uid(), 'item_id': item['id'], 'expected_version': item['version']}
            task = request(name, root + '/' + task['id'] + '/advance', payload, 202)
            return payload

        first = advance('commit old revoke and checkpoint')
        repeated = request('repeat original advance command', root + '/' + task['id'] + '/advance', first, 202)
        assert repeated['items'][0]['version'] == task['items'][0]['version']
        advance('cannot grant before real revoke confirmation')
        assert task['items'][0]['state'] == 'WAIT_REVOKE_CONFIRM' and task['items'][0]['new_grant'] is None
        project()
        advance('real revocation proof advances checkpoint')
        assert task['items'][0]['state'] == 'READY_TO_GRANT' and task['items'][0]['revocation_operation_id']
        advance('create new source at original deadline')
        new = task['items'][0]['new_grant']
        assert new['state'] == 'PENDING' and new['valid_to'] == selected['grant']['valid_to']
        project()
        advance('real projection proof completes task')
        assert task['state'] == 'COMPLETED' and task['items'][0]['new_operation_id']
        assert request('completed task readable after checkpoint', root + '/' + task['id'] + query)['id'] == task['id']
        if args.group:
            grouped = request('GROUP preview preserves dynamic identity', '/role-migration-preview', {**preview_input, 'grant_ids': [grants[0]['id']]})
            assert grouped['eligible_count'] == 1 and grouped['items'][0]['grant']['group_id'] == group and grouped['items'][0]['grant']['member_id'] is None

        harness = ROOT / 'auth-console/.local/menu-role-governance' / run.name
        harness.mkdir(mode=0o700, parents=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';import {AuthProvider} from 'react-oidc-context';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {MemoryRouter,Routes,Route} from 'react-router-dom';
import {oidcSettings,userManager} from '../../../src/auth/oidcConfig';import GovernancePage from '../../../src/pages/GovernancePage';import GovernanceAccessPage from '../../../src/pages/GovernanceAccessPage';import '../../../src/styles/global.css';
const f=(window as any).__MG13;await userManager.storeUser(new User({access_token:f.token,token_type:'Bearer',profile:{sub:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));
createRoot(document.getElementById('root')!).render(<AuthProvider {...oidcSettings} automaticSilentRenew={false}><QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/governance/roles?'+new URLSearchParams({tenant:f.partition.tenant_id,application:f.partition.application_id,environment:f.partition.environment,q:'reader'})]}><Routes><Route element={<GovernancePage/>}><Route path='/governance/roles' element={<GovernanceAccessPage view='roles'/>}/></Route></Routes></MemoryRouter></QueryClientProvider></AuthProvider>);
""")
        payload = {'token': token, 'subject': user['id'], 'issuer': h.ISSUER, 'partition': partition, 'roles': roles, 'grants': grants, 'group': group, 'jar': str(jar), 'projection': list(map(str, projection)), 'admin': 'http://127.0.0.1:' + str(ADMIN_PORT), 'ui': 'http://127.0.0.1:' + str(UI_PORT) + '/.local/menu-role-governance/' + run.name + '/index.html'}
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
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-menu-role-migration-tasks-ui.mjs'), str(run)], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT)
        if result.returncode:
            raise RuntimeError('MG13 browser failed; sanitized private evidence retained')
        old_ids = ','.join("'" + g['id'] + "'" for g in grants)
        assert original_roles == pg('SELECT jsonb_agg(to_jsonb(r) ORDER BY id)::text FROM auth_governance.role_version r')
        assert original_scopes == pg('SELECT jsonb_agg(to_jsonb(s) ORDER BY grant_id)::text FROM auth_governance.grant_scope s WHERE grant_id IN (' + old_ids + ')')
        assert original_fields == pg("SELECT jsonb_agg(to_jsonb(g)-ARRAY['state','version','zed_token','updated_at'] ORDER BY id)::text FROM auth_governance.access_grant g WHERE id IN (" + old_ids + ')')
        assert pg("SELECT source_type FROM auth_governance.access_grant WHERE id='" + grants[1]['id'] + "'") == 'OA_REQUEST'
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': h.CHECKS, 'fixed_original_fields': 'UNCHANGED', 'original_business_grant_writes': 0, 'graph': 'REAL_CAS_PROJECTION', 'transport_retries': transport_retries}))
        print('PASS: MG13 real HTTP=' + str(len(h.CHECKS)) + '; evidence=' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
