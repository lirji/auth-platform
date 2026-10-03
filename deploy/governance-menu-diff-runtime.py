#!/usr/bin/env python3
"""MG02真实Owner HTTP与差异弹层验收；仅新增隔离PG应用，不发布原电商目录或改Grant。"""
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


def module(name, file):
    """复用已有私密配置、真实PKCE和独有进程约束，避免建立第二套认证测试协议。"""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    """本次夹具只写随机应用／企业；所有凭据和日志留在忽略的0600证据目录。"""
    os.umask(0o077)
    h = module('context', 'governance-context-smoke.py')
    auth = module('access_smoke', 'governance-access-smoke.py')
    run = ROOT / '.local/menu-role-governance' / ('mg02-' + uuid.uuid4().hex[:12])
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
                                    stdout=output, stderr=subprocess.STDOUT, timeout=40)
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
    cli('CatalogCli', ['publish', run / 'catalog.properties', run / 'manifest.json'])
    client = fixture['clients']['management']
    authority = {'issuer': h.ISSUER, 'jwks.uri': h.ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'],
                 'client.secret': client['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret']}
    h.private(run / 'admin.properties', db + h.props(authority))
    checks = []
    vite = None
    try:
        h.start(jar, 21662, run / 'admin.log', config=run / 'admin.properties', access=True)
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
        # 页面以实际组件挂载，使用真实Token及实际HTTP；测试壳不冒充完整门户PKCE验收。
        harness = ROOT / 'auth-console/.local/menu-role-governance'
        harness.mkdir(parents=True, exist_ok=True)
        (harness / 'index.html').write_text('<div id="root"></div><script type="module" src="./entry.tsx"></script>')
        (harness / 'entry.tsx').write_text("""import React from 'react';import {createRoot} from 'react-dom/client';import {User} from 'oidc-client-ts';
import {ConfigProvider} from 'antd';import {userManager} from '../../src/auth/oidcConfig';import {CatalogEditor} from '../../src/governance/CatalogEditor';
import '../../src/styles/global.css';import '../../src/styles/governance.css';
const f=(window as any).__MG02;await userManager.storeUser(new User({access_token:f.token,token_type:'Bearer',profile:{sub:f.subject,iss:f.issuer},expires_at:Math.floor(Date.now()/1000)+600}));
createRoot(document.getElementById('root')!).render(<ConfigProvider><CatalogEditor application={f.application} close={()=>{(window as any).__closed=true}} saved={()=>{(window as any).__saved=true}}/></ConfigProvider>);
""")
        payload = {'token': token, 'subject': user['id'], 'issuer': h.ISSUER, 'application': app, 'candidate': candidate, 'illegal': illegal,
                   'admin': 'http://127.0.0.1:21662', 'ui': 'http://127.0.0.1:21665/.local/menu-role-governance/index.html'}
        h.private(run / 'browser.private.json', json.dumps(payload))
        with (run / 'vite.log').open('w') as output:
            vite = subprocess.Popen(['node', 'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '21665', '--strictPort'], cwd=ROOT / 'auth-console', stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 20
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
            result = subprocess.run(['node', str(ROOT / 'deploy/governance-menu-diff-ui.mjs'), str(run)], cwd=ROOT, timeout=120,
                                    stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('browser verification failed; private evidence retained')
        checks = h.CHECKS
        h.private(run / 'http-result.json', json.dumps({'status': 'PASS', 'checks': checks, 'original_catalog_writes': 0, 'grant_writes': 0}))
        print('PASS: isolated Owner HTTP checks=' + str(len(checks)) + '; UI evidence: ' + str(run))
    finally:
        if vite is not None:
            h.stop(vite)
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
