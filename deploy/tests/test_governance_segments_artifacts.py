"""最终证据不能覆盖预停服记录，也不能把未推进的系统任务写成成功。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


segments = module('segments_artifacts', 'governance-ce05-segments.py')
runtime = module('segments_private_runtime', 'governance-context-smoke.py')


class SegmentArtifactTest(unittest.TestCase):
    def context(self, run, processed):
        runtime.private(run / 'segments-owner-pre-outage-result.json', json.dumps({'phase': 'PRE_OUTAGE_PASS'}))
        return {'expect': Mock(), 'sql': Mock(side_effect=[(0, ''), (0, f'{processed}\tCOMPLETED\t1')]),
                'q': lambda value: "'" + value + "'", 'source': 'owned-fixture', 'record': Mock(),
                'user': [], 'segment_outage': {'runId': 'owned-run'}, 'segment_outage_processed': 100,
                'run': run, 'h': runtime}

    def test_final_success_preserves_immutable_pre_outage_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run, 105)
            before = (run / 'segments-owner-pre-outage-result.json').read_bytes()
            segments.outage(context)
            self.assertEqual(before, (run / 'segments-owner-pre-outage-result.json').read_bytes())
            final = json.loads((run / 'segments-owner-result.json').read_text())
            self.assertEqual('PASS', final['phase'])
            self.assertEqual('REAL_AUTH_PROCESS_STOPPED', final['central_outage'])
            self.assertEqual('COMPLETED_WITHOUT_EMPLOYEE_GRANT', final['system_policy'])
            context['expect'].assert_any_call('segment central outage read denied', 18661,
                                             '/v1/admin/segments', [], status=503)
            context['expect'].assert_any_call('segment central outage pump denied', 18661,
                                             '/v1/admin/segments/pump', [], {}, 503)

    def test_no_checkpoint_advance_cannot_emit_final_success(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run, 100)
            with self.assertRaisesRegex(RuntimeError, 'checkpoint did not advance'):
                segments.outage(context)
            self.assertFalse((run / 'segments-owner-result.json').exists())
            context['record'].assert_not_called()


if __name__ == '__main__':
    unittest.main()
