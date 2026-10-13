# 更新日志

本文件记录 XECKernelPro 管理器的用户可见变更。历史版本由 CI 的 `generate_release_notes` 自动汇总，本文从全局 UI 重写（XEC Fluid Glass）开始做人工整理。

---

## v31103–v31107 · 2026-10-13 · 5.10 以下改走 Magisk 官方修补（方案甲）+ 管理器双向管 root

> 类型：路线切换 + 新增功能（对外可见：<5.10 的安装方式、超级用户页、模块安装通道）
> 提交范围：`bc9029e5..41506e67`（发版 v31103 → v31107）

### 变更：<5.10 的 root 改用 Magisk 官方修补（方案甲）

- 安装页在非 GKI（<5.10）分支新增 **「Magisk 修补（5.10 以下的内核）」**：管理器不做任何
  boot 修补算法，只按官方 `boot_patch.sh` 的目录契约摆好载荷（`magiskboot` / `magiskinit` /
  `magisk` / `init-ld` / `busybox` / `stub.apk` / `util_functions.sh`）并以 `BOOTMODE=true`
  跑官方脚本，把产物 `new-boot.img` 导出给用户刷入。
- 高级开关四个，全部对应官方环境变量（默认全关，与 Magisk 默认一致）：
  `KEEPVERITY` / `KEEPFORCEENCRYPT` / `PATCHVBMETAFLAG` / `RECOVERYMODE`；`LEGACYSAR`
  由管理器判定后传入。A-only legacy SAR 的官方答案是 **`RECOVERYMODE=true` 刷 recovery**。
- 载荷版本固定 **v30.7**，CI 按 **APK sha256 + 每个载荷文件 sha256** 校验后解包到
  `assets/magisk/` 与 `jniLibs/<abi>/`（不进源码树）；GPL-3.0 署名、来源与"未修改"声明见
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 新增：挂载态的「超级用户」页 = Magisk 策略表的前端（双向）

- 管理器新增按路线的判定：**驱动是否应答 + 内核是否 GKI**（都不需要 root）。非 GKI 且无驱动
  ⇒ 挂载态：这台设备上的 root 就是 Magisk 的 root。
- **超级用户页**直接读写 Magisk 自己的策略表（`magisk --sqlite` / `magisk --denylist`）：
  权限四态（询问 / 允许 / 拒绝 / 受限）、通知、日志、撤销、Zygisk 开关、DenyList 强制状态
  与条数，并标出「本管理器」自己那一行。**不建影子名单、不缓存授权** ⇒ Magisk App 与本
  管理器两边改一边立刻一致；本管理器的 root 授权也在同一张表里，Magisk 那边看得到、收得回。
- 授权框永远由 Magisk 弹（它只认自己签名的管理器），管理器不会绕过它自己发 root。
- 挂载态的 root shell 改为直接走设备的 `su`（原来先试 `libxeccli.so debug su`，没有内核驱动
  时必然拿不到 root），并且每条命令新开一条 shell —— 授权被撤销或首次被拒之后不会留死壳。

### 变更：模块安装通道

- 挂载态下刷入模块改走 **Magisk 官方安装器**（`magisk --install-module`），模块的
  `customize.sh` 用 Magisk 的 `ui_print` / `set_perm` / `replace` 语义；内核态仍走
  `ksud module install`。启用 / 禁用 / 卸载继续用两边共用的 `disable` / `remove` 标记文件。

### 移除：自研挂载态路线从界面撤下

- `InstallMethod.RamdiskPatch` 及其界面与字符串全部撤下；`xudc ramdisk-patch` 与 `xecinit`
  代码保留为实验，不再对外推荐。
- 「与 Magisk 冲突、模块不可用」不再出现在 <5.10 设备上（那是内核态与真 Magisk 同时存在时
  才有的真冲突）；首页该位置改为「Magisk 路线（5.10 以下挂载态）」。

### 兼容性

- **GKI 2.0 / 内核态（5.10+）行为不变**：Magisk 相关资源与逻辑只出现在非 GKI 分支；
  `_scratch/check_root_mode_isolation.py`（51 条断言）与 CI 全量构建（8 个 LKM、ksud、
  xecinit、管理器 APK）保证这一点。
- <5.10 使用说明见 [docs/mount-state-root-guide.md](docs/mount-state-root-guide.md)。

---

## v31001 · 2026-10-06 · 许可合规整改：XecHide 下架、Zygisk Next 移出分发、上游署名补全、迁移新仓库

> 类型：合规与分发调整（对外可见：Release 资产与资源包内容变化）

- **下架 XecHide**：应用隐藏引擎源码、CI 工作流与 Release 资产整体移除，不再分发。
- **移除 Zygisk Next 分发**：其许可证不允许第三方再分发。Release 与紫罗兰资源包均不再附带该组件；需要 Zygisk 框架的用户请从官方仓库（LSPosed/ZygiskNext）获取——自装 Zygisk 后资源包内 HMA 模块即可正常启用。
- **签名密钥出库**：`xecpro-key.jks` 从仓库与全部 git 历史中移除，改由仓库 Actions Secrets 注入 CI（同一把密钥，签名结果不变，APK 升级链不受影响）。
- **上游署名补全**：新增根 README 与 THIRD_PARTY_NOTICES.md；应用内「开发者名单 → 致谢」补充 KernelSU（weishu/tiann）与 HMA-OSS、TEESimulator-RS、Tricky Addon、susfs4ksu、LSPosed 等上游致谢；README 中明确「内核 GPL-2.0 / 用户态 GPL-3.0」的许可结构与上游归属。
- **内置模块补 NOTICE**：`builtin/` 四个模块目录各附 NOTICE.md 注明上游与许可。

---

## v30056 · 2026-09-14 · XEC Fluid Glass 全局 UI 重写

> 提交范围：`60b825b..9f2fef2`（4 个提交）
> 规模：134 个文件，+3304 / -11701 行（净减约 8400 行）
> 版本号：`versionCode = 30056 = 30000 + 提交总数(56)`，`versionName` 由 CI 打 tag 时的 `git describe` 决定

### 新增：自有的设计语言

管理器的外观此前只是 KernelSU / miuix / Material3 两个上游主题的转发结果，本次改为一套自有、可命名的设计语言 **XEC Fluid Glass**：深墨绿灰底（`#0B0F10`）+ teal 强调色（`#12B886`），设计原则是「底是静的，玻璃是动的，光是水的」。

新增 `ui/design/` 三层结构，上层只依赖下层：

| 层 | 内容 | 职责 |
| --- | --- | --- |
| L1 `design/token/` | `XcColor`、`XcShape`、`XcMotion`、`XcTypography`、`XcElevation`、`XcTheme` | 纯数据令牌，经 `CompositionLocal` 下发 |
| L2 `design/glass/` | `XGlassSurface`、`XGlassCard`、`XGlassBar`、`XGlassDialog` | 玻璃表面，运行时能力三档降级 |
| L3 `design/liquid/` | `WaterDrop`、`XcIndication` | 点击水滴特效 |

全圆角化：`XcShape` 提供 `xs 8dp / sm 12dp / md 16dp / lg 22dp / xl 28dp / bar 32dp / pill 999dp`，**任何矩形表面都不再使用 `RectangleShape`**。

### 新增：全局水滴交互

- `Modifier.xWaterDropClick(onClick, shape)`：波纹裁剪在控件圆角内，并叠加 `pressScale` 按下收缩。扩散直径 2.2× 控件短边、上限 160dp，时长 620ms，起始不透明度 0.42，两层波（第二层滞后 80ms）。
- `XDropletHost`：包住整个应用根节点，用祖先级 `pointerInput`（Initial pass、不 consume）观察任意按下点，在顶层绘制扩散光晕——**没有单独改造过的控件也自动带水滴**。
- 短边 < 48dp 的控件只做收缩、不扩散；内置 `LocalXWaterDropEnabled` 可整体关闭。

### 新增：玻璃对话框体系

- **弃用平台 `WindowDialog`**：切换 window 会让 `LayerBackdrop` 失效（模糊与折射全部丢失），且它没有任何 `Shape` 参数，无法满足圆角要求。改为**同窗口玻璃浮层**，由 `XDialogHost` 统一承载；根组合里建立整窗 backdrop 并通过 `LocalXDialogHost` / `LocalXDialogBackdrop` 下发，返回键语义按平台 `Dialog` 补齐。
- **新增 `XDialog` / `XDialogTitle`**：「内容由调用方自绘」的对话框改为向宿主登记，由根层 `XDialogHost` 绘制，避免 `XGlassDialog` 的 `fillMaxSize()` 全屏浮层在 `LazyColumn` item / `Card` 深子树里被裁切。
- 迁移全仓 **9 处** `OverlayDialog` 调用点：`SendLogDialog`、`ChooseKmiDialogMiuix`、`UninstallDialogMiuix`、`RootProfileConfigMiuix`（Groups / Flags / Caps / SELinux 四个面板）、`SulogMiuix`（`SulogDetailDialog`）、`ModuleMiuix`（`ModuleShortcutDialog`）。全仓 `OverlayDialog` 代码引用归零。
- 对话框补上安全区约束与面板内滚动，长文案不再把按钮顶出屏幕。

### 移除：Material / Miuix 双轨

`UiMode` 与 25 组 `XxxMiuix.kt` + `XxxMaterial.kt` 成对实现是「和 KernelSU 一模一样」的结构性原因，本次收敛为单轨：

- 删除设置页的 **UI 模式开关**、`UiMode.kt`、`LocalUiMode`、`Theme.kt` 里的分发、`SettingsUiState.uiMode` / `onSetUiModeIndex` 以及 `settings_ui_mode*` 字符串资源。
- 删除 **44 个** Material / 双轨实现文件：25 个页面/组件 `*Material.kt`、`material/Expressive*.kt` 全家、`material/SearchBar.kt`、`SendLogBottomSheet.kt`、`SnackBar.kt`、`TonalCard.kt`、`SegmentedList.kt`、`TopBarBackButton.kt`、`profile/dialogs/MultiSelectDialog.kt`、`SingleSelectDialog.kt`、`dialog/DialogMiuix.kt`、`theme/MaterialTheme.kt`、`theme/ThemeExt.kt`、`theme/Type.kt`、`WebUIMaterial.kt` 等。
- 收敛后以 miuix 为渲染后端（保留 Material3 依赖供未及改造处使用）。

### 修复

- **13 条编译阻断**：`MiuixTheme` 里照搬了 Material3 的 9 个 miuix 根本不存在的颜色槽位（`surfaceContainerLow/Lowest/Dim/Bright/Tint`、`onSurfaceVariant`、`outlineVariant`、`inverseSurface`/`onInverseSurface`）。miuix 的 `Colors` 是 MD3 `ColorScheme` 的精简子集，共 53 个槽位，多写一个就报 `No parameter with name ... found`。另修 `RectangleShape` 的包路径（在 `androidx.compose.ui.graphics`，`foundation.shape` 下无此符号）与 `Retained` 的 `value` 缺省值。
- **全局色偏**：miuix 的 `keyColor` 只在 Monet 档生效，默认 System 档下 `primary` 仍是 miuix 蓝，导致按钮、开关等 **20+ 处**强调色与 XEC 主题不一致。改为用 materialkolor 从 `accentSeed` 自派生整套强调色系并逐槽写回。
- **语义色对比度**：新增容器态底色 `dangerTint` / `warningTint` / `successTint` 及前景令牌 `onDangerTint` / `onWarningTint` / `onSuccessTint` / `onSemanticSolid`。语义实色直接当文字时对比度仅约 3:1，故朝 `text` 方向混 55% 拿到 7:1 以上。
- **`BlurredBar` 的折射从未生效**：此前用 `RectangleShape` 作为 `drawBackdrop` 的 shape，而 `lens()` 内部要求 `shape as? CornerBasedShape`，取不到就直接 `return`——所以「液态玻璃」只剩模糊。顺带修掉顶栏/底栏四角是直角的问题。
- 全仓 `isDynamicColor` 硬编码兜底与 `DpSize` 引用归零。

### 兼容性

- `minSdk 31`。玻璃按运行时能力**三档降级**，低版本不崩溃、不黑屏：
  1. 支持 AGSL（API 33+）→ `vibrancy + blur + lens` 真实折射；
  2. 支持 `RenderEffect` → `textureBlur`；
  3. 都不支持 → 半透明纯色 + 描边。
- `DARK_AMOLED` 档位下 `backdrop` 取纯黑，降低玻璃不透明度、改用描边区分层次。
- **保留不动**：`ColorMode`（含 `DARK_AMOLED`）、`keyColor`、`paletteStyle`、`colorSpec` 及其设置项。

### 视觉变化提示

- 对话框标题由「居中 title4 / Medium」统一改为「左对齐 SemiBold」（`XDialogTitle`，可选副标题）。
- `SulogDetailDialog` 的 `weight(1f, fill = false)` 改为 `heightIn(max = 420.dp)`，因为新宿主不再提供可分配 weight 的父容器语义。
- 迁移后各对话框不再自带 `verticalScroll`：宿主面板内层已统一滚动。

### 构建与验证

CI run `34791687327`（sha `9f2fef2`）已跑完，结果如下：

| Job | 结果 |
| --- | --- |
| `build-manager` | success（自 `f6a0f14` 以来 manager 首次编译成功） |
| `repack-manager` | success |
| `build-ksud`（x86_64 / aarch64-linux-android） | success |
| `build-lkm` ×8 | success |
| `generate-key` / `build-ksuinit` | success |
| `build-ksud-extra (aarch64-apple-darwin)` | **failure（既有问题）** |

关于最后一条：`build-ksud-extra (aarch64-apple-darwin)` 在**重构前的基线提交** `60b825b`、`7b37d88` 上同样失败（其余 4 个 `build-ksud-extra` 因矩阵 fail-fast 被 cancelled），是长期既有问题，与本次 Kotlin 改动无关。

静态校验全绿：花括号配平（190 个 `.kt` 文件）、无残留引用已删除符号、调用点参数逐一对齐（13 个声明）。

---

## 附：本版本相关提交

| 提交 | 说明 |
| --- | --- |
| `f6a0f14` | refactor(manager): 全局 UI 重写为 XEC Fluid Glass，接入水滴特效与玻璃对话框 |
| `15b8bd5` | fix(manager): 修复 13 条编译阻断（miuix 槽位 / RectangleShape 包路径 / Retained 缺省值） |
| `3d904e3` | fix(manager): 清除 miuix 动态色兜底造成的全局色偏 |
| `9f2fef2` | fix(manager): 玻璃对话框收敛收尾——全仓 OverlayDialog 调用点迁移到 XDialog |

设计文档：`docs/superpowers/specs/2026-09-14-xecpro-ui-global-refactor-design.md`
