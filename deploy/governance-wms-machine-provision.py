#!/usr/bin/env python3
"""本机旧内部协议专用机器身份：固定Owner主体，最小scope，不导入中央人类成员或修改旧应用。"""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import secrets
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-directory', type=Path, required=True)
    parser.add_argument('--bootstrap-directory', type=Path, help='显式更新本任务Docker初始化资料中的对账机器令牌')
    args = parser.parse_args(); os.umask(0o077)
    def load(name, file):
        spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
        value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
    p = load('wms_machine_private', 'governance-wms-provision.py'); legacy = load('wms_machine_legacy', 'wms-platform-provision.py')
    root = args.state_directory.resolve(); root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if root.stat().st_mode & 0o777 != 0o700: raise RuntimeError('机器凭据目录必须0700')
    path = root / 'state.json'
    specs = {'inventory-worker': ['serial.registry.read', 'serial.registry.write'],
             'recon-worker': ['recon.evidence'], 'wms-fulfillment': ['inventory.tcc.try']}
    state = json.loads(p.read_private(path)) if path.exists() else {
        'issuer': 'http://localhost:8000', 'client': 'wms-platform', 'enterprise': 'ENT-DEMO',
        'machines': {subject: {'name': 'central-machine-' + subject, 'id': subject, 'password': secrets.token_urlsafe(32)} for subject in specs}}
    if (state['issuer'], state['client'], state['enterprise']) != ('http://localhost:8000', 'wms-platform', 'ENT-DEMO'):
        raise RuntimeError('机器发行方绑定不符')
    p.private(path, json.dumps(state))
    client, secret = legacy.builtin_app()
    authorization = 'Basic ' + base64.b64encode((client + ':' + secret).encode()).decode()
    def request(endpoint, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(state['issuer'] + '/api/' + endpoint, data=data,
            headers={'Authorization': authorization, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response: result = json.load(response)
        if result.get('status') != 'ok': raise RuntimeError('机器身份API拒绝 ' + endpoint.split('?')[0])
        return result.get('data')
    def get(kind, owner, name): return request('get-' + kind + '?' + urllib.parse.urlencode({'id': owner + '/' + name}))
    app = get('application', 'admin', 'wms-platform')
    if not app or app['organization'] != 'wms-platform' or app['clientId'] != 'wms-platform': raise RuntimeError('旧WMS应用不符')
    p.private(root / 'original-application.json', json.dumps(app))
    for subject, capabilities in specs.items():
        machine = state['machines'][subject]
        if machine['id'] != subject or machine['name'] != 'central-machine-' + subject: raise RuntimeError('固定机器主体不符')
        fields = {'owner': 'wms-platform', 'name': machine['name'], 'id': subject, 'displayName': machine['name'],
                  'type': 'normal-user', 'password': machine['password'], 'isAdmin': False, 'signupApplication': 'wms-platform',
                  'properties': {'enterprise_id': 'ENT-DEMO', 'warehouses': 'WH-A,WH-B'}}
        current = get('user', 'wms-platform', machine['name'])
        if current:
            if any(current.get(key) != fields[key] for key in ['id', 'isAdmin', 'signupApplication', 'properties']): raise RuntimeError('既有机器身份冲突，不覆盖')
        else: request('add-user', fields)
        role = get('role', 'wms-platform', machine['name'])
        role_fields = {'owner': 'wms-platform', 'name': machine['name'], 'displayName': machine['name'], 'isEnabled': True,
                       'users': ['wms-platform/' + machine['name']]}
        if role:
            if role.get('users') != role_fields['users'] or not role.get('isEnabled'): raise RuntimeError('既有机器角色冲突')
        else: request('add-role', role_fields)
        for capability in capabilities:
            permission = get('permission', 'wms-platform', capability)
            if not permission or not permission.get('isEnabled') or permission.get('effect') != 'Allow': raise RuntimeError('原Owner scope未登记，不猜造权限')
            ref = 'wms-platform/' + machine['name']
            if ref not in (permission.get('roles') or []):
                # 只追加新专用角色边，保留原人类角色/动作/资源；每次变更前保存原记录。
                before = root / ('before-' + capability + '.json')
                if not before.exists(): p.private(before, json.dumps(permission))
                permission['roles'] = sorted(set((permission.get('roles') or []) + [ref]))
                request('update-permission?' + urllib.parse.urlencode({'id': 'wms-platform/' + capability}), permission)
        form = urllib.parse.urlencode({'grant_type': 'password', 'client_id': app['clientId'], 'client_secret': app['clientSecret'],
            'username': machine['name'], 'password': machine['password'], 'scope': 'openid profile'}).encode()
        req = urllib.request.Request(state['issuer'] + '/api/login/oauth/access_token', data=form,
            headers={'Content-Type': 'application/x-www-form-urlencoded'})
        with urllib.request.urlopen(req, timeout=10) as response: token = json.load(response)
        jwt = token.get('access_token', ''); encoded = jwt.split('.')[1]
        claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        if claims.get('sub') != subject or claims.get('enterprise_id') != 'ENT-DEMO' or set(claims.get('scope') or []) != set(capabilities):
            raise RuntimeError('实际IdP发行主体/最小scope不符')
        p.private(root / (subject + '-token.json'), json.dumps(token))
        if subject == 'recon-worker' and args.bootstrap_directory:
            bootstrap = args.bootstrap_directory.resolve()
            if bootstrap.is_symlink() or not bootstrap.is_dir() or bootstrap.stat().st_mode & 0o777 != 0o700:
                raise RuntimeError('Docker初始化资料必须为既有0700目录')
            p.private(bootstrap / 'recon-worker.jwt', jwt)
    if get('application', 'admin', 'wms-platform') != app: raise RuntimeError('旧应用被意外修改')
    p.private(root / 'result.json', json.dumps({'result': 'PASS', 'real_idp': True, 'fixed_subjects': list(specs),
        'application_unchanged': True, 'central_human_grants_created': 0, 'existing_human_role_edges_removed': 0}))
    print('PASS: 三个旧内部Owner机器身份真实发行，最小scope；原应用及人类角色保持。')


if __name__ == '__main__':
    main()
