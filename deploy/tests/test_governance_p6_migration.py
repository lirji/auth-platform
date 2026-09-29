"""迁移前置行为测试：未知映射拒绝、来源约束保留、私有文件不可覆盖。"""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('p6_migration', Path(__file__).resolve().parents[1] / 'governance-p6-migration.py')
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = {
            'format': migration.FORMAT, 'source': {'snapshot_at': '2026-01-01T00:00:00Z'},
            'tables': {
                'store_operator_grant': [{'tenant_id': 'source', 'grant_id': 'grant', 'actor_id': 'actor',
                    'resource_type': 'STORE', 'resource_id': 'store', 'permission': 'CATALOG', 'active': 1,
                    'version': 7, 'updated_at': '2026-01-01 00:00:00'}],
                'platform_credential': [{'tenant_id': 'source', 'actor_id': 'actor', 'role': 'OPERATOR',
                    'active': 1, 'expires_at': '2026-01-03 00:00:00', 'created_at': '2026-01-01 00:00:00'}],
                'store_record': [{'tenant_id': 'source', 'store_id': 'store', 'merchant_id': 'merchant', 'status': 'ACTIVE', 'version': 1}],
                'merchant_record': [{'tenant_id': 'source', 'merchant_id': 'merchant', 'status': 'ACTIVE', 'version': 1}]}}
        self.mapping = {'format': 'p6-commerce-mapping/v1', 'source_sha256': 'a' * 64,
            'source_tenant': 'source', 'target': {'tenant_id': '00000000-0000-4000-8000-000000000001', 'application_id': 'commerce', 'environment': 'local-p6'},
            'evaluation_at': '2026-01-02T00:00:00Z',
            'actors': {'actor': {'principal_id': '00000000-0000-4000-8000-000000000002',
                'membership_id': '00000000-0000-4000-8000-000000000003', 'generation': 1, 'identity_evidence': 'explicit-fixture-binding'}},
            'permissions': {'CATALOG': {'capabilities': ['commerce.catalog.operate'], 'approved_by': 'fixture-owner', 'decision_ref': 'isolated-test', 'valid_from': '2026-01-01T00:00:00Z', 'valid_to': '2026-01-03T00:00:00Z', 'validity_decision': 'explicit finite synthetic test'}}}

    def report(self):
        return migration.dry_run(self.snapshot, 'a' * 64, self.mapping)

    def test_explicit_store_mapping_is_bounded_and_never_authorizes_cutover(self):
        r = self.report()
        self.assertTrue(r['ready_for_import'])
        self.assertFalse(r['cutover_authorized'])
        self.assertEqual(['store'], r['records'][0]['scope_rule']['clauses'][0]['values'])
        self.assertEqual(7, r['records'][0]['source_version'])
        self.assertIsNone(r['records'][0]['source_grant_valid_to'])
        self.assertEqual(self.report(), r)

    def test_shadow_discrepancy_and_double_error_never_allow_cutover(self):
        self.assertTrue(migration.compare_shadow([{'legacy_decision': 'ALLOW', 'central_decision': 'ALLOW'}, {'legacy_decision': 'DENY', 'central_decision': 'DENY'}])['ready'])
        for old, new in [('ALLOW', 'DENY'), ('DENY', 'ALLOW'), ('ERROR', 'ERROR'), ('UNKNOWN', 'DENY')]:
            self.assertFalse(migration.compare_shadow([{'legacy_decision': old, 'central_decision': new}])['ready'])

    def test_active_permanent_grant_requires_explicit_validity_decision(self):
        del self.mapping['permissions']['CATALOG']['validity_decision']
        r = self.report()
        self.assertFalse(r['ready_for_import'])
        self.assertIn('ACTIVE_PERMANENT_SOURCE_NEEDS_VALIDITY_DECISION', r['records'][0]['errors'])

    def test_unknown_identity_is_quarantined_without_guessing(self):
        self.mapping['actors'] = {}
        r = self.report()
        self.assertFalse(r['ready_for_import'])
        self.assertIn('EXACT_IDENTITY_MAPPING_REQUIRED', r['records'][0]['errors'])
        self.assertNotIn('target_binding', r['records'][0])

    def test_missing_decision_or_wildcard_is_not_a_tenant_admin(self):
        self.mapping['permissions']['CATALOG']['approved_by'] = ''
        self.mapping['permissions']['CATALOG']['capabilities'] = ['*']
        r = self.report()['records'][0]
        self.assertIn('SCOPE_CHANGE_DECISION_REQUIRED', r['errors'])
        self.assertIn('CAPABILITY_MAPPING_REQUIRED', r['errors'])

    def test_export_never_follows_catalog_implicitly(self):
        self.mapping['permissions']['CATALOG']['capabilities'].append('commerce.product.export')
        self.assertEqual('QUARANTINED_MAPPING', self.report()['records'][0]['disposition'])

    def test_merchant_dynamic_scope_cannot_be_flattened_to_static_stores(self):
        self.snapshot['tables']['store_operator_grant'][0].update(resource_type='MERCHANT', resource_id='merchant')
        r = self.report()['records'][0]
        self.assertIn('RESOURCE_SEMANTICS_UNSUPPORTED', r['errors'])
        self.assertNotIn('scope_rule', r)

    def test_revocation_and_expired_credential_are_preserved_independently(self):
        self.snapshot['tables']['store_operator_grant'][0]['active'] = 0
        self.snapshot['tables']['platform_credential'][0]['expires_at'] = '2026-01-02 00:00:00'
        r = self.report()
        self.assertFalse(r['ready_for_import'])
        self.assertEqual('PRESERVE_DENIAL', r['records'][0]['disposition'])
        self.assertEqual(['SOURCE_GRANT_REVOKED', 'LOCAL_OPERATOR_CREDENTIAL_UNAVAILABLE'], r['records'][0]['deny_constraints'])
        self.assertIsNone(r['records'][0]['source_grant_valid_to'])

    def test_foreign_tenant_resource_and_credential_cannot_fill_references(self):
        self.snapshot['tables']['store_record'][0]['tenant_id'] = 'foreign'
        self.snapshot['tables']['platform_credential'][0]['tenant_id'] = 'foreign'
        r = self.report()['records'][0]
        self.assertIn('RESOURCE_REFERENCE_MISSING', r['errors'])
        self.assertIn('LOCAL_OPERATOR_CREDENTIAL_UNAVAILABLE', r['deny_constraints'])

    def test_inactive_resource_remains_denied(self):
        self.snapshot['tables']['merchant_record'][0]['status'] = 'FROZEN'
        self.assertEqual('PRESERVE_DENIAL', self.report()['records'][0]['disposition'])

    def test_snapshot_tamper_and_mapping_to_another_snapshot_are_rejected(self):
        self.mapping['source_sha256'] = 'b' * 64
        with self.assertRaises(ValueError):
            self.report()
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'snapshot.json'
            migration.write_private(p, self.snapshot)
            with self.assertRaises(ValueError):
                migration.read_json(p, 'a' * 64)

    def test_duplicate_or_malformed_source_records_fail_the_batch(self):
        original = copy.deepcopy(self.snapshot)
        self.snapshot['tables']['store_operator_grant'] *= 2
        with self.assertRaises(ValueError):
            self.report()
        for value in [-1, True, 1.2, '7']:
            self.snapshot = copy.deepcopy(original)
            self.snapshot['tables']['store_operator_grant'][0]['version'] = value
            with self.assertRaises(ValueError):
                self.report()

    def test_credentials_secret_field_and_row_overflow_rejected(self):
        self.snapshot['tables']['platform_credential'][0]['token_hash'] = 'never-export'
        with self.assertRaises(ValueError):
            self.report()
        del self.snapshot['tables']['platform_credential'][0]['token_hash']
        self.snapshot['tables']['platform_credential'] *= 1001
        with self.assertRaises(ValueError):
            self.report()

    def test_timezone_and_evaluation_before_snapshot_rejected(self):
        for date in ['2026-01-02T00:00:00', '2025-12-31T23:00:00Z']:
            self.mapping['evaluation_at'] = date
            with self.assertRaises(ValueError):
                self.report()

    def test_selected_tenant_must_have_legacy_records(self):
        self.mapping['source_tenant'] = 'unknown'
        with self.assertRaises(ValueError):
            self.report()

    def test_private_files_reject_overwrite_symlink_duplicate_keys_and_nan(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'private.json'
            migration.write_private(p, self.snapshot)
            self.assertEqual(0o600, p.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                migration.write_private(p, {})
            link = Path(directory) / 'link.json'
            link.symlink_to(p)
            with self.assertRaises(OSError):
                migration.read_json(link)
            for text in ['{"a":1,"a":2}', '{"a":NaN}']:
                p.write_text(text)
                with self.assertRaises(ValueError):
                    migration.read_json(p)
            p.chmod(0o644)
            with self.assertRaises(ValueError):
                migration.read_json(p)

    def test_cli_quarantine_exits_two_and_writes_no_grant(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            p = root / 'source.json'
            migration.write_private(p, self.snapshot)
            sha = hashlib.sha256(p.read_bytes()).hexdigest()
            self.mapping.update(source_sha256=sha, actors={})
            migration.write_private(root / 'mapping.json', self.mapping)
            status = migration.main(['dry-run', '--snapshot', str(p), '--sha256', sha, '--mapping', str(root / 'mapping.json'), '--output', str(root / 'report.json')])
            self.assertEqual(2, status)
            r = json.loads((root / 'report.json').read_text())
            self.assertFalse(r['ready_for_import'])
            self.assertFalse(r['cutover_authorized'])


if __name__ == '__main__':
    unittest.main()
