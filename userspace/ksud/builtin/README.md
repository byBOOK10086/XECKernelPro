# 内置模块目录（Built-in Modules）

把需要内置的隐藏模块放在本目录下，每个模块一个子目录，目录名即模块 id。

当前内置：`tricky_store`（TEESimulator-RS）与 `TA_enhanced`（Tricky Addon Enhanced），
两者均为 GPL-3.0，**二进制产物由 CI 从 `third_party/` 下 vendored 的上游源码构建**
（见各目录 `NOTICE.md` 与 `third_party/*/UPSTREAM.md`），本目录只提交脚本、配置与
NOTICE，不提交任何未经源码构建的 GPL 二进制。原内置的 `susfs4ksu`（上游改为
AGPL-3.0）与 `SelinuxFix`（上游无许可证）已按合规要求移除。

## 目录结构（与普通模块一致）

```
builtin/
  <module_id>/          # 目录名即模块 id（脚本以 KSU_MODULE=<目录名> 环境执行）
    module.prop          # 可选：仅用于自描述 id / name / version / description
    post-fs-data.sh      # 可选：post-fs-data 阶段执行
    service.sh           # 可选：service 阶段执行
    boot-completed.sh    # 可选：boot-completed 阶段执行
    post-mount.sh        # 可选：post-mount 阶段执行
    sepolicy.rule        # 可选：SELinux 规则
    system.prop          # 可选：系统属性
    initrc/*.rc          # 可选：init.rc 片段
```

## 说明

- 这些模块会被打包进 ksud（RustEmbed），加密存放在 `/data/adb/ksu/cfg/`（无关文件名），
  每次开机按 ksud 构建解密到 tmpfs 运行目录 `/dev/.xudc_hidden/<module_id>/`。
- 内置模块只执行、不枚举：不会出现在管理器「模块」面板，也不会出现在 `/data/adb/modules/` 下，ADB `ls /data/adb/modules/` 看不到。
- **用户可选（2026-10-15 起）**：两个模块各自有一个开关，落在
  `/data/adb/.xudc_secure/builtin.conf`（一行一个 `key=1|0`，键是 `tee` / `ta`），
  **默认关闭**。关闭时 ksud 既不解密进 tmpfs，也不执行任何阶段脚本，运行目录会被清掉。
  开关由管理器「检测」页的「内置模块」卡片维护，也可以直接用
  `ksud builtin status|enable|disable <tee|ta>`（`enable/disable` 会立刻生效，不必重启）。
- 内置模块**永远不许**删除或标记 `/data/adb/modules/` 下的任何东西：那里的模块是用户的，
  同名（`tricky_store` / `TA_enhanced`）时内置那份主动让位。守卫见
  `_scratch/check_builtin_modules.py`。
- 更新方式：随 ksud 一起更新（存储里的 blob 按 ksud 构建刷新），无需重新刷入 boot。

## 各模块支持的可选调用

| 调用 | 谁调 | 语义 |
| --- | --- | --- |
| `<stage>.sh` | ksud 开机事件 | 正常阶段脚本；只在该模块开关为开时执行 |
| `service.sh --takeover` | 内置存活核对 / 管理器「接管引擎」 | 外部模块占位但没有任何引擎在跑时强制接管注入面（要求开关为开） |
| `service.sh --stop` | `ksud builtin disable <key>` | 收掉本模块自己的进程与看护循环，只碰自己的东西 |
