"""防止增量打包的旧嵌套依赖进入耗时隔离演练。"""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('p6_rehearsal', ROOT / 'deploy/governance-p6-rehearsal.py')
rehearsal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rehearsal)


class RuntimeArtifactTest(unittest.TestCase):
    def test_matching_runtime_passes_but_incremental_stale_dependency_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            modules = {}
            for module in ('protocol', 'core', 'governance'):
                name = f'auth-platform-{module}-0.1.0-SNAPSHOT.jar'
                file = root / f'auth-platform-{module}/target' / name
                file.parent.mkdir(parents=True)
                file.write_bytes(('current-' + module).encode())
                modules[name] = file
            for app in ('admin', 'server'):
                file = root / f'auth-platform-{app}/target/auth-platform-{app}-0.1.0-SNAPSHOT.jar'
                file.parent.mkdir(parents=True)
                with zipfile.ZipFile(file, 'w') as archive:
                    for name, dependency in modules.items():
                        archive.writestr('BOOT-INF/lib/' + name, dependency.read_bytes())
            rehearsal.verify_auth_runtime(root)
            dependency = modules['auth-platform-governance-0.1.0-SNAPSHOT.jar']
            dependency.write_bytes(b'new-finite-capability')
            with self.assertRaisesRegex(RuntimeError, 'runtime contains stale governance'):
                rehearsal.verify_auth_runtime(root)


class PackagedBrowserArtifactTest(unittest.TestCase):
    def artifact(self, path, *, frontend=b'new-ui', code=b'class', resource=b'mapper', dependency=b'library', second=0):
        nested=io.BytesIO()
        with zipfile.ZipFile(nested,'w') as archive:
            info=zipfile.ZipInfo('Owner.class',(2026,1,1,0,0,second))
            archive.writestr(info,dependency)
        with zipfile.ZipFile(path,'w') as archive:
            archive.writestr('BOOT-INF/classes/Controller.class',code)
            archive.writestr('BOOT-INF/classes/mappers/Owner.xml',resource)
            archive.writestr('BOOT-INF/classes/static/index.html',frontend)
            archive.writestr('BOOT-INF/lib/owner.jar',nested.getvalue())

    def test_frontend_and_zip_timestamps_can_change_without_changing_verified_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            old,new=Path(directory)/'old.jar',Path(directory)/'new.jar'
            self.artifact(old,frontend=b'old-ui',second=0)
            self.artifact(new,second=2)
            self.assertEqual(rehearsal.compare_browser_backend(old,new),{'backend_entries':3,'libraries':1})

    def test_changed_backend_class_mapper_or_nested_dependency_cannot_borrow_previous_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            old,new=Path(directory)/'old.jar',Path(directory)/'new.jar'
            self.artifact(old)
            for changes in ({'code':b'stale-code'},{'resource':b'changed-mapper'},{'dependency':b'changed-library'}):
                with self.subTest(changes=changes):
                    self.artifact(new,**changes)
                    with self.assertRaisesRegex(RuntimeError,'changed .*payload'):
                        rehearsal.compare_browser_backend(old,new)

    def test_missing_backend_resource_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            old,new=Path(directory)/'old.jar',Path(directory)/'new.jar'
            self.artifact(old)
            with zipfile.ZipFile(old) as source,zipfile.ZipFile(new,'w') as target:
                for name in source.namelist():
                    if name != 'BOOT-INF/classes/mappers/Owner.xml':target.writestr(name,source.read(name))
            with self.assertRaisesRegex(RuntimeError,'changed backend entries'):
                rehearsal.compare_browser_backend(old,new)
