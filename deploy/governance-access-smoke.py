#!/usr/bin/env python3
"""P2受控管理HTTP验收；只使用专属身份、数据库和回环任务进程。"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

spec = importlib.util.spec_from_file_location('context_smoke', Path(__file__).with_name('governance-context-smoke.py'))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def token(base, fixture, purpose, user_kind):
    """真实PKCE取得指定用户Token，凭据和响应仅保存在0600本地文件。"""
    client = fixture['clients'][purpose]
    user = fixture['users'][user_kind]
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    redirect = 'http://127.0.0.1:18089/callback'
    # 沿用身份夹具已登记回调，不修改共享应用。
    fixture_script = Path(__file__).with_name('governance-casdoor-fixture.py').read_text()
    import re
    redirect = re.search(r"^REDIRECT = '([^']+)'", fixture_script, re.M).group(1)
    query = urllib.parse.urlencode(dict(clientId=client['name'], responseType='code', redirectUri=redirect,
                                       scope='openid', state=secrets.token_urlsafe(20), nonce=secrets.token_urlsafe(20),
                                       code_challenge_method='S256', code_challenge=challenge))
    data = json.dumps(dict(type='code', organization=fixture['organization'], username=user['name'], password=user['password'],
                           application=client['name'], signinMethod='Password')).encode()
    req = urllib.request.Request(base+'/api/login?'+query, data=data, headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=10) as r:
        code=json.load(r)['data']
    data=urllib.parse.urlencode(dict(grant_type='authorization_code', client_id=client['name'], code=code,
                                   code_verifier=verifier, redirect_uri=redirect)).encode()
    req=urllib.request.Request(base+'/api/login/oauth/access_token', data=data, headers={'Content-Type':'application/x-www-form-urlencoded'})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)['access_token']


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--directory', default='.local/governance/p2')
    args=parser.parse_args()
    root=Path(args.directory).resolve()
    run=root/('access-'+secrets.token_hex(6));run.mkdir(parents=True,mode=0o700)
    fixture=json.loads(h.read_private(root/'identity/casdoor.json'))
    tokens=json.loads(h.read_private(root/'identity/tokens.json'))
    ops=json.loads(h.read_private(root.parent/'casdoor-isolated/management-client.json'))
    db=h.read_private(root.parent/'database.properties')
    jar=next(Path('auth-platform-admin/target').glob('auth-platform-admin-*.jar'))
    def cli(name, args):
        with os.fdopen(os.open(run/(name+'-'+secrets.token_hex(4)+'.log'), os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as out:
            result=subprocess.run(['java','-Dloader.main=com.lrj.authz.governance.cli.'+name,'-cp',str(jar),
                'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,args)],stdout=out,stderr=subprocess.STDOUT,timeout=30)
        if result.returncode:raise RuntimeError('controlled CLI failed: '+name)
    tenant=str(uuid.uuid4()); app='commerce'; env='p2-'+secrets.token_hex(4)
    members={}
    for kind in ['internal','external']:
        # 每个源身份固定principal，多次验收仅新增隔离企业成员。
        principal=str(uuid.uuid5(uuid.NAMESPACE_URL,'p2:'+fixture['users'][kind]['id']))
        member=str(uuid.uuid4());members[kind]=member
        values={'command.id':str(uuid.uuid4()),'operator.ref':'p2-fixture','tenant.id':tenant,'tenant.code':'p2-'+tenant,
            'principal.id':principal,'issuer':h.ISSUER,'subject':fixture['users'][kind]['id'],'membership.id':member,
            'valid.from':'2020-01-01T00:00:00Z','source.system':'p2-fixture','source.tenant.ref':tenant,'source.subject.ref':kind}
        path=run/(kind+'.properties');h.private(path,h.props(values));cli('GovernanceCli',['bootstrap',root.parent/'database.properties',path])
        if kind=='internal':owner=principal
    catalog={'catalog.application':app,'catalog.owner-principal':owner,'catalog.entry-origin':'http://127.0.0.1:8601',
        'catalog.operator':'p2-fixture','catalog.command':str(uuid.uuid4()),'catalog.owner-issuer':h.ISSUER,'catalog.owner-subject':fixture['users']['internal']['id']}
    h.private(run/'catalog.properties',db+h.props(catalog));cli('CatalogCli',['register',run/'catalog.properties','configured'])
    manifest={'schema_version':'1','application':app,'manifest_version':1,'capabilities':[
        {'code':'commerce.store.read','resource_type':'store','risk_level':'NORMAL'},
        {'code':'commerce.store.manage','resource_type':'store','risk_level':'HIGH'}],
        'menus':[{'code':'stores','parent':None,'route':'/stores','any_of':['commerce.store.read']}]}
    (run/'manifest.json').write_text(json.dumps(manifest));cli('CatalogCli',['publish',run/'catalog.properties',run/'manifest.json'])
    access={'access.tenant':tenant,'access.application':app,'access.environment':env,'access.manager':members['internal'],
        'access.generation':1,'access.capabilities':'commerce.store.read,commerce.store.manage','access.max-duration-seconds':3600,
        'access.operator':'p2-fixture','access.command':str(uuid.uuid4())}
    h.private(run/'access.properties',db+h.props(access));cli('AccessBootstrapCli',[run/'access.properties'])
    client=fixture['clients']['management']
    authority={'issuer':h.ISSUER,'jwks.uri':h.ISSUER+'/.well-known/jwks','audience':client['name'],'client.id':client['name'],
        'client.secret':client['secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
    h.private(run/'admin.properties',db+h.props(authority))
    h.start(jar,18102,run/'admin.log',config=run/'admin.properties',access=True)
    admin=[('Authorization','Bearer '+tokens['management']['access_token'])]
    other_token=token(h.ISSUER,fixture,'management','external')
    other=[('Authorization','Bearer '+other_token)]
    prefix='/api/governance/v1/access'
    partition={'tenant_id':tenant,'application_id':app,'environment':env}
    def call(name,path,body,headers=admin,status=200,code=None):return h.expect(name,18102,prefix+path,headers,json.dumps(body).encode(),status,code)
    role_body={**partition,'command_id':str(uuid.uuid4()),'role_code':'reader','role_version':1,'capabilities':['commerce.store.read']}
    h.expect('unauthenticated management denied',18102,prefix+'/roles',body=json.dumps(role_body).encode(),status=401,code='INVALID_CREDENTIAL')
    call('member is not manager','/roles',role_body,other,403,'ACCESS_DENIED')
    call('wrong audience denied','/roles',role_body,[('Authorization','Bearer '+tokens['business']['access_token'])],401,'INVALID_CREDENTIAL')
    call('forged principal rejected','/roles',{**role_body,'principal_id':owner},status=400,code='INVALID_ARGUMENT')
    role=call('fixed role created','/roles',role_body)
    assert call('role command replay','/roles',role_body)['id']==role['id']
    call('same command changed body','/roles',{**role_body,'role_code':'changed'},status=409,code='COMMAND_CONFLICT')
    call('cross environment denied','/roles',{**role_body,'environment':'prod'},status=403,code='ACCESS_DENIED')
    now=datetime.now(timezone.utc)
    body={**partition,'command_id':str(uuid.uuid4()),'member_id':members['external'],'member_generation':1,'role_id':role['id'],
        'scope':'TENANT_ALL','source_id':'p2-http','valid_from':(now-timedelta(seconds=5)).isoformat().replace('+00:00','Z'),
        'valid_to':(now+timedelta(minutes=30)).isoformat().replace('+00:00','Z')}
    call('self grant denied','/grants',{**body,'member_id':members['internal']},status=403,code='ACCESS_DENIED')
    call('unsupported scope denied','/grants',{**body,'scope':'STORE'},status=400,code='INVALID_ARGUMENT')
    grant=call('grant accepted pending graph','/grants',body,status=202);assert grant['state']=='PENDING'
    assert call('grant replay unique','/grants',body,status=202)['id']==grant['id']
    query=urllib.parse.urlencode(partition)
    state=h.expect('management state from database',18102,prefix+'/state?'+query,admin)
    assert len(state['roles'])==1 and len(state['grants'])==1
    h.expect('member cannot enumerate state',18102,prefix+'/state?'+query,other,status=403,code='ACCESS_DENIED')
    revoke={**partition,'command_id':str(uuid.uuid4()),'grant_id':grant['id'],'expected_version':1}
    assert call('revoke accepted without graph','/revoke',revoke)['state']=='REVOKED'
    assert call('revoke replay','/revoke',revoke)['state']=='REVOKED'
    h.private(run/'fixture.json',json.dumps({'tenant':tenant,'application':app,'environment':env,'members':members,'role_id':role['id'],'grant_id':grant['id'],
        'admin_token':tokens['management']['access_token'],'member_management_token':other_token,'member_business_token':token(h.ISSUER,fixture,'business','external')}))
    (run/'result.json').write_text(json.dumps(h.CHECKS,indent=2)+'\n')
    print(json.dumps({'checks':len(h.CHECKS),'result':'PASS','evidence':str(run/'result.json')}))

if __name__=='__main__':
    try:main()
    finally:
        for process in h.PROCESSES:h.stop(process)
