package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.design.token.XcRadius
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DropdownItem(
    modifier: Modifier = Modifier,
    text: String,
    optionSize: Int,
    index: Int,
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    onSelectedIndexChange: (Int) -> Unit
) {
    val currentOnSelectedIndexChange = rememberUpdatedState(onSelectedIndexChange)
    val additionalTopPadding = if (index == 0) 20f.dp else 12f.dp
    val additionalBottomPadding = if (index == optionSize - 1) 20f.dp else 12f.dp

    // XEC Fluid Glass：列表项本身也要有圆角。
    // 第一项圆上两角、最后一项圆下两角，取值对齐 Xc.shapes.md（16dp）——
    // 这样即使外层弹窗容器不做裁剪，列表的最外侧也不会露出直角。
    val corner = XcRadius.md
    val shape = RoundedCornerShape(
        topStart = if (index == 0) corner else 0.dp,
        topEnd = if (index == 0) corner else 0.dp,
        bottomEnd = if (index == optionSize - 1) corner else 0.dp,
        bottomStart = if (index == optionSize - 1) corner else 0.dp,
    )

    Row(
        modifier = modifier
            .clip(shape)
            .clickable { currentOnSelectedIndexChange.value(index) }
            .background(dropdownColors.containerColor, shape)
            .padding(horizontal = 20.dp)
            .padding(
                top = additionalTopPadding,
                bottom = additionalBottomPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = dropdownColors.contentColor,
        )
    }
}
