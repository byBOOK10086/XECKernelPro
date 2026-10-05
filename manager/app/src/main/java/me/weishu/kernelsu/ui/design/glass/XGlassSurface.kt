package me.weishu.kernelsu.ui.design.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.component.liquid.InnerShadow
import me.weishu.kernelsu.ui.component.liquid.innerShadow
import me.weishu.kernelsu.ui.component.liquid.lens
import me.weishu.kernelsu.ui.component.liquid.vibrancy
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

/**
 * XEC Fluid Glass · 玻璃表面。
 *
 * 全应用唯一允许绘制"玻璃"的入口。所有容器（栏、卡片、弹层）都必须走这里，
 * 原因是这里一次性管住了三件容易各自出错的事：
 *
 * 1. **圆角**：`shape` 只会取 `Xc.shapes` 的值。本设计里没有直角矩形容器。
 * 2. **折射真的生效**：miuix 的 `lens()` 内部要求 `shape as? CornerBasedShape`，
 *    一旦传 `RectangleShape` 它会静默 `return` —— 过去"液态玻璃"其实只有模糊、
 *    没有折射，根因就在这里。
 * 3. **三档降级**：`lens` 需要 AGSL（API 33+），`textureBlur` 需要 RenderEffect（API 31+），
 *    都没有时退回半透明纯色，保证任何设备上都不出现"没画出来的空白块"。
 *
 * @param backdrop 由 `rememberBlurBackdrop` 产出；为 `null` 表示设备/设置不支持模糊。
 * @param tint 玻璃上覆色，叠在模糊结果之上，决定玻璃"有多深"。
 * @param rim 是否描一圈渐变亮边。没有模糊的设备上，这圈边是唯一的层次来源。
 */
@Composable
fun XGlassSurface(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.lg,
    tint: Color = Xc.colors.glassTint,
    blurRadius: Dp = 6.dp,
    refraction: Dp = 24.dp,
    rimColor: Color = Xc.colors.glassRim,
    rim: Boolean = true,
    innerHighlight: Boolean = true,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.xGlassLayer(
            backdrop = backdrop,
            shape = shape,
            tint = tint,
            blurRadius = blurRadius,
            refraction = refraction,
            rimColor = rimColor,
            rim = rim,
            innerHighlight = innerHighlight,
            glassEnabled = glassEnabled,
        ),
        content = content,
    )
}

/**
 * 玻璃**卡体**：把一枚 miuix `Card` 的实心底换成真玻璃。
 *
 * 和 [xGlassRim] 的关系：`xGlassRim` 只画那一圈 1dp 渐变亮边，卡体实色仍由 miuix
 * `Card` 自己铺 —— 所以引擎再强，卡片上也看不到折射。本函数把「卡体」也接进玻璃管线，
 * 亮边由内部一并画出，**调用点不要再叠 `.xGlassRim(...)`**。
 *
 * 用法（两件事必须同时做，少一件就看不到玻璃）：
 *
 * ```
 * Card(
 *     modifier = modifier.xGlassBody(backdrop = backdrop, shape = Xc.shapes.md),
 *     // 必要：miuix Card 的底色画在 modifier 链内侧，不清空就会盖住玻璃与亮边
 *     colors = CardDefaults.defaultColors(color = Color.Transparent),
 * ) { ... }
 * ```
 *
 * @param backdrop 页面级 `rememberBlurBackdrop` 的产物。**当前实现会忽略它**，原因见函数体
 *   里的「自采样成环」说明：卡体正处在采样源的录制子树内部，不能真去采样。保留形参只是
 *   为了不动 40 多处调用点，等采样源重构（让页面级 backdrop 不再包含卡体自己）后放开即可。
 *   原本 `null` 的语义（设备不支持 / 用户关掉模糊 / 预览 → 不透明实色 + 亮边）保持不变。
 * @param tint 玻璃上覆色。默认沿用 `glassTint`；语义色卡片可传同色系的低透明度版本，
 *   例如状态卡的 `Xc.colors.success.copy(alpha = 0.22f)` —— 传不透明的 `successTint`
 *   会把折射整个遮死。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
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
): Modifier = this.xGlassLayer(
    // ⚠️ 强制 null —— v30086「进不去 / 首帧闪退」的修复点。
    //
    // 卡体位于页面级 backdrop 的**录制子树内部**：
    //     Box(Modifier.layerBackdrop(backdrop)) { LazyColumn { ...卡片... } }
    // `rememberBlurBackdrop` 的录制块是 `{ drawRect(surfaceColor); drawContent() }`，
    // 也就是「渲染这层纹理 = 再画一遍整棵子树」。卡体在那一遍里又去 `drawBackdrop(backdrop)`
    // 采样同一层纹理 —— 渲染这层要求先画卡体，画卡体又要求先渲染这层，递归没有出口，
    // RenderThread 在 `RenderNode::prepareTreeImpl` 里无限递归，进程直接被打成原生
    // SIGSEGV（`stack pointer is not in a rw map; likely due to stack overflow.`）。
    // 表现为「点开就退、连首帧都看不到」，不是 ANR，也没有 Java 栈。
    //
    // 判据：消费者在采样源的录制节点**子树内 ⇒ 崩**；是它的**兄弟 ⇒ 安全**。
    // 顶栏（`BlurredBar` 挂在 `Scaffold(topBar = ...)`）、底栏、弹层、对话框都是兄弟，
    // 所以 [XGlassSurface] / `XGlassBar` 不受影响 —— 只有卡体需要降级。
    // 仓库里同一个坑已有两处文字记录：`ui/component/dialog/DownloadDialog.kt`
    // （玻璃弹窗必须交给根层宿主，就地画必成环）与 `ui/MainActivity.kt`（对话框不放进采样 Box）。
    //
    // 传 null 即走第三档：不透明实色 + 亮边，与 v30084 的 `.xGlassRim(...)` 观感一致。
    // **要恢复卡体真玻璃，请重构采样源（让页面级 backdrop 不再包含卡体本身），
    // 而不是把这里的 null 改回去。**
    backdrop = null,
    shape = shape,
    tint = tint,
    blurRadius = blurRadius,
    refraction = refraction,
    rimColor = rimColor,
    rim = rim,
    innerHighlight = innerHighlight,
    glassEnabled = glassEnabled,
)

/**
 * 玻璃绘制的**唯一实现**：三档降级链。
 *
 * 之所以抽屉成一个函数，是因为卡片、栏、弹层必须逐像素同源 —— 各写一份必然漂移，
 * 而"某处玻璃看起来不一样"这种问题几乎无法定位。所有玻璃入口
 * （[XGlassSurface] / [xGlassBody]）都只是把参数原样转交到这里。
 *
 * 顺序上它必须挂在调用方 `modifier` 的**内侧**：外层的 `padding` 决定玻璃贴哪条边，
 * 内层的 `clip` 才把折射外扩出的取样区裁回圆角。
 */
@Composable
private fun Modifier.xGlassLayer(
    backdrop: LayerBackdrop?,
    shape: Shape,
    tint: Color,
    blurRadius: Dp,
    refraction: Dp,
    rimColor: Color,
    rim: Boolean,
    innerHighlight: Boolean,
    glassEnabled: Boolean,
): Modifier {
    val surface = Xc.colors.surface
    val shaderSupported = remember { isRuntimeShaderSupported() }
    val active = glassEnabled && backdrop != null

    // 三档降级里，后两档都不能直接用半透明的 tint（会透出窗口黑底，面板塌成黑框），
    // 需要先合成成不透明实色。算一次，两档共用。
    val solidTint = if (tint.alpha >= 1f) tint else tint.compositeOver(surface)

    return when {
        active && shaderSupported -> this
            .drawBackdrop(
                backdrop = backdrop!!,
                shape = { shape },
                effects = {
                    val refractPx = refraction.toPx()
                    // 折射要在控件边界外取样，padding 小于折射量时边缘会被裁掉，
                    // 表现为"折射只在中段出现、贴边消失"。
                    padding = maxOf(28.dp.toPx(), refractPx)
                    vibrancy()
                    blur(blurRadius.toPx(), blurRadius.toPx())
                    lens(refractionHeight = refractPx, refractionAmount = refractPx)
                },
                onDrawSurface = {
                    drawRect(tint)
                },
            )
            .clipTo(shape)
            .xGlassRim(shape, rimColor, rim)
            .innerShadow(shape = shape) {
                if (innerHighlight) {
                    InnerShadow(
                        radius = 12.dp,
                        color = Color.Black.copy(alpha = 0.10f),
                    )
                } else {
                    null
                }
            }

        // 回退一档：毛玻璃（RenderEffect，API 31-32）
        // 混色跟着 tint 走，而不是写死 surface：现有三档令牌下两者完全等价
        // （半透明 tint 同色系压在 surface 上就是 surface），但"白毛玻璃"那种
        // 与明暗档无关的 tint 如果写死 surface，在 31/32 上会被糊回深色。
        active -> this
            .textureBlur(
                backdrop = backdrop!!,
                shape = shape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = solidTint.copy(alpha = 0.87f)),
                    ),
                ),
            )
            .clipTo(shape)
            .xGlassRim(shape, rimColor, rim)

        // 没有 backdrop（用户关掉了模糊 / 设备不支持 RenderEffect / 还没采样到图层）
        // 时**不能**继续拿半透明的 tint 兜底：tint 是 0.55 Alpha 的深色，
        // 直接 background 画上去会透出窗口底色，整块面板就塌成一块「黑框」——
        // 这正是"弹窗/栏变黑方块"的来源。把 tint 合成到不透明的 surface 上，
        // 色相保留、Alpha 归 1，与 `BlurredBar` 的无毛玻璃分支保持一致。
        else ->
            this
                .background(color = solidTint, shape = shape)
                .clipTo(shape)
                .xGlassRim(shape, rimColor, rim)
    }
}

/**
 * 把内容裁进圆角里。
 *
 * 必须排在玻璃绘制**之后**：裁剪切在 `drawBackdrop` 前面会把折射所需的
 * 外扩取样区一起裁掉，折射就又没了。
 */
@Composable
private fun Modifier.clipTo(shape: Shape): Modifier = this.clip(shape)

/**
 * 玻璃边：上亮下暗的 1dp 渐变描边。
 * 比纯色描边贵不了多少，但能立刻把"一块半透明色"变成"一片玻璃"。
 *
 * 它是 [xGlassLayer] 内部三档共用的收尾（所以玻璃体自己就带边框），另留给
 * 「卡体暂时还不是玻璃」的调用点直接挂 —— 圆角由 miuix `Card` 自己裁，
 * 缺的就是这圈亮边，因此只需要一个 `shape`：`.xGlassRim(Xc.shapes.md)`。
 * 卡体也要换成真玻璃时改用 [xGlassBody]，两边不要同时挂，亮边会叠成两层。
 * `rimColor` / `rim` 留默认值正是为了这个单参数用法；需要临时关掉亮边时
 * 才显式传 `rim = false`。
 */
@Composable
internal fun Modifier.xGlassRim(
    shape: Shape,
    rimColor: Color = Xc.colors.glassRim,
    rim: Boolean = true,
): Modifier =
    if (!rim) {
        this
    } else {
        this.border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    rimColor.copy(alpha = 0.55f),
                    rimColor.copy(alpha = 0.10f),
                ),
            ),
            shape = shape,
        )
    }

/**
 * 玻璃卡片：内容区最常用的容器。
 *
 * 与 [XGlassSurface] 的区别只有在语义上——卡片额外给一层极轻的投影，
 * 把"内容块"从"贴底的栏"里区分出来。圆角仍由 `Xc.shapes` 说了算。
 */
@Composable
fun XGlassCard(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.md,
    tint: Color = Xc.colors.glassTint,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    XGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = 8.dp,
        refraction = 20.dp,
        glassEnabled = glassEnabled,
        content = content,
    )
}

/**
 * 玻璃栏：顶栏 / 底栏 / 侧边栏。
 *
 * 栏体默认用最大的一档圆角，这是"每个方形框角要圆滑"在壳层上的落点。
 * 全宽贴边的栏应在调用点传一个只圆某几个角的 `RoundedCornerShape`，
 * 例如顶栏只圆下沿。
 */
@Composable
fun XGlassBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.bar,
    tint: Color = Xc.colors.glassTint,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    XGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = 10.dp,
        refraction = 28.dp,
        innerHighlight = false,
        glassEnabled = glassEnabled,
        content = content,
    )
}
