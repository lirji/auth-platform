#!/usr/bin/env python3
"""MG06隔离Owner只读漂移验收，复用已有真实认证／独有进程夹具。"""
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('menu_runtime',Path(__file__).with_name('governance-menu-diff-runtime.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
if __name__=='__main__':
    module.main(drift=True)
