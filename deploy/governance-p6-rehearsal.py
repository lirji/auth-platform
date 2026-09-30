#!/usr/bin/env python3
"""P6所选商城租户隔离演练；只写本工具新建库，原commerce_local保持只读。"""
import argparse, base64, hashlib, http.client, importlib.util, json, os, secrets, shutil, socket, subprocess, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path


def module(name, path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


def rehearse(args, isolation=None):
    root=Path(__file__).resolve().parents[1];commerce=Path(args.commerce_root).resolve()
    migration=module('p6_snapshot',root/'deploy/governance-p6-migration.py')
    up=module('p6_access',root/'deploy/governance-access-smoke.py');h=up.h
    selected=json.loads((root/'docs/implementation/oa-auth/phase-6/P6_SELECTED_UNIT.json').read_text())
    snapshot,_=migration.read_json(root/selected['source_snapshot'],selected['source_sha256'])
    source=selected['source_tenant'];migration.identifier(source)
    suffix=secrets.token_hex(6);run=root/'.local/governance/p6'/('rehearsal-'+suffix);run.mkdir(mode=0o700)
    if isolation is None:
        subprocess.run(['python3',str(root/'deploy/governance-test-db.py'),'--directory',str(run/'database')],check=True,stdout=subprocess.DEVNULL)
        dbfile=run/'database/database.properties'
        fixture_path=root/'.local/governance/p2/identity/casdoor.json';management_path=root/'.local/governance/casdoor-isolated/management-client.json'
    else:
        dbfile=isolation.db['config'];h.ISSUER=isolation.issuer
        fixture_path=isolation.run/'identity/casdoor.json';management_path=isolation.management_config
        h.private(run/'identity-evidence.json',json.dumps({'mode':'DEDICATED_IDP_AND_PG','resources':str(isolation.run),'issuer':isolation.issuer}))
    db=h.read_private(dbfile)
    fixture=json.loads(h.read_private(fixture_path));ops=json.loads(h.read_private(management_path))
    graph=h.read_private(root/'.local/governance/p3/graph/graph.properties');legacy=h.read_private(root/'.local/governance/p2/graph/graph.properties')
    jars={k:root/f'auth-platform-{k}/target/auth-platform-{k}-0.1.0-SNAPSHOT.jar' for k in ('admin','server')}
    local_jar=run/'commerce.jar';shutil.copy2(commerce/'commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar',local_jar)
    tenant=str(uuid.uuid5(uuid.NAMESPACE_URL,'p6-isolated:'+source));env='local-p6';partition={'tenant_id':tenant,'application_id':'commerce','environment':env}
    checks=[];processes=[]
    def uid():return str(uuid.uuid4())
    def now(seconds=0):return (datetime.now(timezone.utc)+timedelta(seconds=seconds)).isoformat(timespec='milliseconds').replace('+00:00','Z')
    def record(name):
        checks.append({'check':name,'result':'PASS'});h.private(run/('checkpoint-%03d.json'%len(checks)),json.dumps(checks,ensure_ascii=False))
    def cli(name,*arguments,expected=0):
        package='com.lrj.authz.admin.governance.' if name=='ReliableProjectionCli' else 'com.lrj.authz.governance.cli.'
        file=run/(name+'-'+secrets.token_hex(4)+'.log')
        with os.fdopen(os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            result=subprocess.run(['java','-Dloader.main='+package+name,'-cp',str(jars['admin']),'org.springframework.boot.loader.launch.PropertiesLauncher',*map(str,arguments)],stdout=log,stderr=subprocess.STDOUT,timeout=45)
        if result.returncode!=expected:raise RuntimeError('CLI '+name+' unexpected exit '+str(result.returncode)+' private log '+file.name)
    def request(port,path,headers=(),body=None):
        c=http.client.HTTPConnection('127.0.0.1',port,timeout=35)
        try:
            data=None if body is None else json.dumps(body).encode();hs=dict(headers)
            if data is not None:hs['Content-Type']='application/json'
            c.request('GET' if data is None else 'POST',path,data,hs);r=c.getresponse();raw=r.read(1048577)
            if len(raw)>1048576:raise RuntimeError('response too large')
            return r.status,json.loads(raw) if raw else {}
        finally:c.close()
    def expect(name,port,path,headers=(),body=None,status=200):
        actual,value=request(port,path,headers,body)
        if actual!=status:raise RuntimeError(name+': expected '+str(status)+' got '+str(actual)+' code='+str(value.get('code') if isinstance(value,dict) else 'array'))
        record(name);return value
    sql_cmd=['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names']
    database='commerce_iam_p6_'+suffix
    def sql(statement,allow_failure=False):
        # 库名仅由工具随机生成；这里没有指向原运行商城的写入口。
        result=subprocess.run(sql_cmd,input='USE '+database+';\n'+statement,text=True,capture_output=True,timeout=20)
        if result.returncode and not allow_failure:raise RuntimeError('owned MySQL operation failed')
        return result.returncode,result.stdout.strip()
    def q(value):
        if value is None:return 'NULL'
        if type(value) in (int,bool):return str(int(value))
        return "CONVERT(0x"+str(value).encode().hex()+" USING utf8mb4)"
    def insert(table,row):
        code,_=sql('INSERT INTO '+table+'('+','.join(row)+') VALUES('+','.join(q(v) for v in row.values())+');');return code
    dbuser='iam_p6_'+suffix;password=secrets.token_hex(24)
    create=subprocess.run(sql_cmd,input=f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;CREATE USER '{dbuser}'@'%' IDENTIFIED BY '{password}';GRANT ALL ON {database}.* TO '{dbuser}'@'%';",text=True,capture_output=True,timeout=20)
    if create.returncode:raise RuntimeError('owned MySQL create failed')
    inspect=json.loads(subprocess.check_output(['docker','inspect','dev-infra-mysql84-1'],text=True))[0]
    owner_password=next(v.split('=',1)[1] for v in inspect['Config']['Env'] if v.startswith('MYSQL_ROOT_PASSWORD='))
    runtime=dict(os.environ,COMMERCE_DB_URL=f'jdbc:mysql://127.0.0.1:43306/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',COMMERCE_DB_USER=dbuser,COMMERCE_DB_PASSWORD=password,COMMERCE_ADDRESS_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),COMMERCE_PORT='18661',COMMERCE_WORKERS_ENABLED='false',COMMERCE_SANDBOX_ENABLED='false')
    h.private(run/'commerce-environment.json',json.dumps({k:v for k,v in runtime.items() if k.startswith('COMMERCE_')}))
    def start_commerce(label,migrate=False):
        with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',18661))
        process_env=dict(runtime)
        if migrate:process_env.update(SPRING_FLYWAY_USER='root',SPRING_FLYWAY_PASSWORD=owner_password)
        with os.fdopen(os.open(run/(label+'.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as log:
            p=subprocess.Popen(['java','-Xmx512m','-jar',str(local_jar),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled=true','--commerce.iam.catalog.enabled=true','--commerce.iam.store-read.configuration='+str(run/'consumer.properties')],env=process_env,stdout=log,stderr=subprocess.STDOUT)
        processes.append(p)
        for _ in range(90):
            if p.poll() is not None:raise RuntimeError('commerce startup failed: '+label)
            try:
                if request(18661,'/actuator/health')[0]==200:return p
            except OSError:pass
            time.sleep(.25)
        raise RuntimeError('commerce startup timeout')
    try:
        members={};principals={}
        for kind in ('internal','external'):
            principals[kind]=str(uuid.uuid5(uuid.NAMESPACE_URL,'p6:'+fixture['users'][kind]['id']));members[kind]=uid()
            values={'command.id':uid(),'operator.ref':'p6-isolated-fixture','tenant.id':tenant,'tenant.code':'p6-'+tenant,'principal.id':principals[kind],'issuer':h.ISSUER,'subject':fixture['users'][kind]['id'],'membership.id':members[kind],'valid.from':'2020-01-01T00:00:00Z','source.system':'p6-isolated-fixture','source.tenant.ref':source,'source.subject.ref':kind}
            file=run/(kind+'.properties');h.private(file,h.props(values));cli('GovernanceCli','bootstrap',dbfile,file)
        catalog={'catalog.application':'commerce','catalog.owner-principal':principals['internal'],'catalog.entry-origin':'http://127.0.0.1:18661','catalog.operator':'p6-fixture','catalog.command':uid(),'catalog.owner-issuer':h.ISSUER,'catalog.owner-subject':fixture['users']['internal']['id']}
        h.private(run/'catalog.properties',db+h.props(catalog));cli('CatalogCli','register',run/'catalog.properties','configured')
        manifest={'schema_version':'1','application':'commerce','manifest_version':1,'capabilities':[{'code':'commerce.catalog.operate','resource_type':'store','risk_level':'HIGH'}],'menus':[]}
        h.private(run/'manifest.json',json.dumps(manifest));cli('CatalogCli','publish',run/'catalog.properties',run/'manifest.json')
        access={'access.tenant':tenant,'access.application':'commerce','access.environment':env,'access.manager':members['internal'],'access.generation':1,'access.capabilities':'commerce.catalog.operate','access.max-duration-seconds':3600,'access.operator':'p6-fixture','access.command':uid()}
        h.private(run/'access.properties',db+h.props(access));cli('AccessBootstrapCli',run/'access.properties')
        def authority(kind):
            c=fixture['clients'][kind];return {'issuer':h.ISSUER,'jwks.uri':h.ISSUER+'/.well-known/jwks','audience':c['name'],'client.id':c['name'],'client.secret':c['secret'],'version-probe.client.id':ops['client_id'],'version-probe.client.secret':ops['client_secret']}
        graph_settings=''.join('scope.'+line+'\n' for line in graph.splitlines() if line.startswith('graph.'))
        h.private(run/'admin.properties',db+legacy+graph_settings+h.props(authority('management')))
        service=secrets.token_urlsafe(48);server={'service.count':1,'service.1.id':'commerce-p6','service.1.application-id':'commerce','service.1.environment':env,'service.1.operation':'context.resolve','service.1.credential-sha256':hashlib.sha256(service.encode()).hexdigest(),'access.check.callers':'commerce-p6','scope.check.callers':'commerce-p6','execution.callers':'commerce-p6','scope.owner.commerce':'store,product'}
        server.update({'service.1.user.'+k:v for k,v in authority('business').items()});h.private(run/'server.properties',db+legacy+graph_settings+h.props(server))
        h.private(run/'consumer.properties',h.props({'central.url':'http://127.0.0.1:18161','central.credential':service,'central.application':'commerce','central.environment':env}))
        admin_token=up.token(h.ISSUER,fixture,'management','internal');user_token=up.token(h.ISSUER,fixture,'business','external')
        admin=[('Authorization','Bearer '+admin_token)];user=[('Authorization','Bearer '+user_token),('X-Tenant-Id',tenant)];dual=[('Authorization','Bearer '+service),('X-User-Access-Token',user_token)]
        h.private(run/'tokens.json',json.dumps({'admin':admin_token,'user':user_token}))
        h.start(jars['admin'],18162,run/'admin.log',config=run/'admin.properties',access=True,scope=True)
        auth=h.start(jars['server'],18161,run/'server.log',config=run/'server.properties',access=True,scope=True,executions=True)
        prefix='/api/governance/v1/access'
        expect('enable strict partition',18162,prefix+'/enable-strict',admin,{**partition,'command_id':uid(),'kind':None},202)
        role=expect('explicit full CATALOG role',18162,prefix+'/roles',admin,{**partition,'command_id':uid(),'role_code':'p6-catalog','role_version':1,'capabilities':['commerce.catalog.operate']})['id']
        app=start_commerce('commerce-migration-owner',True);h.stop(app);app=start_commerce('commerce-application-user')
        record('migrate with owner then restart without owner credential')
        rows={t:[r for r in values if r['tenant_id']==source] for t,values in snapshot['tables'].items()}
        for table in ('merchant_record','store_record'):
            for row in rows[table]:insert(table,{**row,'name':'P6 source snapshot'})
        source_tokens={}
        for row in rows['platform_credential']:
            old_token=secrets.token_urlsafe(48);source_tokens[row['actor_id']]=old_token
            insert('platform_credential',{**row,'token_hash':hashlib.sha256(old_token.encode()).hexdigest()})
        for row in rows['store_operator_grant']:insert('store_operator_grant',{**row,'reason':'P6 readonly snapshot clone'})
        store=rows['store_operator_grant'][0]['resource_id'];other='p6-outside';merchant=rows['store_record'][0]['merchant_id']
        insert('store_record',{'tenant_id':source,'store_id':other,'merchant_id':merchant,'name':'P6 outside proof'})
        legacy_token=secrets.token_urlsafe(48);admin_local=secrets.token_urlsafe(48)
        for actor,role_name,token in [('p6-proof-operator','OPERATOR',legacy_token),('p6-proof-admin','ADMIN',admin_local)]:
            insert('platform_credential',{'tenant_id':source,'actor_id':actor,'role':role_name,'token_hash':hashlib.sha256(token.encode()).hexdigest(),'expires_at':now(3600).replace('T',' ').replace('Z','')})
        insert('store_operator_grant',{'tenant_id':source,'grant_id':'p6-proof-grant','actor_id':'p6-proof-operator','resource_type':'STORE','resource_id':store,'permission':'CATALOG','reason':'separate synthetic positive proof'})
        insert('central_store_identity_binding',{'auth_tenant_id':tenant,'principal_id':principals['external'],'membership_id':members['external'],'generation':1,'tenant_id':source,'actor_id':'p6-proof-operator','created_by':'p6-isolated-proof'})
        insert('catalog_authority_route',{'tenant_id':source,'auth_tenant_id':tenant,'state':'SHADOW'})
        mapping={'format':'p6-commerce-mapping/v1','source_sha256':selected['source_sha256'],'source_tenant':source,'target':partition,'evaluation_at':now(),'actors':{r['actor_id']:{'principal_id':principals['internal'],'membership_id':members['internal'],'generation':1,'identity_evidence':'isolated synthetic principal for preserved-denial ledger; not a production human assertion'} for r in rows['store_operator_grant']},'permissions':{'CATALOG':{'capabilities':['commerce.catalog.operate'],'approved_by':'user-option-2','decision_ref':'CONTRACTS_P6_MIGRATION.md full CATALOG'}}}
        report=migration.dry_run(snapshot,selected['source_sha256'],mapping)
        if report['counts']!={'QUARANTINED_MAPPING':0,'PRESERVE_DENIAL':len(rows['store_operator_grant']),'IMPORT_CANDIDATE':0}:raise RuntimeError('actual source denial did not remain denied')
        h.private(run/'mapping.json',json.dumps(mapping));h.private(run/'dry-run.json',json.dumps(report));record('real snapshot mapped without renewing expired credentials')
        h.private(run/'import.properties',db+h.props({'migration.tenant':tenant,'migration.environment':env,'migration.issuer':h.ISSUER,'migration.subject':fixture['users']['internal']['id']}))
        scope={'version':1,'resource_type':'store','clauses':[{'kind':'SPECIFIED_STORES','values':[store],'include_root':False}]}
        items=[{'source_id':x['source_record'],'source_version':x['source_version'],'member_id':members['internal'],'generation':1,'role_id':role,'scope':x['scope_rule'],'valid_from':None,'valid_to':None,'deny':True,'reason':','.join(x['deny_constraints'])} for x in report['records']]
        items.append({'source_id':'synthetic-positive','source_version':0,'member_id':members['external'],'generation':1,'role_id':role,'scope':scope,'valid_from':now(-2),'valid_to':now(3000),'deny':False,'reason':'explicit finite synthetic rehearsal only'})
        unit='commerce-p6-'+hashlib.sha256(source.encode()).hexdigest()[:16]
        batch={'unit_id':unit,'sequence':1,'snapshot_hash':selected['source_sha256'],'mapping_hash':migration.digest(mapping),'partition':partition,'items':items}
        h.private(run/'batch-1.json',json.dumps(batch));cli('MigrationImportCli',run/'import.properties',run/'batch-1.json');cli('MigrationImportCli',run/'import.properties',run/'batch-1.json');record('real denial and separate finite proof imported idempotently')
        def projection():
            for kind in ('POLICY','DIRECTORY'):
                file=run/('projection-'+secrets.token_hex(4)+'.properties');h.private(file,db+graph+h.props({**access,'projection.kind':kind}));cli('ReliableProjectionCli',file)
        projection()
        def central_check(s):return {'check':{'tenant_id':tenant,'expected_membership_generation':1,'request_id':uid(),'capability':'commerce.catalog.operate','resource_type':'store'},'facts':{'tenant_id':tenant,'resource_type':'store','resource_id':s,'resource_version':0,'owner_principal_id':None,'department_id':None,'department_ancestors':[],'store_id':s,'supplier_id':None}}
        shadow=[]
        for s,expected in ((store,True),(other,False)):
            old,_=request(18661,'/v1/operations/products?storeId='+s,[('Authorization','Bearer '+legacy_token)])
            new,value=request(18161,'/internal/governance/v1/access/check-resource',dual,central_check(s))
            row={'store':s,'legacy_decision':'ALLOW' if old==200 else 'DENY' if old==403 else 'ERROR','central_decision':value.get('decision') if new==200 else 'ERROR'};shadow.append(row)
        if not migration.compare_shadow(shadow)['ready']:raise RuntimeError('shadow discrepancy blocks cutover')
        for discrepancy in ({'legacy_decision':'ALLOW','central_decision':'DENY'},{'legacy_decision':'DENY','central_decision':'ALLOW'},{'legacy_decision':'ERROR','central_decision':'ERROR'}):
            if migration.compare_shadow([discrepancy])['ready']:raise RuntimeError('shadow invalid comparison accepted')
        record('shadow inverse differences and dependency failures block cutover')
        h.private(run/'shadow.json',json.dumps(shadow));record('shadow positive and negative comparison; no OR decision')
        # 先冻结、再确认不可写；中央切换只发生在本工具新库。
        sql('UPDATE catalog_authority_route SET frozen=TRUE,version=version+1 WHERE tenant_id='+q(source)+" AND state='SHADOW' AND version=1;")
        code,_=sql('UPDATE store_operator_grant SET active=FALSE,version=version+1 WHERE tenant_id='+q(source)+';',True)
        if code==0:raise RuntimeError('legacy direct SQL was not frozen')
        record('old grant direct SQL frozen before authority cutover')
        sql("UPDATE catalog_authority_route SET state='CENTRAL',ever_central=TRUE,version=version+1 WHERE tenant_id="+q(source)+" AND state='SHADOW' AND frozen=TRUE AND version=2;")
        if sql('SELECT state FROM catalog_authority_route WHERE tenant_id='+q(source))[1]!='CENTRAL':raise RuntimeError('cutover CAS failed')
        record('single authority CAS switched only isolated selected tenant')
        for original in rows['store_operator_grant']:
            expect('original expired source identity remains denied',18661,'/v1/operations/products?storeId='+original['resource_id'],[('Authorization','Bearer '+source_tokens[original['actor_id']])],status=401)
        expect('old admin cannot bypass central guard',18661,'/v1/operations/products?storeId='+store,[('Authorization','Bearer '+admin_local)],status=403)
        expect('central cross-store denied',18661,'/v1/operations/products?storeId='+other,user,status=403)
        def browser(phase):
            if not args.browser:return
            subprocess.run(['node',str(root/'deploy/governance-p6-catalog.mjs')],env=dict(os.environ,P6_RUN=str(run),P6_PHASE=phase,P6_PLAYWRIGHT_MODULE=str(commerce/'frontend/node_modules/@playwright/test')),check=True,timeout=120)
            record('real central catalog browser '+phase)
        if args.browser:
            h.private(run/'catalog-ui.json',json.dumps({'token':user_token,'authority':h.ISSUER,'client':fixture['clients']['business']['name'],'tenant':tenant,'store':store,'other':other}))
            pilot_class=module('p6_browser_start',root/'deploy/governance-p5-commerce.py').CommercePilot
            pilot=pilot_class.__new__(pilot_class)
            pilot.run,pilot.h=run,h
            pilot.start(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','18665','--strictPort'],commerce/'frontend','catalog-vite',18665,dict(os.environ,VITE_IAM_ENABLED='true',VITE_IAM_AUTHORITY=h.ISSUER,VITE_IAM_CLIENT_ID=fixture['clients']['business']['name'],COMMERCE_API_URL='http://127.0.0.1:18661'))
        def post(name,path,body,key=None):return expect(name,18661,'/v1/operations/'+path,user+[('Idempotency-Key',key or uid())],body)
        post('central create product','products',{'productId':'p6-product','storeId':store,'title':'P6 product','category':'C','brand':'B'})
        sku=post('central create sku','skus',{'skuId':'p6-sku','productId':'p6-product','storeId':store,'title':'P6 sku','unitPrice':'10.00','specifications':[{'name':'size','value':'S'}]})
        sku=post('central publish and price','skus/p6-sku',{'storeId':store,'expectedVersion':sku['revision'],'title':'P6 sku','unitPrice':'11.00','status':'ACTIVE','reason':'P6 publish'})
        post('central channel price','skus/p6-sku/channel-prices',{'storeId':store,'channel':'MINI_APP','expectedVersion':0,'unitPrice':'9.00','validFrom':now(-2),'validTo':now(600),'active':True,'reason':'P6 price'})
        post('central category','catalog-categories',{'categoryId':'p6-category','storeId':store,'parentId':None,'name':'P6 category'})
        post('central specification template','specification-templates',{'templateId':'p6-template','version':1,'storeId':store,'name':'P6 template','fields':[{'name':'size','values':['S','M']}]})
        post('central merchandising','products/p6-product/merchandising',{'storeId':store,'expectedVersion':0,'categoryId':'p6-category','templateId':None,'templateVersion':None,'description':'P6 details','images':[],'reason':'P6 metadata'})
        post('central barcode','skus/p6-sku/barcode',{'storeId':store,'expectedVersion':0,'barcode':'12345678','reason':'P6 barcode'})
        browser('active')
        def job(name,price,revision):return post(name,'catalog-jobs',{'jobId':uid(),'storeId':store,'name':name,'action':'PRICE','runAt':None,'deadline':now(600),'targets':[{'skuId':'p6-sku','expectedRevision':revision,'unitPrice':price}],'reason':'P6 durable proof'})
        queued=job('queued process recovery','12.00',sku['revision']);h.stop(app);app=start_commerce('commerce-resumed')
        expect('background reference survives process restart',18661,'/v1/operations/catalog-jobs/pump?storeId='+store,user,{})
        current=expect('read committed task effect',18661,'/v1/operations/skus?storeId='+store,user)[0]
        if current['unitPrice']!='12.00':raise RuntimeError('task did not commit expected price')
        revoked_job=job('queued before source revoke','20.00',current['revision'])
        delta={**batch,'sequence':2,'snapshot_hash':hashlib.sha256(b'p6-explicit-synthetic-revoke').hexdigest(),'items':[{**items[-1],'source_version':1,'deny':True,'reason':'synthetic source revoke'}]}
        h.private(run/'batch-2.json',json.dumps(delta));cli('MigrationImportCli',run/'import.properties',run/'batch-2.json');cli('MigrationImportCli',run/'import.properties',run/'batch-2.json');projection()
        h.stop(app);runtime['COMMERCE_WORKERS_ENABLED']='true';app=start_commerce('commerce-revoked-background')
        deadline=time.monotonic()+12
        revoked_id=revoked_job['definition']['jobId']
        while time.monotonic()<deadline:
            value=sql('SELECT cursor_index,attempts FROM catalog_operation_job WHERE tenant_id='+q(source)+' AND job_id='+q(revoked_id))[1].split('\t')
            if len(value)==2 and int(value[1])>0:
                if int(value[0])!=0:raise RuntimeError('revoked task advanced')
                record('real background worker stops revoked execution reference');break
            time.sleep(.25)
        else:raise RuntimeError('background revocation was not exercised')
        expect('central denies after imported revocation' ,18661,'/v1/operations/products?storeId='+store,user,status=403)
        browser('revoked')
        cli('MigrationImportCli',run/'import.properties',run/'batch-1.json',expected=2);record('old snapshot cannot resurrect newer tombstone')
        # 安全回退只停止，不把中央撤权后仍为true的旧授权重新变成权威。
        sql("UPDATE catalog_authority_route SET state='STOPPED',version=version+1 WHERE tenant_id="+q(source)+" AND state='CENTRAL';")
        expect('safe rollback blocks old admin',18661,'/v1/operations/products?storeId='+store,[('Authorization','Bearer '+admin_local)],status=403)
        code,_=sql("UPDATE catalog_authority_route SET state='LEGACY',frozen=FALSE,ever_central=FALSE,version=version+1 WHERE tenant_id="+q(source),True)
        if code==0:raise RuntimeError('unsafe rollback accepted')
        record('database rejects rollback that discards central denials')
        sql("UPDATE catalog_authority_route SET state='CENTRAL',version=version+1 WHERE tenant_id="+q(source)+" AND state='STOPPED';")
        expect('resumed central retains denial',18661,'/v1/operations/products?storeId='+store,user,status=403)
        h.stop(auth);expect('central outage does not use legacy allow',18661,'/v1/operations/products?storeId='+store,user,status=503)
        browser('outage')
        if sql('SELECT active FROM store_operator_grant WHERE tenant_id='+q(source)+" AND grant_id='p6-proof-grant'")[1]!='1':raise RuntimeError('legacy fixture unexpectedly changed')
        if sql('SELECT unit_price FROM catalog_sku WHERE tenant_id='+q(source)+" AND sku_id='p6-sku'")[1] not in ('12.00','12.0000'):raise RuntimeError('revoked task modified product')
        result={'result':'PASS','scope':'isolated local rehearsal only','source_tenant':source,'source_sha256':selected['source_sha256'],'database':database,'auth_config':str(dbfile),'run':str(run),'checks':checks,'real_source_records':len(items)-1,'synthetic_positive_records':1,'shadow':shadow,'runtime_switched':False,'production_ready':False,'identity_mode':'DEDICATED_IDP_AND_PG' if isolation else 'EXISTING_IDP_SHARED_PG','commerce_jar_sha256':hashlib.sha256(local_jar.read_bytes()).hexdigest()}
        h.private(run/'result.json',json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({'result':'PASS','checks':len(checks),'evidence':str(run/'result.json')}))
    finally:
        for p in processes+h.PROCESSES:h.stop(p)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--commerce-root',default='../commerce-platform')
    parser.add_argument('--browser',action='store_true')
    parser.add_argument('--isolated-identity',action='store_true')
    args=parser.parse_args()
    isolation=None
    try:
        if args.isolated_identity:
            # 复用P7已验证的自有资源生命周期；这里只准备身份/数据库，不运行容量或恢复测试。
            root=Path(__file__).resolve().parents[1]
            isolation=module('p6_identity_runtime',root/'deploy/governance-p7-rehearsal.py').Rehearsal(1,True,True)
            isolation.prepare_database();isolation.prepare_identity()
            subprocess.run(['python3',str(root/'deploy/governance-casdoor-fixture.py'),'--base',isolation.issuer,'--phase','tokens','--directory',str(isolation.run/'identity'),'--management-config',str(isolation.management_config)],check=True,stdout=subprocess.DEVNULL)
        rehearse(args,isolation)
    finally:
        if isolation:isolation.close()


if __name__=='__main__':
    try:main()
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as error:
        raise SystemExit('P6 rehearsal stopped: '+str(error))
