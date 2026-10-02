#!/usr/bin/env python3
"""只有当前Owner122闭集终验匹配才准备独有PG/HUMAN夹具，执行既有HTTP与真实治理UI。"""
import argparse
import base64
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ISSUER = 'http://localhost:18090'
ADMIN, UI = 21662, 21665
SPEC = importlib.util.spec_from_file_location('publication', ROOT / 'deploy/governance-commerce-publication.py')
publication = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publication)


def properties(values):
    """配置沿用真实CLI协议，不把凭据放到命令行或进程输出。"""
    return ''.join(key + '=' + str(value) + '\n' for key, value in values.items())


def private_text(path, content):
    """独有检查点只写一次；未知命令或失败运行不能被覆盖。"""
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w') as stream:
        stream.write(content)


def read_private(path):
    publication.require(path.is_file() and not path.is_symlink() and path.stat().st_mode & 0o777 == 0o600, 'private file required')
    return path.read_text()


def stable_json(path, value):
    """恢复只接受同一已保存事实，不能覆盖上一轮浏览或SQL检查点。"""
    if path.exists():
        publication.require(publication.load(path, private=True) == value, 'prepared checkpoint changed')
    else:
        publication.save_private(path, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--readiness', type=Path, required=True)
    parser.add_argument('--source-root', action='append', required=True)
    parser.add_argument('--database-directory', type=Path, required=True)
    parser.add_argument('--management-config', type=Path, required=True)
    parser.add_argument('--graph-config', type=Path, required=True)
    parser.add_argument('--playwright-module', required=True)
    parser.add_argument('--resume-prepared', type=Path)
    args = parser.parse_args()
    pairs = [value.split('=', 1) for value in args.source_root]
    publication.require(0 < len(pairs) <= 8 and len({name for name, _ in pairs}) == len(pairs), 'finite unique repositories required')
    roots = {name: Path(path).resolve() for name, path in pairs}
    candidate = publication.load(ROOT / 'docs/design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json')
    plan = publication.plan(candidate, publication.load(args.mapping), publication.load(args.readiness), roots, 2)
    # 先完成源栅栏才允许新增数据库/IdP夹具或发布cap，不把候选灌进去再补证据。
    publication.require(plan['status'] == 'READY_FOR_ISOLATED_OWNER_PUBLICATION', 'Owner terminal capability evidence still pending; no fixture writes')
    dbdir = args.database_directory.resolve()
    publication.require(dbdir.is_relative_to(ROOT / '.local/commerce-catalog-publication'), 'owned database directory required')
    db = publication.load(dbdir / 'database.json', private=True)
    publication.require(db['database'].startswith('auth_gov_p1_test_') and db['username'].startswith('auth_gov_p1_'), 'owned database required')
    for port in (ADMIN, UI):
        with socket.socket() as endpoint:
            endpoint.bind(('127.0.0.1', port))
    run = args.resume_prepared.resolve() if args.resume_prepared else ROOT / '.local/commerce-catalog-publication' / ('runtime-' + secrets.token_hex(6))
    publication.require(run.parent == ROOT / '.local/commerce-catalog-publication' and run.name.startswith('runtime-'), 'owned run directory required')
    if args.resume_prepared:
        saved = publication.load(run / 'prepared.json', private=True)
        publication.require(saved['plan_sha256'] == hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest(), 'original prepared source plan changed')
        publication.require(saved['database'] == db['database'], 'prepared database changed')
        identity = publication.load(run / 'identity.json', private=True)
        publication.require(saved['partition']['tenant_id'] == identity['tenant'], 'prepared identity partition changed')
    else:
        run.mkdir(mode=0o700)
        publication.save_private(run / 'plan.json', plan)
        suffix = secrets.token_hex(6)
        identity = {'organization': 'cp-' + suffix, 'tenant': str(uuid.uuid4()), 'client': {'name': 'cp-console-' + suffix, 'secret': secrets.token_urlsafe(40)},
                    'users': {kind: {'id': str(uuid.uuid4()), 'name': 'cp-' + kind, 'password': secrets.token_urlsafe(24), 'principal': str(uuid.uuid4()), 'member': str(uuid.uuid4())} for kind in ('owner', 'ordinary')}}
        publication.save_private(run / 'identity.json', identity)
    org, client, users, tenant = identity['organization'], identity['client'], identity['users'], identity['tenant']
    partition = {'tenant_id': tenant, 'application_id': 'commerce', 'environment': 'test'}
    management = publication.load(args.management_config, private=True)
    authorization = 'Basic ' + base64.b64encode((management['client_id'] + ':' + management['client_secret']).encode()).decode()
    processes = []
    checks = []

    def record(name):
        """每个已发生实际检查独立保存；失败不覆盖成功或前一轮证据。"""
        checks.append({'check': name, 'result': 'PASS'})
        publication.save_private(run / ('checkpoint-' + uuid.uuid4().hex + '.json'), checks)

    def idp(path, data, form=False, auth=authorization):
        """只调用既有本地Casdoor，为本次独有组织准备HUMAN与真实PKCE。"""
        headers = {'Authorization': auth, 'Accept-Language': 'en', 'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'}
        body = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
        with urllib.request.urlopen(urllib.request.Request(ISSUER + '/api/' + path, body, headers), timeout=15) as response:
            result = json.load(response)
        publication.require(result.get('status') != 'error' and not result.get('error'), 'owned IdP operation failed')
        return result

    def sql(statement):
        """仅用独有DB账号读取或执行CLI后断言；不访问其他Owner业务库。"""
        result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'psql', '-U', db['username'], '-d', db['database'], '-At', '-v', 'ON_ERROR_STOP=1'], input=statement, text=True, capture_output=True, timeout=20)
        if result.returncode:
            private_text(run / ('sql-failure-' + uuid.uuid4().hex + '.log'), result.stderr)
            raise RuntimeError('owned SQL failed; private failure retained')
        return result.stdout.strip()

    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        for module in ('protocol', 'core', 'governance'):
            artifact = ROOT / ('auth-platform-' + module) / 'target' / ('auth-platform-' + module + '-0.1.0-SNAPSHOT.jar')
            publication.require(archive.read('BOOT-INF/lib/' + artifact.name) == artifact.read_bytes(), 'stale packaged Auth dependency')
    record('current packaged Auth protocol/core/governance nested artifacts match worktree')

    def cli(name, *inputs):
        """沿用Governance/Catalog/AccessBootstrap入口，不直接SQL伪造catalog或Role。"""
        log = run / (name + '-' + uuid.uuid4().hex + '.log')
        with log.open('wb') as output:
            result = subprocess.run(['java', '-Dloader.main=com.lrj.authz.governance.cli.' + name, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, inputs)], stdout=output, stderr=subprocess.STDOUT, timeout=40)
        log.chmod(0o600)
        publication.require(result.returncode == 0, 'owned CLI failed; private log retained')

    def token(user):
        """真实authorization_code+S256交换；不造JWT或绕过HUMAN版本探测。"""
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        redirect = 'http://127.0.0.1:' + str(UI) + '/callback'
        query = urllib.parse.urlencode({'clientId': client['name'], 'responseType': 'code', 'redirectUri': redirect, 'scope': 'openid profile', 'state': secrets.token_urlsafe(24), 'nonce': secrets.token_urlsafe(24), 'code_challenge_method': 'S256', 'code_challenge': challenge})
        code = idp('login?' + query, {'type': 'code', 'organization': org, 'username': user['name'], 'password': user['password'], 'application': client['name'], 'signinMethod': 'Password'}, auth='')['data']
        return idp('login/oauth/access_token', {'grant_type': 'authorization_code', 'client_id': client['name'], 'code': code, 'code_verifier': verifier, 'redirect_uri': redirect}, form=True, auth='')['access_token']

    def health():
        try:
            with urllib.request.urlopen('http://127.0.0.1:' + str(ADMIN) + '/actuator/health', timeout=1) as response:
                return response.status == 200
        except OSError:
            return False

    try:
        database = read_private(dbdir / 'database.properties')
        if not args.resume_prepared:
            idp('add-organization', {'owner': 'admin', 'name': org, 'displayName': 'Commerce catalog dedicated acceptance', 'passwordType': 'bcrypt', 'passwordOptions': ['AtLeast8'], 'accountItems': []})
            idp('add-application', {'owner': 'admin', 'name': client['name'], 'displayName': 'Commerce catalog dedicated console', 'organization': org, 'clientId': client['name'], 'clientSecret': client['secret'], 'cert': 'cert-built-in', 'isShared': False, 'enablePassword': True, 'enableSignUp': False, 'enableSigninSession': False, 'grantTypes': ['authorization_code', 'refresh_token'], 'redirectUris': ['http://127.0.0.1:' + str(UI) + '/callback'], 'signinMethods': [{'name': 'Password', 'displayName': 'Password', 'rule': 'All'}], 'providers': [], 'expireInHours': 1, 'refreshExpireInHours': 2, 'tokenFormat': 'JWT-Custom', 'tokenSigningMethod': 'RS256', 'tokenFields': ['Owner', 'Name', 'DisplayName']})
            for kind, user in users.items():
                idp('add-user', {'owner': org, 'name': user['name'], 'id': user['id'], 'displayName': 'Commerce catalog ' + kind, 'type': 'normal-user', 'password': user['password'], 'isAdmin': False, 'signupApplication': client['name']})
                values = {'command.id': str(uuid.uuid4()), 'operator.ref': 'cp-fixture', 'tenant.id': tenant, 'tenant.code': org, 'principal.id': user['principal'], 'issuer': ISSUER, 'subject': user['id'], 'membership.id': user['member'], 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'cp-fixture', 'source.tenant.ref': tenant, 'source.subject.ref': kind}
                config = run / (kind + '-bootstrap.properties'); private_text(config, properties(values)); cli('GovernanceCli', 'bootstrap', dbdir / 'database.properties', config)
            config = run / 'catalog.properties'
            private_text(config, database + properties({'catalog.application': 'commerce', 'catalog.owner-principal': users['owner']['principal'], 'catalog.entry-origin': 'http://127.0.0.1:21661', 'catalog.operator': 'cp-fixture', 'catalog.command': str(uuid.uuid4()), 'catalog.owner-issuer': ISSUER, 'catalog.owner-subject': users['owner']['id']}))
            cli('CatalogCli', 'register', config, 'configured')
            baseline = {**plan['manifest'], 'manifest_version': 1, 'menus': []}
            publication.save_private(run / 'manifest-v1.json', baseline); cli('CatalogCli', 'publish', config, run / 'manifest-v1.json')
            delegation = run / 'owner-delegation.properties'
            private_text(delegation, database + properties({'access.tenant': tenant, 'access.application': 'commerce', 'access.environment': 'test', 'access.manager': users['owner']['member'], 'access.generation': 1, 'access.capabilities': ','.join(cap['code'] for cap in baseline['capabilities']), 'access.max-duration-seconds': 3600, 'access.operator': 'cp-fixture', 'access.command': str(uuid.uuid4())}))
            cli('AccessBootstrapCli', delegation)
            publication.save_private(run / 'prepared.json', {'plan_sha256': hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest(), 'database': db['database'], 'partition': partition})
        graph = dict(line.split('=', 1) for line in read_private(args.graph_config).splitlines() if '=' in line)
        config = run / ('admin-' + uuid.uuid4().hex + '.properties')
        private_text(config, database + properties({**graph, 'scope.graph.http': graph['graph.http'], 'scope.graph.key': graph['graph.key'], 'issuer': ISSUER, 'jwks.uri': ISSUER + '/.well-known/jwks', 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'version-probe.client.id': management['client_id'], 'version-probe.client.secret': management['client_secret'], 'approval.tenant': tenant, 'approval.application': 'commerce', 'approval.environment': 'test', 'approval.inbound-key': secrets.token_urlsafe(48)}))
        log = (run / ('admin-' + uuid.uuid4().hex + '.log')).open('wb')
        server = subprocess.Popen(['java', '-Xmx256m', '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=' + str(ADMIN), '--authz.governance.enabled=true', '--authz.governance.access.enabled=true', '--authz.governance.presentation.enabled=true', '--authz.governance.scope.enabled=true', '--authz.governance.requests.enabled=true', '--authz.governance.configuration=' + str(config)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        processes.append(server)
        for _ in range(180):
            if health(): break
            publication.require(server.poll() is None, 'owned Auth startup failed'); time.sleep(.25)
        else: raise RuntimeError('owned Auth startup timeout')
        tokens = {kind: token(user) for kind, user in users.items()}
        execution = {'admin_origin': 'http://127.0.0.1:' + str(ADMIN), 'partition': partition, 'database': db['database'], 'owner_token': tokens['owner']}
        publication.save_private(run / ('execution-' + uuid.uuid4().hex + '.json'), execution)
        result = publication.apply(plan, execution, run / 'http-checkpoints')
        record('actual Owner HTTP publishes exact current menus and creates34 fixed same-resource snapshots without Grant')
        state = json.loads(sql("SELECT json_build_object('roles',(SELECT json_agg(row_to_json(r) ORDER BY r.id) FROM auth_governance.role_version r WHERE tenant_id='%s'),'grants',(SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id='%s'),'policies',(SELECT count(*) FROM auth_governance.request_policy WHERE tenant_id='%s'));" % (tenant, tenant, tenant)))
        expected = {role['code']: role for role in plan['role_snapshots']}
        publication.require(len(state['roles']) == len(expected) == 34 and state['grants'] == state['policies'] == 0, 'exact role/zero Grant policy SQL assertion failed')
        for role in state['roles']:
            source = expected[role['role_code']]
            publication.require(role['version'] == source['version'] and sorted(json.loads(role['capabilities_json'])) == sorted(source['capabilities']), 'SQL fixed role differs from approved template')
        stable_json(run / 'sql-before-ui.json', state)
        stable_json(run / 'browser.json', {'run': str(run), 'origin': 'http://127.0.0.1:' + str(UI), 'admin': execution['admin_origin'], 'authority': ISSUER, 'client': client['name'], 'users': users, 'partition': partition, 'roles': result['roles'], 'catalog': result['catalog']})
        env = dict(os.environ, VITE_CASDOOR_AUTHORITY=ISSUER, VITE_CASDOOR_CLIENT_ID=client['name'])
        with (run / ('frontend-build-' + uuid.uuid4().hex + '.log')).open('wb') as output:
            subprocess.run(['npm', 'run', 'build'], cwd=ROOT / 'auth-console', env=env, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=180)
        stable_json(run / 'artifacts.json', {'jar_sha256': publication.sha(jar), 'frontend_sources': {str(path.relative_to(ROOT)): publication.sha(path) for path in (ROOT / 'auth-console/src').rglob('*') if path.is_file()}, 'assets': {str(path.relative_to(ROOT / 'auth-console/dist')): publication.sha(path) for path in (ROOT / 'auth-console/dist').rglob('*') if path.is_file()}})
        static = subprocess.Popen(['node', str(ROOT / 'deploy/governance-integrated-resources-static.mjs')], cwd=ROOT, env=dict(env, IR_UI=str(UI), IR_ADMIN=str(ADMIN)), stdout=(run / ('static-' + uuid.uuid4().hex + '.log')).open('wb'), stderr=subprocess.STDOUT); processes.append(static)
        for _ in range(80):
            try:
                with urllib.request.urlopen('http://127.0.0.1:' + str(UI) + '/healthz', timeout=1) as response:
                    if response.status == 200: break
            except OSError: pass
            time.sleep(.1)
        with (run / ('browser-' + uuid.uuid4().hex + '.log')).open('wb') as output:
            subprocess.run(['node', str(ROOT / 'deploy/governance-commerce-publication-ui.mjs')], cwd=ROOT, env=dict(env, CP_RUN=str(run), CP_ATTEMPT=uuid.uuid4().hex, CP_PLAYWRIGHT_MODULE=args.playwright_module), stdout=output, stderr=subprocess.STDOUT, check=True, timeout=300)
        publication.require(sql("SELECT (SELECT count(*) FROM auth_governance.role_version WHERE tenant_id='%s') || ',' || (SELECT count(*) FROM auth_governance.access_grant WHERE tenant_id='%s') || ',' || (SELECT count(*) FROM auth_governance.request_policy WHERE tenant_id='%s');" % (tenant, tenant, tenant)) == '34,0,0', 'UI browsing changed role/Grant/Policy rows')
        record('actual password PKCE/UI current catalog/34roles/newScopeFields;SQL34/0/0 after browsing')
        publication.save_private(run / ('result-' + uuid.uuid4().hex + '.json'), {'status': 'HTTP_SQL_PKCE_UI_PASS_VISUAL_REVIEW_PENDING', 'checks': checks, 'partition': partition, 'visual_review': 'PENDING'})
        print('CP_RUN=' + str(run)); print('PASS HTTP/SQL/PKCE/UI; actual visual review still required')
    except Exception as error:
        publication.save_private(run / ('failure-' + uuid.uuid4().hex + '.json'), {'status': 'FAIL', 'type': type(error).__name__, 'checks_completed': checks})
        raise
    finally:
        # 只回收自己Popen对象；不会按端口停止其他Owner演练。
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)


if __name__ == '__main__':
    main()
