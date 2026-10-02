"""CE05-D1只消费P6自有隔离库、实际Auth与当前编译商城，不接触原运行环境。"""
import copy
import hashlib
import json
import time
from datetime import datetime, timezone

RESOURCE_TYPE = 'coupon_delivery'
CAPABILITIES = ('coupon_delivery.create', 'coupon_delivery.read', 'coupon_delivery.control', 'coupon_delivery.pump')
MANUAL_QUANTUM = 20
RECIPIENT_PAGE_LIMIT = 50
# 比单轮上限多一人，实际时间预算可能提前让出；验证以真实提交人数核对撤权边界。
FIXTURE_AUDIENCE_SIZE = MANUAL_QUANTUM + 1


def rehearse(c):
    """实际HTTP原键、双方向来源、撤权重授、重启与终态SQL，失败记录不能转换为PASS。"""
    expect, sql, q, insert = (c[k] for k in ('expect', 'sql', 'q', 'insert'))
    record, uid, now, projection, ready = (c[k] for k in ('record', 'uid', 'now', 'projection', 'execution_ready'))
    source, tenant, partition, user, admin, dual = (c[k] for k in ('source', 'tenant', 'partition', 'user', 'admin', 'dual'))
    prefix, run, h, runtime, start, app, store = (c[k] for k in ('prefix', 'run', 'h', 'runtime', 'start_commerce', 'app', 'store'))
    base, grants, audits = '/v1/admin/coupon-deliveries', {}, []

    def query(statement):
        return sql(statement)[1]

    def checked(condition, label):
        if not condition:
            raise RuntimeError('coupon delivery invariant failed: ' + label)
        record('coupon delivery ' + label)

    def post(label, path, body=None, status=200, headers=None):
        return expect('coupon delivery ' + label, 18661, path, headers or user + [('Idempotency-Key', uid())], {} if body is None else body, status)

    def grant(code, version=1):
        role = expect('coupon delivery role ' + code + '/' + str(version), 18162, prefix + '/roles', admin,
                      {**partition, 'command_id': uid(), 'role_code': 'ce05-' + code.replace('.', '-'), 'role_version': version, 'capabilities': ['commerce.' + code]})['id']
        gid = expect('coupon delivery finite grant ' + code + '/' + str(version), 18162, prefix + '/scoped-grants', admin,
                     {**partition, 'command_id': uid(), 'member_id': c['member'], 'member_generation': 1, 'role_id': role,
                      'scope_rule': {'version': 1, 'resource_type': RESOURCE_TYPE, 'clauses': [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]},
                      'source_id': 'ce05-' + code + '-' + str(version), 'valid_from': now(-2), 'valid_to': now(1200)}, 202)['id']
        projection()
        ready(code, phase='coupon-delivery-' + str(version), resource_type=RESOURCE_TYPE)
        grants[code] = gid

    def revoke(code):
        expect('coupon delivery revoke original ' + code, 18162, prefix + '/revoke', admin,
               {**partition, 'command_id': uid(), 'grant_id': grants[code], 'expected_version': 1})
        projection()

    def state(batch):
        return json.loads(query("SELECT JSON_OBJECT('status',status,'mode',mode,'processed',processed,'issued',issued,'revoked',revoked,'kept',kept,'attempts',attempts,'error',error_code,'version',version,'contentVersion',content_version,'issue',issue_source_json,'revoke',revoke_source_json) FROM automation_coupon_batch WHERE tenant_id=" + q(source) + ' AND batch_id=' + q(batch)))

    def digest(batch, direction):
        return hashlib.sha256(json.dumps(state(batch)[direction], sort_keys=True).encode()).hexdigest()

    def available(batch, seconds=0):
        sql('UPDATE automation_coupon_batch SET available_at=UTC_TIMESTAMP(3)+INTERVAL ' + str(seconds) + ' SECOND WHERE tenant_id=' + q(source) + ' AND batch_id=' + q(batch))

    def restart(label, workers=False):
        nonlocal app
        h.stop(app)
        runtime['COMMERCE_WORKERS_ENABLED'] = str(workers).lower()
        app = start(label)
        record('coupon delivery actual process restart ' + label)

    def batch_input(batch, audience):
        return {'batchId': batch, 'storeId': store, 'name': 'Real central coupon batch', 'definitionId': 'ce05-delivery-coupon', 'definitionVersion': 1,
                'audience': {'id': audience, 'version': 1}, 'deadline': now(3600), 'minIntervalHours': 24}

    def create(batch, audience):
        body, headers = batch_input(batch, audience), user + [('Idempotency-Key', uid())]
        result = post('create ' + batch, base, body, headers=headers)
        audits.append(('commerce.coupon_delivery.create', batch))
        checked(post('original create key ' + batch, base, body, headers=headers) == result, 'stable original create receipt ' + batch)
        checked(result['version'] == 0 and state(batch)['contentVersion'] == 1, 'CAS zero and actual positive content version ' + batch)
        return body, headers

    def control(batch, action):
        body = {'expectedVersion': state(batch)['version'], 'action': action, 'reason': 'Real original source rehearsal'}
        headers = user + [('Idempotency-Key', uid())]
        result = post('control ' + batch + '/' + action, base + '/' + batch + '/control', body, headers=headers)
        audits.append(('commerce.coupon_delivery.control', batch))
        checked(post('original control key ' + batch, base + '/' + batch + '/control', body, headers=headers) == result, 'stable original control receipt ' + batch)
        return body, headers

    # 专用业务夹具进入新数据库，不硬编码在页面，也不借用其他角色的读取/创建资格。
    insert('benefit_coupon_definition', {'tenant_id': source, 'definition_id': 'ce05-delivery-coupon', 'version': 1, 'store_id': store, 'name': 'Owned delivery fixture',
                                        'minimum_spend': '0.00', 'discount_amount': '1.00', 'valid_from': now(-10).replace('T', ' ').replace('Z', ''),
                                        'valid_to': now(7200).replace('T', ' ').replace('Z', ''), 'quota': 200, 'stackable': 1, 'platform_funding_bps': 10000, 'issuance_mode': 'SOURCE_ONLY', 'validity_days': 366})
    for audience, count in (('ce05-delivery-a', FIXTURE_AUDIENCE_SIZE), ('ce05-delivery-c', FIXTURE_AUDIENCE_SIZE), ('ce05-delivery-outage', 1)):
        insert('marketing_audience_snapshot', {'tenant_id': source, 'audience_id': audience, 'version': 1, 'name': 'Owned fixed delivery audience', 'source': 'P6-D1-FIXTURE',
                                             'watermark': now(-2).replace('T', ' ').replace('Z', ''), 'valid_until': now(7200).replace('T', ' ').replace('Z', ''), 'member_count': count})
        for index in range(count):
            mid = audience + '-' + str(index).zfill(3)
            insert('member_record', {'tenant_id': source, 'member_id': mid, 'actor_id': mid, 'display_name': 'Owned delivery member', 'member_level': 'BASIC', 'status': 'ACTIVE', 'version': 0})
            insert('marketing_audience_member', {'tenant_id': source, 'audience_id': audience, 'version': 1, 'member_id': mid})

    insert('employee_authority_route', {'tenant_id': source, 'auth_tenant_id': tenant, 'family': 'COUPON_DELIVERY', 'state': 'SHADOW'})
    sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=" + q(source) + " AND family='COUPON_DELIVERY' AND state='SHADOW' AND version=1")
    expect('coupon delivery unrelated roles do not imply read', 18661, base + '?storeId=' + store, user, status=403)
    expect('coupon delivery old ADMIN denied', 18661, base + '?storeId=' + store, c['local_admin'], status=403)
    grant('coupon_delivery.create')
    a, revoked_batch = 'ce05-delivery-a', 'ce05-delivery-c'
    create_body, create_headers = create(a, a)
    expect('coupon delivery create not read', 18661, base + '?storeId=' + store, user, status=403)
    post('create not pump', base + '/pump', status=403)
    post('create not control', base + '/' + a + '/control', {'expectedVersion': 0, 'action': 'CANCEL', 'reason': 'Independent capability'}, 403)
    original = state(a)['issue']
    original_hash = digest(a, 'issue')
    checked(original['kind'] == 'CENTRAL' and original['actor']['executionId'] and 'token' not in json.dumps(original).lower(), 'exact original HUMAN metadata without Token')
    checked((datetime.fromisoformat(original['execution']['expiresAt'].replace('Z', '+00:00')) - datetime.now(timezone.utc)).total_seconds() > 604700, 'finite durable signed expiry distinct from business deadline')
    grant('coupon_delivery.pump')
    post('first bounded issuance', base + '/pump')
    first_issue_count = state(a)['processed']
    checked(0 < first_issue_count <= MANUAL_QUANTUM and state(a)['issued'] == first_issue_count and state(a)['status'] == 'RUNNING', 'first real issuance respects twenty-recipient and cooperative time budget')
    restart('commerce-coupon-delivery-issued-restart')
    for index in range(FIXTURE_AUDIENCE_SIZE):
        if state(a)['status'] == 'COMPLETED':
            break
        post('resumed bounded issuance ' + str(index), base + '/pump')
    checked(state(a)['status'] == 'COMPLETED' and state(a)['issued'] == FIXTURE_AUDIENCE_SIZE and digest(a, 'issue') == original_hash, 'actual restart retains source and finishes remaining recipient')
    create(revoked_batch, revoked_batch)
    post('first twenty before original revoke', base + '/pump')
    stopped_issue_count = state(revoked_batch)['processed']
    checked(0 < stopped_issue_count <= MANUAL_QUANTUM and state(revoked_batch)['issued'] == stopped_issue_count, 'actual committed issuance before original Grant revoke is bounded')
    stopped_issue_hash = digest(revoked_batch, 'issue')
    revoke('coupon_delivery.create')
    grant('coupon_delivery.create', 2)
    for index in range(5):
        available(revoked_batch)
        post('old issue cannot borrow regrant ' + str(index), base + '/pump')
    checked(state(revoked_batch)['processed'] == stopped_issue_count and state(revoked_batch)['status'] == 'ISOLATED' and digest(revoked_batch, 'issue') == stopped_issue_hash, 'regrant never revives original issuance and exact committed coupons remain')
    post('old create receipt still denied', base, create_body, 403, create_headers)
    grant('coupon_delivery.read')
    expect('coupon delivery independent directory', 18661, base + '?storeId=' + store, user)
    checked(len(expect('coupon delivery actual parent receipts', 18661, base + '/' + a + '/recipients?limit=' + str(RECIPIENT_PAGE_LIMIT), user)) == FIXTURE_AUDIENCE_SIZE, 'actual parent read uses twenty-one committed receipts')
    expect('coupon delivery foreign parent does not disclose members', 18661, base + '/foreign/recipients', user, status=404)
    grant('coupon_delivery.control')
    control(a, 'REVOKE')
    revoke_hash = digest(a, 'revoke')
    checked(digest(a, 'issue') == original_hash and state(a)['revoke']['actor']['executionId'] != original['actor']['executionId'], 'first independent compensation keeps original issuance')
    post('first twenty compensation effects', base + '/pump')
    first_revoke_count = state(a)['revoked']
    checked(0 < first_revoke_count <= MANUAL_QUANTUM and state(a)['status'] == 'REVOKING', 'compensation has real bounded checkpoint')
    revoke('coupon_delivery.control')
    grant('coupon_delivery.control', 2)
    for index in range(5):
        available(a)
        post('old compensation cannot borrow regrant ' + str(index), base + '/pump')
    checked(state(a)['revoked'] == first_revoke_count and state(a)['status'] == 'ISOLATED' and digest(a, 'revoke') == revoke_hash, 'compensation regrant preserves incomplete result and first source')
    for action in ('RETRY', 'REVOKE'):
        post('old compensation no renewal ' + action, base + '/' + a + '/control', {'expectedVersion': state(a)['version'], 'action': action, 'reason': 'Cannot renew first source'}, 403)
    available(a, 3600)
    control(revoked_batch, 'REVOKE')
    for index in range(FIXTURE_AUDIENCE_SIZE):
        if state(revoked_batch)['status'] == 'REVOCATION_DONE':
            break
        post('independent bounded compensation after revoked issuance ' + str(index), base + '/pump')
    checked(state(revoked_batch)['status'] == 'REVOCATION_DONE' and state(revoked_batch)['revoked'] == stopped_issue_count and digest(revoked_batch, 'issue') == stopped_issue_hash, 'new independent compensation does not revive old ISSUE')

    # 只给全新明确标记的测试任务使用真实Auth六秒引用；不替换任何Owner POST创建的旧来源。
    expiry_batches, expiries = [], []
    for direction, code in (('ISSUE', 'coupon_delivery.create'), ('REVOKE', 'coupon_delivery.control')):
        ref = expect('coupon delivery actual short reference ' + direction, 18161, '/internal/governance/v1/access/executions', dual,
                     {'check': {'tenant_id': tenant, 'expected_membership_generation': 1, 'request_id': uid(), 'capability': 'commerce.' + code, 'resource_type': RESOURCE_TYPE}, 'expires_at': now(6)})
        ctx, execution = ref['context'], copy.deepcopy(original['execution'])
        execution.update(identity={'principalId': ctx['principal_id'], 'membershipId': ctx['membership_id'], 'generation': ctx['membership_generation']},
                         applicationId=ctx['application_id'], environment=ctx['environment'], callerServiceId=ctx['caller_service_id'],
                         membershipVersion=ctx['membership_version'], principalVersion=ctx['principal_version'], expiresAt=ref['expires_at'])
        actor = {**original['actor'], 'executionId': ref['execution_id']}
        captured = {'kind': 'CENTRAL', 'actor': actor, 'execution': execution, 'commandKey': uid(), 'createdAt': now()}
        insert('employee_coupon_delivery_execution', {'tenant_id': source, 'actor_id': actor['actorId'], 'execution_id': ref['execution_id'], 'capability': 'commerce.' + code, 'source_json': json.dumps(execution)})
        batch = 'ce05-delivery-expiry-' + direction.lower()
        insert('automation_coupon_batch', {'tenant_id': source, 'batch_id': batch, 'store_id': store, 'content_json': json.dumps(batch_input(batch, 'ce05-delivery-outage')),
                                           'mode': direction, 'status': 'RUNNING' if direction == 'ISSUE' else 'REVOKING', 'available_at': now().replace('T', ' ').replace('Z', ''),
                                           'content_version': 1, 'issue_source_json' if direction == 'ISSUE' else 'revoke_source_json': json.dumps(captured)})
        expiry_batches.append((batch, 'issue' if direction == 'ISSUE' else 'revoke', digest(batch, 'issue' if direction == 'ISSUE' else 'revoke')))
        expiries.append(datetime.fromisoformat(ref['expires_at'].replace('Z', '+00:00')))
    deadline = time.monotonic() + 9
    while datetime.now(timezone.utc) <= max(expiries):
        if time.monotonic() >= deadline:
            raise RuntimeError('coupon delivery short source expiry wait exceeded')
        time.sleep(.2)
    restart('commerce-coupon-delivery-expired-source-background', workers=True)
    deadline = time.monotonic() + 15
    while not all(state(batch)['attempts'] > 0 for batch, _, _ in expiry_batches):
        if time.monotonic() >= deadline:
            raise RuntimeError('coupon delivery expired background sources not exercised')
        time.sleep(.25)
    restart('commerce-coupon-delivery-expired-worker-stopped')
    for batch, direction, expected_hash in expiry_batches:
        checked(state(batch)['processed'] == 0 and state(batch)['revoked'] == 0 and state(batch)['status'] != 'REVOCATION_DONE' and digest(batch, direction) == expected_hash, 'actual expired source cannot commit or falsely complete ' + direction)
        available(batch, 3600)

    sql("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=" + q(source) + " AND family='COUPON_DELIVERY'")
    expect('coupon delivery STOPPED current reader denied', 18661, base + '?storeId=' + store, user, status=403)
    expect('coupon delivery STOPPED legacy ADMIN denied', 18661, base + '?storeId=' + store, c['local_admin'], status=403)
    sql("UPDATE employee_authority_route SET state='CENTRAL',version=version+1 WHERE tenant_id=" + q(source) + " AND family='COUPON_DELIVERY'")
    outage_batch = 'ce05-delivery-outage'
    create(outage_batch, outage_batch)
    available(outage_batch, 3600)
    c['delivery_outage'] = outage_batch
    c['delivery_outage_source_hash'] = digest(outage_batch, 'issue')
    c['delivery_states'] = {batch: state(batch) for batch in (a, revoked_batch, outage_batch)}
    checked(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='coupon_delivery'") == str(len(audits)), 'exact immutable command identity count')
    checked(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='coupon_delivery' AND (resource_version<>1 OR store_id IS NOT NULL)") == '0', 'no progress CAS or fake store in identity audit')
    expected_audits = sorted(f'{batch}:{code}:1' for code, batch in audits)
    actual_audits = query("SELECT GROUP_CONCAT(CONCAT(resource_id,':',capability,':',n) ORDER BY resource_id,capability) FROM (SELECT resource_id,capability,count(*) n FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='coupon_delivery' GROUP BY resource_id,capability) t").split(',')
    checked(actual_audits == expected_audits, 'exact original capability target audit tuples once')
    checked(query("SELECT count(*) FROM automation_coupon_recipient WHERE tenant_id=" + q(source)) == str(FIXTURE_AUDIENCE_SIZE + stopped_issue_count), 'exact committed recipient count preserved')
    checked(query("SELECT count(*) FROM benefit_coupon WHERE tenant_id=" + q(source) + " AND definition_id='ce05-delivery-coupon' AND status='REVOKED'") == str(first_revoke_count + stopped_issue_count), 'exact real compensation effects with unprocessed coupons retained')
    h.private(run / 'coupon-deliveries-owner-pre-outage-result.json', json.dumps({'phase': 'PRE_OUTAGE_PASS', 'audits': audits, 'source_hashes': {'issue_a': original_hash, 'issue_c': stopped_issue_hash, 'revoke_a': revoke_hash},
               'observed_counts': {'first_issue': first_issue_count, 'stopped_issue': stopped_issue_count, 'first_revoke': first_revoke_count, 'total_recipients': FIXTURE_AUDIENCE_SIZE + stopped_issue_count, 'total_revoked': first_revoke_count + stopped_issue_count},
               'expiry_fixture': 'ACTUAL_AUTH_SHORT_REFERENCE_NEW_TASKS_NOT_POST_TTL', 'states': c['delivery_states'], 'runtime_switched': False}, ensure_ascii=False))
    return app


def outage(c):
    """实际Auth进程停止后，员工入口503且后台原来源不继续履约，已提交券和检查点保留。"""
    expect, sql, q, source = (c[k] for k in ('expect', 'sql', 'q', 'source'))
    expect('coupon delivery central outage read denied', 18661, '/v1/admin/coupon-deliveries?storeId=' + c['store'], c['user'], status=503)
    expect('coupon delivery central outage pump denied', 18661, '/v1/admin/coupon-deliveries/pump', c['user'], {}, 503)
    batch = c['delivery_outage']
    sql('UPDATE automation_coupon_batch SET available_at=UTC_TIMESTAMP(3) WHERE tenant_id=' + q(source) + ' AND batch_id=' + q(batch))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        text = sql("SELECT JSON_OBJECT('processed',processed,'status',status,'attempts',attempts,'error',error_code,'source',issue_source_json) FROM automation_coupon_batch WHERE tenant_id=" + q(source) + ' AND batch_id=' + q(batch))[1]
        row = json.loads(text)
        if row['error'] == 'DEPENDENCY_UNAVAILABLE':
            actual_hash = hashlib.sha256(json.dumps(row['source'], sort_keys=True).encode()).hexdigest()
            if row['processed'] != 0 or row['attempts'] != 0 or row['status'] != 'RUNNING' or actual_hash != c['delivery_outage_source_hash']:
                raise RuntimeError('coupon delivery outage altered original source or committed effects')
            break
        time.sleep(.25)
    else:
        raise RuntimeError('coupon delivery actual central outage not exercised by background')
    c['record']('coupon delivery actual outage preserves source and zero uncommitted effects')
    result = json.loads((c['run'] / 'coupon-deliveries-owner-pre-outage-result.json').read_text())
    result.update(phase='PASS', central_outage='REAL_AUTH_PROCESS_STOPPED', outage_row=row)
    c['h'].private(c['run'] / 'coupon-deliveries-owner-result.json', json.dumps(result, ensure_ascii=False))
