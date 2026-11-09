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
import me.weishu.kernelsu.ui.design.token.Xc

/**
 * 底栏（2026-11-09 第三版：微透明 + 更圆润 + 四席）。
 *
 * 和上一版的差别：
 *  * 「检测」不再常驻 —— 它进工具箱的 XEC SCORE 卡（点卡 → `MainPagerBus.jumpTo(3)`），
 *    底栏只放四个每天都用的页；
 *  * 托盘从实色改成**微透明**（浅色 88% / 深色 82%）+ 1px 描边：和设计稿的"液态玻璃"
 *    神似，但不引入 backdrop 采样链（那条链在没有模糊的设备上会退化成一坨脏色）；
 *  * 每一席从圆角方块改成**圆形**，选中是实心墨圆 + 反色图标，托盘两端留出呼吸位。
 *
 * 页面索引与 [me.weishu.kernelsu.ui.viewmodel.MainPagerConfig] 保持一致：
 * 0 设备 / 1 超级用户 / 2 模块 / 4 设置（3 检测只在工具箱里可达）。
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
        DockItem(Icons.Rounded.Settings, "设置", 4),
    )
    val surface = Xc.colors.surface
    val rim = Xc.colors.glassRim
    val ink = Xc.colors.text
    val muted = Xc.colors.textMuted
    val pageBg = Xc.colors.backdrop
    // 微透明：底色本身就是页面的纸色，透出一点点让托盘"浮"起来，
    // 但不做 backdrop 模糊 —— 扁平面板一旦半透明到 70% 以下，文字会开始发虚。
    val trayAlpha = if (Xc.colors.isDark) 0.82f else 0.88f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(Xc.shapes.pill)
                .background(surface.copy(alpha = trayAlpha), Xc.shapes.pill)
                .border(1.dp, rim.copy(alpha = 0.75f), Xc.shapes.pill)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = selectedPage == item.page
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (selected) ink else pageBg.copy(alpha = 0f))
                        .clickable { onSelectPage(item.page) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) pageBg else muted,
                        modifier = Modifier.size(21.dp),
                    )
                    if (item.page == 2 && moduleBadge > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-7).dp, y = 8.dp)
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

/** 底栏一格：图标 + 无障碍标签 + 目标页。 */
private data class DockItem(
    val icon: ImageVector,
    val label: String,
    val page: Int,
)
