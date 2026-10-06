# 第三方组件与模块致谢（THIRD PARTY NOTICES）

本项目（XECKernel Pro）包含或分发以下第三方作品。各作品的许可证均未被改变，
感谢原作者。如你是权利人且不希望被列于此或被随本项目分发，请提交 issue，我们会立即处理。

## 基础项目

- **KernelSU** — https://github.com/tiann/KernelSU （作者 weishu / tiann 及社区贡献者）
  本项目的基础。内核部分 GPL-2.0（[kernel/LICENSE](kernel/LICENSE)），用户态部分 GPL-3.0（[LICENSE](LICENSE)）。
- **android_bootimg** — https://github.com/5ec1cff/android_bootimg （boot 镜像解析库，许可证以上游仓库为准）
- **AnyKernel3**（osm0sis 原作；GKI 配方参考 WildKernels 维护分支）— https://github.com/osm0sis/AnyKernel3 （GPL-2.0）

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

## 内置模块（随管理器核心 xudc 内嵌分发，`userspace/ksud/builtin/`）

| 模块目录 | 上游 | 作者 | 许可证 |
|---|---|---|---|
| `tricky_store`（TEESimulator-RS） | https://github.com/Enginex0/TEESimulator-RS | JingMatrix、Enginex0 | GPL-3.0（技术源自 5ec1cff 的 TrickyStore，GPL-3.0） |
| `TA_enhanced`（Tricky Addon Enhanced） | https://github.com/Enginex0/tricky-addon-enhanced | Enginex0 | GPL-3.0 |
| `susfs4ksu` | https://github.com/sidex15/ksu_module_susfs | sidex15（模块）；SUSFS 内核补丁：simonpunk | GPL-2.0 |
| `SelinuxFix` | 随包第三方模块 | 有始有终 | 上游未声明许可证，版权归原作者 |

以上每个模块目录内均附有 `NOTICE.md` 说明来源与许可。

## 紫罗兰工具箱资源包（云端分发，不内嵌 APK；「一键隐藏」使用）

- **HMA-OSS** — https://github.com/frknkrc44/HMA-OSS （作者 frknkrc44；AGPL-3.0）
- **LSPosed** — https://github.com/LSPosed/LSPosed （GPL-3.0）
- **TEESimulator-RS**、**susfs4ksu 模块** — 同上表
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

## 内嵌检测工具

- **密钥链验机**（`manager/app/src/main/assets/detect/keychain-check.apk`，包名 `wu.keyChain.test`）— 第三方验机工具，版权归原作者。

## 其他

- Rust 依赖见各 `Cargo.toml` / `Cargo.lock`；Android 依赖见 `manager/gradle/libs.versions.toml`
  （含 miuix `top.yukonga.miuix.kmp` 0.9.3，https://github.com/yukonga/miuix ，Apache-2.0）；
  网站依赖见 `website/package.json`；许可证以上游仓库为准。

## XecHide 下架说明

原内置「XecHide」应用隐藏引擎已从本项目源码、CI 与全部发布渠道整体下架，不再分发。
