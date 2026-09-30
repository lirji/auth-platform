#!/usr/bin/env python3
"""商城迁移前置只读工具；不持有导入、续期或权限切换能力。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import uuid

MAX_BYTES = 4 * 1024 * 1024
MAX_ROWS = 1000
FORMAT = 'p6-commerce-source-observation/v1'
TABLES = {
    'store_operator_grant': ('tenant_id', 'grant_id', 'actor_id', 'resource_type', 'resource_id', 'permission', 'active', 'version', 'updated_at'),
    'platform_credential': ('tenant_id', 'actor_id', 'role', 'active', 'expires_at', 'created_at'),
    'store_record': ('tenant_id', 'store_id', 'merchant_id', 'status', 'version'),
    'merchant_record': ('tenant_id', 'merchant_id', 'status', 'version'),
}
CAPABILITIES = frozenset(('commerce.catalog.operate',))


def require(condition, code):
    """错误只输出固定编码，避免把账号或私密输入带入日志。"""
    if not condition:
        raise ValueError(code)


def canonical(value):
    """稳定编码用于同版本冲突检查；禁用JSON非有限数值扩展。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def _object(pairs):
    """重复键不能被解析器最后一项覆盖，否则摘要和实际映射含义可能不一致。"""
    result = {}
    for key, value in pairs:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def read_json(path, expected_hash=None):
    """私密输入按描述符校验权限和大小，拒绝符号链接及摘要漂移。"""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_mode & 0o077 == 0, 'PRIVATE_REGULAR_FILE_REQUIRED')
        raw = source.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, 'INPUT_TOO_LARGE')
    actual = hashlib.sha256(raw).hexdigest()
    if expected_hash is not None:
        require(bool(re.fullmatch('[a-f0-9]{64}', expected_hash)) and expected_hash == actual, 'SNAPSHOT_HASH_MISMATCH')
    def invalid_constant(_):
        raise ValueError('NON_FINITE_JSON')
    return json.loads(raw, object_pairs_hook=_object, parse_constant=invalid_constant), actual


def write_private(path, value):
    """用排他创建保留上次结果；不能覆盖历史快照或把文件写成全局可读。"""
    raw = canonical(value) + b'\n'
    require(len(raw) <= MAX_BYTES, 'OUTPUT_TOO_LARGE')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as target:
        target.write(raw)
        target.flush()
        os.fsync(target.fileno())


def timestamp(value):
    """MySQL快照来自UTC会话；映射evaluation_at必须显式带时区。"""
    require(isinstance(value, str), 'INVALID_TIMESTAMP')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    return result.replace(tzinfo=timezone.utc) if result.tzinfo is None else result.astimezone(timezone.utc)


def identifier(value):
    require(isinstance(value, str) and bool(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}', value)), 'INVALID_IDENTIFIER')
    return value


def stable_uuid(value):
    require(isinstance(value, str) and str(uuid.UUID(value)) == value, 'INVALID_TARGET_ID')
    return value


def snapshot_tables(snapshot):
    """白名单验证源模型；来源撤销和版本是后续导入不可丢失的事实。"""
    require(isinstance(snapshot, dict) and snapshot.get('format') == FORMAT, 'UNKNOWN_SNAPSHOT_FORMAT')
    tables = snapshot.get('tables')
    require(isinstance(tables, dict) and set(tables) == set(TABLES), 'INCOMPLETE_TABLE_SET')
    for table, fields in TABLES.items():
        rows = tables[table]
        require(isinstance(rows, list) and len(rows) <= MAX_ROWS, 'ROW_BUDGET_EXCEEDED')
        seen = set()
        for row in rows:
            require(isinstance(row, dict) and set(row) == set(fields), 'INVALID_SOURCE_FIELDS')
            identifier(row['tenant_id'])
            for key in fields:
                if key.endswith('_id') or key in ('permission', 'resource_type', 'role', 'status'):
                    identifier(row[key])
            if 'active' in row:
                require(type(row['active']) in (bool, int) and row['active'] in (0, 1), 'INVALID_ACTIVE_STATE')
            if 'version' in row:
                require(type(row['version']) is int and 0 <= row['version'] <= 9223372036854775807, 'INVALID_SOURCE_VERSION')
            for key in ('updated_at', 'expires_at', 'created_at'):
                if key in row:
                    timestamp(row[key])
            if table != 'platform_credential':
                key = (row['tenant_id'], row[fields[1]])
                require(key not in seen, 'DUPLICATE_SOURCE_RECORD')
                seen.add(key)
    return tables


def dry_run(snapshot, snapshot_hash, mapping):
    """仅产生候选/隔离结果；每个允许候选都要求显式身份、能力和真实资源引用。"""
    tables = snapshot_tables(snapshot)
    require(isinstance(mapping, dict) and mapping.get('format') == 'p6-commerce-mapping/v1', 'UNKNOWN_MAPPING_FORMAT')
    require(mapping.get('source_sha256') == snapshot_hash, 'MAPPING_SOURCE_MISMATCH')
    tenant = identifier(mapping.get('source_tenant'))
    target = mapping.get('target')
    require(isinstance(target, dict) and set(target) == {'tenant_id', 'application_id', 'environment'}, 'INVALID_TARGET')
    stable_uuid(target['tenant_id'])
    require(target['application_id'] == 'commerce', 'APPLICATION_MISMATCH')
    identifier(target['environment'])
    evaluation = mapping.get('evaluation_at')
    require(isinstance(evaluation, str) and (evaluation.endswith('Z') or re.search(r'[+-]\d\d:\d\d$', evaluation)), 'EVALUATION_ZONE_REQUIRED')
    now = timestamp(evaluation)
    require(now >= timestamp(snapshot['source']['snapshot_at']), 'EVALUATION_PRECEDES_SNAPSHOT')
    bindings, rules = mapping.get('actors'), mapping.get('permissions')
    require(isinstance(bindings, dict) and isinstance(rules, dict), 'INVALID_MAPPING')
    stores = {(s['tenant_id'], s['store_id']): s for s in tables['store_record']}
    merchants = {(s['tenant_id'], s['merchant_id']): s for s in tables['merchant_record']}
    results = []
    for grant in sorted(tables['store_operator_grant'], key=lambda g: (g['tenant_id'], g['grant_id'])):
        if grant['tenant_id'] != tenant:
            continue
        errors = []
        binding = bindings.get(grant['actor_id'])
        if not isinstance(binding, dict):
            errors.append('EXACT_IDENTITY_MAPPING_REQUIRED')
        else:
            stable_uuid(binding.get('principal_id'))
            stable_uuid(binding.get('membership_id'))
            require(type(binding.get('generation')) is int and binding['generation'] > 0, 'INVALID_GENERATION')
            if not isinstance(binding.get('identity_evidence'), str) or not binding['identity_evidence'].strip():
                errors.append('IDENTITY_EVIDENCE_REQUIRED')
        rule = rules.get(grant['permission'])
        caps = rule.get('capabilities') if isinstance(rule, dict) else None
        if (grant['permission'] != 'CATALOG' or not isinstance(caps, list) or not caps
                or any(not isinstance(c, str) or c not in CAPABILITIES for c in caps) or len(set(caps)) != len(caps)):
            errors.append('CAPABILITY_MAPPING_REQUIRED')
        if not isinstance(rule, dict) or any(not isinstance(rule.get(k), str) or not rule[k].strip() for k in ('approved_by', 'decision_ref')):
            errors.append('SCOPE_CHANGE_DECISION_REQUIRED')
        if grant['resource_type'] != 'STORE':
            errors.append('RESOURCE_SEMANTICS_UNSUPPORTED')
        store = stores.get((tenant, grant['resource_id'])) if grant['resource_type'] == 'STORE' else None
        merchant = merchants.get((tenant, store['merchant_id'])) if store else None
        if store is None or merchant is None:
            errors.append('RESOURCE_REFERENCE_MISSING')
        credentials = [c for c in tables['platform_credential'] if c['tenant_id'] == tenant and c['actor_id'] == grant['actor_id'] and c['role'] == 'OPERATOR']
        credential_active = any(c['active'] and timestamp(c['expires_at']) > now for c in credentials)
        constraints = []
        if not grant['active']:
            constraints.append('SOURCE_GRANT_REVOKED')
        if not credential_active:
            constraints.append('LOCAL_OPERATOR_CREDENTIAL_UNAVAILABLE')
        if store is not None and (store['status'] != 'ACTIVE' or merchant is not None and merchant['status'] != 'ACTIVE'):
            constraints.append('RESOURCE_INACTIVE')
        if not errors and not constraints:
            if any(not isinstance(rule.get(k), str) or not rule[k].strip() for k in ('valid_from', 'valid_to', 'validity_decision')):
                errors.append('ACTIVE_PERMANENT_SOURCE_NEEDS_VALIDITY_DECISION')
            else:
                require(timestamp(rule['valid_from']) <= now < timestamp(rule['valid_to']), 'INVALID_TARGET_VALIDITY')
        disposition = 'QUARANTINED_MAPPING' if errors else 'PRESERVE_DENIAL' if constraints else 'IMPORT_CANDIDATE'
        item = {'source_tenant': tenant, 'source_record': grant['grant_id'], 'source_version': grant['version'],
                'source_fingerprint': digest(grant), 'source_active': bool(grant['active']),
                'source_grant_valid_to': None, 'local_credential_available': credential_active,
                'disposition': disposition, 'errors': errors, 'deny_constraints': constraints}
        if not errors:
            item['target_binding'] = binding
            item['target_capabilities'] = sorted(caps)
            item['target_valid_from'] = rule.get('valid_from') if not constraints else None
            item['target_valid_to'] = rule.get('valid_to') if not constraints else None
            item['scope_rule'] = {'version': 1, 'resource_type': 'store', 'clauses': [{'kind': 'SPECIFIED_STORES', 'values': [grant['resource_id']], 'include_root': False}]}
        results.append(item)
    require(bool(results), 'SOURCE_TENANT_HAS_NO_GRANTS')
    counts = {key: sum(r['disposition'] == key for r in results) for key in ('QUARANTINED_MAPPING', 'PRESERVE_DENIAL', 'IMPORT_CANDIDATE')}
    return {'format': 'p6-commerce-dry-run/v1', 'snapshot_hash': snapshot_hash, 'mapping_hash': digest(mapping),
            'evaluation_at': evaluation, 'target': target, 'records': results, 'counts': counts,
            'ready_for_import': counts['QUARANTINED_MAPPING'] == 0 and counts['IMPORT_CANDIDATE'] > 0,
            'cutover_authorized': False}


def prepare_review(snapshot, snapshot_hash, tenant, evaluation, previous=None):
    """输出人工核对材料，绝不从同名账号推断OA身份或把源删除自动变成授权。"""
    tables = snapshot_tables(snapshot)
    identifier(tenant)
    require(isinstance(evaluation, str) and (evaluation.endswith('Z') or re.search(r'[+-]\d\d:\d\d$', evaluation)), 'EVALUATION_ZONE_REQUIRED')
    now = timestamp(evaluation)
    require(now >= timestamp(snapshot['source']['snapshot_at']), 'EVALUATION_PRECEDES_SNAPSHOT')
    grants = [g for g in tables['store_operator_grant'] if g['tenant_id'] == tenant]
    prior = snapshot_tables(previous) if previous is not None else None
    require(bool(grants) or prior is not None and any(g['tenant_id'] == tenant for g in prior['store_operator_grant']), 'SOURCE_TENANT_HAS_NO_GRANTS')
    actors = []
    for actor in sorted({g['actor_id'] for g in grants}):
        credentials = [c for c in tables['platform_credential'] if c['tenant_id'] == tenant and c['actor_id'] == actor]
        actors.append({'source_actor': actor, 'credential_metadata': credentials,
                       'operator_credential_available': any(c['role'] == 'OPERATOR' and c['active'] and timestamp(c['expires_at']) > now for c in credentials),
                       'oa_subject_ref': None, 'principal_id': None, 'membership_id': None,
                       'generation': None, 'identity_evidence': None, 'reviewed_by': None,
                       'status': 'EXACT_IDENTITY_MAPPING_REQUIRED'})
    stores = {s['store_id']: s for s in tables['store_record'] if s['tenant_id'] == tenant}
    merchants = {m['merchant_id']: m for m in tables['merchant_record'] if m['tenant_id'] == tenant}
    records = []
    for grant in sorted(grants, key=lambda g: g['grant_id']):
        store = stores.get(grant['resource_id']) if grant['resource_type'] == 'STORE' else None
        merchant = merchants.get(store['merchant_id']) if store else None
        records.append({'source': grant, 'fingerprint': digest(grant), 'store': store, 'merchant': merchant,
                        'resource_review': 'RESOURCE_SEMANTICS_UNSUPPORTED' if grant['resource_type'] != 'STORE' else 'RESOURCE_REFERENCE_MISSING' if not store or not merchant else 'OWNER_REFERENCE_PRESENT',
                        'target_valid_from': None, 'target_valid_to': None, 'validity_decision': None})
    changes = []
    if previous is not None:
        require(all(previous['source'].get(k) == snapshot['source'].get(k) for k in ('database', 'database_container')), 'SNAPSHOT_SOURCE_MISMATCH')
        require(timestamp(previous['source']['snapshot_at']) <= timestamp(snapshot['source']['snapshot_at']), 'SNAPSHOT_ORDER_INVALID')
        # 资源状态/归属变化也会影响授权；只比较所选租户且保存完整来源版本。
        for table, fields in TABLES.items():
            if table == 'platform_credential':
                before = sorted((c for c in prior[table] if c['tenant_id'] == tenant), key=canonical)
                after = sorted((c for c in tables[table] if c['tenant_id'] == tenant), key=canonical)
                if before != after:
                    changes.append({'table': table, 'kind': 'CREDENTIAL_SET_CHANGED', 'before': before, 'after': after})
                continue
            key = fields[1]
            before = {r[key]: r for r in prior[table] if r['tenant_id'] == tenant}
            after = {r[key]: r for r in tables[table] if r['tenant_id'] == tenant}
            for record_id in sorted(before.keys() | after.keys()):
                old, new = before.get(record_id), after.get(record_id)
                if old == new:
                    continue
                kind = 'MISSING_REQUIRES_TOMBSTONE_REVIEW' if new is None else 'ADDED' if old is None else 'CHANGED'
                if old and new and new['version'] <= old['version']:
                    kind = 'VERSION_CONFLICT'
                changes.append({'table': table, 'id': record_id, 'kind': kind, 'before': old, 'after': new})
    return {'format': 'p6-commerce-mapping-review/v1', 'source_sha256': snapshot_hash,
            'source_tenant': tenant, 'evaluation_at': evaluation, 'authority': 'OA',
            'actors': actors, 'records': records, 'changes': changes,
            'decisions': {'target_partition': None, 'capability_mapping': None, 'owner_approval': None},
            'ready_for_import': False, 'cutover_authorized': False}


def compare_shadow(samples):
    """只有完整ALLOW/DENY相等才收敛；双边ERROR也不能当成一致拒绝。"""
    require(isinstance(samples, list) and 0 < len(samples) <= MAX_ROWS, 'INVALID_SHADOW_SAMPLES')
    differences = []
    for index, sample in enumerate(samples):
        require(isinstance(sample, dict), 'INVALID_SHADOW_SAMPLE')
        old, new = sample.get('legacy_decision'), sample.get('central_decision')
        if old not in ('ALLOW', 'DENY') or new not in ('ALLOW', 'DENY') or old != new:
            differences.append({'index': index, 'legacy_decision': old, 'central_decision': new})
    return {'sample_count': len(samples), 'ready': not differences, 'differences': differences,
            'authorization_mode': 'LEGACY_ONLY_DURING_SHADOW'}


class MySqlSnapshotSource:
    """运维提取适配器，SQL仅在此持久化边界；不向应用提供跨库业务查询。"""
    def export(self, database, container):
        require(bool(re.fullmatch('[a-zA-Z0-9_]{1,64}', database)), 'INVALID_DATABASE')
        require(bool(re.fullmatch('[a-zA-Z0-9_.-]{1,100}', container)), 'INVALID_CONTAINER')
        statements = ["SET SESSION time_zone='+00:00'", 'SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ',
                      'SET SESSION TRANSACTION READ ONLY', 'START TRANSACTION WITH CONSISTENT SNAPSHOT',
                      "SELECT JSON_OBJECT('snapshot_at',DATE_FORMAT(UTC_TIMESTAMP(3),'%Y-%m-%dT%H:%i:%s.%fZ'),'database',DATABASE(),'tables',(SELECT JSON_ARRAYAGG(JSON_OBJECT('name',TABLE_NAME,'engine',ENGINE)) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('store_operator_grant','platform_credential','store_record','merchant_record')))"]
        for table, fields in TABLES.items():
            pairs = ','.join("'%s',x.%s" % (c, c) for c in fields)
            where = '' if table == 'store_operator_grant' else ' WHERE EXISTS (SELECT 1 FROM store_operator_grant g WHERE g.tenant_id=t.tenant_id)'
            statements.append(f"SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT({pairs})),JSON_ARRAY()) FROM (SELECT {','.join(fields)} FROM {table} t{where} ORDER BY {','.join(fields[:2])} LIMIT {MAX_ROWS + 1}) x")
        statements.append('COMMIT')
        # 凭据在容器内取用，禁止出现在命令参数或错误回显；查询超时后没有写事务需要补偿。
        process = subprocess.run(['docker', 'exec', '-i', container, 'sh', '-c',
                                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root --batch --raw --skip-column-names "$1"', 'sh', database],
                                 input=';\n'.join(statements) + ';\n', text=True, capture_output=True, timeout=30)
        require(process.returncode == 0, 'SOURCE_QUERY_FAILED')
        require(len(process.stdout.encode()) <= MAX_BYTES, 'SNAPSHOT_TOO_LARGE')
        rows = process.stdout.splitlines()
        require(len(rows) == 5, 'INCOMPLETE_SOURCE_RESPONSE')
        meta = json.loads(rows[0], object_pairs_hook=_object)
        require(isinstance(meta.get('tables'), list) and len(meta['tables']) == 4 and all(t['engine'] == 'InnoDB' for t in meta['tables']), 'CONSISTENT_SNAPSHOT_UNSUPPORTED')
        result = {'format': FORMAT, 'source': {'database_container': container, **meta},
                  'tables': {t: json.loads(v, object_pairs_hook=_object) for t, v in zip(TABLES, rows[1:])},
                  'consistency': 'single repeatable-read read-only InnoDB transaction; writers not frozen',
                  'migration_selected': False}
        snapshot_tables(result)
        return result


def main(argv=None):
    """显式子命令，输出仅摘要；dry-run需要处置时退出2，不能被脚本误当成功。"""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    source = commands.add_parser('snapshot')
    source.add_argument('--database', required=True)
    source.add_argument('--container', required=True)
    source.add_argument('--output', required=True)
    dry = commands.add_parser('dry-run')
    dry.add_argument('--snapshot', required=True)
    dry.add_argument('--sha256', required=True)
    dry.add_argument('--mapping', required=True)
    dry.add_argument('--output', required=True)
    review = commands.add_parser('prepare-review')
    review.add_argument('--snapshot', required=True)
    review.add_argument('--sha256', required=True)
    review.add_argument('--tenant', required=True)
    review.add_argument('--evaluation-at', required=True)
    review.add_argument('--previous')
    review.add_argument('--previous-sha256')
    review.add_argument('--output', required=True)
    args = parser.parse_args(argv)
    try:
        if args.action == 'snapshot':
            result = MySqlSnapshotSource().export(args.database, args.container)
            write_private(args.output, result)
            print(json.dumps({'result': 'SNAPSHOT_SAVED', 'sha256': hashlib.sha256(Path(args.output).read_bytes()).hexdigest(), 'counts': {t: len(v) for t, v in result['tables'].items()}}))
            return 0
        snapshot, actual = read_json(args.snapshot, args.sha256)
        if args.action == 'prepare-review':
            require(bool(args.previous) == bool(args.previous_sha256), 'PREVIOUS_HASH_REQUIRED')
            previous = read_json(args.previous, args.previous_sha256)[0] if args.previous else None
            report = prepare_review(snapshot, actual, args.tenant, args.evaluation_at, previous)
            report['previous_sha256'] = args.previous_sha256
            write_private(args.output, report)
            print(json.dumps({'result': 'REVIEW_REQUIRED', 'actors': len(report['actors']),
                              'records': len(report['records']), 'changes': len(report['changes']),
                              'ready_for_import': False, 'cutover_authorized': False}))
            return 2
        mapping, _ = read_json(args.mapping)
        report = dry_run(snapshot, actual, mapping)
        write_private(args.output, report)
        print(json.dumps({'counts': report['counts'], 'ready_for_import': report['ready_for_import'], 'cutover_authorized': False}))
        return 0 if report['ready_for_import'] else 2
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        print('MIGRATION_INPUT_OR_IO_ERROR: private diagnostics suppressed', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
