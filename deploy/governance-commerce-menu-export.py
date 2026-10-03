#!/usr/bin/env python3
"""从已验收目录和不可变商城导航导出本机Owner发布候选；不登录、不创建角色或Grant。"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('publication', ROOT / 'deploy/governance-commerce-publication.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


def export_registry(registry, current, version):
    """日常增删使用稳定声明ID；原能力语义保留，菜单变化不被旧数量假设吞掉。"""
    require = publication.require
    require(set(registry) == {'schema_version', 'application', 'capabilities', 'menus'}
            and registry['schema_version'] == '1' and registry['application'] == 'commerce', 'invalid source declaration')
    code = lambda value: isinstance(value, str) and re.fullmatch(r'[a-z][a-z0-9._-]{0,99}', value) is not None
    caps, menus, positions, routes = {}, {}, set(), set()
    require(isinstance(registry['capabilities'], list) and 0 < len(registry['capabilities']) <= 200, 'capability bound')
    require(isinstance(registry['menus'], list) and len(registry['menus']) <= 100, 'menu bound')
    for cap in registry['capabilities']:
        require(set(cap) == {'code', 'resource_type', 'risk_level'} and code(cap['code']) and cap['code'].startswith('commerce.')
                and code(cap['resource_type']) and cap['risk_level'] in ('NORMAL', 'HIGH') and cap['code'] not in caps, 'invalid capability')
        caps[cap['code']] = cap
    for menu in registry['menus']:
        require(set(menu).issubset({'code', 'parent', 'route', 'any_of', 'label', 'position'})
                and {'code', 'parent', 'route', 'any_of'}.issubset(menu) and code(menu['code']) and menu['code'] not in menus, 'invalid menu')
        require(menu['parent'] is None or code(menu['parent']), 'invalid parent')
        require(isinstance(menu['any_of'], list) and len(menu['any_of']) <= 200 and len(set(menu['any_of'])) == len(menu['any_of'])
                and all(cap in caps for cap in menu['any_of']), 'unknown menu capability')
        route = menu['route']
        require(route is None or (isinstance(route, str) and re.fullmatch(r'/[a-zA-Z0-9/_-]*', route)
                and '//' not in route and menu['any_of'] and route not in routes), 'invalid route')
        if route is not None:
            routes.add(route)
        label, position = menu.get('label'), menu.get('position')
        require((label is None) == (position is None), 'paired display fields required')
        if label is not None:
            require(isinstance(label, str) and label == label.strip() and 0 < len(label) <= 80
                    and not any(ord(c) < 32 or 127 <= ord(c) <= 159 or c in '<>' for c in label), 'invalid label')
            require(type(position) is int and 0 <= position < 100 and position not in positions, 'invalid position')
            positions.add(position)
        menus[menu['code']] = dict(menu)
    for menu in menus.values():
        path, node = set(), menu
        while node is not None:
            require(node['code'] not in path, 'menu cycle')
            path.add(node['code'])
            require(node['parent'] is None or node['parent'] in menus, 'missing parent')
            node = None if node['parent'] is None else menus[node['parent']]
    require(current['application'] == registry['application'] and type(version) is int and current['manifest_version'] < version <= 2**53 - 1, 'explicit forward version required')
    require(all(caps.get(cap['code']) == cap for cap in current['capabilities']), 'existing capability meaning must remain')
    manifest = dict(schema_version='1', application='commerce', manifest_version=version,
                    capabilities=sorted(caps.values(), key=lambda cap: cap['code']), menus=sorted(menus.values(), key=lambda menu: menu['code']))
    require(len(json.dumps(manifest, ensure_ascii=False).encode()) <= 131072, 'manifest byte bound')
    return manifest


def export(approved, contract, current, navigation, shell, version):
    """已有授权与菜单全部保留，仅按真实导航补展示元数据；不接受缺失或多余页面。"""
    require = publication.require
    require(approved['status'] == 'READY_FOR_ISOLATED_OWNER_PUBLICATION' and approved['auto_grants'] is False, 'validated publication plan required')
    proposed = approved['manifest']
    expected = [{k: cap[k] for k in ('code', 'resource_type', 'risk_level')} for cap in contract['capabilities']]
    require(sorted(proposed['capabilities'], key=lambda c: c['code']) == sorted(expected, key=lambda c: c['code']), 'approved capability closure differs')
    require(current['application'] == proposed['application'] == 'commerce' and version == current['manifest_version'] + 1, 'explicit next commerce version required')
    caps = {cap['code']: cap for cap in current['capabilities']}
    for cap in proposed['capabilities']:
        require(cap['code'] not in caps or caps[cap['code']] == cap, 'existing capability meaning changed')
        caps[cap['code']] = cap
    original = {menu['code']: dict(menu) for menu in current['menus']}
    menus = {menu['code']: dict(menu) for menu in proposed['menus']}
    for code, menu in original.items():
        require(code not in menus or menus[code] == menu, 'existing menu meaning changed')
        menus[code] = menu
    parsed = set()
    position = 0
    for group in publication.GROUP.finditer(navigation):
        key, label, body = group.groups()
        node = menus['group.' + key]
        node.update(label=label, position=position)
        position += 1
        for page in publication.PAGE.finditer(body):
            route, title = page.groups()
            node = menus['menu.' + route[1:].replace('/', '.')]
            require(node['route'] == route and node['parent'] == 'group.' + key, 'approved page hierarchy differs')
            node.update(label=title, position=position)
            position += 1
            parsed.add(route)
    require(parsed == {m['route'] for m in proposed['menus'] if m['route'] and m['route'].startswith('/operations/')} and len(parsed) == approved['navigation_routes'] == 35, 'complete actual navigation required')
    require('"商品协作"' in shell and '"/collaboration/products"' in navigation, 'actual collaboration label/route required')
    menus['menu.collaboration.products'].update(label='商品协作', position=position)
    require(len(menus) == len(original) + 42 and len(caps) <= 200, 'complete bounded preserved catalog required')
    return dict(schema_version='1', application='commerce', manifest_version=version,
                capabilities=sorted(caps.values(), key=lambda c: c['code']), menus=sorted(menus.values(), key=lambda m: m['code']))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--approved-plan', type=Path)
    parser.add_argument('--current-manifest', type=Path, required=True)
    parser.add_argument('--commerce-repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', type=int, required=True)
    args = parser.parse_args()
    head = subprocess.check_output(['git', '-C', str(args.commerce_repo), 'rev-parse', 'HEAD'], text=True).strip()
    def blob(path):
        return subprocess.check_output(['git', '-C', str(args.commerce_repo), 'show', head + ':' + path]).decode()
    registry_path = 'frontend/src/iam/catalog.json'
    has_registry = subprocess.run(['git', '-C', str(args.commerce_repo), 'cat-file', '-e', head + ':' + registry_path], capture_output=True).returncode == 0
    if has_registry:
        raw = blob(registry_path)
        manifest = export_registry(json.loads(raw), publication.load(args.current_manifest), args.version)
        output = {'manifest': manifest, 'source': {'commit': head, 'artifact_hash': hashlib.sha256(raw.encode()).hexdigest()},
                  'auto_grants': False, 'auto_roles': False}
    else:
        # 已完成的历史有界发布仍可回放；新声明项目不再依赖此正则／固定数量分支。
        publication.require(args.approved_plan is not None, 'legacy revision requires explicit approved plan')
        nav = blob('frontend/src/iam/navigation.ts')
        shell = blob('frontend/src/iam/CentralShell.tsx')
        manifest = export(publication.load(args.approved_plan), publication.load(ROOT / 'docs/design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json'),
                          publication.load(args.current_manifest), nav, shell, args.version)
        output = {'manifest': manifest, 'auto_grants': False, 'auto_roles': False,
                  'commerce_head': head, 'navigation_sha256': hashlib.sha256(nav.encode()).hexdigest(),
                  'approved_plan_sha256': publication.sha(args.approved_plan), 'current_manifest_sha256': publication.sha(args.current_manifest)}
    publication.save_private(args.output, output)
    print('PASS: exported %s menus and %s capabilities; no role or Grant writes' % (len(manifest['menus']), len(manifest['capabilities'])))


if __name__ == '__main__':
    main()
