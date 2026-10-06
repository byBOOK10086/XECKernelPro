// XEC Clear Glass · 透明液态玻璃绘制内核。
//
// 着色器为本项目从零编写（AGSL）：圆角盒 SDF → 屏幕空间法线 → 二次斜面折射 →
// 亮度自适应的透明染色 → 菲涅尔双瓣亮边 + 贴边高光，观感对标 iOS 26 Liquid Glass
// 的 "Clear" 形态——玻璃本体几乎全透，只靠折射与边缘光照塑形。
// 仅以 Apple 的设计语言作观感参考，未复制任何第三方着色器代码。
// 玻璃管线宿主 API 来自 miuix-blur（top.yukonga.miuix.kmp.blur，Apache-2.0），
// 依赖与许可见 THIRD_PARTY_NOTICES.md。

package me.weishu.kernelsu.ui.design.clear

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

private const val CLEAR_GLASS_KEY = "XcClearGlass"

/** 光源方向：左上来光（-light 指向左上，顶边最亮、左下角次亮）。 */
private const val CLEAR_GLASS_LIGHT_X = 0.6f
private const val CLEAR_GLASS_LIGHT_Y = 0.8f

/**
 * 本着色器能否被当前设备的 AGSL 编译器接受。
 *
 * AGSL 编译失败不会在注册 effect 时抛出，而是拖到渲染线程真正绘制那一帧；
 * 所以在首次使用前单独构造一次 [RuntimeShader]，失败就永久停用透明内核，
 * [me.weishu.kernelsu.ui.design.clear.ClearGlassSurface] 落到毛玻璃/实色降级档。
 * 不联动 [XcGlassKernel]：那是 Lens 移植内核的熔断器，两套着色器互不连坐。
 */
private val clearShaderUsable: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        false
    } else {
        runCatching { RuntimeShader(CLEAR_GLASS_SHADER) }.isSuccess
    }
}

/**
 * 透明玻璃效果：挂在 [top.yukonga.miuix.kmp.blur.drawBackdrop] 的 effects 链里，
 * 排在 `blur()` 之后——本 effect 采样到的是已模糊的背景，再叠加折射与边缘光照。
 *
 * @param refraction 边缘折射位移（px），同时决定了斜面带的宽度下限。
 * @param bevel 斜面带宽度（px）：折射沿这条带从边缘向内衰减到 0。
 * @param tint 玻璃本体染色。透明玻璃的本体色应当接近全透（alpha ≤ 0.1），
 *   可读性交给着色器里的亮度自适应层。
 * @param adaptive 亮度自适应开关：亮背景上局部压暗、暗背景上局部提亮，
 *   保证玻璃跨明暗内容时文字始终可读。
 * @param specular 边缘高光强度倍率。
 */
internal fun BackdropEffectScope.clearGlass(
    refraction: Float,
    bevel: Float,
    tint: Color,
    adaptive: Boolean = true,
    specular: Float = 1f,
) {
    if (!isRuntimeShaderSupported()) return
    if (!clearShaderUsable) return
    if (refraction <= 0f) return

    // 折射要在控件边界外取样：padding 小于折射量时边缘折射带会被裁掉，
    // 表现为"折射只在中段出现、贴边消失"（与 XGlassSurface 同一条经验）。
    if (padding < refraction) {
        padding = refraction
    }

    val radii = roundedRectCornerRadii() ?: return

    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    // 与 Lens.kt 相同的纪律：全部按 downscaleFactor 换算到着色器坐标，
    // 并在 lambda 外预计算，闭包里只引用局部值
    val scaledPadding = padding / sf
    val scaledW = size.width / sf
    val scaledH = size.height / sf
    val scaledRadii = FloatArray(radii.size) { radii[it] / sf }
    val scaledRefraction = refraction / sf
    val scaledBevel = bevel / sf
    val tintComponents = floatArrayOf(tint.red, tint.green, tint.blue, tint.alpha)
    runCatching {
        runtimeShaderEffect(
            key = CLEAR_GLASS_KEY,
            shaderString = CLEAR_GLASS_SHADER,
            uniformShaderName = "content",
        ) {
            setFloatUniform("margin", scaledPadding)
            setFloatUniform("size", scaledW, scaledH)
            // 逐角半径顺序与 Lens.kt 一致：(左上, 右上, 右下, 左下)
            setFloatUniform("radii", scaledRadii)
            setFloatUniform("refraction", scaledRefraction)
            setFloatUniform("bevel", scaledBevel)
            setFloatUniform("tint", tintComponents)
            setFloatUniform("adaptive", if (adaptive) 1f else 0f)
            setFloatUniform("specular", specular)
            setFloatUniform("light", CLEAR_GLASS_LIGHT_X, CLEAR_GLASS_LIGHT_Y)
        }
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val cornerShape = shape as? CornerBasedShape ?: return null
    val sizePx = size
    val maxRadius = sizePx.minDimension / 2f
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) cornerShape.topStart.toPx(sizePx, this) else cornerShape.topEnd.toPx(sizePx, this)
    val topRight = if (isLtr) cornerShape.topEnd.toPx(sizePx, this) else cornerShape.topStart.toPx(sizePx, this)
    val bottomRight = if (isLtr) cornerShape.bottomEnd.toPx(sizePx, this) else cornerShape.bottomStart.toPx(sizePx, this)
    val bottomLeft = if (isLtr) cornerShape.bottomStart.toPx(sizePx, this) else cornerShape.bottomEnd.toPx(sizePx, this)
    return floatArrayOf(
        topLeft.fastCoerceAtMost(maxRadius),
        topRight.fastCoerceAtMost(maxRadius),
        bottomRight.fastCoerceAtMost(maxRadius),
        bottomLeft.fastCoerceAtMost(maxRadius),
    )
}

/**
 * 透明液态玻璃着色器（本项目原创）。
 *
 * 坐标约定与 Lens.kt 相同：`coord` 是录制内容（含 margin 外扩）的像素坐标，
 * `p = coord - margin` 是视图局部坐标，形状与尺寸都在这个空间里。
 * 全部 px 入参由调用方按 downscaleFactor 换算后再上报。
 */
private const val CLEAR_GLASS_SHADER = """
uniform shader content;
uniform float  margin;
uniform float2 size;
uniform float4 radii;     // 逐角半径：(左上, 右上, 右下, 左下)
uniform float  refraction;
uniform float  bevel;
uniform float4 tint;
uniform float  adaptive;
uniform float  specular;
uniform float2 light;

float sdBox4(float2 p, float2 b, float4 r) {
    float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
    float2 q = abs(p) - b + rx;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
}

float sdf(float2 local) {
    return sdBox4(local, size * 0.5, radii);
}

half4 main(float2 coord) {
    float2 p = coord - float2(margin, margin);
    // 视图矩形外硬切：内容录制区比视图大一圈 margin，形状外不允许有输出
    if (p.x < 0.0 || p.y < 0.0 || p.x > size.x || p.y > size.y) {
        return half4(0.0);
    }
    float2 c = p - size * 0.5;
    float d = sdf(c);

    // 覆盖率：1px 抗锯齿羽化，形状外完全透明
    float cov = clamp(0.5 - d, 0.0, 1.0);
    if (cov <= 0.003) {
        return half4(0.0);
    }

    // SDF 数值梯度 → 屏幕空间外法线
    float2 n = float2(
        sdf(c + float2(1.0, 0.0)) - sdf(c - float2(1.0, 0.0)),
        sdf(c + float2(0.0, 1.0)) - sdf(c - float2(0.0, 1.0))
    );
    float nl = length(n);
    if (nl > 0.0001) {
        n /= nl;
    } else {
        n = float2(0.0, -1.0);
    }

    // 厚度剖面：t=1 在玻璃深处，t=0 在边缘；二次斜面——折射集中在最外沿，
    // 向内平滑归零，这是透明玻璃"边缘弯折"观感的来源
    float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
    float slope = (1.0 - t) * (1.0 - t);

    // 折射：沿法线向内采样（iOS 一致的内侧压缩镜像）
    float2 offs = -n * (slope * refraction);

    // 轻微色散：蓝光弯得比红光多，只在边缘斜面带内可见（中部 slope≈0 三通道重合）
    float2 lo = float2(margin + 0.5, margin + 0.5);
    float2 hi = float2(margin + size.x - 1.5, margin + size.y - 1.5);
    float2 pR = clamp(coord + offs * 0.94, lo, hi);
    float2 pG = clamp(coord + offs, lo, hi);
    float2 pB = clamp(coord + offs * 1.06, lo, hi);
    float3 col = float3(content.eval(pR).r, content.eval(pG).g, content.eval(pB).b);

    // 亮度自适应的可读性层：透明玻璃没有实底，文字直接压在背景上，
    // 亮背景局部压暗、暗背景局部提亮，玻璃跨明暗内容时不会整体翻车
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    if (adaptive > 0.5) {
        float e = smoothstep(0.30, 0.78, lum);
        col = mix(col, float3(0.0), 0.20 * e);
        col = mix(col, float3(1.0), 0.14 * (1.0 - e));
    }

    // 本体染色：给一层近乎全透的色调（透明玻璃靠它带一点介质色）
    col = mix(col, tint.rgb, tint.a);

    // 光照：菲涅尔双瓣——迎光侧与背光侧各一道亮边（透明介质的内壁反射），
    // 外加迎光侧向内衰减的柔和高光带
    float facing = dot(n, -light);
    float lobeF = pow(max(facing, 0.0), 4.0);
    float lobeB = pow(max(-facing, 0.0), 4.0);
    float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
    float glow = pow(clamp(1.0 - (-d) / max(bevel, 1.0), 0.0, 1.0), 2.0) * cov;
    float spec = (hair * 0.55 * (lobeF + lobeB) + glow * 0.18 * lobeF) * specular;
    col += float3(spec);

    col = clamp(col, float3(0.0), float3(1.0));
    return half4(half3(col * cov), half(cov));
}
"""
