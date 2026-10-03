"""出版工具的有界失败场景；纯本地fixture不冒充Owner或真实数据库验收。"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("commerce_publication", ROOT / "deploy/governance-commerce-publication.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class CommercePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
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

    def test_ignored_untracked_source_cannot_masquerade_as_clean_commit(self):
        def git(args, **kwargs):
            if 'ls-files' in args:
                raise publisher.subprocess.CalledProcessError(1, args)
            return 'a' * 40 if 'rev-parse' in args else ''
        with patch.object(publisher.subprocess, 'check_output', side_effect=git):
            self.reject()

    def test_owner_proof_unknown_capability_is_rejected(self):
        self.readiness['proofs']['owner']['capabilities'].append('commerce.unknown')
        self.reject()

    def test_real_historical_pass_shapes_are_preserved_without_relabel(self):
        for receipt in [{'result': 'PASS'}, {'status': 'COMPLETED', 'result': 'PASS'}, [{'check': 'actual original typed port', 'result': 'PASS'}]]:
            path=self.root/'receipt.json';path.write_text(json.dumps(receipt));self.receipt['sha256']=publisher.sha(path)
            self.assertEqual(self.build()['status'], 'READY_FOR_ISOLATED_OWNER_PUBLICATION')
        for receipt in [[], [{'check': 'failure', 'result': 'FAIL'}], {'status':'FAIL','result':'PASS'}]:
            path=self.root/'receipt.json';path.write_text(json.dumps(receipt));self.receipt['sha256']=publisher.sha(path)
            self.reject()

    def test_navigation_growth_cannot_be_silently_omitted(self):
        path = self.root / "frontend/src/iam/navigation.ts"
        path.write_text(path.read_text().replace(']]}', '],["/operations/new","新页"]]}'))
        self.reject()

    def test_structured_navigation_retains_stable_id_and_detects_unsupported_hierarchy(self):
        directory = self.root / "frontend/src/iam"
        (directory / "navigation.ts").write_text('import declaration from "./catalog.json";')
        declaration = {"schema_version": "1", "application": "commerce", "menus": [
            {"code": "group.catalog", "parent": None, "route": None, "label": "商品"},
            {"code": "stable.product.page", "parent": "group.catalog", "route": "/operations/renamed", "label": "新名称"}]}
        path = directory / "catalog.json"
        path.write_text(json.dumps(declaration))
        routes, groups, references = publisher.navigation(self.roots)
        self.assertEqual(routes, {"/operations/renamed": {"group": "catalog", "label": "新名称"}})
        self.assertEqual(groups, {"catalog": "商品"})
        self.assertEqual(len(references), 2)
        declaration["menus"][1]["parent"] = "missing"
        path.write_text(json.dumps(declaration))
        with self.assertRaisesRegex(ValueError, "unsupported navigation hierarchy"):
            publisher.navigation(self.roots)

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

    def execution_fixture(self):
        publication = self.build()
        current = {"manifest_version": 2, "view_hash": "current-v2", "menus": publication["manifest"]["menus"],
                   "capabilities": [{**cap, "disabled": False, "grantable": True} for cap in publication["manifest"]["capabilities"]],
                   "resource_types": [{"code": resource, "scope_supported": True} for resource in ("store", "product")]}
        config = {"partition": {"tenant_id": str(uuid.uuid4()), "application_id": "commerce", "environment": "test"}}
        directory = self.root / ".local/commerce-catalog-publication/run"
        role = {"id": str(uuid.uuid4()), "role_code": "reader.product", "version": 1, "capabilities": ["commerce.product.read"]}
        return publication, current, config, directory, role

    def test_old_auth_resource_binding_is_rejected_before_publish(self):
        publication, current, config, directory, _ = self.execution_fixture()
        current["resource_types"][0]["scope_supported"] = False
        with patch.object(publisher, "ROOT", self.root), patch.object(publisher, "request", return_value=current) as request:
            with self.assertRaisesRegex(ValueError, "before publication"):
                publisher.apply(publication, config, directory)
            self.assertEqual([call.args[1] for call in request.call_args_list], ["GET"])

    def test_lost_committed_publish_replays_identical_original_key(self):
        publication, current, config, directory, role = self.execution_fixture()
        published = []
        def response(*args):
            if args[1] == "GET":
                return current
            if args[2].endswith("catalog/publish"):
                published.append((args[3], args[4]))
                if len(published) == 1:
                    raise OSError("lost response after server commit")
                return {"manifest_version": 2}
            return role
        with patch.object(publisher, "ROOT", self.root), patch.object(publisher, "request", side_effect=response):
            with self.assertRaises(OSError):
                publisher.apply(publication, config, directory)
            result = publisher.apply(publication, config, directory)
        self.assertEqual(published[0], published[1])
        self.assertEqual(result["manifest_receipt"], {"manifest_version": 2})
        self.assertFalse(result["auto_grants"])

    def test_bad_role_receipt_does_not_confirm_success_or_replace_intent(self):
        publication, current, config, directory, _ = self.execution_fixture()
        def response(*args):
            return current if args[1] == "GET" else {}
        with patch.object(publisher, "ROOT", self.root), patch.object(publisher, "request", side_effect=response):
            with self.assertRaisesRegex(ValueError, "role receipt"):
                publisher.apply(publication, config, directory)
            original = (directory / "intent.json").read_bytes()
            with self.assertRaises(ValueError):
                publisher.apply(publication, config, directory)
        self.assertEqual((directory / "intent.json").read_bytes(), original)
        self.assertEqual(list(directory.glob("http-result-*.json")), [])

    def test_late_authority_change_blocks_role_write(self):
        publication, current, config, directory, _ = self.execution_fixture()
        get_count = 0
        writes = []
        def response(*args):
            nonlocal get_count
            if args[1] == "GET":
                get_count += 1
                return {**current, "view_hash": "revoked"} if get_count == 3 else current
            writes.append(args[2])
            return {}
        with patch.object(publisher, "ROOT", self.root), patch.object(publisher, "request", side_effect=response):
            with self.assertRaisesRegex(ValueError, "authority changed"):
                publisher.apply(publication, config, directory)
        self.assertEqual(writes, ["/api/governance/v1/catalog/publish"])


if __name__ == "__main__":
    unittest.main()
