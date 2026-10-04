"""校验WMS受控引导的冲突与范围边界；真实身份/库验证另有证据。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('wms_provision', Path(__file__).parents[1] / 'governance-wms-provision.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WmsProvisionTest(unittest.TestCase):
    def test_checkpoint_cannot_change_enterprise_tenant_or_copy_members(self):
        state = module.new_state()
        module.validate_state(state)
        for field, value in [('enterprise', 'OTHER'), ('organization', 'local-commerce'), ('tenant', module.uid('foreign'))]:
            changed = {**state, field: value}
            with self.assertRaises(ValueError):
                module.validate_state(changed)
        state['users']['commerce-member'] = state['users']['reader-a']
        with self.assertRaises(ValueError):
            module.validate_state(state)

    def test_duplicate_service_credentials_and_rebound_subjects_rejected(self):
        state = module.new_state()
        state['services']['inbound'] = state['services']['outbound']
        with self.assertRaises(ValueError):
            module.validate_state(state)
        state = module.new_state()
        state['users']['reader-a']['id'] = state['users']['reader-b']['id']
        with self.assertRaises(ValueError):
            module.validate_state(state)

    def test_existing_client_conflict_never_updates_or_resets_an_identity(self):
        operations = []
        def existing(endpoint, authorization, body=None):
            operations.append(endpoint)
            if endpoint.startswith('get-organization'):
                return {'passwordType': 'bcrypt', 'defaultApplication': 'wms-central'}
            return {'organization': 'local-commerce'}
        with patch.object(module, 'request', side_effect=existing), self.assertRaises(ValueError):
            module.ensure_identity(module.new_state(), {'client_id': 'test', 'client_secret': 'private'})
        self.assertTrue(all(endpoint.startswith('get-') for endpoint in operations))

    def test_private_inputs_duplicate_fields_and_symlinks_rejected(self):
        with self.assertRaises(ValueError):
            module.properties('tenant=first\ntenant=second\n')
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'config'
            target.write_text('safe=value\n')
            target.chmod(0o644)
            with self.assertRaises(ValueError):
                module.read_private(target)
            target.chmod(0o600)
            link = Path(directory) / 'link'
            link.symlink_to(target)
            with self.assertRaises(ValueError):
                module.read_private(link)
            with self.assertRaises(ValueError):
                module.private(link, 'unsafe')
            self.assertEqual('safe=value\n', target.read_text())

    def test_service_settings_are_fixed_unique_and_do_not_expose_plaintext_credential(self):
        state = module.new_state()
        config = module.prepare_configuration(state, {'jdbc.url': 'fixture'},
            {'graph.http': 'http://127.0.0.1:18544', 'graph.key': 'fixture'},
            {'client_id': 'fixture', 'client_secret': 'private'})
        for index in range(1, 6):
            prefix = 'service.' + str(index) + '.'
            self.assertEqual('wms', config[prefix + 'application-id'])
            self.assertEqual('local', config[prefix + 'environment'])
            self.assertEqual('wms-central', config[prefix + 'user.audience'])
        self.assertNotIn('scope.owner.commerce', config)
        self.assertFalse(any(secret in str(config) for secret in state['services'].values()))


if __name__ == '__main__':
    unittest.main()
