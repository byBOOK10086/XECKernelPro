// XEC Clear Glass · 透明液态玻璃组件。
//
// 与 ui/design/glass 的 XGlass* 共用同一套管线纪律（三档降级、圆角只取 Xc.shapes、
// 裁剪切在折射取样之后、描边复用 xGlassRim），差异只在玻璃本体：
//   - XGlass* 是"磨砂玻璃"：模糊之上压一层较重的 glassTint，内容若隐若现；
//   - Clear* 是"透明玻璃"（iOS 26 Liquid Glass 的 Clear 形态）：本体近乎全透，
//     只靠折射、菲涅尔亮边与亮度自适应层塑形（着色器见 ClearGlassShader.kt）。
//
// 采样安全约束与 XGlassSurface 完全相同：栏/弹层/按钮是录制节点的**兄弟**，
// 可以真采样；位于录制子树**内部**的卡体一律走 [xClearGlassBody] 强制降级，
// 原因（自采样成环 → RenderThread SIGSEGV）见 `XGlassSurface.xGlassBody` 的完整注释。

package me.weishu.kernelsu.ui.design.clear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.component.liquid.vibrancy
import me.weishu.kernelsu.ui.design.glass.xGlassRim
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

/**
 * 透明液态玻璃表面。
 *
 * @param backdrop 由 `rememberBlurBackdrop` 产出；`null` 表示设备/设置不支持模糊。
 * @param tint 玻璃本体染色，默认 [Color.Transparent] —— 透明玻璃不上色，
 *   可读性由着色器内的亮度自适应层负责。要带介质色时给低透明度的颜色。
 * @param adaptive 亮度自适应可读性层开关（仅着色器档生效）。
 * @param specular 边缘高光强度倍率。
 */
@Composable
fun ClearGlassSurface(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.lg,
    tint: Color = Color.Transparent,
    blurRadius: Dp = 6.dp,
    refraction: Dp = 26.dp,
    rimColor: Color = Xc.colors.glassRim,
    rim: Boolean = true,
    glassEnabled: Boolean = true,
    adaptive: Boolean = true,
    specular: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.xClearGlassLayer(
            backdrop = backdrop,
            shape = shape,
            tint = tint,
            blurRadius = blurRadius,
            refraction = refraction,
            rimColor = rimColor,
            rim = rim,
            glassEnabled = glassEnabled,
            adaptive = adaptive,
            specular = specular,
        ),
        content = content,
    )
}

/**
 * 透明玻璃**卡体**：给位于录制子树内部的容器用（列表里的卡片、分组块）。
 *
 * 和 `XGlassSurface.xGlassBody` 同一条安全规则：卡体正处在页面级 backdrop 的
 * 录制子树内部，真采样会自采样成环，RenderThread 无限递归直接 SIGSEGV。
 * 所以这里强制 `backdrop = null` 走实色降级档——**要恢复卡体真玻璃，请先重构
 * 采样源，而不是把 null 改回去。**
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Modifier.xClearGlassBody(
    backdrop: LayerBackdrop?,
    shape: Shape = Xc.shapes.md,
    tint: Color = Color.Transparent,
    glassEnabled: Boolean = true,
): Modifier = this.xClearGlassLayer(
    backdrop = null,
    shape = shape,
    tint = tint,
    blurRadius = 8.dp,
    refraction = 20.dp,
    rimColor = Xc.colors.glassRim,
    rim = true,
    glassEnabled = glassEnabled,
    adaptive = true,
    specular = 1f,
)

/**
 * 透明玻璃绘制的唯一实现：三档降级链（AGSL 透明玻璃 → RenderEffect 毛玻璃 → 实色）。
 * 结构与 `XGlassSurface.xGlassLayer` 逐档对应，方便两套玻璃观感对齐与排障。
 */
@Composable
private fun Modifier.xClearGlassLayer(
    backdrop: LayerBackdrop?,
    shape: Shape,
    tint: Color,
    blurRadius: Dp,
    refraction: Dp,
    rimColor: Color,
    rim: Boolean,
    glassEnabled: Boolean,
    adaptive: Boolean,
    specular: Float,
): Modifier {
    val surface = Xc.colors.surface
    val shaderSupported = remember { isRuntimeShaderSupported() }
    val active = glassEnabled && backdrop != null

    // 降级档不能直接用半透明 tint（会透出窗口黑底，塌成黑框），
    // 先合成成不透明实色，三档共用。
    val solidTint = if (tint.alpha >= 1f) tint else tint.compositeOver(surface)

    return when {
        active && shaderSupported -> this
            .drawBackdrop(
                backdrop = backdrop!!,
                shape = { shape },
                effects = {
                    val refractPx = refraction.toPx()
                    padding = maxOf(28.dp.toPx(), refractPx)
                    vibrancy()
                    blur(blurRadius.toPx(), blurRadius.toPx())
                    clearGlass(
                        refraction = refractPx,
                        bevel = refractPx * 0.6f,
                        tint = tint,
                        adaptive = adaptive,
                        specular = specular,
                    )
                },
                // 透明玻璃不在表面上再压色：本体、可读性层与边缘光全部由
                // clearGlass 着色器完成，这里保持空实现（与磨砂玻璃的差异点）。
                onDrawSurface = { },
            )
            .clip(shape)
            .xGlassRim(shape, rimColor, rim)

        // 回退一档：毛玻璃（RenderEffect，API 31-32）
        active -> this
            .textureBlur(
                backdrop = backdrop!!,
                shape = shape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = solidTint.copy(alpha = 0.72f)),
                    ),
                ),
            )
            .clip(shape)
            .xGlassRim(shape, rimColor, rim)

        // 没有 backdrop（用户关掉模糊 / 设备不支持 / 预览）：实色 + 描边
        else -> this
            .background(solidTint, shape)
            .clip(shape)
            .xGlassRim(shape, rimColor, rim)
    }
}

/** 透明玻璃卡片：内容区容器，卡体语义（圆角与模糊档位比表面低一档）。 */
@Composable
fun ClearGlassCard(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.md,
    tint: Color = Color.Transparent,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    ClearGlassSurface(
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

/** 透明玻璃栏：顶栏 / 底栏 / 悬浮栏（全宽贴边的栏在调用点传只圆部分角的 shape）。 */
@Composable
fun ClearGlassBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.bar,
    tint: Color = Color.Transparent,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    ClearGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = 10.dp,
        refraction = 28.dp,
        glassEnabled = glassEnabled,
        content = content,
    )
}

/**
 * 透明玻璃按钮：按下 0.96 缩放 + 弱弹簧回弹，无 ripple（玻璃自带的边缘光就是按压反馈）。
 *
 * 默认 `backdrop = null`：按钮通常长在卡片/列表里（录制子树内部），就地采样必成环；
 * 放在栏、弹层等兄弟节点上时由调用方显式传入页面级 backdrop。
 */
@Composable
fun ClearGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    shape: Shape = Xc.shapes.pill,
    tint: Color = Color.Transparent,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "clearGlassButtonPress",
    )
    Box(
        modifier = modifier
            .xClearGlassLayer(
                backdrop = backdrop,
                shape = shape,
                tint = tint,
                blurRadius = 8.dp,
                refraction = 18.dp,
                rimColor = Xc.colors.glassRim,
                rim = true,
                glassEnabled = glassEnabled,
                adaptive = true,
                specular = 1.2f,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        content = content,
    )
}
