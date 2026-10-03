#!/usr/bin/env python3
"""商城Owner目录/岗位出版：候选先核对当前Owner证据，完整有界计划才走既有隔离HTTP，绝不创建Grant。"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
MAX_CAPABILITIES = 200
MAX_MENUS = 100
MAX_JSON_BYTES = 2 * 1024 * 1024
TEST_ADMIN_PORT = 21662
CODE = re.compile(r"[a-z][a-z0-9._-]{0,99}\Z")
GROUP = re.compile(r'\{\s*key:\s*"([a-z0-9_-]+)"\s*,\s*label:\s*"([^"\n]+)"\s*,\s*pages:\s*\[(.*?)\]\s*,?\s*\}', re.S)
PAGE = re.compile(r'\[\s*"(/operations/[a-z0-9_-]+)"\s*,\s*"([^"\n]+)"\s*\]')


def require(condition, message):
    """先失败关闭，不把缺证据、陈旧源码或未接线页面降级成可发布候选。"""
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path, private=False):
    """凭据只从显式0600普通文件读取；业务清单也限制体积并拒绝重复键。"""
    require(path.is_file() and not path.is_symlink(), "regular input file required")
    require(path.stat().st_size <= MAX_JSON_BYTES, "JSON exceeds bounded size")
    if private:
        require(path.stat().st_mode & 0o777 == 0o600, "private configuration requires0600")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique)


def save_private(path, value):
    """检查点使用O_EXCL；未知结果不能覆盖原意图或已有失败证据。"""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def source_file(roots, reference):
    """仓库引用只能读取声明根内的文件，不能通过路径逃逸读取凭据。"""
    require(set(reference) == {"repo", "path", "sha256"}, "source reference fields invalid")
    require(reference["repo"] in roots and re.fullmatch(r"[0-9a-f]{64}", reference["sha256"]), "unknown source repository/digest")
    root = roots[reference["repo"]].resolve()
    path = (root / reference["path"]).resolve()
    require(path.is_relative_to(root) and path.is_file(), "source path outside repository")
    require(sha(path) == reference["sha256"], "current source/evidence digest changed")
    return path


def terminal_pass(receipt):
    """沿用项目既有真实回执形状：PASS对象或非空逐项PASS列表，不重写历史报告伪造统一格式。"""
    if isinstance(receipt, list):
        return bool(receipt) and all(isinstance(check, dict) and check.get("result") == "PASS" and check.get("check") for check in receipt)
    if not isinstance(receipt, dict):
        return False
    if receipt.get("status") not in {None, "PASS", "COMPLETED"}:
        return False
    return receipt.get("status") == "PASS" or receipt.get("result") == "PASS"


def verified_proof(proof, roots):
    """终验声明必须同时绑定提交、当前源码及实际PASS回执；迁移/enum自身不是交付证据。"""
    require(proof.get("status") == "OWNER_TERMINAL_VALIDATION_PASS", "Owner terminal validation required")
    require(proof.get("sources") and proof.get("receipts") and proof.get("commits"), "proof must bind source/receipt/commit")
    declared = proof.get("capabilities", [])
    require(declared and len(declared) == len(set(declared)) and all(CODE.fullmatch(code) for code in declared), "explicit validated capability closure required")
    commit_repos = {commit["repo"] for commit in proof["commits"]}
    require(len(commit_repos) == len(proof["commits"]) and all(reference["repo"] in commit_repos for reference in proof["sources"]), "source repository lacks exact commit binding")
    for commit in proof["commits"]:
        require(commit["repo"] in roots, "unknown commit repository")
        head = subprocess.check_output(["git", "-C", str(roots[commit["repo"]]), "rev-parse", "HEAD"], text=True).strip()
        require(head == commit["head"], "Owner commit changed")
    for reference in proof["sources"]:
        path = source_file(roots, reference)
        try:
            subprocess.check_output(["git", "-C", str(roots[reference["repo"]]), "ls-files", "--error-unmatch", "--", reference["path"]], text=True, stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError as error:
            raise ValueError("Owner implementation source is not tracked") from error
        dirty = subprocess.check_output(["git", "-C", str(roots[reference["repo"]]), "status", "--porcelain", "--", reference["path"]], text=True)
        require(not dirty, "Owner source must be committed before publication")
        require(path.stat().st_size > 0, "empty implementation source")
    for reference in proof["receipts"]:
        path = source_file(roots, reference)
        receipt = load(path)
        require(terminal_pass(receipt), "actual terminal PASS receipt required")
    return True


def navigation(roots):
    """从真实Owner导航字面量读取当前入口；新增未识别结构必须明确补齐，不能静默漏菜单。"""
    routes, groups, references = {}, {}, []
    for repo, root in roots.items():
        path = root / "frontend/src/iam/navigation.ts"
        if not path.is_file():
            continue
        text = path.read_text()
        reference = {"repo": repo, "path": str(path.relative_to(root)), "sha256": sha(path)}
        references.append(reference)
        registry = root / "frontend/src/iam/catalog.json"
        if registry.is_file():
            # 新项目消费结构化声明；历史Owner能力证明流程仍核对实际导航，但不再解析生成后的TS。
            require('"./catalog.json"' in text or "'./catalog.json'" in text, "navigation does not consume declaration")
            declaration = load(registry)
            require(declaration.get('schema_version') == '1' and declaration.get('application') == 'commerce', 'invalid declaration identity')
            indexed = {menu['code']: menu for menu in declaration['menus']}
            require(len(indexed) == len(declaration['menus']) <= MAX_MENUS, 'duplicate/overflow source menus')
            references.append({"repo": repo, "path": str(registry.relative_to(root)), "sha256": sha(registry)})
            for menu in declaration['menus']:
                if menu['code'].startswith('group.') and menu['parent'] is None:
                    groups[menu['code'][6:]] = menu['label']
                if menu['route'] is not None and menu['route'].startswith('/operations/'):
                    parent = indexed.get(menu['parent'])
                    require(parent is not None and parent['code'].startswith('group.') and parent['parent'] is None, 'unsupported navigation hierarchy')
                    route, key = menu['route'], parent['code'][6:]
                    require(route not in routes, 'duplicate source navigation route')
                    routes[route] = {'group': key, 'label': menu['label']}
            continue
        parsed = set()
        for group in GROUP.finditer(text):
            key, label, body = group.groups()
            require(key not in groups or groups[key] == label, "Owner group labels disagree")
            groups[key] = label
            for page in PAGE.finditer(body):
                route, title = page.groups()
                require(route not in routes or routes[route]["group"] == key and routes[route]["label"] == title, "Owner route meanings disagree")
                routes[route] = {"group": key, "label": title}
                parsed.add(route)
        literal = set(re.findall(r'"(/operations/[a-z0-9_-]+)"', text))
        require(parsed == literal, "unparsed navigation entry")
    require(routes and len(routes) <= MAX_MENUS, "finite actual navigation required")
    return routes, groups, references


def plan(contract, mapping, readiness, roots, manifest_version):
    """输出全量缺口和完整角色快照；任何一项待证时仅生成可审查计划，不产生可执行清单。"""
    require(contract.get("schema_version") == 1 and contract.get("status") == "DESIGN_ONLY_NOT_PUBLISHED", "candidate contract status invalid")
    caps = contract["capabilities"]
    indexed = {cap["code"]: cap for cap in caps}
    require(0 < len(caps) <= MAX_CAPABILITIES and len(indexed) == len(caps), "duplicate/overflow capabilities")
    for cap in caps:
        require(CODE.fullmatch(cap["code"]) and cap["code"].startswith("commerce.") and CODE.fullmatch(cap["resource_type"]), "invalid capability namespace")
        require(cap["risk_level"] in {"NORMAL", "HIGH"}, "unknown risk")
        allowed = {"TENANT_ALL", "SPECIFIED_STORES", "SPECIFIED_RESOURCES"} if cap["resource_type"] in {"store", "product"} else {"TENANT_ALL", "SPECIFIED_RESOURCES"} if cap["resource_type"] == "merchant" else {"TENANT_ALL"}
        require(cap["allowed_scope_kinds"] and set(cap["allowed_scope_kinds"]) <= allowed and len(cap["allowed_scope_kinds"]) == len(set(cap["allowed_scope_kinds"])), "candidate resource scope unsupported")
        if cap["code"] in {"commerce.store.create", "commerce.merchant.create"}:
            require(cap["allowed_scope_kinds"] == ["TENANT_ALL"], "creation cannot authorize nonexistent instances")
    routes, groups, nav_sources = navigation(roots)
    mappings = {row["route"]: row for row in mapping["menus"]}
    require(len(mappings) == len(mapping["menus"]), "duplicate menu mapping")
    require(set(mappings) == set(routes) | {"/collaboration/products"}, "current navigation/fixed collaboration mapping incomplete")
    known_proofs, coverage = {}, []
    require(set(readiness.get("capability_proofs", {})) <= set(indexed), "Owner proof names unknown capability")
    for cap in caps:
        proof_id = readiness.get("capability_proofs", {}).get(cap["code"])
        if proof_id is None:
            coverage.append({**cap, "state": "AWAIT_OWNER_TERMINAL_PROOF"})
            continue
        require(proof_id in readiness.get("proofs", {}), "missing Owner proof definition")
        require(set(readiness["proofs"][proof_id].get("capabilities", [])) <= set(indexed), "Owner proof declares unknown capability")
        require(cap["code"] in readiness["proofs"][proof_id].get("capabilities", []), "receipt does not cover this capability")
        if proof_id not in known_proofs:
            known_proofs[proof_id] = verified_proof(readiness["proofs"][proof_id], roots)
        coverage.append({**cap, "state": "VERIFIED_CURRENT_OWNER_SOURCE", "proof": proof_id})
    ready = {cap["code"] for cap in coverage if cap["state"] == "VERIFIED_CURRENT_OWNER_SOURCE"}
    menus = [{"code": "group." + group, "parent": None, "route": None, "any_of": []} for group in sorted(groups)]
    for route, row in sorted(mappings.items()):
        values = row["capabilities"]
        require(values and len(values) == len(set(values)) and set(values) <= set(indexed), "menu unknown/duplicate capability")
        require(row.get("sources"), "actual page/Owner source references required")
        for source in row["sources"]:
            source_file(roots, source)
        if route in routes:
            parent = "group." + routes[route]["group"]
        else:
            parent = None
            require("commerce.product.read" in values and all(indexed[value]["resource_type"] == "product" for value in values), "collaboration uses actual product capability closure")
        if route == "/operations/products":
            require("commerce.product.read" in values and "commerce.catalog.operate" not in values, "product.read cannot alias catalog.operate")
        menus.append({"code": "menu." + route[1:].replace("/", "."), "parent": parent, "route": route, "any_of": sorted(values)})
    require(len(menus) <= MAX_MENUS, "menu count exceeds protocol bound")
    role_codes, role_caps = set(), set()
    roles = []
    for role in contract["roles"]:
        values = role["capabilities"]
        require(CODE.fullmatch(role["code"]) and role["code"] not in role_codes and role["version"] == 1, "invalid/duplicate fixed role")
        require(role["grant_policy"] == "OWNER_REVIEW_REQUIRED" and values and len(values) == len(set(values)), "role needs explicit Owner review")
        require(all(value in indexed and indexed[value]["resource_type"] == role["resource_type"] for value in values), "role mixes resource types")
        role_codes.add(role["code"])
        role_caps.update(values)
        roles.append({**role, "state": "READY_FOR_ROLE_CREATE_ONLY" if set(values) <= ready else "AWAIT_OWNER_TERMINAL_PROOF"})
    complete = ready == set(indexed)
    manifest = {"schema_version": "1", "application": "commerce", "manifest_version": manifest_version,
                "capabilities": [{key: cap[key] for key in ("code", "resource_type", "risk_level")} for cap in sorted(caps, key=lambda cap: cap["code"])], "menus": menus} if complete else None
    if manifest is not None:
        require(0 < manifest_version <= 2**53 - 1 and len(json.dumps(manifest).encode()) <= 131072, "manifest exceeds actual protocol bound")
    return {"schema_version": 1, "status": "READY_FOR_ISOLATED_OWNER_PUBLICATION" if complete else "AWAIT_OWNER_TERMINAL_PROOF", "coverage": coverage, "manifest": manifest,
            "role_snapshots": roles, "distinct_jobs": len({role["job"] for role in roles}), "template_union_capabilities": len(role_caps),
            "capabilities_outside_templates": sorted(set(indexed) - role_caps), "auto_grants": False, "navigation_routes": len(routes), "fixed_additional_routes": 1,
            "menu_routes": len(mappings), "menu_count": len(menus), "navigation_sources": nav_sources,
            "proofs": readiness.get("proofs", {}), "menu_sources": mapping["menus"]}


def request(config, method, path, payload=None, command=None):
    """只允许本任务回环Auth与test分区；不接受任意目标/重定向或命令行Token。"""
    origin = urllib.parse.urlsplit(config["admin_origin"])
    require(origin.scheme == "http" and origin.hostname == "127.0.0.1" and origin.port == TEST_ADMIN_PORT
            and not any((origin.username, origin.password, origin.path, origin.query, origin.fragment)), "owned loopback Auth origin required")
    require(config["partition"]["application_id"] == "commerce" and config["partition"]["environment"] == "test", "isolated commerce test partition required")
    uuid.UUID(config["partition"]["tenant_id"])
    require(re.fullmatch(r"auth_gov_p1_test_[a-f0-9]{12}", config.get("database", "")), "owned database declaration required")
    route = urllib.parse.urlsplit(path)
    require((method, route.path) in {("POST", "/api/governance/v1/catalog/publish"), ("POST", "/api/governance/v1/access/roles"), ("GET", "/api/governance/v1/access/published-catalog")}
            and not route.scheme and not route.netloc and not route.fragment, "publication HTTP endpoint outside closed set")
    if method == "POST":
        uuid.UUID(command or payload.get("command_id", ""))
    headers = {"Authorization": "Bearer " + config["owner_token"], "Content-Type": "application/json"}
    if command:
        headers["X-Command-Id"] = command
    conn = http.client.HTTPConnection(origin.hostname, origin.port, timeout=20)
    try:
        conn.request(method, path, body=None if payload is None else json.dumps(payload), headers=headers)
        response = conn.getresponse()
        require(response.status == 200, "Owner HTTP rejected; original checkpoint retained")
        body = response.read(MAX_JSON_BYTES + 1)
        require(len(body) <= MAX_JSON_BYTES, "Owner response exceeds bound")
        result = json.loads(body)
        require(isinstance(result, dict), "unconfirmed response shape; original key retained")
        return result
    finally:
        conn.close()


def apply(publication, config, directory):
    """先持久化精确HTTP意图；角色固定版本按原契约创建，整个工具没有Grant/Policy写入口。"""
    require(publication["status"] == "READY_FOR_ISOLATED_OWNER_PUBLICATION" and publication["manifest"] is not None, "unverified candidate publication forbidden")
    require(directory.resolve().is_relative_to(ROOT / ".local/commerce-catalog-publication"), "checkpoint outside owned publication directory")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    fingerprint = hashlib.sha256(json.dumps(publication, sort_keys=True).encode()).hexdigest()
    checkpoint = directory / "intent.json"
    if checkpoint.exists():
        intent = load(checkpoint, private=True)
        require(intent["fingerprint"] == fingerprint and intent["partition"] == config["partition"], "cannot replace unknown publication intent")
    else:
        intent = {"fingerprint": fingerprint, "partition": config["partition"], "publish_command": str(uuid.uuid4()),
                  "role_commands": {role["code"]: str(uuid.uuid4()) for role in publication["role_snapshots"]}}
        save_private(checkpoint, intent)
    query = urllib.parse.urlencode(config["partition"])
    # 独立夹具先用同一已终验cap闭集准备v1基线；先检查真实绑定，不能发布后才发现旧Auth不支持资源。
    baseline = request(config, "GET", "/api/governance/v1/access/published-catalog?" + query)
    required_resources = {cap["resource_type"] for cap in publication["manifest"]["capabilities"]}
    baseline_resources = {resource["code"]: resource for resource in baseline["resource_types"]}
    require(required_resources <= set(baseline_resources) and all(baseline_resources[resource]["scope_supported"] for resource in required_resources), "Auth resource bindings dependency missing before publication")
    publication_receipt = request(config, "POST", "/api/governance/v1/catalog/publish", publication["manifest"], intent["publish_command"])
    current = request(config, "GET", "/api/governance/v1/access/published-catalog?" + query)
    require(current["manifest_version"] == publication["manifest"]["manifest_version"], "late manifest version changed")
    actual = {cap["code"]: cap for cap in current["capabilities"]}
    expected = {cap["code"]: cap for cap in publication["manifest"]["capabilities"]}
    require(set(actual) == set(expected) and current["menus"] == publication["manifest"]["menus"], "actual published snapshot differs")
    require(all(not cap["disabled"] and cap["grantable"] and all(cap[key] == expected[code][key] for key in ("resource_type", "risk_level")) for code, cap in actual.items()), "current disabled/ceiling mismatch")
    require(all(resource["scope_supported"] for resource in current["resource_types"]), "Auth resource bindings dependency missing")
    roles = []
    for role in publication["role_snapshots"]:
        latest = request(config, "GET", "/api/governance/v1/access/published-catalog?" + query)
        require(latest["view_hash"] == current["view_hash"], "catalog/authority changed during role publication")
        payload = {**config["partition"], "command_id": intent["role_commands"][role["code"]], "role_code": role["code"], "role_version": role["version"], "capabilities": sorted(role["capabilities"])}
        receipt = request(config, "POST", "/api/governance/v1/access/roles", payload)
        require(receipt.get("role_code") == role["code"] and receipt.get("version") == role["version"] and sorted(receipt.get("capabilities", [])) == payload["capabilities"], "unconfirmed actual role receipt; original key retained")
        uuid.UUID(receipt["id"])
        roles.append(receipt)
    require(request(config, "GET", "/api/governance/v1/access/published-catalog?" + query)["view_hash"] == current["view_hash"], "late catalog/authority changed")
    result = {"status": "HTTP_PUBLICATION_AND_ROLE_CREATE_PASS_SQL_UI_PENDING", "manifest_receipt": publication_receipt, "catalog": current,
              "roles": roles, "auto_grants": False, "intent_sha256": sha(checkpoint)}
    output = directory / ("http-result-" + uuid.uuid4().hex + ".json")
    save_private(output, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=ROOT / "docs/design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json")
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--readiness", type=Path, required=True)
    parser.add_argument("--source-root", action="append", default=[], metavar="NAME=PATH")
    parser.add_argument("--manifest-version", type=int, default=2)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply-config", type=Path)
    parser.add_argument("--checkpoint-directory", type=Path)
    args = parser.parse_args()
    pairs = [value.split("=", 1) for value in args.source_root]
    require(0 < len(pairs) <= 8 and len({pair[0] for pair in pairs}) == len(pairs), "duplicate/overflow repository roots")
    roots = {name: Path(path).resolve() for name, path in pairs}
    publication = plan(load(args.contract), load(args.mapping), load(args.readiness), roots, args.manifest_version)
    require(args.output.resolve().is_relative_to(ROOT / ".local/commerce-catalog-publication"), "output outside owned publication directory")
    if args.output.exists():
        require(load(args.output, private=True) == publication, "cannot replace original publication plan")
    else:
        save_private(args.output, publication)
    if args.apply_config:
        require(args.checkpoint_directory is not None, "owned checkpoint directory required")
        apply(publication, load(args.apply_config, private=True), args.checkpoint_directory)
    print(json.dumps({key: publication[key] for key in ("status", "distinct_jobs", "template_union_capabilities", "navigation_routes", "menu_count", "auto_grants")}))


if __name__ == "__main__":
    main()
