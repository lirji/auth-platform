#!/usr/bin/env python3
"""从现有容器导出私密接管配置；只读 Docker，不停止容器或生成新凭据。"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

GRAPH_COMMAND = [
    'serve', '--http-enabled', '--http-addr=:8443', '--grpc-addr=:50051',
    '--datastore-conn-pool-read-min-open=1', '--datastore-conn-pool-read-max-open=4',
    '--datastore-conn-pool-write-min-open=1', '--datastore-conn-pool-write-max-open=2',
]


def inspect(kind, name):
    """不将 Docker stderr 回显，避免配置错误时泄露私密连接串。"""
    result = subprocess.run(['docker', kind, 'inspect', name], capture_output=True,
                            text=True, timeout=20)
    if result.returncode:
        raise RuntimeError(f'{kind} inspect failed')
    return json.loads(result.stdout)[0]


def private_file(path, content):
    """重复准备只接受完全一致的配置，防止悄悄换库或覆盖回退证据。"""
    if path.is_symlink():
        raise RuntimeError('symlink output rejected')
    if path.exists():
        if path.read_text() != content or path.stat().st_mode & 0o777 != 0o600:
            raise RuntimeError('existing private configuration differs')
        return
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--casdoor-container', required=True)
    parser.add_argument('--graph-container', required=True)
    parser.add_argument('--output-directory', required=True, type=Path)
    args = parser.parse_args()
    casdoor = inspect('container', args.casdoor_container)
    graph = inspect('container', args.graph_container)
    # 固定本地接管范围；其它网络、挂载或启动参数需要重新分析，不能漏掉后照搬。
    expected_ports = [({'8000/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18090'}]}, casdoor),
                      ({'8443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18544'}]}, graph)]
    for ports, container in expected_ports:
        if container['HostConfig']['PortBindings'] != ports:
            raise RuntimeError('port bindings outside adoption scope')
        if not re.fullmatch(r'[^\s]+@sha256:[a-f0-9]{64}', container['Config']['Image']):
            raise RuntimeError('digest-pinned image required')
    for container, memory in [(casdoor, 768 * 1024 * 1024), (graph, 512 * 1024 * 1024)]:
        host = container['HostConfig']
        if host['NanoCpus'] != 1_000_000_000 or host['Memory'] != memory or host['ReadonlyRootfs']:
            raise RuntimeError('resource configuration outside adoption scope')
        image = inspect('image', container['Config']['Image'])['Config']
        defaults = {'Entrypoint': None, 'User': '', 'WorkingDir': ''}
        if any(container['Config'].get(k, default) != image.get(k, default) for k, default in defaults.items()):
            raise RuntimeError('image runtime overrides require separate review')
        current_env = dict(item.split('=', 1) for item in container['Config']['Env'])
        image_env = dict(item.split('=', 1) for item in image.get('Env', []))
        if container is graph:
            current_env = {k: v for k, v in current_env.items() if not k.startswith('SPICEDB_')}
        if current_env != image_env or (container is casdoor and container['Config']['Cmd'] != image.get('Cmd')):
            raise RuntimeError('unsupported image environment or command override')
    if graph['Config']['Cmd'] != GRAPH_COMMAND or graph['HostConfig']['NetworkMode'] != 'bridge' or graph['Mounts']:
        raise RuntimeError('graph runtime differs from supported scope')
    mounts = casdoor['Mounts']
    if len(mounts) != 1 or mounts[0]['Type'] != 'volume' or mounts[0]['Destination'] != '/conf' or mounts[0]['RW']:
        raise RuntimeError('existing read-only Casdoor configuration volume required')
    networks = list(casdoor['NetworkSettings']['Networks'])
    if len(networks) != 1:
        raise RuntimeError('single existing database network required')
    inspect('volume', mounts[0]['Name'])
    inspect('network', networks[0])
    values = dict(item.split('=', 1) for item in graph['Config']['Env'])
    keys = ['SPICEDB_DATASTORE_ENGINE', 'SPICEDB_DATASTORE_CONN_URI', 'SPICEDB_GRPC_PRESHARED_KEY']
    if {k for k in values if k.startswith('SPICEDB_')} != set(keys):
        raise RuntimeError('additional graph options require separate review')
    if values.get(keys[0]) != 'postgres' or any(not values.get(k) or '\n' in values[k] for k in keys):
        raise RuntimeError('persistent graph configuration required')
    root = args.output_directory.expanduser().absolute()
    if root.is_symlink():
        raise RuntimeError('symlink directory rejected')
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    snapshot = root / 'dependencies-before.private.json'
    if not snapshot.exists():
        private_file(snapshot, json.dumps([casdoor, graph], indent=2) + '\n')
    elif snapshot.is_symlink() or snapshot.stat().st_mode & 0o777 != 0o600:
        raise RuntimeError('private original snapshot required')
    private_file(root / 'dependencies-graph.env', ''.join(k + '=' + values[k] + '\n' for k in keys))
    exports = {
        'GOVERNANCE_CASDOOR_IMAGE': casdoor['Config']['Image'],
        'GOVERNANCE_GRAPH_IMAGE': graph['Config']['Image'],
        'GOVERNANCE_CASDOOR_CONFIG_VOLUME': mounts[0]['Name'],
        'GOVERNANCE_DATABASE_NETWORK': networks[0],
        'GOVERNANCE_GRAPH_ENV_FILE': str(root / 'dependencies-graph.env'),
    }
    # Compose 的插值 env 与 raw 容器 env 分开；前者用单引号保留路径中的美元符号。
    private_file(root / 'dependencies.env', ''.join(k + "='" + v.replace("'", "\\'") + "'\n" for k, v in exports.items()))
    print('Private dependencies configuration prepared; containers unchanged.')


if __name__ == '__main__':
    main()
