"""CE05-D2最终编译SSO页面：四独立真实HUMAN岗位，不修改旧任务的原执行来源。"""
import hashlib
import base64
import json
import os
import secrets
import subprocess
import threading
import time
from pathlib import Path
from http import HTTPStatus
from urllib.parse import urlencode

ACTIONS = ('create', 'pump', 'control', 'read')
HINTS = ('create', 'control', 'pump')
RESOURCE_TYPE = 'coupon_delivery'
QUANTUM = 20
AUDIENCE_SIZE = QUANTUM + 1
MAX_CONFIRMED_CALLS = 32
BROWSER_TIMEOUT_SECONDS = 240
FIXTURE_GRANT_SECONDS = 3000
TARGET = 'ce05-delivery-ui:' + 'x' * 47
CREATED = 'ce05-delivery-ui:create'
CANCELLED = 'ce05-delivery-ui:cancel'


def independent_identity(fixture, issuer, ops, suffix, tool, uid):
    """管理者不能自授；另建自有真实身份，既不借D1创建者Grant也不绕过防自授。"""
    organization = fixture['organization']
    if not organization.startswith('gov-p1-') or not suffix or not all(c in '0123456789abcdef' for c in suffix):
        raise RuntimeError('coupon delivery UI identity ownership mismatch')
    user = {'name': 'p6-delivery-ui-' + suffix, 'id': uid(), 'password': secrets.token_urlsafe(24)}
    tool.BASE = issuer
    auth = 'Basic ' + base64.b64encode((ops['client_id'] + ':' + ops['client_secret']).encode()).decode()
    target = 'get-user?' + urlencode({'id': organization + '/' + user['name']})
    if tool.request(target, authorization=auth).get('data') is not None:
        raise RuntimeError('coupon delivery UI owned identity already exists')
    tool.request('add-user', {**user, 'owner': organization, 'displayName': 'P6 delivery independent operator',
                            'type': 'normal-user', 'isAdmin': False,
                            'signupApplication': fixture['clients']['management']['name']}, auth)
    actual = tool.request(target, authorization=auth).get('data')
    if not actual or actual.get('id') != user['id'] or actual.get('isAdmin') is not False or actual.get('owner') != organization:
        raise RuntimeError('coupon delivery UI real identity readback mismatch')
    return user


def batch_state(query, q, source, batch):
    """保留D1原状态全部字段，计次/错误变化同样不能被新增UI进度字段掩盖。"""
    return json.loads(query("SELECT JSON_OBJECT('status',status,'mode',mode,'processed',processed,'issued',issued,'skipped',skipped,'revoked',revoked,'kept',kept,'attempts',attempts,'error',error_code,'version',version,'contentVersion',content_version,'issue',issue_source_json,'revoke',revoke_source_json) FROM automation_coupon_batch WHERE tenant_id=" + q(source) + ' AND batch_id=' + q(batch)))


AUDIT_COLUMNS = ('tenant_id', 'actor_id', 'operation', 'command_key', 'principal_id', 'membership_id',
                 'generation', 'execution_id', 'capability', 'resource_type', 'resource_id', 'resource_version',
                 'store_id', 'route_version')
IDENTITY_COLUMNS = ('tenant_id', 'actor_id', 'principal_id', 'membership_id', 'generation')


def identity_rows(query, q, source, targets):
    """逐行JSON避免GROUP_CONCAT截断，审计原键与准确引用也进入冻结比较。"""
    projection = ','.join("'" + column + "'," + column for column in AUDIT_COLUMNS)
    text = query('SELECT JSON_OBJECT(' + projection + ') FROM employee_command_identity WHERE tenant_id=' + q(source)
                 + " AND resource_type='coupon_delivery' AND resource_id IN (" + ','.join(q(batch) for batch in targets)
                 + ') ORDER BY resource_id,capability,command_key')
    return [json.loads(line) for line in text.splitlines() if line]


def verify_identity_rows(rows, expected):
    """精确目标、能力与身份必须逐项一致，五条总数不能掩盖重复、错主体或漏目标。"""
    fields = (*IDENTITY_COLUMNS, 'operation', 'capability', 'resource_type', 'resource_id', 'resource_version', 'store_id')
    actual = [{key: row.get(key) for key in fields} for row in rows]
    wanted = [{key: row[key] for key in fields} for row in expected]
    canonical = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
    if canonical(actual) != canonical(wanted) or any(
            not row.get('execution_id') or not row.get('command_key') or row.get('route_version', 0) <= 0 for row in rows):
        raise RuntimeError('coupon delivery UI exact identity tuples mismatch')
    return rows


def verify_direction_source(value, identity, partition, audit, persisted):
    """首次采样须关联真实命令及持久执行元数据，不能仅凭CENTRAL标签证明来源正确。"""
    if not isinstance(value, dict) or value.get('kind') != 'CENTRAL':
        raise RuntimeError('coupon delivery UI original direction source mismatch')
    actor, execution = value.get('actor', {}), value.get('execution', {})
    route = execution.get('route', {})
    valid = (actor.get('tenantId') == identity['tenant_id'] and actor.get('actorId') == identity['actor_id']
             and actor.get('role') == 'OPERATOR' and actor.get('executionId') == audit.get('execution_id')
             and value.get('commandKey') == audit.get('command_key') and bool(value.get('createdAt'))
             and execution.get('identity') == {'principalId': identity['principal_id'],
                 'membershipId': identity['membership_id'], 'generation': identity['generation']}
             and route.get('tenantId') == identity['tenant_id'] and route.get('authTenantId') == partition['tenant_id']
             and route.get('family') == 'COUPON_DELIVERY' and route.get('state') == 'CENTRAL'
             and route.get('everCentral') is True and route.get('version') == audit.get('route_version')
             and execution.get('applicationId') == partition['application_id']
             and execution.get('environment') == partition['environment']
             and bool(execution.get('callerServiceId')) and bool(execution.get('expiresAt'))
             and execution.get('membershipVersion', 0) > 0 and execution.get('principalVersion', 0) > 0
             and execution == persisted)
    if not valid:
        raise RuntimeError('coupon delivery UI original direction source mismatch')
    return json.loads(json.dumps(value))


def verify_original_grant(plan, identity, partition, grant_id, capability, execution):
    """复核原引用仍包含首次动作的真实有限Grant，不能借其他主体的可用路径。"""
    context = plan.get('context', {})
    if (plan.get('decision') != 'ALLOW' or plan.get('capability') != capability
            or plan.get('resource_type') != RESOURCE_TYPE or context.get('actor_type') != 'HUMAN'
            or context.get('caller_service_id') != execution['callerServiceId']
            or context.get('membership_version') != execution['membershipVersion']
            or context.get('principal_version') != execution['principalVersion']
            or context.get('principal_id') != identity['principal_id']
            or context.get('membership_id') != identity['membership_id']
            or context.get('membership_generation') != identity['generation']
            or context.get('tenant_id') != partition['tenant_id']
            or context.get('application_id') != partition['application_id']
            or context.get('environment') != partition['environment']
            or not any(path.get('grant_id') == grant_id and path.get('scope_version') == 1
                       and path.get('clauses') == [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]
                       for path in plan.get('alternatives', []))):
        raise RuntimeError('coupon delivery UI original execution Grant mismatch')


def observe_first_source(read, capture, operation, timeout=BROWSER_TIMEOUT_SECONDS):
    """丢响应重放前观察首次已提交来源；SQL异常显式传播，浏览器结束后观察线程必须停止。"""
    stop, observed = threading.Event(), {}

    def observe():
        try:
            deadline = time.monotonic() + timeout
            while not stop.is_set() and time.monotonic() < deadline:
                value = read()
                if value is not None:
                    observed['value'] = capture(value)
                    return
                stop.wait(.1)
            raise RuntimeError('coupon delivery UI first source was not observed')
        except Exception as failure:
            observed['error'] = failure

    watcher = threading.Thread(target=observe, name='coupon-delivery-first-source')
    watcher.start()
    try:
        result = operation()
    finally:
        stop.set()
        # 读取来源、审计与持久元数据最多三次SQL，各20秒超时；Grant HTTP在join后执行。
        watcher.join(65)
        if watcher.is_alive():
            raise RuntimeError('coupon delivery UI first source observer did not stop')
    if 'error' in observed:
        raise observed['error']
    if 'value' not in observed:
        raise RuntimeError('coupon delivery UI first source was not observed')
    return result, observed['value']


def recipient_coupon_rows(query, q, source, batch):
    """LEFT JOIN保留缺券错误，逐张证明会员、固定定义及定向来源与真实钱包撤回状态。"""
    return [json.loads(line) for line in query(
        "SELECT JSON_OBJECT('member',r.member_id,'recipient_status',r.status,'recipient_error',r.error_code,"
        "'coupon',r.coupon_id,'coupon_tenant',c.tenant_id,'coupon_member',c.member_id,'coupon_status',c.status,"
        "'definition',c.definition_id,'definition_version',c.definition_version,'source_type',c.source_type,'source_id',c.source_id)"
        ' FROM automation_coupon_recipient r LEFT JOIN benefit_coupon c ON c.tenant_id=r.tenant_id AND c.coupon_id=r.coupon_id'
        ' WHERE r.tenant_id=' + q(source) + ' AND r.batch_id=' + q(batch) + ' ORDER BY r.member_id').splitlines() if line]


def verify_recipient_coupons(rows, source, batch, members, definition, version):
    """数量正确但券缺失、跨会员、错来源或仅回执标成REVOKED均不能写成成功。"""
    if len(rows) != len(members) or sorted(row.get('member') for row in rows) != sorted(members):
        raise RuntimeError('coupon delivery UI recipient coupon join mismatch')
    for row in rows:
        if (row.get('recipient_status') != 'REVOKED' or row.get('recipient_error') is not None
                or not row.get('coupon') or row.get('coupon_tenant') != source
                or row.get('coupon_member') != row['member'] or row.get('coupon_status') != 'REVOKED'
                or row.get('definition') != definition or row.get('definition_version') != version
                or row.get('source_type') != 'TARGETED'
                or row.get('source_id') != hashlib.sha256((batch + '/' + row['member']).encode()).hexdigest()):
            raise RuntimeError('coupon delivery UI recipient coupon join mismatch')
    if len({row['coupon'] for row in rows}) != len(members):
        raise RuntimeError('coupon delivery UI recipient coupon join mismatch')
    return rows


def checked_browser(run, phase):
    """浏览器须绑定当前脚本、真实后端与制品，截图缺失/篡改不能进入后续SQL验证。"""
    script = Path(__file__).with_suffix('.mjs')
    result = json.loads((run / ('coupon-deliveries-ui-browser-' + phase + '.json')).read_text())
    artifact = json.loads((run / 'browser-artifact-fence.json').read_text())
    if (result.get('phase') != phase or result.get('result') != 'PASS' or result.get('backend') != 'REAL'
            or result.get('runtime') != 'PACKAGED_JAR'
            or artifact.get('mode') != 'PACKAGED_JAR'
            or artifact.get('browser_origin') != 'http://127.0.0.1:18665'
            or result.get('browserOrigin') != artifact['browser_origin']
            or result.get('artifactSha256') != artifact.get('jar_sha256')
            or result.get('artifactSha256') != hashlib.sha256((run / 'commerce-browser.jar').read_bytes()).hexdigest()
            or result.get('assetCount', 0) < 1 or result.get('assetCount') != artifact.get('frontend_files')
            or result.get('harnessSha256') != hashlib.sha256(script.read_bytes()).hexdigest()
            or not result.get('checks') or not result.get('screenshots')):
        raise RuntimeError('coupon delivery browser evidence incomplete or mismatched')
    for item in result['screenshots']:
        path = run / item['name']
        if path.parent != run or not path.name.startswith('coupon-deliveries-' + phase + '-'):
            raise RuntimeError('coupon delivery screenshot outside actual phase')
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise RuntimeError('coupon delivery screenshot bytes changed')
    return result


def browser(c, phase):
    """只读取P6专用0600身份夹具；丢真实响应允许，伪造HTTP业务结果不允许。"""
    root = Path(__file__).resolve().parent
    subprocess.run(['node', str(root / 'governance-ce05-coupon-deliveries-ui.mjs')], check=True,
                   timeout=BROWSER_TIMEOUT_SECONDS,
                   env=dict(os.environ, P6_RUN=str(c['run']), P6_PHASE=phase,
                            P6_PLAYWRIGHT_MODULE=str(c['commerce'] / 'frontend/node_modules/@playwright/test')))
    result = checked_browser(c['run'], phase)
    c['record']('coupon delivery UI actual compiled browser ' + phase)
    return result


def rehearse(c):
    """写岗位不持有read；后台21人任务保留外部原创建者，补偿完成前不撤销原control来源。"""
    expect, sql, q, insert, uid, now, projection = (c[k] for k in ('expect', 'sql', 'q', 'insert', 'uid', 'now', 'projection'))
    source, tenant, partition, prefix, admin, run, h = (c[k] for k in ('source', 'tenant', 'partition', 'prefix', 'admin', 'run', 'h'))
    base = '/v1/admin/coupon-deliveries'
    user = [('Authorization', 'Bearer ' + c['ui_token']), ('X-Tenant-Id', tenant)]
    app = c['app']

    def query(statement):
        return sql(statement)[1]

    def require(condition, label):
        if not condition:
            raise RuntimeError('coupon delivery UI SQL invariant: ' + label)
        c['record']('coupon delivery UI ' + label)

    def state(batch):
        return batch_state(query, q, source, batch)

    def identity(member, principal=None):
        projection = ','.join("'" + column + "'," + column for column in IDENTITY_COLUMNS)
        rows = query('SELECT JSON_OBJECT(' + projection + ') FROM central_store_identity_binding WHERE auth_tenant_id='
                     + q(tenant) + ' AND tenant_id=' + q(source) + ' AND membership_id=' + q(member) + ' AND generation=1').splitlines()
        if len(rows) != 1:
            raise RuntimeError('coupon delivery UI exact identity binding mismatch')
        value = json.loads(rows[0])
        if principal is not None and value['principal_id'] != principal:
            raise RuntimeError('coupon delivery UI exact identity binding mismatch')
        return value

    def expected_audit(batch, action, owner):
        return {**owner, 'operation': 'coupon.delivery.' + action, 'capability': 'commerce.coupon_delivery.' + action,
                'resource_type': RESOURCE_TYPE, 'resource_id': batch, 'resource_version': 1, 'store_id': None}

    def check_original_grant(captured, owner):
        audit, persisted = captured['audit'], captured['source']['execution']
        headers = [(key, val) for key, val in c['dual'] if key == 'Authorization']
        status, plan = c['request'](18161, '/internal/governance/v1/access/execution-scope', headers,
                                   {'execution_id': audit['execution_id'], 'check': {'tenant_id': tenant,
                                    'expected_membership_generation': owner['generation'], 'request_id': uid(),
                                    'capability': audit['capability'], 'resource_type': RESOURCE_TYPE}})
        if status != HTTPStatus.OK:
            raise RuntimeError('coupon delivery UI original execution scope failed: ' + str(status))
        verify_original_grant(plan, owner, partition, captured['grant_id'], audit['capability'], persisted)
        return plan

    def capture_source(batch, direction, owner, grant_id, value=None, check_grant=True):
        action = 'create' if direction == 'issue' else 'control'
        expected = expected_audit(batch, action, owner)
        rows = [row for row in identity_rows(query, q, source, (batch,)) if row['capability'] == expected['capability']]
        audit = verify_identity_rows(rows, [expected])[0]
        value = state(batch)[direction] if value is None else value
        persisted = json.loads(query('SELECT source_json FROM employee_coupon_delivery_execution WHERE tenant_id='
                                    + q(source) + ' AND actor_id=' + q(owner['actor_id']) + ' AND execution_id='
                                    + q(audit['execution_id']) + ' AND capability=' + q(expected['capability'])))
        original = verify_direction_source(value, owner, partition, audit, persisted)
        captured = {'source': original, 'audit': audit, 'grant_id': grant_id,
                    'captured_at': now(), 'captured_before_worker_restart': True}
        # 使用服务身份读取原引用；不签发新引用。观察线程只读SQL，HTTP放在join之后。
        if check_grant:
            captured['scope_plan'] = check_original_grant(captured, owner)
        h.private(run / ('coupon-deliveries-ui-first-source-' + ('target' if batch == TARGET else 'created' if batch == CREATED else 'cancelled')
                         + '-' + direction + '.json'), json.dumps(captured, ensure_ascii=False))
        return captured

    def grant(action, member, suffix):
        role = expect('coupon delivery UI real role ' + suffix, 18162, prefix + '/roles', admin,
                      {**partition, 'command_id': uid(), 'role_code': 'ce05-ui-delivery-' + suffix, 'role_version': 1,
                       'capabilities': ['commerce.coupon_delivery.' + action]})['id']
        gid = expect('coupon delivery UI finite grant ' + suffix, 18162, prefix + '/scoped-grants', admin,
                     {**partition, 'command_id': uid(), 'member_id': member, 'member_generation': 1, 'role_id': role,
                      'scope_rule': {'version': 1, 'resource_type': RESOURCE_TYPE,
                                     'clauses': [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]},
                      'source_id': 'ce05-ui-delivery-' + suffix, 'valid_from': now(-2), 'valid_to': now(FIXTURE_GRANT_SECONDS)}, 202)['id']
        projection()
        return gid

    def revoke(gid, label):
        expect('coupon delivery UI revoke ' + label, 18162, prefix + '/revoke', admin,
               {**partition, 'command_id': uid(), 'grant_id': gid, 'expected_version': 1})
        projection()

    def pump(label):
        return expect('coupon delivery UI ' + label, 18661, base + '/pump', user, {})

    def readiness(action):
        path = base + '?storeId=' + c['store'] if action == 'read' else '/v1/operations/coupon-deliveries/' + action + '-access'
        stable = 0
        for _ in range(40):
            status, value = c['request'](18661, path, user)
            stable = stable + 1 if status == HTTPStatus.OK else 0
            if stable >= 4:
                return
            if status not in (HTTPStatus.OK, HTTPStatus.FORBIDDEN):
                raise RuntimeError('coupon delivery UI unexpected readiness ' + str(status))
            time.sleep(.25)
        raise RuntimeError('coupon delivery UI exact role not ready ' + action)

    def independent(action):
        before = query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source))
        for hint in HINTS:
            expect('coupon delivery UI exact hint ' + action + '/' + hint, 18661,
                   '/v1/operations/coupon-deliveries/' + hint + '-access', user, status=200 if action == hint else 403)
        expect('coupon delivery UI exact directory ' + action, 18661, base + '?storeId=' + c['store'], user,
               status=200 if action == 'read' else 403)
        require(before == query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source)), 'hints and read have no command audit ' + action)

    # 使用实际已登记internal HUMAN建立自有OPERATOR映射，中央请求从不使用此随机本地凭据。
    insert('platform_credential', {'tenant_id': source, 'actor_id': 'p6-delivery-ui-operator', 'role': 'OPERATOR',
                                  'token_hash': hashlib.sha256(secrets.token_bytes(48)).hexdigest(),
                                  'expires_at': now(3600).replace('T', ' ').replace('Z', '')})
    insert('central_store_identity_binding', {'auth_tenant_id': tenant, 'principal_id': c['ui_principal'],
                                            'membership_id': c['ui_member'], 'generation': 1, 'tenant_id': source,
                                            'actor_id': 'p6-delivery-ui-operator', 'created_by': 'p6-owned-delivery-ui'})
    external_owner, ui_owner = identity(c['member']), identity(c['ui_member'], c['ui_principal'])
    require(external_owner['principal_id'] != ui_owner['principal_id'], 'distinct original external issuer and UI HUMAN')
    # 原D1已经验证的创建者主体再次与实际受控绑定关联，不能把另一成员当external来源。
    require(all(row['issue']['execution']['identity'] == {'principalId': external_owner['principal_id'],
                'membershipId': external_owner['membership_id'], 'generation': external_owner['generation']}
                for row in c['delivery_states'].values()), 'external issuer matches original D1 identity')
    for audience, count in ((CREATED, 1), (TARGET, AUDIENCE_SIZE), (CANCELLED, 1)):
        insert('marketing_audience_snapshot', {'tenant_id': source, 'audience_id': audience, 'version': 1,
                                             'name': '真实页面专用人群', 'source': 'P6-D2-OWNED',
                                             'watermark': now(-2).replace('T', ' ').replace('Z', ''),
                                             'valid_until': now(7200).replace('T', ' ').replace('Z', ''), 'member_count': count})
        for index in range(count):
            mid = 'ce05-ui-member-' + audience.rsplit(':', 1)[-1][:12] + '-' + str(index).zfill(3)
            insert('member_record', {'tenant_id': source, 'member_id': mid, 'actor_id': mid, 'display_name': '页面隔离会员', 'member_level': 'BASIC', 'status': 'ACTIVE', 'version': 0})
            insert('marketing_audience_member', {'tenant_id': source, 'audience_id': audience, 'version': 1, 'member_id': mid})
    fixture = {'token': c['ui_token'], 'authority': c['ui_authority'], 'client': c['ui_client'],
               'tenant': tenant, 'store': c['store'], 'interactive': c['ui_interactive'], 'user': c['ui_identity'],
               'created': CREATED, 'target': TARGET, 'cancelled': CANCELLED, 'deadline': now(3600),
               'definition': 'ce05-delivery-coupon', 'definitionVersion': 1, 'audienceVersion': 1,
               'audienceSize': AUDIENCE_SIZE, 'maxCalls': MAX_CONFIRMED_CALLS}
    h.private(run / 'coupon-deliveries-ui.json', json.dumps(fixture, ensure_ascii=False))
    baseline = int(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='coupon_delivery'"))
    original_hashes = {batch: hashlib.sha256(json.dumps(row, sort_keys=True).encode()).hexdigest() for batch, row in c['delivery_states'].items()}
    phases = {}
    create_grant = grant('create', c['ui_member'], 'create')
    readiness('create'); independent('create')
    phases['create-only'] = browser(c, 'create-only')
    created_source = capture_source(CREATED, 'issue', ui_owner, create_grant)
    require(state(CREATED)['processed'] == 0 and state(CREATED)['contentVersion'] == 1, 'actual create-only batch and positive content version')
    revoke(create_grant, 'create')
    # 只延后自有未完成任务的调度时点，不伪造取消/成功状态或改写原来源。
    sql('UPDATE automation_coupon_batch SET available_at=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR WHERE tenant_id=' + q(source) + ' AND batch_id=' + q(CREATED))
    pump_grant = grant('pump', c['ui_member'], 'pump')
    readiness('pump'); independent('pump')
    # 旧方向来源已撤销，真实推进直至隔离；不手工写ISOLATED，不清理此前失败任务。
    for index in range(MAX_CONFIRMED_CALLS):
        if state(CREATED)['status'] == 'ISOLATED':
            break
        sql('UPDATE automation_coupon_batch SET available_at=UTC_TIMESTAMP(3) WHERE tenant_id=' + q(source) + ' AND batch_id=' + q(CREATED))
        pump('original create revoked pump ' + str(index))
    require(state(CREATED)['status'] == 'ISOLATED' and state(CREATED)['processed'] == 0, 'revoked original create remains no-effect isolated')
    # 21人待发任务有独立真实原创建者。人工推进岗位自身仍仅PUMP，不能借该创建者的目录权限。
    issuer_grant = grant('create', c['member'], 'source-issuer')
    issue_sources = {}
    for batch in (TARGET, CANCELLED):
        body = {'batchId': batch, 'storeId': c['store'], 'name': '真实页面固定来源', 'definitionId': fixture['definition'],
                'definitionVersion': 1, 'audience': {'id': batch, 'version': 1}, 'deadline': fixture['deadline'], 'minIntervalHours': 24}
        expect('coupon delivery UI actual source issuer ' + batch, 18661, base, c['user'] + [('Idempotency-Key', uid())], body)
        # 创建响应刚确认即保存真实SQL来源，后续PUMP/CONTROL不能成为新的基线。
        issue_sources[batch] = capture_source(batch, 'issue', external_owner, issuer_grant)
    sql('UPDATE automation_coupon_batch SET available_at=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR WHERE tenant_id=' + q(source) + ' AND batch_id=' + q(CANCELLED))
    phases['pump-only'] = browser(c, 'pump-only')
    require(state(TARGET)['status'] == 'COMPLETED' and state(TARGET)['issued'] == AUDIENCE_SIZE, 'actual twenty-one recipients finish with only PUMP')
    fixture['targetVersion'] = state(TARGET)['version']
    h.private(run / 'coupon-deliveries-ui-target.json', json.dumps({'targetVersion': fixture['targetVersion']}))
    revoke(pump_grant, 'pump')
    control_grant = grant('control', c['ui_member'], 'control')
    readiness('control'); independent('control')
    phases['control-only'], revoke_source = observe_first_source(
        lambda: state(TARGET)['revoke'],
        lambda value: capture_source(TARGET, 'revoke', ui_owner, control_grant, value, check_grant=False),
        lambda: browser(c, 'control-only'))
    revoke_source['scope_plan'] = check_original_grant(revoke_source, ui_owner)
    require(state(TARGET)['revoke'] == revoke_source['source'], 'first CONTROL source preserved through exact key replay')
    require(state(TARGET)['issue'] == issue_sources[TARGET]['source'], 'external ISSUE source preserved through pump and control')
    require(revoke_source['audit']['execution_id'] != issue_sources[TARGET]['audit']['execution_id'], 'independent first CONTROL execution reference')
    require(state(CANCELLED)['status'] == 'CANCELLED' and state(CANCELLED)['processed'] == 0, 'actual cancel does not issue or revoke coupons')
    require(state(TARGET)['mode'] == 'REVOKE' and state(TARGET)['status'] == 'REVOKING', 'actual first compensation accepted under independent CONTROL')
    require(state(CREATED)['status'] == 'ISOLATED' and state(CREATED)['processed'] == 0, 'retry cannot revive original revoked create source')
    # 保持首次CONTROL原Grant有效，实际后台完成补偿后才撤权；人工岗位没有暗含PUMP。
    h.stop(app); c['runtime']['COMMERCE_WORKERS_ENABLED'] = 'true'; app = c['start_commerce']('commerce-delivery-ui-compensation')
    deadline = time.monotonic() + 30
    while state(TARGET)['status'] != 'REVOCATION_DONE' and time.monotonic() < deadline:
        time.sleep(.25)
    require(state(TARGET)['status'] == 'REVOCATION_DONE' and state(TARGET)['revoked'] == AUDIENCE_SIZE and state(TARGET)['kept'] == 0, 'real background compensation exact twenty-one')
    h.stop(app); c['runtime']['COMMERCE_WORKERS_ENABLED'] = 'false'; app = c['start_commerce']('commerce-delivery-ui-compensation-stopped')
    revoke(control_grant, 'control')
    read_grant = grant('read', c['ui_member'], 'read')
    readiness('read'); independent('read')
    phases['read-only'] = browser(c, 'read-only')
    revoke(read_grant, 'read')
    phases['revoked'] = browser(c, 'revoked')
    # 最后给同一成员各有限Grant，使实际Auth停服明确表现503，而非本来就缺权的403。
    for action in ACTIONS:
        grant(action, c['ui_member'], 'outage-' + action)
    projection()
    for action in ACTIONS:
        readiness(action)
    require(int(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='coupon_delivery'")) == baseline + 5, 'exact five new original command identities')
    targets = ','.join(q(batch) for batch in (CREATED, TARGET, CANCELLED))
    require(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='coupon_delivery' AND resource_id IN (" + targets + ") AND (resource_version<>1 OR store_id IS NOT NULL)") == '0', 'no fake store or public progress CAS in identity audit')
    require(query('SELECT count(*) FROM automation_coupon_recipient WHERE tenant_id=' + q(source) + ' AND batch_id=' + q(TARGET)) == str(AUDIENCE_SIZE), 'exact actual UI recipient rows')
    expected = [expected_audit(CREATED, 'create', ui_owner), expected_audit(TARGET, 'create', external_owner),
                expected_audit(CANCELLED, 'create', external_owner), expected_audit(CANCELLED, 'control', ui_owner),
                expected_audit(TARGET, 'control', ui_owner)]
    actual_audits = verify_identity_rows(identity_rows(query, q, source, (CREATED, TARGET, CANCELLED)), expected)
    for captured in (created_source, *issue_sources.values(), revoke_source):
        require(captured['audit'] in actual_audits, 'first committed audit key and execution reference preserved ' + captured['audit']['resource_id'])
    for batch, captured in issue_sources.items():
        require(state(batch)['issue'] == captured['source'], 'exact original external ISSUE metadata preserved ' + batch)
    require(state(TARGET)['revoke'] == revoke_source['source'], 'exact first UI CONTROL metadata preserved after compensation and revocation')
    target_members = ['ce05-ui-member-' + TARGET.rsplit(':', 1)[-1][:12] + '-' + str(index).zfill(3) for index in range(AUDIENCE_SIZE)]
    coupons = verify_recipient_coupons(recipient_coupon_rows(query, q, source, TARGET), source, TARGET, target_members,
                                      fixture['definition'], fixture['definitionVersion'])
    require(len(coupons) == AUDIENCE_SIZE, 'exact twenty-one recipient and actual wallet revoked effects')
    for batch, expected in original_hashes.items():
        original = c['delivery_states'][batch]
        current = state(batch)
        # D1状态对象没有skipped字段；比较它原先实际提供的全部字段，不以增量DTO字段制造假差异。
        current = {key: current[key] for key in original}
        require(hashlib.sha256(json.dumps(current, sort_keys=True).encode()).hexdigest() == expected, 'D1 original task and sources preserved ' + batch)
    c['delivery_ui_user'] = user
    result = {'phase': 'PRE_OUTAGE_PASS', 'backend': 'REAL', 'runtime': 'PACKAGED_JAR', 'independent_human': c['ui_principal'],
              'phases': phases, 'new_identity_count': 5, 'actual_recipient_count': AUDIENCE_SIZE,
              'states': {batch: state(batch) for batch in (CREATED, TARGET, CANCELLED)}, 'runtime_switched': False,
              'exact_identity_audits': actual_audits, 'first_sources': {'issue': issue_sources[TARGET], 'revoke': revoke_source},
              'recipient_coupon_rows': coupons, 'first_source_observer': 'JOINED_AND_STOPPED',
              'visual_review': 'UNVERIFIED_UNTIL_IMAGES_OPENED'}
    h.private(run / 'coupon-deliveries-ui-pre-outage-result.json', json.dumps(result, ensure_ascii=False))
    return app


def outage(c):
    """父演练已实际停止Auth进程；页面独立资格与目录均须503且无身份降级。"""
    user = c['delivery_ui_user']
    for action in HINTS:
        c['expect']('coupon delivery UI real outage ' + action, 18661, '/v1/operations/coupon-deliveries/' + action + '-access', user, status=503)
    c['expect']('coupon delivery UI real outage directory', 18661, '/v1/admin/coupon-deliveries?storeId=' + c['store'], user, status=503)
    result = json.loads((c['run'] / 'coupon-deliveries-ui-pre-outage-result.json').read_text())
    result['phases']['outage'] = browser(c, 'outage')
    result.update(phase='PASS', central_outage='REAL_AUTH_PROCESS_STOPPED')
    c['h'].private(c['run'] / 'coupon-deliveries-ui-result.json', json.dumps(result, ensure_ascii=False))
