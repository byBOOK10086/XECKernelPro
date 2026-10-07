# NOTICE

本目录为 **Tricky Addon Enhanced**（模块 id `TA_enhanced`），由 XECKernel Pro 管理器核心内嵌分发。

- 上游：https://github.com/Enginex0/tricky-addon-enhanced
- 作者：Enginex0（目标列表相关组件见 https://github.com/KOWX712/Tricky-Addon-Update-Target-List ）
- 许可证：GPL-3.0（与本仓库根 LICENSE 同许可证）

## 源码合编译声明

本目录**不包含任何 GPL 预编译二进制**。以下产物由 CI 从本仓库
`third_party/tricky-addon-enhanced/`（上游 `v5.27.0`，commit `1951ba023d100b427ba7b321074808a89106889e`
的原样快照）源码构建：

- `bin/arm64-v8a/ta-enhanced` —— `third_party/tricky-addon-enhanced/rust/`
- `bin/arm64-v8a/resetprop-rs` —— `third_party/tricky-addon-enhanced/external/resetprop-rs/`
  （resetprop-rs，Enginex0/resetprop-rs，**MIT**，Copyright (c) 2026 Enginex0，
  固定 commit `4646d28a2de49c139025abc5f6d0357cd863320d`）

`bin/arm64-v8a/aapt` 为 AOSP 预编译工具（**Apache-2.0**，
https://android.googlesource.com/platform/frameworks/base/ ），按上游模块原样随包分发。
其余 `*.sh`、`common/`、`more-exclude.json`、`webui/` 为本目录内可读源文件/配置
（`service.sh` 等为适配本仓库内置模块具体化机制的重写版）。

原随包的 `common/vbhash_extractor.apk`（无源码、无许可证声明、脚本亦无引用）已移除；
vbhash 提取功能由源码构建的 ta-enhanced 守护进程（`rust/src/vbhash/`）承担。

## 本地修改（GPL-3.0 §5(a) 要求的改动声明）

**本目录下的 `*.sh` 与 `common/*.sh` 是修改版，不是上游原样文件。** 上游对应用户态部分为
`rust/`（二进制由 CI 构建，见上），模块脚本原本服务于上游自己的模块目录布局；
本项目把它们改写成"内置模块"（`/dev/.xudc_hidden/TA_enhanced`、数据目录
`/data/adb/.xudc_secure`）形态，并补齐本项目的可靠性修复。改动者：**XECKernel Pro**。

- **2026-10-07 起**：`service.sh` / `prop.sh` / `propclean.sh` / `post-fs-data.sh` /
  `common/common.sh` 全面适配内置模块布局（`MODPATH` 改用内置隐藏目录、`TS_DIR` 指向
  `/data/adb/.xudc_secure`、二进制 staging 到 `/data` 后运行）。
- **2026-10-07**：`service.sh` 增加 keybox 自动拉取配置下发（yurikey、3 分钟周期）、
  开机后维护任务的超时上限；`common/common.sh` 增加 `resetprop` 解析阶梯。
- **2026-10-08**：`common/common.sh` 的 `resetprop` 兜底重写为四级阶梯（绝对路径
  `/data/adb/ksu/bin/resetprop` → PATH → `resetprop-rs` → 只读包装），并把 `-w/--wait`
  改为自实现的 getprop 轮询（此前缺 `-w` 的档位会把 `resetprop -w sys.boot_completed 0`
  退化成一次写操作，把 `sys.boot_completed` 写成 0）。
- **2026-10-08**：`service.sh` 把 60 秒 keybox 镜像循环升级为 **keybox 守护**（每 5 秒）：
  每次 ta-enhanced 守护进程成功拉取 keybox 后，上游 `keybox/mod.rs::install_data` 会把
  `/data/adb/tricky_store/keybox.xml` 落成 **0600 root**，而读它的 `KeyBoxManager` 运行在
  **keystore2 进程（uid keystore）** 内——权限位打不开，engine 只能退回软件级证书链。
  守护把权限钉回 644（与本仓库内置 tricky_store 的播种权限一致）、校验失败时强制重拉、
  按"实时 → 兼容副本"单向镜像。**vendor 源码保持零修改。**
- **2026-10-08**：`prop.sh` 的收尾校验改为两遍（不一致先补写再复查）并记录实际使用的
  `resetprop` 后端；ZeroMount 豁免由 INFO 升为 WARN 并写明"此开机不伪装 bootloader 状态"。

许可证不由本项目改变：本模块整体仍按 **GPL-3.0** 分发，对应源码（含上述修改）随本仓库
一同提供；完整对应源码获取方式见根目录 `THIRD_PARTY_NOTICES.md`。

版权归原作者所有；许可证全文见上游仓库与 `third_party/tricky-addon-enhanced/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。
