// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
// Mirrored from compose-miuix-ui example.
// XC_LENS_SHADER（新绘制内核）移植自 QWEA0/Liquid-Glass-Android —
// https://github.com/QWEA0/Liquid-Glass-Android (MIT)。

package me.weishu.kernelsu.ui.component.liquid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return

    if (padding < refractionAmount) {
        padding = refractionAmount
    }

    val radii = roundedRectCornerRadii() ?: return

    val dispersionEnabled = chromaticAberration > 0f

    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    val scaledSizeW = size.width / sf
    val scaledSizeH = size.height / sf
    val scaledPadding = padding / sf
    val scaledRefractionHeight = refractionHeight / sf
    val scaledRefractionAmount = refractionAmount / sf
    val scaledRadii = FloatArray(radii.size) { radii[it] / sf }

    // ---- 新内核：移植自 QWEA0/Liquid-Glass-Android（MIT）的单 pass 透镜 ----
    //
    // 面板尺寸与折射强度仍由本仓库原有的四个入参决定，观感对齐旧内核：
    // 斜面宽度 = refractionHeight、折射位移量 = refractionAmount、方向 = 向内采样。
    // 坐标模型与旧内核完全一致（上游 p = coord - margin，等价于旧内核的 coord + offset），
    // 所以这里只是换一套 uniform 上报，视图侧的调用点一行都不用改。
    if (XcGlassKernel.current == XcGlassKernel.Kernel.NEW && xcLensShaderUsable) {
        val halfW = scaledSizeW * 0.5f
        val halfH = scaledSizeH * 0.5f
        val newKernelApplied =
            runCatching {
                runtimeShaderEffect(
                    key = XC_LENS_KEY,
                    shaderString = XC_LENS_SHADER,
                    uniformShaderName = "content",
                ) {
                    // 整个视图矩形就是唯一主形状：(centerX, centerY, halfExtentX, halfExtentY)
                    setFloatUniform("margin", scaledPadding)
                    setFloatUniform("viewSize", scaledSizeW, scaledSizeH)
                    setFloatUniform("shape1", floatArrayOf(halfW, halfH, halfW, halfH))
                    // 逐角半径沿用旧内核的顺序：(左上, 右上, 右下, 左下)
                    setFloatUniform("radii1", scaledRadii)
                    // 平边不延伸：没有额外斜面，形状收口交给上游的视图矩形硬切
                    setFloatUniform("shape1L", floatArrayOf(halfW, halfH, halfW, halfH))
                    // 边缘柔化关闭：旧内核没有对应能力
                    setFloatUniform("rimSoft", 0f)
                    // 副形状关闭（shape2.z <= 0.5 即不参与 SDF）；水珠效果由 CombinedBackdrop 两次绘制承担
                    setFloatUniform("shape2", floatArrayOf(0f, 0f, 0f, 0f))
                    setFloatUniform("radius2", 0f)
                    setFloatUniform("blendK", 0f)
                    // 斜面宽度 / 折射位移量：与旧内核的 refractionHeight / refractionAmount 一一对应
                    setFloatUniform("bevel", scaledRefractionHeight)
                    setFloatUniform("refractPx", scaledRefractionAmount)
                    // 旧内核的 depthEffect 是"法线朝圆心偏"，新内核用逆幂剖面表达同一意图
                    setFloatUniform("falloff", if (depthEffect) XC_LENS_FALLOFF_DEPTH else 0f)
                    // 向内采样：边缘呈现内侧背景的压缩镜像（与旧内核上报 -refractionAmount 同向）
                    setFloatUniform("refractDir", -1f)
                    // 采样安全区：录制内容坐标下取视图矩形向内收 1px，区外是透明黑
                    setFloatUniform("sampleLo", scaledPadding, scaledPadding)
                    setFloatUniform("sampleHi", scaledPadding + scaledSizeW - 1f, scaledPadding + scaledSizeH - 1f)
                    // 色散强度：0 时三通道采样点重合，等价于无色散
                    setFloatUniform("dispersion", chromaticAberration.coerceIn(0f, 0.9f))
                    // 固定光源方向（上游 LightSourceController 的默认值）
                    setFloatUniform("lightDir", XC_LENS_LIGHT_X, XC_LENS_LIGHT_Y)
                    setFloatUniform("specStrength", XC_LENS_SPEC_STRENGTH)
                    // 贴边高光带宽度上限，与其它像素量一起换算到着色器坐标
                    setFloatUniform("rimBandMax", XC_LENS_RIM_BAND_MAX_PX / sf)
                    // 以下能力全部旁路：染色由 onDrawSurface 负责、饱和度由 vibrancy() 负责
                    setFloatUniform("tintColor", floatArrayOf(0f, 0f, 0f, 0f))
                    setFloatUniform("adaptiveTint", 0f)
                    setFloatUniform("glassTint", floatArrayOf(0f, 0f, 0f, 0f))
                    setFloatUniform("dimAmount", 0f)
                    setFloatUniform("satFactor", 1f)
                    // 按下态的形变由既有交互层承担，内核保持静止
                    setFloatUniform("press", 0f)
                    setFloatUniform("touchPos", 0f, 0f)
                    setFloatUniform("touchAmp", 0f)
                }
            }.onFailure { XcGlassKernel.trip(it) }.isSuccess
        // 失败说明新内核在这台设备上不可用，全局已切成 LEGACY，这一帧继续走下面的旧内核
        if (newKernelApplied) return
    }

    // ---- 回退路径：本仓库原有的着色器，原样保留 ----
    val shaderString =
        if (dispersionEnabled) {
            ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER
        } else {
            ROUNDED_RECT_REFRACTION_SHADER
        }
    val key = if (dispersionEnabled) "LiquidGlassLensDispersion" else "LiquidGlassLens"

    runtimeShaderEffect(
        key = key,
        shaderString = shaderString,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", scaledSizeW, scaledSizeH)
        setFloatUniform("offset", -scaledPadding, -scaledPadding)
        setFloatUniform("cornerRadii", scaledRadii)
        setFloatUniform("refractionHeight", scaledRefractionHeight)
        setFloatUniform("refractionAmount", -scaledRefractionAmount)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersionEnabled) {
            setFloatUniform("chromaticAberration", chromaticAberration)
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

// ---- 新内核（移植自 QWEA0/Liquid-Glass-Android，MIT）所需的常量与预编译校验 ----

private const val XC_LENS_KEY = "XcLiquidGlassLens"

/** 逆幂折射剖面的衰减指数：用来表达旧内核 `depthEffect = true` 的“法线朝圆心偏移”。 */
private const val XC_LENS_FALLOFF_DEPTH = 2f

/** 固定光源方向：上游 `LightSourceController` 的默认值 DEFAULT_X / DEFAULT_Y。 */
private const val XC_LENS_LIGHT_X = 0.866f
private const val XC_LENS_LIGHT_Y = 0.5f

/** 贴边高光强度：上游 `(edgeHighlightOpacity / 100) * specBoost`，默认 `(100 / 100) * 1.0`。 */
private const val XC_LENS_SPEC_STRENGTH = 1f

/** 贴边高光带宽度上限（px）：上游 RIM_BAND_MAX_PX。 */
private const val XC_LENS_RIM_BAND_MAX_PX = 6f

/**
 * 新内核的着色器能否被当前设备的 AGSL 编译器接受。
 *
 * 这是熔断的关键一环：AGSL 编译失败不会在注册 effect 时抛出，而是拖到渲染线程真正绘制那一帧，
 * 那时已经来不及回退。所以在首次使用前单独编译一次，失败就立刻把全局内核切成
 * [XcGlassKernel.Kernel.LEGACY]，本进程后续全部走旧内核。
 */
private val xcLensShaderUsable: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        false
    } else {
        runCatching { RuntimeShader(XC_LENS_SHADER) }
            .onFailure { XcGlassKernel.trip(it) }
            .isSuccess
    }
}

/**
 * 新绘制内核：上游 `GlassLensRenderer.LENS_AGSL` 的完整转写，单 pass 完成
 * 圆角盒 SDF → 斜面厚度剖面 → 屏幕空间法线 → 折射 → 三通道色散 → 贴边亮线。
 *
 * 坐标约定（与旧内核等价）：`coord` 是录制内容（含 margin 外扩）的像素坐标，
 * `p = coord - margin` 是视图局部坐标，形状参数都在这个空间里。
 *
 * 上游是设备像素空间的常量，本仓库按 `downscaleFactor` 缩放到着色器坐标后再上报，
 * 所以这里出现的所有 px 量都与调用方换算后的值一致。
 */
private const val XC_LENS_SHADER = """
uniform shader content;
uniform float  margin;
uniform float2 viewSize;
uniform float4 shape1;    // 真实主形状（触摸凸起的尺度用它）
uniform float4 radii1;
uniform float4 shape1L;   // 主形状：平边方向延伸到视图外，那条边就没有斜面（视图矩形外硬切）
uniform float  rimSoft;   // 边缘柔化：折射带内沿法线方向的抹匀宽度（px，0 = 关）
uniform float4 shape2;
uniform float  radius2;
uniform float  blendK;
uniform float  bevel;
uniform float  refractPx;
uniform float  falloff;      // 折射剖面：> 0 为逆幂（引力透镜）衰减指数，0 = 平方斜面
uniform float  refractDir;   // -1 向内采样（默认，与 iOS 一致：内侧压缩镜像）/ +1 向外（可选的凸透镜模式）
uniform float2 sampleLo;     // 采样安全区（录制内容坐标）：区外没有内容，读到的是透明黑
uniform float2 sampleHi;
uniform float  dispersion;
uniform float2 lightDir;
uniform float  specStrength;
uniform float  rimBandMax;   // 贴边高光带宽度上限（px，小控件按短边收）
uniform float4 tintColor;
uniform float  adaptiveTint;
uniform float4 glassTint;
uniform float  dimAmount;
uniform float  satFactor;
uniform float  press;
uniform float2 touchPos;
uniform float  touchAmp;

float sdRoundedBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

// 逐角半径版：r = (左上, 右上, 右下, 左下)，按 p 所在象限选半径
float sdRoundedBox4(float2 p, float2 b, float4 r) {
    float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
    float2 q = abs(p) - b + rx;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
}

float sminPoly(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

// 斜面 / 法线 / 高光用透镜形状（平边已延伸出去，不产生边缘）
float lensSDF(float2 p) {
    float d = sdRoundedBox4(p - shape1L.xy, shape1L.zw, radii1);
    if (shape2.z > 0.5) {
        float d2 = sdRoundedBox(p - shape2.xy, shape2.zw, radius2);
        if (blendK > 0.5) {
            d = sminPoly(d, d2, blendK);
        } else {
            d = min(d, d2);
        }
    }
    return d;
}

half4 main(float2 coord) {
    float2 p = coord - float2(margin, margin);
    // 视图矩形外硬切：平边方向形状延伸到了视图外，靠这一刀收口，
    // 平边上不做羽化，两块玻璃贴边拼接时不会叠出一条发丝缝
    if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
        return half4(0.0);
    }
    float d = lensSDF(p);

    // 覆盖率：1.5px 抗锯齿羽化，形状外完全透明
    float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
    if (cov <= 0.004) {
        return half4(0.0);
    }

    // SDF 数值梯度 → 屏幕空间外法线
    float2 n = float2(
        lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
        lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))
    );
    float nLen = length(n);
    if (nLen > 0.0001) {
        n = n / nLen;
    } else {
        n = float2(0.0, -1.0);
    }

    // 厚度剖面：t=1 平坦内部，t=0 边缘；slope 为斜面陡峭度（折射位移的比例）
    float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
    float edge = 1.0 - t;
    float slope;
    if (falloff > 0.001) {
        // 引力透镜式逆幂衰减：位移 ∝ (1 + x/k)^-p，核半径 k = 斜面宽度的 1/4，
        // 减去带末端的值再归一化，贴边 = 1、带末端平滑落到 0。越贴边越剧烈：
        // p = 2 时离边 k 处只剩 1/4，绝大部分弯折压在最外几个像素，
        // 内侧只留一段缓慢回落的轻微放大尾巴
        float gB = pow(5.0, -falloff);
        slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
    } else {
        // 平方斜面：弯折沿整条斜面带均匀铺开
        slope = edge * edge;
    }

    // 折射：refractDir = -1 沿法线向内采样（默认，与 iOS 一致）——边缘是内侧背景的
    // 压缩镜像；+1 为可选的凸透镜模式，向外采样，形状外的背景被弯进边缘，
    // 靠近的内容还没进到玻璃下面就先出现在边缘，进来之后沿边缘延展。
    // RuntimeShader 子输入只保证"输出裁剪区"内可采样（Android 未暴露 Skia 的
    // childSampleRadius），向外采样必须让输出区覆盖整个外扩录制区——见上游 draw()
    // 里的外层合成节点；采样再由 sampleLo/Hi 钳在有内容的范围内
    float refr = refractPx * (1.0 + 0.6 * press);
    float2 offset = n * (refractDir * slope * refr);

    // 触摸局部液态凸起（手指下方的局部放大：采样向触点收缩）
    if (touchAmp > 0.001) {
        float2 tp = p - touchPos;
        float tr = length(tp);
        float sigma = max(max(shape1.z, shape1.w), 1.0);
        float bump = touchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));
        if (tr > 1.0) {
            offset -= (tp / tr) * (bump * refr * 0.5);
        }
    }

    // 色散：三通道折射量不同（蓝光弯折最多），边缘出现光谱边纹
    float2 cR = coord + offset * (1.0 - dispersion * slope);
    float2 cG = coord + offset;
    float2 cB = coord + offset * (1.0 + dispersion * slope);

    // 安全钳制到有内容的采样区，杜绝透明黑
    float2 lo = sampleLo;
    float2 hi = sampleHi;
    cR = clamp(cR, lo, hi);
    cG = clamp(cG, lo, hi);
    cB = clamp(cB, lo, hi);
    float3 col;
    if (rimSoft > 0.01 && slope > 0.001) {
        // 边缘柔化：折射带内沿法线方向抹匀（宽度随斜面深度增长），
        // 压缩带从一条硬线变成一段渐变，采样点仍钳在内容区内
        float2 sm = n * (rimSoft * slope);
        float2 cR1 = clamp(cR - sm, lo, hi);
        float2 cR2 = clamp(cR + sm, lo, hi);
        float2 cG1 = clamp(cG - sm, lo, hi);
        float2 cG2 = clamp(cG + sm, lo, hi);
        float2 cB1 = clamp(cB - sm, lo, hi);
        float2 cB2 = clamp(cB + sm, lo, hi);
        col = float3(
            (content.eval(cR).r + content.eval(cR1).r + content.eval(cR2).r) / 3.0,
            (content.eval(cG).g + content.eval(cG1).g + content.eval(cG2).g) / 3.0,
            (content.eval(cB).b + content.eval(cB1).b + content.eval(cB2).b) / 3.0
        );
    } else {
        col = float3(
            content.eval(cR).r,
            content.eval(cG).g,
            content.eval(cB).b
        );
    }

    // 饱和度（合并进同一 pass）；提饱和端走 vibrancy 曲线：低饱和
    // 像素多提、高饱和像素少提、极亮像素保护，避免线性提饱和把浓色
    // 推过曝（曲线与上游 GlassRuntimeEffects 的 API 36 滤镜一致）
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    if (satFactor <= 1.0) {
        col = mix(float3(lum), col, satFactor);
    } else {
        float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
        float room = 1.0 - smoothstep(0.2, 0.85, satNow);
        float hl = 1.0 - smoothstep(0.75, 0.98, lum);
        float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
        col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
    }

    // 自适应染色（Regular）/ 压暗层（Clear）
    if (adaptiveTint > 0.5) {
        // 逐像素自适应：按局部（模糊后）亮度在提亮/压暗之间平滑过渡，
        // 玻璃跨明暗背景时不再整体翻转
        float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));
        float e = smoothstep(0.35, 0.75, lumT);
        col = mix(col, float3(1.0 - e), 0.14 + 0.08 * e);
    } else {
        col = mix(col, tintColor.rgb, tintColor.a);
    }
    col = col * (1.0 - dimAmount);

    // 使用方指定的玻璃本体色：按"有色介质"建模——吸收（保留背景明暗
    // 层次与折射细节）+ 少量散射（暗背景下也看得出色相）。
    // 位置在光照之前：染色属于透射，镜面高光属于表面反射，不该被染色
    if (glassTint.a > 0.002) {
        float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));
        float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);
        float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));
        col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);
    }

    // 光照：同一法线场驱动。整圈亮边的明暗只由 dot(N, -L) 决定，没有与方向
    // 无关的常亮项——侧向（法线垂直于光线处）归零，不会留下一圈固定描边。
    // 两道对称的角度瓣（沿 iOS 26 控制中心截图里的控件一圈逐角量得）：迎光侧
    // 与背光侧峰值相等（背光侧是透明介质的内壁反射），瓣宽 pow 4.5（离轴 30°
    // 剩一半、45° 归零）；迎光侧另加一层向内的柔和辉光——iOS 左上角是亮线 +
    // 辉光，右下角只有亮线，两侧的边缘内侧都没有暗带
    float facing = dot(n, -lightDir);
    float lobeF = pow(max(facing, 0.0), 4.5);
    float lobeB = pow(max(-facing, 0.0), 4.5);

    // 贴边亮线（中心在边内 1px，半宽 2px）+ 迎光侧辉光：从亮线内侧（边内 3px）起
    // 向内 1.5 次幂衰减，宽度与斜面弱相关、上限默认 6px、小控件按短边收。
    // 辉光不叠在亮线上，两侧亮线峰值相等
    float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
    float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
    float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
    float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
    float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF)
                 * specStrength * (1.0 - 0.35 * press);
    col += float3(spec);

    col = clamp(col, float3(0.0), float3(1.0));
    return half4(half3(col * cov), half(cov));
}
"""

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}
"""
