"""P5商城真实试点扩展；只持有新建测试数据库和本次回环进程，不修改业务库或共享客户端。"""
import base64, hashlib, json, os, secrets, shutil, socket, subprocess, time
from pathlib import Path

class CommercePilot:
    def __init__(self,run,commerce,h,fixture,tenant,management_token):
        self.run,self.commerce,self.h,self.fixture=run,Path(commerce).resolve(),h,fixture
        self.data={'tenant':tenant,'management_token':management_token,'authority':h.ISSUER,'management_client':fixture['clients']['management']['name'],'business_client':fixture['clients']['business']['name']}
        self.env=dict(os.environ,AUTH_CONSOLE_UI_PORT='15275',VITE_GOVERNANCE_TARGET='http://127.0.0.1:18422',VITE_CASDOOR_AUTHORITY=h.ISSUER,VITE_CASDOOR_CLIENT_ID=fixture['clients']['management']['name'],P5_COMMERCE_RUN=str(run),P5_PLAYWRIGHT_MODULE=str(self.commerce/'frontend/node_modules/@playwright/test'))
        self.start(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--strictPort'],Path('auth-console'),'portal-vite',15275,self.env)
    def start(self,command,cwd,label,port,env):
        with socket.socket() as guard:
            # 仅重用已退出进程留下的TIME_WAIT；不启用SO_REUSEPORT，活动监听者仍会阻止启动。
            guard.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            guard.bind(('127.0.0.1',port))
        with os.fdopen(os.open(self.run/(label+'.log'),os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as log:
            process=subprocess.Popen(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT)
        self.h.PROCESSES.append(process)
        for _ in range(120):
            if process.poll() is not None:raise RuntimeError(label+' startup failed')
            try:
                with socket.create_connection(('127.0.0.1',port),timeout=1):return
            except OSError:time.sleep(.5)
        raise RuntimeError(label+' startup timeout')
    def browser(self,phase,**data):
        self.data.update(data);self.write_data()
        subprocess.run(['node','deploy/governance-p5-commerce.mjs'],env=dict(self.env,P5_COMMERCE_PHASE=phase),check=True,timeout=150)
        self.h.CHECKS.append({'check':'real commerce browser '+phase,'result':'PASS'})
    def write_data(self):
        temporary=self.run/('commerce-ui-'+secrets.token_hex(5)+'.json')
        self.h.private(temporary,json.dumps(self.data));os.replace(temporary,self.run/'commerce-ui.json')
    def invitation(self,invitation,proof):
        self.browser('invitation',invitation=invitation,proof=proof)
        self.data.pop('proof');self.data.pop('invitation');self.write_data()
        return json.loads(self.h.read_private(self.run/'accepted.json'))
    def business(self,service,principal,member,token):
        h=self.h;self.data['business_token']=token
        suffix=secrets.token_hex(6);database='commerce_iam_p5_'+suffix;dbuser='iam_p5_'+suffix;password=secrets.token_hex(24);local='p5-'+suffix
        sql_command=['docker','exec','-i','dev-infra-mysql84-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names']
        def sql(statement):
            result=subprocess.run(sql_command,input=statement,text=True,capture_output=True,timeout=30)
            if result.returncode:raise RuntimeError('owned P5 MySQL operation failed; output suppressed')
        sql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE USER '{dbuser}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {database}.* TO '{dbuser}'@'%';")
        env=dict(os.environ,COMMERCE_DB_URL=f'jdbc:mysql://127.0.0.1:43306/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',COMMERCE_DB_USER=dbuser,COMMERCE_DB_PASSWORD=password,COMMERCE_ADDRESS_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),COMMERCE_PORT='18603',COMMERCE_WORKERS_ENABLED='false',COMMERCE_SANDBOX_ENABLED='false')
        h.private(self.run/'commerce-environment.json',json.dumps({k:v for k,v in env.items() if k.startswith('COMMERCE_')}))
        h.private(self.run/'consumer.properties',h.props({'central.url':'http://127.0.0.1:18421','central.credential':service,'central.application':'commerce','central.environment':'test'}))
        jar=self.run/'commerce.jar';shutil.copy2(self.commerce/'commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar',jar)
        self.start(['java','-Xmx384m','-jar',str(jar),'--server.address=127.0.0.1','--commerce.iam.store-read.enabled=true','--commerce.iam.scope.enabled=true','--commerce.iam.store-read.configuration='+str(self.run/'consumer.properties')],self.commerce,'commerce',18603,env)
        for _ in range(60):
            try:
                if h.request(18603,'/actuator/health')[0]==200:break
            except (OSError,ValueError):pass
            time.sleep(.5)
        else:raise RuntimeError('commerce health timeout')
        # 受控测试fixture写资源Owner自己的库；正式业务没有跨库查询。
        sql(f"""USE {database};
INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES('{hashlib.sha256(secrets.token_bytes(48)).hexdigest()}','{local}','partner','OPERATOR',UTC_TIMESTAMP()+INTERVAL 1 HOUR);
INSERT INTO central_store_identity_binding(auth_tenant_id,principal_id,membership_id,generation,tenant_id,actor_id,created_by) VALUES('{self.data['tenant']}','{principal}','{member}',1,'{local}','partner','p5-isolated');
INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES('{local}','M','试点商家'),('foreign-{suffix}','M','其他商家');
INSERT INTO store_record(tenant_id,store_id,merchant_id,name) VALUES('{local}','S001','M','授权协作门店'),('{local}','S002','M','其他门店'),('{local}','S003','M','到期场景门店'),('foreign-{suffix}','FOREIGN','M','其他企业门店');
INSERT INTO catalog_product(tenant_id,product_id,store_id,title,category,brand) VALUES('{local}','P001','S001','协作门店商品','日用','试点品牌'),('{local}','P002','S002','其他门店保密商品','日用','其他品牌'),('{local}','P003','S003','Expiry product','日用','试点品牌'),('foreign-{suffix}','FOREIGN','FOREIGN','其他企业商品','日用','其他品牌');""")
        self.env.update(VITE_IAM_ENABLED='true',VITE_IAM_AUTHORITY=h.ISSUER,VITE_IAM_CLIENT_ID=self.fixture['clients']['business']['name'],VITE_IAM_PORTAL_URL='http://127.0.0.1:15275',VITE_IAM_ENVIRONMENT='test',COMMERCE_API_URL='http://127.0.0.1:18603')
        self.start(['npm','run','dev','--','--port','18605'],self.commerce/'frontend','commerce-vite',18605,self.env)
    def submitted(self):
        self.browser('request')
        result=json.loads(self.h.read_private(self.run/'submitted.json'));self.data['request_id']=result['id'];return result
