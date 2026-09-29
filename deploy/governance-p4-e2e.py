#!/usr/bin/env python3
"""P4真实Casdoor→auth→OA→Kafka/Flowable→签名回调→SpiceDB隔离验收。"""
import argparse
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
from http import HTTPStatus
import threading
import shutil
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.parse
import uuid
from datetime import datetime, timedelta, timezone

spec=importlib.util.spec_from_file_location('access_smoke',Path(__file__).with_name('governance-access-smoke.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a);h=a.h

SERVERS=[]
CALLBACKS={}

def callback_proxy():
    """真实转发已验签事件后丢首个ACK，模拟提交成功但响应未知。"""
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body=self.rfile.read(int(self.headers["Content-Length"]));event=json.loads(body)["event_id"]
            conn=http.client.HTTPConnection("127.0.0.1",18422,timeout=5)
            try:
                conn.request("POST",self.path,body,{"Content-Type":"application/json","X-Approval-Signature":self.headers["X-Approval-Signature"]})
                response=conn.getresponse();status=response.status;reply=response.read()
            finally:conn.close()
            rows=CALLBACKS.setdefault(event,[]);rows.append((hashlib.sha256(body).hexdigest(),self.headers["X-Approval-Signature"]))
            self.send_response(HTTPStatus.SERVICE_UNAVAILABLE if status==HTTPStatus.ACCEPTED and len(rows)==1 else status);self.end_headers();self.wfile.write(reply)
        def log_message(self,*args):pass
    server=HTTPServer(("127.0.0.1",18423),Handler);SERVERS.append(server);threading.Thread(target=server.serve_forever,daemon=True).start()

def uid(): return str(uuid.uuid4())
def properties(path): return dict(line.split('=',1) for line in h.read_private(path).splitlines() if '=' in line)
def timestamp(delta=0): return (datetime.now(timezone.utc)+timedelta(seconds=delta)).isoformat().replace('+00:00','Z')
def sql(db,statement):
    if not re.fullmatch('auth_gov_p1_test_[a-f0-9]{12}',db): raise RuntimeError('isolated database required')
    result=subprocess.run(['docker','exec','-i','auth-governance-p4-postgres-1','sh','-c','exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At','sh',db],input=statement,text=True,capture_output=True,timeout=20)
    if result.returncode: raise RuntimeError('isolated SQL fixture failed; SQL output suppressed')
    return result.stdout.strip()

def wait_for(name,condition,timeout=45):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        result=condition()
        if result:return result
        time.sleep(.5)
    raise RuntimeError('bounded wait failed: '+name)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--oa',required=True);parser.add_argument('--infra-env',required=True);parser.add_argument('--p5-playwright-module')
    args=parser.parse_args();root=Path('.local/governance').resolve();p4=root/'p4';run=p4/('e2e-'+secrets.token_hex(5));run.mkdir(mode=0o700)
    fixture=json.loads(h.read_private(root/'p2/identity/casdoor.json'));ops=json.loads(h.read_private(root/'casdoor-isolated/management-client.json'))
    authdb=h.read_private(p4/'runtime-auth-db/database.properties');oadb=properties(p4/'runtime-oa-db/database.properties');oa_dbname=oadb['jdbc.url'].rsplit('/',1)[1]
    jar=next(Path('auth-platform-admin/target').glob('auth-platform-admin-*.jar')).resolve()
    serverjar=next(Path('auth-platform-server/target').glob('auth-platform-server-*.jar')).resolve()
    shutil.copy2(jar,run/'admin.jar');shutil.copy2(serverjar,run/'server.jar');jar=run/'admin.jar';serverjar=run/'server.jar'
    def cli(name,params,allowed=(0,)):
        package='com.lrj.authz.admin.governance.' if name.endswith(('ProjectionCli','DecisionCli','StartCli','LifecycleCli','NotificationCli')) else 'com.lrj.authz.governance.cli.'
        with os.fdopen(os.open(run/(name+'-'+secrets.token_hex(4)+'.log'),os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as out:
            result=subprocess.run(['java','-Xmx256m','-Dloader.main='+package+name,'-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,params)],stdout=out,stderr=subprocess.STDOUT,timeout=40)
        if result.returncode not in allowed:raise RuntimeError('CLI failed: '+name)
        return result.returncode
    tenant=uid();app='p4-'+secrets.token_hex(5);environment='test';members={};principals={}
    for kind in ['internal']:
        principal=str(uuid.uuid5(uuid.NAMESPACE_URL,'p2:'+fixture['users'][kind]['id']));principals[kind]=principal;members[kind]=uid()
        values={'command.id':uid(),'operator.ref':'p4-fixture','tenant.id':tenant,'tenant.code':'p4-'+tenant,'principal.id':principal,'issuer':h.ISSUER,
            'subject':fixture['users'][kind]['id'],'membership.id':members[kind],'valid.from':'2020-01-01T00:00:00Z','source.system':'p4-fixture','source.tenant.ref':tenant,'source.subject.ref':kind}
        path=run/(kind+'.properties');h.private(path,h.props(values));cli('GovernanceCli',['bootstrap',p4/'runtime-auth-db/database.properties',path])
    cat={'catalog.application':app,'catalog.owner-principal':principals['internal'],'catalog.entry-origin':'http://127.0.0.1:18600','catalog.operator':'p4-fixture','catalog.command':uid(),
        'catalog.owner-issuer':h.ISSUER,'catalog.owner-subject':fixture['users']['internal']['id']}
    h.private(run/'catalog.properties',authdb+h.props(cat));cli('CatalogCli',['register',run/'catalog.properties','configured'])
    manifest={'schema_version':'1','application':app,'manifest_version':1,'capabilities':[{'code':app+'.read','resource_type':'store','risk_level':'NORMAL'},{'code':app+'.export','resource_type':'store','risk_level':'HIGH'}],'menus':[]}
    (run/'manifest.json').write_text(json.dumps(manifest));cli('CatalogCli',['publish',run/'catalog.properties',run/'manifest.json'])
    access={'access.tenant':tenant,'access.application':app,'access.environment':environment,'access.manager':members['internal'],'access.generation':1,'access.capabilities':app+'.read,'+app+'.export','access.max-duration-seconds':3600,'access.operator':'p4-fixture','access.command':uid()}
    outbound=secrets.token_urlsafe(36);inbound=secrets.token_urlsafe(36)
    graph=properties(root/'p3/graph/graph.properties')
    worker={**access,**graph,'approval.oa-url':'http://127.0.0.1:18420','approval.outbound-key':outbound}
    h.private(run/'worker.properties',authdb+h.props(worker));cli('AccessBootstrapCli',[run/'worker.properties'])
    for kind in ['POLICY','DIRECTORY']:h.private(run/(kind+'.properties'),authdb+h.props({**worker,'projection.kind':kind}))
    h.private(run/'broken-graph.properties',authdb+h.props({**worker,'projection.kind':'POLICY','graph.http':'http://127.0.0.1:1'}))
    mgmt=fixture['clients']['management'];business=fixture['clients']['business']
    authority={'issuer':h.ISSUER,'jwks.uri':h.ISSUER+'/.well-known/jwks','audience':mgmt['name'],'client.id':mgmt['name'],'client.secret':mgmt['secret'],
        'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
    callback={'approval.tenant':tenant,'approval.application':app,'approval.environment':environment,'approval.inbound-key':inbound,'approval.allow-loopback-http':'true'}
    h.private(run/'admin.properties',authdb+h.props({**authority,**callback,**{'invitation.user.'+k:v for k,v in authority.items()}}))
    admin=h.start(jar,18422,run/'admin.log',config=run/'admin.properties',access=True,requests=True,invitations=True)
    manager=[('Authorization','Bearer '+a.token(h.ISSUER,fixture,'management','internal'))]
    member=[('Authorization','Bearer '+a.token(h.ISSUER,fixture,'management','external'))]
    invitation=uid()
    h.private(run/'invitation-authority.properties',authdb+h.props({'invitation.operator-ref':'p4-fixture','invitation.tenant-id':tenant,'invitation.sponsor-membership-id':members['internal']}))
    h.private(run/'invitation-command.properties',h.props({'command.id':uid(),'invitation.id':invitation,'issuer':h.ISSUER,'subject':fixture['users']['external']['id'],'member.kind':'PARTNER','expires.at':timestamp(600),'membership.valid.to':timestamp(3600),'reason':'P4 external partner fixture'}))
    cli('InvitationCli',['issue',run/'invitation-authority.properties',run/'invitation-command.properties',run/'invitation-proof.properties'])
    proof=properties(run/'invitation-proof.properties')
    accepted=h.expect('real external identity accepts PARTNER invitation',18422,'/api/governance/v1/invitations/accept',member,json.dumps({'invitation_id':invitation,'token':proof['token']}).encode())
    members['external']=accepted['membership_id']
    part={'tenant_id':tenant,'application_id':app,'environment':environment};query=urllib.parse.urlencode(part)
    def req(name,path,body=None,headers=member,status=200):return h.expect(name,18422,'/api/governance/v1'+path,headers,None if body is None else json.dumps(body).encode(),status)
    req('enable strict partition','/access/enable-strict',{**part,'command_id':uid()},manager,202)
    role=req('fixed export role','/access/roles',{**part,'command_id':uid(),'role_code':'exporter','role_version':1,'capabilities':[app+'.export']},manager)
    scope={'version':1,'resource_type':'store','clauses':[{'kind':'SPECIFIED_STORES','values':['S001'],'include_root':False}]}
    policy=req('registered fixed approval policy','/requests/policies',{**part,'command_id':uid(),'role_id':role['id'],'scope_rule':scope,'max_duration_seconds':600,'approver_membership_id':members['internal'],'approver_generation':1,'policy_version':1},manager)
    service=secrets.token_urlsafe(48);serviceid='p4-probe'
    server={'service.count':1,'service.1.id':serviceid,'service.1.application-id':app,'service.1.environment':environment,'service.1.operation':'context.resolve',
        'service.1.credential-sha256':hashlib.sha256(service.encode()).hexdigest(),'access.check.callers':serviceid,'scope.check.callers':serviceid,'scope.owner.'+app:'store',
        'scope.graph.http':graph['graph.http'],'scope.graph.key':graph['graph.key'],**graph}
    user_authority={**authority,'audience':business['name'],'client.id':business['name'],'client.secret':business['secret']}
    server.update({'service.1.user.'+k:v for k,v in user_authority.items()});h.private(run/'server.properties',authdb+h.props(server))
    authserver=h.start(serverjar,18421,run/'server.log',config=run/'server.properties',access=True,scope=True)
    usertoken=a.token(h.ISSUER,fixture,'business','external');checkheaders=[('Authorization','Bearer '+service),('X-User-Access-Token',usertoken)]
    def check(name,cap='export',store='S001',status=200):
        body={'check':{'tenant_id':tenant,'expected_membership_generation':1,'request_id':uid(),'capability':app+'.'+cap,'resource_type':'store'},'facts':{'tenant_id':tenant,'resource_type':'store','resource_id':store,'resource_version':1,'owner_principal_id':None,'department_id':None,'department_ancestors':[],'store_id':store,'supplier_id':None}}
        return h.expect(name,18421,'/internal/governance/v1/access/check-resource',checkheaders,json.dumps(body).encode(),status)
    def project():
        for kind in ['POLICY','DIRECTORY']:cli('ReliableProjectionCli',[run/(kind+'.properties')])
    project();assert check('unapproved export denied')['decision']=='DENY'
    submitted=req('self request accepted','/requests',{**part,'command_id':uid(),'policy_id':policy['id'],'valid_from':timestamp(-2),'valid_to':timestamp(300),'reason':'P4 isolated external export'},status=202)
    rid=submitted['id'];execution=lambda:req('request execution','/requests/'+rid+'/execution?'+query)
    req('other member detail denied','/requests/'+rid+'?'+query,headers=manager,status=403)
    req('other member list has no applicant data','/requests?'+query,headers=manager)
    assert req('own list includes request','/requests?'+query)['items'][0]['id']==rid
    req('role version expansion','/access/roles',{**part,'command_id':uid(),'role_code':'exporter','role_version':2,'capabilities':[app+'.export',app+'.read']},manager)
    # 此时OA未运行：未知启动结果进入可靠重试；恢复后先按业务键查询。
    cli('ApprovalStartCli',[run/'worker.properties']);assert execution()['start_state']=='PENDING'
    infra=dict(line.split('=',1) for line in Path(args.infra_env).read_text().splitlines() if '=' in line and not line.startswith('#'))
    settings={'server.address':'127.0.0.1','server.port':'18420','spring.datasource.url':oadb['jdbc.url'],'spring.datasource.username':oadb['jdbc.username'],'spring.datasource.password':oadb['jdbc.password'],
        'spring.datasource.hikari.maximum-pool-size':'8','spring.data.redis.port':'46379','spring.data.redis.password':infra['REDIS7_PASSWORD'],'spring.kafka.bootstrap-servers':'127.0.0.1:19492',
        'oa.security.mode':'JWT','oa.security.issuer':h.ISSUER,'oa.security.jwk-set-uri':h.ISSUER+'/.well-known/jwks','oa.security.audience':mgmt['name'],'oa.iam.cache.l2-enabled':'false','oa.iam.outbox.enabled':'false',
        'oa.crypto.data-key':__import__('base64').b64encode(secrets.token_bytes(32)).decode(),'oa.flow.workflow.mode':'REMOTE','oa.flow.workflow.base-url':'http://127.0.0.1:18300','workflow.client.enabled':'true','workflow.client.base-url':'http://127.0.0.1:18300',
        'oa.flow.outbox.poll-ms':'500','oa.flow.todo.reconcile-ms':'1000','oa.flow.instance.reconcile-ms':'1000','oa.flow.central-approval.enabled':'true','oa.flow.central-approval.tenant-id':tenant,'oa.flow.central-approval.application-id':app,'oa.flow.central-approval.environment':environment,
        'oa.flow.central-approval.oa-tenant-id':'1','oa.flow.central-approval.inbound-key':outbound,'oa.flow.central-approval.allow-loopback-http':'true','oa.flow.central-approval.callback-enabled':'true',
        'oa.flow.central-approval.callback-url':'http://127.0.0.1:18423/internal/oa-approval/v1/events','oa.flow.central-approval.outbound-key':inbound,'oa.flow.central-approval.capture-ms':'500','oa.flow.central-approval.delivery-ms':'500'}
    if args.p5_playwright_module:settings['oa.security.allowed-origins[0]']='http://127.0.0.1:15276'
    for kind in ['internal','external']:
        prefix='oa.flow.central-approval.members.'+members[kind]+'.';settings.update({prefix+'generation':'1',prefix+'user-id':fixture['users'][kind]['id'],prefix+'org-id':'1',prefix+'org-path':'/1/'})
    h.private(run/'oa.properties',h.props(settings));oajar=Path(args.oa).resolve()/'oa-app/target/oa-app-0.1.0-SNAPSHOT.jar'
    def start_oa(log):
        with os.fdopen(os.open(run/log,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as out:
            p=subprocess.Popen(['java','-Xmx384m','-jar',str(oajar),'--spring.config.additional-location=file:'+str(run/'oa.properties')],stdout=out,stderr=subprocess.STDOUT)
        h.PROCESSES.append(p)
        def ready():
            if p.poll() is not None:raise RuntimeError('OA startup failed; private log retained')
            try:return h.request(18420,'/actuator/health')[0]==200
            except (OSError,ValueError):return False
        wait_for('OA ready',ready,60);return p
    callback_proxy();oa=start_oa('oa.log')
    with __import__('urllib.request',fromlist=['urlopen']).urlopen('http://127.0.0.1:18420/v3/api-docs',timeout=15) as response:
        (run/'oa-openapi.json').write_bytes(response.read(2_000_000))
    # 只给指定审批者既有待办两项权限；外部申请人没有OA员工/目录/工作台权限。
    who=fixture['users']['internal']['id'];uuid.UUID(who)
    rolecode='p4-'+secrets.token_hex(6)
    sql(oa_dbname,f"""INSERT INTO oa_iam.role(code,name,type,default_scope) VALUES('{rolecode}','P4审批夹具','CUSTOM','SELF');
INSERT INTO oa_iam.role_inherit SELECT id,id,0 FROM oa_iam.role WHERE code='{rolecode}';
INSERT INTO oa_iam.role_permission SELECT r.id,p.id FROM oa_iam.role r CROSS JOIN oa_iam.permission p WHERE r.code='{rolecode}' AND p.code IN ('oa:flow:todo:view','oa:flow:todo:handle');
INSERT INTO oa_iam.grant_record(subject_type,subject_id,role_id,scope_type,valid_to,reason) SELECT 'USER','{who}',id,'SELF',clock_timestamp()+interval '1 hour','P4 isolated approval' FROM oa_iam.role WHERE code='{rolecode}';""")
    cli('ApprovalStartCli',[run/'worker.properties']);instance=execution();assert instance['start_state']=='DONE'
    def todos():
        result=h.expect('actual OA approver todos',18420,'/api/v1/flow/todos',manager)
        return [t for t in result['data'] if str(t.get('instanceId'))==req('request binding','/requests/'+rid+'?'+query)['approval_instance_id']]
    todo=wait_for('real Flowable task projected into OA',todos,60)[0]
    h.expect('external cannot open OA workbench',18420,'/api/v1/flow/todos',member,status=403) # 校验OA真实HTTP权限边界。
    denied=h.request(18420,'/api/v1/flow/todos',member)[1];assert denied.get('code')!=0 and denied.get('code')!='00000' and denied.get('data') is None
    assert check('still cannot export during approval')['decision']=='DENY'
    task=todo['taskId']
    basis=h.expect('approver reads exact immutable approval basis',18420,'/api/v1/flow/central-access/tasks/'+task,manager)['data']
    assert basis['requestId']==rid and basis['snapshotHash']==submitted['snapshot_hash'] and basis['roleId']==role['id']
    h.expect('external cannot read approval basis',18420,'/api/v1/flow/central-access/tasks/'+task,member,status=403)
    body=json.dumps({'outcome':'APPROVE','comment':'P4 real approval','onBehalfOf':None}).encode()
    if args.p5_playwright_module:
        # P5复用此真实审批节点做UI验收；默认P4命令仍使用原HTTP检查。
        import socket
        env=dict(os.environ,OA_CONSOLE_PORT='15276',VITE_AUTH_ENABLED='true',VITE_API_TARGET='http://127.0.0.1:18420',
                 VITE_NOTIFY_TARGET='http://127.0.0.1:18420',VITE_CASDOOR_AUTHORITY=h.ISSUER,VITE_CASDOOR_CLIENT_ID=mgmt['name'],
                 P5_OA_FIXTURE=str(run),P5_PLAYWRIGHT_MODULE=str(Path(args.p5_playwright_module).resolve()))
        h.private(run/'oa-ui.json',json.dumps({'manager_token':manager[0][1].removeprefix('Bearer '),'member_token':member[0][1].removeprefix('Bearer '),
                  'request_id':rid,'snapshot_hash':submitted['snapshot_hash'],'task_id':task}))
        with socket.socket() as guard:guard.bind(('127.0.0.1',15276))
        with os.fdopen(os.open(run/'oa-vite.log',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as out:
            ui=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--strictPort'],cwd=Path(args.oa)/'oa-console',env=env,stdout=out,stderr=subprocess.STDOUT)
        h.PROCESSES.append(ui)
        def ui_ready():
            try:
                with socket.create_connection(('127.0.0.1',15276),timeout=1):return True
            except OSError:return False
        wait_for('OA console ready',ui_ready,30)
        subprocess.run(['node','deploy/governance-p5-oa-review.mjs'],env=env,check=True,timeout=90)
        h.stop(ui)
    else:
        completed=h.expect('assigned approver completes real task',18420,'/api/v1/flow/todos/'+task+'/complete',manager,body);assert completed.get('code') in (0,'00000','200',200)
    wait_for('trusted OA result received',lambda:execution().get('callback_status')=='RECEIVED',45)
    cli('ApprovalDecisionCli',[run/'worker.properties']);assert execution()['display_state']=='PENDING_APPLY'
    cli('ReliableProjectionCli',[run/'broken-graph.properties'],allowed=(2,));assert execution()['display_state']!='ACTIVE'
    check('graph failure denies export',status=503)
    # 图调用失败的退避由真实持久operation恢复，不改表伪造回执。
    wait_for('real graph recovery',lambda:cli('ReliableProjectionCli',[run/'POLICY.properties'],allowed=(0,2))==0,50)
    cli('ReliableProjectionCli',[run/'DIRECTORY.properties']);active=execution();assert active['display_state']=='ACTIVE' and active['operation_id']
    wait_for('fixed store graph read catches up',lambda:check('real completed approval allows fixed store export')['decision']=='ALLOW',10)
    assert check('other store remains denied',store='S002')['decision']=='DENY'
    assert check('expanded role capability remains denied',cap='read')['decision']=='DENY'
    cli('RequestNotificationCli',[run/'worker.properties']);assert req('site notification visible','/requests/notifications?'+query)['items']
    wait_for('same event reliably retried after lost callback ACK',lambda:any(len(rows)>=2 for rows in CALLBACKS.values()))
    assert all(len({row[0] for row in rows})==1 and len({row[1] for row in rows})==len(rows) for rows in CALLBACKS.values())
    h.CHECKS.append({'check':'lost callback ACK retries same business payload with fresh signature; only one Grant','result':'PASS'})
    assert len(req('one request one grant','/access/state?'+query,headers=manager)['grants'])==1
    # 第二条短窗口申请只允许S003；第一条S001不能掩盖它的到期拒绝。
    shortscope={**scope,'clauses':[{'kind':'SPECIFIED_STORES','values':['S003'],'include_root':False}]}
    shortpolicy=req('short fixed policy','/requests/policies',{**part,'command_id':uid(),'role_id':role['id'],'scope_rule':shortscope,'max_duration_seconds':60,'approver_membership_id':members['internal'],'approver_generation':1,'policy_version':1},manager)
    short=req('short lifetime request','/requests',{**part,'command_id':uid(),'policy_id':shortpolicy['id'],'valid_from':timestamp(-1),'valid_to':timestamp(25),'reason':'expiry without cleanup'},status=202)
    cli('ApprovalStartCli',[run/'worker.properties'])
    shortid=short['id']
    def short_todo():
        instance=req('short binding','/requests/'+shortid+'?'+query)['approval_instance_id']
        tasks=h.expect('short real todos',18420,'/api/v1/flow/todos',manager)['data']
        return [t for t in tasks if str(t.get('instanceId'))==instance]
    st=wait_for('short actual task',short_todo)[0]
    assert h.expect('short actual approval',18420,'/api/v1/flow/todos/'+st['taskId']+'/complete',manager,body)['code']==0
    wait_for('short callback',lambda:req('short execution','/requests/'+shortid+'/execution?'+query).get('callback_status')=='RECEIVED')
    cli('ApprovalDecisionCli',[run/'worker.properties']);project();wait_for('short graph read catches up',lambda:check('short grant initially active',store='S003')['decision']=='ALLOW',10)
    h.stop(oa);assert check('OA stopped existing authorization unaffected')['decision']=='ALLOW'
    end=datetime.fromisoformat(short['valid_to'].replace('Z','+00:00'))
    wait_for('fixed short window passes without lifecycle worker',lambda:datetime.now(timezone.utc)>=end,30)
    assert check('expired grant denied with OA and cleanup stopped',store='S003')['decision']=='DENY'
    cli('RequestLifecycleCli',[run/'worker.properties']);project()
    readrole=req('independent query role','/access/roles',{**part,'command_id':uid(),'role_code':'reader','role_version':1,'capabilities':[app+'.read']},manager)
    req('independent direct query source','/access/scoped-grants',{**part,'command_id':uid(),'member_id':members['external'],'member_generation':1,'role_id':readrole['id'],'scope_rule':scope,'source_id':'p4-independent','valid_from':timestamp(-1),'valid_to':timestamp(120)},manager,202)
    project();wait_for('independent query catches up',lambda:check('independent query active',cap='read')['decision']=='ALLOW',10)
    req('cancel approved request becomes source revoke','/requests/'+rid+'/cancel',{**part,'command_id':uid(),'state_version':submitted['state_version']},status=202)
    assert execution()['display_state']=='REVOKING';project();assert execution()['display_state']=='REVOKED'
    assert check('only request export source revoked')['decision']=='DENY'
    assert check('independent query remains allowed',cap='read')['decision']=='ALLOW'
    result={'result':'PASS','run':run.name,'request_id':rid,'approval_instance_id':req('final binding','/requests/'+rid+'?'+query)['approval_instance_id'],'grant_id':active['grant_id'],'operation_id':active['operation_id'],'revocation_operation_id':execution()['operation_id'],'short_request_id':shortid,'checks':h.CHECKS,'runtime':'real Casdoor JWT, auth HTTP/worker processes, OA HTTP, isolated Kafka/Flowable, PostgreSQL, SpiceDB'}
    (run/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');(p4/'latest-e2e.txt').write_text(str(run)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='checks'},ensure_ascii=False))

if __name__=='__main__':
    try:main()
    finally:
        for process in reversed(h.PROCESSES):h.stop(process)
        for server in SERVERS:server.shutdown();server.server_close()
