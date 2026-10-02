"""实际停服阶段不能覆盖预停服证据，推进过或更换原来源的任务不能写成PASS。"""
import hashlib
import importlib.util
import json
import sqlite3
import threading
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
from copy import deepcopy


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


deliveries = module('coupon_delivery_artifacts', 'governance-ce05-coupon-deliveries.py')
runtime = module('coupon_delivery_private_runtime', 'governance-context-smoke.py')
ui = module('coupon_delivery_ui_artifacts', 'governance-ce05-coupon-deliveries-ui.py')


class CouponDeliveryUiFixtureBoundaryTest(unittest.TestCase):
    def fixture(self):
        return {'organization': 'gov-p1-owned', 'clients': {'management': {'name': 'owned-manager'}}}

    def test_independent_identity_uses_owned_non_admin_readback(self):
        tool = Mock()
        tool.request.side_effect = [
            {'data': None}, {}, {'data': {'id': 'owned-third-person', 'owner': 'gov-p1-owned', 'isAdmin': False}}]
        user = ui.independent_identity(self.fixture(), 'http://localhost:18094',
                                       {'client_id': 'fixture-client', 'client_secret': 'fixture-secret'},
                                       'abc123', tool, lambda: 'owned-third-person')
        self.assertEqual('owned-third-person', user['id'])
        call = tool.request.call_args_list[1]
        self.assertEqual('add-user', call.args[0])
        self.assertEqual('gov-p1-owned', call.args[1]['owner'])
        self.assertFalse(call.args[1]['isAdmin'])
        self.assertEqual('normal-user', call.args[1]['type'])

    def test_foreign_existing_or_mismatched_identity_never_enters_role_rehearsal(self):
        for label, organization, responses in (
                ('foreign', 'business-owner', []),
                ('existing', 'gov-p1-owned', [{'data': {'id': 'unrelated-person'}}]),
                ('wrong-readback', 'gov-p1-owned', [{'data': None}, {},
                    {'data': {'id': 'wrong-person', 'owner': 'gov-p1-owned', 'isAdmin': False}}])):
            with self.subTest(label=label):
                fixture = self.fixture(); fixture['organization'] = organization
                tool = Mock(); tool.request.side_effect = responses
                with self.assertRaisesRegex(RuntimeError, 'ownership mismatch|already exists|readback mismatch'):
                    ui.independent_identity(fixture, 'http://localhost:18094',
                                            {'client_id': 'fixture-client', 'client_secret': 'fixture-secret'},
                                            'abc123', tool, lambda: 'owned-third-person')
                self.assertEqual(len(responses), tool.request.call_count)

    def test_original_failure_counters_and_error_survive_ui_state_projection(self):
        # 仅验证SQL投影契约；CTE不建表、不修改MySQL，不冒充真实事务或授权证据。
        columns = ['tenant_id', 'batch_id', 'status', 'mode', 'processed', 'issued', 'skipped', 'revoked',
                   'kept', 'attempts', 'error_code', 'version', 'content_version', 'issue_source_json', 'revoke_source_json']
        values = ['owned-tenant', 'owned-batch', 'ISOLATED', 'REVOKE', 21, 21, 0, 7, 0, 5,
                  'AUTHORIZATION_DENIED', 22, 1, None, None]
        original = {'status': 'ISOLATED', 'mode': 'REVOKE', 'processed': 21, 'issued': 21, 'revoked': 7,
                    'kept': 0, 'attempts': 5, 'error': 'AUTHORIZATION_DENIED', 'version': 22,
                    'contentVersion': 1, 'issue': None, 'revoke': None}
        with sqlite3.connect(':memory:') as connection:
            def query(statement):
                cte = 'WITH automation_coupon_batch(' + ','.join(columns) + ') AS (VALUES (' + ','.join('?' for _ in columns) + ')) '
                return connection.execute(cte + statement, values).fetchone()[0]
            current = ui.batch_state(query, lambda value: "'" + value + "'", 'owned-tenant', 'owned-batch')
        self.assertEqual(original, {key: current[key] for key in original})
        self.assertEqual(0, current['skipped'])


class CouponDeliveryArtifactTest(unittest.TestCase):
    def context(self, run, processed=0, changed=False):
        runtime.private(run / 'coupon-deliveries-owner-pre-outage-result.json', json.dumps({'phase': 'PRE_OUTAGE_PASS'}))
        original = {'kind': 'CENTRAL', 'actor': {'executionId': 'owned-original'}}
        row = {'processed': processed, 'status': 'RUNNING', 'attempts': 0, 'error': 'DEPENDENCY_UNAVAILABLE',
               'source': {'kind': 'LEGACY'} if changed else original}
        return {'expect': Mock(), 'sql': Mock(side_effect=[(0, ''), (0, json.dumps(row))]),
                'q': lambda value: "'" + value + "'", 'source': 'owned-fixture', 'store': 'owned-store', 'record': Mock(),
                'user': [], 'delivery_outage': 'owned-batch',
                'delivery_outage_source_hash': hashlib.sha256(json.dumps(original, sort_keys=True).encode()).hexdigest(),
                'run': run, 'h': runtime}

    def test_actual_stop_preserves_pre_outage_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run)
            before = (run / 'coupon-deliveries-owner-pre-outage-result.json').read_bytes()
            deliveries.outage(context)
            self.assertEqual(before, (run / 'coupon-deliveries-owner-pre-outage-result.json').read_bytes())
            result = json.loads((run / 'coupon-deliveries-owner-result.json').read_text())
            self.assertEqual('PASS', result['phase'])
            self.assertEqual('REAL_AUTH_PROCESS_STOPPED', result['central_outage'])
            self.assertEqual(0, result['outage_row']['processed'])
            context['expect'].assert_any_call('coupon delivery central outage pump denied', 18661,
                                              '/v1/admin/coupon-deliveries/pump', [], {}, 503)

    def test_outage_checkpoint_advance_cannot_emit_success(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run, processed=1)
            with self.assertRaisesRegex(RuntimeError, 'altered original source or committed effects'):
                deliveries.outage(context)
            self.assertFalse((run / 'coupon-deliveries-owner-result.json').exists())
            context['record'].assert_not_called()

    def test_replacement_source_cannot_emit_success(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run, changed=True)
            with self.assertRaisesRegex(RuntimeError, 'altered original source or committed effects'):
                deliveries.outage(context)
            self.assertFalse((run / 'coupon-deliveries-owner-result.json').exists())


class CouponDeliveryBrowserEvidenceTest(unittest.TestCase):
    def evidence(self, run):
        screenshot = run / 'coupon-deliveries-read-only-detail.png'
        screenshot.write_bytes(b'owned-test-image-bytes')
        script = Path(ui.__file__).with_suffix('.mjs')
        jar = run / 'commerce-browser.jar'
        jar.write_bytes(b'fixture archive integrity only; not actual runtime evidence')
        archive_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
        (run / 'browser-artifact-fence.json').write_text(json.dumps({'mode': 'PACKAGED_JAR',
            'browser_origin': 'http://127.0.0.1:18665', 'jar_sha256': archive_sha, 'frontend_files': 1}))
        data = {'phase': 'read-only', 'result': 'PASS', 'backend': 'REAL', 'runtime': 'PACKAGED_JAR',
                'browserOrigin': 'http://127.0.0.1:18665', 'artifactSha256': archive_sha, 'assetCount': 1,
                'harnessSha256': hashlib.sha256(script.read_bytes()).hexdigest(), 'checks': ['fixture integrity only'],
                'screenshots': [{'name': screenshot.name, 'sha256': hashlib.sha256(screenshot.read_bytes()).hexdigest()}]}
        path = run / 'coupon-deliveries-ui-browser-read-only.json'
        path.write_text(json.dumps(data))
        return data, path, screenshot

    def test_current_bound_artifact_is_read_only(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            data, path, screenshot = self.evidence(run)
            before = path.read_bytes()
            self.assertEqual(data, ui.checked_browser(run, 'read-only'))
            self.assertEqual(before, path.read_bytes())
            self.assertEqual(b'owned-test-image-bytes', screenshot.read_bytes())

    def test_stale_harness_or_synthetic_backend_cannot_enter_sql_validation(self):
        for field, value in (('harnessSha256', 'old-script'), ('backend', 'FIXTURE'), ('runtime', 'VITE'), ('phase', 'create-only'),
                             ('browserOrigin', 'http://127.0.0.1:18661'), ('artifactSha256', 'old-jar'), ('assetCount', 0)):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as folder:
                run = Path(folder)
                data, path, _ = self.evidence(run)
                data[field] = value
                path.write_text(json.dumps(data))
                with self.assertRaisesRegex(RuntimeError, 'incomplete or mismatched'):
                    ui.checked_browser(run, 'read-only')

    def test_missing_or_changed_screenshot_never_enters_next_phase(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            _, _, screenshot = self.evidence(run)
            screenshot.write_bytes(b'changed-image')
            with self.assertRaisesRegex(RuntimeError, 'bytes changed'):
                ui.checked_browser(run, 'read-only')
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            data, path, _ = self.evidence(run)
            data['screenshots'] = []
            path.write_text(json.dumps(data))
            with self.assertRaisesRegex(RuntimeError, 'incomplete or mismatched'):
                ui.checked_browser(run, 'read-only')


class CouponDeliveryExactEffectEvidenceTest(unittest.TestCase):
    """纯证据完整性回归：拒绝错主体/换源/虚假券状态，不证明MySQL事务或真实Auth语义。"""

    def fixture(self):
        identity = {'tenant_id': 'commerce-tenant', 'actor_id': 'original-actor', 'principal_id': 'external-human',
                    'membership_id': 'original-member', 'generation': 1}
        partition = {'tenant_id': 'auth-tenant', 'application_id': 'commerce', 'environment': 'local-p6'}
        audit = {**identity, 'operation': 'coupon.delivery.create', 'command_key': 'original-command',
                 'execution_id': 'original-reference', 'capability': 'commerce.coupon_delivery.create',
                 'resource_type': 'coupon_delivery', 'resource_id': ui.TARGET, 'resource_version': 1,
                 'store_id': None, 'route_version': 4}
        execution = {'identity': {'principalId': 'external-human', 'membershipId': 'original-member', 'generation': 1},
                     'route': {'tenantId': 'commerce-tenant', 'authTenantId': 'auth-tenant', 'family': 'COUPON_DELIVERY',
                               'state': 'CENTRAL', 'everCentral': True, 'version': 4},
                     'applicationId': 'commerce', 'environment': 'local-p6', 'callerServiceId': 'commerce-p6',
                     'membershipVersion': 1, 'principalVersion': 1, 'expiresAt': '2026-10-09T00:00:00Z'}
        source = {'kind': 'CENTRAL', 'actor': {'tenantId': 'commerce-tenant', 'actorId': 'original-actor',
                  'role': 'OPERATOR', 'executionId': 'original-reference'}, 'execution': execution,
                  'commandKey': 'original-command', 'createdAt': '2026-10-02T00:00:00Z'}
        return identity, partition, audit, source

    def test_five_counts_cannot_hide_duplicate_target_or_wrong_identity_tuple(self):
        _, _, audit, _ = self.fixture()
        expected = [{**audit, 'resource_id': str(index), 'command_key': str(index)} for index in range(5)]
        self.assertEqual(expected, ui.verify_identity_rows(expected, expected))
        for field, value in [('tenant_id', 'foreign'), ('actor_id', 'foreign'), ('principal_id', 'foreign'),
                             ('membership_id', 'foreign'), ('generation', 2), ('resource_version', 2),
                             ('store_id', 'fake-store'), ('capability', 'commerce.coupon_delivery.pump'),
                             ('operation', 'coupon.delivery.control'), ('resource_type', 'campaign')]:
            with self.subTest(field=field):
                rows = deepcopy(expected); rows[0][field] = value
                with self.assertRaisesRegex(RuntimeError, 'exact identity tuples mismatch'):
                    ui.verify_identity_rows(rows, expected)
        rows = deepcopy(expected); rows[0] = rows[1]
        with self.assertRaisesRegex(RuntimeError, 'exact identity tuples mismatch'):
            ui.verify_identity_rows(rows, expected)

    def test_central_source_requires_exact_audit_reference_and_persisted_metadata(self):
        identity, partition, audit, source = self.fixture()
        original = ui.verify_direction_source(source, identity, partition, audit, source['execution'])
        for field, value in [('executionId', 'replacement-reference'), ('tenantId', 'foreign'), ('actorId', 'new-actor')]:
            with self.subTest(field=field):
                changed = deepcopy(source); changed['actor'][field] = value
                with self.assertRaisesRegex(RuntimeError, 'original direction source mismatch'):
                    ui.verify_direction_source(changed, identity, partition, audit, changed['execution'])
        changed = deepcopy(source); changed['execution']['expiresAt'] = '2026-10-10T00:00:00Z'
        with self.assertRaisesRegex(RuntimeError, 'original direction source mismatch'):
            ui.verify_direction_source(changed, identity, partition, audit, source['execution'])
        source['execution']['expiresAt'] = 'mutated-after-capture'
        self.assertEqual('2026-10-09T00:00:00Z', original['execution']['expiresAt'])

    def test_first_source_grant_must_match_subject_partition_and_original_path(self):
        identity, partition, audit, source = self.fixture()
        execution = source['execution']
        plan = {'decision': 'ALLOW', 'capability': audit['capability'], 'resource_type': 'coupon_delivery',
                'context': {'principal_id': identity['principal_id'], 'membership_id': identity['membership_id'],
                            'membership_generation': 1, 'membership_version': 1, 'principal_version': 1,
                            **partition, 'caller_service_id': 'commerce-p6', 'actor_type': 'HUMAN'},
                'alternatives': [{'grant_id': 'original-grant', 'scope_version': 1,
                                  'clauses': [{'kind': 'TENANT_ALL', 'values': [], 'include_root': False}]}]}
        ui.verify_original_grant(plan, identity, partition, 'original-grant', audit['capability'], execution)
        for field, value in [('principal_id', 'other-human'), ('membership_id', 'other-member'),
                             ('membership_generation', 2), ('tenant_id', 'foreign'), ('actor_type', 'SERVICE')]:
            with self.subTest(field=field):
                changed = deepcopy(plan); changed['context'][field] = value
                with self.assertRaisesRegex(RuntimeError, 'original execution Grant mismatch'):
                    ui.verify_original_grant(changed, identity, partition, 'original-grant', audit['capability'], execution)
        changed = deepcopy(plan); changed['alternatives'][0]['grant_id'] = 'new-grant'
        with self.assertRaisesRegex(RuntimeError, 'original execution Grant mismatch'):
            ui.verify_original_grant(changed, identity, partition, 'original-grant', audit['capability'], execution)

    def test_observer_freezes_before_operation_replay_and_joins(self):
        committed, captured = threading.Event(), threading.Event()
        source = {'execution': 'first-reference'}
        def read():
            return source if committed.is_set() else None
        def capture(value):
            frozen = deepcopy(value); captured.set(); return frozen
        def operation():
            committed.set()
            self.assertTrue(captured.wait(2))
            source['execution'] = 'replacement-reference'
            return 'operation-completed'
        result, frozen = ui.observe_first_source(read, capture, operation, timeout=3)
        self.assertEqual('operation-completed', result)
        self.assertEqual({'execution': 'first-reference'}, frozen)
        self.assertFalse(any(thread.name == 'coupon-delivery-first-source' for thread in threading.enumerate()))

    def test_observer_sql_error_or_missing_commit_never_emits_success(self):
        observed = threading.Event()
        def failing_read():
            observed.set(); raise RuntimeError('actual SQL failure')
        with self.assertRaisesRegex(RuntimeError, 'actual SQL failure'):
            ui.observe_first_source(failing_read, deepcopy, lambda: observed.wait(2), timeout=3)
        with self.assertRaisesRegex(RuntimeError, 'first source was not observed'):
            ui.observe_first_source(lambda: None, deepcopy, lambda: None, timeout=.1)
        self.assertFalse(any(thread.name == 'coupon-delivery-first-source' for thread in threading.enumerate()))

    def test_recipient_count_and_status_do_not_substitute_actual_coupon_join(self):
        row = {'member': 'member-1', 'recipient_status': 'REVOKED', 'recipient_error': None, 'coupon': 'real-coupon',
               'coupon_tenant': 'tenant', 'coupon_member': 'member-1', 'coupon_status': 'REVOKED',
               'definition': 'fixed-definition', 'definition_version': 1, 'source_type': 'TARGETED',
               'source_id': hashlib.sha256((ui.TARGET + '/member-1').encode()).hexdigest()}
        self.assertEqual([row], ui.verify_recipient_coupons([row], 'tenant', ui.TARGET, ['member-1'], 'fixed-definition', 1))
        for field, value in [('coupon', None), ('coupon_status', 'AVAILABLE'), ('coupon_member', 'other-member'),
                             ('coupon_tenant', 'foreign'), ('definition_version', 2), ('definition', 'other-definition'),
                             ('source_type', 'JOURNEY'), ('source_id', 'other-source'), ('recipient_error', 'kept')]:
            with self.subTest(field=field):
                changed = {**row, field: value}
                with self.assertRaisesRegex(RuntimeError, 'recipient coupon join mismatch'):
                    ui.verify_recipient_coupons([changed], 'tenant', ui.TARGET, ['member-1'], 'fixed-definition', 1)


if __name__ == '__main__':
    unittest.main()
