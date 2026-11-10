#!/usr/bin/env python3
"""守卫：`server/` 里不得出现明文凭据或绝对路径。

服务器端代码现在是对外开源的（AGPL-3.0 §13），所以它同时也是"任何人都能读到"的。
这条守卫做三件事：

  1. 扫描 `server/` 下所有文本文件，任何"口令/令牌 = 字面量"的写法直接判失败；
  2. 禁止出现本机绝对路径（`C:\\Users\\...`）——那会把开发机目录结构带进公开仓库；
  3. 确认 `.env.example` 存在且**只含占位符**（`XEC_OPENLIST_PASS=` 后面必须是空的）。

失败时只打印文件名、行号和掩码后的片段，不复述密钥本身。
"""
from __future__ import annotations

import pathlib
import re
import sys

SERVER_DIR = 'server'
TEXT_SUFFIXES = {'.py', '.ps1', '.md', '.html', '.json', '.yml', '.yaml', '.txt', '.env', '.example'}

# 形如 password = "...", token: "...", ADMIN = ("admin", "...") 的赋值。
SECRET_ASSIGN = re.compile(
    r'(?i)\b(password|passwd|pwd|secret|token|apikey|api_key)\b\s*[:=]\s*[\'"][^\'"\s]{4,}[\'"]')
ADMIN_TUPLE = re.compile(r'ADMIN\s*=\s*\(\s*[\'"]admin[\'"]\s*,\s*[\'"][^\'"]+[\'"]\s*\)')
BEARER = re.compile(r'Bearer\s+[A-Za-z0-9_\-\.]{20,}')
WINDOWS_PATH = re.compile(r'[A-Za-z]:[\\/]{1,2}Users[\\/]', re.I)
# 我们自己的口令前缀，作为"这条串有没有漏出来"的哨兵（不写全值）。
CANARY = re.compile(r'[\'"]Xec-[A-Za-z0-9]{6,}[\'"]')


def mask(line: str) -> str:
    return re.sub(r'([\'"])([^\'"]{3,})\1', lambda m: m.group(1) + m.group(2)[:2] + '***' + m.group(1), line.strip())[:140]


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    base = root / SERVER_DIR
    if not base.is_dir():
        print('FAIL: %s/ is missing' % SERVER_DIR, file=sys.stderr)
        return 1

    errors: list[str] = []
    checked = 0
    for path in sorted(base.rglob('*')):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if '__pycache__' in rel:
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in ('.env.example',):
            continue
        checked += 1
        text = path.read_text(encoding='utf-8', errors='replace')
        for lineno, line in enumerate(text.splitlines(), 1):
            for name, pattern in (
                ('plaintext credential', SECRET_ASSIGN),
                ('plaintext admin tuple', ADMIN_TUPLE),
                ('bearer token', BEARER),
                ('absolute home path', WINDOWS_PATH),
                ('leaked password prefix', CANARY),
            ):
                if pattern.search(line):
                    errors.append('%s:%d %s: %s' % (rel, lineno, name, mask(line)))

    example = base / '.env.example'
    if not example.is_file():
        errors.append('server/.env.example: missing')
    else:
        for lineno, line in enumerate(example.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
            if line.startswith('XEC_OPENLIST_PASS=') and line.strip() != 'XEC_OPENLIST_PASS=':
                errors.append('server/.env.example:%d placeholder must stay empty: %s' % (lineno, line.strip()))
            if line.startswith('XEC_GH_TOKEN=') and line.strip() != 'XEC_GH_TOKEN=':
                errors.append('server/.env.example:%d placeholder must stay empty: %s' % (lineno, line.strip()))

    if errors:
        print('FAIL: server/ is not safe to publish:', file=sys.stderr)
        for err in errors:
            print('  - ' + err, file=sys.stderr)
        return 1

    print('OK: %d server file(s) carry no plaintext credentials or absolute paths' % checked)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
