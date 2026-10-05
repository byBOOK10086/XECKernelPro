# 模块页改版：回到上游结构 + 霓虹皮肤 + 长按弹出操作面板

日期：2026-09-15
状态：**已评审通过**（用户 2026-09-15 批准），并追加基准要求 ——「**效果和 HTML 里一样就行**」
本版说明：初版 spec 里我自己写进去的若干「刻意偏离」全部撤销，一律以设计稿 `52546940-2756-44d7-acee-c9cb3a4c0500_1.html` 为准（第 0 节逐条列出）。

## 0. 基准与冲突裁决

设计稿是唯一视觉基准。凡本 spec 出现数值/文案/配色，均可在 HTML 里找到对应原文；凡是与 HTML 不一致的，改 spec 而不是改 HTML。

### 0.1 A 类：完全照抄 HTML

| 项 | HTML 原文 | 初版 spec 的偏离（已撤销） |
| --- | --- | --- |
| 页面光晕 | `circle at 10% 10%, rgba(139,92,246,0.15) 0%, transparent 40%` / `circle at 90% 80%, rgba(6,182,212,0.15) 0%, transparent 40%` | 曾改成 `0.05w/0.05h` α0.10、`1.0w/0.95h` α0.08（自创） |
| 卡片内边距 | `padding: 18px 16px` | 曾写成 16dp |
| 卡片文字 | 名称 `text-lg`(18px) `font-semibold` `text-white`；版本/作者 `text-sm mt-1`；描述 `text-sm mt-3 leading-relaxed` | 曾沿用 17sp / 12sp / 无 12dp 上间距 |
| 卡片阴影 | `box-shadow: 0 4px 24px rgba(0,0,0,0.3)` | 初版漏写 |
| 按压外辉光 | 单层 `box-shadow: 0 0 22px rgba(139,92,246,0.35)` | 曾自创 3 层 0.16/0.10/0.05、外扩 6/12/18dp |
| 进度条取消 | 移除 `pressing` 类 → 宽度**瞬时**归 0 | 曾自创 `tween(120ms)` 回退 |
| WebUI 按钮文案 | `WebUi` | 曾决定复用 `R.string.open`（= `Open`） |
| 按钮内边距/间距 | `padding: 14px 4px` + `gap: 7px` | 曾写成「纵向内边距 14dp」、`spacedBy(12.dp)` |
| 按钮按下态 | `transform: scale(0.93)` + `background: rgba(139,92,246,0.25)` + `border-color: rgba(139,92,246,0.6)`，`transition: all .2s ease` | 初版漏写 |
| 按钮置灰 | `opacity: 0.35` + `filter: grayscale(0.6)` + `pointer-events: none` | 曾只写 α0.35 + 手写 lerp，漏 grayscale |
| 安装 FAB | 64dp（`w-16 h-16`）、`linear-gradient(135deg,#8b5cf6,#06b6d4)`、`box-shadow: 0 4px 20px rgba(139,92,246,0.5)` | 曾决定「不动既有 FAB」 |
| 提示文案 | 模块页「长按模块横幅 1 秒查看详情」；详情卡底「Action、WebUi 仅当模块包含对应能力时可点击」 | 初版两条都漏 |
| 更新 URL 行 | `text-neonCyan` + `fa-external-link` | 初版漏 |

### 0.2 B 类：保留你已拍板的结构（HTML 是静态 demo，这两处按 D1/D2 走）

| 项 | HTML | 本项目 |
| --- | --- | --- |
| 长按后的载体 | 跳转整页 `#page-module-detail` | **底部弹出面板**（D1）。HTML 详情卡的**内容与样式 1:1 搬进面板**（见 5.7），差别只在「整页跳转 → 底部浮层」这一层载体。 |
| 卡片字段 | 只有 名称 / 版本·作者 / 描述 | **上游卡片结构**（另含开关、META 徽标、更新药丸）（D2，对应你原话「恢复到原版 KSU」）。这些字段的视觉沿用本页霓虹皮肤。 |

若你更想要 HTML 那种「长按后整页切到模块详情」，说一句即可 —— 改动只在 `ModuleMiuix.kt` + 新增一个 route，面板代码可原样复用。

### 0.3 C 类：应用侧必要扩展（HTML 没有、但真机必须有）

| 项 | 说明 |
| --- | --- |
| 浅色 / AMOLED 色板 | HTML 只有暗色稿；管理器支持浅色与纯黑，纯霓虹紫压在近白底上不可读，故补浅色档（5.1）。 |
| 图标 | HTML 用 Font Awesome（`fa-terminal` / `fa-globe` / `fa-refresh` / `fa-trash-o` / `fa-plus` / `fa-external-link`），Compose 侧映射到最接近的矢量图标（5.7）。 |
| 拖柄、遮罩 | 整页 → 底部浮层后，需要拖柄与遮罩来表达「可关闭」；HTML 无对应元素，按现有 XEC 语汇补。 |
| 3 条新字符串 | `WebUi`、模块页长按提示、面板底部能力提示（7 节）。 |
| RTL / 无障碍 | 面板与按钮排布需在 RTL 下镜像；每枚按钮补 `contentDescription`。 |

## 1. 目标

把管理器「模块」页改成设计稿的样子：

1. 丢掉 XEC 自研的「左滑露出圆形操作按钮」那套交互，卡片结构回到上游 KernelSU 原版；
2. 卡片换成设计稿的霓虹紫/青暗色玻璃风（数值逐条照抄 HTML）；
3. **长按卡片 1 秒**弹出底部操作面板，面板内容 = HTML 详情卡原文，含 `Action` / `WebUi` / `Update` / `Uninstall` 四枚按钮。

## 2. 现状

| 来源 | 结构 |
| --- | --- |
| 上游 KernelSU（`ModuleMaterial.kt`，已在本仓库 `f6a0f14` 删除） | 卡片：名称/版本/作者 + 右侧启用开关 → 描述（最多 4 行可展开）→ META 徽标 → 分隔线 → 底部一排内联按钮（操作 / 打开 / 更新 / 卸载）。卡片整体在模块含 WebUI 时可点，点击打开 WebUI。长按「操作」「打开」按钮可创建桌面快捷方式。 |
| 当前工作区（`ModuleMiuix.kt`） | 卡片：名称 + META + 版本 + 作者 + 描述 + 更新药丸。**操作按钮全部藏在左滑露出的右侧竖排圆形按钮里**（`SlideActionButton`），`detectHorizontalDragGestures` 驱动 `offsetX`。无长按能力。 |
| 设计稿 | 霓虹卡片，**长按 1 秒**底部进度条走满 → 打开详情（本项目改为弹出面板）。卡片本体不含任何按钮。 |

关键结论：设计稿的「卡片本体不承载操作按钮」与上游「按钮内联在卡片里」互斥，本方案选择设计稿的形态，把上游的按钮行整体搬进长按面板。

## 3. 已确认的决策

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 长按 1 秒后四枚按钮的出现形式 | **底部弹出操作面板**（不跳详情页） |
| D2 | 「恢复到原版 KSU」的口径 | **回到上游卡片式结构**（名称/版本/作者/开关/描述/META），按钮行从卡片内移入长按面板 |
| D3 | 霓虹紫青配色范围 | **仅模块页**；其他页面继续用现有 XEC Fluid Glass（墨绿灰 `#0B0F10` + teal `#12B886`） |
| D4 | 现有「左滑露出操作栏」手势 | **直接移除** |

## 4. 默认取值（可随时否决）

| # | 项 | 取值 | 理由 |
| --- | --- | --- | --- |
| A1 | 卡片短按 | 模块含 WebUI 时打开 WebUI，否则无响应 | 与上游一致，不新增第二条到 WebUI 的路径语义。 |
| A2 | 上游「长按按钮 → 创建桌面快捷方式」 | 保留：面板里 Action/WebUI 按钮长按即创建快捷方式 | 零功能损失，`ModuleShortcutState` 与 `ModuleShortcutDialog` 原样复用。 |
| A3 | 面板内容 | **= HTML 详情卡原文**：模块名 → 版本行 → 作者行 → 介绍行 → 更新 URL 行 → 四枚按钮 → 能力提示（见 5.7） | 这是「效果和 HTML 一样」的直接落点；面板高度上限约半屏，超出在面板内滚动。 |

## 5. 视觉规格

### 5.1 霓虹色板

新增 `ui/design/token/XcNeon.kt`，导出 `XcNeon`。**不进 `XcTheme` 全局下发**，模块页内自行按 `isInDarkTheme()` 取值，保证只有本页变色。暗色档数值全部取自 HTML 原文。

| 语义 | 暗色档（= HTML 原文） | 浅色档（C 类补充） |
| --- | --- | --- |
| `cardBg` | `#14142D` @ 0.60 | `#FFFFFF` @ 0.92 |
| `cardBorder` | `#8B5CF6` @ 0.20 | `#6D28D9` @ 0.22 |
| `cardBorderPressed` | `#8B5CF6` @ 0.65 | `#6D28D9` @ 0.65 |
| `actionBorder` | `#8B5CF6` @ 0.25 | `#6D28D9` @ 0.25 |
| `actionBgPressed` | `#8B5CF6` @ 0.25 | `#6D28D9` @ 0.20 |
| `accentPurple` | `#8B5CF6` | `#6D28D9` |
| `accentCyan` | `#06B6D4` | `#0E7490` |
| `danger` | `#EF4444` | `#DC2626` |
| `dangerBorder` | `#EF4444` @ 0.30 | `#DC2626` @ 0.30 |
| `dangerBgPressed` | `#EF4444` @ 0.20 | `#DC2626` @ 0.18 |
| `textMain` | `#E0E7FF` | `#1E1B4B` |
| `textSub` | `#94A3B8` | `#5B6478` |
| `white` | `#FFFFFF` | `#0B0B1A` |
| `gradient` | `linear(#8B5CF6 → #06B6D4)` | `linear(#6D28D9 → #0E7490)` |
| `cardShadow` | `Black` @ 0.30 | `Black` @ 0.16 |
| `glow` | `#8B5CF6` @ 0.35 | `#6D28D9` @ 0.28 |
| `fabGlow` | `#8B5CF6` @ 0.50 | `#6D28D9` @ 0.40 |

### 5.2 页面光晕

HTML 页面底有两团 `radial-gradient`（左上紫、右下青），直接照抄。**不改全局背景**（`BgEffectBackground` 与用户背景图是共享的），改为在模块页内容容器上叠一层本页专属的 `drawBehind`，覆盖本页可视区（含顶栏下方），随容器尺寸重算：

| 位置 | CSS 原文 | Compose 换算 |
| --- | --- | --- |
| 左上 | `radial-gradient(circle at 10% 10%, rgba(139,92,246,0.15) 0%, transparent 40%)` | `center = (0.10w, 0.10h)`；`circle` 默认 `farthest-corner`，故 `radius = 0.40 × 0.9 × √(w² + h²)`；`accentPurple` @ 0.15 → 透明（`Brush.radialGradient`） |
| 右下 | `radial-gradient(circle at 90% 80%, rgba(6,182,212,0.15) 0%, transparent 40%)` | `center = (0.90w, 0.80h)`；最远角为左上，`radius = 0.40 × √(0.81w² + 0.64h²)`；`accentCyan` @ 0.15 → 透明 |

两团都用 `BlendMode.SrcOver` 依次叠画；`transparent` 侧用同色 α=0 而非 `Color.Transparent`，避免深色底上出现灰带。

### 5.3 卡片

| 项 | 取值（HTML 原文 → Compose） |
| --- | --- |
| 圆角 | 12dp（`Xc.shapes.sm`） |
| 外边距 | 水平 16dp（页面 `px-4`）、底部 20dp（`margin-bottom: 20px`） |
| 内边距 | **垂直 18dp、水平 16dp**（`padding: 18px 16px`） |
| 底色 | `cardBg`（`rgba(20,20,45,0.6)`） |
| 模糊 | 12dp（`backdrop-filter: blur(12px)`） |
| 描边 | 1dp `cardBorder` |
| 外阴影 | `0 4dp 24dp Black@0.30`（`0 4px 24px rgba(0,0,0,0.3)`） |
| 裁剪 | `overflow: hidden`：子内容与进度条都裁在 12dp 圆角内 |
| 模块名 | **18sp** SemiBold `white`（`text-lg font-semibold text-white`） |
| 版本 · 作者 | **14sp** `textSub`，上间距 **4dp**（`text-sm mt-1`） |
| 描述 | **14sp** `textSub`，上间距 **12dp**（`mt-3`），行高 **1.625**（`leading-relaxed`），默认最多 4 行，点击整段展开/收起（沿用上游 `onTextLayout` + `hasVisualOverflow` 判定） |
| META 徽标 | 底 `accentPurple` @ 0.18、字 `#C4B5FD`、圆角 8dp，位置与上游一致（D2 保留字段，配色并入霓虹） |
| 开关 | 沿用 Miuix `Switch`，位置在卡片右上；未选中态拇指用 `textSub`，选中态用 `accentCyan` |
| 更新药丸 | 沿用现有 `MiuixIcons.UploadCloud` 药丸，配色并入霓虹（字 `accentCyan`、底 `accentCyan` @ 0.12） |
| 待移除态 | 名称/版本/作者/描述加删除线，与上游一致 |

### 5.4 长按进度条

完全照 `.lp-progress`：

- `position: absolute; bottom: 0; left: 0; height: 3px; width: 0%`
- 填充 `linear-gradient(90deg, #8b5cf6, #06b6d4)`
- `border-radius: 0 0 12px 12px`（只有下两角与卡片同圆角）
- 宽度 0 → 100%，`@keyframes lp-fill { 1s linear forwards }`
- 仅在按压中出现；非按压态宽度为 0、不绘制

### 5.5 按压态

`.module-card.pressing` 三件事同时发生，`transition: transform .15s ease, border-color .15s ease, box-shadow .15s ease`：

| # | HTML | Compose |
| --- | --- | --- |
| 1 | `transform: scale(0.98)` | `graphicsLayer { scaleX = scaleY = 0.98f }`，`animateFloatAsState(tween(150, FastOutSlowInEasing))` |
| 2 | `border-color: rgba(139,92,246,0.65)` | `animateColorAsState(tween(150))` → `cardBorderPressed`，1dp 描边 |
| 3 | `box-shadow: 0 0 22px rgba(139,92,246,0.35)`（单层、blur 22px、无 spread、无偏移） | Compose 无 `box-shadow`：同形状覆层 + `Modifier.blur(11.dp, BlurredEdgeTreatment.Unbounded)`（blur sigma ≈ 半径 ÷ 2）填 `glow`，垫在卡片之下；`Modifier.blur` 不可用（API < 31）时退化为外扩 22dp 的 3 层递减圆角描边（α 0.18 / 0.10 / 0.06）近似软化边缘 |

第 3 条是**平台能力差异**，不是设计取舍：Compose 没有 `box-shadow`，只能用模糊覆层逼近同一个视觉效果；颜色、半径、层数语义与 HTML 对齐（单层）。

### 5.6 安装 FAB（模块页内）

| 项 | 取值 |
| --- | --- |
| 尺寸 | **64dp**（`w-16 h-16`），`CircleShape`（`rounded-full`） |
| 底色 | `linearGradient(135°, #8B5CF6 → #06B6D4)`（`.fab-btn`） |
| 辉光 | `0 4dp 20dp` `fabGlow`（`0 4px 20px rgba(139,92,246,0.5)`），实现同 5.5 第 3 条 |
| 图标 | 相当于 `fa-plus`：`Icons.Rounded.Add`，白 `white`，28dp（HTML 未给字号，按与 64dp 容器的视觉比例取） |
| 位置 | 沿用现有 XEC 定位（右下，避开底栏与手势条）——HTML 的 `right-2 bottom-0` 是贴着页面容器，本项目需让开底部导航，属布局约束而非视觉取舍 |
| 模糊开启/关闭 | 无差别：渐变与辉光与 backdrop 无关，两态一致 |

仅改模块页这一枚 FAB 的尺寸/底色/辉光；其余页面无 FAB，`FloatingActionButtonDefaults` 的全局取值不动。

### 5.7 底部操作面板

新增 `ui/screen/module/ModuleActionSheet.kt`。**不复用共享 `XGlassDialog`**（它是居中弹窗，定位与进退场动画都不匹配底部面板）。面板内容 = HTML 详情卡 `#page-module-detail` 里那张 `glass-card` 的 1:1 转写。

容器（HTML `.glass-card`，等价于「浮在底部的详情卡」）：

| 项 | 取值 |
| --- | --- |
| 定位 | 底部对齐、左右各留 12dp；底边距 = 列表用的同一个 `bottomInnerPadding`，即**面板正好压在底部导航栏之上**（因此不动 `MainActivity` 与底栏） |
| 圆角 | 12dp（`rounded-xl`）四角全圆 |
| 底色 / 模糊 | `cardBg` + 12dp 模糊（`rgba(20,20,45,0.6)` + `blur(12px)`） |
| 描边 | 1dp `cardBorder` |
| 外阴影 | `0 4dp 24dp Black@0.30` |
| 实现 | `XGlassSurface(backdrop = 模块页的 backdrop, tint = cardBg, rimColor = cardBorder, shape = 12dp)`；`backdrop == null`（用户关模糊 / 设备不支持）时按 `XGlassSurface` 既有降级走「tint 合成到不透明底色」，仍是霓虹紫调而不是墨绿 |
| 遮罩 | `Xc.colors.backdropScrim`（`#0B0F10` @ 0.80），覆盖模块页内容区，点击关闭 |
| 拖柄 | 40dp × 4dp，居中，`textSub` @ 0.40 |
| 内边距 | 20dp（`p-5`） |

内容（自上而下，行结构照 HTML 详情卡）：

| 元素 | HTML 原文 | 取值 |
| --- | --- | --- |
| 模块名 | `<h2 class="text-xl font-bold text-white">` | 20sp Bold `white` |
| 版本行 | `flex justify-between items-center border-b border-white/5 pb-3` | 标签「版本」14sp `textSub`；值 14sp `white`；`border-b` = 1dp `white` @ 0.05；下内边距 12dp |
| 作者行 | 同上 | 标签「作者」14sp `textSub`；值 14sp `white`；同样 1dp `white` @ 0.05 + 12dp |
| 介绍行 | `<span class="text-textSub text-sm block mb-2">介绍</span>` + `<p class="text-white text-sm leading-relaxed whitespace-pre-line">` | 标签 14sp `textSub`，下间距 8dp；正文 14sp `white`、行高 1.625、**保留换行**（`whitespace-pre-line`）；同样 1dp `white` @ 0.05 + 12dp |
| 更新 URL 行 | `<a class="text-neonCyan text-sm break-all flex items-center gap-1"><i class="fa fa-external-link"></i><span>` | `accentCyan` 14sp、允许任意位置换行、行首一枚外链图标（16dp）；**无下边框**；URL 为空时整行不绘制 |
| 按钮组 | `<div class="grid grid-cols-4 gap-3">` | `Row`，`spacedBy(12.dp)`，四枚 `weight(1f)`（gap 12px） |
| 底部提示 | `<p class="text-center text-[11px] text-textSub opacity-50">Action、WebUi 仅当模块包含对应能力时可点击</p>` | 11sp `textSub` @ 0.50，居中 |

按钮外观（HTML `.detail-action`，逐条对应）：

| 项 | 取值 |
| --- | --- |
| 圆角 | 12dp |
| 底色 / 描边 | `cardBg`（`rgba(20,20,45,0.6)`）/ 1dp `actionBorder`（`rgba(139,92,246,0.25)`） |
| 内边距 | **垂直 14dp、水平 4dp**（`padding: 14px 4px`） |
| 排布 | 图标在上、文字在下，间距 **7dp**（`gap: 7px`），整体居中 |
| 图标 | 20dp，色 `accentPurple`（`i { font-size: 20px; color: #8b5cf6 }`） |
| 文字 | 11sp `textMain`（`font-size: 11px; color: #e0e7ff`） |
| 按下态 | `scale(0.93)` + 底 `actionBgPressed` + 描边 `actionBorderPressed`，`200ms ease`（`transition: all .2s ease`） |
| Uninstall 变体 | 描边 `dangerBorder`；图标 `danger`；按下态底 `dangerBgPressed` + 描边 `danger` @ 0.60 |
| 置灰 | `opacity: 0.35` + `filter: grayscale(0.6)` + `pointer-events: none` → `Modifier.alpha(0.35f)` + 图标与文字色 `Color.lerp(c, Color.Gray, 0.6f)`，且不注册点击 |

按钮文案与图标：

| 按钮 | 文案 | 图标（首选 → 回退） |
| --- | --- | --- |
| Action | `R.string.action` | `Icons.Rounded.Terminal` → `Icons.Rounded.PlayArrow` |
| WebUi | `R.string.webui`（**新增**，值为 `WebUi`） | `Icons.Rounded.Language` → `Icons.Rounded.Code` |
| Update | `R.string.module_update` | `MiuixIcons.UploadCloud`（现用） |
| Uninstall | `R.string.uninstall` / `module.remove` 为真时 `R.string.undo` | `MiuixIcons.Delete` / `MiuixIcons.Undo`（现用） |

`androidx-compose-material-icons-extended` 已在 `manager/gradle/libs.versions.toml` 声明并随 compose-bom 2026.08.00 解析，且工程内已在用 `Icons.Rounded.AspectRatio` / `LayersClear` / `BlurOn` / `DesignServices` / `WaterDrop` 这类只有 extended 包才有的图标，故 `Terminal` / `Language` 可用性很高；本机 `~/.gradle/caches` 为空、无法离线核实，故保留回退列，实现时以编译器为准。同理 `fa-external-link` → `Icons.Rounded.OpenInNew`（回退 `MiuixIcons.…` 或 `Icons.Rounded.Link`）。

入场 / 退场：`slideInVertically(initialOffsetY = { it }) + fadeIn` / `slideOutVertically + fadeOut`，180ms；返回键由 `NavigationBackHandler` 接管 = 关闭面板。

## 6. 交互规格

### 6.1 手势状态机

短按与长按必须写在**同一个 `pointerInput`** 里，否则两个手势识别器会互相竞争。

```
awaitEachGesture {
    down = awaitFirstDown(requireUnconsumed = false)   // 不立刻消费，列表照常能滚
    progress.snapTo(0f)
    launch { progress.animateTo(1f, tween(1000, LinearEasing)) }
    loop {
        event = awaitPointerEvent()
        if (位移 > 10.dp) → 取消
        if (!pressed)     → 抬起：若已跑满 1000ms 则视为长按，否则视为短按
    }
}
```

- **取消**（位移超 10dp / 提前抬起）：`progress.snapTo(0f)` —— 与 HTML 一致（移除 `pressing` 类后宽度瞬时归零，无回退动画）；缩放与描边按 5.5 用 150ms 过渡回常态。不触发任何动作，纵向滚动不受影响。
- **长按触发**：进度跑满 1000ms 且未取消 → `HapticFeedbackType.LongPress`（等价于设计稿的 `navigator.vibrate(30)`）+ 打开面板 + 消费该次 up 事件，避免紧接着误触发短按。
- **短按**：1000ms 内抬起且位移 < 10dp → 按 A1 处理（含 WebUI 则打开 WebUI）。
- HTML 另外把 `contextmenu` 拦掉了（防止移动端长按弹出系统菜单）。Compose 侧对应做法：卡片内文字设 `user-select: none` 的等价物 —— `Modifier.pointerInput` 之外不再挂 `SelectionContainer`，并通过消费 down 事件抑制系统长按菜单。

震动反馈在本工程属于**首次引入**：全 manager 目录此前没有任何 `performHapticFeedback` / `HapticFeedbackType` 调用。本次采用 Compose 侧的 `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`（对应 HTML 的 `navigator.vibrate(30)`），不用 `LocalView` + `HapticFeedbackConstants`，便于在无震动硬件的设备上静默降级。

### 6.2 按钮可用性

| 按钮 | 可用条件 | 置灰表现 |
| --- | --- | --- |
| Action | `module.hasActionScript && module.enabled && !module.remove` | 置灰 |
| WebUi | `module.hasWebUi && module.enabled && !module.remove` | 置灰 |
| Update | `updateUrl.isNotEmpty() && !module.remove` | 置灰 |
| Uninstall | 恒可用 | 不置灰；`module.remove` 为真时文案与图标切换为「撤销」 |

置灰的**视觉**完全照 HTML（α0.35 + grayscale 0.6 + 不响应点击）。触发条件上，HTML 只判 `!d.action` / `!d.webui`（它是一份写死能力的静态 demo），真机上模块被禁用时 `action.sh` 与 WebUI 本身跑不起来，故额外带上 `module.enabled`，沿用上游 `actionButtonsEnabled` 的语义 —— 这是 0.3 节列的「应用侧必要扩展」，不影响观感。

### 6.3 动作接线

面板不新增任何业务逻辑，全部转发到既有 `ModuleActions`：

| 按钮 | 回调 |
| --- | --- |
| Action | `actions.onExecuteModuleAction(module)` |
| WebUi | `actions.onOpenWebUi(module)` |
| Update | `actions.onRequestUpdateConfirmation(module, updateInfo)` |
| Uninstall | `module.remove` ? `actions.onUndoUninstallModule(module)` : `actions.onRequestUninstallConfirmation(module)` |

点击后**先关闭面板再执行动作**，避免确认框叠在面板上。长按 Action / WebUi 走 A2 的 `onModuleAddShortcut`。

### 6.4 面板状态存放

面板只持有一个 `moduleId: String?`（`rememberSaveable`），显示时从 `uiState.moduleList` 反查 `Module`。不持有 `Module` 对象本身，避免刷新后拿到过期数据。

## 7. 改动清单

| 文件 | 改动 |
| --- | --- |
| `ui/design/token/XcNeon.kt` | **新增**。霓虹色板（5.1），仅模块页取用。 |
| `ui/screen/module/ModuleActionSheet.kt` | **新增**。底部操作面板：遮罩、滑入退场、返回键接管、HTML 详情卡内容、四枚 `.detail-action` 按钮、能力提示。 |
| `ui/screen/module/ModuleMiuix.kt` | **重写** `ModuleList`(698-765) 与 `ModuleItem`(767-962)（卡片换霓虹皮肤 + 长按状态机 + 进度条 + 按压态）；**删除** `SlideActionButton`(964-995) 与 `detectHorizontalDragGestures` 相关逻辑；`ModulePagerMiuix` 增加 `pressedModuleId` 状态、页面光晕 `drawBehind`、长按提示文案、面板挂载点、安装 FAB 换成 64dp 渐变态。 |
| `res/values/strings.xml` | **新增 3 条**：`webui` = `WebUi`；`module_long_press_hint` = `长按模块横幅 1 秒查看详情`；`module_sheet_capability_hint` = `Action、WebUi 仅当模块包含对应能力时可点击`。 |
| `res/values-zh-rCN/strings.xml` | 同上 3 条的中文档（其余 45 个语言档靠英文默认档回退，不逐个补译）。 |
| `ui/screen/module/ModuleUiState.kt` | 不改。 |
| `ui/screen/module/ModuleScreen.kt` | 不改。 |
| `ui/viewmodel/ModuleViewModel.kt` | 不改。 |
| `ui/design/.../XGlassDialog.kt` | 不改（面板不复用它）。 |

按钮文案严格按 HTML 取 `WebUi`（新增字符串）而不是复用 `R.string.open`（`Open`）；`WebUi` 属产品术语，默认档一处定义即可，不逐语言翻译。

`ModuleMiuix.kt` 重写后需一并清理的 import（删掉左滑逻辑与卡片内联按钮行后，这些符号不再被引用，留着会触发未使用告警）：`animateContentSize`、`animate`、`detectHorizontalDragGestures`、`CircleShape`、`SubcomposeLayout`、`Constraints`、`IntOffset`、`Icons.Rounded.PowerSettingsNew`、`Icons.Rounded.PlayArrow`、`Icons.Rounded.Code`；`Modifier.xWaterDropClick` 为**疑似**失效项 —— 它现在只服务于更新药丸与 `SlideActionButton`，两处都会删掉，但若卡片按压态决定复用 `xDropletPressScale` 则保留。实现时以编译器告警逐条确认，不做超前删除。

## 8. 明确不做

- 不改任何全局设计令牌（`XcColors` / `XcShapes` / `XcTheme`），其他页面视觉零变化。
- 不改页面底色、背景图、全局光效（`BgEffectBackground`）；5.2 的光晕只画在模块页内容容器上。
- 不改底部导航栏、搜索栏（`SearchBarFake` / `SearchBox` / `SearchPager` 是共享组件）。
- 不改顶栏（`TopAppBar` + Sort 弹窗 + 下载入口）：HTML 顶栏是「标题 + 徽标 + 下载」，其中徽标 `111.9` 是 demo 数据，本项目顶栏的排序/仓库入口要保留，故顶栏结构不动；顶栏的墨绿底与模块页的霓虹卡片并存，是 D3「仅模块页换肤」的必然结果。
- 不做模块详情页路由（设计稿的 `#page-module-detail`）—— D1 已选底部面板，详情卡内容已 1:1 进面板（5.7）。
- 不改 `ModuleRepo`（模块仓库）相关页面。
- 不改 `ModuleShortcutState` / `ModuleShortcutDialog`（A2 复用）。

## 9. 风险与验证

| 风险 | 处理 |
| --- | --- |
| 长按与 `LazyColumn` 纵向滚动抢手势 | 位移阈值 10dp 内不消费事件；超过阈值立即取消长按，滚动交由列表处理。 |
| 短按与长按两个识别器竞争 | 强制写在同一个 `pointerInput` 内，不叠加 `detectTapGestures`。 |
| 长按跑满后抬起事件继续下传，误触发短按 | 触发长按时消费该次 up，并置一个已触发标记让短按分支跳过。 |
| `Modifier.blur` 在 API < 31 上不可用 | 按 5.5 退化为 3 层递减圆角描边；两档都只在按压期间绘制。 |
| 无模糊设备上面板回退成墨绿 | 面板 `tint` 直接给 `cardBg`，走 `XGlassSurface` 的「tint 合成到不透明底」分支，回退后仍是紫调。 |
| 面板压在底部导航之上导致遮挡 | 面板底边距取与列表同一个 `bottomInnerPadding`，并量测底栏高度变化（导航轨模式下为 0）。 |
| 浅色主题下霓虹不可读 | 提供浅色档色板（5.1），不直接复用暗色档取值。 |
| 介绍文本很长把面板撑满 | 面板高度上限 60% 可用高，正文区域内部纵向滚动。 |

验证方式：

1. `./gradlew :manager:app:assembleDebug`（或仓库等价的 manager 构建任务）编译通过 —— **本机无 JDK / 无 Android SDK / 无 Gradle 缓存，本地跑不了**，改为推分支让 GitHub Actions（`build-xecpro.yml`）编译验证；
2. 装机后逐项走查：长按 1s 弹面板 + 震动、中途松手进度条瞬时归零、纵向滑动列表仍可滚、卡片按压缩放/描边/辉光、面板四按钮置灰规则、点击后先关面板再出确认框、模块禁用时 Action/WebUi 置灰、待移除模块 Uninstall 变「撤销」、安装 FAB 渐变色与 64dp、浅色/深色/AMOLED 三档观感；
3. 与设计稿逐帧比对：页面光晕位置/浓度、卡片内边距 18/16、进度条 3dp 下两角圆角、按钮 14×4 内边距与 7dp 间距、按下态 0.93 缩放；
4. 切到其他页面确认视觉零变化。
