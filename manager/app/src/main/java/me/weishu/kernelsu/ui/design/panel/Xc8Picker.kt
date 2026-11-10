package me.weishu.kernelsu.ui.design.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * MIUI 8 弹出的两块东西：**对话框**与**下拉选择**。
 *
 * 为什么单独一个文件而不是塞进 [Xc8Panel]：面板族是"页面里的东西"，这两样是
 * "盖在页面上的东西"，它们的约束完全不同 —— 面板跟着背板走（可透），
 * 弹层必须自己撑起可读性（不透）。混在一个文件里，下一个人改面板样式时
 * 很容易顺手把弹层也改成半透，那就直接没法看了。
 *
 * ## 透明度约定（重要）
 *
 * 弹层**不**跟随「组件透明」开关：[Xc.surface] 是全透明时，弹层仍然用
 * [Xc.colors.surface] 实色。原因不是偷懒 —— 弹层底下是遮罩压暗的列表，
 * 全透明会让 15sp 的深色文字落在深色遮罩上，直接读不出来；
 * 主题里真正该"透"的是页面分组（[Xc8Panel]），弹层属于模态层。
 */

/** 弹层左右留白。MIUI 8 的对话框宽约 280dp / 屏宽 360dp，折下来就是这一档。 */
private val DialogPaddingH = 36.dp

/** 弹层按钮行高。 */
private val DialogActionHeight = 46.dp

/**
 * MIUI 8 对话框：白色圆角面板 + 居中标题 + 内容 + 底部按钮行。
 *
 * 底部按钮行是这一套的精髓：整行两等分，中间 1px 竖线，确认色 = 主题强调色。
 * 不用 Material 的 `TextButton`：那套是左右留白的胶囊按钮，MIUI 8 是"整格铺满"。
 *
 * @param confirmText 传 null = 只有一个按钮（纯提示框），此时占满整行。
 */
@Composable
fun Xc8Dialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val rim = Xc.colors.glassRim
    val surface = Xc.colors.surface
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = DialogPaddingH),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Xc.shapes.lg)
                    .background(surface),
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                    textAlign = TextAlign.Center,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                    content = content,
                )
                if (confirmText != null || dismissText != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DialogActionHeight)
                            .drawBehind {
                                drawLine(
                                    color = rim,
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            },
                    ) {
                        if (dismissText != null) {
                            Xc8DialogAction(
                                text = dismissText,
                                color = Xc.colors.textSecondary,
                                modifier = Modifier.weight(1f),
                                onClick = onDismissRequest,
                            )
                        }
                        if (confirmText != null) {
                            if (dismissText != null) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(DialogActionHeight)
                                        .background(rim),
                                )
                            }
                            Xc8DialogAction(
                                text = confirmText,
                                color = Xc.colors.accent,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onConfirm?.invoke()
                                    onDismissRequest()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 对话框底部的一格按钮（确认框、选择框共用）。 */
@Composable
fun Xc8DialogAction(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(DialogActionHeight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 下拉选择：行样式完全复用 [Xc8Row]（右侧显示当前值 + `›`），点开是 [Xc8Dialog] 里的单选列表。
 *
 * 与 miuix 的 `OverlayDropdownPreference` 是同一件事的 MIUI 8 版本：
 * 后者是"贴着那一行展开的下拉浮层"，MIUI 8 是"居中弹出的选择框"。
 * 参数刻意保持同形（title / items / selectedIndex / onSelectedIndexChange），
 * 换调用点时不用重写一遍状态逻辑。
 */
@Composable
fun Xc8Dropdown(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    enabled: Boolean = true,
    cancelText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = items.getOrNull(selectedIndex)
    val usable = enabled && items.isNotEmpty()
    Xc8Row(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        leading = leading,
        enabled = usable,
        onClick = if (usable) ({ expanded = true }) else null,
        trailing = {
            if (current != null) {
                Xc8Value(current)
            }
            Xc8Chevron()
        },
    )
    if (expanded) {
        Xc8Dialog(
            title = title,
            onDismissRequest = { expanded = false },
            dismissText = cancelText,
        ) {
            LazyColumn(
                // 选择框最多占屏幕一半多，再多就该滚动而不是把对话框撑出屏幕。
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                itemsIndexed(items) { index, label ->
                    val selected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectedIndexChange(index)
                                expanded = false
                            }
                            .padding(vertical = 13.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = if (selected) Xc.colors.accent else Xc.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Xc.colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
