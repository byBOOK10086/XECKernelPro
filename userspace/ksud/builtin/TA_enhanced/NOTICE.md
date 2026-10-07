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

版权归原作者所有；许可证全文见上游仓库与 `third_party/tricky-addon-enhanced/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。
