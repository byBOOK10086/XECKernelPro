#!/usr/bin/env python3
"""守卫：应用包名在所有"会被编译进去"的地方必须和真 APK 的 applicationId 一致。

背景（2026-11-08 实测）：管理器 `applicationId` 是 `com.xecpro.kernel`（PR 是
`com.xecpro.kernel.pr`），但 ksud 烘进去的默认包名还是上游的 `me.weishu.kernelsu`。
后果是构建全绿、产物也"能跑"，但设备上：

  * `su_daemon::is_manager_uid()` 永远查不到管理器（`package_uid()` 返回 None），
    `bootstrap_manager()` 因此从不在挂载态设备上首授权 —— 注释里写的死锁是真实存在的：
    没有这一步就没有任何 App 能用 su，也就没有任何 App 能给自己授权；
  * `boot_patch::find_backup_location()` 在 /data/adb 不可写时退回
    `/data/user_de/<user>/<pkg>/boot_backup`，包名错 ⇒ 目录建不出来 ⇒ 备份失败，
    整个 boot 修补报 "Both /data/adb/ksu and ... are not accessible!"；
  * `cli.rs` 里 `--package-name` 的默认值（uninstall / su-grant / boot-patch）；
  * TA 引擎 `refresh_root_manager("KSU")` 拿错包名 ⇒ 应用后无法重启管理器；
  * `TA_enhanced/more-exclude.json` 排除表里认不出自家管理器。

单一真源 = `manager/app/build.gradle.kts` 的 `defaultManagerPackageName`。
这个脚本把其余四处钉死到它上面，任何一边再漂回去都会在 guards job 里直接失败。

用法：
  python scripts/check_package_identity.py
  python scripts/check_package_identity.py --quiet
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

# 以脚本位置定根，不依赖调用时的 cwd（从 scripts/ 里跑也要能对）
ROOT = pathlib.Path(__file__).resolve().parents[1]

GRADLE = 'manager/app/build.gradle.kts'
KSud_BUILD = 'userspace/ksud/build.rs'
KSud_WORKFLOW = '.github/workflows/ksud.yml'
TA_WATCHER = 'third_party/tricky-addon-enhanced/rust/src/automation/watcher.rs'
TA_EXCLUDE = 'userspace/ksud/builtin/TA_enhanced/more-exclude.json'

# 上游包名：不允许再作为"设备上管理器包名"出现在下面的槽位里
STALE = 'me.weishu.kernelsu'

# defaultManagerPackageName = if (isPrBuild) "<pr>" else "<release>"
GRADLE_RE = re.compile(
    r'defaultManagerPackageName\s*=\s*if\s*\(\s*isPrBuild\s*\)\s*"([^"]+)"\s*else\s*"([^"]+)"'
)


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding='utf-8')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--quiet', action='store_true')
    args = ap.parse_args()

    problems: list[str] = []
    checked: list[str] = []

    def want(rel: str, needle: str, what: str) -> None:
        try:
            text = read(rel)
        except OSError as exc:  # 文件不见了也是失败，不能静默跳过
            problems.append(f'{rel}: 读不到（{exc}）')
            return
        checked.append(f'{rel}: {what}')
        if needle not in text:
            problems.append(f'{rel}: 期望出现 `{needle}`（{what}），实际没有')
        if STALE in text and STALE not in needle:
            problems.append(f'{rel}: 仍残留上游包名 `{STALE}`（{what}）')

    # 1) 单一真源
    try:
        gradle = read(GRADLE)
    except OSError as exc:
        print(f'FAIL: 读不到 {GRADLE}: {exc}', file=sys.stderr)
        return 1
    m = GRADLE_RE.search(gradle)
    if not m:
        print(
            f'FAIL: {GRADLE} 里找不到 defaultManagerPackageName 的 '
            f'`if (isPrBuild) "..." else "..."` 形式，守卫无法比对',
            file=sys.stderr,
        )
        return 1
    pr_pkg, release_pkg = m.group(1), m.group(2)
    checked.append(f'{GRADLE}: applicationId 真源 = {release_pkg} / {pr_pkg}')

    if not release_pkg or not pr_pkg or release_pkg == pr_pkg:
        problems.append(
            f'{GRADLE}: 解析出的包名异常（release={release_pkg!r} pr={pr_pkg!r}）'
        )

    # 2) ksud 默认包名（非 PR 路径）
    want(KSud_BUILD, f'KSU_PACKAGE_NAME={release_pkg}', 'ksud 默认包名')

    # 3) ksud workflow 的 PR 分支
    want(KSud_WORKFLOW, f'export KSU_PACKAGE_NAME="{pr_pkg}"', 'CI PR 分支包名')

    # 4) TA 引擎重启管理器的映射
    want(TA_WATCHER, f'"KSU" => "{release_pkg}"', 'TA refresh_root_manager 映射')

    # 5) TA 排除表：既要认得出自家包名，也要是合法 JSON
    try:
        exclude = json.loads(read(TA_EXCLUDE))
        pkgs = set()

        def walk(node: object) -> None:
            if isinstance(node, dict):
                value = node.get('package-name')
                if isinstance(value, str):
                    pkgs.add(value)
                for v in node.values():
                    walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)

        walk(exclude)
        checked.append(f'{TA_EXCLUDE}: {len(pkgs)} 个包名')
        if release_pkg not in pkgs:
            problems.append(
                f'{TA_EXCLUDE}: 排除表里没有 `{release_pkg}`（{len(pkgs)} 个包名里找不到）'
            )
        if STALE in pkgs:
            problems.append(f'{TA_EXCLUDE}: 排除表里仍写着上游包名 `{STALE}`')
    except (OSError, json.JSONDecodeError) as exc:
        problems.append(f'{TA_EXCLUDE}: 读不到或不是合法 JSON（{exc}）')

    if problems:
        print(f'FAIL: 包名不一致，共 {len(problems)} 处：', file=sys.stderr)
        for item in problems:
            print(f'  - {item}', file=sys.stderr)
        print(
            '\n真源是 manager/app/build.gradle.kts 的 applicationId；'
            '改包名时下面这些地方必须一起改。',
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print(f'OK: {len(checked)} 处包名一致（{release_pkg} / PR {pr_pkg}）')
        for item in checked:
            print(f'  - {item}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
