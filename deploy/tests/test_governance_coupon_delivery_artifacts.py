"""实际停服阶段不能覆盖预停服证据，推进过或更换原来源的任务不能写成PASS。"""
import hashlib
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


deliveries = module('coupon_delivery_artifacts', 'governance-ce05-coupon-deliveries.py')
runtime = module('coupon_delivery_private_runtime', 'governance-context-smoke.py')


class CouponDeliveryArtifactTest(unittest.TestCase):
    def context(self, run, processed=0, changed=False):
        runtime.private(run / 'coupon-deliveries-owner-pre-outage-result.json', json.dumps({'phase': 'PRE_OUTAGE_PASS'}))
        original = {'kind': 'CENTRAL', 'actor': {'executionId': 'owned-original'}}
        row = {'processed': processed, 'status': 'RUNNING', 'attempts': 0, 'error': 'DEPENDENCY_UNAVAILABLE',
               'source': {'kind': 'LEGACY'} if changed else original}
        return {'expect': Mock(), 'sql': Mock(side_effect=[(0, ''), (0, json.dumps(row))]),
                'q': lambda value: "'" + value + "'", 'source': 'owned-fixture', 'store': 'owned-store', 'record': Mock(),
                'user': [], 'delivery_outage': 'owned-batch',
                'delivery_outage_source_hash': hashlib.sha256(json.dumps(original, sort_keys=True).encode()).hexdigest(),
                'run': run, 'h': runtime}

    def test_actual_stop_preserves_pre_outage_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run)
            before = (run / 'coupon-deliveries-owner-pre-outage-result.json').read_bytes()
            deliveries.outage(context)
            self.assertEqual(before, (run / 'coupon-deliveries-owner-pre-outage-result.json').read_bytes())
            result = json.loads((run / 'coupon-deliveries-owner-result.json').read_text())
            self.assertEqual('PASS', result['phase'])
            self.assertEqual('REAL_AUTH_PROCESS_STOPPED', result['central_outage'])
            self.assertEqual(0, result['outage_row']['processed'])
            context['expect'].assert_any_call('coupon delivery central outage pump denied', 18661,
                                              '/v1/admin/coupon-deliveries/pump', [], {}, 503)

    def test_outage_checkpoint_advance_cannot_emit_success(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run, processed=1)
            with self.assertRaisesRegex(RuntimeError, 'altered original source or committed effects'):
                deliveries.outage(context)
            self.assertFalse((run / 'coupon-deliveries-owner-result.json').exists())
            context['record'].assert_not_called()

    def test_replacement_source_cannot_emit_success(self):
        with tempfile.TemporaryDirectory() as folder:
            run = Path(folder)
            context = self.context(run, changed=True)
            with self.assertRaisesRegex(RuntimeError, 'altered original source or committed effects'):
                deliveries.outage(context)
            self.assertFalse((run / 'coupon-deliveries-owner-result.json').exists())


if __name__ == '__main__':
    unittest.main()
