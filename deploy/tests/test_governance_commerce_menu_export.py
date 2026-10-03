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

    def test_registry_allows_real_menu_evolution_but_keeps_capability_semantics(self):
        registry = dict(schema_version='1', application='commerce', capabilities=copy.deepcopy(self.current['capabilities']),
                        menus=[dict(code='stable.entry', parent=None, route='/operations/new-path', any_of=['commerce.product.read'], label='真实新入口', position=0)])
        result = exporter.export_registry(registry, self.current, 3)
        self.assertEqual(result['menus'][0]['code'], 'stable.entry')
        self.assertEqual(len(result['menus']), 1)
        self.assertEqual(self.current['manifest_version'], 1)
        registry['capabilities'][0]['risk_level'] = 'HIGH'
        with self.assertRaises(ValueError):
            exporter.export_registry(registry, self.current, 3)

    def test_registry_rejects_unknown_bindings_cycles_and_bad_position(self):
        registry = dict(schema_version='1', application='commerce', capabilities=self.current['capabilities'],
                        menus=[dict(code='stable.entry', parent=None, route='/operations/new', any_of=['commerce.unknown'])])
        with self.assertRaises(ValueError):
            exporter.export_registry(registry, self.current, 2)
        registry['menus'][0]['any_of'] = ['commerce.product.read']
        registry['menus'][0]['parent'] = 'stable.entry'
        with self.assertRaises(ValueError):
            exporter.export_registry(registry, self.current, 2)
