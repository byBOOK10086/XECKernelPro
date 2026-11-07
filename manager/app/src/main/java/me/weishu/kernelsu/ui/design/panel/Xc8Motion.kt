package me.weishu.kernelsu.ui.design.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text

/**
 * MIUI 8 的按键特性与动画。
 *
 * 这里的每个数值都来自原型的 CSS，不是"看着差不多"：
 *  * 整行/按钮按下 = **压暗一档**（`.row--action:hover` → `--surface-alt`，`.button:hover`
 *    → `--accent-strong #e85e00`），过渡 120ms；**不是**放大、不是水波纹、不是阴影加深。
 *  * 开关轨道 150ms 变色、滑块 150ms 位移（`.switch`）。
 *  * 分段控件选中块 150ms 变色（方案 A：每段独立底色；滑动指示器会引入测量耦合，
 *    而这套控件在设置页只有两处，视觉收益不值那个风险）。
 *  * 模式卡收起/展开 220ms，光带扫过徽标 4.4s 循环（`@keyframes badge-sheen`）。
 *
 * 为什么把时长集中在一个 object：它们必须彼此协调（按下要比展开快一档，否则手感会"黏"），
 * 散在十几个调用点迟早漂移。
 */
object Xc8Motion {
    /** 120ms：按下/松开的底色过渡（原型 `transition: background 0.12s ease`）。 */
    const val press = 120

    /** 150ms：开关、分段控件这类"有位移"的小控件。 */
    const val control = 150

    /** 220ms：卡片展开/收起（原型 `max-height 0.22s`）。 */
    const val expand = 220

    /** 180ms：淡入淡出（原型 `opacity 0.18s`）。 */
    const val fade = 180

    /** 4400ms：徽标光带的一个周期。 */
    const val sheen = 4400
}

/**
 * 按下态颜色：MIUI 8 是"同一个色被压暗一档"。
 *
 * 深色档往白里混、浅色档往黑里混——直接乘 alpha 会透出下层，在这套不透明面板里
 * 会显得脏（那正是上一版玻璃形态的做法）。
 */
@Composable
fun xc8PressedColor(base: Color): Color =
    if (Xc.colors.isDark) lerp(base, Color.White, 0.14f) else lerp(base, Color.Black, 0.12f)

/**
 * 徽标/角标上的斜向光带（原型 `@keyframes badge-sheen`）。
 *
 * 作用在**底色之上、文字之下**：调用方把本修饰符挂在一个 `matchParentSize` 的空 Box 上，
 * 再把文字画在它后面，光带就永远压不到字（原型用 `z-index:-1` 解决同一个问题）。
 *
 * 用 `rememberInfiniteTransition` 而不是自建动画：它只让绘制阶段失效，不触发重组，
 * 面积又只有徽标那么大，代价可以忽略；真要关掉，删掉调用点即可，这里不留开关。
 */
@Composable
fun Modifier.xc8Sheen(periodMillis: Int = Xc8Motion.sheen): Modifier {
    val transition = rememberInfiniteTransition(label = "xc8Sheen")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "xc8SheenProgress",
    )
    return drawWithContent {
        drawContent()
        val width = size.width
        val height = size.height
        // -1 → 2 个自身宽度：起点在左侧外，终点在右侧外，扫过时才有"经过"的观感。
        val center = width * (-1f + 3f * progress)
        val band = width * 0.62f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                start = Offset(center - band / 2f, 0f),
                end = Offset(center + band / 2f, height),
            ),
        )
    }
}

/**
 * MIUI 8 开关：40×22 的轨道 + 18dp 白钮，选中转橙。
 *
 * 不用 miuix 的 `Switch`：那一档是 HyperOS 的尺寸（更宽更高、带描边），
 * 放进 46dp 的行里会把行撑高，整套密度就散了。
 */
@Composable
fun Xc8Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackOff = if (Xc.colors.isDark) Color(0xFF3A3A41) else Color(0xFFD8D8D8)
    val track by animateColorAsState(
        targetValue = if (checked) Xc.colors.accent else trackOff,
        animationSpec = tween(Xc8Motion.control),
        label = "xc8SwitchTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        animationSpec = tween(Xc8Motion.control),
        label = "xc8SwitchKnob",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(Xc.shapes.pill)
            .background(track)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .offset(x = knobOffset)
                .size(18.dp)
                .shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.7f)),
        )
    }
}

/**
 * 分段控件（原型 `.segmented`）：方角外框 + 选中段实橙。
 *
 * 选中块用底色过渡而不是滑动指示器：滑动需要按段宽测量，段数或文案一变就要重算，
 * 而这两处（主题模式、界面缩放）的段数是固定的，视觉收益抵不上那层耦合。
 */
@Composable
fun Xc8Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(Xc.shapes.sm)
            .background(Xc.colors.surface),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                targetValue = if (selected) Xc.colors.accent else Color.Transparent,
                animationSpec = tween(Xc8Motion.control),
                label = "xc8SegBg$index",
            )
            val foreground by animateColorAsState(
                targetValue = if (selected) Color.White else Xc.colors.textSecondary,
                animationSpec = tween(Xc8Motion.control),
                label = "xc8SegFg$index",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .background(background)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = option, fontSize = 11.5.sp, color = foreground, maxLines = 1)
            }
        }
    }
}

/**
 * 行首的图标砖（原型 `.row__icon`：26×26、2dp 圆角、橙色的低饱和底）。
 *
 * 单独开一个而不是让 [Xc8Row] 直接画裸图标：原型里每个可操作行的左图标都是
 * "砖"而不是"字形"，这是这套列表最容易一眼认出来的细节。
 */
@Composable
fun Xc8IconTile(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(Xc.shapes.xs)
            .background(Xc.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
