#!/usr/bin/env python3
"""守卫：supercall.h 的三份镜像必须逐字节一致。

`kernel/include/uapi/supercall.h`、仓库根的 `uapi/supercall.h` 与
`manager/app/src/main/cpp/uapi/supercall.h` 是同一份 UAPI 的手工镜像：内核侧、
外部使用者、Manager 的 JNI 各读一份。加载器时代它们失步过一次（一边删了 ioctl、
另一边还留着结构体），所以这里加一道零成本的断言。

失败条件：任一文件缺失，或三份内容（字节级）不完全相同。
修复方式：以 `kernel/include/uapi/supercall.h` 为准覆盖另外两份。
"""
from __future__ import annotations

import pathlib
import sys

MIRRORS = (
    'kernel/include/uapi/supercall.h',
    'uapi/supercall.h',
    'manager/app/src/main/cpp/uapi/supercall.h',
)


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    blobs: dict[str, bytes] = {}
    missing = []
    for rel in MIRRORS:
        p = root / rel
        if not p.is_file():
            missing.append(rel)
            continue
        blobs[rel] = p.read_bytes()

    if missing:
        print('FAIL: uapi mirror(s) missing: ' + ', '.join(missing), file=sys.stderr)
        return 1

    reference = MIRRORS[0]
    ref = blobs[reference]
    drift = [rel for rel in MIRRORS[1:] if blobs[rel] != ref]
    if drift:
        print('FAIL: uapi mirrors drifted from %s:' % reference, file=sys.stderr)
        for rel in drift:
            print('  %s (%d bytes vs %d)' % (rel, len(blobs[rel]), len(ref)), file=sys.stderr)
        print('\nCopy %s over the drifted copies.' % reference, file=sys.stderr)
        return 1

    print('OK: %d uapi mirrors are byte-identical (%d bytes)' % (len(MIRRORS), len(ref)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
