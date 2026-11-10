#!/usr/bin/env python3
"""守卫：许可证矩阵必须自洽（AGPL-3.0 主体 + GPL-3.0 上游派生 + GPL-2.0 内核）。

本项目不是单一许可证仓库：根 `LICENSE` 是我们原创代码的 AGPL-3.0，`LICENSE-GPL-3.0`
是源自 tiann/KernelSU 的那些文件的 GPL-3.0，`kernel/LICENSE` 是 Linux 派生物的
GPL-2.0，`third_party/*` 各自原样。改错任何一处都会让二次分发者拿到的条款与实际不符，
所以这里做零成本断言：

  1. 根 LICENSE 必须是 AGPL-3.0 正文；
  2. LICENSE-GPL-3.0 必须是 GPL-3.0 正文（上游派生文件仍受它约束）；
  3. kernel/LICENSE 必须是 GPL-2.0 正文；
  4. LICENSE-MATRIX.md 存在，且同时提到 AGPL-3.0 / GPL-2.0 / LICENSE-GPL-3.0；
  5. README.md 与 README_ZH.md 必须链接矩阵文件、且不再宣称主体是 GPL-3.0；
  6. 仓库里不得混进 Oh My Keymint（上游自定义许可，只能按外部模块运行时集成）。

失败即返回 1；本脚本由 `.github/workflows/build-xecpro.yml` 的 guards job 调用。
"""
from __future__ import annotations

import pathlib
import sys

AGPL_MARKERS = ('GNU AFFERO GENERAL PUBLIC LICENSE', 'Version 3, 19 November 2007')
GPL3_MARKERS = ('GNU GENERAL PUBLIC LICENSE', 'Version 3, 29 June 2007')
GPL2_MARKERS = ('GNU GENERAL PUBLIC LICENSE', 'Version 2, June 1991')

# 目录名/文件名里出现这些即视为把 Oh My Keymint 塞进了仓库。
FORBIDDEN_NAMES = ('ohmykeymint', 'oh_my_keymint', 'omk-')


def read(root: pathlib.Path, rel: str) -> str:
    return (root / rel).read_text(encoding='utf-8', errors='replace')


def check_text_props(root: pathlib.Path, rel: str, markers: tuple[str, ...], errors: list[str]) -> None:
    p = root / rel
    if not p.is_file():
        errors.append(f'{rel}: missing')
        return
    text = read(root, rel)
    for marker in markers:
        if marker not in text:
            errors.append(f'{rel}: expected marker {marker!r} not found')


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    errors: list[str] = []

    # 1-3 三份许可证正文。
    check_text_props(root, 'LICENSE', AGPL_MARKERS, errors)
    check_text_props(root, 'LICENSE-GPL-3.0', GPL3_MARKERS, errors)
    check_text_props(root, 'kernel/LICENSE', GPL2_MARKERS, errors)

    # 4 矩阵文件必须把三类许可都写清楚。
    matrix = root / 'LICENSE-MATRIX.md'
    if not matrix.is_file():
        errors.append('LICENSE-MATRIX.md: missing')
    else:
        text = matrix.read_text(encoding='utf-8', errors='replace')
        for needle in ('AGPL-3.0', 'GPL-2.0', 'LICENSE-GPL-3.0'):
            if needle not in text:
                errors.append(f'LICENSE-MATRIX.md: does not mention {needle!r}')

    # 5 两个 README 都要指向矩阵，且不能还写着主体是 GPL-3.0。
    for rel in ('README.md', 'README_ZH.md'):
        p = root / rel
        if not p.is_file():
            errors.append(f'{rel}: missing')
            continue
        text = p.read_text(encoding='utf-8', errors='replace')
        if 'LICENSE-MATRIX.md' not in text:
            errors.append(f'{rel}: does not link LICENSE-MATRIX.md')
        if 'AGPL-3.0' not in text:
            errors.append(f'{rel}: does not mention AGPL-3.0')
        # badge 残留的旧口径（"License: GPL v3" / "license-GPL--3.0"）。
        for stale in ('License: GPL v3', 'license-GPL--3.0'):
            if stale in text:
                errors.append(f'{rel}: still advertises the old licence badge {stale!r}')

    # 6 不得 vendor Oh My Keymint（自定义许可，与本仓 AGPL 不兼容）。
    for path in root.rglob('*'):
        if not path.is_file():
            continue
        parts = [p.lower() for p in path.parts]
        if '.git' in parts:
            continue
        if any(token in p for p in parts[len(root.parts):] for token in FORBIDDEN_NAMES):
            errors.append(f'{path.relative_to(root)}: Oh My Keymint must not be vendored')

    if errors:
        print('FAIL: licence matrix is inconsistent:', file=sys.stderr)
        for err in errors:
            print('  - ' + err, file=sys.stderr)
        return 1

    print('OK: licence matrix consistent (LICENSE=AGPL-3.0, LICENSE-GPL-3.0=GPL-3.0, kernel=GPL-2.0)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
