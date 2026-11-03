#!/usr/bin/env python3
"""反向守卫：全库不允许再出现 KPM（KernelPatch Module）平面。

D4（2026-11-04）把内核模块装载面、管理器 KPM 页、ksud KPM 子命令、样例模块与
CI 流水线整体移除。这个守卫防止任何残留复活：源码/构建/文档里出现 `kpm`
（大小写不敏感）即失败。

白名单只放三类：
  * CHANGELOG.md —— 历史条目必须保留，附带"已移除"标注；
  * docs/superpowers/specs/ —— 归档设计文档，标注 superseded 后保留；
  * 证书/密钥等二进制文本（keybox.xml 的 base64 里可能偶然出现这三个字母）。

用法：
  python scripts/check_no_kpm.py            # 扫全库
  python scripts/check_no_kpm.py --quiet    # 只在失败时输出
"""
from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys

NEEDLE = 'kpm'

# path prefixes that may keep historical mentions
ALLOW_PREFIXES = (
    'docs/superpowers/specs/',
    'scripts/check_no_kpm.py',
    'CHANGELOG.md',
)

# binary-ish or vendored text where a coincidental substring is not a KPM plane
ALLOW_SUFFIXES = ('.xml',)

SKIP_DIRS = {'.git', 'node_modules', 'target', 'build', '.gradle', '.idea'}

TEXT_SUFFIXES = {
    '.c', '.h', '.cc', '.cpp', '.rs', '.kt', '.kts', '.java', '.py', '.sh',
    '.xml', '.yml', '.yaml', '.toml', '.json', '.md', '.gradle', '.properties',
    '.pro', '.mk', '.txt', '.js', '.ts', '.tsx', '.aidl', '.bp', '.cfg', '.ini',
}


def tracked_files() -> list[pathlib.Path]:
    """Prefer git's index; fall back to a filesystem walk outside a repo."""
    try:
        out = subprocess.run(
            ['git', 'ls-files', '-z'],
            check=True, capture_output=True, text=True,
        ).stdout
        return [pathlib.Path(p) for p in out.split('\0') if p]
    except (OSError, subprocess.CalledProcessError):
        files = []
        for path in pathlib.Path('.').rglob('*'):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            if path.is_file():
                files.append(path)
        return files


def allowed(rel: str) -> bool:
    if rel.startswith(ALLOW_PREFIXES):
        return True
    return rel.endswith(ALLOW_SUFFIXES)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--quiet', action='store_true', help='only print failures')
    args = parser.parse_args()

    offenders: list[tuple[str, int, str]] = []
    for path in tracked_files():
        rel = path.as_posix()
        if allowed(rel):
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        if NEEDLE not in text.lower():
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if NEEDLE in line.lower():
                offenders.append((rel, lineno, line.strip()[:120]))

    if offenders:
        print(f'FAIL: {len(offenders)} residual "kpm" reference(s):', file=sys.stderr)
        for rel, lineno, line in offenders:
            print(f'  {rel}:{lineno}: {line}', file=sys.stderr)
        print(
            '\nThe KPM plane was removed on 2026-11-04. Delete the code, or add the '
            'path to ALLOW_PREFIXES only if it is historical record.',
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print('OK: no residual KPM references outside the historical whitelist')
    return 0


if __name__ == '__main__':
    sys.exit(main())
