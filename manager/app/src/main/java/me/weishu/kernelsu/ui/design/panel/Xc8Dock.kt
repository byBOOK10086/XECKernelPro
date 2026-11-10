package me.weishu.kernelsu.ui.design.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.design.token.XcEasing
import top.yukonga.miuix.kmp.basic.Icon

/** 一席的直径。 */
private val DockSlot = 46.dp

/** 席间空隙。滑块位移量 = [DockSlot] + [DockGap]。 */
private val DockGap = 6.dp

/**
 * 底栏（2026-11-11 第四版：选中态从"实心墨圆"改成"强调色滑块"）。
 *
 * ## 这一版修的是什么
 *
 * 第三版把每一席做成「选中 = 实心圆填 `Xc.colors.text`」。在奶白纸面上，那是
 * 一颗 #29332E 的近黑圆，**不透明、无过渡、瞬间跳变** —— 用户原话是"深色圆形过于
 * 突兀"。突兀感来自三件事的叠加：太深的填充、太硬的边界、以及切换时没有任何过渡。
 *
 * 这一版三件一起改：
 *  * 填充 → `accent @ 14%`：还是"选中"，但落在纸色上是鼠尾草绿的一层薄雾，
 *    不再是贴上去的一块黑；
 *  * 形状 → 仍然 46dp 圆形（设计稿的圆是这一栏的特征，不改）；
 *  * 运动 → 改成**一颗滑块在四席之间移动** + 图标颜色/缩放过渡。上一版是每格
 *    自己闪一下，眼睛看到的是一次跳变；单颗滑块移动才能读出"位置变了"。
 *
 * ## 为什么不做"液体玻璃"
 *
 * 仓库里那套玻璃底栏（`ui/component/FloatingBottomBar.kt`）已经不在这条渲染路径上
 * （`BottomBar()` 零调用），而且它依赖 backdrop 采样链，在没有模糊的设备上会退化成
 * 一坨脏色。这一栏保持"微透明实色 + 1px 描边"，与设计稿的克制方向一致。
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
    val accent = Xc.colors.accent
    // 微透明：底色本身就是页面的纸色，透出一点点让托盘"浮"起来，
    // 但不做 backdrop 模糊 —— 扁平面板一旦半透明到 70% 以下，文字会开始发虚。
    val trayAlpha = if (Xc.colors.isDark) 0.82f else 0.88f

    // selectedPage 有可能是 3（检测页）—— 那一页只在工具箱里可达，底栏没有对应席位。
    // 找不到就落到第一席，不能让它算成 -1 把滑块顶到托盘外。
    val selectedIndex = items.indexOfFirst { it.page == selectedPage }.coerceAtLeast(0)

    val pitch = DockSlot + DockGap
    val indicatorOffset by animateDpAsState(
        targetValue = pitch * selectedIndex,
        animationSpec = tween(Xc.motion.state, easing = XcEasing.standard),
        label = "dockIndicator",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(Xc.shapes.pill)
                .background(surface.copy(alpha = trayAlpha), Xc.shapes.pill)
                .border(1.dp, rim.copy(alpha = 0.75f), Xc.shapes.pill)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            // 滑块与图标叠在同一个坐标系里：滑块按 pitch 平移，图标行位置固定，
            // 所以容器宽度自始至终不变 —— 设计要求「不允许导航栏整体跳动」。
            Box {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .size(DockSlot)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DockGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { item ->
                        DockItemCell(
                            item = item,
                            selected = item.page == selectedPage,
                            showBadge = item.page == 2 && moduleBadge > 0,
                            onClick = { onSelectPage(item.page) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一席：图标 + 无障碍标签 + 目标页。
 *
 * 颜色与缩放都是 [animateColorAsState] / [animateFloatAsState] 驱动的，不用
 * `indication` —— 底栏的反馈是"滑块移过去 + 图标变色"，再叠一层按压凹陷会互相打架。
 */
@Composable
private fun DockItemCell(
    item: DockItem,
    selected: Boolean,
    showBadge: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) Xc.colors.accent else Xc.colors.textMuted,
        animationSpec = tween(Xc.motion.state, easing = XcEasing.standard),
        label = "dockTint",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) Xc.motion.navIconScale else 1f,
        animationSpec = tween(Xc.motion.state, easing = XcEasing.standard),
        label = "dockScale",
    )
    Box(
        modifier = Modifier
            .size(DockSlot)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        if (showBadge) {
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

/** 底栏一格：图标 + 无障碍标签 + 目标页。 */
private data class DockItem(
    val icon: ImageVector,
    val label: String,
    val page: Int,
)
