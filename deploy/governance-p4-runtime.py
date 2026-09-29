#!/usr/bin/env python3
"""可重复启动/停止P4专属运行时；保留卷和数据库，拒绝覆盖未知配置。"""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import time

HEALTHY="healthy"
ROOT=Path(__file__).resolve().parent.parent
STATE=ROOT/'.local/governance/p4'
COMPOSE=['docker','compose','--env-file',str(STATE/'compose.env'),'-f',str(ROOT/'deploy/governance-p4-compose.yml')]

def private(path,body):
    if path.exists():
        if path.is_symlink() or path.stat().st_mode&0o777!=0o600 or path.read_text()!=body:raise RuntimeError('private runtime configuration mismatch')
        return
    with os.fdopen(os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as out:out.write(body)

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['up','stop']);p.add_argument('--workflow-image',default='workflow-platform-server:local');args=p.parse_args()
    STATE.mkdir(parents=True,exist_ok=True,mode=0o700)
    if args.action=='stop':subprocess.run(COMPOSE+['stop'],check=True);return
    env=STATE/'compose.env'
    if not env.exists():
        image=subprocess.check_output(['docker','image','inspect',args.workflow_image,'--format','{{.Id}}'],text=True).strip()
        private(env,'P4_WORKFLOW_IMAGE='+image+'\nP4_WORKFLOW_CONFIG='+str(STATE/'workflow.properties')+'\nP4_POSTGRES_PASSWORD='+secrets.token_urlsafe(36)+'\n')
    if env.is_symlink() or env.stat().st_mode&0o777!=0o600:raise RuntimeError('private runtime env required')
    subprocess.run(COMPOSE+['config','--quiet'],check=True)
    subprocess.run(COMPOSE+['up','-d','postgres','kafka'],check=True)
    for _ in range(40):
        ready=subprocess.run(['docker','exec','auth-governance-p4-postgres-1','pg_isready','-U','p4_runtime'],capture_output=True)
        if ready.returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('isolated PostgreSQL readiness timeout')
    for kind in ['auth','oa','workflow']:
        subprocess.run(['python3',str(ROOT/'deploy/governance-test-db.py'),'--directory',str(STATE/('runtime-'+kind+'-db')),'--container','auth-governance-p4-postgres-1','--port','15434'],check=True)
    db=dict(line.split('=',1) for line in (STATE/'runtime-workflow-db/database.properties').read_text().splitlines())
    private(STATE/'workflow.properties','spring.datasource.url='+db['jdbc.url'].replace('127.0.0.1:15434','postgres:5432')+'\nspring.datasource.username='+db['jdbc.username']+'\nspring.datasource.password='+db['jdbc.password']+'\nspring.datasource.hikari.maximum-pool-size=8\n')
    subprocess.run(COMPOSE+['up','-d','workflow'],check=True)
    for _ in range(60):
        status=subprocess.check_output(['docker','inspect','auth-governance-p4-workflow-1','--format','{{.State.Health.Status}}'],text=True).strip()
        if status==HEALTHY:print('PASS: isolated P4 PostgreSQL/Kafka/Flowable healthy');return
        time.sleep(1)
    raise RuntimeError('isolated workflow readiness timeout')

if __name__=='__main__':main()
