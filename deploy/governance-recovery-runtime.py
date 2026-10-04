#!/usr/bin/env python3
"""MG19只在已验证自有MG18专库演练；断开客户端代理，不停止共享IdP或授权图。"""
import argparse
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
ADMIN_PORT, IO_TIMEOUT, MAX_RESPONSE = 21830, 10, 10 * 1024 * 1024
NORMAL, IDP_OFF, INTROSPECTION_OFF, JWKS_OFF, GRAPH_OFF = 'NORMAL', 'IDP_OFF', 'INTROSPECTION_OFF', 'JWKS_OFF', 'GRAPH_OFF'


def module(name, filename):
    """复用既有受控启动和真实PKCE工具，不增加产品故障钩子。"""
    spec = importlib.util.spec_from_file_location(name, ROOT / 'deploy' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def main():
    """只持有自有进程和回环代理，所有失败／检查点保留，退出不撤销业务效果。"""
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', required=True, type=Path, help='已通过MG18的自有专库证据目录')
    args = parser.parse_args()
    baseline = args.baseline.resolve()
    assert baseline.parent == ROOT / '.local/menu-role-governance' and re.fullmatch(r'mg18-http-[a-f0-9]{12}', baseline.name)
    h = module('mg19_context', 'governance-context-smoke.py'); h.ISSUER = 'http://localhost:18094'
    result = json.loads(h.read_private(baseline / 'http-result.json'))
    assert result['status'] == 'PASS' and result['original_business_grant_writes'] == 0
    jar = ROOT / 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar'
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == result['jar_sha256']
    browser = json.loads(h.read_private(baseline / 'browser-result.json'))
    creation = next(x['body'] for x in browser['requests'] if x['method'] == 'POST')
    tenant, app, env = (creation[k] for k in ('tenant_id', 'application_id', 'environment'))
    uuid.UUID(tenant); assert re.fullmatch(r'mg18-[a-f0-9]{12}', app) and env == 'test'
    database = json.loads(h.read_private(baseline / 'database/database.json'))['database']
    assert re.fullmatch(r'auth_gov_p1_test_[a-z0-9_]+', database)
    run = ROOT / '.local/menu-role-governance' / ('mg19-recovery-' + uuid.uuid4().hex[:12]); run.mkdir(mode=0o700)
    state, proxy_records, checks = [NORMAL], [], []
    allowed = {('localhost', 18094), ('127.0.0.1', 18544)}

    class FaultProxy(BaseHTTPRequestHandler):
        """代理只允许两项既有依赖；故障直接断TCP，绝不伪造HTTP错误或认证事实。"""
        def log_message(self, *_): pass
        def do_GET(self): self.forward()
        def do_POST(self): self.forward()
        def forward(self):
            url = urllib.parse.urlsplit(self.path)
            if (url.hostname, url.port) not in allowed: self.send_error(403); return
            mode = state[0]
            disconnected = url.port == 18094 and (mode == IDP_OFF or mode == INTROSPECTION_OFF and url.path.endswith('/introspect') or mode == JWKS_OFF and url.path.endswith('/jwks')) or url.port == 18544 and mode == GRAPH_OFF
            record = dict(mode=mode, port=url.port, path=url.path, disconnected=disconnected)
            proxy_records.append(record)
            if disconnected:
                self.close_connection = True
                try: self.connection.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                self.connection.close(); return
            connection = http.client.HTTPConnection(url.hostname, url.port, timeout=IO_TIMEOUT)
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= MAX_RESPONSE: self.send_error(413); return
                body = self.rfile.read(length) if length else None
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection', 'proxy-connection', 'content-length')}
                connection.request(self.command, url.path + ('?' + url.query if url.query else ''), body, headers)
                response = connection.getresponse(); data = response.read(MAX_RESPONSE + 1)
                if len(data) > MAX_RESPONSE: raise RuntimeError('bounded upstream response exceeded')
                record['actual_upstream_status'] = response.status
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in ('connection', 'transfer-encoding', 'content-length'): self.send_header(key, value)
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            finally: connection.close()

    proxy = ThreadingHTTPServer(('127.0.0.1', 0), FaultProxy)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    process, drop_server = None, None
    config = h.read_private(baseline / 'admin.properties')
    h.private(run / 'admin.properties', config)

    def pg(sql):
        reply = subprocess.run(['docker', 'exec', '-i', 'dev-infra-postgres16-1', 'sh', '-c', 'exec psql -U "$POSTGRES_USER" -d ' + database + ' -v ON_ERROR_STOP=1 -At'], input=sql, text=True, capture_output=True, timeout=IO_TIMEOUT)
        if reply.returncode: raise RuntimeError('owned SQL assertion failed')
        return reply.stdout.strip()
    def snapshot(table, predicate):
        return pg('SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),\'[]\'::jsonb)::text FROM auth_governance.' + table + ' t WHERE ' + predicate + ';')
    partition = "tenant_id='" + tenant + "' AND application_id='" + app + "' AND environment='test'"
    protected = {table: snapshot(table, partition) for table in ('role_version', 'access_grant', 'access_review_task', 'access_review_item', 'access_delegation')}
    old_manifest = snapshot('application_manifest', "application_id='" + app + "'")
    assert len(json.loads(protected['access_grant'])) == 4
    assert pg("SELECT count(*) FROM auth_governance.access_grant WHERE " + partition + " AND state='ACTIVE' AND valid_to>clock_timestamp();") == '3'
    def start(label):
        saved = os.environ.get('JAVA_TOOL_OPTIONS')
        os.environ['JAVA_TOOL_OPTIONS'] = (saved + ' ' if saved else '') + '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=' + str(proxy.server_port) + ' -Dhttp.nonProxyHosts='
        try: return h.start(jar, ADMIN_PORT, run / (label + '.log'), config=run / 'admin.properties', access=True, presentation=True, scope=True)
        finally:
            if saved is None: os.environ.pop('JAVA_TOOL_OPTIONS', None)
            else: os.environ['JAVA_TOOL_OPTIONS'] = saved
    fixture = json.loads(h.read_private(ROOT / '.local/menu-role-governance/mg12-idp/identity/casdoor.json'))
    auth = module('mg19_auth', 'governance-access-smoke.py')
    owner = auth.token(h.ISSUER, fixture, 'management', 'internal')
    member = auth.token(h.ISSUER, fixture, 'management', 'external')
    def request(name, path, body=None, status=200, code=None, token=owner, port=ADMIN_PORT):
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=IO_TIMEOUT)
        try:
            headers = {'Authorization': 'Bearer ' + token} if token else {}
            if body is not None: headers['Content-Type'] = 'application/json'
            connection.request('POST' if body is not None else 'GET', '/api/governance/v1' + path, None if body is None else json.dumps(body), headers)
            response = connection.getresponse(); raw = response.read(MAX_RESPONSE + 1)
            assert len(raw) <= MAX_RESPONSE
            value = json.loads(raw) if raw else {}
            if response.status != status or code and (value.get('code') != code or set(value) != {'code', 'trace_id'}): raise RuntimeError('HTTP assertion failed: ' + name + ' status=' + str(response.status))
            checks.append(dict(check=name, result='PASS')); h.private(run / ('checks-' + str(len(checks)) + '.json'), json.dumps(checks))
            return value
        finally: connection.close()
    query = '?' + urllib.parse.urlencode(dict(tenant_id=tenant, application_id=app, environment=env))
    review_path = '/access/reviews/' + browser['task_id'] + query
    access_path = '/me/access' + query
    def fail_dependency(mode, path, token=owner):
        before = len(proxy_records); state[0] = mode
        request(mode + ' actual TCP disconnect refuses HTTP', path, status=503, code='DEPENDENCY_UNAVAILABLE', token=token)
        assert any(r['disconnected'] for r in proxy_records[before:])
        state[0] = NORMAL
        return request(mode + ' actual dependency restored', path, token=token)
    try:
        process = start('admin-first')
        task = request('persisted review task restored without decisions', review_path)
        current = request('actual graph allows remaining owned sources', access_path, token=member)
        assert current['capability_hints'] == [app + '.read']
        assert fail_dependency(IDP_OFF, review_path)['id'] == task['id']
        assert fail_dependency(INTROSPECTION_OFF, review_path)['id'] == task['id']
        assert fail_dependency(GRAPH_OFF, access_path, member)['capability_hints'] == current['capability_hints']
        h.stop(process); state[0] = JWKS_OFF; process = start('admin-fresh-jwks')
        assert fail_dependency(JWKS_OFF, review_path)['id'] == task['id']
        request('restored ordinary member remains refused', review_path, status=403, code='ACCESS_DENIED', token=member)
        request('anonymous remains refused after recovery', review_path, status=401, code='INVALID_CREDENTIAL', token=None)
        request('cross partition remains refused after recovery', '/access/reviews/' + browser['task_id'] + '?' + urllib.parse.urlencode(dict(tenant_id=str(uuid.uuid4()), application_id=app, environment=env)), status=403, code='MEMBERSHIP_UNAVAILABLE')
        # 仅自有专库应用从v1向v2/v3推进。没有改写原Commerce目录或旧快照。
        request('owned application guarded writer gate', '/catalog/enable-guard', dict(application_id=app, command_id=str(uuid.uuid4()), expected_version=0, legacy_writers_exited=True, reason='MG19自有专库没有其他目录写节点'))
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        def candidate(version, label):
            manifest = dict(schema_version='1', application=app, manifest_version=version, capabilities=[dict(code=app + '.read', resource_type='store', risk_level='NORMAL')], menus=[dict(code='recovery-group', parent=None, route=None, any_of=[], label=label, position=1)])
            return dict(manifest=manifest, source=dict(commit=commit, artifact_hash=hashlib.sha256(json.dumps(manifest).encode()).hexdigest()), reason=None, decision=None, impact=None)
        frozen = candidate(2, '待修正名称'); preview = request('current guarded API freezes owned v2', '/catalog/release-preview', frozen)
        command = dict(command_id=str(uuid.uuid4()), preview_id=preview['preview_id'])
        checkpoint = dict(state='PUBLISHING_UNKNOWN', partition=dict(tenant_id=tenant, application_id=app, environment=env), source=frozen, command=command)
        h.private(run / 'publication-checkpoint.json', json.dumps(checkpoint, ensure_ascii=False))
        actual_commit = []
        class DropCommittedResponse(BaseHTTPRequestHandler):
            """实际提交成功后断开响应，检查点固定原命令供新进程恢复。"""
            def log_message(self, *_): pass
            def do_POST(self):
                assert self.path == '/api/governance/v1/catalog/release-publish'
                length = int(self.headers.get('Content-Length', '0')); assert 0 < length <= 1024
                body = json.loads(self.rfile.read(length)); assert body == command
                value = request('actual v2 committed before response disconnect', '/catalog/release-publish', body)
                actual_commit.append(value); self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close()
        drop_server = ThreadingHTTPServer(('127.0.0.1', 0), DropCommittedResponse)
        threading.Thread(target=drop_server.serve_forever, daemon=True).start()
        try:
            request('publication unknown outcome', '/catalog/release-publish', command, port=drop_server.server_port)
            raise RuntimeError('committed response did not disconnect')
        except http.client.RemoteDisconnected: pass
        assert len(actual_commit) == 1
        assert pg("SELECT count(*) FROM auth_governance.catalog_release WHERE application_id='" + app + "' AND command_id='" + command['command_id'] + "';") == '1'
        previous_pid = process.pid; h.stop(process); process = start('admin-publication-resume')
        restored = json.loads(h.read_private(run / 'publication-checkpoint.json'))
        recovered = request('new Admin process resumes original publication command', '/catalog/release-publish', restored['command'])
        assert recovered == actual_commit[0] and previous_pid != process.pid
        v2 = snapshot('application_manifest', "application_id='" + app + "' AND version=2")
        corrected = request('higher correction version frozen', '/catalog/release-preview', candidate(3, '修正后的目录名称'))
        request('higher correction v3 commits without grant compensation', '/catalog/release-publish', dict(command_id=str(uuid.uuid4()), preview_id=corrected['preview_id']))
        assert request('original v2 replay cannot regress current v3', '/catalog/release-publish', command) == recovered
        request('lower version fresh preview rejected', '/catalog/release-preview', frozen, status=409, code='VERSION_CONFLICT')
        assert pg("SELECT manifest_version FROM auth_governance.application_catalog WHERE application_id='" + app + "';") == '3'
        assert snapshot('application_manifest', "application_id='" + app + "' AND version=1") == old_manifest
        assert snapshot('application_manifest', "application_id='" + app + "' AND version=2") == v2
        assert all(snapshot(table, partition) == before for table, before in protected.items())
        assert pg("SELECT count(*) FROM auth_governance.catalog_release WHERE application_id='" + app + "' AND command_id='" + command['command_id'] + "';") == '1'
        request('review unchanged after correction publication and restart', review_path)
        checks.append(dict(check='roles, Grants, review checkpoints, delegations and v1/v2 historical bytes unchanged', result='PASS'))
        h.private(run / 'result.json', json.dumps(dict(status='PASS', checks=checks, baseline=str(baseline), jar_sha256=result['jar_sha256'], proxy_records=proxy_records, owned_partition=dict(tenant_id=tenant, application_id=app, environment=env), publication_versions=[2, 3], original_business_grant_writes=0, owned_grant_writes=0, publication_restart=dict(old_pid=previous_pid, new_pid=process.pid), protected_sha256={t: hashlib.sha256(v.encode()).hexdigest() for t, v in protected.items()}, real_oa_engine='NOT_CONNECTED'), ensure_ascii=False, indent=2))
        print('PASS: MG19 actual recovery checks=' + str(len(checks)) + '; evidence=' + str(run))
    finally:
        h.private(run / 'proxy-records.json', json.dumps(proxy_records, indent=2))
        for owned in h.PROCESSES: h.stop(owned)
        for owned in (drop_server, proxy):
            if owned is not None: owned.shutdown(); owned.server_close()


if __name__ == '__main__': main()
