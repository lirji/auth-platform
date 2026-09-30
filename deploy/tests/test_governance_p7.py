"""新授权就绪探针必须有界、保留拒绝证据，不能吞掉真实依赖故障。"""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location('p7_rehearsal', Path(__file__).resolve().parents[1] / 'governance-p7-rehearsal.py')
rehearsal = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rehearsal)


class GrantReadinessTest(unittest.TestCase):
    def setUp(self):
        # 只测试响应状态序列，不创建数据库、身份或进程。
        self.probe = object.__new__(rehearsal.Rehearsal)
        self.probe.check = Mock()

    @patch.object(rehearsal.time, 'sleep')
    def test_keeps_denial_and_not_ready_observations_until_actual_allow(self, sleep):
        self.probe.check.side_effect = [
            {'decision': 'DENY'}, RuntimeError('HTTP code=AUTHZ_STATE_NOT_READY'), {'decision': 'ALLOW'}]
        result = self.probe.await_new_grant(18170, 'store-new')
        self.assertEqual(['DENY', 'AUTHZ_STATE_NOT_READY', 'ALLOW'], result['observations'])
        self.assertEqual(3, self.probe.check.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch.object(rehearsal.time, 'sleep')
    def test_never_turns_persistent_denial_into_success(self, sleep):
        self.probe.check.return_value = {'decision': 'DENY'}
        with self.assertRaisesRegex(RuntimeError, 'did not converge'):
            self.probe.await_new_grant(18170, 'store-new')
        self.assertEqual(12, self.probe.check.call_count)
        self.assertEqual(11, sleep.call_count)

    @patch.object(rehearsal.time, 'sleep')
    def test_dependency_failure_is_not_retried(self, sleep):
        self.probe.check.side_effect = RuntimeError('HTTP code=DEPENDENCY_UNAVAILABLE')
        with self.assertRaisesRegex(RuntimeError, 'DEPENDENCY_UNAVAILABLE'):
            self.probe.await_new_grant(18170, 'store-new')
        self.assertEqual(1, self.probe.check.call_count)
        sleep.assert_not_called()

    @patch.object(rehearsal.time, 'sleep')
    def test_unknown_decision_is_protocol_failure(self, sleep):
        self.probe.check.return_value = {'decision': 'UNKNOWN'}
        with self.assertRaisesRegex(RuntimeError, 'unexpected'):
            self.probe.await_new_grant(18170, 'store-new')
        self.assertEqual(1, self.probe.check.call_count)
        sleep.assert_not_called()


class IdentitySubnetTest(unittest.TestCase):
    @patch.object(rehearsal.subprocess, 'run')
    def test_rejects_public_ipv6_broad_or_noncanonical_subnet_before_docker(self, run):
        probe = object.__new__(rehearsal.Rehearsal)
        probe.isolated_identity = True
        probe.suffix = 'offline'
        for subnet in ('8.8.8.0/24', '::/0', '10.0.0.0/8', '10.1.1.1/24'):
            with self.subTest(subnet=subnet), self.assertRaises(ValueError):
                probe.prepare_identity(subnet)
        run.assert_not_called()
