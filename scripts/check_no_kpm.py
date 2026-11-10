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

# The removed loader also owned two opaque payloads whose file names never
# contained "kpm"; ban them by path and by their XOR magic so a silent
# resurrection is caught too.
BANNED_PATHS = (
    'userspace/ksud/bin/aarch64/kpim',
    'userspace/ksud/bin/aarch64/kptd',
)
BANNED_TOKENS = ('xdcv1',)

# path prefixes that may keep historical mentions
ALLOW_PREFIXES = (
    'docs/superpowers/specs/',
    'scripts/check_no_kpm.py',
    'CHANGELOG.md',
)

# A certificate blob is base64 text; three letters can appear by coincidence.
# Allow that one file explicitly instead of every .xml in the tree (the first
# version of this guard waved through any XML, which is far too wide).
ALLOW_FILES = (
    'userspace/ksud/builtin/tricky_store/keybox.xml',
    # handover doc: has to name the working branch
    'COLLAB-STATE.md',
    # 官网生成器里有一段"历史 release 正文过滤"：老版本的更新说明里写过 KPM/
    # 防格机，站点需要在渲染时把这些行剔掉。这是展示层的过滤规则，不是装载面。
    'server/gen_site.py',
)

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
    return rel in ALLOW_FILES


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--quiet', action='store_true', help='only print failures')
    args = parser.parse_args()

    offenders: list[tuple[str, int, str]] = []
    for path in tracked_files():
        rel = path.as_posix()
        if rel in BANNED_PATHS:
            offenders.append((rel, 0, 'removed loader payload is back'))
            continue
        if allowed(rel):
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        lowered = text.lower()
        if NEEDLE not in lowered and not any(tok in lowered for tok in BANNED_TOKENS):
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            # Mentioning this guard by name is not a residual implementation.
            probe = line.replace('check_no_kpm', '')
            low = probe.lower()
            if NEEDLE in low or any(tok in low for tok in BANNED_TOKENS):
                offenders.append((rel, lineno, line.strip()[:120]))

    if offenders:
        print(f'FAIL: {len(offenders)} residual loader reference(s):', file=sys.stderr)
        for rel, lineno, line in offenders:
            print(f'  {rel}:{lineno}: {line}', file=sys.stderr)
        print(
            '\nThe KernelPatch module-loading plane was removed on 2026-11-04. Delete '
            'the code, or add the path to ALLOW_PREFIXES/ALLOW_FILES only if it is '
            'historical record or a certificate blob.',
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print('OK: no residual loader references outside the historical whitelist')
    return 0


if __name__ == '__main__':
    sys.exit(main())
