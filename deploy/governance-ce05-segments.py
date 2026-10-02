"""CE05-S1真实原来源演练：只消费P6新建库与身份，不接触原商城或共享业务数据。"""
import copy
import hashlib
import json
import time
from datetime import datetime, timezone

RESOURCE_TYPE = 'segment'
CAPABILITIES = ('segment.read', 'segment.create', 'segment.schedule', 'segment.refresh', 'segment.control', 'segment.pump')


def rehearse(c):
    """HTTP命令、原执行路径与独立周期政策均通过实际编译Owner，失败不改写为PASS。"""
    expect, request, sql, q, insert = (c[k] for k in ('expect', 'request', 'sql', 'q', 'insert'))
    record, uid, now, projection, ready = (c[k] for k in ('record', 'uid', 'now', 'projection', 'execution_ready'))
    source, tenant, partition, user, admin, dual = (c[k] for k in ('source', 'tenant', 'partition', 'user', 'admin', 'dual'))
    prefix, run, h, runtime, start, app = (c[k] for k in ('prefix', 'run', 'h', 'runtime', 'start_commerce', 'app'))
    base = '/v1/admin/segments'
    grants, audits = {}, []

    def query(statement):
        return sql(statement)[1]

    def checked(condition, name):
        if not condition:
            raise RuntimeError('segment invariant failed: ' + name)
        record('segment ' + name)

    def post(name, path, body=None, status=200, headers=None):
        return expect('segment ' + name, 18661, path, headers or user + [('Idempotency-Key', uid())], {} if body is None else body, status)

    def grant(code, version=1):
        role = expect('segment finite role ' + code + '/' + str(version), 18162, prefix + '/roles', admin,
                      {**partition, 'command_id': uid(), 'role_code': 'ce05-' + code.replace('.', '-'), 'role_version': version, 'capabilities': ['commerce.' + code]})['id']
        gid = expect('segment independent grant ' + code + '/' + str(version), 18162, prefix + '/scoped-grants', admin,
                     {**partition, 'command_id': uid(), 'member_id': c['member'], 'member_generation': 1, 'role_id': role,
                      'scope_rule': {'version': 1, 'resource_type': RESOURCE_TYPE, 'clauses': [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]},
                      'source_id': 'ce05-' + code + '-' + str(version), 'valid_from': now(-2), 'valid_to': now(1200)}, 202)['id']
        projection()
        ready(code, phase='segment-' + str(version), resource_type=RESOURCE_TYPE)
        grants[code] = gid
        return gid

    def revoke(code):
        expect('segment revoke original ' + code, 18162, prefix + '/revoke', admin,
               {**partition, 'command_id': uid(), 'grant_id': grants[code], 'expected_version': 1})
        projection()

    def definition(name, version=7):
        return {'segmentId': name, 'version': version, 'name': 'Real central dynamic segment',
                'rule': {'kind': 'COMPARE', 'field': 'memberStatus', 'operator': 'EQ', 'valueType': 'TEXT', 'value': 'ACTIVE'},
                'ttlSeconds': 3600, 'refreshSeconds': 60, 'maxMembers': 1000}

    def create(name, version=7):
        result = post('create fixed definition ' + name, base, definition(name, version))
        audits.append(('commerce.segment.create', name, version))
        return result

    def refresh(name, version=7, headers=None):
        result = post('refresh ' + name, base + '/' + name + '/refresh', headers=headers)
        audits.append(('commerce.segment.refresh', name, version))
        checked(result['segmentId'] == name and result['definitionVersion'] == version, 'actual parent definition for ' + name)
        return result

    def schedule(name, expected, enabled, version=7):
        result = post('schedule ' + name + '/' + str(enabled), base + '/' + name + '/schedule', {'expectedVersion': expected, 'enabled': enabled})
        audits.append(('commerce.segment.schedule', name, version))
        return result

    def control(target, action, headers=None):
        result = post('control ' + action, '/v1/admin/segment-runs/' + target['runId'] + '/' + action, headers=headers)
        audits.append(('commerce.segment.control', target['segmentId'], target['definitionVersion']))
        return result

    def state(target):
        text = query('SELECT JSON_OBJECT(\'status\',status,\'processed\',processed,\'matched\',matched,\'attempts\',attempts,\'entries\',entries_announced,\'entryAttempts\',entry_attempts,\'source\',execution_source_json) FROM marketing_segment_run WHERE tenant_id=' + q(source) + ' AND run_id=' + q(target['runId']))
        return json.loads(text)

    def make_ready(target):
        sql('UPDATE marketing_segment_run SET available_at=UTC_TIMESTAMP(3),entry_available_at=UTC_TIMESTAMP(3) WHERE tenant_id=' + q(source) + ' AND run_id=' + q(target['runId']))

    def events(target):
        return int(query("SELECT count(*) FROM platform_event WHERE tenant_id=" + q(source) + " AND event_type='segment.member.entered.v1' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.segmentId'))=" + q(target['segmentId'])))

    def source_hash(target):
        return hashlib.sha256(json.dumps(state(target)['source'], sort_keys=True).encode()).hexdigest()

    def restart(label, workers=False):
        nonlocal app
        h.stop(app)
        runtime['COMMERCE_WORKERS_ENABLED'] = str(workers).lower()
        app = start(label)
        record('segment actual process restart ' + label)

    insert('employee_authority_route', {'tenant_id': source, 'auth_tenant_id': tenant, 'family': 'SEGMENT', 'state': 'SHADOW'})
    sql("UPDATE employee_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id=" + q(source) + " AND family='SEGMENT' AND state='SHADOW' AND version=1")
    expect('segment unrelated roles do not grant read', 18661, base, user, status=403)
    expect('segment old ADMIN cannot bypass', 18661, base, c['local_admin'], status=403)
    grant('segment.create')
    original_headers = user + [('Idempotency-Key', uid())]
    original_definition = definition('ce05-segment-manual')
    created = post('create without read', base, original_definition, headers=original_headers)
    audits.append(('commerce.segment.create', original_definition['segmentId'], 7))
    checked(post('create original key', base, original_definition, headers=original_headers) == created, 'create old receipt once')
    expect('segment create does not imply read', 18661, base, user, status=403)
    post('create does not imply refresh', base + '/ce05-segment-manual/refresh', status=403)
    post('create does not imply pump', base + '/pump', status=403)
    post('create does not imply schedule', base + '/ce05-segment-manual/schedule', {'expectedVersion': 0, 'enabled': True}, 403)
    grant('segment.refresh')
    manual_headers = user + [('Idempotency-Key', uid())]
    manual = refresh('ce05-segment-manual', headers=manual_headers)
    checked(post('refresh original key', base + '/ce05-segment-manual/refresh', headers=manual_headers) == manual, 'refresh old receipt once')
    checked(refresh('ce05-segment-manual')['runId'] == manual['runId'], 'active task returned without replacement')
    original_source_hash = source_hash(manual)
    original_source = state(manual)['source']
    checked(original_source['kind'] == 'MANUAL' and original_source['actor']['executionId'], 'original HUMAN execution persisted')
    until = datetime.fromisoformat(original_source['execution']['expiresAt'].replace('Z', '+00:00'))
    checked((until - datetime.now(timezone.utc)).total_seconds() > 86300, 'exact durable reference distinct from commit window')
    checked(not any(key.lower().endswith('token') for key in original_source['actor']), 'no Token in original actor')
    grant('segment.control')
    grant('segment.schedule')
    grant('segment.read')
    grant('segment.pump')
    expect('segment valid real directory', 18661, base, user)
    expect('segment actual run list', 18661, base + '/ce05-segment-manual/runs', user)
    expect('segment foreign tenant denied', 18661, base, [('Authorization', c['user_authorization']), ('X-Tenant-Id', uid())], status=403)
    expect('segment invalid token denied', 18661, base, [('Authorization', 'Bearer invalid'), ('X-Tenant-Id', tenant)], status=401)
    post('segment unknown control denied', '/v1/admin/segment-runs/' + manual['runId'] + '/unknown', status=401)
    for index in range(105):
        insert('member_record', {'tenant_id': source, 'member_id': 'ce05-segment-member-' + str(index).zfill(3), 'actor_id': 'ce05-segment-customer-' + str(index), 'display_name': 'Isolated segment fixture', 'member_level': 'BASIC', 'status': 'ACTIVE', 'version': 0})
    # startedAt is the original member cutoff; create another actual run after the fixture members exist.
    control(manual, 'cancel')
    manual_headers = user + [('Idempotency-Key', uid())]
    manual = refresh('ce05-segment-manual', headers=manual_headers)
    original_source = state(manual)['source']
    original_source_hash = source_hash(manual)
    post('first bounded member batch', base + '/pump')
    checked(state(manual)['processed'] == 100 and state(manual)['status'] == 'RUNNING', 'first batch commits exactly one hundred')
    restart('commerce-segment-manual-recovery')
    post('resumed member batch', base + '/pump')
    checked(state(manual)['status'] == 'COMPLETED' and state(manual)['matched'] >= 105, 'restart resumes fixed original task')
    checked(source_hash(manual) == original_source_hash, 'restart preserves original source exactly')
    announced = events(manual)
    checked(announced == 100 and not state(manual)['entries'], 'first announcement remains bounded')
    revoke('segment.refresh')
    make_ready(manual)
    post('revoked original announcement cannot progress', base + '/pump')
    checked(events(manual) == announced and not state(manual)['entries'], 'revocation preserves committed snapshot and Outbox')
    grant('segment.refresh', 2)
    denied = expect('segment original reference stays denied after regrant', 18161, '/internal/governance/v1/access/execution-scope', dual,
                    {'execution_id': original_source['actor']['executionId'], 'check': {'tenant_id': tenant, 'expected_membership_generation': 1, 'request_id': uid(), 'capability': 'commerce.segment.refresh', 'resource_type': RESOURCE_TYPE}})
    checked(denied['decision'] == 'DENY', 'new Grant cannot revive original reference')
    post('old refresh receipt cannot revive original', base + '/ce05-segment-manual/refresh', headers=manual_headers, status=403)
    post('new control cannot replace revoked creator', '/v1/admin/segment-runs/' + manual['runId'] + '/retry-announcement', status=403)
    checked(source_hash(manual) == original_source_hash and events(manual) == announced, 'creator and committed effects survive denial')

    create('ce05-segment-control')
    target = refresh('ce05-segment-control')
    sql("UPDATE marketing_segment_run SET status='ISOLATED',attempts=5 WHERE tenant_id=" + q(source) + ' AND run_id=' + q(target['runId']))
    headers = user + [('Idempotency-Key', uid())]
    retried = control(target, 'retry', headers)
    checked(post('retry old receipt', '/v1/admin/segment-runs/' + target['runId'] + '/retry', headers=headers) == retried, 'retry once from original checkpoint')
    for _ in range(3):
        make_ready(target)
        post('finish resumed control task', base + '/pump')
        if state(target)['status'] == 'COMPLETED' and state(target)['entries']:
            break
    checked(state(target)['status'] == 'COMPLETED' and state(target)['entries'], 'control recovery completes original task')
    previous_events = events(target)
    sql('UPDATE marketing_segment_run SET entries_announced=FALSE,entry_attempts=5 WHERE tenant_id=' + q(source) + ' AND run_id=' + q(target['runId']))
    control(target, 'retry-announcement')
    make_ready(target)
    post('announcement recovery resumes checkpoint', base + '/pump')
    checked(state(target)['entries'] and events(target) == previous_events, 'announcement recovery never duplicates committed events')

    create('ce05-segment-policy')
    schedule('ce05-segment-policy', 0, True)
    revoke('segment.schedule')
    post('independent approved policy begins', base + '/pump')
    policy_runs = expect('segment policy run list', 18661, base + '/ce05-segment-policy/runs', user)
    checked(len(policy_runs) == 1, 'policy begins one real task')
    periodic = policy_runs[0]
    policy_hash = source_hash(periodic)
    checked(state(periodic)['source']['kind'] == 'SYSTEM' and state(periodic)['source'].get('actor') is None, 'periodic policy does not impersonate employee')
    grant('segment.schedule', 2)
    schedule('ce05-segment-policy', 1, False)
    create('ce05-segment-policy', 8)
    revoke('segment.schedule')
    revoke('segment.pump')
    post('manual pump cannot borrow system responsibility', base + '/pump', status=403)
    restart('commerce-segment-independent-policy-background', workers=True)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if state(periodic)['status'] == 'COMPLETED' and state(periodic)['entries']:
            break
        time.sleep(.25)
    else:
        raise RuntimeError('segment approved periodic background did not complete')
    checked(source_hash(periodic) == policy_hash and periodic['definitionVersion'] == 7, 'policy survives stop future schedule and new definition with fixed version')
    checked(query('SELECT count(*) FROM marketing_segment_run WHERE tenant_id=' + q(source) + " AND segment_id='ce05-segment-policy'") == '1', 'new definition creates no future unapproved task')
    restart('commerce-segment-policy-worker-stopped')
    grant('segment.pump', 2)
    grant('segment.schedule', 3)
    create('ce05-segment-outage')
    schedule('ce05-segment-outage', 0, True)
    post('approved outage policy first batch', base + '/pump')
    outage = expect('segment outage task list', 18661, base + '/ce05-segment-outage/runs', user)[0]
    checked(state(outage)['processed'] == 100, 'outage policy has real durable checkpoint')
    revoke('segment.schedule')
    sql("UPDATE employee_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id=" + q(source) + " AND family='SEGMENT'")
    expect('segment STOPPED denies current employee', 18661, base, user, status=403)
    expect('segment STOPPED denies old ADMIN', 18661, base, c['local_admin'], status=403)
    make_ready(outage)
    restart('commerce-segment-stopped-background', workers=True)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if state(outage)['attempts'] > 0:
            break
        time.sleep(.25)
    else:
        raise RuntimeError('segment STOPPED system policy not exercised')
    checked(state(outage)['processed'] == 100 and state(outage)['status'] != 'COMPLETED', 'STOPPED blocks actual system policy batch')
    restart('commerce-segment-stopped-background-finished')
    sql("UPDATE employee_authority_route SET state='CENTRAL',version=version+1 WHERE tenant_id=" + q(source) + " AND family='SEGMENT'")
    sql('UPDATE marketing_segment_run SET available_at=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR WHERE tenant_id=' + q(source) + ' AND run_id=' + q(outage['runId']))

    create('ce05-segment-expiry')
    # Explicit short fixture uses a real HUMAN reference and exact deadline issued by Auth; no old task source is replaced.
    reference = expect('segment real short expiry reference', 18161, '/internal/governance/v1/access/executions', dual,
                       {'check': {'tenant_id': tenant, 'expected_membership_generation': 1, 'request_id': uid(), 'capability': 'commerce.segment.refresh', 'resource_type': RESOURCE_TYPE}, 'expires_at': now(6)})
    ctx = reference['context']
    route_row = query("SELECT version FROM employee_authority_route WHERE tenant_id=" + q(source) + " AND family='SEGMENT'")
    captured = copy.deepcopy(original_source['execution'])
    captured.update(identity={'principalId': ctx['principal_id'], 'membershipId': ctx['membership_id'], 'generation': ctx['membership_generation']},
                    applicationId=ctx['application_id'], environment=ctx['environment'], callerServiceId=ctx['caller_service_id'],
                    membershipVersion=ctx['membership_version'], principalVersion=ctx['principal_version'], expiresAt=reference['expires_at'])
    captured['route']['version'] = int(route_row)
    original_actor = {**original_source['actor'], 'executionId': reference['execution_id']}
    expiry_source = {'kind': 'MANUAL', 'actor': original_actor, 'execution': captured, 'policy': None, 'commandKey': uid(), 'createdAt': now()}
    insert('employee_segment_execution', {'tenant_id': source, 'actor_id': original_actor['actorId'], 'execution_id': reference['execution_id'], 'source_json': json.dumps(captured)})
    expiry = {'runId': uid(), 'segmentId': 'ce05-segment-expiry', 'definitionVersion': 7}
    audience = query('SELECT audience_id FROM marketing_segment WHERE tenant_id=' + q(source) + " AND segment_id='ce05-segment-expiry'")
    sql('UPDATE marketing_segment SET snapshot_sequence=1 WHERE tenant_id=' + q(source) + " AND segment_id='ce05-segment-expiry'")
    insert('marketing_segment_run', {'tenant_id': source, 'run_id': expiry['runId'], 'segment_id': expiry['segmentId'], 'definition_version': 7, 'audience_id': audience,
                                    'snapshot_version': 1, 'started_at': now().replace('T', ' ').replace('Z', ''), 'valid_until': now(3600).replace('T', ' ').replace('Z', ''),
                                    'available_at': now().replace('T', ' ').replace('Z', ''), 'execution_source_json': json.dumps(expiry_source)})
    expiry_hash = source_hash(expiry)
    deadline = time.monotonic() + 8
    expires = datetime.fromisoformat(reference['expires_at'].replace('Z', '+00:00'))
    while datetime.now(timezone.utc) <= expires:
        if time.monotonic() >= deadline:
            raise RuntimeError('segment short-reference expiry wait exceeded')
        time.sleep(.2)
    restart('commerce-segment-expired-reference-background', workers=True)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if state(expiry)['attempts'] > 0:
            break
        time.sleep(.25)
    else:
        raise RuntimeError('segment expired original reference not exercised')
    checked(state(expiry)['processed'] == 0 and source_hash(expiry) == expiry_hash, 'expired Auth reference cannot start batch after process restart')
    restart('commerce-segment-expiry-worker-stopped')
    post('fresh grant cannot replace expired task', base + '/ce05-segment-expiry/refresh', status=403)
    post('control cannot replace expired task', '/v1/admin/segment-runs/' + expiry['runId'] + '/cancel', status=403)

    create('ce05-segment-unknown')
    unknown = refresh('ce05-segment-unknown')
    sql('UPDATE marketing_segment_run SET execution_source_json=NULL WHERE tenant_id=' + q(source) + ' AND run_id=' + q(unknown['runId']))
    make_ready(unknown)
    post('old unknown source fails closed', base + '/pump')
    checked(state(unknown)['processed'] == 0, 'NULL source never becomes approved system policy')
    post('unknown source cannot be cancelled as new creator', '/v1/admin/segment-runs/' + unknown['runId'] + '/cancel', status=403)

    create('ce05-segment-rollback')
    constraint = 'ck_segment_rehearsal_audit'
    sql("ALTER TABLE employee_command_identity ADD CONSTRAINT " + constraint + " CHECK(resource_type<>'segment' OR capability<>'commerce.segment.refresh' OR resource_id<>'ce05-segment-rollback')")
    headers = user + [('Idempotency-Key', uid())]
    try:
        post('actual SQL audit failure', base + '/ce05-segment-rollback/refresh', headers=headers, status=500)
        checked(query('SELECT count(*) FROM marketing_segment_run WHERE tenant_id=' + q(source) + " AND segment_id='ce05-segment-rollback'") == '0', 'actual SQL failure rolls back task')
        checked(query('SELECT count(*) FROM platform_command WHERE tenant_id=' + q(source) + ' AND command_key=' + q(dict(headers)['Idempotency-Key'])) == '0', 'actual SQL failure rolls back receipt')
        checked(query('SELECT snapshot_sequence FROM marketing_segment WHERE tenant_id=' + q(source) + " AND segment_id='ce05-segment-rollback'") == '0', 'actual SQL failure rolls back sequence')
    finally:
        sql('ALTER TABLE employee_command_identity DROP CHECK ' + constraint)
    rollback = refresh('ce05-segment-rollback', headers=headers)
    checked(rollback['snapshotVersion'] == 1, 'same key succeeds after SQL fault removal')
    control(rollback, 'cancel')
    actual_audits = query("SELECT capability,resource_id,resource_version,COUNT(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='segment' AND store_id IS NULL GROUP BY capability,resource_id,resource_version ORDER BY capability,resource_id,resource_version")
    expected = {}
    for entry in audits:
        expected[entry] = expected.get(entry, 0) + 1
    actual = {(row[0], row[1], int(row[2])): int(row[3]) for row in (line.split('\t') for line in actual_audits.splitlines())}
    checked(actual == expected, 'exact versioned audit effects and original receipts')
    checked(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='segment' AND (store_id IS NOT NULL OR resource_version IS NULL OR resource_version<=0)") == '0', 'no fake store or snapshot audit version')
    # Hold this already-started policy for the harness's real central outage, without changing its fixed provenance.
    c['segment_outage'] = outage
    c['segment_outage_processed'] = state(outage)['processed']
    h.private(run / 'segments-owner-pre-outage-result.json', json.dumps({'phase': 'PRE_OUTAGE_PASS', 'audit_count': len(audits), 'exact_audits': actual_audits,
               'manual_source_hash': original_source_hash, 'periodic_source_hash': policy_hash, 'expiry_fixture': 'AUTH_ISSUED_SHORT_REFERENCE_NEW_TASK',
               'expiry_source_hash': expiry_hash, 'outage_run': outage['runId'], 'expired_run': expiry['runId'], 'unknown_run': unknown['runId']}, ensure_ascii=False))
    return app


def outage(c):
    """实际Auth进程停止后，员工资格失败关闭，已批准系统政策继续独立履约。"""
    expect, sql, q, source, record = (c[k] for k in ('expect', 'sql', 'q', 'source', 'record'))
    expect('segment central outage read denied', 18661, '/v1/admin/segments', c['user'], status=503)
    expect('segment central outage pump denied', 18661, '/v1/admin/segments/pump', c['user'], {}, 503)
    target = c['segment_outage']
    sql('UPDATE marketing_segment_run SET available_at=UTC_TIMESTAMP(3) WHERE tenant_id=' + q(source) + ' AND run_id=' + q(target['runId']))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        values = sql('SELECT processed,status,entries_announced FROM marketing_segment_run WHERE tenant_id=' + q(source) + ' AND run_id=' + q(target['runId']))[1].split('\t')
        if len(values) == 3 and values[1] == 'COMPLETED' and values[2] == '1':
            if int(values[0]) <= c['segment_outage_processed']:
                raise RuntimeError('segment independent policy checkpoint did not advance')
            record('segment independent approved policy completes during actual central outage')
            break
        time.sleep(.25)
    else:
        raise RuntimeError('segment approved policy did not survive central outage')
    result = json.loads((c['run'] / 'segments-owner-pre-outage-result.json').read_text())
    result.update(phase='PASS', central_outage='REAL_AUTH_PROCESS_STOPPED', system_policy='COMPLETED_WITHOUT_EMPLOYEE_GRANT')
    c['h'].private(c['run'] / 'segments-owner-result.json', json.dumps(result, ensure_ascii=False))
