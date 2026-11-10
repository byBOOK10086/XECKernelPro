package me.weishu.kernelsu.ui.design.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcDuration
import me.weishu.kernelsu.ui.design.token.XcRadius
import me.weishu.kernelsu.ui.design.token.XcSpace
import me.weishu.kernelsu.ui.design.token.XcType

/**
 * 「减量 XEC」的行与数据层：图标方块、列表行、三格数据、hero、开关。
 *
 * 这些是参考稿里出现频率最高的形状——五个页面 80% 的面积由它们组成。
 * 旧的 `Xc8Row`（64 处调用）与新行长得不一样，所以 P2 换骨架时逐个替换，
 * 不做一眼看不出来的"半新半旧"。
 */

/** 行首图标方块：34dp、11dp 圆角、柔底 + 语义色符号。 */
@Composable
fun XcIconBox(symbol: String, tone: XcTone = XcTone.Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(XcRadius.iconBox)
    Box(
        modifier = modifier
            .size(XcSpace.iconBox)
            .clip(shape)
            .background(tone.soft),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = tone.ink, fontSize = XcType.section, fontWeight = FontWeight(600))
    }
}

/**
 * 列表行：图标 + 主/次文本 + 右侧值（或箭头）。
 *
 * 行高最小 62dp（参考稿口径），分隔线由调用方用 [XcCard] 包起来后自己画——
 * 这里只负责内容，不画上下边线，避免"卡里还有线"的套娃。
 */
@Composable
fun XcListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    symbol: String? = null,
    tone: XcTone = XcTone.Accent,
    value: String? = null,
    valueTone: XcTone = XcTone.Neutral,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(XcSpace.row)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (symbol != null) {
            XcIconBox(symbol, tone)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Xc.colors.text,
                fontSize = XcType.body,
                fontWeight = FontWeight(620),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    color = Xc.colors.textMuted,
                    fontSize = XcType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (value != null) {
            Text(value, color = valueTone.ink, fontSize = XcType.caption, fontWeight = FontWeight(600))
        }
        if (showChevron) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Xc.colors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 三格数据的一格。 */
data class XcStat(val label: String, val value: String, val caption: String? = null)

/** 三格数据：格间 1px 竖线，数字 19sp / 650。 */
@Composable
fun XcStatStrip(items: List<XcStat>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(XcRadius.card))
            .background(Xc.colors.surface)
            .padding(vertical = 14.dp),
    ) {
        items.forEachIndexed { index, stat ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 0.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(Xc.colors.glassRim),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(stat.label, color = Xc.colors.textMuted, fontSize = XcType.caption)
                Text(
                    stat.value,
                    color = Xc.colors.text,
                    fontSize = XcType.stat,
                    fontWeight = FontWeight(650),
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (stat.caption != null) {
                    Text(
                        stat.caption,
                        color = Xc.colors.accent,
                        fontSize = XcType.caption,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 顶部 hero：左侧标题组，右侧状态药丸。 */
@Composable
fun XcHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    kicker: String? = null,
    status: String? = null,
    statusTone: XcTone = XcTone.Success,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (!kicker.isNullOrEmpty()) {
                Text(
                    kicker,
                    color = Xc.colors.textMuted,
                    fontSize = XcType.caption,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight(600),
                )
            }
            Text(
                title,
                color = Xc.colors.text,
                fontSize = XcType.hero,
                fontWeight = FontWeight(620),
                lineHeight = 30.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
            Text(
                subtitle,
                color = Xc.colors.textMuted,
                fontSize = XcType.caption,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (status != null) {
            XcPill(status, statusTone)
        }
    }
}

/** 扁平开关：36×20 轨道 + 14dp 滑块，200ms。 */
@Composable
fun XcSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        animationSpec = tween(XcDuration.control),
        label = "xc-switch",
    )
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (checked) Xc.colors.accent else Xc.colors.glassRim)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(start = 3.dp, top = 3.dp)
                .padding(start = knobOffset)
                .size(14.dp)
                .clip(CircleShape)
                .background(Xc.colors.surface),
        )
    }
}
