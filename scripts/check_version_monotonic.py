#!/usr/bin/env python3
"""守卫：公开版本码只许前进，不许倒退。

`version.properties` 里的 `versionCode` 是用户能在「关于」页、更新日志、Release tag
和 APK 文件名里看到的那个数字，管理器检查更新时也拿它比较大小。它被从 git 提交数推导
过两次，两次都是对外事故：

  1. 为了给 debug 变体留一个 +1 而整体乘十 → 公开号变成 312770（六位数），
     同时内核报 31277、管理器报 312770，「LKM 可更新」提示永远清不掉。
  2. 改成「31000 + 提交数」后，从另一个功能分支(346 个提交) 切回
     fresh-main(279 个提交) → 31346 倒退成 31279，装了 31346 的用户永远收不到更新。

现在它是单一真源，这道护栏拿它跟仓库里所有 `v<码>-*` 标签比：只要不大于历史最大值就
直接失败，让错误停在 CI 里，而不是停在用户的「已是最新」上。

失败条件：
  - `version.properties` 缺失，或没有可解析的 `versionCode`；
  - `versionCode` 不大于仓库里已有 `v<码>-*` 标签中的最大值。

修复方式：把 `version.properties` 里的数字改到大于历史最大值（通常 +1），重新发版。
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

TAG_RE = re.compile(r'^v(\d+)-')
KV_RE = re.compile(r'^\s*versionCode\s*=\s*(\d+)\s*$')


def read_version_code(path: pathlib.Path) -> int | None:
    if not path.is_file():
        print('FAIL: %s is missing' % path.name, file=sys.stderr)
        return None
    for line in path.read_text(encoding='utf-8').splitlines():
        m = KV_RE.match(line)
        if m:
            return int(m.group(1))
    print('FAIL: %s has no parsable "versionCode=<int>" line' % path.name, file=sys.stderr)
    return None


def _git(root: pathlib.Path, *args: str) -> str:
    return subprocess.run(
        ['git', *args],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def tagged_version_codes(root: pathlib.Path) -> tuple[dict[int, str], str | None]:
    """Return every `v<code>-*` tag's code -> tag name, plus an error string on failure.

    Tags pointing at the current commit are excluded on purpose: publishing a
    release creates `v<code>-<sha>` on the commit that is already checked out,
    and that tag's code *is* the one in version.properties. Counting it would
    make every tag-triggered run fail the "must strictly increase" rule, which is
    exactly what happened the first time this guard shipped.
    """
    try:
        listed = _git(root, 'tag', '--list')
        at_head = set(_git(root, 'tag', '--points-at', 'HEAD').split())
    except (OSError, subprocess.CalledProcessError) as exc:
        return {}, str(exc)

    codes: dict[int, str] = {}
    for tag in listed.splitlines():
        name = tag.strip()
        if not name or name in at_head:
            continue
        m = TAG_RE.match(name)
        if m:
            codes[int(m.group(1))] = name
    return codes, None


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    current = read_version_code(root / 'version.properties')
    if current is None:
        return 1

    codes, error = tagged_version_codes(root)
    if error is not None:
        # 没有 git 或不认识 tag 时不阻断（例如打 tarball 构建），但要明确说出来，
        # 避免"护栏静默通过"被当成"已经查过了"。
        print('WARN: cannot read git tags (%s); skipped the monotonic comparison' % error)
        print('OK: versionCode=%d (unverified against tags)' % current)
        return 0

    if not codes:
        print('OK: versionCode=%d (no v<code>-* tags in this clone yet)' % current)
        return 0

    highest = max(codes)
    if current <= highest:
        print(
            'FAIL: version.properties says versionCode=%d, but v%d-%s is already in the repository.'
            % (current, highest, codes[highest].split('-', 1)[-1]),
            file=sys.stderr,
        )
        offenders = sorted(c for c in codes if c >= current)
        print('      tags at or above the current code: ' + ', '.join(codes[c] for c in offenders), file=sys.stderr)
        print(
            '\n      The public version code must strictly increase: users on the tag above would\n'
            '      never be offered this build, because the updater compares this number.\n'
            '      Bump version.properties to %d or higher.' % (highest + 1),
            file=sys.stderr,
        )
        return 1

    print('OK: versionCode=%d is ahead of the highest tag v%d' % (current, highest))
    return 0


if __name__ == '__main__':
    sys.exit(main())
