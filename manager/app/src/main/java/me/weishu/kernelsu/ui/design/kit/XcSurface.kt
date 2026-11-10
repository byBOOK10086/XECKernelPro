package me.weishu.kernelsu.ui.design.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcPaletteReduced
import me.weishu.kernelsu.ui.design.token.XcRadius
import me.weishu.kernelsu.ui.design.token.XcSpace
import me.weishu.kernelsu.ui.design.token.XcType

/**
 * 「减量 XEC」表面层：一张卡、一个分区标题、一条提示带、一颗状态药丸。
 *
 * 与旧玻璃体系的区别只有三条：**不用模糊、不用阴影、只用 1px 发丝线**。
 * 参考稿里所有"卡"都是这个形状，页面不再各自拼 Surface/Card。
 */
@Composable
fun XcCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = XcSpace.card,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(XcRadius.card)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Xc.colors.surface, shape)
            .border(1.dp, Xc.colors.glassRim, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(contentPadding),
    ) {
        content()
    }
}

/** 分区标题：14sp / 650，右侧可挂一个动作。 */
@Composable
fun XcSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = XcSpace.section, bottom = XcSpace.sectionHead),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Xc.colors.text,
            fontSize = XcType.section,
            fontWeight = FontWeight(650),
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** 状态药丸：柔底 + 同色文字，20dp 圆角。 */
@Composable
fun XcPill(text: String, tone: XcTone = XcTone.Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(XcRadius.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(tone.soft)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = tone.ink, fontSize = XcType.caption, fontWeight = FontWeight(600))
    }
}

/** 提示带：浅主色底 + 一句话，参考稿的 banner。 */
@Composable
fun XcBanner(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    tone: XcTone = XcTone.Accent,
) {
    val shape = RoundedCornerShape(XcRadius.banner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.soft)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(tone.ink),
        )
        Column {
            Text(title, color = Xc.colors.text, fontSize = XcType.sub, fontWeight = FontWeight(620))
            Text(
                text,
                color = Xc.colors.textSecondary,
                fontSize = XcType.caption,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 文字动作：右侧「详细信息 ›」那种，12sp 主色。 */
@Composable
fun XcTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Xc.colors.accent,
        fontSize = XcType.body,
        fontWeight = FontWeight(650),
        modifier = modifier.clickable { onClick() },
    )
}

/**
 * 语义色档。
 *
 * 参考稿只有三条色相（绿 / 玫瑰 / 琥珀），连同中性、危险、成功一起收敛成枚举——
 * 页面不再自己挑颜色，只能选语义。
 */
enum class XcTone { Neutral, Accent, Rose, Amber, Danger, Success }

/** 语义色前景。 */
val XcTone.ink: Color
    @Composable get() = when (this) {
        XcTone.Neutral -> Xc.colors.textSecondary
        XcTone.Accent -> Xc.colors.accent
        XcTone.Rose -> if (Xc.colors.isDark) XcPaletteReduced.dRose else XcPaletteReduced.rose
        XcTone.Amber -> if (Xc.colors.isDark) XcPaletteReduced.dAmber else XcPaletteReduced.amber
        XcTone.Danger -> Xc.colors.danger
        XcTone.Success -> Xc.colors.success
    }

/** 语义色柔底。 */
val XcTone.soft: Color
    @Composable get() = when (this) {
        XcTone.Neutral -> Xc.colors.surfaceMuted
        XcTone.Accent -> Xc.colors.accentSoft
        XcTone.Rose -> if (Xc.colors.isDark) XcPaletteReduced.dRoseSoft else XcPaletteReduced.roseSoft
        XcTone.Amber -> if (Xc.colors.isDark) XcPaletteReduced.dAmberSoft else XcPaletteReduced.amberSoft
        XcTone.Danger -> Xc.colors.dangerTint
        XcTone.Success -> Xc.colors.successTint
    }
