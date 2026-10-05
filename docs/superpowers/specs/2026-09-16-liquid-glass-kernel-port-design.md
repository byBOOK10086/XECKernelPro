# 液态玻璃内核移植：以上游 `LENS_AGSL` 替换现有折射内核，并经玻璃控件层覆盖主流页面

日期：2026-09-16
状态：**设计已获用户批准**（用户 2026-09-16 拍板：「该内核＋新增玻璃控件层」「主流页面全覆盖」「就这样 这个UI效果要保留 怎么安全怎么来」）
上游：`QWEA0/Liquid-Glass-Android`（本地台账：`GlassLensRenderer.kt` / `GlassRuntimeEffects.kt` / `LightSourceController.kt` / `GlassAccessibility.kt` / `BackdropLuminanceMeter.kt`）
口径：**上游 UI 效果完整保留、不许缩水；落地策略一切以「稳」为先** —— 不碰 native、不重建降级链、AGSL 出问题必须熔断兜底。

## 0. 基准与口径

| # | 用户原话 | 本 spec 的落地口径 |
| --- | --- | --- |
| ① | 「该内核＋新增玻璃控件层吧」 | **只做两件事**：把上游统一透镜着色器搬进现有 `lens()`（内核），并在 `ui/design/glass/` 增补控件层。**不整壳移植 View widget**、不动 NDK/CMake。 |
| ② | 「主流页面全覆盖」 | 覆盖由**内核升级自动继承**达成（所有玻璃调用点共用同一入口），而不是逐页重写 UI；再对主流页面的卡片**分批**开玻璃实体。见第 6 节。 |
| ③ | 「就这样 这个UI效果要保留 怎么安全怎么来」 | 上游效果**逐通道映射、不做减法**（第 4.2 节映射表逐条给出每个 uniform 的着落）；安全由第 7 节的 11 条措施兜底，其中「旧着色器不删 + 全局开关 + 异常熔断」是三条硬要求。 |

凡本 spec 出现的上游源码行号，均来自本地已下载的台账文件，而非记忆；凡本 spec 无法核实的具体数值（如上游 View 层默认参数），一律标注为「实现时抄上游」，**不臆造**（见第 11 节）。

## 1. 目标

1. 把上游的**统一透镜着色器**（Apple Liquid Glass 完整光学模型，单 pass）替换掉本项目现有那个只做圆角矩形折射的着色器；
2. 在 `ui/design/glass/` 增补一层控件能力，让「玻璃实体」可以像现在的 `xGlassRim` 一样，以 **Modifier** 形式贴到任意容器上；
3. 用 1+2 让首页 / 模块 / 超级用户 / 设置 / 关于等主流页面真正吃到新内核，且**每个文件可单独回滚**。

## 2. 现状（取证结论）

### 2.1 上游资产盘点与可移植性判定

| 上游文件 | 内容 | 判定 | 依据 |
| --- | --- | --- | --- |
| `GlassLensRenderer.kt` 的 `LENS_AGSL`（`:54–:274`） | 单 pass 统一着色器：圆角盒 SDF（逐角半径）→ 斜面剖面 → 法线 → 折射 → 三通道色散 → 饱和度 → 染色 → 光照亮线/辉光。**零 Bitmap、零回读、背景是活的** | **✅ 唯一可移植资产，本任务的核心** | 纯 AGSL 字符串，与宿主无关；已用 `runtimeShaderEffect` 同构寄宿（本项目 `Lens.kt` 已在用同一 miuix API） |
| `GlassLensRenderer.kt` 的 `draw()`（`:355–:394`） | `RenderNode` + `Canvas` + `glassView.backdropView` 录制/合成 | **❌ 不可复用** | `:364` 直接 `val parent = glassView.backdropView ?: return false`；`:310/:319` 用 `RenderNode.setUseCompositingLayer`。整套是 **View 体系**实现，Compose 侧没有对应物 |
| `GlassRuntimeEffects.kt` | `VIBRANCY_AGSL` / `RIM_AGSL` / `ADAPTIVE_TINT_AGSL` 三个 API 36 专用滤镜 | **❌ 不可用** | `@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.BAKLAVA)`；本项目 `minSdk = 31`。**但**其 vibrancy 曲线已被 `LENS_AGSL` 内联合并（`:213–:225`），能力不丢 |
| 上游 native（`libnativegauss.so`） | 只做 IIR / box 高斯模糊 | **❌ 不可复用，也不需要** | 上游 AAR 只含 `arm64-v8a` + `armeabi-v7a`，本项目 `abiFilters = ["arm64-v8a", "x86_64"]`；且模糊继续走 miuix `blur()` |
| `LightSourceController.kt` | 重力/加速度传感器驱动光照方向 | ⭕ 可选增量 | 宿主是 `View`（`register(view: View)` + `postInvalidateOnAnimation()`），Compose 化需改写 |
| `GlassAccessibility.kt` | 降透明度 / 降动效 / 省电模式三态 | ⭕ 可选增量 | 纯 Android API，改写成本低 |
| `BackdropLuminanceMeter.kt` | 全局亮度采样 → 驱动 `adaptiveTint` | ⭕ 可选增量 | 需要跨层取背景位图，本项目当前无此链路 |

**结论**：上游能真正搬过来的，就是 `LENS_AGSL` 这一段；其余要么宿主不兼容，要么 API 等级不够。这不是取舍，是事实。

### 2.2 本项目玻璃体系与调用面

三层结构：`ui/design/token/`（Xc* 令牌）→ `ui/design/glass/`（`XGlassSurface` / `XGlassCard` / `XGlassBar` / `XGlassDialog`）→ `ui/component/liquid/`（`Lens.kt` / `Vibrancy.kt` / `InnerShadow.kt` / `CombinedBackdrop.kt`）。

关键：**除 `FloatingBottomBar.kt` 的 3 处手写外，所有玻璃绘制都收口到 `XGlassSurface` 里的同一段 `effects`**（`XGlassSurface.kt:74–:83`，其中 `padding` 赋值在 `:78`、`onDrawSurface` 在 `:83–:85`）：

```kotlin
.drawBackdrop(
    backdrop = backdrop!!,
    shape = { shape },
    effects = {
        val refractPx = refraction.toPx()
        padding = maxOf(28.dp.toPx(), refractPx)
        vibrancy()
        blur(blurRadius.toPx(), blurRadius.toPx())
        lens(refractionHeight = refractPx, refractionAmount = refractPx)
    },
    onDrawSurface = { drawRect(tint) },
)
```

调用面实测（已排除 `import` 行与函数定义行）：

| 载体 | 规模 | 说明 |
| --- | --- | --- |
| `Card(modifier = Modifier.xGlassRim(shape))` | **42 处 / 13 文件** | **主旋律**。`Card(...)` 调用同为 42 处 / 13 文件，与 `xGlassRim` **逐文件一一对应**（全库 `Card(` 字面命中 43 处 / 14 文件，多出的 1 处是 `ui/design/token/XcShape.kt:61` 的 KDoc 提及，非调用点）。这些卡片目前**只有一圈 1dp 亮边**，卡体是 miuix 自己的不透明底色，**没有模糊也没有折射** |
| `BlurredBar(...)` | **18 处 / 17 文件** | 唯一入口是 `ui/util/BlurExt.kt:62`，内转 `XGlassBar`（→ `XGlassSurface`） |
| `XGlassDialog(...)` | **7 处 / 3 文件** | 内转 `XGlassSurface`。分布：`WebUIMiuix.kt` 3、`XDialogHost.kt` 3、`FlashUtils.kt` 1 |
| `XGlassSurface(...)` 直接调用 | **4 处 / 3 文件**（其中 2 处在 `XGlassSurface.kt` 内部；外部只有 `XGlassDialog.kt` 1 处与 `ModuleActionSheet.kt` 1 处） | 真·玻璃实体 |
| 手写 `drawBackdrop(...)` | **3 处，全在 `FloatingBottomBar.kt`** | `:358`（药丸主画面）/`:411`（叠层）/:443（水滴；`depthEffect = true` 在 `:451`、`chromaticAberration = 0.5f` 在 `:452`）。**改造必回归** |
| `XGlassCard(...)` | **0 处** | 该控件当前无调用点；玻璃层实际被使用的只有 `XGlassBar`（经 `BlurExt.kt`）与 `XGlassDialog` |

> 全库 `drawBackdrop(` 共 6 处 / 3 文件：除上表的 `FloatingBottomBar.kt` 3 处外，`CombinedBackdrop.kt` 2 处是转发（`first`/`second` 各一次）、`XGlassSurface.kt` 1 处是主入口。

卡片亮边的实际分布（`xGlassRim` 调用点）：`SettingsMiuix.kt` 7 > `AppProfileMiuix.kt` 6 = `HomeMiuix.kt` 6 > `InstallMiuix.kt` 5 = `ModuleRepoMiuix.kt` 5 > `ColorPaletteScreenMiuix.kt` 3 > `SulogMiuix.kt` 2 = `SuperUserMiuix.kt` 2 = `KpmScreen.kt` 2 > `TemplateMiuix.kt` 1 = `TemplateEditorMiuix.kt` 1 = `AboutMiuix.kt` 1 = `WarningCard.kt` 1（组件）。**`ModuleMiuix.kt` 内没有卡片亮边**，它只通过 `BlurredBar` 1 处吃玻璃。

### 2.3 现有 `lens()` 的能力边界

`Lens.kt` 全文 216 行，来源 Kyant0/AndroidLiquidGlass（Apache 2.0）。现有能力：圆角矩形 SDF（逐角半径）→ `circleMap` 折射 → 可选 7 次 `content.eval` 光谱色散。**没有**：真实斜面剖面、贴边亮线与光照角度瓣、边缘柔化、边缘覆盖率输出、采样安全区钳制、逐像素染色、触摸凸起。

## 3. 架构：一个内核，一处升级，全局受益

```
                     ┌─ XGlassCard ─┐
XGlassSurface ───────┤              ├──► Modifier.drawBackdrop{ vibrancy(); blur(); lens() }
  (唯一玻璃绘制入口)  └─ XGlassBar ──┘                    │
                                                        ▼
                                          Lens.kt :: lens()  ◄── 唯一改写点（内核）
                                                        │
                    FloatingBottomBar.kt (3 处手写) ────┘
```

- **改写点只有一个函数**：`Lens.kt` 的 `lens(...)` 内部 **+ 新的着色器常量 + 新的 uniform 编排**。
- 函数签名 `lens(refractionHeight, refractionAmount, depthEffect = false, chromaticAberration = 0f)` **一字不改** ⇒ 调用点（`XGlassSurface` 1 处 + `FloatingBottomBar` 3 处）**零改动**。
- 由此，**第 2.2 节表里的 42 处卡片亮边之外，其余玻璃实体（`BlurredBar` 18 处、`XGlassDialog` 7 处、`XGlassSurface` 直调 4 处、手写 `drawBackdrop` 3 处）在批次 0 就自动拿到新内核**（首页顶栏、底部药丸栏与水滴、模块操作面板、WebUI / Flash 弹窗…）。

这是「主流页面全覆盖」最省、也最稳的路径：**不是逐页铺，是内核换一次，全局继承**。

## 4. 坐标与参数映射（等价性推导）

### 4.1 坐标约定 —— 两套模型等价，无需重写

| | 上游 `LENS_AGSL` | 本项目现有实现 |
| --- | --- | --- |
| 录制内容坐标 | `coord`（含 `margin` 外扩） | `coord`（含 `padding` 外扩） |
| 视图局部坐标 | `p = coord - margin`，形状参数都在此空间 | `centeredCoord = (coord + offset) - halfSize`，`offset = -scaledPadding` |
| 全尺寸 | `viewSize` | `size`（= 视图尺寸，**不含** padding） |
| 外扩量 | `margin` | `scaledPadding` |

对应关系：`margin ↔ scaledPadding`、`viewSize ↔ (scaledSizeW, scaledSizeH)`、`p ↔ centeredCoord + viewSize/2`。**因此上报值时 `margin = scaledPadding`、`viewSize = size / sf` 即可，坐标模型一行不用改。**

外扩可行性已核：`XGlassSurface.kt:78` 的 `padding = maxOf(28.dp.toPx(), refractPx)` 恒 ≥ `refractPx`，而 `Lens.kt:22–24` 的 `if (padding < refractionAmount) padding = refractionAmount` 保留不动 ⇒ 录制区一定容得下折射采样。

### 4.2 uniform 映射表（逐条给出着落，**不缩水**）

| 上游 uniform | 本项目取值 | 效果是否保留 |
| --- | --- | --- |
| `margin` | `scaledPadding` | ✅ |
| `viewSize` | `(scaledSizeW, scaledSizeH)` | ✅ |
| `shape1` | `(viewSize.x/2, viewSize.y/2, viewSize.x/2, viewSize.y/2)` —— 形状即整个视图矩形 | ✅ |
| `radii1` | 现有 `roundedRectCornerRadii()` 结果 `/ sf`（逐角、已含 RTL 与 `minDimension/2` 钳制） | ✅ |
| `shape1L` | **= `shape1`**（不向视图外延伸）。理由：本项目所有玻璃都是完整圆角矩形、四边都在视图内；只有「平边贴边拼接」场景才需要延伸，本项目无此场景。 | ✅（该能力保留在着色器里，只是不启用） |
| `rimSoft` | 默认 `0`（关）。开启后单像素 `content.eval` 从 3 次涨到 9 次，属性能敏感项 | ✅ 保留为可选档 |
| `shape2` / `radius2` / `blendK` | 默认 `shape2.z = 0`（副形状禁用）。本项目 `FloatingBottomBar` 的「药丸 + 水珠」是用**两次独立绘制 + `CombinedBackdrop`**（`ui/component/liquid/CombinedBackdrop.kt`）实现的，不走 `shape2` 融合 | ✅ 保留为可选档 |
| `bevel` | 起点 `= scaledRefractionHeight`（即调用点现值）。若首版观感斜面偏宽，只调**映射系数**（如 `bevel = refractionHeight * 0.5`），不动调用点参数 | ✅ |
| `refractPx` | `scaledRefractionAmount` | ✅ |
| `falloff` | 默认 `0`（平方斜面 `edge*edge`）—— 剖面单调性（贴边最强、带末端归零）与现有 `circleMap` 同族，观感连续；`depthEffect = true` 时映射为 `2.0`（引力透镜式逆幂剖面，贴边剧烈、内侧缓慢回落，即「球面/深度感」） | ✅ 两种剖面都在 |
| `refractDir` | **`-1`（向内采样，与 iOS 一致）**。⚠️ 这是**唯一一处有意的观感变化**：现有实现是向外采样（凸透镜味），上游默认向内（边缘呈内侧背景的压缩镜像）。这正是「顶替掉原来的液态玻璃效果」的落点；靠第 7 节开关可一键退回 | ✅（且是本任务的主要升级之一） |
| `sampleLo` / `sampleHi` | `(0,0)` ~ `(scaledSizeW + 2*margin, scaledSizeH + 2*margin)`。向内采样时采样点天然落在录制区内（见 4.1 外扩可行性的核查），这里是双保险 | ✅ |
| `dispersion` | `chromaticAberration` 直接传入（上游多色散档位由 `lens()` 现有参数承载，映射关系比现有 7 次 eval 更省） | ✅ |
| `lightDir` | **固定 `(0.866, 0.5)`** = 上游 `LightSourceController` 的 `DEFAULT_X`/`DEFAULT_Y`（左上方约 30°）。不接传感器 ⇒ 高光位置稳定、不引入新权限与新失效面（传感器驱动列为可选增量） | ✅ 高光能力完整，只是方向恒定 |
| `specStrength` | 上游 View 层默认值（**实现时抄上游，本 spec 不臆造**）。首版若与既有 `xGlassRim` 亮边叠加后边缘过亮，**优先下调 `specStrength`，不动 `xGlassRim`** —— 因为非 AGSL 设备上 `xGlassRim` 是唯一的边缘定义 | ✅ 新增能力 |
| `rimBandMax` | 上游默认值（实现时抄）；约束：小控件按短边收 | ✅ 新增能力 |
| `tintColor` / `adaptiveTint` / `glassTint` / `dimAmount` | **全部旁路**：`tintColor = (0,0,0,0)`、`adaptiveTint = 0`、`glassTint = (0,0,0,0)`、`dimAmount = 0`。染色语义**继续由既有 `onDrawSurface = { drawRect(tint) }` 负责**（`XGlassSurface.kt:83–85`） | ✅ 染色能力一行没丢，只是换了个已有通道执行，色板零变化 |
| `satFactor` | **`1.0` 旁路**，饱和度继续由既有 `vibrancy()`（`colorControls(saturation = 1.5f)`）负责，避免与内核内曲线重复提饱和 | ✅ 内核里那条 vibrancy 曲线保留为可选档 |
| `press` / `touchPos` / `touchAmp` | 默认 `0` / `(0,0)` / `0`。本项目玻璃层不在手势链上，无按压输入。`FloatingBottomBar` 的 `dampedDragAnimation.pressProgress` **可选**接到这里，让水珠拖拽时局部凸起 | ✅ 触摸凸起保留为可选档 |

**结论**：上游着色器的每一项能力，要么默认启用、要么以保留下拉的方式接在本项目既有通道上，**没有任何一项被删掉**。

### 4.3 与现有实现的行为差异（必须知情）

| # | 差异 | 影响面 | 处置 |
| --- | --- | --- | --- |
| 1 | 采样方向 向内 ↔ 向外 | **全部玻璃调用点** | 有意为之（第 4.2 节）；开关可退 |
| 2 | 输出 alpha 由 `cov`（SDF 覆盖率、1.5px 抗锯齿）决定，不再直接透传 `content` 的 alpha | 全部调用点 | 形状内 `cov = 1`，与现状等价；仅边缘多一层 1.5px AA，观感更干净 |
| 3 | 新增贴边亮线 + 两道光照角度瓣（`hair` / `glow`） | 全部调用点，与 `xGlassRim` 叠加 | `specStrength` 单点可调 |
| 4 | 斜面剖面由 `circleMap` 换成 `edge*edge` | 全部调用点 | `bevel` 映射系数可调；`falloff` 可回引力透镜剖面 |

## 5. 玻璃控件层

**不新增平行控件**（新增 `XGlassXxx` 只会造出第二套体系，风险大于收益）。改法是**把玻璃实体抽成 Modifier**，让「不能换容器、只能换 modifier」的场景（即那 42 处 miuix `Card` + `xGlassRim`）也能吃到新内核。

在 `ui/design/glass/XGlassSurface.kt` 内：

| 新增/改动 | 形态 | 说明 |
| --- | --- | --- |
| `Modifier.xGlassBackdrop(backdrop, shape, tint, blurRadius, refraction, ...)` | **internal Modifier 扩展** | 把 `XGlassSurface` 里现有那段 `drawBackdrop + clipTo + xGlassRim + innerShadow` 原样抽出来，供 `XGlassSurface` 与外部卡片共用（纯重构，行为不变） |
| `Modifier.xGlassBody(backdrop, shape, tint, ...)` | **public Modifier 扩展** | 对外入口：给任意容器铺玻璃实体。`backdrop == null` 或设备不支持时按既有降级走 `background(solidTint)`。命名暂定，实现时可换 |
| `XGlassSurface(...)` | 增加**带默认值**的可选参数 `bevel: Dp = refraction`、`specStrength: Float`、`rimBandMax: Dp`、`falloff: Float = 0f`、`outward: Boolean = false` | 全部有默认值 ⇒ `XGlassCard`（0 调用点）/ `XGlassBar`（经 `BlurExt.kt`）/ `XGlassDialog`（7 处）/ `XGlassSurface` 直调 4 处的既有写法**零改动**即有新内核 |
| `XGlassBar` | 增加 `specStrength` / `bevel` 透传（可选） | 底栏是观感最重的地方，需要单独试档 |

改动后各屏的写法（以首页 `HomeMiuix.kt:452` 为例）：

```kotlin
// 现在：只有一圈亮边，卡体是 miuix 不透明底色
Card(modifier = modifier.xGlassRim(Xc.shapes.md), ...)

// 之后：卡体真玻璃 + 亮边（亮边由 xGlassBody 内部一并画，不再手动叠 xGlassRim）
Card(
    modifier = modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
    colors = CardDefaults.defaultColors(color = Color.Transparent),
    ...
)
```

要点：**miuix `Card` 的 `onClick` / `showIndication` / `pressFeedbackType = Tilt` 全部保留**，只是底色调成透明、玻璃由 modifier 画 —— 这是这 42 处卡片能耗最小、最可控的改法。

## 6. 主流页面覆盖（分三批，逐批可回滚）

| 批次 | 范围 | 内容 | 回滚 |
| --- | --- | --- | --- |
| **0** | `Lens.kt` 内核 | 换着色器 + uniform 编排。**零页面改动** | 切开关回旧内核 |
| **1** | 首页 `HomeMiuix.kt`(6 处) + 设置 `SettingsMiuix.kt`(7 处)，共 13 处 | 卡片 `xGlassRim` → `xGlassBody` + 透明底 | 逐文件 revert |
| **2** | 其余 11 个带亮边文件、共 29 处：`AppProfileMiuix.kt`(6)、`ModuleRepoMiuix.kt`(5)、`InstallMiuix.kt`(5)、`ColorPaletteScreenMiuix.kt`(3)、`SulogMiuix.kt`(2)、`SuperUserMiuix.kt`(2)、`KpmScreen.kt`(2)、`TemplateMiuix.kt`(1)、`TemplateEditorMiuix.kt`(1)、`AboutMiuix.kt`(1)、`WarningCard.kt`(1) | 同一改法铺开 | 逐文件 revert |

> 上表批次 1+2 合计 13 + 29 = **42 处**，与第 2.2 节的卡片亮边总数一致。**没有卡片亮边的文件不进任何批次**（如 `ModuleMiuix.kt`、`ModuleActionSheet.kt`、`ExecuteModuleActionMiuix.kt`、`FlashMiuix.kt`、`WebUIMiuix.kt`、`TerminalPager.kt` 等），它们本来就走 `BlurredBar` / `XGlassDialog` ⇒ 批次 0 已自动受益，不需要再动。

批次 0 是**收益最大、风险最小**的一步：一次改动即让首页顶栏、底部药丸栏与水珠、模块操作面板、WebUI / Flash 弹窗全部换新内核（因为这些本来就走 `XGlassSurface`）。批次 1、2 是把「只有一圈亮边」的卡片升级成「真玻璃卡体」，视觉收益大但涉及 42 处，故按文件切分。

批次 0 完成后**先出版本让用户过眼**，再决定是否进入批次 1 —— 这一步的观感（尤其第 4.3 节的 4 条差异）是后面要不要继续铺的前提。

### 6.1 注意事项

- **13 个带亮边的文件全部已经 import `xGlassRim`**（含曾误判的 `SuperUserMiuix.kt`，其 `import me.weishu.kernelsu.ui.design.glass.xGlassRim` 在 `:74`）⇒ 批次 1/2 只需把这一行 import 换成 `xGlassBody`，**不引入任何新包依赖**（两者同在 `ui.design.glass`）。因此 `xGlassBody` 必须放在 `XGlassSurface.kt` 内与 `xGlassRim` 同文件。
- `AboutMiuix.kt:313/345` 的 `textureBlur(shape = RectangleShape, …, contentBlendMode = DstIn)` 是**合法例外**（那是模糊的取样区域，不是容器圆角，结果还会被图标/文字的 alpha 做 DstIn 遮罩），不在改造范围内，spec 里「不许直角」的约束**不适用于它**。
- `FloatingBottomBar.kt` 的 3 处手写 `drawBackdrop` 是批次 0 的**重点回归对象**：`:443` 那处传了 `depthEffect = true, chromaticAberration = 0.5f`，对应第 4.2 节 `falloff = 2.0` 与 `dispersion`。
- `ModuleRepoMiuix.kt` 有 3 处 backdrop 传递（`:1138/1145/1165`），列表项级 backdrop 复用面比预想广，批次 2 时逐处确认 backdrop 是否非空。

## 7. 安全措施（「怎么安全怎么来」的落点）

| # | 措施 | 说明 |
| --- | --- | --- |
| 1 | **旧着色器常量保留不删** | `ROUNDED_RECT_REFRACTION_SHADER`、`ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER`、`ROUNDED_RECT_SDF` 原样留在 `Lens.kt` 里，作为回退路径。**这是最硬的一条：任何观感不满都能一行切回** |
| 2 | **全局内核开关** | 新增 `internal object XcGlassKernel { var current: Kernel = Kernel.NEW }`（`Kernel.NEW` / `Kernel.LEGACY`）。`lens()` 按开关选着色器与 uniform 编排 |
| 3 | **异常熔断** | `lens()` 内的 uniform 设置与 `runtimeShaderEffect` 注册包 `runCatching`；一旦抛异常 → 把 `current` 置为 `LEGACY` 并记日志，本进程后续全部走旧内核。等价于上游 `GlassLensRenderer` 的 `shaderBroken` 语义（声明 `:330`、短路 `:361`、AGSL 编译失败 `:371–:376`、effect 构建失败 `:397–:401`） |
| 4 | **新能力默认旁路** | `rimSoft = 0`、`shape2.z = 0`、`blendK = 0`、`falloff = 0`、`satFactor = 1.0`、`adaptiveTint = 0`、`glassTint.a = 0`、`dimAmount = 0`、`press = 0`、`touchAmp = 0` ⇒ 未启用的通道不参与运算、不影响画面 |
| 5 | **`padding` 语义不变** | 仍为 `maxOf(28.dp.toPx(), refractPx)`，`Lens.kt:22–24` 的兜底也不动 ⇒ 录制区尺寸与本 spec 前完全一致 |
| 6 | **`refractDir = -1`（向内）** | 采样点天然落在录制区内，杜绝「读到透明黑」。向外采样所需的「输出区覆盖整个外扩录制区」那套外层合成节点，本项目**不需要引入** |
| 7 | **不碰构建面** | 不动 `abiFilters` / CMake / NDK / native 库；不新增依赖；不改 `minSdk` / `compileSdk` / `targetSdk` / Java 版本 |
| 8 | **不改既有签名** | `lens()` 4 个参数一字不改；`XGlassSurface` 只**新增带默认值**的可选参数 ⇒ 既有调用点零改动即可编译 |
| 9 | **三档降级链原样保留** | `isRuntimeShaderSupported()`（API 33+）→ `textureBlur`（RenderEffect，API 31+）→ 不透明 `solidTint` 兜底，以及 `XGlassSurface.kt:67` 的 `solidTint` 不变量 `if (tint.alpha >= 1f) tint else tint.compositeOver(surface)` **一行不动**。新内核只作用于第一档 |
| 10 | **亮边双通道不打架** | `xGlassRim` 不动（非 AGSL 设备上它是唯一边缘定义）；若叠加后边缘过亮，只调 `specStrength` |
| 11 | **分批独立提交** | 批次 0 / 1 / 2 各自独立提交，可单独 revert |

## 8. 改动清单

| 文件 | 改动 |
| --- | --- |
| `ui/component/liquid/Lens.kt` | **主体改动**。新增 `LENS_AGSL` 常量（上游 `:54–:274` 转写，去掉 Compose 侧不存在的 `press` 输入源即可）；`lens()` 内新增 uniform 编排与新开关分支；**保留**旧常量与旧分支 |
| `ui/component/liquid/XcGlassKernel.kt` | **新增**。内核开关 + 熔断状态（第 7 节措施 2、3） |
| `ui/design/glass/XGlassSurface.kt` | 抽出 `Modifier.xGlassBackdrop(...)`（纯重构）；新增 `Modifier.xGlassBody(...)`；`XGlassSurface` 增可选参数并透传 |
| `ui/screen/home/HomeMiuix.kt` | 批次 1：卡片改 `xGlassBody` + 透明底 |
| `ui/screen/settings/SettingsMiuix.kt` | 批次 1：同上（7 处） |
| 批次 2 的 11 个带亮边文件（见第 6 节） | 批次 2：同上 |
| `ui/component/FloatingBottomBar.kt` | 批次 0/1：3 处手写调用的参数走查（`:443` 的 `depthEffect` / `chromaticAberration` 映射到 `falloff` / `dispersion`）；`.kt` 逻辑不改 |
| `ui/util/BlurExt.kt` | 不改（`BlurredBar` / `rememberBlurBackdrop` 原样，自动受益） |
| `ui/design/token/XcColor.kt` | 不改（色板零变化） |

## 9. 明确不做

- **不移植 `LiquidGlassView`（View 壳）**，不用 `AndroidView` 包装；上游 README 自己也建议 Compose 场景不要这么做。
- **不移植 native**（`libnativegauss.so`）、不改 ABI、不动 CMake/NDK。模糊继续走 miuix `blur()`。
- **不移植 `GlassRuntimeEffects`**（API 36 专用，本项目 `minSdk = 31` 用不上）。
- **不接传感器光源**（`LightSourceController`）—— 固定 `(0.866, 0.5)`，不引入传感器权限与新失效面。
- **不做 `GlassAccessibility` 三态**（降透明度 / 降动效 / 省电模式）。
- **不做 `BackdropLuminanceMeter`**（全局亮度采样 → `adaptiveTint`），故 `adaptiveTint` 默认 `0`。
- **不做按钮玻璃化**。全项目按钮清一色是 miuix `TextButton` + `ButtonDefaults`（`TextButton` 在 **17 个文件**被 import、37 处调用；`IconButton` / `ButtonDefaults` 另计），没有自定义玻璃按钮容器，玻璃化要么换按钮、要么再包一层，成本与风险都远超收益。`XGlassBody` 只铺卡片类容器。
- **不改全局令牌数值**（`XcColors` / `XcShapes` / `XcMotion`），不改 `XGlassCard` / `XGlassBar` 的默认 `blurRadius` / `refraction`。
- **不改页面底色、背景图、全局光效**（`BgEffectBackground`）。
- **不动 `vibrancy()`**（`ui/component/liquid/Vibrancy.kt`，15 行）—— 饱和度继续由它负责，内核 `satFactor` 默认旁路。
- 不在批次 1、2 之外继续往长尾铺（本 spec 到批次 2 为止即达成「主流页面全覆盖」）。

## 10. 风险与验证

| 风险 | 处理 |
| --- | --- |
| 新着色器在真机上编译失败或渲染异常 | 措施 1、2、3（旧着色器保留 + 开关 + 熔断）。发行前在 API 33+ 与 API 31–32 两类设备上各走一遍 |
| 观感漂移（向外 → 向内采样，第 4.3 节第 1 条） | 这是有意为之；若不接受，把 `refractDir` 改 `+1` 并同步放开 `padding` 即可退回旧观感，无需回滚代码 |
| 斜面观感过宽/过窄（`edge*edge` 比 `circleMap` 中段更强） | 只调 `bevel` 的映射系数（第 4.2 节），不动任何调用点参数 |
| 贴边亮线与既有 `xGlassRim` 叠加后边缘过亮 | 只下调 `specStrength`，`xGlassRim` 不动 |
| `rimSoft` 开启后填充率吃紧（单像素 `content.eval` 由 3 次涨到 9 次） | 默认 `0`；仅在个别控件试档，并做帧率对比 |
| miuix `Card` 底色调透明后按压反馈 / 波纹观感变化 | `onClick` / `showIndication` / `pressFeedbackType` 语义不动，只改底色；批次 1 先只改首页做样张 |
| 卡片透明底在「不支持玻璃」的设备上变成看穿背景 | `xGlassBody` 在 `backdrop == null` 或设备不支持时走 `background(solidTint)`（既有降级），**不会**出现全透明卡片 |
| 卡片透明底在半透明背景图上对比度不足 | 沿用 `XGlassSurface.kt:67` 的 `solidTint` 不变量；必要时对 `tint` 的 alpha 单独取档 |
| `RectangleShape` 误伤（`AboutMiuix.kt`） | 第 6.1 节已列为合法例外，改造时跳过 |
| `Shape` 非 `CornerBasedShape` 时 `lens()` 静默失效 | `roundedRectCornerRadii()` 现行逻辑不动（`Lens.kt:62–77`）；`BlurExt.kt` 的 KDoc 已记录过这个坑（曾传 `RectangleShape` → 四角直角 **且** `lens()` 静默失效） |
| RTL 下逐角半径错位 | 复用现有 `roundedRectCornerRadii()`（已按 `layoutDirection` 交换 topStart/topEnd），新着色器的 `radii1` 直接吃这个结果 |
| `downscaleFactor > 1` 时尺寸/半径/位移的缩放不一致 | 沿用既有 `/sf` 处理（`Lens.kt:37–43`），新增的 `margin` / `viewSize` / `bevel` / `refractPx` **一律 `/sf`** |

验证方式：

1. **编译**：`assembleDebug`（仓库等价的 manager 构建任务）。本机无 JDK / 无 Android SDK / 无 Gradle 缓存 ⇒ 本地跑不了，改为**推分支让 GitHub Actions（`.github/workflows/build-xecpro.yml`）跑**。
2. **批次 0 真机走查**：首页顶栏、底部药丸栏（含拖拽水珠）、模块长按面板、WebUI 弹窗、Flash 弹窗、设置页顶栏；对照项 = 边缘压缩镜像是否出现、贴边亮线是否随角变亮变暗、色散是否只在边缘、形状外是否透明无灰边。
3. **降级链走查**：API 33+ 真机（AGSL 档）、API 31–32 真机或开发者选项强制关模糊（RenderEffect 档 / 不透明档），确认后两档与本 spec 前**零变化**。
4. **主题走查**：暗色 / 浅色 / AMOLED 三档，确认色板零变化（染色仍走 `onDrawSurface`）。
5. **开关走查**：`XcGlassKernel.current = LEGACY` 下逐页比对，确认与移植前逐像素一致。
6. **批次 1 / 2 走查**：逐文件改完后单独截图比对，确认卡片按压（Tilt）/ 点击 / 波纹不受影响。

## 11. 待实现时确认

以下是本 spec 明确**不臆造**、需在实现时从上游源码核实的具体数值：

| # | 项 | 来源 |
| --- | --- | --- |
| 1 | `specStrength` 默认值 | 上游 View 层（`LiquidGlassView` 的默认参数），本地台账未下载该文件，实现时从 `QWEA0/Liquid-Glass-Android` 取 |
| 2 | `rimBandMax` 默认值（含「小控件按短边收」的具体规则） | 同上 |
| 3 | `LiquidGlassView` 里 `margin` 外扩量的取值规则（用于交叉核对第 4.1 节的 `margin = scaledPadding`） | 同上 |
| 4 | `press` / `touchAmp` 的默认强度（仅当决定把 `FloatingBottomBar` 的水珠拖拽接进来时才需要） | 同上 |

另需在实现时确认（不影响本 spec 结论，只影响代码写法）：

| # | 项 | 说明 |
| --- | --- | --- |
| 5 | miuix `runtimeShaderEffect` 对 uniform 数量的上限 | 新着色器约 25 个 uniform（现有 7 个）。若受限，先旁路掉不参与运算的那些（如 `shape2`、`touchPos`） |
| 6 | miuix `driver` 的 `content` 子输入坐标原点是否与输出一致 | 第 4.2 节按「一致」推导；实现时用一次 `sampleLo/Hi` 的边界测试验证（把 `sampleHi` 故意调小，看边缘是否出现黑边） |
| 7 | `isRuntimeShaderSupported()` 的双导入路径 | `Lens.kt:10` 走 `top.yukonga.miuix.kmp.blur.*`，`XGlassSurface.kt:28` 走 `top.yukonga.miuix.kmp.shader.*`。改写 `Lens.kt` 时**不要动 import** |