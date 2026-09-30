"""离线权限契约回归，覆盖漏接口、误授角色和动态动作扩权。"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('contract_check', ROOT / 'deploy/verify-commerce-contract.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class CommerceContractTest(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads((ROOT / 'docs/design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json').read_text())
        lines = (ROOT / 'docs/implementation/oa-auth/commerce-readiness/HTTP_INVENTORY.md').read_text().splitlines()[6:]
        self.actual = set()
        for line in lines:
            if line.startswith('| '):
                fields = [x.strip().strip('`') for x in line.strip('|').split('|')]
                self.actual.add((fields[1], fields[2].replace('&#124;', '|')))

    def reject(self):
        with self.assertRaises(ValueError):
            checker.validate(self.contract, self.actual)

    def test_baseline_matches_reviewed_inventory_without_publishing(self):
        self.assertEqual(checker.validate(self.contract, self.actual),
                         dict(status='PASS', capabilities=122, routes=234, role_snapshots=34, published=False))

    def test_missing_route_or_new_source_route_is_not_silently_skipped(self):
        self.contract['routes'].pop()
        self.reject()
        self.setUp()
        self.actual.add(('DELETE', '/v1/admin/members/{id}'))
        self.reject()

    def test_unknown_and_duplicate_capability_fail(self):
        self.contract['routes'][1]['capabilities'] = ['commerce.*']
        self.reject()
        self.setUp()
        self.contract['capabilities'].append(copy.deepcopy(self.contract['capabilities'][0]))
        self.reject()

    def test_customer_endpoint_cannot_receive_employee_capability(self):
        self.contract['routes'][0]['capabilities'] = ['commerce.fulfillment.read']
        self.reject()

    def test_role_cannot_mix_tenant_member_scope_with_store_scope(self):
        self.contract['roles'][0]['capabilities'].append('commerce.member.read')
        self.reject()

    def test_tenant_only_member_cannot_be_assigned_fake_store_scope(self):
        next(c for c in self.contract['capabilities'] if c['code'] == 'commerce.member.read')['allowed_scope_kinds'].append('SPECIFIED_STORES')
        self.reject()

    def test_unknown_action_cannot_be_interpreted_as_permission(self):
        next(r for r in self.contract['routes'] if 'actions' in r)['unknown_action'] = 'ALLOW'
        self.reject()

    def test_limit_does_not_truncate(self):
        self.contract['capabilities'] *= 2
        self.reject()

    def test_annotation_parser_handles_empty_mapping_and_path_arrays(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'commerce-app/src/main/java/com/lrj/commerce/app/http/TestController.java'
            source.parent.mkdir(parents=True)
            source.write_text('@RestController\n@RequestMapping("/v1/test")\npublic class TestController {\n@PostMapping\npublic void create(){}\n@GetMapping({"/one", "/two"})\npublic void read(){}\n}')
            self.assertEqual(checker.endpoints(root), {('POST', '/v1/test'), ('GET', '/v1/test/one'), ('GET', '/v1/test/two')})


if __name__ == '__main__':
    unittest.main()
