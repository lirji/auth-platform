#!/usr/bin/env python3
"""为当前任务创建专用 PostgreSQL 测试库；不重置、不删除、不读取其他业务表。"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess


def private_file(path, content):
    """先持久化私密检查点；即使后续开库失败，重跑仍使用同一身份。"""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(content)


def sql(container, statement):
    """SQL 经 stdin 传入，不把密码放到参数或错误输出。"""
    result = subprocess.run(
        ['docker', 'exec', '-i', container, 'sh', '-c',
         'exec psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -At'],
        input=statement, text=True, capture_output=True, check=False, timeout=20)
    if result.returncode:
        raise RuntimeError('专用测试库准备失败；检查容器状态和私密检查点，不回显 SQL')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='.local/governance')
    parser.add_argument('--container', default='dev-infra-postgres16-1')
    parser.add_argument('--port', type=int, default=45432)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        raise RuntimeError('测试端口无效')
    directory = Path(args.directory).resolve()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    checkpoint = directory / 'database.json'
    if not checkpoint.exists():
        suffix = secrets.token_hex(6)
        private_file(checkpoint, json.dumps({
            'database': 'auth_gov_p1_test_' + suffix,
            'username': 'auth_gov_p1_' + suffix,
            'password': secrets.token_urlsafe(36),
        }))
    if checkpoint.is_symlink() or checkpoint.stat().st_mode & 0o777 != 0o600:
        raise RuntimeError('检查点权限必须为 0600 且不是符号链接')
    spec = json.loads(checkpoint.read_text())
    name, username, password = spec['database'], spec['username'], spec['password']
    if (not re.fullmatch(r'auth_gov_p1_test_[a-f0-9]{12}', name)
            or not re.fullmatch(r'auth_gov_p1_[a-f0-9]{12}', username)
            or not re.fullmatch(r'[A-Za-z0-9_-]+', password)):
        raise RuntimeError('私密检查点不属于当前工具命名空间')
    if not sql(args.container, "SELECT 1 FROM pg_roles WHERE rolname = '%s';" % username):
        sql(args.container, "CREATE ROLE %s LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '%s';" % (username, password))
    owner = sql(args.container, "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '%s';" % name)
    if owner and owner != username:
        raise RuntimeError('目标库存在但 Owner 不匹配，拒绝接管')
    if not owner:
        sql(args.container, 'CREATE DATABASE %s OWNER %s;' % (name, username))
    config = directory / 'database.properties'
    content = 'jdbc.url=jdbc:postgresql://127.0.0.1:%s/%s\njdbc.username=%s\njdbc.password=%s\n' % (args.port, name, username, password)
    if not config.exists():
        private_file(config, content)
    elif config.is_symlink() or config.stat().st_mode & 0o777 != 0o600 or config.read_text() != content:
        raise RuntimeError('目标配置不一致，拒绝覆盖')
    print('PASS: dedicated database ' + name + '; config=' + str(config))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit('FAIL: governance test database preparation; inspect private checkpoint locally')
