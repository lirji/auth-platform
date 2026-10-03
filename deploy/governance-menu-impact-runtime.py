#!/usr/bin/env python3
"""MG03复用同源Owner运行夹具，新增随机分区来源与独立诊断资格；不改变原Commerce授权。"""
import importlib.util
from pathlib import Path

if __name__ == '__main__':
    path=Path(__file__).with_name('governance-menu-diff-runtime.py')
    spec=importlib.util.spec_from_file_location('menu_diff_runtime',path)
    runtime=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    runtime.main(impact=True)
