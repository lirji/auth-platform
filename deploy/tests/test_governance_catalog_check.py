"""只读工具防目标替换／自动授权／重复字段，正式语义由真实Java/PG验证。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('checker',Path(__file__).resolve().parents[1]/'governance-catalog-check.py')
checker=importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

class CatalogCheckTest(unittest.TestCase):
    def test_fixed_envelope_preserves_source_and_raw_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'input.json'
            manifest={'application':'commerce'}
            path.write_text(json.dumps(manifest));self.assertEqual(checker.candidate(path,'commerce'),{'manifest':manifest})
            source={'commit':'a'*40,'artifact_hash':'b'*64}
            path.write_text(json.dumps({'manifest':manifest,'source':source,'auto_grants':False,'auto_roles':False}))
            self.assertEqual(checker.candidate(path,'commerce'),{'manifest':manifest,'source':source})
    def test_wrong_target_unknown_and_automatic_authorization_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'input.json'
            for value in [{'application':'other'},{'manifest':{'application':'commerce'},'subject':'victim'},
                          {'manifest':{'application':'commerce'},'auto_grants':True},{'manifest':{'application':'commerce'},'auto_roles':0}]:
                path.write_text(json.dumps(value))
                with self.assertRaises(ValueError):checker.candidate(path,'commerce')
    def test_duplicate_oversize_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'input.json'
            path.write_text('{"application":"commerce","application":"other"}')
            with self.assertRaises(ValueError):checker.candidate(path,'commerce')
            path.write_bytes(b' '*141313)
            with self.assertRaises(ValueError):checker.candidate(path,'commerce')
            link=Path(folder)/'link.json';link.symlink_to(path)
            with self.assertRaises(ValueError):checker.candidate(link,'commerce')

if __name__=='__main__':unittest.main()
