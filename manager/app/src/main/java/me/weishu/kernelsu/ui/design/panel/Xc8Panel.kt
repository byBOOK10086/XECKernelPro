package me.weishu.kernelsu.ui.design.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcBlockBackground
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * MIUI 8 面板族（2026-11-08 全盘换装）。
 *
 * 这一层是"界面骨架"，不是又一套卡片皮肤：MIUI 8 的组织方式只有三样东西 ——
 * 浅灰底 + 白色分组面板 + 1px 分隔线。密度的来源不是间距变大变小，
 * 而是每一行都同时承载"标题 + 副标题 + 右值"三档信息。
 *
 * 与 [me.weishu.kernelsu.ui.design.glass.XGlassSurface] 的关系：面板本体复用
 * `xGlassBody`，这样主题里给某个区块配的背景图（[XcBlockBackground]）继续生效；
 * 今天 backdrop 恒为 null，于是它落在"实色 + 1px 描边"那一支，正好就是白面板。
 * 换句话说：没有主题时它就是纯白分组，有主题时仍然是用户自己设的那张图。
 *
 * 分隔线用"每行自绘上边线"而不是让面板去数第几个孩子：
 * 首行的线正好压在面板描边的同一像素上（两者同色同宽），看不出双线；
 * 面板一旦包住矩形子项就没有"第一个是谁"这个信息，硬算反而会在条件渲染时错位。
 */

/** 行内左右留白。MIUI 8 的行是 15dp，比 Material 的 16dp 略窄，为的是多塞一点字符。 */
private val RowPaddingH = 14.dp

/** 行内上下留白。13dp 是"有副标题也撑得住、没副标题也不塌"的那一档。 */
private val RowPaddingV = 12.dp

/** 分组标题：面板之外，左对齐，字号比正文小一档。 */
@Composable
fun Xc8SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 3.dp, end = 3.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * 白色分组面板。子项之间靠 [Xc8Row] 自绘的上边线分隔，所以这里不加 spacing ——
 * 加了 spacing 会让线与面板底色之间露出一条更宽的缝，看着像每行各是一张卡。
 *
 * [block] 传主题区块 id 对应的背景；null = 今天这张纯白面板。
 */
@Composable
fun Xc8Panel(
    modifier: Modifier = Modifier,
    block: XcBlockBackground? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = Xc.shapes.lg
    // 主题/设置把组件调成半透明或全透明时，不能再走 xGlassBody：它的实色兜底一定会画
    // 不透明底色（那是"没有 backdrop 时别塌成黑框"的保险）。这一支改用直接描边 + 可调 alpha。
    val translucent = Xc.surfaceAlpha < 0.999f
    val container = if (translucent) {
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Xc.surface)
            .border(1.dp, Xc.colors.glassRim, shape)
    } else {
        modifier
            .fillMaxWidth()
            .xGlassBody(backdrop = null, shape = shape, block = block)
    }
    Column(modifier = container, content = content)
}

/**
 * 面板里的一行：可选左图标 + 标题/副标题 + 右侧插槽。
 *
 * [onClick] 为 null 时不可点（纯信息行）；不为 null 时按下整行转 [Xc.surfaceMuted]。
 * 这里刻意不要水波纹：MIUI 8 的列表按下是"整行压暗"，而 Material 的圆形水波纹
 * 在这套方角面板里会显得既不像也不齐。
 */
@Composable
fun Xc8Row(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    /**
     * 自定义前导块（miuix 的 `startAction` 可以原样传进来）。
     *
     * 给了它就先画它、不再画 [leading] 的橙色图标砖：miuix 那些调用点早就自己带了
     * 图标与间距，再套一层砖会变成"砖里套图标"的双层。
     * 代价是禁用时的淡化由调用方自己负责（砖会自动转灰，这里不会）。
     */
    leadingContent: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    titleColor: Color = Xc.colors.text,
    /**
     * false = 整行**看起来就是不可用**：标题/副标题转 `textMuted`、图标砖转淡、点击摘掉。
     *
     * 只靠 `onClick = null` 是不够的——那会出现"看着能点却点不动"，是设置页里最容易踩的假可用。
     */
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rim = Xc.colors.glassRim
    val clickable = onClick != null && enabled
    // 按下=整行压暗，120ms 过渡（原型 `.row--action` 的 0.12s）；不是水波纹。
    val background by animateColorAsState(
        targetValue = if (pressed && clickable) Xc.surfaceMuted else Xc.surface,
        animationSpec = tween(Xc8Motion.press),
        label = "xc8RowBg",
    )
    val effectiveTitleColor = if (enabled) titleColor else Xc.colors.textMuted
    val subtitleColor = if (enabled) Xc.colors.textSecondary else Xc.colors.textMuted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                drawLine(
                    color = rim,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .then(
                if (!clickable) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick?.invoke() },
                    )
                }
            )
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(12.dp))
        } else if (leading != null) {
            // 26dp 橙色砖里的图标（原型 `.row__icon`）；禁用时砖与图标一起转淡。
            Xc8IconTile(tint = if (enabled) Xc.colors.accent else Xc.colors.textMuted) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    tint = if (enabled) Xc.colors.accent else Xc.colors.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = effectiveTitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 插槽恒在：`trailing = {}` 的宽度是 0，只多出 10dp 的前导间隔，
        // 在标题用了 weight(1f) 的行里看不出来；反过来给它开一个"有没有插槽"
        // 的布尔参数，会让每个调用点都要解释一次自己要不要，得不偿失。
        Spacer(Modifier.width(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = trailing,
        )
    }
}

/** 行右侧的值文本。 */
@Composable
fun Xc8Value(text: String, modifier: Modifier = Modifier, tint: Color = Xc.colors.textSecondary) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 14.sp,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 行右侧的 `›`。 */
@Composable
fun Xc8Chevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = Xc.colors.textMuted,
        modifier = modifier.size(18.dp),
    )
}

/** 标签色调。语义色只用于"状态"，不用于装饰。 */
enum class Xc8Tone { Neutral, Accent, Success, Warning, Danger }

/** 小标签（`稳定` / `已启用` / `未启用`）。 */
@Composable
fun Xc8Tag(text: String, modifier: Modifier = Modifier, tone: Xc8Tone = Xc8Tone.Neutral) {
    val background = when (tone) {
        Xc8Tone.Neutral -> Xc.colors.glassRim
        Xc8Tone.Accent -> Xc.colors.accentSoft
        Xc8Tone.Success -> Xc.colors.successTint
        Xc8Tone.Warning -> Xc.colors.warningTint
        Xc8Tone.Danger -> Xc.colors.dangerTint
    }
    val foreground = when (tone) {
        Xc8Tone.Neutral -> Xc.colors.textSecondary
        Xc8Tone.Accent -> Xc.colors.accent
        Xc8Tone.Success -> Xc.colors.onSuccessTint
        Xc8Tone.Warning -> Xc.colors.onWarningTint
        Xc8Tone.Danger -> Xc.colors.onDangerTint
    }
    Text(
        text = text,
        modifier = modifier
            .clip(Xc.shapes.xs)
            .background(background)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        fontSize = 11.sp,
        color = foreground,
        maxLines = 1,
    )
}

/**
 * 面板底部的说明行（浅灰小字，不带分隔线）。
 *
 * 单独开一个而不是复用 [Xc8Row]：说明文字没有可点区域，套进行高会造成
 * "看着能点却点不动"。
 */
@Composable
fun Xc8Note(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Xc.surface),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Xc.colors.textMuted,
            modifier = Modifier.padding(start = RowPaddingH, end = RowPaddingH, top = 2.dp, bottom = 10.dp),
        )
    }
}

/** 面板里的竖排键值格（模式卡的四格详情）。 */
@Composable
fun Xc8KeyValueCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = RowPaddingH, vertical = 9.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Xc.colors.textMuted,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Xc.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * MIUI 8 的方角按钮：32dp 高、4dp 圆角、13sp。
 *
 * 按下态按原型处理：主按钮从强调橙压暗一档（`--accent-strong`），
 * 次按钮（描边款）底色转淡橙、文字与描边转橙。过渡 120ms，不缩放。
 */
@Composable
fun Xc8Button(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = Xc.shapes.sm
    val base = if (primary) Xc.colors.accent else Xc.surface
    val pressedBackground = when {
        primary -> xc8PressedColor(Xc.colors.accent)
        else -> Xc.colors.accentSoft
    }
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> base.copy(alpha = 0.45f)
            pressed -> pressedBackground
            else -> base
        },
        animationSpec = tween(Xc8Motion.press),
        label = "xc8ButtonBg",
    )
    val contentColor = when {
        !enabled -> Xc.colors.textMuted
        primary -> Color.White
        pressed -> Xc.colors.accent
        else -> Xc.colors.text
    }
    val borderColor = when {
        primary -> Color.Transparent
        pressed -> Xc.colors.accent
        else -> Xc.colors.glassRim
    }
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 13.sp, color = contentColor, maxLines = 1)
    }
}

/**
 * 页头：小字提示（kicker）+ 大标题 + 一句说明 + 右侧操作区。
 *
 * 原型里它是"这一页在讲什么"的唯一说明位，所以三段都在面板**之外**：
 * 塞进面板会让第一块面板看起来像页头的一部分。
 */
@Composable
fun Xc8PageHeading(
    title: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 3.dp, end = 3.dp, top = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (kicker != null) {
                Text(text = kicker, fontSize = 11.sp, color = Xc.colors.textMuted, maxLines = 1)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = title,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = Xc.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Xc.colors.textSecondary,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}
