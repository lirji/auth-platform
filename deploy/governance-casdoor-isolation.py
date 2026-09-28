#!/usr/bin/env python3
"""启动固定摘要的 Casdoor 验证实例，专用库和回环端口；不升级共享实例。"""
import base64
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request

IMAGE = 'casbin/casdoor@sha256:138b5e46d49ad678ea2d53487fa74f8aae6e3ee3a670fc1b62ebe981c2a9d964'
BASE = 'http://localhost:18090'
POSTGRES_CONTAINER = 'dev-infra-postgres16-1'
COMMAND_TIMEOUT_SECONDS = 30
INSPECT_TIMEOUT_SECONDS = 10


def config_volume(container, config):
    """保持镜像原生 UID 1000；私密配置复制到本任务卷，避免宿主 UID/日志目录差异。"""
    volume = container + '-config'
    owner = subprocess.run(['docker', 'volume', 'inspect', '--format', '{{index .Labels "com.lrj.task"}}', volume],
                           text=True, capture_output=True, timeout=INSPECT_TIMEOUT_SECONDS)
    if owner.returncode == 0 and owner.stdout.strip() != 'oa-auth-p1-token-compat':
        raise RuntimeError('private configuration volume owner conflict')
    if owner.returncode:
        command(['docker', 'volume', 'create', '--label', 'com.lrj.task=oa-auth-p1-token-compat', volume])
    # 该临时 helper 无网络、只读根文件系统，仅可写本任务卷；不运行 IdP 或放宽源文件权限。
    script = '''set -eu
umask 077
if [ -e /private-conf/app.conf ]; then
  cmp -s /input.conf /private-conf/app.conf
else
  cp /input.conf /private-conf/app.conf
fi
chown 1000:1000 /private-conf /private-conf/app.conf
chmod 700 /private-conf
chmod 600 /private-conf/app.conf
'''
    command(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--memory', '32m', '--user', '0',
             '--entrypoint', 'sh', '--mount', 'type=bind,source=' + str(config) + ',target=/input.conf,readonly',
             '--mount', 'type=volume,source=' + volume + ',target=/private-conf', IMAGE, '-c', script])
    return volume


def private_file(path, content):
    """私密文件只首次创建，重跑核对一致性而不重置。"""
    if path.exists():
        if path.is_symlink() or path.stat().st_mode & 0o777 != 0o600 or path.read_text() != content:
            raise RuntimeError('private configuration conflict')
        return
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(content)


def command(args, input=None):
    """本工具不回显 stderr 中可能出现的数据源或凭据。"""
    result = subprocess.run(args, input=input, text=True, capture_output=True, timeout=COMMAND_TIMEOUT_SECONDS)
    if result.returncode:
        raise RuntimeError('isolated runtime command failed')
    return result.stdout.strip()


def sql(database, statement):
    return command(['docker', 'exec', '-i', POSTGRES_CONTAINER, 'sh', '-c',
                    'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], statement)


def main():
    global POSTGRES_CONTAINER, BASE
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='.local/governance')
    parser.add_argument('--postgres-container', default=POSTGRES_CONTAINER)
    parser.add_argument('--postgres-host', default='dev-infra-postgres16-1')
    parser.add_argument('--network', default='dev-infra')
    parser.add_argument('--port', type=int, choices=[18090, 18093], default=18090)
    args = parser.parse_args()
    POSTGRES_CONTAINER = args.postgres_container
    BASE = 'http://localhost:' + str(args.port)
    if not re.fullmatch(r'[a-zA-Z0-9_.-]+', args.postgres_host):
        raise RuntimeError('invalid isolated PostgreSQL host')
    base = Path(args.directory).resolve()
    directory = base / 'casdoor-isolated'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    checkpoint = base / 'database.json'
    if checkpoint.is_symlink() or checkpoint.stat().st_mode & 0o777 != 0o600:
        raise RuntimeError('private database checkpoint required')
    database = json.loads(checkpoint.read_text())
    if (not re.fullmatch(r'auth_gov_p1_test_[a-f0-9]{12}', database['database'])
            or not re.fullmatch(r'auth_gov_p1_[a-f0-9]{12}', database['username'])
            or not re.fullmatch(r'[A-Za-z0-9_-]+', database['password'])):
        raise RuntimeError('unknown database namespace')
    name = database['database'].replace('auth_gov_p1_test_', 'auth_casdoor_p1_test_')
    owner = sql('postgres', "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='%s';" % name)
    if owner and owner != database['username']:
        raise RuntimeError('database owner conflict')
    if not owner:
        sql('postgres', 'CREATE DATABASE %s OWNER %s;' % (name, database['username']))
    content = '''appname = casdoor
httpport = 8000
runmode = prod
driverName = postgres
dataSourceName = "user=%s password=%s host=%s port=5432 sslmode=disable dbname=%s"
dbName = %s
tableNamePrefix =
showSql = false
origin = "%s"
originFrontend = "%s"
isDemoMode = false
''' % (database['username'], database['password'], args.postgres_host, name, name, BASE, BASE)
    config = directory / 'app.conf'
    private_file(config, content)
    container = name.replace('auth_casdoor_p1_test_', 'auth-gov-casdoor-p1-')
    lookup = subprocess.run(['docker', 'inspect', '--format', '{{.Config.Image}}|{{index .Config.Labels "com.lrj.task"}}', container],
                            text=True, capture_output=True, timeout=INSPECT_TIMEOUT_SECONDS)
    if lookup.returncode == 0:
        if lookup.stdout.strip() != IMAGE + '|oa-auth-p1-token-compat':
            raise RuntimeError('container owner/image conflict')
    else:
        volume = config_volume(container, config)
        command(['docker', 'run', '-d', '--name', container, '--label', 'com.lrj.task=oa-auth-p1-token-compat',
                 '--network', args.network, '--cpus', '1', '--memory', '768m', '--restart', 'no',
                 '-p', '127.0.0.1:' + str(args.port) + ':8000', '--mount', 'type=volume,source=' + volume + ',target=/conf,readonly', IMAGE])
    deadline = time.monotonic() + 50
    while True:
        try:
            with urllib.request.urlopen(BASE + '/.well-known/openid-configuration', timeout=3) as response:
                discovery = json.load(response)
            if discovery.get('issuer') == BASE:
                break
        except (OSError, ValueError):
            if time.monotonic() >= deadline:
                state = command(['docker', 'inspect', '--format', '{{.State.Status}}|{{.State.ExitCode}}', container])
                # 只输出状态类别；不能将 Casdoor 启动日志中的 DSN/secret 发到 CI stdout。
                logs = subprocess.run(['docker', 'logs', container], text=True, capture_output=True, timeout=INSPECT_TIMEOUT_SECONDS)
                category = 'PERMISSION_DENIED' if 'permission denied' in logs.stdout + logs.stderr else 'STARTUP_OR_DEPENDENCY'
                print(json.dumps({'gate': 'readiness', 'container_state': state, 'category': category, 'result': 'FAIL'}))
                raise RuntimeError('isolated IdP readiness timeout') from None
        time.sleep(0.5)
    credentials = json.loads(sql(name, "SELECT json_build_object('client_id',client_id,'client_secret',client_secret) "
                                     "FROM application WHERE owner='admin' AND name='app-built-in';"))
    private_file(directory / 'management-client.json', json.dumps(credentials, indent=2) + '\n')
    header = 'Basic ' + base64.b64encode((credentials['client_id'] + ':' + credentials['client_secret']).encode()).decode()
    req = urllib.request.Request(BASE + '/api/get-version-info', headers={'Authorization': header})
    with urllib.request.urlopen(req, timeout=5) as response:
        metadata = json.load(response)
    version = metadata.get('data', {}).get('version')
    if version != 'v4.11.0':
        raise RuntimeError('immutable image version mismatch; keep isolated for review')
    facts = {'result': 'PASS', 'image': IMAGE, 'version': version, 'base': BASE, 'container': container,
             'database': name, 'shared_instance_modified': False, 'credentials': 'management-client.json (0600)'}
    (directory / 'runtime-result.json').write_text(json.dumps(facts, indent=2) + '\n')
    print(json.dumps(facts, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit('FAIL: isolated Casdoor preparation; shared instance unchanged; inspect private checkpoint locally')
