#!/usr/bin/env python3
"""MG06人工只读核对固定候选，受控配置授权由原CatalogCli重新验证。"""
import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAX_CANDIDATE_BYTES = 141312
CLI_TIMEOUT_SECONDS = 40


def unique_object(pairs):
    """导出输入拒绝重复字段，不能在工具里丢掉注入字段后再声称严格核对。"""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate field')
        result[key] = value
    return result


def candidate(path, application):
    """固定文件只拆受支持信封；真实语义与字节预算仍由服务器同源Java内核检查。"""
    if not path.is_file() or path.is_symlink():
        raise ValueError('regular fixed file required')
    with path.open('rb') as stream:
        raw = stream.read(MAX_CANDIDATE_BYTES + 1)
    if len(raw) > MAX_CANDIDATE_BYTES:
        raise ValueError('candidate too large')
    value = json.loads(raw, object_pairs_hook=unique_object)
    if not isinstance(value, dict):
        raise ValueError('object required')
    if 'manifest' in value:
        if set(value) - {'manifest', 'source', 'reason', 'decision', 'auto_grants', 'auto_roles'}:
            raise ValueError('unknown envelope fields')
        for key in ['auto_grants', 'auto_roles']:
            if key in value and value[key] is not False:
                raise ValueError('automatic authorization forbidden')
        value = {key: item for key, item in value.items() if key not in ['auto_grants', 'auto_roles']}
    else:
        value = {'manifest': value}
    if not isinstance(value.get('manifest'), dict) or value['manifest'].get('application') != application:
        raise ValueError('wrong application')
    return value


def main():
    """仅执行check-source，不发布、不注册目标，不输出凭据或底层失败日志。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--application', required=True)
    parser.add_argument('--configuration', type=Path, required=True)
    parser.add_argument('--jar', type=Path, default=ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        config_stat = args.configuration.lstat()
        if not stat.S_ISREG(config_stat.st_mode) or config_stat.st_uid != os.getuid() or stat.S_IMODE(config_stat.st_mode) & 0o077:
            raise ValueError('private owned configuration required')
        value = candidate(args.candidate, args.application)
        if args.output.exists() or args.output.is_symlink() or not args.output.parent.is_dir():
            raise ValueError('new report path required')
        with tempfile.TemporaryDirectory(prefix='catalog-check-') as directory:
            fixed = Path(directory) / 'candidate.json'
            fixed.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
            completed = subprocess.run(['java', '-Dloader.main=com.lrj.authz.governance.cli.CatalogCli', '-cp', str(args.jar),
                                        'org.springframework.boot.loader.launch.PropertiesLauncher', 'check-source', str(args.configuration), str(fixed)],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=CLI_TIMEOUT_SECONDS)
        if completed.returncode:
            raise RuntimeError('READ_ONLY_CHECK_FAILED')
        lines = [line for line in completed.stdout.splitlines() if line.startswith('{')]
        if len(lines) != 1:
            raise RuntimeError('INVALID_CHECK_RESPONSE')
        report = json.loads(lines[0], object_pairs_hook=unique_object)
        if report.get('application') != args.application or report.get('runtime_state') != 'UNKNOWN' or report.get('state') not in {
                'NOT_PUBLISHED', 'SOURCE_MISMATCH', 'DISPLAY_MISMATCH', 'MATCHED_DECLARATION', 'UNKNOWN'}:
            raise RuntimeError('INVALID_CHECK_RESPONSE')
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print('PASS: read-only source check; runtime_state=UNKNOWN; report=' + str(args.output))
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired, RuntimeError):
        print('FAILED: read-only catalog check; inspect target, private configuration and candidate')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
