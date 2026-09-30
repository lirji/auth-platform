"""超过50条授权时必须推进到READY；中间成功、失败或超时不能误判为就绪。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('p6_batches', ROOT / 'deploy/governance-p6-rehearsal.py')
p6 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p6)


class ProjectionBatchesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.calls = []

    def callback(self, results):
        stream = iter(results)

        def invoke(timeout):
            self.calls.append(timeout)
            code, text = next(stream)
            path = Path(self.directory.name) / f'projection-{len(self.calls)}.log'
            path.write_text(text)
            return code, path
        return invoke

    def test_ready_is_the_only_terminal_success(self):
        logs = p6.complete_projection('POLICY', self.callback([(0, 'projection kind=POLICY result=READY')]))
        self.assertEqual(len(logs), 1)
        self.assertTrue(0 < self.calls[0] <= p6.PROJECTION_TIMEOUT_SECONDS)

    def test_applied_and_recovered_continue_to_a_real_ready_batch(self):
        logs = p6.complete_projection('POLICY', self.callback([
            (2, 'projection kind=POLICY result=APPLIED'),
            (2, 'projection kind=POLICY result=RECOVERED'),
            (0, 'projection kind=POLICY result=READY')]))
        self.assertEqual(len(logs), 3)

    def test_blocked_wait_busy_unknown_and_mismatched_status_fail_closed(self):
        for code, text in [(2, 'kind=POLICY result=' + state) for state in ('BLOCKED', 'RETRY_WAIT', 'BUSY', 'UNKNOWN', 'READY')] + [
                (0, 'kind=POLICY result=APPLIED'), (0, 'kind=DIRECTORY result=READY'),
                (0, 'no structured projection result'), (3, 'kind=POLICY result=APPLIED')]:
            with self.subTest(code=code, text=text), self.assertRaises(RuntimeError):
                p6.complete_projection('POLICY', self.callback([(code, text)]))

    def test_batch_limit_prevents_unbounded_progress_loop(self):
        with self.assertRaisesRegex(RuntimeError, 'batch limit'):
            p6.complete_projection('POLICY', self.callback(
                [(2, 'kind=POLICY result=APPLIED')] * p6.PROJECTION_MAX_BATCHES))
        self.assertEqual(len(self.calls), p6.PROJECTION_MAX_BATCHES)

    def test_overall_deadline_rejects_even_late_ready(self):
        times = iter([0, 0, p6.PROJECTION_TIMEOUT_SECONDS + 1])
        with self.assertRaisesRegex(RuntimeError, 'deadline'):
            p6.complete_projection('POLICY', self.callback([(0, 'kind=POLICY result=READY')]), clock=lambda: next(times))
        self.assertEqual(len(self.calls), 1)


if __name__ == '__main__':
    unittest.main()
