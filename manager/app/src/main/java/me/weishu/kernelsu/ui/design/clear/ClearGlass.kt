// XEC Clear Glass · 透明液态玻璃组件。
//
// 与 ui/design/glass 的 XGlass* 共用同一套管线纪律（三档降级、圆角只取 Xc.shapes、
// 裁剪切在折射取样之后、描边复用 xGlassRim），差异只在玻璃本体：
//   - XGlass* 是"磨砂玻璃"：模糊之上压一层较重的 glassTint，内容若隐若现；
//   - Clear* 是"透明玻璃"（iOS 26 Liquid Glass 的 Clear 形态）：本体近乎全透，
//     只靠折射、菲涅尔亮边与亮度自适应层塑形（着色器见 ClearGlassShader.kt）。
//
// 采样安全约束与 XGlassSurface 相同：消费者在采样源的录制子树**内部**必成环，
// **之外**（兄弟、祖先的更早兄弟）合法。卡体一律不采页面级 backdrop（卡体就在
// 录制子树里），改采 `LocalWallpaperBackdrop`（壁纸 Image 的录制子树里只有壁纸，
// 谁采都安全）——`xGlassBody` 的实现见 `XGlassSurface.kt`。
//
// 本体染色由 `Xc.colors.clearGlassTint` 令牌说了算：浅色档全透（经典 Clear 形态），
// 深色档/AMOLED 是"黑液态玻璃"——深色壁纸已经压暗，玻璃再全透会显得又亮又飘，
// 深色下给一层吸光介质，折射与边缘光保留。

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
 * @param tint 玻璃本体染色，默认 [Xc.colors.clearGlassTint] —— 浅色档全透
 *   （透明玻璃不上色，可读性由着色器内的亮度自适应层负责），深色档是黑液态玻璃。
 *   要覆盖介质色时给低透明度的颜色。
 * @param adaptive 亮度自适应可读性层开关（仅着色器档生效）。
 * @param specular 边缘高光强度倍率。
 */
@Composable
fun ClearGlassSurface(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = Xc.shapes.lg,
    tint: Color = Xc.colors.clearGlassTint,
    blurRadius: Dp = 2.5.dp,
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
 * 透明玻璃绘制的唯一实现：三档降级链（AGSL 透明玻璃 → RenderEffect 毛玻璃 → 实色）。
 * 结构与 `XGlassSurface.xGlassLayer` 逐档对应，方便两套玻璃观感对齐与排障。
 *
 * internal：`XGlassSurface.xGlassBody`（全应用卡体入口，采 [LocalWallpaperBackdrop]）
 * 复用这条链，玻璃观感必须与栏/弹层逐像素同源。
 *
 * ⚠️ 模糊半径在着色器档被钳到 [CLEAR_GLASS_MAX_BLUR_PX]：miuix blur 按模糊半径自适应
 * 降低 backdrop 记录分辨率（σ² ≥ 12.6 就开始 ½、≥ 90.25 到 ¼，BlurEffect.kt 的
 * downScaleExpFor），低分辨率记录上的折射位移就是肉眼可见的马赛克。σ = 半径px × 0.45，
 * 钳在 7.5px ⇒ σ² ≈ 11.4 < 12.6，任何密度都吃满全分辨率记录——这同时就是"透明玻璃"
 * 该有的雾度：几乎不糊，靠折射与亮边塑形，而不是靠磨砂。
 */
const val CLEAR_GLASS_MAX_BLUR_PX = 7.5f

@Composable
internal fun Modifier.xClearGlassLayer(
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
                    // 不做 vibrancy：透明玻璃不压磨砂底，提高采样饱和度只会把壁纸的
                    // 颜色噪点推到文字底下。透明感靠"几乎不糊 + 全分辨率折射"。
                    val blurPx = minOf(blurRadius.toPx(), CLEAR_GLASS_MAX_BLUR_PX)
                    blur(blurPx, blurPx)
                    clearGlass(
                        refraction = refractPx,
                        // 斜面带与最大位移同宽：1.5 次幂剖面下弯折带更宽，
                        // 是"厚玻璃"而非"软凝胶"的关键（见 ClearGlassShader.kt）。
                        bevel = refractPx,
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
    tint: Color = Xc.colors.clearGlassTint,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    ClearGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = 2.dp,
        refraction = 26.dp,
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
    tint: Color = Xc.colors.clearGlassTint,
    glassEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    ClearGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = 3.dp,
        refraction = 28.dp,
        glassEnabled = glassEnabled,
        content = content,
    )
}

/**
 * 透明玻璃按钮：按下 0.96 缩放 + 弱弹簧回弹，无 ripple（玻璃自带的边缘光就是按压反馈）。
 *
 * 默认采 [LocalWallpaperBackdrop]：按钮长在卡片/列表里时，采页面级 backdrop 必成环，
 * 而采壁纸永远安全——折射出的正是按钮身后那段壁纸，与所在卡体的玻璃同源。
 */
@Composable
fun ClearGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = LocalWallpaperBackdrop.current,
    shape: Shape = Xc.shapes.pill,
    tint: Color = Xc.colors.clearGlassTint,
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
                blurRadius = 2.dp,
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
