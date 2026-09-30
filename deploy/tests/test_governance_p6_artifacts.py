"""防止增量打包的旧嵌套依赖进入耗时隔离演练。"""
import importlib.util
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
