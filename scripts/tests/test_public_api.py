"""模拟真实消费者的类型改名、签名删除及稳定常量变化。"""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('api', Path(__file__).parents[1] / 'check-public-api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class PublicApiTest(unittest.TestCase):
    def test_adding_overload_preserves_existing_callers(self):
        old = {'example.Client': ['public class example.Client {', 'public void check(java.lang.String);']}
        new = {'example.Client': [*old['example.Client'], 'public void check(java.lang.String, long);']}
        self.assertEqual([], api.incompatible(old, new))

    def test_renamed_facade_and_removed_method_break_consumers(self):
        old = {'example.Client': ['public void check(java.lang.String);']}
        self.assertTrue(api.incompatible(old, {'example.internal.Client': old['example.Client']}))
        self.assertTrue(api.incompatible(old, {'example.Client': ['public void check(long);']}))

    def test_changing_published_budget_is_detected(self):
        old = {'example.Protocol': ['public static final int MAX = 20;']}
        new = {'example.Protocol': ['public static final int MAX = 21;']}
        self.assertTrue(api.incompatible(old, new))
