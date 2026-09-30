#!/usr/bin/env python3
"""离线核对商城能力契约和真实HTTP入口；不连接环境、不发布清单、不生成Grant。"""
import argparse
import json
import re
from pathlib import Path

CODE = re.compile(r'[a-z][a-z0-9._-]{0,99}\Z')
MODES = {'EMPLOYEE_CENTRAL_PLANNED', 'EXISTING_CATALOG', 'EXISTING_SCOPED',
         'EXISTING_STORE_READ', 'LEGACY_GRANT_GOVERNANCE_ONLY', 'LOCAL_SANDBOX_ONLY',
         'SEPARATE_PLATFORM_IDENTITY', 'STATIC_NO_AUTHORITY', 'LEGACY_SESSION_METADATA',
         'CUSTOMER_OR_LEGACY_IDENTITY'}


def require(condition, reason):
    """失败不降级为警告，防止未映射接口被当成已覆盖。"""
    if not condition:
        raise ValueError(reason)


def endpoints(commerce):
    """沿用现有Controller注解形式；新增无法解析的注解必须先扩展检查器。"""
    result = set()
    root = commerce / 'commerce-app/src/main/java/com/lrj/commerce/app/http'
    require(root.is_dir(), 'commerce Controller目录不存在')
    for path in root.rglob('*.java'):
        text = path.read_text()
        if '@RestController' not in text and '@Controller' not in text:
            continue
        before = text.split('public class', 1)[0]
        prefixes = re.findall(r'@RequestMapping\(\s*"([^"]*)"\s*\)', before)
        prefix = prefixes[0] if prefixes else ''
        matches = list(re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\b(?:\(([^)]*)\))?', text))
        require(len(matches) == len(re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping\b', text)),
                '存在未解析的HTTP注解: ' + str(path))
        for match in matches:
            paths = re.findall(r'"([^"]*)"', match[2]) if match[2] is not None else ['']
            require(paths and all(p == '' or p.startswith('/') for p in paths), 'HTTP注解参数需要人工核对: ' + str(path))
            for uri in paths:
                key = (match[1].upper(), prefix + uri)
                require(key not in result, '重复HTTP入口: ' + str(key))
                result.add(key)
    return result


def validate(contract, actual):
    """校验入口完备、动态动作、同资源角色快照及有限范围；不代表业务接管完成。"""
    require(contract['schema_version'] == 1 and contract['status'] == 'DESIGN_ONLY_NOT_PUBLISHED', '契约版本/发布状态无效')
    caps = contract['capabilities']
    require(0 < len(caps) <= 200, '能力清单必须在1—200项内')
    indexed = {c['code']: c for c in caps}
    require(len(indexed) == len(caps), '重复能力')
    for c in caps:
        require(CODE.fullmatch(c['code']) and c['code'].startswith('commerce.') and CODE.fullmatch(c['resource_type']), '非法能力/资源名称')
        require(c['risk_level'] in {'NORMAL', 'HIGH'}, '风险等级无效')
        allowed = {'TENANT_ALL', 'SPECIFIED_STORES', 'SPECIFIED_RESOURCES'} if c['resource_type'] in {'store', 'product'} else {'TENANT_ALL', 'SPECIFIED_RESOURCES'} if c['resource_type'] == 'merchant' else {'TENANT_ALL'}
        kinds = c['allowed_scope_kinds']
        require(kinds and len(kinds) == len(set(kinds)) and set(kinds) <= allowed, '资源范围未绑定: ' + c['code'])
        if c['code'] in {'commerce.store.create', 'commerce.merchant.create'}:
            require(kinds == ['TENANT_ALL'], '创建操作不能预授权不存在对象')
    role_codes = set()
    for role in contract['roles']:
        require(role['code'] not in role_codes and CODE.fullmatch(role['code']), '重复/非法角色快照')
        role_codes.add(role['code'])
        require(role['version'] == 1 and role['grant_policy'] == 'OWNER_REVIEW_REQUIRED', '岗位模板不能自动授予')
        values = role['capabilities']
        require(values and len(values) == len(set(values)), '空/重复岗位能力')
        require(all(c in indexed and indexed[c]['resource_type'] == role['resource_type'] for c in values), '角色混合不同资源范围')
    seen = set()
    for route in contract['routes']:
        key = (route['method'], route['path'])
        require(key not in seen and key in actual, '重复或不存在的HTTP入口: ' + str(key))
        seen.add(key)
        require(route['mode'] in MODES, '入口模式无效')
        values = route['capabilities']
        require(len(values) == len(set(values)) and all(c in indexed for c in values), '入口引用未知/重复能力')
        require(all(c in indexed for c in route.get('additional_checks', [])), '聚合引用未知能力')
        central = route['mode'].startswith('EXISTING_') or route['mode'] == 'EMPLOYEE_CENTRAL_PLANNED'
        require(bool(values) == central, '中央入口无能力或排除入口意外授予')
        if '{action' in route['path'] and central and route['mode'] != 'EXISTING_CATALOG':
            require(route.get('unknown_action') == 'DENY' and route.get('action_selector'), '动态动作没有失败关闭')
            require(bool(route.get('actions')) or route.get('target_check') == 'OWNER_ACTION_AND_TARGET_CAPABILITY', '动态动作缺少枚举/Owner绑定')
        if 'actions' in route:
            require(route.get('unknown_action') == 'DENY', '动态动作没有失败关闭')
            require(set(route['actions'].values()) == set(values), '动态动作与能力不一致')
        if 'type_bindings' in route:
            require(route.get('unknown_type') == 'DENY' and set(route['type_bindings']) <= {'store', 'product'}, '未知范围类型')
            require(set(values) == {c for cs in route['type_bindings'].values() for c in cs}, '类型绑定遗漏')
    require(seen == actual, '未映射HTTP入口: ' + str(sorted(actual - seen)))
    return {'status': 'PASS', 'capabilities': len(caps), 'routes': len(seen), 'role_snapshots': len(role_codes), 'published': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commerce', type=Path, required=True)
    parser.add_argument('--contract', type=Path, default=Path(__file__).resolve().parents[1] / 'docs/design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json')
    args = parser.parse_args()
    try:
        result = validate(json.loads(args.contract.read_text()), endpoints(args.commerce))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, 'FAIL: ' + str(error) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
