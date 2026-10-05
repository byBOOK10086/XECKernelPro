# 全局 UI 底层重构 · 深色流体玻璃 + 水滴特效

日期：2026-09-14
状态：已确认（用户批准全量范围 P0–P3）

## 目标

把 XECKernelPro 管理器的外观从"两个上游库的默认皮肤"改成一套自有的、可命名的设计语言，并让每一次点击都有水滴扩散反馈。改造后应满足：

1. 视觉上与 KernelSU / miuix / Material3 默认观感明显不同。
2. 所有容器表面统一为圆角玻璃，不出现直角矩形。
3. 全局所有可点击元素在按下时产生水滴扩散 + 轻微收缩。
4. 低版本设备（无 AGSL / 无 RenderEffect）自动降级，不崩溃、不黑屏。

## 现状诊断

外观并非本项目所写，而是两个上游主题的转发结果：

- `ui/theme/Theme.kt` 只是按 `UiMode` 把 `AppSettings` 分发给 `MiuixKernelSUTheme` 或 `MaterialKernelSUTheme`。
- `ui/theme/MiuixTheme.kt` 仅把 `background` alpha 降到 0.15、5 个 `surface*` alpha 降到 0.40，得到"半透明磨砂"观感。
- 页面/组件成对存在 `XxxMiuix.kt` + `XxxMaterial.kt`（25 组），由 `UiMode` / `LocalUiMode` 驱动。
- 项目自有资产只有三块：`MainActivity` 的全屏背景图、`ui/component/liquid/*`（AGSL 折射/饱和/内阴影）、`ui/component/miuix/animation/InteractiveHighlight.kt`（AGSL 按压径向高光）。

由此产生的两个既有缺陷：

- `ui/util/BlurExt.kt` 的 `BlurredBar` 用 `RectangleShape` 作为 `drawBackdrop` 的 shape。而 `lens()` 内部要求 `shape as? CornerBasedShape`，取不到就 `return`。**所以现有的"液态玻璃"折射其实从未生效**，只剩模糊。
- 同处 `RectangleShape` 让顶栏/底栏四角是直角，与整体圆角风格冲突。

## 设计语言

- 名称：**XEC Fluid Glass**（深色流体玻璃）。
- 底层：深墨绿灰（`#0B0F10` 一系），背景图之上叠一层玻璃。
- 强调色：teal `#12B886`。选它的理由是与深色底反差足够大、又不落入 miuix 的蓝或 Material3 的紫，且作为"水"的联想色最自然。
- 一句话原则：**底是静的，玻璃是动的，光是水的。**

## 令牌（设计变量）

### 颜色 `XcColor`

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `backdrop` | `#0B0F10` | 页面最底 |
| `surface` | `#131A1C` | 玻璃基底 |
| `surfaceMuted` | `#1A2225` | 次级玻璃 / 内嵌块 |
| `glassTint` | `#131A1C` @ 55% | 玻璃上覆色 |
| `glassRim` | `#263033` | 玻璃 1px 描边 |
| `text` / `textSecondary` / `textMuted` | `#E7EDEE` / `#BAC6C8` / `#8A9799` | 文字三级 |
| `accent` / `accentSoft` | `#12B886` / `#12241F` | 强调、强调底 |
| `water` | `#12B886` @ 42% | 水滴本体 |
| `danger` / `warning` / `success` | `#FF4D4F` / `#FAAD14` / `#52C41A` | 语义色 |

AMOLED 档位下 `backdrop` 取纯黑 `#000000`，其余不变。

### 形状 `XcShape` — 全圆角

**任何矩形表面都必须取这里的值，不允许 `RectangleShape`。**

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `xs` | 8dp | 标签、徽标、小药丸 |
| `sm` | 12dp | 列表项内嵌块、输入框 |
| `md` | 16dp | 卡片、分组容器 |
| `lg` | 22dp | 大卡片、面板 |
| `xl` | 28dp | 对话框、底部弹层 |
| `bar` | 32dp | 顶栏 / 导航栏 / 悬浮栏 |
| `pill` | 999dp | 按钮、分段控件 |

### 动效 `XcMotion`

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `waterDropDuration` | 620ms | 水滴扩散 |
| `waterDropEasing` | `CubicBezier(0.16, 0.84, 0.30, 1.0)` | 水滴扩散 |
| `pressScale` | 0.965 | 按下收缩 |
| `pressDuration` | 320ms | 按下收缩 |

### 字阶 `XcTypography` / 层级 `XcElevation`

字阶 5 档（display 28 / title 22 / subtitle 16 / body 14 / caption 12），层级 3 档（glass 0dp / raised 4dp / overlay 12dp）。

## 架构

三层，自下而上，上层只依赖下层：

**L1 `ui/design/token/`** — 纯数据，不含渲染。
`XcColor.kt`、`XcShape.kt`、`XcMotion.kt`、`XcTypography.kt`、`XcElevation.kt`，由 `XcTheme.kt` 通过 `CompositionLocal` 下发，并在 miuix 主题之上桥接（深色时取 Xc 值，浅色时回退到 miuix 原值，保证浅色模式不崩）。

**L2 `ui/design/glass/`** — 玻璃表面。
`XGlassSurface`（基础）、`XGlassCard`（卡片）、`XGlassBar`（栏）、`XGlassDialog`（弹层）。内部按运行时能力三档降级：

1. `isRuntimeShaderSupported()` → `vibrancy + blur + lens`（**shape 必须传圆角**，这样折射才真正生效）。
2. `isRenderEffectSupported()` → `textureBlur`。
3. 都没有 → 半透明纯色。

**L3 `ui/design/liquid/`** — 水滴。
`WaterDrop.kt` 提供两条通道：

- `Modifier.xWaterDropClick(onClick, shape)`：挂在具体控件上，波纹**裁剪在控件圆角内**，并叠加 `pressScale` 收缩。精度最高。
- `XDropletHost`：包住整个应用根节点，用祖先级 `pointerInput`（Initial pass，不 consume）观察任意按下点，在顶层 `drawWithContent` 里画一圈扩散光晕。这样**没有改到的控件也自动有水滴**，满足"全局所有可点击元素"。

内置开关 `LocalXWaterDropEnabled`，供 `prefers-reduced-motion` 类需求或调试关闭。

水滴参数：扩散直径 2.2× 控件短边、上限 160dp；时长 620ms；起始不透明度 0.42；两层波（第二层滞后 80ms）；短边 < 48dp 的控件不扩散只做收缩。

## 收敛（删除双轨）

`UiMode` / `LocalUiMode` 与 25 组双轨是"和 KernelSU 一模一样"的结构性原因。收敛顺序：

1. **先建后拆**：L1/L2/L3 建好并接管壳层，此时双轨仍在，随时可回退。
2. **逐组合并**：按页面把 `XxxMiuix.kt` 保留并改名/改造为唯一实现，删除 `XxxMaterial.kt`，并把调用处的 `when (uiMode)` 拆掉。
3. **最后拆枚举**：删 `UiMode.kt`、`LocalUiMode`、`Theme.kt` 里的分发、`SettingsUiState.uiMode` / `onSetUiModeIndex`、设置页的 UI 模式开关、`settings_ui_mode*` 字符串资源，以及 `ThemeController.getAppSettings` 里的 miuix monet 分支。

**保留不动**：`ColorMode`（含 `DARK_AMOLED`）、`keyColor`、`paletteStyle`、`colorSpec` 及其设置项。

## 分期

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| P0 | L1 + L2 + L3 三个新包 | 设计系统可用，不影响现有页面 |
| P1 | 壳层：`MainActivity`、`BottomBar`、`Theme.kt` | 背景/导航/主题切到新系统，水滴全局生效 |
| P2 | 重点页：Home、Settings、KPM；删除 UI 模式开关 | 主要观感完成转变 |
| P3 | 其余双轨页面逐组合并，删 `UiMode` | 结构收敛完成 |

## 风险与取舍

| 风险 | 缓解 |
| --- | --- |
| AGSL 需 API 33+，minSdk 31；`AboutMiuix` 的玻璃还要求 API 35 | 三档降级，低版本退回模糊/纯色 |
| 全屏水滴宿主带来额外重绘 | 波纹数量硬上限，动画结束即移除节点；可整体关闭 |
| 收敛引发大面积编译错误 | 严格按"先建后拆 / 逐组 / 最后拆枚举"顺序，每阶段独立可编译 |
| 上游 KernelSU 升级冲突 | 尽量只删不加改动上游文件；新增代码独立成包 |
| DARK_AMOLED 下玻璃失效 | AMOLED 下降低玻璃不透明度、改用描边区分层次 |

## 明确的取舍

- 不做浅色专属设计，浅色沿用 miuix 调色板，只统一形状与动效。
- 不引入新的第三方依赖，全部基于现有 miuix blur + AGSL。
- 水滴不做物理仿真，用缓动曲线近似。
- 收敛后的实现以 miuix 为渲染后端（保留 Material3 依赖供未及改造处使用），不追求彻底去 miuix。
