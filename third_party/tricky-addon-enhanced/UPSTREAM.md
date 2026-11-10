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

## 本地修改（GPL-3.0 §5(a) 标注）

| 文件 | 改动 | 日期 | 理由 |
|---|---|---|---|
| `rust/src/automation/watcher.rs` | `refresh_root_manager` 的包名匹配加 `"KSU" => "com.xecpro.kernel"` | 2026-11-09 | 本仓管理器包名不是上游的，force-stop 会打错应用 |
| `rust/src/keybox/roots/google_rkp.pem` | **新增**信任锚（Google RKP 树根 `CN=Key Attestation CA1`，EC P-384 公钥） | 2026-11-10 | 上游只登记 RSA-4096 那张 Google 根；2025 年起 RKP 设备（含本项目下发的 keybox）挂在 EC P-384 根下，缺这条会把合法箱子判成 `root certificate not recognized`，`keybox validate` 失败、daemon 自动拉取丢弃新箱 |
| `rust/src/keybox/validate.rs` | `KeyboxRootType::GoogleRkp` + `trust_anchors()` 多一项 + `as_snake_case()`/单测断言；EC 私钥匹配在 PKCS#8 之外**也接受 SEC1**（`from_sec1_pem`） | 2026-11-10 | 前者只增加一个被认可的根，不放宽任何签名/有效期/吊销校验；后者修的是"keybox.xml 里的 `BEGIN EC PRIVATE KEY` 被判成 Mismatched ⇒ 整份 ok=false"，上游只试 PKCS#8 |
| `rust/src/health/mod.rs` | 新增 `engine_dirs()`（常规 / 隐藏 / **builtin** 三候选），引擎存在性、nice-name、自愈重启统一走它 | 2026-11-10 | 本项目把引擎装成 builtin（`/data/adb/ksu/builtin/tricky_store`），上游只认 `/data/adb/modules/tricky_store` ⇒ `is_engine_enabled()` 恒 false、`check_once()` 早退 ⇒ 引擎挂了永远不自动拉 |
| `rust/src/security_patch/mod.rs` | 新增 `variant_module_prop()`，变体检测按 builtin → 常规 → 隐藏取第一份 module.prop | 2026-11-10 | 同一路径问题会让变体恒为 `Legacy` ⇒ `security-patch set/update` 不写 `security_patch.txt` 而改写属性 |
| `userspace/ksud/builtin/TA_enhanced/service.sh` | 把 staged 的 `resetprop-rs` 再同步一份到 `/data/adb/tricky_store/ta-enhanced/bin/`（Rust 固定路径） | 2026-11-10 | v5.53.1 起 Rust 写属性 exec 这个固定路径（`platform/props.rs` 的 `RP_PATH`），与我们自己的 staging 目录不同 ⇒ region/补丁属性写入全失败 |

公钥来源：Google 官方根证书清单里 `CN=Key Attestation CA1, OU=Android, O=Google LLC, C=US`
（2025-07-17 ~ 2035-07-15）那张自签根的 SubjectPublicKeyInfo，与本项目
`userspace/ksud/builtin/TA_enhanced/common/keybox_roots.txt` 第 3 条、以及内置验机工具的
`GOOGLE_RKP_ROOT_PUBLIC_KEY` 是同一把公钥。复现脚本见 `_scratch/make_rkp_pem.py`（不入库）。

