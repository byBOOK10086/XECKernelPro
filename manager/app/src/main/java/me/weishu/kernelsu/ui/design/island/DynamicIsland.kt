package me.weishu.kernelsu.ui.design.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 灵动岛顶栏。
 *
 * 设计基准（用户点名 iOS / 小米 / vivo / OPPO）：
 * - iOS：紧凑↔展开用带过冲的弹簧形变（dampingRatio 0.55），尺寸连续生长，
 *   没有跳变；
 * - 小米：两态内容交叉淡化（AnimatedContent fade+scale），不做硬切；
 * - vivo/OPPO：展开卡再叠加一层 0.94→1 的缩放进场 + 状态点呼吸。
 *
 * 内容归属（设计决策）：
 * - 紧凑态：状态点（root 活性配色）+ 一行实时文本（当前页名 / 已授权数 /
 *   模块数 / 挂起提示），常驻顶部居中悬浮，避开状态栏；
 * - 展开态：玻璃卡（复用 xGlassBody 管线）+ 页面大标题 + 上下文操作 chips
 *   （各页通过 [IslandState] 注册）+ 收起按钮。
 * - 仅主 pager（Home/SuperUser/Module/Detect/Settings）挂岛；二级页有自己的
 *   大标题栏，不叠加。
 */
object IslandState {
    /** 当前主 pager 页（0..4）；-1 = 不在主 pager（岛隐藏）。 */
    var page by mutableStateOf(-1)

    /** 各页注册的上下文操作（页码 → 操作列表）。 */
    var pageActions by mutableStateOf<Map<Int, List<IslandAction>>>(emptyMap())

    /** 各页注册的实时状态文本。 */
    var pageLiveText by mutableStateOf<Map<Int, String>>(emptyMap())

    /** root 是否激活（紧凑态状态点颜色）。 */
    var rootActive by mutableStateOf(false)

    /** 临时提醒（如"模块已卸载"）：优先于页面实时文本，8 秒后清除。 */
    var toast by mutableStateOf<String?>(null)

    fun show(pageIndex: Int) {
        page = pageIndex
    }

    fun hide() {
        page = -1
    }

    fun registerActions(pageIndex: Int, actions: List<IslandAction>) {
        pageActions = pageActions + (pageIndex to actions)
    }

    fun registerLiveText(pageIndex: Int, text: String) {
        pageLiveText = pageLiveText + (pageIndex to text)
    }
}

data class IslandAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun DynamicIslandHost() {
    val page = IslandState.page
    AnimatedVisibility(
        visible = page in 0..4,
        enter = fadeIn(tween(200)) + expandVertically(expandFrom = Alignment.Top, animationSpec = tween(250)),
        exit = fadeOut(tween(180)) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(220)),
    ) {
        var expanded by remember { mutableStateOf(false) }
        val compactWidth = 168.dp
        val expandedWidth = 312.dp
        val compactHeight = 38.dp
        val expandedHeight = 148.dp
        // iOS 弹簧：尺寸生长带轻微过冲；小米/OPPO 的缩放淡化交给 AnimatedContent。
        val width by animateDpAsState(
            targetValue = if (expanded) expandedWidth else compactWidth,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
            label = "islandWidth",
        )
        val height by animateDpAsState(
            targetValue = if (expanded) expandedHeight else compactHeight,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
            label = "islandHeight",
        )
        val shape = RoundedCornerShape(if (expanded) 28.dp else 19.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(width)
                    .height(height)
                    .xGlassBody(
                        backdrop = null,
                        shape = shape,
                        tint = Color.Black.copy(alpha = 0.52f),
                        rim = true,
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.10f),
                                Color.Transparent,
                            ),
                        ),
                        shape,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = {
                        // 小米式交叉淡化 + OPPO/vivo 式轻微缩放
                        (fadeIn(tween(220)) + androidx.compose.animation.scaleIn(
                            initialScale = 0.94f,
                            animationSpec = tween(240),
                        )) togetherWith
                            (fadeOut(tween(160)) + androidx.compose.animation.scaleOut(
                                targetScale = 0.96f,
                                animationSpec = tween(180),
                            ))
                    },
                    label = "islandContent",
                ) { isExpanded ->
                    if (isExpanded) {
                        IslandExpandedContent(page = page, onCollapse = { expanded = false })
                    } else {
                        IslandCompactContent(page = page)
                    }
                }
            }
        }
    }
}

@Composable
private fun IslandCompactContent(page: Int) {
    val dotColor = if (IslandState.rootActive) Color(0xFF66BB6A) else Color(0xFFFFB300)
    val liveText = IslandState.toast
        ?: IslandState.pageLiveText[page]
        ?: defaultLiveText(page)
    Row(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态点：root 活性；呼吸用 alpha 往复（1.6s 一周期）。
        val breath = rememberInfiniteTransition(label = "islandBreath").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
            label = "islandBreathAlpha",
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(breath.value)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = liveText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun IslandExpandedContent(page: Int, onCollapse: () -> Unit) {
    val nav = me.weishu.kernelsu.ui.navigation3.LocalNavigator.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = if (IslandState.rootActive) Color(0xFF66BB6A) else Color(0xFFFFB300),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = pageTitle(page),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = IslandState.pageLiveText[page] ?: defaultLiveText(page),
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (IslandState.pageActions[page] ?: defaultActions(page, nav)).take(3).forEach { action ->
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            action.onClick()
                            onCollapse()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = action.label,
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun pageTitle(page: Int): String = when (page) {
    0 -> stringResource(me.weishu.kernelsu.R.string.island_home)
    1 -> stringResource(me.weishu.kernelsu.R.string.island_superuser)
    2 -> stringResource(me.weishu.kernelsu.R.string.island_modules)
    3 -> stringResource(me.weishu.kernelsu.R.string.island_detect)
    4 -> stringResource(me.weishu.kernelsu.R.string.island_settings)
    else -> ""
}

@Composable
private fun defaultLiveText(page: Int): String = when (page) {
    0 -> stringResource(
        if (IslandState.rootActive) me.weishu.kernelsu.R.string.island_working
        else me.weishu.kernelsu.R.string.island_not_working
    )
    else -> ""
}

/** 未注册页的兜底操作：SuperUser/Module 各给一个跳转。 */
@Composable
private fun defaultActions(page: Int, nav: me.weishu.kernelsu.ui.navigation3.Navigator): List<IslandAction> = when (page) {
    2 -> listOf(
        IslandAction(stringResource(me.weishu.kernelsu.R.string.kpm), Icons.Rounded.Memory) {
            nav.push(me.weishu.kernelsu.ui.navigation3.Route.Kpm)
        },
    )
    0 -> listOf(
        IslandAction(stringResource(me.weishu.kernelsu.R.string.install), Icons.Rounded.Bolt) {
            nav.push(me.weishu.kernelsu.ui.navigation3.Route.Install)
        },
    )
    else -> emptyList()
}
