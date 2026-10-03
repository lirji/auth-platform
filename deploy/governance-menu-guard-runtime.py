#!/usr/bin/env python3
"""MG05隔离Owner发布与固定预览页面验收，复用既有身份和运行夹具。"""
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('menu_runtime',Path(__file__).with_name('governance-menu-diff-runtime.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
if __name__=='__main__':
    module.main(guard=True)
