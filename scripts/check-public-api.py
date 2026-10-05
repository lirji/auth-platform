#!/usr/bin/env python3
"""保护已发布协议和SDK门面的类名、方法与常量，新增内部实现不改变既有消费者ABI。"""
import argparse
import json
from pathlib import Path
import re
import subprocess


def declarations(output: str) -> dict[str, list[str]]:
    """只索引公开类型的javap声明；编译器生成的私有嵌套实现不属于消费契约。"""
    result = {}
    for block in re.split(r'Compiled from "[^"\n]+"\n', output):
        lines = [line.strip() for line in block.splitlines()
                 if line.strip() and line.strip() not in ('{', '}')]
        if not lines or not lines[0].startswith('public '):
            continue
        match = re.search(r'\b(?:class|interface) ([\w.$]+)', lines[0])
        if match:
            result[match[1]] = lines
    return result


def incompatible(baseline: dict, current: dict) -> list[str]:
    """既有声明必须继续存在；新增公开重载可兼容，移除/改名/改常量必须显式迁移。"""
    errors = []
    for name, expected in baseline.items():
        if name not in current:
            errors.append(f'{name}: 已发布类型不存在')
            continue
        for declaration in expected:
            if declaration not in current[name]:
                errors.append(f'{name}: 已发布声明缺失或变化 {declaration}')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root.resolve()
    snapshot = json.loads((root / 'compatibility/public-api-baseline.json').read_text())
    errors = []
    count = 0
    for module, baseline in snapshot['modules'].items():
        classes = root / module / 'target/classes'
        names = [name for name in baseline if (classes / (name.replace('.', '/') + '.class')).is_file()]
        if names:
            output = subprocess.check_output(['javap', '-public', '-constants', '-classpath', str(classes), *names], text=True)
            current = declarations(output)
        else:
            current = {}
        errors.extend(incompatible(baseline, current))
        count += len(baseline)
    if errors:
        print('\n'.join(errors))
        return 1
    print(f'public API: PASS ({count} 个已发布类型，方法与常量兼容)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
