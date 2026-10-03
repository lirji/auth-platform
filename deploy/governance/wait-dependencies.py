#!/usr/bin/env python3
"""在应用启动前等待原登录 issuer 与持久化图可读取，不修改身份或图数据。"""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration', required=True, type=Path)
    args = parser.parse_args()
    lines = args.configuration.read_text().splitlines()
    # 配置由 prepare-dependencies.py 导出，raw 值不能按 shell 或 dotenv 插值。
    env = dict(line.split('=', 1) for line in lines if line and not line.startswith('#'))
    headers = {'Authorization': 'Bearer ' + env['SPICEDB_GRPC_PRESHARED_KEY'],
               'Content-Type': 'application/json'}
    deadline = time.monotonic() + 45
    while True:
        try:
            with urllib.request.urlopen('http://localhost:18090/.well-known/openid-configuration', timeout=3) as response:
                if json.load(response).get('issuer') != 'http://localhost:18090':
                    raise RuntimeError('unexpected local issuer')
            request = urllib.request.Request('http://127.0.0.1:18544/v1/schema/read',
                                             data=b'{}', headers=headers)
            with urllib.request.urlopen(request, timeout=3) as response:
                if not json.load(response).get('schemaText'):
                    raise RuntimeError('existing graph schema required')
            print('Governance identity and graph reads ready.')
            return
        except urllib.error.HTTPError as error:
            if error.code not in (500, 502, 503, 504):
                raise RuntimeError('dependency read rejected') from None
        except (OSError, ValueError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('dependency readiness deadline exceeded')
        time.sleep(0.5)


if __name__ == '__main__':
    main()
