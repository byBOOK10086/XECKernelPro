package me.weishu.kernelsu.ui.design.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 底栏（扁平方案 A，2026-11-09）。
 *
 * 取代 2026-11-08 的「两席 + 中央径向控制台」：那套是玻璃时代的产物，靠凸起圆钮
 * 和手势环制造记忆点；设计稿定稿的扁平语言里不再有凸起、光晕和弹层，所以
 * 回到"一条胶囊托盘 + 五个等距图标"——但**页数一项没少**，0 设备 / 1 超级用户 /
 * 2 模块 / 3 检测 / 4 设置 全部直接可达，比径向菜单少一次手势。
 *
 * 选中态是整块实心（墨色底 + 反色图标），不是染色图标：扁平语言里"选中"
 * 用面来表达，不用颜色表达，这样主题换色也不会让底栏变成一栏彩色药丸。
 */
@Composable
fun Xc8Dock(
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    moduleBadge: Int = 0,
    @Suppress("UNUSED_PARAMETER") superuserCount: Int = 0,
) {
    val items = listOf(
        DockItem(Icons.Rounded.Cottage, "设备", 0),
        DockItem(Icons.Rounded.Person, "超级用户", 1),
        DockItem(Icons.Rounded.Extension, "模块", 2),
        DockItem(Icons.Rounded.CheckCircleOutline, "检测", 3),
        DockItem(Icons.Rounded.Settings, "设置", 4),
    )
    val surface = Xc.colors.surface
    val rim = Xc.colors.glassRim
    val ink = Xc.colors.text
    val muted = Xc.colors.textMuted
    val pageBg = Xc.colors.backdrop

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(pageBg)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, rim, Xc.shapes.pill)
                .background(surface, Xc.shapes.pill)
                .padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = selectedPage == item.page
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(Xc.shapes.lg)
                        .background(if (selected) ink else surface)
                        .clickable { onSelectPage(item.page) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) pageBg else muted,
                        modifier = Modifier.size(22.dp),
                    )
                    if (item.page == 2 && moduleBadge > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-4).dp, y = 4.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Xc.colors.accent),
                        )
                    }
                }
            }
        }
    }
}

/** 底栏一格：图标 + 语义（无障碍用）+ 目标页。 */
private data class DockItem(
    val icon: ImageVector,
    val label: String,
    val page: Int,
)

/** 供将来"控制台"类入口复用的行（当前底栏不渲染，保留以兼容同包调用）。 */
@Composable
private fun DockCaption(text: String) {
    Text(text = text, fontSize = 10.sp, color = Xc.colors.textMuted)
}
