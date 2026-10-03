#!/usr/bin/env python3
"""从已验收目录和不可变商城导航导出本机Owner发布候选；不登录、不创建角色或Grant。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('publication', ROOT / 'deploy/governance-commerce-publication.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


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
    parser.add_argument('--approved-plan', type=Path, required=True)
    parser.add_argument('--current-manifest', type=Path, required=True)
    parser.add_argument('--commerce-repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', type=int, required=True)
    args = parser.parse_args()
    head = subprocess.check_output(['git', '-C', str(args.commerce_repo), 'rev-parse', 'HEAD'], text=True).strip()
    def blob(path):
        return subprocess.check_output(['git', '-C', str(args.commerce_repo), 'show', head + ':' + path]).decode()
    nav = blob('frontend/src/iam/navigation.ts')
    shell = blob('frontend/src/iam/CentralShell.tsx')
    manifest = export(publication.load(args.approved_plan), publication.load(ROOT / 'docs/design/oa-auth-unification/COMMERCE_PERMISSION_BINDINGS.json'),
                      publication.load(args.current_manifest), nav, shell, args.version)
    publication.save_private(args.output, {'manifest': manifest, 'auto_grants': False, 'auto_roles': False,
        'commerce_head': head, 'navigation_sha256': hashlib.sha256(nav.encode()).hexdigest(),
        'approved_plan_sha256': publication.sha(args.approved_plan), 'current_manifest_sha256': publication.sha(args.current_manifest)})
    print('PASS: exported %s menus and %s capabilities; no role or Grant writes' % (len(manifest['menus']), len(manifest['capabilities'])))


if __name__ == '__main__':
    main()
