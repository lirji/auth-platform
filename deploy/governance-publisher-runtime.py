#!/usr/bin/env python3
"""MG10真实Casdoor／HTTP机器边界；新建两专用PG目标，只关闭自己持有的回环进程。"""
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.parse
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PORTS = (21801, 21802)
ISSUER = 'http://localhost:18090'


def module(name, file):
    """复用既有真实PKCE和私密证据工具，不重复建设身份协议。"""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main(client_rehearsal=None):
    """仅新增本次夹具，失败保留日志及检查点；不更新原IdP客户端或权限目录。"""
    os.umask(0o077)
    h = module('publisher_context', 'governance-context-smoke.py')
    a = module('publisher_access', 'governance-access-smoke.py')
    idp = module('publisher_idp', 'governance-casdoor-fixture.py')
    idp.BASE = ISSUER
    run = ROOT / '.local/menu-role-governance' / ('mg10-http-' + uuid.uuid4().hex[:12])
    run.mkdir(mode=0o700, parents=True)
    fixture = json.loads(h.read_private(ROOT / '.local/governance/p2/identity/casdoor.json'))
    ops = json.loads(h.read_private(ROOT / '.local/governance/casdoor-isolated/management-client.json'))
    basic = 'Basic ' + base64.b64encode((ops['client_id'] + ':' + ops['client_secret']).encode()).decode()
    version = idp.request('get-version-info', authorization=basic).get('data', {}).get('version')
    if version != 'v4.11.0':
        raise RuntimeError('fixed Casdoor runtime required')
    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        if archive.read('BOOT-INF/lib/auth-platform-governance-0.1.0-SNAPSHOT.jar') != (ROOT / 'auth-platform-governance/target/auth-platform-governance-0.1.0-SNAPSHOT.jar').read_bytes():
            raise RuntimeError('current governance artifact required')
    checks = []
    targets = []

    def uid():
        return str(uuid.uuid4())

    def stamp(seconds):
        return (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')

    def cli(name, args):
        log = run / (name + '-' + uuid.uuid4().hex[:8] + '.log')
        with log.open('w') as stream:
            result = subprocess.run(['java', '-Xmx256m', '-Dloader.main=com.lrj.authz.governance.cli.' + name, '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, args)], stdout=stream, stderr=subprocess.STDOUT, timeout=30)
        if result.returncode:
            raise RuntimeError('owned CLI failed: ' + name)

    def check(name, target, path, token=None, payload=None, status=200, code=None, raw=None):
        headers = [] if token is None else [('Authorization', 'Bearer ' + token)]
        body = raw if raw is not None else None if payload is None else json.dumps(payload).encode()
        actual, result = h.request(target['port'], path, headers, body)
        if actual != status or code and (result.get('code') != code or set(result) != {'code', 'trace_id'}):
            raise RuntimeError(name + ': unexpected status ' + str(actual))
        if code:
            uuid.UUID(result['trace_id'])
        checks.append({'check': name, 'result': 'PASS'})
        h.private(run / ('checks-' + str(len(checks)) + '.json'), json.dumps(checks, ensure_ascii=False, indent=2))
        return result

    def machine_client(purpose, ttl=300):
        client = {'name': 'mg10-' + purpose + '-' + uuid.uuid4().hex[:12], 'secret': secrets.token_urlsafe(36), 'publisher': uid(), 'principal': uid()}
        h.private(run / (client['name'] + '.json'), json.dumps(client))
        app = {'owner': 'admin', 'name': client['name'], 'displayName': 'MG10 owned ' + purpose, 'organization': fixture['organization'], 'clientId': client['name'], 'clientSecret': client['secret'], 'cert': 'cert-built-in', 'isShared': False, 'enablePassword': False, 'enableSignUp': False, 'enableSigninSession': False, 'grantTypes': ['client_credentials'], 'redirectUris': [], 'signinMethods': [], 'providers': [], 'expireInHours': ttl / 3600, 'refreshExpireInHours': 0, 'tokenFormat': 'JWT', 'tokenSigningMethod': 'RS256'}
        idp.request('add-application', app, basic)
        current = idp.request('get-application?' + urllib.parse.urlencode({'id': 'admin/' + client['name']}), authorization=basic)['data']
        for key in ('clientId', 'clientSecret', 'isShared', 'enablePassword', 'enableSignUp', 'enableSigninSession', 'grantTypes', 'redirectUris', 'tokenFormat', 'tokenSigningMethod', 'expireInHours'):
            if current.get(key) != app[key]:
                raise RuntimeError('actual machine client configuration mismatch')
        return client

    def token(client):
        response = idp.request('login/oauth/access_token', {'grant_type': 'client_credentials', 'client_id': client['name'], 'client_secret': client['secret'], 'scope': 'openid'}, form=True)
        value = response.get('access_token')
        if not value:
            raise RuntimeError('actual client_credentials token missing')
        claims = json.loads(base64.urlsafe_b64decode(value.split('.')[1] + '==='))
        if claims.get('type') != 'application' or claims.get('tokenType') != 'access-token' or claims.get('aud') != [client['name']] or claims.get('azp') != client['name'] or claims.get('sub') != 'admin/' + client['name'] or not 0 < claims.get('exp', 0) - claims.get('iat', 0) <= 300:
            raise RuntimeError('actual machine claims mismatch')
        h.private(run / ('token-' + uid() + '.json'), json.dumps(response))
        return value, claims

    def pg(target, sql):
        result = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + target['database'] + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=10)
        if result.returncode:
            raise RuntimeError('owned PG read failed')
        return result.stdout.strip()

    def candidate(target, version, label, route='/stores'):
        app = target['application']
        return {'target_instance_id': target['instance'], 'environment': target['environment'], 'manifest': {'schema_version': '1', 'application': app, 'manifest_version': version, 'capabilities': [{'code': app + '.read', 'resource_type': 'store', 'risk_level': 'NORMAL'}], 'menus': [{'code': 'stores', 'parent': None, 'route': route, 'any_of': [app + '.read'], 'label': label, 'position': 0}]}, 'source': {'commit': 'a' * 40, 'artifact_hash': 'b' * 64}, 'reason': '真实隔离机器展示验收'}

    def delegate(target, client, owner):
        payload = {'application_id': target['application'], 'publisher_id': client['publisher'], 'command_id': uid(), 'valid_until': stamp(1200), 'reason': '有限机器展示委派'}
        return check('real HUMAN creates fixed SERVICE delegation', target, '/api/governance/v1/catalog/publisher-delegations', owner, payload)

    result_path = run / 'result.json'
    try:
        owner_token = a.token(ISSUER, fixture, 'management', 'internal')
        for index, port in enumerate(PORTS):
            directory = run / ('target-' + str(index))
            subprocess.run(['python3', 'deploy/governance-test-db.py', '--directory', str(directory / 'database')], cwd=ROOT, check=True, stdout=subprocess.DEVNULL, timeout=30)
            database = json.loads(h.read_private(directory / 'database/database.json'))['database']
            db = h.read_private(directory / 'database/database.properties')
            target = {'port': port, 'database': database, 'instance': uid(), 'environment': 'test' if index == 0 else 'staging', 'application': 'mg10-shop', 'owner': uid()}
            clients = [machine_client('primary-' + str(index)), machine_client('rotation-' + str(index))]
            if index == 0:
                clients.append(machine_client('short', 5))
                if client_rehearsal:
                    ci_client = machine_client('ci-commerce')
                    ci_client['application'] = 'commerce'
                    clients.append(ci_client)
            target['clients'] = clients
            targets.append(target)
            person = {'command.id': uid(), 'operator.ref': 'mg10-fixture', 'tenant.id': uid(), 'tenant.code': 'mg10-' + uid(), 'principal.id': target['owner'], 'issuer': ISSUER, 'subject': fixture['users']['internal']['id'], 'membership.id': uid(), 'valid.from': '2020-01-01T00:00:00Z', 'source.system': 'mg10-fixture', 'source.tenant.ref': uid(), 'source.subject.ref': 'owner'}
            h.private(directory / 'owner.properties', h.props(person))
            cli('GovernanceCli', ['bootstrap', directory / 'database/database.properties', directory / 'owner.properties'])
            catalog = {'catalog.application': target['application'], 'catalog.owner-principal': target['owner'], 'catalog.entry-origin': 'https://example.test', 'catalog.operator': 'mg10-fixture', 'catalog.command': uid(), 'catalog.owner-issuer': ISSUER, 'catalog.owner-subject': fixture['users']['internal']['id']}
            h.private(directory / 'catalog.properties', db + h.props(catalog))
            cli('CatalogCli', ['register', directory / 'catalog.properties', 'configured'])
            h.private(directory / 'manifest.json', json.dumps(candidate(target, 1, '初始目录')['manifest']))
            cli('CatalogCli', ['publish', directory / 'catalog.properties', directory / 'manifest.json'])
            if index == 0 and client_rehearsal:
                # CI验收使用实际Commerce固定提交声明，只发布到本次新建的权限数据库。
                commerce = ROOT.parent / 'commerce-platform'
                commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=commerce, text=True).strip()
                artifact = subprocess.check_output(['git', 'show', commit + ':frontend/src/iam/catalog.json'], cwd=commerce)
                declaration = json.loads(artifact)
                ci_catalog = {**catalog, 'catalog.application': 'commerce', 'catalog.command': uid()}
                h.private(directory / 'ci-catalog.properties', db + h.props(ci_catalog))
                h.private(directory / 'ci-initial-manifest.json', json.dumps({**declaration, 'manifest_version': 1}))
                cli('CatalogCli', ['register', directory / 'ci-catalog.properties', 'configured'])
                cli('CatalogCli', ['publish', directory / 'ci-catalog.properties', directory / 'ci-initial-manifest.json'])
                target['ci_source'] = {'commit': commit, 'artifact': artifact}
            management = fixture['clients']['management']
            authority = {'issuer': ISSUER, 'jwks.uri': ISSUER + '/.well-known/jwks', 'audience': management['name'], 'client.id': management['name'], 'client.secret': management['secret'], 'version-probe.client.id': ops['client_id'], 'version-probe.client.secret': ops['client_secret']}
            settings = {'publisher.target-instance-id': target['instance'], 'publisher.environment': target['environment'], 'publisher.ids': ','.join(c['publisher'] for c in clients), 'publisher.initialize.operator': 'mg10-fixture', 'publisher.initialize.reason': '独立实例数据库隔离验收'}
            for client in clients:
                prefix = 'publisher.' + client['publisher'] + '.'
                values = {**authority, 'audience': client['name'], 'client.id': client['name'], 'client.secret': client['secret'], 'application': client.get('application', target['application']), 'subject': 'admin/' + client['name'], 'service-principal-id': client['principal']}
                settings.update({prefix + key: value for key, value in values.items()})
            config = directory / 'admin.properties'
            h.private(config, db + h.props({**authority, **settings}))
            target['config'] = config
            cli('CatalogPublisherTargetCli', ['initialize-target', config])
            if index == 0:
                off_config = directory / 'default-off.properties'
                h.private(off_config, db + h.props({**authority, 'publisher.ids': 'invalid-machine-config-must-not-be-read'}))
                disabled = h.start(jar, port, directory / 'default-off.log', config=off_config, access=True)
                check('default disabled machine prefix rejects HUMAN without legacy fallback', target, '/api/catalog-publisher/v1/preview', owner_token, candidate(target, 2, '不允许'), 403, 'ACCESS_DENIED')
                check('default disabled machine prefix rejects anonymous consistently', target, '/api/catalog-publisher/v1/preview', None, candidate(target, 2, '不允许'), 403, 'ACCESS_DENIED')
                h.stop(disabled)
            h.start(jar, port, directory / 'admin.log', config=config, access=True, publisher=True)
            check('real owner guards isolated catalog', target, '/api/governance/v1/catalog/enable-guard', owner_token, {'application_id': target['application'], 'command_id': uid(), 'expected_version': 0, 'legacy_writers_exited': True, 'reason': '本工具不存在旧写节点'})
            for client in clients:
                client['delegation'] = delegate({**target, 'application': client.get('application', target['application'])}, client, owner_token)
            target['token'], target['claims'] = token(clients[0])
        one, two = targets
        path = '/api/catalog-publisher/v1/'
        machine = one['token']
        body = candidate(one, 2, '机器展示目录')
        check('anonymous machine call refused', one, path + 'preview', None, body, 401, 'INVALID_CREDENTIAL')
        check('HUMAN token cannot enter machine boundary', one, path + 'preview', owner_token, body, 401, 'INVALID_CREDENTIAL')
        check('machine token cannot enter HUMAN management', one, '/api/governance/v1/catalog/publisher-delegations?application_id=' + one['application'], machine, status=401, code='INVALID_CREDENTIAL')
        check('other isolated instance client rejected', two, path + 'preview', machine, candidate(two, 2, '错误实例'), 401, 'INVALID_CREDENTIAL')
        check('other target confirmation refused', one, path + 'preview', machine, {**body, 'target_instance_id': two['instance']}, 403, 'ACCESS_DENIED')
        check('other environment confirmation refused', one, path + 'preview', machine, {**body, 'environment': 'staging'}, 403, 'ACCESS_DENIED')
        check('machine decision injection refused', one, path + 'preview', machine, {**body, 'decision': 'KEEP_CURRENT_GRANTS'}, 400, 'INVALID_ARGUMENT')
        check('machine query injection refused', one, path + 'preview?operator=owner', machine, body, 400, 'INVALID_ARGUMENT')
        check('duplicate owner query refused', one, '/api/governance/v1/catalog/publisher-delegations?application_id=mg10-shop&application_id=mg10-shop', owner_token, status=400, code='INVALID_ARGUMENT')
        risk = check('semantic candidate returns real diff and no ticket', one, path + 'preview', machine, candidate(one, 2, '人工审查', '/different'))
        if risk['eligibility'] != 'REQUIRES_OWNER_REVIEW' or risk['ticket'] is not None or not risk['preview']['menu_changes']:
            raise RuntimeError('semantic candidate obtained authority')
        facts_sql = "SELECT row_to_json(t)::text FROM auth_governance.access_grant t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.role_version t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.membership t ORDER BY id; SELECT row_to_json(t)::text FROM auth_governance.capability_state t ORDER BY application_id,capability; SELECT count(*) FROM auth_governance.execution_reference;"
        before = pg(one, facts_sql)
        report = check('actual machine freezes nonsemantic candidate', one, path + 'preview', machine, body)
        if report['eligibility'] != 'AUTOMATION_ALLOWED' or not report['ticket']:
            raise RuntimeError('safe candidate has no fixed ticket')
        command = {'target_instance_id': one['instance'], 'environment': one['environment'], 'command_id': uid(), 'preview_id': report['ticket']['preview_id']}
        receipt = check('actual SERVICE publishes immutable candidate', one, path + 'publish', machine, command)
        if receipt['release']['published_by'] != one['clients'][0]['principal'] or receipt['actor']['owner_principal'] != one['owner']:
            raise RuntimeError('publisher actor impersonated human')
        if check('original successful command returns same receipt', one, path + 'publish', machine, command) != receipt:
            raise RuntimeError('changed original receipt')
        query = {key: value for key, value in command.items() if key != 'preview_id'}
        if check('unknown response recovery queries original command', one, path + 'receipt', machine, query) != receipt:
            raise RuntimeError('recovery returned another receipt')
        check('publish cannot replace candidate', one, path + 'publish', machine, {**command, 'manifest': body['manifest']}, 400, 'INVALID_ARGUMENT')
        check('publish budget is exactly bounded', one, path + 'publish', machine, status=400, code='INVALID_ARGUMENT', raw=b' ' * 4097)
        check('publish repeated JSON key refused', one, path + 'publish', machine, status=400, code='INVALID_ARGUMENT', raw=b'{"command_id":"a","command_id":"b"}')
        check('own unsubmitted receipt is not found', one, path + 'receipt', machine, {**query, 'command_id': uid()}, 404, 'NOT_FOUND')
        if pg(one, facts_sql) != before:
            raise RuntimeError('machine publication changed business facts')
        short, short_claims = token(one['clients'][2])
        check('real short machine token works before expiry', one, path + 'preview', short, candidate(one, 3, '短凭据'))
        deadline = time.monotonic() + 7
        while time.time() <= short_claims['exp'] + 0.1 and time.monotonic() < deadline:
            time.sleep(0.1)
        check('real expired machine token refused', one, path + 'preview', short, candidate(one, 3, '已到期'), 401, 'INVALID_CREDENTIAL')
        stopped = check('HUMAN disables current machine delegation', one, '/api/governance/v1/catalog/publisher-delegations/disable', owner_token, {'application_id': one['application'], 'delegation_id': one['clients'][0]['delegation']['delegation_id'], 'expected_version': 1, 'command_id': uid(), 'reason': '真实撤销测试'})
        if stopped['status'] != 'DISABLED':
            raise RuntimeError('disable did not take effect')
        check('valid old Token cannot publish after disable', one, path + 'preview', machine, candidate(one, 3, '旧凭据'), 403, 'ACCESS_DENIED')
        check('disabled delegation has no original receipt exception', one, path + 'receipt', machine, query, 403, 'ACCESS_DENIED')
        rotated, rotated_claims = token(one['clients'][1])
        check('new isolated client and delegation rotates safely', one, path + 'preview', rotated, candidate(one, 3, '新客户端'))
        check('new client cannot read old client receipt', one, path + 'receipt', rotated, query, 404, 'NOT_FOUND')
        if client_rehearsal:
            client_rehearsal(h, run, one, owner_token, pg, check)
        # 只撤销本工具刚签发、并精确核对所属客户端的Token，不删除客户端或其他人员凭据。
        token_id = rotated_claims.get('jti')
        if not isinstance(token_id, str) or not token_id.startswith('admin/'):
            raise RuntimeError('owned token ID missing')
        stored = idp.request('get-token?' + urllib.parse.urlencode({'id': token_id}), authorization=basic).get('data')
        if not stored or stored.get('application') != one['clients'][1]['name'] or stored.get('organization') != fixture['organization'] or stored.get('grantType') != 'client_credentials':
            raise RuntimeError('token revocation fixture is not owned')
        idp.request('delete-token', {'owner': 'admin', 'name': token_id.split('/', 1)[1], 'organization': fixture['organization']}, basic)
        check('real IdP revocation is seen on next introspection', one, path + 'preview', rotated, candidate(one, 3, '已撤销Token'), 401, 'INVALID_CREDENTIAL')
        independent = check('second instance publishes only own fixed candidate', two, path + 'preview', two['token'], candidate(two, 2, '独立环境'))
        check('second instance own SERVICE publication', two, path + 'publish', two['token'], {'target_instance_id': two['instance'], 'environment': two['environment'], 'command_id': uid(), 'preview_id': independent['ticket']['preview_id']})
        result = {'status': 'PASS', 'runtime_version': version, 'checks': checks, 'owned_instances': [{key: value for key, value in t.items() if key in ('port', 'database', 'instance', 'environment', 'application')} for t in targets], 'artifact_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'original_deployment_writes': 0, 'production_deployments': 0}
        h.private(result_path, json.dumps(result, ensure_ascii=False, indent=2))
        print(json.dumps({'status': 'PASS', 'checks': len(checks), 'evidence': str(result_path)}, ensure_ascii=False))
    except Exception as failure:
        h.private(result_path, json.dumps({'status': 'FAIL', 'failure_type': type(failure).__name__, 'completed_checks': checks}, ensure_ascii=False, indent=2))
        print(json.dumps({'status': 'FAIL', 'evidence': str(result_path)}, ensure_ascii=False))
        raise RuntimeError('owned publisher runtime failed; inspect private evidence') from None
    finally:
        for process in h.PROCESSES:
            h.stop(process)


if __name__ == '__main__':
    main()
