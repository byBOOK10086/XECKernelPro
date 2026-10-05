package me.weishu.kernelsu.ui.component.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.component.WarningLevel
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    level: WarningLevel = WarningLevel.Error,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    backdrop: LayerBackdrop? = null,
) {
    Card(
        // 告警卡是全 App 复用最广的容器（首页 7 处、日志页 3 处）。
        // 卡底交给玻璃管线，但**语义色不能丢**：语义色改从 `tint` 走 —— 传同色相的
        // 低透明度版本，有模糊时是 13%~22% 的红/橙压在模糊画面上（是玻璃不是色块）；
        // 拿不到 backdrop（预览 / 关掉模糊 / 设备不支持）时 `tint.compositeOver(surface)`
        // 复合出的正是原来的 dangerTint / warningTint，兜底观感与改动前一致。
        // `backdrop` 带默认值 null：本文件外调用点（首页、日志页）与 @Preview 都不必改。
        modifier = modifier.xGlassBody(
            backdrop = backdrop,
            shape = Xc.shapes.md,
            tint = level.tintColor(),
        ),
        onClick = { onClick?.invoke() },
        colors = CardDefaults.defaultColors(
            // 卡底必须透明：miuix Card 的底色画在本 modifier 链内侧，留着会盖住玻璃。
            color = Color.Transparent,
            contentColor = level.contentColor(),
        ),
        showIndication = onClick != null,
        pressFeedbackType = PressFeedbackType.Sink
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                fontSize = 14.sp
            )
            action?.invoke()
        }
    }
}

/**
 * 语义色改从 `tint` 走：同色相的低透明度版本，alpha 与 [Xc.colors] 里
 * `dangerTint` / `warningTint` 合成时用的那一档取值一致（深色 0.22、浅色 0.13 / 0.15），
 * 所以没玻璃时 `compositeOver(surface)` 复合回的正是原来那枚不透明容器色。
 */
@Composable
private fun WarningLevel.tintColor(): Color = when (this) {
    WarningLevel.Error -> Xc.colors.danger.copy(alpha = if (Xc.colors.isDark) 0.22f else 0.13f)
    WarningLevel.Notice -> Xc.colors.warning.copy(alpha = if (Xc.colors.isDark) 0.22f else 0.15f)
}

@Composable
private fun WarningLevel.contentColor(): Color = when (this) {
    WarningLevel.Error -> Xc.colors.onDangerTint
    WarningLevel.Notice -> Xc.colors.onWarningTint
}
