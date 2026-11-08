package me.weishu.kernelsu.ui.design.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * XEC Console · 底栏（2026-11-08 换掉「五个等宽 tab」）。
 *
 * 为什么换：KernelSU / Magisk 那一套骨架是「首页·超级用户·模块·检测·设置」五格等宽，
 * 换皮换不掉这个形状。这里只留两席 **模块 / 设备**，中间凸起的是**控制台**：
 * 超级用户、检测、设置都收进控制台弹层 —— 它们都是"进去看一眼"的页面，
 * 不配占常驻席位。
 *
 * 页面索引与 [MainPagerConfig] 保持一致（0 设备 / 1 超级用户 / 2 模块 / 3 检测 / 4 设置），
 * 所以这次改动不动 pager 本身：所有页面照旧可达，只是入口从"五格"变成"两席 + 控制台"。
 */
@Composable
fun Xc8Dock(
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    moduleBadge: Int = 0,
    superuserCount: Int = 0,
) {
    var consoleOpen by remember { mutableStateOf(false) }
    val rim = Xc.colors.glassRim
    val surface = Xc.colors.surface
    val accent = Xc.colors.accent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(surface),
    ) {
        // 1px 上边线：和面板族同一套分隔语言。
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(rim),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockSeat(
                icon = Icons.Rounded.Extension,
                label = "模块",
                selected = selectedPage == 2,
                badge = moduleBadge,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPage(2) },
            )
            // 中间给凸起按钮留位，宽度与按钮对齐，两侧席位才对称。
            Spacer(Modifier.width(96.dp))
            DockSeat(
                icon = Icons.Rounded.Cottage,
                label = "设备",
                selected = selectedPage == 0,
                badge = 0,
                modifier = Modifier.weight(1f),
                onClick = { onSelectPage(0) },
            )
        }

        // 控制台按钮：凸起 13dp，白圈把它从底栏上"抬"起来（原型里的做法）。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-13).dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable { consoleOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            ConsoleGlyph(tint = Color.White)
        }

        // 超级用户 / 检测 / 设置：都在控制台里。
        if (consoleOpen) {
            Xc8Dialog(
                title = "控制台",
                onDismissRequest = { consoleOpen = false },
                dismissText = "关闭",
            ) {
                ConsoleEntry(
                    icon = Icons.Rounded.Security,
                    title = "超级用户",
                    subtitle = if (superuserCount > 0) "$superuserCount 个已授权" else "还没有授权应用",
                ) {
                    consoleOpen = false
                    onSelectPage(1)
                }
                ConsoleEntry(
                    icon = Icons.Rounded.CheckCircleOutline,
                    title = "XEC SCORE",
                    subtitle = "九项完整性与兼容性检查",
                ) {
                    consoleOpen = false
                    onSelectPage(3)
                }
                ConsoleEntry(
                    icon = Icons.Rounded.Settings,
                    title = "设置",
                    subtitle = "外观、功能开关与模块选项",
                ) {
                    consoleOpen = false
                    onSelectPage(4)
                }
            }
        }
    }
}

/**
 * 底栏上的一席：图标 + 文字，选中态用强调色。
 *
 * 不用 miuix 的 `NavigationBarItem`：那一套自带水波纹与固定内边距，
 * 和这套方角、无波纹的语言冲突（按下态在面板族里是"整块压暗"）。
 */
@Composable
private fun DockSeat(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (selected) Xc.colors.accent else Xc.colors.textMuted
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-6).dp)
                        .clip(CircleShape)
                        .background(Xc.colors.accent)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = if (badge > 99) "99+" else badge.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(text = label, fontSize = 10.sp, color = tint)
    }
}

/** 控制台弹层里的一条入口。 */
@Composable
private fun ConsoleEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Xc8Row(
        title = title,
        subtitle = subtitle,
        leading = icon,
        onClick = onClick,
        trailing = { Xc8Chevron() },
    )
}

/**
 * 控制台图标：三条滑杆。刻意用 Canvas 自己画而不是引一个 Material 图标 ——
 * 这样不依赖图标库版本（缺失时会编译失败），造型也正好是"控制台"。
 * 颜色在 Canvas 之外取好：`Xc.colors` 是 @Composable，不能进 DrawScope。
 */
@Composable
private fun ConsoleGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.09f
        val xs = listOf(0.18f, 0.5f, 0.82f)
        val knobY = listOf(0.32f, 0.68f, 0.44f)
        for (i in xs.indices) {
            val y = h * (0.24f + i * 0.28f)
            drawLine(
                color = tint,
                start = Offset(w * 0.10f, y),
                end = Offset(w * 0.90f, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = tint,
                radius = stroke * 1.7f,
                center = Offset(w * xs[i], h * (knobY[i] * 1.0f)),
            )
        }
    }
}
