"""测试导出保留旧能力和菜单，以及源码目录不完整时失败关闭。"""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('exporter', Path(__file__).resolve().parents[1] / 'governance-commerce-menu-export.py')
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class MenuExportTest(unittest.TestCase):
    def setUp(self):
        cap = dict(code='commerce.product.read', resource_type='product', risk_level='NORMAL')
        self.contract = {'capabilities': [cap]}
        self.current = dict(application='commerce', manifest_version=1, capabilities=[cap, dict(code='commerce.store.manage', resource_type='store', risk_level='NORMAL')],
                            menus=[dict(code='products', parent=None, route='/products', any_of=[cap['code']]), dict(code='stores', parent=None, route='/stores', any_of=['commerce.store.manage'])])
        menus = []
        groups = []
        for group in range(6):
            key = 'g' + str(group)
            menus.append(dict(code='group.' + key, parent=None, route=None, any_of=[]))
            pages = []
            for index in range(6 if group < 5 else 5):
                slug = key + '-' + str(index)
                route = '/operations/' + slug
                pages.append('["' + route + '","实际页面' + slug + '"]')
                menus.append(dict(code='menu.operations.' + slug, parent='group.' + key, route=route, any_of=[cap['code']]))
            groups.append('{key:"' + key + '",label:"实际分组' + key + '",pages:[' + ','.join(pages) + ']}')
        menus.append(dict(code='menu.collaboration.products', parent=None, route='/collaboration/products', any_of=[cap['code']]))
        self.approved = dict(status='READY_FOR_ISOLATED_OWNER_PUBLICATION', auto_grants=False, navigation_routes=35, manifest=dict(application='commerce', capabilities=[cap], menus=menus))
        self.nav = 'const groups=[' + ','.join(groups) + ']; const routes=["/collaboration/products"];'

    def build(self):
        return exporter.export(self.approved, self.contract, self.current, self.nav, '"商品协作"', 2)

    def test_full_source_labels_preserve_original_authorization(self):
        before = copy.deepcopy(self.current)
        result = self.build()
        self.assertEqual(len(result['menus']), 44)
        self.assertEqual(len(result['capabilities']), 2)
        self.assertEqual(len([m for m in result['menus'] if 'label' in m]), 42)
        self.assertEqual(self.current, before)
        self.assertEqual(next(m for m in result['menus'] if m['code'] == 'stores'), before['menus'][1])

    def test_missing_navigation_capability_mutation_and_wrong_version_fail(self):
        self.nav = self.nav.replace('/operations/g0-0', '/operations/missing')
        with self.assertRaises((ValueError, KeyError)):
            self.build()
        self.setUp()
        self.current['capabilities'][0] = {**self.current['capabilities'][0], 'risk_level': 'HIGH'}
        with self.assertRaises(ValueError):
            self.build()
        self.setUp()
        with self.assertRaises(ValueError):
            exporter.export(self.approved, self.contract, self.current, self.nav, '"商品协作"', 1)
