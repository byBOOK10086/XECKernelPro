# 液态玻璃移植 · 批次 1 实施计划（玻璃卡片层落地）

- 日期：2026-09-16
- 上游设计：`docs/superpowers/specs/2026-09-16-liquid-glass-kernel-port-design.md`
- 前置：批次 0 已完成并发布（`9616678` → tag `v30084-85e64340`），绘制内核已是上游 `LENS_AGSL` 单 pass 透镜
- 用户拍板口径：「**就这样，这个 UI 效果要保留，怎么安全怎么来**」⇒ 上游观感不缩水 + 落地以稳为先

## 1. 本批次要解决的问题

批次 0 只换了「画玻璃的引擎」，但**卡片根本不是玻璃**：全应用 42 处卡片只挂了一圈
`.xGlassRim(Xc.shapes.md)`，卡体实色仍由 miuix `Card` 自己铺的不透明底色决定。
结果是引擎再强，卡片上看不到折射与色散。

本批次给「卡片」这一层补上真正的玻璃体，落到两个主页面（首页、设置页）共 **13 处**。

## 2. 范围与非目标

**做：**

| 文件 | 处数 | 位置 |
| --- | --- | --- |
| `ui/design/glass/XGlassSurface.kt` | 新增 1 个函数 | `xGlassBody` |
| `ui/screen/home/HomeMiuix.kt` | 6 | `:293` `:381` `:417` `:452` `:566` `:598` |
| `ui/screen/settings/SettingsMiuix.kt` | 7 | `:114` `:152` `:174` `:198` `:332` `:386` `:413` |

**不做（本批次刻意不碰）：**

- 不改任何颜色令牌数值（`XcColor.kt` 零改动）
- 不改 `rememberBlurBackdrop` / `BlurredBar` / 顶栏
- 不改三档降级链的判定条件与顺序
- 不删旧内核（`LEGACY` 分支原样保留）
- 不迁移其余 11 个文件 29 处（批次 2）

## 3. 核心改动

### 3.1 抽出共享玻璃链 `xGlassLayer`

`XGlassSurface` 里那段 `when { 三档 }` 是**唯一**一份玻璃绘制实现。把它原样搬进
私有 `Modifier.xGlassLayer(...)`，`XGlassSurface` 与新的 `xGlassBody` 都走这一份：

- 好处：卡片玻璃与弹窗/栏玻璃**逐像素同源**，只有一个地方要调参、只有一个地方会错。
- 风险控制：`when` 三分支代码**一行不改**（只是首行 `Modifier.` 改 `this.`，保持
  「调用方 modifier 在外、玻璃链在内」的顺序不变），属机械搬移。

### 3.2 新增 `xGlassBody`

```kotlin
@Composable
internal fun Modifier.xGlassBody(
    backdrop: LayerBackdrop?,
    shape: Shape = Xc.shapes.md,
    tint: Color = Xc.colors.glassTint,
    blurRadius: Dp = 8.dp,
    refraction: Dp = 20.dp,
    rimColor: Color = Xc.colors.glassRim,
    rim: Boolean = true,
    innerHighlight: Boolean = true,
    glassEnabled: Boolean = true,
): Modifier = xGlassLayer(...)
```

- 与 `xGlassRim` **同文件、同包**：13 个调用点原本就 import 了 `xGlassRim`，
  只换 import 名，不引入新包（spec §6.1 约束）。
- 默认值取 `XGlassCard` 那一档（`8.dp` / `20.dp`），因为它的语义就是「卡片」。
- 亮边由 `xGlassLayer` 内部一并画，调用点**不再叠** `.xGlassRim(...)`。

### 3.3 调用点写法

```kotlin
// 之前：只有一圈亮边，卡体是 miuix 不透明底色
Card(modifier = modifier.xGlassRim(Xc.shapes.md)) { ... }

// 之后：卡体真玻璃 + 亮边
Card(
    modifier = modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
    colors = CardDefaults.defaultColors(color = Color.Transparent),
) { ... }
```

`color = Color.Transparent` 是**必须**的：miuix `Card` 自己的底色在 modifier 链内侧，
不置空就会盖住玻璃与亮边。

### 3.4 首页状态卡（`:293`）的特殊处理

这张卡原本用 `successTint` 当卡底。`successTint` 是 `success.over(surface, 0.22f)`
合成出来的**不透明**色，直接当玻璃上覆色会把折射全遮死。改用同一实色的低透明度版本：

```kotlin
tint = Xc.colors.success.copy(alpha = if (Xc.colors.isDark) 0.22f else 0.15f),
```

- 有玻璃时：22% 的绿压在模糊画面上 —— 是玻璃，不是色块。
- 没玻璃时（降级第三档）：`tint.compositeOver(surface)` 复合出的正是原来的
  `successTint`，**兜底观感与改动前逐像素一致**。

### 3.5 `backdrop` 的传递

`StatusCard` / `InfoCard` / `SupportLinks` 都是 private 子组件，原本不接 `backdrop`。
各加一个 `backdrop: LayerBackdrop? = null` 形参（**带默认值**），由 `HomePagerMiuix`
在 `:170` / `:174` / `:179` 处把页面级 `backdrop`（`:105`）传进去。

- 带默认值 ⇒ 所有 `@Preview` 预览函数**一个字都不用改**，也不会在预览里触发 AGSL。
- 设置页 7 处都在 `SettingPagerMiuix` 函数体内，`backdrop`（`:77`）直接可见，无需透传。

## 4. 安全措施

1. **单点实现**：玻璃只有 `xGlassLayer` 一份代码，卡片/弹窗/栏同源。
2. **降级链原样**：`isRuntimeShaderSupported()`（33+）→ `textureBlur`（31+）→
   不透明 `solidTint`，判定顺序与不变量（`solidTint = tint.compositeOver(surface)`）不改。
3. **旧内核保留**：`XcGlassKernel.LEGACY` 与 `trip()` 熔断机制不动，AGSL 构造失败仍全局回落。
4. **`padding` 语义不变**：玻璃链照旧挂在调用方 modifier 的**内侧**，设置页每张卡的
   `Modifier.padding(top = 12.dp)` 仍在外层 —— 亮边贴着卡片而不是贴着留白框。
5. **形参全带默认值**：新增的 `backdrop` 形参一律 `= null`，不破坏任何既有调用点。
6. **分批独立提交**：本批次一个 commit，出问题可整批 revert 而不影响批次 0。
7. **可验证**：推 `main` 触发 `build-manager.yml` 编译验证（零打扰，不出版本）。

## 5. 验收口径

- 编译：`build-manager.yml` 全绿。
- 观感（本机无法构建，交由用户在真机版本里过眼）：
  1. 首页状态卡、双栏卡、信息卡、支持卡能看到卡体随下方内容滚动而折射；
  2. 设置页 7 张卡同样；
  3. 关掉模糊设置 → 卡片回到不透明实色 + 亮边，不出现黑框或空白块；
  4. 浅色 / 深色 / AMOLED 三档令牌下都成立。

## 6. 回滚

`git revert <batch-1-commit>` 即可；批次 0 的内核与已发布版本不受影响。

## 7. 后续（批次 2，本批次完成后）

其余 11 个文件共 29 处 `xGlassRim` 铺开，含 `ModuleRepoMiuix.kt`（4 处
`Modifier.layerBackdrop` 需逐处确认非空）与 `AboutMiuix.kt`（双套 backdrop，勿混用）。
