"""客户端协议测试使用本机HTTP夹具；不将夹具当作真实IdP、权限或数据库验收。"""
import copy
import datetime
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SPEC = importlib.util.spec_from_file_location('catalog_publisher_client', Path(__file__).resolve().parents[1] / 'governance-catalog-publisher.py')
CLIENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLIENT)


class PublisherClientTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='publisher-protocol-')
        self.root = Path(self.directory.name)
        self.calls = {'token': 0, 'preview': 0, 'publish': 0, 'receipt': 0}
        self.committed = {}
        self.drop, self.corrupt, self.status, self.wrong_preview, self.truncate = False, False, None, False, False
        self.owner_review = False
        self.secret, self.token = 'fixture-private-secret', 'fixture-memory-only-token'
        self.config = {'base_url': '', 'target_instance_id': str(uuid.uuid4()), 'environment': 'test', 'application_id': 'shop', 'publisher_id': str(uuid.uuid4()), 'delegation_id': str(uuid.uuid4()), 'service_principal': str(uuid.uuid4()), 'issuer': '', 'client_id': 'fixed-client', 'client_secret': self.secret}
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, value, status=200):
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                raw = self.rfile.read(int(self.headers['Content-Length']))
                if self.path.endswith('access_token'):
                    outer.calls['token'] += 1
                    self.reply({'access_token': outer.token, 'token_type': 'Bearer', 'expires_in': 300})
                    return
                operation = self.path.rsplit('/', 1)[-1]
                outer.calls[operation] += 1
                body = json.loads(raw)
                if operation == 'preview':
                    candidate = {'manifest': body['manifest'], 'source': body['source'], 'reason': body['reason'], 'decision': None}
                    preview = {'application': 'shop', 'current_version': 1, 'proposed_version': 999 if outer.wrong_preview else 2, 'content_hash': 'a' * 64, 'added': [], 'retained': ['shop.read'], 'presentation_hash': 'b' * 64, 'menu_changes': [], 'violations': [], 'publishable': True, 'affected_capabilities': []}
                    ticket = {'preview_id': str(uuid.uuid4()), 'expires_at': (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=10)).isoformat().replace('+00:00', 'Z'), 'base_version': 1, 'base_content_hash': 'c' * 64, 'base_presentation_hash': 'd' * 64, 'candidate': candidate, 'preview': preview, 'impact': None}
                    outer.report = {'target': {'target_instance_id': outer.config['target_instance_id'], 'environment': 'test'}, 'publisher_id': outer.config['publisher_id'], 'delegation_id': outer.config['delegation_id'], 'candidate_hash': 'e' * 64, 'eligibility': 'AUTOMATION_ALLOWED', 'preview': preview, 'ticket': ticket}
                    if outer.owner_review:
                        outer.report.update(eligibility='REQUIRES_OWNER_REVIEW', ticket=None)
                        preview['affected_capabilities'] = ['shop.cap-' + str(i) for i in range(201)]
                    self.reply(outer.report)
                    return
                if outer.status is not None:
                    self.reply({'code': 'DEPENDENCY_UNAVAILABLE', 'trace_id': str(uuid.uuid4())}, outer.status)
                    return
                if operation == 'receipt' and body['command_id'] not in outer.committed:
                    self.reply({'code': 'NOT_FOUND', 'trace_id': str(uuid.uuid4())}, 404)
                    return
                if operation == 'publish' and body['command_id'] not in outer.committed:
                    report = outer.report
                    release = {'application': 'shop', 'version': 2, 'content_hash': 'a' * 64, 'presentation_hash': 'b' * 64, 'published_by': outer.config['service_principal'], 'published_at': datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z'), 'source': report['ticket']['candidate']['source'], 'reason': None, 'decision': None, 'command_id': body['command_id'], 'base_version': 1, 'base_content_hash': 'c' * 64, 'base_presentation_hash': 'd' * 64, 'preview': report['preview']}
                    actor = {'kind': 'SERVICE', 'service_principal': outer.config['service_principal'], 'owner_principal': str(uuid.uuid4()), 'delegation_id': outer.config['delegation_id'], 'publisher_id': outer.config['publisher_id'], 'target_instance_id': outer.config['target_instance_id'], 'environment': 'test'}
                    outer.committed[body['command_id']] = {'release': release, 'actor': actor}
                if operation == 'publish' and outer.drop:
                    outer.drop = False
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                receipt = copy.deepcopy(outer.committed[body['command_id']])
                if outer.corrupt:
                    receipt['actor']['kind'] = 'HUMAN'
                if outer.truncate:
                    self.send_response(200)
                    self.send_header('Transfer-Encoding', 'chunked')
                    self.end_headers()
                    self.wfile.write(b'10\r\n{')
                    self.close_connection = True
                    return
                self.reply(receipt)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        origin = 'http://127.0.0.1:' + str(self.server.server_port)
        self.config.update(base_url=origin, issuer=origin)
        self.config_file = self.root / 'target.json'
        self.write(self.config_file, self.config)
        self.commit = 'f' * 40
        declaration = {'schema_version': '1', 'application': 'shop', 'capabilities': [{'code': 'shop.read', 'resource_type': 'store', 'risk_level': 'NORMAL'}], 'menus': [{'code': 'stores', 'parent': None, 'route': '/stores', 'any_of': ['shop.read']}]}
        self.artifact = self.root / 'declaration.json'
        self.write(self.artifact, declaration)
        self.candidate = self.root / 'candidate.json'
        self.write(self.candidate, {'manifest': {**declaration, 'manifest_version': 2}, 'source': {'commit': self.commit, 'artifact_hash': CLIENT.digest(self.artifact.read_bytes())}, 'auto_roles': False, 'auto_grants': False})
        self.checkpoint = self.root / 'job.json'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def write(self, path, value):
        path.write_text(json.dumps(value))
        path.chmod(0o600)

    def execute(self, stage='preview', config=None, candidate=None, artifact=None):
        return CLIENT.execute(stage, config or CLIENT.target(self.config_file), self.checkpoint, candidate or self.candidate, artifact or self.artifact, self.commit)

    def enable(self):
        self.config['publish_enabled'] = True
        self.write(self.config_file, self.config)

    def error(self, expected, operation):
        with self.assertRaises(CLIENT.PublisherError) as failure:
            operation()
        self.assertEqual(expected, failure.exception.code)

    def test_default_dry_run_has_no_publish_and_disabled_publish_sends_no_token(self):
        state = self.execute()
        self.assertEqual('PREVIEWED', state['state'])
        self.error('ACCESS_DENIED', lambda: self.execute('publish'))
        self.assertEqual({'token': 1, 'preview': 1, 'publish': 0, 'receipt': 0}, self.calls)
        self.assertEqual('UNKNOWN', state['projection_status'])
        self.assertEqual('UNKNOWN', state['runtime_status'])

    def test_real_http_fixture_keeps_original_command_and_does_not_cache_token(self):
        first = self.execute()
        self.enable()
        published = self.execute('publish')
        self.assertEqual(first['command_id'], published['command_id'])
        self.assertEqual(published['receipt'], self.execute('publish')['receipt'])
        self.assertEqual(1, len(self.committed))
        self.assertEqual(3, self.calls['token'])

    def test_candidate_bytes_replacement_stops_before_authentication_or_publish(self):
        self.execute()
        self.enable()
        self.candidate.write_bytes(self.candidate.read_bytes() + b'\n')
        self.error('SOURCE_CHANGED', lambda: self.execute('publish'))
        self.assertEqual(1, self.calls['token'])
        self.assertEqual(0, self.calls['publish'])

    def test_artifact_replacement_or_wrong_commit_never_previews(self):
        self.artifact.write_bytes(self.artifact.read_bytes() + b'\n')
        self.error('SOURCE_CHANGED', self.execute)
        self.assertEqual(0, self.calls['token'])

    def test_target_or_delegation_change_cannot_take_over_checkpoint(self):
        self.execute()
        self.enable()
        self.config['delegation_id'] = str(uuid.uuid4())
        self.write(self.config_file, self.config)
        self.error('TARGET_CHANGED', lambda: self.execute('publish'))
        self.assertEqual(0, self.calls['publish'])

    def test_committed_response_loss_recovers_original_receipt_without_source_files(self):
        first = self.execute()
        self.enable()
        self.drop = True
        self.error('DEPENDENCY_UNAVAILABLE', lambda: self.execute('publish'))
        self.assertEqual('PUBLISHING_UNKNOWN', json.loads(self.checkpoint.read_text())['state'])
        recovered = self.execute('recover', candidate=self.root / 'absent.json', artifact=self.root / 'absent-artifact')
        self.assertEqual('PUBLISHED', recovered['state'])
        self.assertEqual(first['command_id'], recovered['receipt']['release']['command_id'])
        self.assertEqual(1, len(self.committed))

    def test_corrupt_actor_is_unknown_and_valid_original_receipt_can_recover(self):
        self.execute()
        self.enable()
        self.corrupt = True
        self.error('PROTOCOL_INVALID', lambda: self.execute('publish'))
        self.assertEqual('PUBLISHING_UNKNOWN', json.loads(self.checkpoint.read_text())['state'])
        self.corrupt = False
        self.assertEqual('PUBLISHED', self.execute('recover')['state'])

    def test_503_and_receipt_404_never_claim_uncommitted(self):
        self.execute()
        self.enable()
        self.status = 503
        self.error('DEPENDENCY_UNAVAILABLE', lambda: self.execute('publish'))
        self.status = None
        self.error('NOT_FOUND', lambda: self.execute('recover'))
        self.assertEqual('PUBLISHING_UNKNOWN', json.loads(self.checkpoint.read_text())['state'])

    def test_truncated_success_response_remains_unknown_and_recovers(self):
        self.execute()
        self.enable()
        self.truncate = True
        self.error('DEPENDENCY_UNAVAILABLE', lambda: self.execute('publish'))
        self.assertEqual('PUBLISHING_UNKNOWN', json.loads(self.checkpoint.read_text())['state'])
        self.truncate = False
        self.assertEqual('PUBLISHED', self.execute('recover')['state'])

    def test_rejected_retry_cannot_turn_previous_unknown_into_uncommitted(self):
        self.execute()
        self.enable()
        self.drop = True
        self.error('DEPENDENCY_UNAVAILABLE', lambda: self.execute('publish'))
        self.status = 403
        self.error('DEPENDENCY_UNAVAILABLE', lambda: self.execute('publish'))
        self.assertEqual('PUBLISHING_UNKNOWN', json.loads(self.checkpoint.read_text())['state'])
        self.status = None
        self.assertEqual('PUBLISHED', self.execute('recover')['state'])

    def test_rejected_retry_keeps_previously_verified_historical_receipt(self):
        self.execute()
        self.enable()
        receipt = self.execute('publish')['receipt']
        self.status = 403
        self.error('DEPENDENCY_UNAVAILABLE', lambda: self.execute('publish'))
        state = json.loads(self.checkpoint.read_text())
        self.assertEqual('PUBLISHED', state['state'])
        self.assertEqual(receipt, state['receipt'])

    def test_bad_preview_version_is_not_accepted_or_published(self):
        self.wrong_preview = True
        self.error('PROTOCOL_INVALID', self.execute)
        self.assertEqual('PREVIEW_PENDING', json.loads(self.checkpoint.read_text())['state'])
        self.assertEqual(0, self.calls['publish'])

    def test_owner_review_report_is_saved_and_no_machine_publish_is_attempted(self):
        self.owner_review = True
        state = self.execute()
        self.assertEqual('REQUIRES_OWNER_REVIEW', state['state'])
        self.assertIsNone(state['report']['ticket'])
        self.enable()
        self.error('REQUIRES_OWNER_REVIEW', lambda: self.execute('publish'))
        self.assertEqual(0, self.calls['publish'])

    def test_config_requires_private_file_and_production_https_and_strict_scalar(self):
        self.config_file.chmod(0o644)
        self.error('INVALID_ARGUMENT', lambda: CLIENT.target(self.config_file))
        self.config['environment'] = 'production'
        self.write(self.config_file, self.config)
        self.error('INVALID_ARGUMENT', lambda: CLIENT.target(self.config_file))
        self.config['environment'] = 'test'
        self.config['publish_enabled'] = 'true'
        self.write(self.config_file, self.config)
        self.error('INVALID_ARGUMENT', lambda: CLIENT.target(self.config_file))

    def test_duplicate_json_and_auto_authorization_flags_are_rejected(self):
        self.error('INVALID_ARGUMENT', lambda: CLIENT.strict_json(b'{"a":1,"a":2}'))
        value = json.loads(self.candidate.read_text())
        value['auto_grants'] = True
        self.write(self.candidate, value)
        self.error('INVALID_ARGUMENT', self.execute)
        self.assertEqual(0, self.calls['token'])

    def test_concurrent_checkpoint_use_does_not_overwrite_and_contains_no_credentials(self):
        self.execute()
        with CLIENT.checkpoint_lock(self.checkpoint):
            self.error('STATE_CONFLICT', lambda: self.execute('publish'))
        raw = self.checkpoint.read_text()
        self.assertNotIn(self.secret, raw)
        self.assertNotIn(self.token, raw)
        self.assertNotIn('client_secret', raw)
        self.assertEqual(0o600, self.checkpoint.stat().st_mode & 0o777)
        self.error('STATE_CONFLICT', self.execute)


if __name__ == '__main__':
    unittest.main()
