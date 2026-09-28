#!/usr/bin/env python3
"""复用已安装SpiceDB版本和dev_infra PG，创建可恢复的P2专属持久图。"""
import argparse
import re
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request
import urllib.error

IMAGE='authzed/spicedb@sha256:aa96009a0477f8a759149823407d47ad16d1a74390bae3102b3b0b7143502764'
ROOT=Path('.local/governance/p2/graph').resolve()
NAME='auth-governance-p2-graph'
PORT=18543

def schema_shape(value):
    # 服务端规范化会重排definition；比较所有定义的完整token，拒绝额外内容。
    clean=re.sub(r'//[^\n]*','',value)
    pattern=r'definition\s+(\w+)\s*\{([^{}]*)\}'
    if re.sub(pattern,'',clean).strip(): raise RuntimeError('unsupported schema content')
    return sorted((name,re.findall(r'\w+|[^\s]',body)) for name,body in re.findall(pattern,clean))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--postgres-container',default='dev-infra-postgres16-1')
    parser.add_argument('--port',type=int,default=45432)
    parser.add_argument('--postgres-host',default='host.docker.internal')
    parser.add_argument('--network')
    args=parser.parse_args()
    network=['--network',args.network] if args.network else []
    ROOT.mkdir(parents=True,exist_ok=True,mode=0o700)
    subprocess.run(['python3','deploy/governance-test-db.py','--directory',str(ROOT),'--container',args.postgres_container,'--port',str(args.port)],check=True,stdout=subprocess.DEVNULL)
    spec=json.loads((ROOT/'database.json').read_text())
    env=ROOT/'graph.env'
    if not env.exists():
        key=secrets.token_urlsafe(48)
        with os.fdopen(os.open(env,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as f:
            f.write('SPICEDB_DATASTORE_ENGINE=postgres\nSPICEDB_DATASTORE_CONN_URI=postgres://'+spec['username']+':'+spec['password']+'@'+args.postgres_host+':'+str(5432 if args.network else args.port)+'/'+spec['database']+'?sslmode=disable\nSPICEDB_GRPC_PRESHARED_KEY='+key+'\n')
    if env.is_symlink() or env.stat().st_mode&0o777!=0o600:raise RuntimeError('private graph configuration required')
    values=dict(line.split('=',1) for line in env.read_text().splitlines())
    found=subprocess.run(['docker','inspect',NAME],capture_output=True,text=True)
    if found.returncode==0:
        current=json.loads(found.stdout)[0]
        if current['Config']['Labels'].get('auth-governance-phase')!='p2' or current['Config']['Image']!=IMAGE:
            raise RuntimeError('existing container ownership mismatch')
        if not current['State']['Running']:subprocess.run(['docker','start',NAME],check=True,stdout=subprocess.DEVNULL)
    else:
        with open(ROOT/'migration.log','w') as out:
            subprocess.run(['docker','run',*network,'--rm','--env-file',str(env),IMAGE,'datastore','migrate','head'],check=True,stdout=out,stderr=subprocess.STDOUT)
        subprocess.run(['docker','run',*network,'-d','--name',NAME,'--label','auth-governance-phase=p2','--cpus=1','--memory=512m',
            '--env-file',str(env),'-p',f'127.0.0.1:{PORT}:8443',IMAGE,'serve','--http-enabled','--http-addr=:8443','--grpc-addr=:50051'],check=True,stdout=subprocess.DEVNULL)
    headers={'Authorization':'Bearer '+values['SPICEDB_GRPC_PRESHARED_KEY'],'Content-Type':'application/json'}
    deadline=time.monotonic()+45
    while True:
        try:
            req=urllib.request.Request(f'http://127.0.0.1:{PORT}/v1/schema/read',data=b'{}',headers=headers)
            with urllib.request.urlopen(req,timeout=3) as r:current=json.load(r).get('schemaText','')
            break
        except urllib.error.HTTPError as error:
            if error.code==404:
                current="";break
            raise RuntimeError("isolated schema read failed") from None
        except Exception:
            if time.monotonic()>deadline:raise RuntimeError('isolated graph startup timeout')
            time.sleep(.25)
    schema=Path('auth-platform-core/src/main/resources/schemas/governance.zed').read_text()
    # 仅空的专属图首次初始化；已有模型不覆盖，要求人工评审迁移。
    if not current:
        req=urllib.request.Request(f'http://127.0.0.1:{PORT}/v1/schema/write',data=json.dumps({'schema':schema}).encode(),headers=headers)
        with urllib.request.urlopen(req,timeout=5) as r:json.load(r)
    elif schema_shape(current) != schema_shape(schema):
        raise RuntimeError('existing schema mismatch')
    config=ROOT/'graph.properties'
    content=f'graph.http=http://127.0.0.1:{PORT}\ngraph.key='+values['SPICEDB_GRPC_PRESHARED_KEY']+'\n'
    if not config.exists():
        with os.fdopen(os.open(config,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as f:f.write(content)
    elif config.read_text()!=content:raise RuntimeError('graph client configuration mismatch')
    print('PASS: dedicated persistent graph v1.56.2 on loopback '+str(PORT))

if __name__=='__main__':main()
