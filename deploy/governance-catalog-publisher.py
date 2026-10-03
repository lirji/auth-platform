#!/usr/bin/env python3
"""固定目标的目录发布客户端：默认预览，先持久化原命令，再发送发布；不部署或写业务授权。"""
import argparse
import contextlib
import datetime
from enum import Enum
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_MANIFEST_BYTES = 131072
MAX_CANDIDATE_BYTES = 141312
MAX_RESPONSE_BYTES = 2097152
MAX_CHECKPOINT_BYTES = 4194304
TARGET_FIELDS = {'base_url', 'target_instance_id', 'environment', 'application_id', 'publisher_id', 'delegation_id', 'service_principal', 'issuer', 'client_id', 'client_secret', 'publish_enabled', 'timeout_seconds'}
TARGET_REQUIRED = TARGET_FIELDS - {'publish_enabled', 'timeout_seconds'}
PREVIEW_FIELDS = {'application', 'current_version', 'proposed_version', 'content_hash', 'added', 'retained', 'presentation_hash', 'menu_changes', 'violations', 'publishable', 'affected_capabilities'}


class Stage(str, Enum):
    """命令入口只有三个稳定阶段，禁止任意字符串成为远程操作。"""
    PREVIEW = 'preview'
    PUBLISH = 'publish'
    RECOVER = 'recover'


class PublicationState(str, Enum):
    """检查点状态表示目录发布事实，恢复不能将历史未知改写为未提交。"""
    PREVIEW_PENDING = 'PREVIEW_PENDING'
    PREVIEWED = 'PREVIEWED'
    REQUIRES_OWNER_REVIEW = 'REQUIRES_OWNER_REVIEW'
    PUBLISHING_UNKNOWN = 'PUBLISHING_UNKNOWN'
    PUBLISHED = 'PUBLISHED'
    REJECTED = 'REJECTED'


class PublisherError(Exception):
    """仅稳定错误码可进入终端，外部异常和认证原文不穿透边界。"""
    def __init__(self, code, trace_id=None, uncertain=False):
        super().__init__(code)
        self.code, self.trace_id, self.uncertain = code, trace_id, uncertain


def require(condition, code='INVALID_ARGUMENT'):
    """协议和输入不确定时拒绝，不做宽松转换或推测成功。"""
    if not condition:
        raise PublisherError(code)


def strict_json(data):
    """重复键、NaN和非对象根不能形成固定命令。"""
    def pairs(items):
        value = {}
        for key, item in items:
            require(key not in value)
            value[key] = item
        return value
    try:
        result = json.loads(data, object_pairs_hook=pairs, parse_constant=lambda _: require(False))
        require(isinstance(result, dict))
        return result
    except (ValueError, TypeError, UnicodeError):
        raise PublisherError('INVALID_ARGUMENT') from None


def fields(value, allowed, required=None, code='INVALID_ARGUMENT'):
    """DTO与私密配置各自允许列表，未知字段不会悄悄变成权限。"""
    require(isinstance(value, dict) and set(value) <= allowed and (required or set()) <= set(value), code)


def code(value):
    return isinstance(value, str) and re.fullmatch(r'[a-z][a-z0-9._-]{0,99}', value) is not None


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', value) is not None


def digest(value):
    return hashlib.sha256(value).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True).encode()


def read_file(path, maximum, private=False):
    """最终文件NOFOLLOW且读取有界；Secret或检查点要求本用户0600。"""
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'rb') as stream:
            info = os.fstat(stream.fileno())
            require(stat.S_ISREG(info.st_mode))
            if private:
                require(stat.S_IMODE(info.st_mode) == 0o600 and info.st_uid == os.getuid())
            data = stream.read(maximum + 1)
            require(len(data) <= maximum)
            return data
    except OSError:
        raise PublisherError('INVALID_ARGUMENT') from None


def target(path):
    """调用方只能选择受控目标文件；候选和Token不能改变网络目标。"""
    value = strict_json(read_file(path, 8192, True))
    fields(value, TARGET_FIELDS, TARGET_REQUIRED)
    for key in ('target_instance_id', 'publisher_id', 'delegation_id', 'service_principal'):
        require(identifier(value[key]))
    for key in ('environment', 'application_id'):
        require(code(value[key]))
    for key in ('client_id', 'client_secret'):
        require(isinstance(value[key], str) and 0 < len(value[key]) <= (160 if key == 'client_id' else 4096) and not any(ord(c) < 32 for c in value[key]))
    for key in ('base_url', 'issuer'):
        require(isinstance(value[key], str) and len(value[key]) <= 500 and not any(c.isspace() or ord(c) < 32 for c in value[key]))
        url = urllib.parse.urlsplit(value[key])
        require(url.hostname and not url.username and not url.password and not url.path and not url.query and not url.fragment)
        require(url.scheme == 'https' or url.scheme == 'http' and url.hostname in ('localhost', '127.0.0.1') and value['environment'] not in ('prod', 'production'))
        try:
            require(url.port is None or 1 <= url.port <= 65535)
        except ValueError:
            raise PublisherError('INVALID_ARGUMENT') from None
    value.setdefault('publish_enabled', False)
    value.setdefault('timeout_seconds', 5)
    require(type(value['publish_enabled']) is bool and type(value['timeout_seconds']) is int and 1 <= value['timeout_seconds'] <= 5)
    return value


def binding(value):
    """检查点绑定目标及稳定机器身份，不保存Secret，换客户端或委派不能接管原命令。"""
    return {key: value[key] for key in TARGET_REQUIRED if key != 'client_secret'}


def normalized_manifest(value):
    """仅归一化声明排序供来源匹配；服务端仍执行完整引用、树及生命周期校验。"""
    fields(value, {'schema_version', 'application', 'manifest_version', 'capabilities', 'menus'}, {'schema_version', 'application', 'capabilities', 'menus'})
    require(value['schema_version'] == '1' and code(value['application']))
    caps, menus = value['capabilities'], value['menus']
    require(isinstance(caps, list) and 0 < len(caps) <= 200 and isinstance(menus, list) and len(menus) <= 100)
    for cap in caps:
        fields(cap, {'code', 'resource_type', 'risk_level'}, {'code', 'resource_type', 'risk_level'})
        require(code(cap['code']) and cap['code'].startswith(value['application'] + '.') and code(cap['resource_type']) and cap['risk_level'] in ('NORMAL', 'HIGH'))
    result = []
    for menu in menus:
        fields(menu, {'code', 'parent', 'route', 'any_of', 'label', 'position'}, {'code', 'parent', 'route', 'any_of'})
        require(code(menu['code']) and isinstance(menu['any_of'], list) and len(menu['any_of']) <= 200 and all(code(c) for c in menu['any_of']))
        require(menu['parent'] is None or code(menu['parent']))
        require(menu['route'] is None or isinstance(menu['route'], str) and re.fullmatch(r'/[a-zA-Z0-9/_-]*', menu['route']) and '//' not in menu['route'])
        require((menu.get('label') is None) == (menu.get('position') is None))
        require(menu.get('label') is None or isinstance(menu['label'], str) and type(menu['position']) is int)
        result.append({**menu, 'label': menu.get('label'), 'position': menu.get('position'), 'any_of': sorted(menu['any_of'])})
    return {'schema_version': '1', 'application': value['application'], 'capabilities': sorted(caps, key=lambda c: c['code']), 'menus': sorted(result, key=lambda m: m['code'])}


def source(candidate_path, artifact_path, commit, config):
    """原始字节、固定提交和完整声明一致，不能仅把调用者写的摘要当作构建证据。"""
    require(isinstance(commit, str) and re.fullmatch(r'(?:[0-9a-f]{40}|[0-9a-f]{64})', commit))
    raw = read_file(candidate_path, MAX_CANDIDATE_BYTES)
    artifact = read_file(artifact_path, MAX_MANIFEST_BYTES)
    value, declaration = strict_json(raw), strict_json(artifact)
    fields(value, {'manifest', 'source', 'reason', 'decision', 'auto_grants', 'auto_roles'}, {'manifest', 'source'})
    require(value.get('decision') is None and value.get('auto_grants', False) is False and value.get('auto_roles', False) is False)
    fields(value['source'], {'commit', 'artifact_hash'}, {'commit', 'artifact_hash'})
    require(value['source'] == {'commit': commit, 'artifact_hash': digest(artifact)}, 'SOURCE_CHANGED')
    fields(declaration, {'schema_version', 'application', 'capabilities', 'menus'}, {'schema_version', 'application', 'capabilities', 'menus'})
    require(normalized_manifest(value['manifest']) == normalized_manifest(declaration), 'SOURCE_CHANGED')
    require(value['manifest']['application'] == config['application_id'])
    version = value['manifest'].get('manifest_version')
    require(type(version) is int and 0 < version <= 9007199254740991)
    reason = value.get('reason')
    require(reason is None or isinstance(reason, str) and 0 < len(reason) <= 500)
    candidate = {'manifest': {**normalized_manifest(value['manifest']), 'manifest_version': version}, 'source': value['source'], 'reason': reason, 'decision': None}
    return {'candidate': candidate, 'candidate_file_sha256': digest(raw), 'artifact_sha256': digest(artifact), 'expected_commit': commit}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """任何重定向都拒绝，防止固定Secret被发送到另一个目标。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def post(url, data, timeout, token=None, form=False, maximum=MAX_RESPONSE_BYTES):
    """外部错误只输出稳定code；5xx／网络故障保留结果未知，不保存原响应正文。"""
    body = urllib.parse.urlencode(data).encode() if form else encoded(data)
    headers = {'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    try:
        with urllib.request.build_opener(NoRedirect()).open(urllib.request.Request(url, body, headers), timeout=timeout) as response:
            raw = response.read(maximum + 1)
            require(len(raw) <= maximum, 'PROTOCOL_INVALID')
            try:
                return strict_json(raw)
            except PublisherError:
                raise PublisherError('PROTOCOL_INVALID') from None
    except urllib.error.HTTPError as failure:
        known = None
        try:
            raw = failure.read(4097)
            if len(raw) <= 4096:
                error = strict_json(raw)
                if set(error) == {'code', 'trace_id'} and isinstance(error['code'], str) and re.fullmatch(r'[A-Z_]{1,64}', error['code']) and identifier(error['trace_id']):
                    known = error
        except (OSError, PublisherError):
            # 损坏错误正文不能证明请求未提交；使用下面的未知结果分支。
            known = None
        finally:
            failure.close()
        raise PublisherError(known['code'] if known else 'REQUEST_REJECTED', known['trace_id'] if known else None, failure.code >= 500 or known is None) from None
    except (OSError, urllib.error.URLError, http.client.HTTPException):
        raise PublisherError('DEPENDENCY_UNAVAILABLE', uncertain=True) from None


def access_token(config):
    """机器Token仅保留在函数调用内存，不持久化、打印或提供给前端。"""
    response = post(config['issuer'] + '/api/login/oauth/access_token', {'grant_type': 'client_credentials', 'client_id': config['client_id'], 'client_secret': config['client_secret'], 'scope': 'openid'}, config['timeout_seconds'], form=True, maximum=65536)
    value = response.get('access_token')
    require(isinstance(value, str) and 0 < len(value) <= 32768 and not any(c.isspace() for c in value), 'AUTHENTICATION_FAILED')
    require(isinstance(response.get('token_type'), str) and response['token_type'].lower() == 'bearer' and type(response.get('expires_in')) is int and 0 < response['expires_in'] <= 300, 'AUTHENTICATION_FAILED')
    return value


@contextlib.contextmanager
def checkpoint_lock(path):
    """一个检查点只允许一个执行者；不会无界排队或覆盖其他任务。"""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not path.parent.is_symlink() and stat.S_IMODE(path.parent.stat().st_mode) == 0o700)
    descriptor = os.open(str(path) + '.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        require(stat.S_IMODE(os.fstat(descriptor).st_mode) == 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PublisherError('STATE_CONFLICT') from None
        yield
    finally:
        os.close(descriptor)


def save(path, state):
    """先fsync完整状态再原子替换，未知结果不能只留在进程内存。"""
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.writing')
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(encoded(state))
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def validate_report(report, state, config):
    """只接受本次准确候选与固定身份的报告；风险报告不能伪装可执行票据。"""
    fields(report, {'target', 'publisher_id', 'delegation_id', 'candidate_hash', 'eligibility', 'preview', 'ticket'}, {'target', 'publisher_id', 'delegation_id', 'candidate_hash', 'eligibility', 'preview', 'ticket'}, 'PROTOCOL_INVALID')
    require(report['target'] == {'target_instance_id': config['target_instance_id'], 'environment': config['environment']} and report['publisher_id'] == config['publisher_id'] and report['delegation_id'] == config['delegation_id'], 'PROTOCOL_INVALID')
    require(isinstance(report['candidate_hash'], str) and re.fullmatch(r'[a-f0-9]{64}', report['candidate_hash']), 'PROTOCOL_INVALID')
    preview, candidate = report['preview'], state['source']['candidate']
    fields(preview, PREVIEW_FIELDS, PREVIEW_FIELDS, 'PROTOCOL_INVALID')
    require(preview['application'] == config['application_id'] and type(preview['current_version']) is int and type(preview['proposed_version']) is int and preview['current_version'] < preview['proposed_version'] == candidate['manifest']['manifest_version'] and preview['publishable'] is True, 'PROTOCOL_INVALID')
    for key in ('content_hash', 'presentation_hash'):
        require(isinstance(preview[key], str) and re.fullmatch(r'[a-f0-9]{64}', preview[key]), 'PROTOCOL_INVALID')
    for key in ('added', 'retained', 'affected_capabilities'):
        require(isinstance(preview[key], list) and len(preview[key]) <= (400 if key == 'affected_capabilities' else 200) and all(code(c) for c in preview[key]), 'PROTOCOL_INVALID')
    require(isinstance(preview['menu_changes'], list) and len(preview['menu_changes']) <= 200 and isinstance(preview['violations'], list) and not preview['violations'], 'PROTOCOL_INVALID')
    require(report['eligibility'] in ('AUTOMATION_ALLOWED', 'REQUIRES_OWNER_REVIEW'), 'PROTOCOL_INVALID')
    if report['eligibility'] == 'REQUIRES_OWNER_REVIEW':
        require(report['ticket'] is None, 'PROTOCOL_INVALID')
        return
    ticket = report['ticket']
    allowed = {'preview_id', 'expires_at', 'base_version', 'base_content_hash', 'base_presentation_hash', 'candidate', 'preview', 'impact'}
    fields(ticket, allowed, allowed, 'PROTOCOL_INVALID')
    require(identifier(ticket['preview_id']) and ticket['candidate'] == candidate and ticket['preview'] == preview and ticket['impact'] is None and type(ticket['base_version']) is int and ticket['base_version'] == preview['current_version'], 'PROTOCOL_INVALID')
    require(isinstance(ticket['expires_at'], str), 'PROTOCOL_INVALID')
    try:
        require(datetime.datetime.fromisoformat(ticket['expires_at'].replace('Z', '+00:00')).utcoffset() == datetime.timedelta(0), 'PROTOCOL_INVALID')
    except ValueError:
        raise PublisherError('PROTOCOL_INVALID') from None
    for key in ('base_content_hash', 'base_presentation_hash'):
        require(ticket[key] is None if ticket['base_version'] == 0 else isinstance(ticket[key], str) and re.fullmatch(r'[a-f0-9]{64}', ticket[key]), 'PROTOCOL_INVALID')


def validate_receipt(receipt, state, config):
    """固定双摘要、来源、原差异和实际SERVICE均核对后才标PUBLISHED。"""
    fields(receipt, {'release', 'actor'}, {'release', 'actor'}, 'PROTOCOL_INVALID')
    report, candidate = state['report'], state['source']['candidate']
    ticket, preview = report['ticket'], report['preview']
    release, actor = receipt['release'], receipt['actor']
    fields(release, {'application', 'version', 'content_hash', 'presentation_hash', 'published_by', 'published_at', 'source', 'reason', 'decision', 'command_id', 'base_version', 'base_content_hash', 'base_presentation_hash', 'preview'}, {'application', 'version', 'content_hash', 'presentation_hash', 'published_by', 'published_at', 'source', 'reason', 'decision', 'command_id', 'base_version', 'base_content_hash', 'base_presentation_hash', 'preview'}, 'PROTOCOL_INVALID')
    require(release['application'] == config['application_id'] and type(release['version']) is int and release['version'] == candidate['manifest']['manifest_version'] and release['published_by'] == config['service_principal'] and release['command_id'] == state['command_id'], 'PROTOCOL_INVALID')
    require(release['source'] == candidate['source'] and release['reason'] == candidate['reason'] and release['decision'] is None and release['preview'] == preview, 'PROTOCOL_INVALID')
    require(isinstance(release['published_at'], str) and type(release['base_version']) is int, 'PROTOCOL_INVALID')
    for key in ('content_hash', 'presentation_hash'):
        require(release[key] == preview[key], 'PROTOCOL_INVALID')
    for key in ('base_version', 'base_content_hash', 'base_presentation_hash'):
        require(release[key] == ticket[key], 'PROTOCOL_INVALID')
    fields(actor, {'kind', 'service_principal', 'owner_principal', 'delegation_id', 'publisher_id', 'target_instance_id', 'environment'}, {'kind', 'service_principal', 'owner_principal', 'delegation_id', 'publisher_id', 'target_instance_id', 'environment'}, 'PROTOCOL_INVALID')
    require(actor['kind'] == 'SERVICE' and actor['service_principal'] == config['service_principal'] and identifier(actor['owner_principal']) and actor['delegation_id'] == config['delegation_id'] and actor['publisher_id'] == config['publisher_id'] and actor['target_instance_id'] == config['target_instance_id'] and actor['environment'] == config['environment'], 'PROTOCOL_INVALID')


def execute(stage, config, path, candidate_path=None, artifact_path=None, commit=None):
    """三个阶段保留同一原命令；只读恢复不依赖当前源码文件仍存在。"""
    with checkpoint_lock(path):
        if stage == Stage.PREVIEW:
            require(not path.exists() and not path.is_symlink(), 'STATE_CONFLICT')
            require(candidate_path and artifact_path and commit)
            frozen = source(candidate_path, artifact_path, commit, config)
            state = {'schema_version': '1', 'target': binding(config), 'source': frozen, 'command_id': str(uuid.uuid4()), 'state': PublicationState.PREVIEW_PENDING, 'projection_status': 'UNKNOWN', 'runtime_status': 'UNKNOWN'}
            save(path, state)
            token = access_token(config)
            body = {'target_instance_id': config['target_instance_id'], 'environment': config['environment'], **{key: frozen['candidate'][key] for key in ('manifest', 'source', 'reason')}}
            report = post(config['base_url'] + '/api/catalog-publisher/v1/preview', body, config['timeout_seconds'], token)
            validate_report(report, state, config)
            state.update(report=report, state=PublicationState.PREVIEWED if report['eligibility'] == 'AUTOMATION_ALLOWED' else PublicationState.REQUIRES_OWNER_REVIEW)
            save(path, state)
            return state
        state = strict_json(read_file(path, MAX_CHECKPOINT_BYTES, True))
        require(state.get('schema_version') == '1' and state.get('target') == binding(config) and identifier(state.get('command_id')), 'TARGET_CHANGED')
        require(state.get('state') != PublicationState.REQUIRES_OWNER_REVIEW, 'REQUIRES_OWNER_REVIEW')
        require(state.get('state') in (PublicationState.PREVIEWED, PublicationState.PUBLISHING_UNKNOWN, PublicationState.PUBLISHED), 'STATE_CONFLICT')
        validate_report(state['report'], state, config)
        require(state['report']['eligibility'] == 'AUTOMATION_ALLOWED', 'REQUIRES_OWNER_REVIEW')
        body = {'target_instance_id': config['target_instance_id'], 'environment': config['environment'], 'command_id': state['command_id']}
        if stage == Stage.PUBLISH:
            require(config['publish_enabled'], 'ACCESS_DENIED')
            require(candidate_path and artifact_path and commit)
            require(source(candidate_path, artifact_path, commit, config) == state['source'], 'SOURCE_CHANGED')
            body['preview_id'] = state['report']['ticket']['preview_id']
        else:
            require(stage == Stage.RECOVER and state['state'] in (PublicationState.PUBLISHING_UNKNOWN, PublicationState.PUBLISHED), 'STATE_CONFLICT')
        token = access_token(config)
        previous_state = state['state']
        if stage == Stage.PUBLISH:
            state['state'] = PublicationState.PUBLISHING_UNKNOWN
            save(path, state)
        try:
            receipt = post(config['base_url'] + '/api/catalog-publisher/v1/' + ('publish' if stage == Stage.PUBLISH else 'receipt'), body, config['timeout_seconds'], token)
            validate_receipt(receipt, state, config)
        except PublisherError as failure:
            # 5xx、断连、损坏响应和查询404都不能证明原发布未提交。
            if stage == Stage.PUBLISH and not failure.uncertain and failure.code != 'PROTOCOL_INVALID':
                # 重试被拒绝只证明本次未执行，不能推翻之前未知或已核验的提交事实。
                state['state'] = PublicationState.REJECTED if previous_state == PublicationState.PREVIEWED else previous_state
            state['last_error'] = {'code': failure.code, 'trace_id': failure.trace_id}
            save(path, state)
            raise
        state.update(state=PublicationState.PUBLISHED, receipt=receipt)
        state.pop('last_error', None)
        save(path, state)
        return state


def main(argv=None):
    """终端只显示关联字段；完整非凭据报告保存到私密检查点。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=tuple(stage.value for stage in Stage), nargs='?', default=Stage.PREVIEW.value)
    parser.add_argument('--target', required=True)
    parser.add_argument('--checkpoint', required=True)
    parser.add_argument('--candidate')
    parser.add_argument('--source-artifact')
    parser.add_argument('--commit')
    args = parser.parse_args(argv)
    try:
        result = execute(args.stage, target(args.target), Path(args.checkpoint).absolute(), args.candidate, args.source_artifact, args.commit)
        print(json.dumps({'directory_publication': result['state'], 'state': result['state'], 'command_id': result['command_id'], 'candidate_hash': result['report']['candidate_hash'], 'projection_status': result['projection_status'], 'runtime_status': result['runtime_status']}))
        return 0
    except PublisherError as failure:
        print(json.dumps({'code': failure.code, 'trace_id': failure.trace_id}), file=sys.stderr)
        return 3 if failure.uncertain or failure.code == 'PROTOCOL_INVALID' else 2
    except (OSError, ValueError, KeyError, TypeError):
        print('{"code":"INVALID_ARGUMENT"}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
