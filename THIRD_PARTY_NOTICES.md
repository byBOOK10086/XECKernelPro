# 第三方组件与模块致谢（THIRD PARTY NOTICES）

本项目（XECKernel Pro）包含或分发以下第三方作品。各作品的许可证均未被改变，
感谢原作者。如你是权利人且不希望被列于此或被随本项目分发，请提交 issue，我们会立即处理。

## 开源协议合规（GPL / LGPL / Apache / MIT）

许可证划分与上游 KernelSU 一致：**内核模块 `kernel/` 为 GPL-2.0**
（[kernel/LICENSE](kernel/LICENSE)），**用户态（`manager/`、`userspace/`、`uapi/`、
`website/` 等）为 GPL-3.0**（[LICENSE](LICENSE)）。上游 tiann/KernelSU 采用同一划分
（其根 `LICENSE` 为 GPL-3.0、`kernel/LICENSE` 为 GPL-2.0）；本项目不改变任何上游组件的
许可条款，也不对其施加任何额外限制。

对 GPL-3.0 素材的实际履约方式：

| 义务（GPL-3.0 条款） | 本项目的做法 |
|---|---|
| §4 保留版权声明与许可证 | 根 `LICENSE`、`kernel/LICENSE`、每个 vendored 上游树内的 `LICENSE` 原样保留；本文件与各模块 `NOTICE.md` 逐项署名（项目 / 作者 / 链接 / 固定 commit / 许可证） |
| §5(a) 修改必须显著声明并给出日期 | 所有源自上游的文件在**文件头**给出"修改版 + 改动清单位置"声明；逐项改动与日期见 `userspace/ksud/builtin/*/NOTICE.md` 的「本地修改」节 |
| §5(b)(c) 整体以 GPL-3.0 分发、不得附加限制 | 本仓库用户态整体按 GPL-3.0 分发；未对接受者附加 GPL 之外的任何限制 |
| §6 提供对应源码（Corresponding Source） | 仓库公开于 `github.com/byBOOK10086/XECKernelPro`（分支 `fresh-main`）：vendored 上游源码树（`third_party/`）逐字保留、CI 构建脚本（`.github/scripts/build-builtin-modules.sh`、`.github/workflows/`）、模块脚本与配置全部在树内；Release 中的每个二进制都可由同一提交的源码重建 |
| §7 附加条款 | 未添加任何 §7 附加条款 |

其他许可证的履约要点：

- **LGPL-3.0**（LSPlt，随 TEESimulator-RS 构建进 `libTEESimulator.so`）：完整源码与构建配方
  随仓库提供（`third_party/TEESimulator-RS/app/src/main/cpp/external/LSPlt/` + CI 脚本），
  满足 LGPL-3.0 关于"可替换/可重新链接"的要求——任何接受者都可以用修改过的 LSPlt 源码
  按同一配方重建该库。
- **Apache-2.0**（miuix、AOSP libbinder/libutils 头文件子集、aapt、
  Kyant0/AndroidLiquidGlass、miuix 示例 BgEffect）：保留版权与许可证声明
  （本文件与各源文件头注释），未使用其商标。
- **MIT**（resetprop-rs、QWEA0/Liquid-Glass-Android）：保留版权声明与许可证全文
  （分别见 `third_party/tricky-addon-enhanced/external/resetprop-rs/LICENSE` 等）。
- **无许可证 / 许可证强于本项目（AGPL-3.0 等）**：不合编译、不分发
  （见「已移除的内置模块」）。

> 若上游对某一部分的许可证与本文件描述不一致，**以上游仓库同名 LICENSE 文件为准**；
> 发现不一致请提 issue，我们会按其真实条款修正署名与许可证划分。

## 基础项目

- **KernelSU** — https://github.com/tiann/KernelSU （作者 weishu / tiann 及社区贡献者）
  本项目的基础。内核部分 GPL-2.0（[kernel/LICENSE](kernel/LICENSE)），用户态部分 GPL-3.0（[LICENSE](LICENSE)）。
- **android_bootimg** — https://github.com/5ec1cff/android_bootimg （boot 镜像解析库，许可证以上游仓库为准）

### KernelSU 上游致谢（原样转引）

以下条目原样转引自上游 tiann/KernelSU 的 README「Credits」节（`docs/README.md` @ main，
2026-10-06 取）。它们是 KernelSU 的灵感与能力来源，也是本项目的间接上游，感谢原作者：

- [Kernel-Assisted Superuser](https://git.zx2c4.com/kernel-assisted-superuser/about/): The KernelSU idea.
- [Magisk](https://github.com/topjohnwu/Magisk): The powerful root tool.
- [genuine](https://github.com/brevent/genuine/): APK v2 signature validation.
- [Diamorphine](https://github.com/m0nad/Diamorphine): Some rootkit skills.

### 管理器透明玻璃（XEC Clear Glass）

`ui/design/clear/` 下的透明液态玻璃着色器与组件为本项目原创（AGSL 手写）；
观感参考 Apple iOS 26 Liquid Glass 设计语言（仅设计参考，未引用代码），
玻璃管线宿主 API 为 miuix-blur（见「其他」节）。

### 应用图标 / Logo

- **应用图标 / Logo** — 作者 **明风ouo**，依 CC 协议授权使用（作者未提供仓库链接）。
  本项目的 launcher 图标、启动屏图标与页内头像均使用该作品，在此致谢。

## GPL-2.0 上游致谢（单独列出）

本项目用户态整体按 GPL-3.0 分发；凡来自 **GPL-2.0** 上游的代码在此单独致谢，
各许可证均未被改变：

- **KernelSU 内核模块**（`kernel/`）— https://github.com/tiann/KernelSU
  （作者 weishu / tiann 及社区贡献者，GPL-2.0，见 [kernel/LICENSE](kernel/LICENSE)）
- **SUSFS 内核补丁**（SUSFS GKI 内核构建使用）— simonpunk/susfs4ksu
  （https://gitlab.com/simonpunk/susfs4ksu ，GPL-2.0；GitHub 镜像
  https://github.com/ShirkNeko/susfs4ksu ）。`.github/workflows/build-susfs-gki.yml`
  直接从上游拉取补丁应用于基础内核源码构建，仓库内不留存补丁正文。
- **AnyKernel3**（GKI 刷入配方）— osm0sis 原作（https://github.com/osm0sis/AnyKernel3 ，GPL-2.0）；
  GKI 配方参考 WildKernels 维护分支
- **Linux 内核 UAPI 头文件子集**（`third_party/TEESimulator-RS/app/src/main/cpp/external/linux-kernel/`）
  — https://git.kernel.org/ （GPL-2.0）

## 内置模块（随管理器核心 xudc 内嵌分发，`userspace/ksud/builtin/`）

内置模块均为 **GPL-3.0**，且**不再内嵌任何 GPL 预编译二进制**：二进制产物由 CI 从
`third_party/` 下 vendored 的上游源码构建（见下节「源码合编译」），模块目录内只
提交脚本、配置与 NOTICE：

| 模块目录 | 上游 | 作者 | 许可证 | 产物来源 |
|---|---|---|---|---|
| `tricky_store`（TEESimulator-RS） | https://github.com/Enginex0/TEESimulator-RS | JingMatrix、Enginex0 | GPL-3.0 | `third_party/TEESimulator-RS`（v6.0.0-162 / commit 5267c9d） |
| `TA_enhanced`（Tricky Addon Enhanced） | https://github.com/Enginex0/tricky-addon-enhanced | Enginex0 | GPL-3.0 | `third_party/tricky-addon-enhanced`（v5.27.0 / commit 1951ba0） |

### 源码合编译（vendored sources, `third_party/`）

- **TEESimulator-RS** — https://github.com/Enginex0/TEESimulator-RS （GPL-3.0）
  tag `v6.0.0-162`（commit `5267c9dd0092b69dee4a34eb0c8af8e617b2bc5d`）原样快照；
  技术源自 5ec1cff/TrickyStore（GPL-3.0）https://github.com/5ec1cff/TrickyStore 。
  CI 构建产物：`builtin/tricky_store/` 的 `classes.dex`、`libTEESimulator.so`、
  `libcertgen.so`、`inject`、`supervisor`。
- **LSPlt**（随 TEESimulator-RS 源码 vendored）— JingMatrix/LSPlt
  （https://github.com/JingMatrix/LSPlt ，**LGPL-3.0**），commit `3e29437f037cb7d2b9fbb459dcf162f6b8d1d926`。
- **AOSP libbinder/libutils 头文件子集**（随 TEESimulator-RS 源码 vendored）
  — https://android.googlesource.com/platform/frameworks/native/ （Apache-2.0）。
- **tricky-addon-enhanced（rust/ 守护进程源码）** — https://github.com/Enginex0/tricky-addon-enhanced
  （GPL-3.0）tag `v5.27.0`（commit `1951ba023d100b427ba7b321074808a89106889e`）快照；
  CI 构建产物：`builtin/TA_enhanced/bin/arm64-v8a/ta-enhanced`。
- **resetprop-rs**（随 tricky-addon-enhanced 子模块 vendored）— Enginex0/resetprop-rs
  （https://github.com/Enginex0/resetprop-rs ，**MIT**，Copyright (c) 2026 Enginex0），
  commit `4646d28a2de49c139025abc5f6d0357cd863320d`；CI 构建产物：
  `builtin/TA_enhanced/bin/arm64-v8a/resetprop-rs`。
- **aapt**（AOSP 预编译工具，随上游模块原样分发）—
  https://android.googlesource.com/platform/frameworks/base/ （Apache-2.0）。

### 已移除的内置模块

- **`susfs4ksu` 模块**（sidex15，原 NOTICE 标注 GPL-2.0）— 上游仓库已更名为
  https://github.com/sidex15/susfs4ksu-module 且许可证变更为 **AGPL-3.0**（v1.5.2+ 分支
  LICENSE 全文核验，2026-10-06）。按本项目合规规则（上游许可证强于本项目时不合编译、
  不分发），已从源码、CI 与内置集合整体移除。SUSFS **内核侧**（simonpunk/susfs4ksu，
  GPL-2.0）不受影响，仍用于 SUSFS GKI 内核构建（见「GPL-2.0 上游致谢」）。
- **`SelinuxFix` 模块**（有始有终）— 上游未声明任何许可证、无可用源码，无法源码合
  编译也无法合规再分发，已整体移除。

以上每个模块目录内均附有 `NOTICE.md` 说明来源、许可与构建方式。

### 内置模块的本地修改（GPL-3.0 §5(a)）

`userspace/ksud/builtin/` 下的 `*.sh` 与 `common/*.sh` **是修改版**（上游脚本原本服务于
上游自己的模块目录布局），每个文件头均有"修改版 + 改动清单位置"声明。逐项改动与日期见
各模块 `NOTICE.md` 的「本地修改」节。最近一批（2026-10-08）：

- `TA_enhanced`：keybox 守护（权限钉回 644、校验失败强制重拉、单向镜像）、
  `resetprop` 四级解析阶梯与 `-w` 自实现、属性校验两遍 + 记录实际后端。
- `tricky_store`：引擎启动前密钥取证（sha/年龄/权限）、出厂 keybox 识别、权限修正。

vendored 源码树（`third_party/`）始终**零修改**：上述修复全部落在模块脚本层，
上游 Rust/Kotlin 源码未被改动。

### 本项目原创代码（无上游来源）

- **透明液态玻璃**：`ui/design/clear/`（AGSL 着色器与组件）为本项目原创；
  `ui/design/liquid/` 下 XcIndication / WaterDrop / JellyBar 等为本项目实现的按压与
  过渡动效（使用 miuix/blur 宿主 API 与 Kyant0/AndroidLiquidGlass 的改写基础，
  已在「移植代码」节署名）。观感参考 Apple 设计语言，未引用其代码。
- **远程模块助手 · 按键脚本**（`ui/screen/remote/RemoteKeyPlan.kt`、`RemoteEntry`）：
  发送端把"刷到该模块时按什么音量键、延迟多少"写进分享链接，接收端以 root 注入
  **真实 input 事件**（`sendevent` + `EV_SYN`），使需要音量键选择的模块可在无人值守下
  安装。实现为本项目原创，未移植第三方代码。

## 紫罗兰工具箱资源包（云端分发，不内嵌 APK；「一键隐藏」使用）

- **HMA-OSS** — https://github.com/frknkrc44/HMA-OSS （作者 frknkrc44；AGPL-3.0）
- **LSPosed** — https://github.com/LSPosed/LSPosed （GPL-3.0）
- **TEESimulator-RS** — 同「源码合编译」节
- **紫罗兰附加模块 / 自动救砖** — 紫罗兰工具箱组件
- **检测 / 管理 APK**（momo、ruru、Hunter、Luna、紫色放大镜、密钥认证、应用列表检测器、春秋检测、MT管理器）— 版权归各自作者，仅作检测工具随资源包分发；许可证以上游发布页为准。

> **Zygisk Next** — https://github.com/LSPosed/ZygiskNext 。其许可证不允许第三方再分发，本项目**不再分发**该组件（Release 与资源包均已移除）；需要 Zygisk 框架的用户请从官方仓库获取。

## 移植代码（Ported Code）

- **ReSukiSU** — https://github.com/ReSukiSU/ReSukiSU （GPL-3.0）
  以下改动移植自其用户态代码，各源文件内有同内容出处注释：
  - `userspace/ksud/src/susfs.rs` — SUSFS kstat 伪装常量与提交实现，移植自其仓库快照 commit
    aa7c82a7 中 `userspace/ksud/src/android/susfs/api/features/sus_kstat.rs`；SUSFS 内核 ABI 原作为
    simonpunk/susfs4ksu（GPL-2.0，见上表）。
  - `userspace/ksud/src/boot_patch.rs` — config-only 修补（`--no-install` 且传入镜像）跳过 KMI 探测，
    移植自 commit 63268b9（对应 tiann/KernelSU#3803，作者 fhgffy）。
  - `userspace/ksud/src/apk_sign.rs` — APK 签名扫描的 ZIP 注释长度边界修正，移植自 commit
    9fc9b9c（对应 tiann/KernelSU#3802，作者 fhgffy）。

### 管理器 UI（源文件头部均有同内容注释）

- **Kyant0/AndroidLiquidGlass** — https://github.com/Kyant0/AndroidLiquidGlass （Apache-2.0）
  `ui/component/liquid/` 下 CombinedBackdrop、Vibrancy、InnerShadow、Lens 的改写来源。
- **compose-miuix-ui（miuix 项目示例代码）** — 上游 https://github.com/compose-miuix-ui/miuix，
  维护分支 https://github.com/yukonga/miuix （作者 YuKongA 及 compose-miuix-ui 社区，Apache-2.0）。
  `ui/component/miuix/effect/`（BgEffect* 与 OS3BgFrag，共 10 个文件）及
  `ui/component/liquid/` 同名文件以 "Mirrored from" 标注镜像自其 example 代码；
  `FloatingBottomBar.kt` 改写自其 IosLiquidGlassNavigationBar 示例。
- **QWEA0/Liquid-Glass-Android** — https://github.com/QWEA0/Liquid-Glass-Android （MIT）
  `Lens.kt` 中 XC_LENS_SHADER 着色器的移植来源。

## 环境检测审计引用（Adversarial detection audit）

- **DuckDetector**（`eltavine/Duck-Detector-Refactoring`，Apache-2.0，作者 Eltavine 与 Duck Apps 贡献者）
  — 本项目对其 18 个检测器的判定层、采集层与原生探针做了离线静态审计，用来校准本项目的环境隐蔽能力
  （内核超级调用收敛、管理器身份锚点、启动状态一致性等）。**未复制该项目的任何源代码**；
  引用范围限于公开的检测项语义、可观测信号与证据模型，许可证为 Apache-2.0。
- 审计同时参考了各检测器的 `EVIDENCE.md`（其自述的依据、适用版本与已知盲区）。
- 相关对抗性审计与隐蔽策略的分项记录，见仓库内 `docs/`（如随版本发布）与提交历史。

## 内嵌检测工具

- **密钥链验机**（`manager/app/src/main/assets/detect/keychain-check.apk`，包名 `wu.keyChain.test`）— 第三方验机工具，版权归原作者。

## 其他

- Rust 依赖见各 `Cargo.toml` / `Cargo.lock`；Android 依赖见 `manager/gradle/libs.versions.toml`
  （含 miuix `top.yukonga.miuix.kmp` 0.9.3，https://github.com/yukonga/miuix ，Apache-2.0）；
  网站依赖见 `website/package.json`；许可证以上游仓库为准。

## XecHide 下架说明

原内置「XecHide」应用隐藏引擎已从本项目源码、CI 与全部发布渠道整体下架，不再分发。
