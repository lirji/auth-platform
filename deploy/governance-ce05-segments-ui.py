"""CE05-S2最终编译页面的真实六岗位验证；只使用P6创建的隔离身份与数据库。"""
import json
import os
import subprocess
from pathlib import Path

CAPS = ('create', 'schedule', 'refresh', 'pump', 'control', 'read')
HINTS = ('create', 'schedule', 'refresh', 'control', 'pump')
BROWSER_TIMEOUT_SECONDS = 180


def rehearse(c):
    """先撤销S1仍有效的个人Grant，再逐个创建单能力岗位，SQL逐项核对UI真实单次效果。"""
    expect, sql, q, uid, projection = (c[k] for k in ('expect', 'sql', 'q', 'uid', 'projection'))
    admin, prefix, partition, user = (c[k] for k in ('admin', 'prefix', 'partition', 'user'))
    source, run, h, record = (c[k] for k in ('source', 'run', 'h', 'record'))
    base = '/v1/admin/segments'
    root = Path(__file__).resolve().parent
    source_fixture = json.loads((run / 'campaigns-ui.json').read_text())
    fixture = {**source_fixture, 'segment': 'ce05-segment-ui-a', 'manual': 'ce05-segment-ui-b', 'known': 'ce05-segment-manual'}
    h.private(run / 'segments-ui.json', json.dumps(fixture, ensure_ascii=False))

    def query(statement):
        return sql(statement)[1]

    def browser(phase):
        subprocess.run(['node', str(root / 'governance-ce05-segments.mjs')], check=True, timeout=BROWSER_TIMEOUT_SECONDS,
                       env=dict(os.environ, P6_RUN=str(run), P6_PHASE=phase,
                                P6_PLAYWRIGHT_MODULE=str(c['commerce'] / 'frontend/node_modules/@playwright/test')))
        record('segment UI real compiled browser ' + phase)

    def revoke(gid, label):
        expect('segment UI revoke ' + label, 18162, prefix + '/revoke', admin,
               {**partition, 'command_id': uid(), 'grant_id': gid, 'expected_version': 1})
        projection()

    for code in ('segment.create', 'segment.refresh', 'segment.control', 'segment.read', 'segment.pump'):
        revoke(c['segment_grants'][code], 'S1 ' + code)
    initial = int(query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='segment'"))
    if initial != 21:
        raise RuntimeError('segment UI baseline audit count differs from verified S1')
    for action in CAPS:
        role = expect('segment UI independent role ' + action, 18162, prefix + '/roles', admin,
                      {**partition, 'command_id': uid(), 'role_code': 'ce05-ui-segment-' + action, 'role_version': 1,
                       'capabilities': ['commerce.segment.' + action]})['id']
        gid = expect('segment UI independent grant ' + action, 18162, prefix + '/scoped-grants', admin,
                     {**partition, 'command_id': uid(), 'member_id': c['member'], 'member_generation': 1, 'role_id': role,
                      'scope_rule': {'version': 1, 'resource_type': 'segment', 'clauses': [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]},
                      'source_id': 'ce05-ui-segment-' + action, 'valid_from': c['now'](-2), 'valid_to': c['now'](1200)}, 202)['id']
        projection()
        c['execution_ready']('segment.' + action, phase='ui-' + action, resource_type='segment')
        before = query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source))
        for hint in HINTS:
            path = '/v1/operations/segments/' + hint + '-access'
            expect('segment UI independent hint ' + action + '/' + hint, 18661, path, user, status=200 if hint == action else 403)
        expect('segment UI independent directory ' + action, 18661, base, user, status=200 if action == 'read' else 403)
        if before != query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source)):
            raise RuntimeError('segment UI hints or reads created identity audit')
        if action == 'create':
            for hint in HINTS:
                path = '/v1/operations/segments/' + hint + '-access'
                expect('segment UI anonymous hint ' + hint, 18661, path, [], status=401)
                expect('segment UI legacy ADMIN hint ' + hint, 18661, path, c['local_admin'], status=403)
        browser(action + '-only')
        if action == 'pump':
            system = query("SELECT run_id FROM marketing_segment_run WHERE tenant_id=" + q(source) + " AND segment_id='ce05-segment-ui-a'")
            manual = query("SELECT run_id FROM marketing_segment_run WHERE tenant_id=" + q(source) + " AND segment_id='ce05-segment-ui-b'")
            if not system or not manual:
                raise RuntimeError('segment UI actual system/manual tasks missing')
            for target, processed in ((system, 100), (manual, 0)):
                actual = query('SELECT processed,status FROM marketing_segment_run WHERE tenant_id=' + q(source) + ' AND run_id=' + q(target))
                if actual != str(processed) + '\tRUNNING':
                    raise RuntimeError('segment UI bounded checkpoint or original-source denial differs')
            if query("SELECT JSON_UNQUOTE(JSON_EXTRACT(execution_source_json,'$.kind')) FROM marketing_segment_run WHERE tenant_id=" + q(source) + ' AND run_id=' + q(system)) != 'SYSTEM':
                raise RuntimeError('segment UI control fixture is not an actual approved periodic source')
            h.private(run / 'segments-ui-targets.json', json.dumps({'system': system, 'manual': manual}))
        revoke(gid, action)
        for hint in HINTS:
            expect('segment UI revoked hint ' + action + '/' + hint, 18661, '/v1/operations/segments/' + hint + '-access', user, status=403)
    browser('revoked')
    actual = query("SELECT capability,resource_id,resource_version,count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='segment' AND resource_id IN ('ce05-segment-ui-a','ce05-segment-ui-b') GROUP BY capability,resource_id,resource_version ORDER BY capability,resource_id")
    expected = '\n'.join(('commerce.segment.control\tce05-segment-ui-a\t7\t1', 'commerce.segment.create\tce05-segment-ui-a\t7\t1',
                          'commerce.segment.create\tce05-segment-ui-b\t7\t1', 'commerce.segment.refresh\tce05-segment-ui-b\t7\t1',
                          'commerce.segment.schedule\tce05-segment-ui-a\t7\t1'))
    if actual != expected or query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='segment'") != str(initial + 5):
        raise RuntimeError('segment UI exact five effects or original S1 audit count differs')
    if query("SELECT count(*) FROM employee_command_identity WHERE tenant_id=" + q(source) + " AND resource_type='segment' AND (store_id IS NOT NULL OR resource_version<>7) AND resource_id IN ('ce05-segment-ui-a','ce05-segment-ui-b')") != '0':
        raise RuntimeError('segment UI audit uses a fake store or output version')
    if query("SELECT GROUP_CONCAT(CONCAT(segment_id,':',definition_version,':',snapshot_version,':',status,':',processed) ORDER BY segment_id) FROM marketing_segment_run WHERE tenant_id=" + q(source) + " AND segment_id IN ('ce05-segment-ui-a','ce05-segment-ui-b')") != 'ce05-segment-ui-a:7:1:CANCELLED:100,ce05-segment-ui-b:7:1:RUNNING:0':
        raise RuntimeError('segment UI actual task state differs from receipts')
    if query("SELECT count(*) FROM marketing_audience_snapshot WHERE tenant_id=" + q(source) + " AND audience_id IN (SELECT audience_id FROM marketing_segment WHERE tenant_id=" + q(source) + " AND segment_id IN ('ce05-segment-ui-a','ce05-segment-ui-b'))") != '0':
        raise RuntimeError('segment UI incomplete or cancelled task published a snapshot')
    if query("SELECT count(*) FROM platform_event WHERE tenant_id=" + q(source) + " AND event_type='segment.member.entered.v1' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.segmentId')) IN ('ce05-segment-ui-a','ce05-segment-ui-b')") != '0':
        raise RuntimeError('segment UI incomplete task announced members')
    # 只延后隔离夹具的下一触发时点，后续停服只检验S1已准备的原政策，不额外启动UI新任务。
    sql("UPDATE marketing_segment SET next_due=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR WHERE tenant_id=" + q(source) + " AND segment_id='ce05-segment-ui-a'")
    h.private(run / 'segments-ui-pre-outage-result.json', json.dumps({'phase': 'PRE_OUTAGE_PASS', 'baseline_audits': initial, 'ui_audits': 5, 'exact_ui_audits': actual, 'snapshot_publications': 0, 'ui_announcements': 0}, ensure_ascii=False))
    record('segment UI exact five original-version effects and no premature snapshots or Outbox')
    c['segment_ui_browser'] = browser


def outage(c):
    """实际Auth停服时五提示均503；浏览器不得开放写入或回退旧身份。"""
    for action in HINTS:
        c['expect']('segment UI actual central outage hint ' + action, 18661, '/v1/operations/segments/' + action + '-access', c['user'], status=503)
    c['segment_ui_browser']('outage')
    result = json.loads((c['run'] / 'segments-ui-pre-outage-result.json').read_text())
    result.update(phase='PASS', central_outage='REAL_AUTH_PROCESS_STOPPED')
    c['h'].private(c['run'] / 'segments-ui-result.json', json.dumps(result, ensure_ascii=False))
