#!/usr/bin/env python3
"""MG04复用真实Owner夹具，在随机隔离应用验证来源、固定回执和历史；不写原Commerce。"""
import importlib.util
from pathlib import Path

if __name__ == '__main__':
    path=Path(__file__).with_name('governance-menu-diff-runtime.py')
    spec=importlib.util.spec_from_file_location('menu_diff_runtime',path)
    runtime=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    runtime.main(history=True)
