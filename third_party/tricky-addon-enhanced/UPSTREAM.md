# Upstream provenance（上游来源与构建说明）

- **项目**：Tricky Addon Enhanced（内置模块 id `TA_enhanced` 的守护进程源码）
- **上游仓库**：https://github.com/Enginex0/tricky-addon-enhanced
- **作者**：Enginex0
- **固定修订**：tag `v5.53.1`，commit `5c5e49751dd844ea446c7250f09082dc61489f81`（2026-05-01）
- **许可证**：GPL-3.0（本目录 `LICENSE`；与本仓库根 LICENSE 同许可证）

## vendored 范围

只取守护进程构建所需的源码子集（模块脚本与 WebUI 资产以
`userspace/ksud/builtin/TA_enhanced/` 内的原样快照为准）：

- `rust/` — ta-enhanced 守护进程（Rust，cargo workspace 成员依赖见 `Cargo.toml`）。
  自 v5.51.0 起内嵌 `rust/src/keybox/roots/*.pem`（四个信任锚：Google 硬件根 /
  AOSP-EC / AOSP-RSA / Samsung Knox）与 `roots/status.json`（Google 吊销快照），
  由 `include_bytes!` 打进二进制，构建时无需外部文件。
  **上游自带的 `rust/.cargo/config.toml` 故意不 vendor**：它把 linker 钉成
  android26 wrapper，而本仓 CI 需要按实际安装的 NDK 选择存在的 wrapper 并通过
  `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` 覆盖，见
  `.github/scripts/build-builtin-modules.sh`。
- `external/resetprop-rs/` — 上游 `.gitmodules` 所钉的子模块内容原样 vendored：
  resetprop-rs，Enginex0/resetprop-rs（https://github.com/Enginex0/resetprop-rs ，**MIT**，
  Copyright (c) 2026 Enginex0），固定 commit `4646d28a2de49c139025abc5f6d0357cd863320d`。
  其 `crates/resetprop-cli` 同时产出独立 CLI（内置模块 `bin/arm64-v8a/resetprop-rs`）。
  v5.53.1 的 ta-enhanced 已不再依赖 `resetprop` crate（属性处理迁到 `platform/`），
  该 CLI 仍按原样独立构建。

## 本仓库如何使用这份源码

CI（`.github/workflows/ksud.yml` → `.github/scripts/build-builtin-modules.sh`）
从本目录源码构建以下产物，并放入 `userspace/ksud/builtin/TA_enhanced/bin/arm64-v8a/`：

| 产物 | 源码位置 |
|---|---|
| `ta-enhanced` | `rust/`（cargo，target aarch64-linux-android） |
| `resetprop-rs` | `external/resetprop-rs/crates/resetprop-cli`（cargo，同 target） |

`bin/arm64-v8a/aapt` 为 AOSP 预编译工具（Apache-2.0），按上游模块原样分发，
见该目录 `NOTICE.md`。

除本文件外，本目录内容未做任何修改（上游 `v5.53.1` 原样快照，`.cargo/config.toml`
除外——原因见上）。
