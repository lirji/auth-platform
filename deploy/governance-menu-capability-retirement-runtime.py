#!/usr/bin/env python3
"""MG16自有PG／真实IdP／CAS图与页面验证；不访问原业务数据库或迁移原Grant。"""
import argparse
import base64
import re
import secrets
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
ADMIN_PORT, UI_PORT = 21824, 21825
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
    h = module('mg16_context', 'governance-context-smoke.py')
    h.ISSUER = args.issuer
    run = ROOT / '.local/menu-role-governance' / ('mg16-http-' + uuid.uuid4().hex[:12])
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
        return log

    def pg(sql):
        result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=10)
        if result.returncode:
            raise RuntimeError('owned PG assertion failed')
        return result.stdout.strip()

    tenant, owner, manager, recipient = uid(), uid(), uid(), uid()
    app = 'mg16-' + uuid.uuid4().hex[:12]
    partition = dict(tenant_id=tenant, application_id=app, environment='test')
    user = fixture['users']['internal']
    person = {'command.id': uid(), 'operator.ref': 'mg16-fixture', 'tenant.id': tenant, 'tenant.code': 'mg16-' + tenant, 'principal.id': owner, 'issuer': h.ISSUER, 'subject': user['id'], 'membership.id': manager, 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg16-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'owner'}
    h.private(run / 'owner.properties', h.props(person))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'owner.properties'])
    target = {**person, 'command.id': uid(), 'principal.id': uid(), 'membership.id': recipient, 'subject': fixture['users']['external']['id'], 'source.subject.ref': 'recipient'}
    h.private(run / 'recipient.properties', h.props(target))
    cli('GovernanceCli', ['bootstrap', run / 'database/database.properties', run / 'recipient.properties'])
    catalog = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'https://mg16.example', 'catalog.operator': 'mg16-fixture', 'catalog.command': uid(), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': user['id']}
    h.private(run / 'catalog.properties', db + h.props(catalog))
    cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    caps = [app + '.read', app + '.write', app + '.merchant']
    manifest = dict(schema_version='1', application=app, manifest_version=1, capabilities=[dict(code=c, resource_type='merchant' if i == 2 else 'store', risk_level='NORMAL') for i, c in enumerate(caps)], menus=[])
    h.private(run / 'manifest.json', json.dumps(manifest))
    cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    delegation = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': manager, 'access.generation': '1', 'access.capabilities': ','.join(caps), 'access.max-duration-seconds': '3600', 'access.operator': 'mg16-fixture', 'access.command': uid()}
    h.private(run / 'access.properties', db + h.props(delegation))
    cli('AccessBootstrapCli', [run / 'access.properties'])
    client = fixture['clients']['management']
    # 本机共享宿主存在实测慢响应；只给本轮隔离目标选择既有配置上限，不改变产品2秒默认。
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret'], 'read-timeout-ms': '5000'}
    graph = dict(line.split('=', 1) for line in h.read_private(ROOT / '.local/governance/p3/graph/graph.properties').splitlines() if line and not line.startswith('#') and '=' in line)
    authority.update({'graph.http': graph['graph.http'], 'graph.key': graph['graph.key'], 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key']})
    signer = run / 'proof-fixture.mjs'
    signer.write_text("""import fs from 'node:fs';import crypto from 'node:crypto';const dir=process.argv[2];if(process.argv[3]==='generate'){const k=crypto.generateKeyPairSync('ed25519');fs.writeFileSync(dir+'/proof-key.private',k.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});fs.writeFileSync(dir+'/proof-public.txt',k.publicKey.export({type:'spki',format:'der'}).toString('base64'),{mode:0o600});}else{const p=fs.readFileSync(dir+'/proof-payload.json'),k=fs.readFileSync(dir+'/proof-key.private');fs.writeFileSync(dir+'/proof.json',JSON.stringify({payload:p.toString('base64'),signature:crypto.sign(null,p,k).toString('base64')}),{mode:0o600});}""")
    subprocess.run(['node', str(signer), str(run), 'generate'], check=True, timeout=CLI_TIMEOUT)
    authority.update({'catalog.retirement.count': '1', 'catalog.retirement.1.application-id': app, 'catalog.retirement.1.deployment-targets': 'api-a,api-b', 'catalog.retirement.1.public-key': (run / 'proof-public.txt').read_text(), 'catalog.retirement.1.proof-file': str(run / 'proof.json')})
    authority.update({'portal.diagnostic.count': '1', 'portal.diagnostic.1.tenant-id': tenant, 'portal.diagnostic.1.application-id': app, 'portal.diagnostic.1.environment': 'test', 'portal.diagnostic.1.membership-id': recipient, 'portal.diagnostic.1.generation': '1'})
    # 申请接口沿用宿主签名回调配置；本轮只取消未启动请求，不伪造OA批准或启动外部流程。
    authority.update({'approval.tenant': tenant, 'approval.application': app, 'approval.environment': 'test', 'approval.inbound-key': secrets.token_urlsafe(36), 'approval.allow-loopback-http': 'true'})
    h.private(run / 'admin.properties', db + h.props(authority))
    # 第二个真实HUMAN是分区管理者，但不是应用Owner。
    h.private(run / 'manager-access.properties', db + h.props({**delegation, 'access.manager': recipient, 'access.capabilities': caps[0], 'access.command': uid()}))
    cli('AccessBootstrapCli', [run / 'manager-access.properties'])
    vite = None
    try:
        h.start(jar, ADMIN_PORT, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=True, scope=True, requests=True)
        auth = module('mg16_auth', 'governance-access-smoke.py')
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

        request('strict isolated partition', '/access/enable-strict', {**partition, 'command_id': uid()}, 202)
        role = request('historical target role', '/access/roles', {**partition, 'command_id': uid(), 'role_code': 'old-write', 'role_version': 1, 'capabilities': [caps[1]]})
        read_role = request('future source role', '/access/roles', {**partition, 'command_id': uid(), 'role_code': 'future-read', 'role_version': 1, 'capabilities': [caps[0]]})
        request('future source from actual API', '/access/grants', {**partition, 'command_id': uid(), 'member_id': recipient, 'member_generation': 1, 'role_id': read_role['id'], 'scope': 'TENANT_ALL', 'source_id': uid(), 'valid_from': stamp(600), 'valid_to': stamp(1200)}, 202)
        policy = request('enabled target policy', '/requests/policies', {**partition, 'command_id': uid(), 'role_id': role['id'], 'scope_rule': {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES', 'values': ['S1'], 'include_root': False}]}, 'max_duration_seconds': 1800, 'approver_membership_id': manager, 'approver_generation': 1, 'policy_version': 1})
        new_request = request('actual unfinished request', '/requests', {**partition, 'command_id': uid(), 'policy_id': policy['id'], 'valid_from': stamp(0), 'valid_to': stamp(1200), 'reason': '退役前在途申请'}, 202, credential=[('Authorization', 'Bearer ' + other)])
        query = '/catalog/capability-retirement?' + urllib.parse.urlencode({'application_id': app, 'capability': caps[1]})
        detail = '/access/capability-retirement?' + urllib.parse.urlencode({**partition, 'capability': caps[1]})
        request('anonymous summary denied', query, status=401, code='INVALID_CREDENTIAL', credential=[])
        request('ordinary manager not Owner', query, status=403, code='ACCESS_DENIED', credential=[('Authorization', 'Bearer ' + other)])
        request('Owner has no independent personnel diagnostics', detail, status=403, code='ACCESS_DENIED')
        references = request('independent diagnostic source details', detail, credential=[('Authorization', 'Bearer ' + other)])
        assert any(r['kind'] == 'POLICY' and r['blocking'] for r in references['items'])
        request('Owner deprecates fixed target', '/catalog/capability-lifecycle', {'application_id': app, 'capability': caps[1], 'state': 'DEPRECATED', 'expected_version': 0, 'reason': '先停止新增', 'command_id': uid()})
        report = request('all references and unavailable runtime proof', query)
        assert not report['eligible'] and report['proof_state'] == 'UNPROVEN' and any(c['kind'] == 'REQUEST' and c['blocking'] == 1 for c in report['counts'])
        command = {'application_id': app, 'capability': caps[1], 'expected_version': 1, 'basis_hash': report['basis_hash'], 'reason': '固定完整退出', 'command_id': uid()}
        request('remaining references block normal retirement', '/catalog/capability-retire', command, 409, 'RETIREMENT_BLOCKED')
        request('actor cannot be supplied', '/catalog/capability-retire', {**command, 'actor': owner}, 400, 'INVALID_ARGUMENT')
        request('reason cannot be empty', '/catalog/capability-retire', {**command, 'reason': ''}, 400, 'INVALID_ARGUMENT')
        request('ordinary manager cannot retire', '/catalog/capability-retire', command, 403, 'ACCESS_DENIED', [('Authorization', 'Bearer ' + other)])
        request('beneficiary cancels pending request', '/requests/' + new_request['id'] + '/cancel', {**partition, 'command_id': uid(), 'state_version': new_request['state_version']}, 202, credential=[('Authorization', 'Bearer ' + other)])

        def exit_reference(kind, target_id):
            # 受控平台配置单向退出，不用初始化接口冒充修改，不手写正式业务表。
            config = {'retirement.exit.tenant-id': tenant, 'retirement.exit.application-id': app, 'retirement.exit.environment': 'test', 'retirement.exit.kind': kind, 'retirement.exit.target-id': target_id, 'retirement.exit.operator': 'mg16-isolated-ops', 'retirement.exit.command-id': uid(), 'retirement.exit.reason': '隔离退出引用验收'}
            file = run / ('exit-' + kind + '.properties')
            h.private(file, db + h.props(config))
            log = cli('RetirementReferenceExitCli', ['inspect', file])
            digest = next(line for line in log.read_text().splitlines() if re.fullmatch('[a-f0-9]{64}', line))
            config['retirement.exit.expected-hash'] = digest
            file = run / ('exit-command-' + kind + '.properties')
            h.private(file, db + h.props(config))
            cli('RetirementReferenceExitCli', ['exit', file])
            cli('RetirementReferenceExitCli', ['exit', file])
            h.CHECKS.append({'check': 'controlled ' + kind + ' exact inspect and exit replay', 'result': 'PASS'})

        exit_reference('POLICY', policy['id'])
        exit_reference('DELEGATION', manager)
        report = request('zero blocking references still require independent proof', query)
        assert all(c['blocking'] == 0 for c in report['counts']) and not report['eligible']
        request('old basis cannot retire after reference changes', '/catalog/capability-retire', command, 409, 'VERSION_CONFLICT')
        owner_view = request('Owner view survives last delegation exit', '/catalog/owner-view?' + urllib.parse.urlencode({'application_id': app}))
        assert owner_view['application_id'] == app and all(not c['grantable'] for c in owner_view['capabilities'])
        request('Owner cannot bypass absent management delegation', '/access/published-catalog?' + urllib.parse.urlencode(partition), status=403, code='ACCESS_DENIED')
        request('ordinary manager cannot read Owner view', '/catalog/owner-view?' + urllib.parse.urlencode({'application_id': app}), status=403, code='ACCESS_DENIED', credential=[('Authorization', 'Bearer ' + other)])
        target = {'target_id': 'api-a', 'commit': 'a' * 40, 'artifact_hash': 'b' * 64, 'api_mapping_hash': 'c' * 64, 'complete': True, 'capability_references': 0}
        proof = {'schema_version': 1, 'application_id': app, 'capability': caps[1], 'manifest_version': report['manifest_version'], 'content_hash': report['content_hash'], 'presentation_hash': report['presentation_hash'], 'lifecycle_version': 1, 'checked_at': stamp(-1), 'valid_until': stamp(298), 'deployments': [target, {**target, 'target_id': 'api-b'}]}
        h.private(run / 'proof-payload.json', json.dumps(proof))
        subprocess.run(['node', str(signer), str(run), 'sign'], check=True, timeout=CLI_TIMEOUT)
        report = request('signed independent contract fixture accepted', query)
        assert report['eligible'] and report['proof_state'] == 'PROVEN'
        frozen = {table: pg('SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY ' + key + ")::text,'[]') FROM auth_governance." + table + ' t') for table, key in [('role_version', 'id'), ('access_grant', 'id'), ('application_manifest', 'version')]}
        harness = ROOT / 'auth-console/.local/menu-role-governance' / run.name
        harness.mkdir(mode=0o700, parents=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';import {AuthProvider} from 'react-oidc-context';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {MemoryRouter,Routes,Route} from 'react-router-dom';import {oidcSettings,userManager} from '../../../src/auth/oidcConfig';import GovernancePage from '../../../src/pages/GovernancePage';import GovernanceCatalogPage from '../../../src/pages/GovernanceCatalogPage';import '../../../src/styles/global.css';
const f=(window as any).__MG16;const manager=new URLSearchParams(location.search).get('kind')==='manager';await userManager.storeUser(new User({access_token:manager?f.other:f.token,token_type:'Bearer',profile:{sub:manager?f.otherSubject:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));createRoot(document.getElementById('root')!).render(<AuthProvider {...oidcSettings} automaticSilentRenew={false}><QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/governance/catalog?'+new URLSearchParams({tenant:f.partition.tenant_id,application:f.partition.application_id,environment:f.partition.environment,capability:f.capabilities[1]})]}><Routes><Route element={<GovernancePage/>}><Route path='/governance/catalog' element={<GovernanceCatalogPage/>}/></Route></Routes></MemoryRouter></QueryClientProvider></AuthProvider>);""")
        payload = {'token': token, 'other': other, 'subject': user['id'], 'otherSubject': fixture['users']['external']['id'], 'issuer': h.ISSUER, 'partition': partition, 'capabilities': caps, 'admin': 'http://127.0.0.1:' + str(ADMIN_PORT), 'ui': 'http://127.0.0.1:' + str(UI_PORT) + '/.local/menu-role-governance/' + run.name + '/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as out:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', str(UI_PORT), '--strictPort'], cwd=ROOT / 'auth-console', stdout=out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + STARTUP_TIMEOUT
        while time.monotonic() < deadline:
            if vite.poll() is not None: raise RuntimeError('owned Vite exited')
            try:
                with urllib.request.urlopen(payload['ui'], timeout=1) as response:
                    if response.status == HTTPStatus.OK: break
            except OSError: time.sleep(.2)
        else: raise RuntimeError('owned Vite not ready')
        with (run / 'browser.log').open('w') as out:
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-menu-capability-retirement-ui.mjs'), str(run)], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, timeout=BROWSER_TIMEOUT, env={**os.environ, 'PLAYWRIGHT_MODULE': str(ROOT.parent / 'commerce-platform/frontend/node_modules/@playwright/test')})
        if result.returncode: raise RuntimeError('MG16 browser failed; sanitized private evidence retained')
        current = request('actual retired metadata after browser', query)
        assert current['lifecycle_state'] == 'RETIRED' and current['lifecycle_version'] == 2
        request('normal lifecycle cannot restore retired tombstone', '/catalog/capability-lifecycle', {'application_id': app, 'capability': caps[1], 'state': 'ACTIVE', 'expected_version': 2, 'reason': '不允许恢复', 'command_id': uid()}, 409, 'VERSION_CONFLICT')
        for table, key in [('role_version', 'id'), ('access_grant', 'id'), ('application_manifest', 'version')]:
            assert frozen[table] == pg('SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY ' + key + ")::text,'[]') FROM auth_governance." + table + ' t')
        assert pg('SELECT count(*) FROM auth_governance.capability_retirement_evidence') == '1'
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': h.CHECKS, 'fixed_original_tables': 'UNCHANGED', 'original_business_grant_writes': 0, 'transport_retries': transport_retries, 'runtime_verifier': 'SIGNED_CONTRACT_FIXTURE_NOT_DEPLOYMENT_SCAN'}))
        print('PASS: MG16 real HTTP=' + str(len(h.CHECKS)) + '; evidence=' + str(run))
    finally:
        if vite is not None: h.stop(vite)
        for process in h.PROCESSES: h.stop(process)

if __name__ == '__main__':
    main()
