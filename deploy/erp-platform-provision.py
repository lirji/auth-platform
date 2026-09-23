#!/usr/bin/env python3
"""开通 ERP 独立 Casdoor 应用和本地验证账号；不改其他项目，不打印凭据。"""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import urllib.parse

# 复用已经验证的 Casdoor 管理 API 及本机凭据读取方式；不复制默认口令到 ERP。
spec = importlib.util.spec_from_file_location('casdoor_provision', Path(__file__).with_name('wms-platform-provision.py'))
casdoor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(casdoor)
ORG = 'erp-platform'
APP = 'erp-platform'


def main():
    """创建前检查身份冲突，重跑不重置已有密码或替换已有绑定。"""
    if casdoor.BASE != 'http://localhost:8000':
        raise RuntimeError('此工具只用于已授权的本地 Casdoor，生产使用单独的受控开通流程')
    base = os.environ.get('ERP_PUBLIC_BASE_URL', 'http://localhost:8500')
    if base not in ('http://localhost:8500', 'http://127.0.0.1:8500', 'http://localhost:18500'):
        raise RuntimeError('本地回调 origin 不在工具允许范围内')
    credential_path = Path(os.environ.get('ERP_IAM_CREDENTIALS', str(Path.home()/'.config/erp-platform/oidc-local.json')))
    credential_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if credential_path.exists():
        if credential_path.is_symlink() or credential_path.stat().st_mode & 0o077:
            raise RuntimeError('凭据文件必须为非符号链接且权限 0600')
        credentials = json.loads(credential_path.read_text())
    else:
        credentials = {'client_id': APP, 'client_secret': secrets.token_urlsafe(32), 'users': {
            'demo_operator': {'username': 'demo_operator', 'password': secrets.token_urlsafe(24)},
            'unbound_user': {'username': 'unbound_user', 'password': secrets.token_urlsafe(24)}}}
        with os.fdopen(os.open(credential_path, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600), 'w') as stream:
            json.dump(credentials, stream)
    cid, secret = casdoor.builtin_app()
    admin = casdoor.request('login/oauth/access_token', {
        'grant_type': 'password', 'username': os.environ.get('CASDOOR_ADMIN', 'admin'),
        'password': os.environ.get('CASDOOR_ADMIN_PW', '123'),
        'client_id': cid, 'client_secret': secret, 'scope': 'openid'}, form=True)['access_token']

    def get(kind, owner, name):
        return casdoor.request('get-'+kind+'?'+urllib.parse.urlencode({'id': owner+'/'+name}), token=admin).get('data')

    def create(kind, payload):
        casdoor.request('add-'+kind, payload, admin)

    organization = get('organization', 'admin', ORG)
    if not organization:
        create('organization', {'owner':'admin', 'name':ORG, 'displayName':'ERP',
            'passwordType':'bcrypt', 'passwordOptions':['AtLeast6'], 'defaultApplication': APP,
            'accountItems':[{'name':'Password','visible':True,'viewRule':'Self','modifyRule':'Self'}]})
    app = get('application', 'admin', APP)
    if app and (app.get('organization') != ORG or app.get('clientId') != APP
                or app.get('clientSecret') != credentials['client_secret']):
        raise RuntimeError('同名应用或本地凭据冲突，拒绝覆盖')
    redirects = [base+'/auth/callback']
    payload = dict(app or {}, owner='admin', name=APP, displayName='ERP Platform', organization=ORG,
        clientId=APP, clientSecret=credentials['client_secret'], enablePassword=True,
        enableSignUp=False, enableSigninSession=True, enableCodeSignin=False,
        grantTypes=['authorization_code','refresh_token'],
        redirectUris=sorted(set((app or {}).get('redirectUris',[])+redirects)),
        signinMethods=[{'name':'Password','displayName':'Password','rule':'All'}], providers=[],
        expireInHours=1, refreshExpireInHours=8, tokenFormat='JWT-Custom', tokenSigningMethod='RS256',
        tokenFields=['Owner','Name','DisplayName'])
    if app:
        casdoor.request('update-application?'+urllib.parse.urlencode({'id':'admin/'+APP}),payload,admin)
    else:
        create('application',payload)
    for username, entry in credentials['users'].items():
        user = get('user',ORG,username)
        if user and entry.get('sub') != user.get('id'):
            raise RuntimeError('同名用户没有已保存的 subject 绑定，拒绝自动认领')
        if not user:
            create('user',{'owner':ORG,'name':username,'displayName':username,'type':'normal-user',
                'password':entry['password'],'signupApplication':APP,'isAdmin':False})
            user = get('user',ORG,username)
        if not user.get('id'):
            raise RuntimeError('Casdoor 未返回稳定 subject')
        entry['sub'] = user['id']
        # 每个成功创建的身份立刻存检查点，后续失败重跑不会错认其他账号。
        credential_path.write_text(json.dumps(credentials,ensure_ascii=False,indent=2))
    credentials.update(issuer='http://localhost:8000',owner=ORG,public_base_url=base,
                       redirect_uri=base+'/auth/callback')
    credential_path.write_text(json.dumps(credentials,ensure_ascii=False,indent=2))
    print(json.dumps({'issuer':credentials['issuer'],'client_id':APP,'owner':ORG,
        'redirect_uri':credentials['redirect_uri'],'credentials_file':str(credential_path)},ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # HTTP 错误响应可能含密码/令牌，只报告异常类别和人工可读的本地检查错误。
        print(str(error) if isinstance(error, RuntimeError) else type(error).__name__)
        raise SystemExit(1)
