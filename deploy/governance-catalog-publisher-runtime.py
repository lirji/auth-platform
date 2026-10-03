#!/usr/bin/env python3
"""MG11实际固定Commerce提交和机器客户端演练，复用MG10两新专用库，不修改原部署。"""
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http import HTTPStatus

ROOT = Path(__file__).resolve().parents[1]


def rehearsal(h, run, target, owner_token, pg, check):
    """丢失的是实际已提交API响应；查询真实PG证明只提交一次，而非Mock事务。"""
    directory = run / 'ci-client'
    directory.mkdir(mode=0o700)
    client = target['clients'][3]
    commit = target['ci_source']['commit']
    artifact = directory / 'declaration.json'
    h.private(artifact, target['ci_source']['artifact'].decode())
    candidates = {}
    for version in (1, 2, 3, 4):
        path = directory / ('candidate-' + str(version) + '.json')
        subprocess.run(['node', 'frontend/scripts/export-menu-catalog.mjs', '--commit', commit, '--version', str(version), '--output', str(path)], cwd=ROOT.parent / 'commerce-platform', check=True, stdout=subprocess.DEVNULL, timeout=10)
        candidates[version] = path
    config = {'base_url': '', 'target_instance_id': target['instance'], 'environment': target['environment'], 'application_id': 'commerce', 'publisher_id': client['publisher'], 'delegation_id': client['delegation']['delegation_id'], 'service_principal': client['principal'], 'issuer': 'http://localhost:18090', 'client_id': client['name'], 'client_secret': client['secret']}
    checks = []
    drop = [False]

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers['Content-Length']))
            status, result = h.request(target['port'], self.path, [('Authorization', self.headers['Authorization'])], body)
            if self.path.endswith('/publish') and drop[0] and status == HTTPStatus.OK:
                drop[0] = False
                # 上游200在真实事务提交后收到，再丢给客户端；不是在提交前模拟失败。
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            raw = json.dumps(result).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    config['base_url'] = 'http://127.0.0.1:' + str(server.server_port)
    config_path = directory / 'target.json'
    h.private(config_path, json.dumps(config))

    def command(name, stage, checkpoint, version=None, expected=0, expected_code=None, expected_commit=None):
        args = ['python3', 'deploy/governance-catalog-publisher.py', stage, '--target', str(config_path), '--checkpoint', str(checkpoint)]
        if version is not None:
            args += ['--candidate', str(candidates[version]), '--source-artifact', str(artifact), '--commit', expected_commit or commit]
        response = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=20)
        h.private(directory / ('command-' + uuid.uuid4().hex[:8] + '.json'), json.dumps({'check': name, 'exit': response.returncode, 'stdout': response.stdout, 'stderr': response.stderr}))
        if response.returncode != expected:
            raise RuntimeError('real client unexpected exit: ' + name)
        reply = json.loads(response.stdout if expected == 0 else response.stderr)
        if expected_code and reply.get('code') != expected_code:
            raise RuntimeError('real client unexpected code: ' + name)
        if client['secret'] in response.stdout + response.stderr:
            raise RuntimeError('client leaked authentication secret')
        checks.append({'check': name, 'result': 'PASS'})
        return reply

    try:
        check('CI Owner guards actual Commerce declaration on owned database', target, '/api/governance/v1/catalog/enable-guard', owner_token, {'application_id': 'commerce', 'command_id': str(uuid.uuid4()), 'expected_version': 0, 'legacy_writers_exited': True, 'reason': 'CI隔离目标不存在其他写节点'})
        checkpoint = directory / 'dry-run.json'
        reply = command('fixed commit dry-run previews actual Commerce declaration', 'preview', checkpoint, 2)
        if reply['state'] != 'PREVIEWED' or reply['projection_status'] != 'UNKNOWN' or reply['runtime_status'] != 'UNKNOWN':
            raise RuntimeError('client claimed unverified readiness')
        command('default false publication flag sends no publish', 'publish', checkpoint, 2, 2, 'ACCESS_DENIED')
        config['publish_enabled'] = True
        config_path.write_text(json.dumps(config))
        original = candidates[2].read_bytes()
        candidates[2].write_bytes(original + b'\n')
        command('candidate replacement after preview rejected', 'publish', checkpoint, 2, 2, 'SOURCE_CHANGED')
        candidates[2].write_bytes(original)
        original_artifact = artifact.read_bytes()
        artifact.write_bytes(original_artifact + b'\n')
        command('artifact replacement after preview rejected', 'publish', checkpoint, 2, 2, 'SOURCE_CHANGED')
        artifact.write_bytes(original_artifact)
        command('wrong fixed commit rejected', 'publish', checkpoint, 2, 2, 'SOURCE_CHANGED', 'a' * 40)
        published = command('explicit isolated CI client publishes fixed candidate', 'publish', checkpoint, 2)
        repeated = command('original command retries same publication', 'publish', checkpoint, 2)
        if repeated != published:
            raise RuntimeError('original command facts changed')
        command('fresh process recovers original fixed receipt', 'recover', checkpoint)
        state = json.loads(checkpoint.read_text())
        if pg(target, "SELECT count(*) FROM auth_governance.catalog_release WHERE application_id='commerce' AND command_id='" + state['command_id'] + "';") != '1':
            raise RuntimeError('original command committed more than once')
        stale = directory / 'stale.json'
        command('freeze next CI candidate before newer Owner publication', 'preview', stale, 3)
        owner_candidate = json.loads(candidates[3].read_text())
        ticket = check('Owner independently publishes next fixed declaration', target, '/api/governance/v1/catalog/release-preview', owner_token, {'manifest': owner_candidate['manifest'], 'source': owner_candidate['source'], 'reason': None, 'decision': None, 'impact': None})
        check('Owner next declaration commits on real PG', target, '/api/governance/v1/catalog/release-publish', owner_token, {'command_id': str(uuid.uuid4()), 'preview_id': ticket['preview_id']})
        command('stale fixed basis is explicitly rejected', 'publish', stale, 3, 2, 'VERSION_CONFLICT')
        lost = directory / 'response-lost.json'
        command('freeze next candidate for actual response loss', 'preview', lost, 4)
        drop[0] = True
        command('actual committed publish response lost preserves UNKNOWN', 'publish', lost, 4, 3, 'DEPENDENCY_UNAVAILABLE')
        state = json.loads(lost.read_text())
        if state['state'] != 'PUBLISHING_UNKNOWN' or pg(target, "SELECT count(*) FROM auth_governance.catalog_release WHERE application_id='commerce' AND command_id='" + state['command_id'] + "';") != '1':
            raise RuntimeError('response-loss fixture did not commit exactly once')
        command('recover actual committed response using only checkpoint', 'recover', lost)
        command('retry original command after actual lost response stays idempotent', 'publish', lost, 4)
        config['environment'] = 'staging'
        config_path.write_text(json.dumps(config))
        command('wrong environment cannot take over stored command', 'publish', lost, 4, 2, 'TARGET_CHANGED')
        config['environment'] = 'test'
        config_path.write_text(json.dumps(config))
        command('regressed source version refused by real API', 'preview', directory / 'regressed.json', 1, 2, 'VERSION_CONFLICT')
        for path in (checkpoint, stale, lost):
            raw = path.read_text()
            if client['secret'] in raw or 'client_secret' in raw or 'access_token' in raw or 'Bearer ' in raw:
                raise RuntimeError('checkpoint leaked credentials')
        h.private(directory / 'result.json', json.dumps({'status': 'PASS', 'checks': checks, 'fixed_commit': commit, 'source_artifact_sha256': __import__('hashlib').sha256(original_artifact).hexdigest(), 'actual_committed_response_loss': True, 'runtime_status': 'UNKNOWN', 'projection_status': 'UNKNOWN'}, ensure_ascii=False, indent=2))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == '__main__':
    spec = importlib.util.spec_from_file_location('publisher_owned_runtime', ROOT / 'deploy/governance-publisher-runtime.py')
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    runtime.main(client_rehearsal=rehearsal)
