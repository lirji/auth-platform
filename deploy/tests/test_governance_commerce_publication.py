"""出版工具的有界失败场景；纯本地fixture不冒充Owner或真实数据库验收。"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("commerce_publication", ROOT / "deploy/governance-commerce-publication.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class CommercePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.roots = {"commerce": self.root}
        nav = self.root / "frontend/src/iam/navigation.ts"
        nav.parent.mkdir(parents=True)
        nav.write_text('export const centralGroups = [{key:"catalog",label:"商品",pages:[["/operations/products","商品"]]}]; export const centralRoutes=["/collaboration/products"];')
        source = self.root / "frontend/src/iam/Products.tsx"
        source.write_text("actual fixture source")
        receipt = self.root / "receipt.json"
        receipt.write_text(json.dumps({"status": "PASS"}))
        self.reference = {"repo": "commerce", "path": str(source.relative_to(self.root)), "sha256": publisher.sha(source)}
        self.receipt = {"repo": "commerce", "path": "receipt.json", "sha256": publisher.sha(receipt)}
        self.contract = {"schema_version": 1, "status": "DESIGN_ONLY_NOT_PUBLISHED", "capabilities": [
            {"code": "commerce.product.read", "resource_type": "product", "risk_level": "NORMAL", "allowed_scope_kinds": ["TENANT_ALL", "SPECIFIED_STORES"]},
            {"code": "commerce.catalog.operate", "resource_type": "store", "risk_level": "HIGH", "allowed_scope_kinds": ["TENANT_ALL"]}],
            "roles": [{"code": "reader.product", "job": "reader", "version": 1, "resource_type": "product", "capabilities": ["commerce.product.read"], "grant_policy": "OWNER_REVIEW_REQUIRED"}]}
        self.mapping = {"menus": [{"route": route, "capabilities": ["commerce.product.read"], "sources": [self.reference]} for route in ["/operations/products", "/collaboration/products"]]}
        self.readiness = {"proofs": {"owner": {"status": "OWNER_TERMINAL_VALIDATION_PASS", "capabilities": [cap["code"] for cap in self.contract["capabilities"]], "sources": [self.reference], "receipts": [self.receipt], "commits": [{"repo": "commerce", "head": "a" * 40}]}},
                          "capability_proofs": {cap["code"]: "owner" for cap in self.contract["capabilities"]}}
        self.git = patch.object(publisher.subprocess, "check_output", side_effect=lambda args, **kwargs: "a" * 40 if "rev-parse" in args else "")
        self.git.start()

    def tearDown(self):
        self.git.stop()
        self.temporary.cleanup()

    def build(self):
        return publisher.plan(self.contract, self.mapping, self.readiness, self.roots, 2)

    def reject(self):
        with self.assertRaises(ValueError):
            self.build()

    def test_complete_plan_has_fixed_same_resource_roles_and_no_grants(self):
        result = self.build()
        self.assertEqual(result["status"], "READY_FOR_ISOLATED_OWNER_PUBLICATION")
        self.assertEqual(result["navigation_routes"], 1)
        self.assertEqual(result["menu_routes"], 2)
        self.assertEqual(result["distinct_jobs"], 1)
        self.assertEqual(result["capabilities_outside_templates"], ["commerce.catalog.operate"])
        self.assertFalse(result["auto_grants"])

    def test_missing_owner_proof_produces_gap_without_executable_manifest(self):
        self.readiness["capability_proofs"].pop("commerce.product.read")
        result = self.build()
        self.assertEqual(result["status"], "AWAIT_OWNER_TERMINAL_PROOF")
        self.assertIsNone(result["manifest"])
        with patch.object(publisher, "request") as request:
            with self.assertRaises(ValueError):
                publisher.apply(result, {}, self.root)
            request.assert_not_called()

    def test_working_source_or_changed_commit_cannot_be_published(self):
        with patch.object(publisher.subprocess, "check_output", return_value="b" * 40):
            self.reject()
        with patch.object(publisher.subprocess, "check_output", side_effect=lambda args, **kwargs: "a" * 40 if "rev-parse" in args else " M Products.tsx"):
            self.reject()

    def test_changed_source_digest_or_fail_receipt_is_rejected(self):
        (self.root / self.reference["path"]).write_text("changed")
        self.reject()
        (self.root / self.reference["path"]).write_text("actual fixture source")
        (self.root / "receipt.json").write_text(json.dumps({"status": "FAIL"}))
        self.receipt["sha256"] = publisher.sha(self.root / "receipt.json")
        self.reject()

    def test_enum_migration_claim_is_not_terminal_owner_proof(self):
        self.readiness["proofs"]["owner"]["status"] = "PROVIDER_REGISTERED"
        self.reject()

    def test_receipt_cannot_be_reused_for_uncovered_capability(self):
        self.readiness["proofs"]["owner"]["capabilities"].remove("commerce.product.read")
        self.reject()

    def test_every_source_repository_must_bind_exact_commit(self):
        self.readiness["proofs"]["owner"]["commits"] = [{"repo": "unbound", "head": "a" * 40}]
        self.reject()

    def test_navigation_growth_cannot_be_silently_omitted(self):
        path = self.root / "frontend/src/iam/navigation.ts"
        path.write_text(path.read_text().replace(']]}', '],["/operations/new","新页"]]}'))
        self.reject()

    def test_unsupported_navigation_shape_is_not_guessed(self):
        path = self.root / "frontend/src/iam/navigation.ts"
        path.write_text('export const routes=["/operations/products"];')
        self.reject()

    def test_product_read_is_not_catalog_alias(self):
        self.mapping["menus"][0]["capabilities"] = ["commerce.catalog.operate"]
        self.reject()

    def test_unknown_or_duplicate_capability_and_role_are_rejected(self):
        self.contract["capabilities"].append(copy.deepcopy(self.contract["capabilities"][0]))
        self.reject()
        self.contract["capabilities"].pop()
        self.readiness["capability_proofs"]["commerce.unknown"] = "owner"
        self.reject()

    def test_role_does_not_mix_resource_types_or_auto_grant(self):
        self.contract["roles"][0]["capabilities"].append("commerce.catalog.operate")
        self.reject()
        self.contract["roles"][0]["capabilities"].pop()
        self.contract["roles"][0]["grant_policy"] = "AUTO_GRANT"
        self.reject()

    def test_namespace_and_scope_are_bounded(self):
        self.contract["capabilities"][0]["allowed_scope_kinds"] = ["ANY"]
        self.reject()

    def test_source_reference_cannot_escape_repository(self):
        self.reference["path"] = "../secret"
        self.reject()

    def test_duplicate_json_and_public_token_config_are_rejected(self):
        path = self.root / "bad.json"
        path.write_text('{"key":1,"key":2}')
        with self.assertRaises(ValueError):
            publisher.load(path)
        path.write_text("{}")
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            publisher.load(path, private=True)

    def test_target_is_only_owned_loopback_test(self):
        config = {"admin_origin": "https://production.example", "partition": {"tenant_id": "a", "application_id": "commerce", "environment": "test"}}
        with patch.object(publisher.http.client, "HTTPConnection") as connection:
            with self.assertRaises(ValueError):
                publisher.request(config, "POST", "/api/governance/v1/catalog/publish", {})
            connection.assert_not_called()


if __name__ == "__main__":
    unittest.main()
