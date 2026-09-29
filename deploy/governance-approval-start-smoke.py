#!/usr/bin/env python3
"""真实OA HTTP进程验证中央申请启动幂等；仅写显式隔离库，不声明引擎审批完成。"""
import argparse
import concurrent.futures
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid


def private(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as out:
        out.write(content)


def call(key, path, body, signature=None):
    wire=json.dumps(body,separators=(',', ':'),ensure_ascii=False).encode()
    if signature is None:
        meta=f'auth-platform:test:{int(time.time())}:{uuid.uuid4()}'
        signature=meta+':'+hmac.new(key.encode(),meta.encode()+b'\nPOST\n'+path.encode()+b'\n'+wire,hashlib.sha256).hexdigest()
    conn=http.client.HTTPConnection('127.0.0.1',18420,timeout=5)
    try:
        conn.request('POST',path,wire,{'Content-Type':'application/json','X-Approval-Signature':signature})
        response=conn.getresponse()
        return response.status,response.read(),signature
    finally:
        conn.close()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--oa',required=True)
    parser.add_argument('--database-config',required=True)
    parser.add_argument('--infra-env',required=True)
    parser.add_argument('--directory',default='.local/governance/p4/http')
    args=parser.parse_args()
    directory=Path(args.directory).resolve(); directory.mkdir(parents=True,exist_ok=True)
    config=Path(args.database_config).resolve()
    if config.is_symlink() or config.stat().st_mode & 0o777 != 0o600: raise RuntimeError('private database config required')
    props=dict(line.strip().split('=',1) for line in config.read_text().splitlines() if '=' in line)
    if not props['jdbc.url'].startswith('jdbc:postgresql://127.0.0.1:45432/auth_gov_p1_test_'): raise RuntimeError('isolated database required')
    state_file=directory/'fixture.json'
    if not state_file.exists():
        private(state_file,json.dumps({'tenant':str(uuid.uuid4()),'member':str(uuid.uuid4()),'approver':str(uuid.uuid4()),'key':secrets.token_urlsafe(36)}))
    state=json.loads(state_file.read_text());settings=directory/'application.properties'
    infra=dict(line.strip().split('=',1) for line in Path(args.infra_env).read_text().splitlines() if '=' in line and not line.startswith('#'))
    redis_password=infra.get('REDIS7_PASSWORD')
    if not redis_password: raise RuntimeError('explicit Redis credential missing')
    if not settings.exists():
        lines={'server.address':'127.0.0.1','server.port':'18420','spring.datasource.url':props['jdbc.url'],
               'spring.datasource.username':props['jdbc.username'],'spring.datasource.password':props['jdbc.password'],
               'spring.datasource.hikari.maximum-pool-size':'8','spring.data.redis.port':'46379','spring.data.redis.password':redis_password,'spring.kafka.bootstrap-servers':'127.0.0.1:1',
               'oa.security.mode':'JWT','oa.security.issuer':'http://127.0.0.1:18090','oa.security.jwk-set-uri':'http://127.0.0.1:18090/.well-known/jwks',
               'oa.security.audience':'p4-service-only','oa.iam.cache.l2-enabled':'false','oa.iam.outbox.enabled':'false',
               'oa.crypto.data-key':__import__('base64').b64encode(secrets.token_bytes(32)).decode(),
               'oa.flow.workflow.mode':'REMOTE','oa.flow.workflow.base-url':'http://127.0.0.1:8300','workflow.client.enabled':'true',
               'workflow.client.base-url':'http://127.0.0.1:8300','oa.flow.outbox.poll-ms':'86400000',
               'oa.flow.central-approval.enabled':'true','oa.flow.central-approval.tenant-id':state['tenant'],
               'oa.flow.central-approval.application-id':'commerce','oa.flow.central-approval.environment':'test',
               'oa.flow.central-approval.oa-tenant-id':'1','oa.flow.central-approval.inbound-key':state['key'],
               'oa.flow.central-approval.allow-loopback-http':'true'}
        for field,user in [('member','p4-requester'),('approver','p4-approver')]:
            prefix='oa.flow.central-approval.members.'+state[field]+'.'
            lines.update({prefix+'generation':'1',prefix+'user-id':user,prefix+'org-id':'1',prefix+'org-path':'/1/'})
        private(settings,'\n'.join(k+'='+v for k,v in lines.items())+'\n')
    jar=Path(args.oa).resolve()/'oa-app/target/oa-app-0.1.0-SNAPSHOT.jar'
    log=open(directory/'oa.log','w')
    process=subprocess.Popen(['java','-jar',str(jar),'--spring.config.additional-location=file:'+str(settings)],stdout=log,stderr=log)
    evidence={'checks':[],'engine_approval':'UNVERIFIED','runtime':'real oa-app / dedicated PostgreSQL / signed HTTP'}
    try:
        lookup={'tenant_id':state['tenant'],'application_id':'commerce','environment':'test','request_id':str(uuid.uuid4()),'request_version':1,'snapshot_hash':'a'*64}
        deadline=time.monotonic()+55
        while time.monotonic()<deadline:
            if process.poll() is not None: raise RuntimeError('OA startup failed; private log retained')
            try:
                if call(state['key'],'/internal/iam-approval/v1/lookup',lookup)[0]==404: break
            except (OSError,http.client.HTTPException): pass
            time.sleep(1)
        else: raise RuntimeError('OA startup timeout')
        start={**lookup,'policy_id':str(uuid.uuid4()),'policy_version':1,'policy_hash':'b'*64,'membership_id':state['member'],'generation':1,
               'approver_membership_id':state['approver'],'approver_generation':1,'role_id':str(uuid.uuid4()),'capabilities':['commerce.store.read'],
               'scope_rule':{'version':1,'resource_type':'store','clauses':[{'kind':'TENANT_ALL','values':[],'include_root':False}]},
               'valid_from':'2026-09-29T00:00:00Z','valid_to':'2026-09-30T00:00:00Z','reason':'isolated P4 start test'}
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            responses=list(pool.map(lambda _:call(state['key'],'/internal/iam-approval/v1/start',start),range(3)))
        assert all(r[0]==200 for r in responses),[r[0] for r in responses]
        results=[json.loads(r[1]) for r in responses];assert all(r==results[0] for r in results)
        found=call(state['key'],'/internal/iam-approval/v1/lookup',lookup);assert found[0]==200 and json.loads(found[1])==results[0]
        evidence['checks'].append('three concurrent starts and result lookup return one persisted OA instance')
        assert call(state['key'],'/internal/iam-approval/v1/start',{**start,'reason':'changed'})[0]==409
        assert call(state['key'],'/internal/iam-approval/v1/start',start,responses[0][2])[0]==409
        assert call(state['key'],'/internal/iam-approval/v1/start',start,'forged')[0]==401
        assert call(state['key'],'/internal/iam-approval/v1/lookup',{**lookup,'environment':'prod'})[0]==403
        evidence['checks']+=['changed body conflict','transport replay rejected','forged signature rejected','foreign partition rejected']
        evidence['request_id']=lookup['request_id'];evidence['approval_instance_id']=results[0]['approval_instance_id'];evidence['result']='PASS'
        (directory/'result.json').write_text(json.dumps(evidence,indent=2)+'\n')
        print(json.dumps(evidence))
    finally:
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)
        log.close()


if __name__=='__main__':
    main()
