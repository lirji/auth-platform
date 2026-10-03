#!/usr/bin/env python3
"""MG02真实Owner HTTP与差异弹层验收；仅新增隔离PG应用，不发布原电商目录或改Grant。"""
import datetime
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CLI_TIMEOUT_SECONDS = 40
BROWSER_TIMEOUT_SECONDS = 120
UI_STARTUP_TIMEOUT_SECONDS = 20


def module(name, file):
    """复用已有私密配置、真实PKCE和独有进程约束，避免建立第二套认证测试协议。"""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main(impact=False, history=False, guard=False):
    """本次夹具只写随机应用／企业；所有凭据和日志留在忽略的0600证据目录。"""
    impact = impact or guard
    os.umask(0o077)
    h = module('context', 'governance-context-smoke.py')
    auth = module('access_smoke', 'governance-access-smoke.py')
    run = ROOT / '.local/menu-role-governance' / (('mg05-' if guard else 'mg04-' if history else 'mg03-' if impact else 'mg02-') + uuid.uuid4().hex[:12])
    run.mkdir(parents=True, mode=0o700)
    fixture = json.loads(h.read_private(ROOT / '.local/governance/p2/identity/casdoor.json'))
    ops = json.loads(h.read_private(ROOT / '.local/governance/casdoor-isolated/management-client.json'))
    db = h.read_private(ROOT / '.local/governance/database.properties')
    if not re.search(r'^jdbc.url=jdbc:postgresql://(?:127\.0\.0\.1|localhost):[0-9]+/auth_gov_p1_test_[a-z0-9_]+$', db, re.M):
        raise RuntimeError('owned isolated PG required')
    app, tenant, member = 'mg02-' + uuid.uuid4().hex[:12], str(uuid.uuid4()), str(uuid.uuid4())
    user = fixture['users']['internal']
    owner = str(uuid.uuid5(uuid.NAMESPACE_URL, 'p2:' + user['id']))
    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    # 增量repackage曾复用旧依赖；实际HTTP必须先绑定完整新治理JAR，不能仅看构建退出码。
    with zipfile.ZipFile(jar) as packaged:
        if packaged.read('BOOT-INF/lib/auth-platform-governance-0.1.0-SNAPSHOT.jar') != (ROOT / 'auth-platform-governance/target/auth-platform-governance-0.1.0-SNAPSHOT.jar').read_bytes():
            raise RuntimeError('stale nested governance artifact; repackage with maven.jar.forceCreation=true')

    def cli(name, args):
        """受控引导只允许本次夹具；错误细节不回显配置或底层连接信息。"""
        with (run / (name + '-' + uuid.uuid4().hex + '.log')).open('w') as output:
            result = subprocess.run(['java', '-Dloader.main=com.lrj.authz.governance.cli.' + name, '-cp', str(jar),
                                     'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, args)],
                                    stdout=output, stderr=subprocess.STDOUT, timeout=CLI_TIMEOUT_SECONDS)
        if result.returncode:
            raise RuntimeError('owned CLI failed: ' + name)

    values = {'command.id': str(uuid.uuid4()), 'operator.ref': 'mg02-fixture', 'tenant.id': tenant, 'tenant.code': 'mg02-' + tenant,
              'principal.id': owner, 'issuer': h.ISSUER, 'subject': user['id'], 'membership.id': member,
              'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg02-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': 'owner'}
    h.private(run / 'bootstrap.properties', h.props(values))
    cli('GovernanceCli', ['bootstrap', ROOT / '.local/governance/database.properties', run / 'bootstrap.properties'])
    catalog = {'catalog.application': app, 'catalog.owner-principal': owner, 'catalog.entry-origin': 'https://mg02.example',
               'catalog.operator': 'mg02-fixture', 'catalog.command': str(uuid.uuid4()), 'catalog.owner-issuer': h.ISSUER, 'catalog.owner-subject': user['id']}
    h.private(run / 'catalog.properties', db + h.props(catalog))
    cli('CatalogCli', ['register', run / 'catalog.properties', 'configured'])
    def menu(code, parent, route, any_of, label, position):
        return dict(code=code, parent=parent, route=route, any_of=any_of, label=label, position=position)
    manifest = dict(schema_version='1', application=app, manifest_version=1,
                    capabilities=[dict(code=app + '.read', resource_type='store', risk_level='NORMAL'), dict(code=app + '.write', resource_type='store', risk_level='HIGH')],
                    menus=[menu('group.a', None, None, [], '商品经营', 0), menu('group.b', None, None, [], '会员经营', 1),
                           menu('page.products', 'group.a', '/products', [app + '.read'], '商品列表', 2),
                           menu('page.old', 'group.a', '/old', [app + '.read'], '旧入口', 3)])
    h.private(run / 'manifest.json', json.dumps(manifest))
    declared_source = {'commit': 'a' * 40, 'artifact_hash': 'b' * 64}
    if history:
        # 这是隔离验收的声明来源，仅证明记录／重试，不声称真实Commerce制品或运行部署。
        h.private(run / 'source-candidate.json', json.dumps({'manifest': manifest, 'source': declared_source, 'reason': '隔离来源验收', 'decision': 'KEEP_CURRENT_GRANTS'}))
        cli('CatalogCli', ['publish-source', run / 'catalog.properties', run / 'source-candidate.json'])
    else:
        cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    client = fixture['clients']['management']
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'],
                 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret']}
    if impact:
        # presentation启用需要既有专用图配置；只装配配置，本验收不调用图或冒充ALLOW证明。
        graph = dict(line.split('=', 1) for line in h.read_private(ROOT / '.local/governance/p3/graph/graph.properties').splitlines()
                     if line and not line.startswith('#') and '=' in line)
        authority = {**authority, 'graph.http': graph['graph.http'], 'graph.key': graph['graph.key'],
                     'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key']}
    partition = {'tenant_id': tenant, 'application_id': app, 'environment': 'test'}
    recipients = []
    if impact:
        delegation = {'access.tenant': tenant, 'access.application': app, 'access.environment': 'test', 'access.manager': member,
                      'access.generation': '1', 'access.capabilities': app+'.read,'+app+'.write', 'access.max-duration-seconds': '3600',
                      'access.operator': 'mg03-fixture', 'access.command': str(uuid.uuid4())}
        h.private(run / 'access.properties', db + h.props(delegation))
        cli('AccessBootstrapCli', [run / 'access.properties'])
        for index in range(2):
            target = {**values, 'command.id': str(uuid.uuid4()), 'principal.id': str(uuid.uuid4()), 'subject': 'mg03-'+str(uuid.uuid4()),
                      'membership.id': str(uuid.uuid4()), 'source.subject.ref': 'recipient-'+str(index)}
            if index == 1:
                external = fixture['users']['external']
                target.update({'principal.id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'p2:'+external['id'])), 'subject': external['id']})
            h.private(run / ('recipient-'+str(index)+'.properties'), h.props(target))
            cli('GovernanceCli', ['bootstrap', ROOT / '.local/governance/database.properties', run / ('recipient-'+str(index)+'.properties')])
            recipients.append(target['membership.id'])
        h.private(run / 'admin-owner-only.properties', db + h.props(authority))
        authority = {**authority, 'portal.diagnostic.count': '1', 'portal.diagnostic.1.tenant-id': tenant,
                     'portal.diagnostic.1.application-id': app, 'portal.diagnostic.1.environment': 'test',
                     'portal.diagnostic.1.membership-id': member, 'portal.diagnostic.1.generation': '1'}
    h.private(run / 'admin.properties', db + h.props(authority))
    checks = []
    vite = None
    try:
        h.start(jar, 21662, run / 'admin.log', config=run / 'admin.properties', access=True, presentation=impact, scope=impact)
        token = auth.token(h.ISSUER, fixture, 'management', 'internal')
        other = auth.token(h.ISSUER, fixture, 'management', 'external')
        headers = [('Authorization', 'Bearer ' + token)]
        candidate = {**manifest, 'manifest_version': 2, 'menus': [manifest['menus'][0], manifest['menus'][1],
                     menu('page.products', 'group.b', '/product-records', [app + '.write'], '商品档案', 4),
                     menu('page.new', 'group.b', '/new', [app + '.read'], '新增入口', 5)]}
        def preview(name, value, credential=headers, status=200, code=None):
            return h.expect(name, 21662, '/api/governance/v1/catalog/preview', credential, json.dumps(value).encode(), status, code)
        result = preview('Owner receives complete diff', candidate)
        assert result['publishable'] and len(result['menu_changes']) == 3
        assert [c['kind'] for c in result['menu_changes']] == ['ADDED', 'REMOVED', 'CHANGED']
        assert result['menu_changes'][2]['fields'] == ['label', 'position', 'parent', 'route', 'any_of']
        preview('different HUMAN cannot read diff', candidate, [('Authorization', 'Bearer ' + other)], 403, 'ACCESS_DENIED')
        preview('anonymous cannot preview', candidate, [], 401, 'INVALID_CREDENTIAL')
        broken = {**candidate, 'menus': [{**candidate['menus'][2], 'parent': 'missing'}]}
        preview('broken hierarchy rejected', broken, status=400, code='INVALID_ARGUMENT')
        illegal = {**candidate, 'capabilities': [{**manifest['capabilities'][0], 'resource_type': 'order'}, manifest['capabilities'][1]]}
        invalid = preview('capability semantic violation is reviewable', illegal)
        assert not invalid['publishable'] and invalid['violations'][0]['code'] == 'CAPABILITY_CHANGED'
        h.expect('invalid publication remains blocked', 21662, '/api/governance/v1/catalog/publish', headers + [('X-Command-Id', str(uuid.uuid4()))],
                 json.dumps(illegal).encode(), 409, 'VERSION_CONFLICT')
        if impact:
            def request(name, endpoint, body, status=200, credential=headers, code=None, port=21662):
                return h.expect(name, port, '/api/governance/v1'+endpoint, credential, json.dumps(body).encode(), status, code)
            request('enable isolated impact partition', '/access/enable-strict', {**partition, 'command_id': str(uuid.uuid4())}, 202)
            roles = [request('create isolated fixed role '+str(i), '/access/roles', {**partition, 'command_id': str(uuid.uuid4()),
                     'role_code': 'reader_'+str(i), 'role_version': 1, 'capabilities': [app+'.read']}) for i in range(2)]
            for who, role in [(recipients[0], roles[0]), (recipients[0], roles[1]), (recipients[1], roles[0])]:
                request('create isolated pending source', '/access/scoped-grants', {**partition, 'command_id': str(uuid.uuid4()),
                        'member_id': who, 'member_generation': 1, 'role_id': role['id'], 'source_id': str(uuid.uuid4()),
                        'scope_rule': {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES', 'values': ['S1'], 'include_root': False}]},
                        'valid_from': (datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(seconds=2)).isoformat().replace('+00:00','Z'), 'valid_to': (datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=30)).isoformat().replace('+00:00','Z')}, 202)
            report = request('diagnostic HTTP deduplicates exact sources', '/access/catalog-impact', {**partition, 'manifest': candidate})
            assert report['stats']['pending_grant_count'] == 3 and report['stats']['people_count'] == 2 and report['stats']['role_count'] == 2
            assert len(report['sources']) == 3 and report['fences']['policy_state'] != 'READY'
            request('anonymous impact denied', '/access/catalog-impact', {**partition, 'manifest': candidate}, 401, [], 'INVALID_CREDENTIAL')
            request('other HUMAN cannot diagnose', '/access/catalog-impact', {**partition, 'manifest': candidate}, 403, [('Authorization', 'Bearer '+other)], 'ACCESS_DENIED')
            request('cross-environment impact denied', '/access/catalog-impact', {**partition, 'environment': 'production', 'manifest': candidate}, 403, code='ACCESS_DENIED')
            request('strict impact actor injection', '/access/catalog-impact', {**partition, 'manifest': candidate, 'subject': 'victim'}, 400, code='INVALID_ARGUMENT')
            h.start(jar, 21663, run / 'admin-owner-only.log', config=run / 'admin-owner-only.properties', access=True, presentation=True, scope=True)
            request('Owner management alone cannot diagnose', '/access/catalog-impact', {**partition, 'manifest': candidate}, 403, code='ACCESS_DENIED', port=21663)
        if guard:
            guarded_candidate = dict(manifest=candidate, source=declared_source, reason='隔离菜单入口调整，保留原授权', decision='KEEP_CURRENT_GRANTS', impact=None)
            request('changed route requires explicit business decision', '/catalog/release-preview', dict(manifest=candidate), 400, code='INVALID_ARGUMENT')
            fixed = request('Owner creates persistent fixed source preview', '/catalog/release-preview', guarded_candidate)
            assert fixed['preview']['content_hash'] == result['content_hash'] and fixed['preview']['presentation_hash'] == result['presentation_hash']
            assert fixed['base_version'] == 1 and fixed['candidate']['source'] == declared_source
            scoped = {**guarded_candidate, 'impact': {**partition, 'basis_hash': report['basis_hash']}}
            request('fixed preview independently verifies selected impact basis', '/catalog/release-preview', scoped)
            request('Owner cannot confirm impact without diagnostic authority', '/catalog/release-preview', scoped, 403, code='ACCESS_DENIED', port=21663)
            request('wrong impact basis rejected', '/catalog/release-preview', {**scoped, 'impact': {**partition, 'basis_hash': 'c'*64}}, 409, code='VERSION_CONFLICT')
            request('foreign Owner cannot use fixed preview ID', '/catalog/release-publish', {'command_id': str(uuid.uuid4()), 'preview_id': fixed['preview_id']}, 403, [('Authorization','Bearer '+other)], 'ACCESS_DENIED')
            request('anonymous fixed publication rejected', '/catalog/release-publish', {'command_id': str(uuid.uuid4()), 'preview_id': fixed['preview_id']}, 401, [], 'INVALID_CREDENTIAL')
            for field in ['manifest', 'subject', 'expires_at']:
                request('publication rejects injected '+field, '/catalog/release-publish', {'command_id': str(uuid.uuid4()), 'preview_id': fixed['preview_id'], field: candidate if field=='manifest' else 'client-value'}, 400, code='INVALID_ARGUMENT')
            policy_app = app+'-policy'
            h.private(run/'policy-catalog.properties', db+h.props({**catalog,'catalog.application':policy_app,'catalog.command':str(uuid.uuid4())}))
            cli('CatalogCli',['register',run/'policy-catalog.properties','configured'])
            policy = h.expect('old application default remains explicit LEGACY',21662,'/api/governance/v1/catalog/guard-policy?application_id='+policy_app,headers,None,200)
            assert policy['mode']=='LEGACY' and policy['version']==0
            enable = {'application_id':policy_app,'command_id':str(uuid.uuid4()),'expected_version':0,'legacy_writers_exited':True,'reason':'仅隔离应用已退出全部旧写节点'}
            enabled = request('Owner enables isolated guarded policy once','/catalog/enable-guard',enable)
            assert enabled['mode']=='GUARDED' and request('same enable command reads immutable receipt','/catalog/enable-guard',enable)==enabled
            request('changed enable body conflicts','/catalog/enable-guard',{**enable,'reason':'改体'},409,code='COMMAND_CONFLICT')
            request('different enable command cannot replace enabled fact','/catalog/enable-guard',{**enable,'command_id':str(uuid.uuid4())},409,code='VERSION_CONFLICT')
            request('enable cannot coerce caller boolean','/catalog/enable-guard',{**enable,'legacy_writers_exited':'true'},400,code='INVALID_ARGUMENT')
            policy_manifest = {**manifest,'application':policy_app,'capabilities':[{'code':policy_app+'.read','resource_type':'store','risk_level':'NORMAL'}],'menus':[]}
            h.expect('guarded policy rejects legacy HTTP publication',21662,'/api/governance/v1/catalog/publish',headers+[('X-Command-Id',str(uuid.uuid4()))],json.dumps(policy_manifest).encode(),409,'VERSION_CONFLICT')
            h.private(run/'policy-manifest.json',json.dumps(policy_manifest))
            try:
                cli('CatalogCli',['publish',run/'policy-catalog.properties',run/'policy-manifest.json'])
            except RuntimeError:
                if 'VERSION_CONFLICT' not in max(run.glob('CatalogCli-*.log'),key=lambda p:p.stat().st_mtime).read_text():
                    raise RuntimeError('legacy CLI failed for unrelated reason')
                h.CHECKS.append({'check':'guarded policy rejects current legacy CLI publication','result':'PASS'})
            else:
                raise RuntimeError('legacy CLI bypassed guarded policy')
        if history:
            receipt = h.expect('publish only random history application',21662,'/api/governance/v1/catalog/publish',headers+[('X-Command-Id',str(uuid.uuid4()))],json.dumps(candidate).encode(),200)
            assert receipt['proposed_version'] == 2
            cli('CatalogCli', ['publish-source', run / 'catalog.properties', run / 'source-candidate.json'])
            def history_read(name, endpoint, status=200, credential=headers, code=None):
                return h.expect(name,21662,endpoint,credential,None,status,code)
            listing = history_read('current Owner sees source and unknown history','/api/governance/v1/catalog/releases?application_id='+app)
            assert [item['version'] for item in listing['items']] == [2,1]
            assert listing['items'][0]['source'] is None and listing['items'][1]['source'] == declared_source
            detail = history_read('fixed version actual menu facts','/api/governance/v1/catalog/releases/1?application_id='+app)
            expected_manifest = {**manifest, 'menus': sorted(manifest['menus'], key=lambda item: item['code']),
                                 'capabilities': sorted(manifest['capabilities'], key=lambda item: item['code'])}
            assert detail['manifest'] == expected_manifest and detail['release']['preview']['current_version'] == 0
            history_read('foreign Owner cannot inspect history','/api/governance/v1/catalog/releases?application_id='+app,403,[('Authorization','Bearer '+other)],'ACCESS_DENIED')
            history_read('anonymous history denied','/api/governance/v1/catalog/releases?application_id='+app,401,[],'INVALID_CREDENTIAL')
            history_read('unknown fixed version not a current fallback','/api/governance/v1/catalog/releases/3?application_id='+app,404,code='NOT_FOUND')
            history_read('invalid history cursor rejected','/api/governance/v1/catalog/releases?application_id='+app+'&before_version=0',400,code='INVALID_ARGUMENT')
        # 页面以实际组件挂载，使用真实Token及实际HTTP；测试壳不冒充完整门户PKCE验收。
        harness = ROOT / 'auth-console/.local/menu-role-governance'
        harness.mkdir(parents=True, exist_ok=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';
import {ConfigProvider} from 'antd';import {userManager} from '../../src/auth/oidcConfig';import {CatalogEditor} from '../../src/governance/CatalogEditor';
import '../../src/styles/global.css';import '../../src/styles/governance.css';
const f=(window as any).__MG02;await userManager.storeUser(new User({access_token:f.token,token_type:'Bearer',profile:{sub:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));
createRoot(document.getElementById('root')!).render(<ConfigProvider><CatalogEditor partition={f.partition} application={f.application} close={()=>{(window as any).__closed=true}} saved={()=>{(window as any).__saved=true}}/></ConfigProvider>);
""")
        payload = {'token': token, 'subject': user['id'], 'issuer': h.ISSUER, 'application': app, 'partition': partition, 'owner_only_admin': 'http://127.0.0.1:21663', 'declared_source': declared_source, 'candidate': candidate, 'illegal': illegal,
                   'admin': 'http://127.0.0.1:21662', 'ui': 'http://127.0.0.1:21665/.local/menu-role-governance/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as output:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '21665', '--strictPort'], cwd=ROOT / 'auth-console', stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + UI_STARTUP_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if vite.poll() is not None:
                raise RuntimeError('owned Vite process failed')
            try:
                with urllib.request.urlopen('http://127.0.0.1:21665/healthz', timeout=1):
                    break
            except OSError:
                time.sleep(.2)
        else:
            raise RuntimeError('owned Vite process startup timeout')
        with (run / 'browser.log').open('w') as output:
            result = subprocess.run(['node', str(ROOT / ('deploy/governance-menu-guard-ui.mjs' if guard else 'deploy/governance-menu-history-ui.mjs' if history else 'deploy/governance-menu-impact-ui.mjs' if impact else 'deploy/governance-menu-diff-ui.mjs')), str(run)], cwd=ROOT, timeout=BROWSER_TIMEOUT_SECONDS,
                                    stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('browser verification failed; private evidence retained')
        checks = h.CHECKS
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': checks, 'original_catalog_writes': 0, 'fixture_grant_writes': 3 if impact else 0, 'original_grant_writes': 0}))
        print('PASS: isolated Owner HTTP checks=' + str(len(checks)) + '; UI evidence: ' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
