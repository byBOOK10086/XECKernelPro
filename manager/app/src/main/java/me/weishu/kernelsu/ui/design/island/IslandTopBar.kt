package me.weishu.kernelsu.ui.design.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.ui.design.glass.xGlassBody
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 灵动岛顶栏：顶栏本身就是岛的展开态。
 *
 * 设计（用户定稿）：删除顶栏的果冻玻璃背景——展开态就是原顶栏内容（无底、
 * 透明浮在页面上）；上滑收缩成顶部的黑色玻璃胶囊（紧凑岛），点按或下拉
 * 再展开回顶栏。动画语言：iOS 弹簧形变（尺寸连续生长带过冲）+ 小米交叉
 * 淡化 + OPPO/vivo 缩放进场。
 *
 * [content] 是各页原有的 TopAppBar（调用方把 BlurredBar 玻璃底去掉，透明
 * 传入）；收起后整条顶栏变成为 [islandTitle]/[islandLive] 服务的胶囊。
 */
@Composable
fun IslandTopBar(
    islandTitle: String,
    islandLive: String?,
    statusActive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 上滑收缩 / 下拉展开的累计位移阈值。
    var dragAccum by remember { mutableFloatStateOf(0f) }

    val pillHeight = 42.dp
    // 弹簧形变：iOS 岛的"连续生长"手感（dampingRatio 0.55 带轻过冲）。
    val collapseFraction by animateDpAsState(
        targetValue = if (expanded) 0.dp else pillHeight,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "islandCollapse",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(expanded) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccum = 0f },
                ) { _, dragAmount ->
                    dragAccum += dragAmount
                    if (expanded && dragAccum > 96f) {
                        expanded = false
                        dragAccum = 0f
                    } else if (!expanded && dragAccum < -96f) {
                        expanded = true
                        dragAccum = 0f
                    }
                }
            },
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                // 小米式交叉淡化 + OPPO/vivo 式缩放进场。
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f, animationSpec = tween(240)))
                    .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.96f, animationSpec = tween(180)))
            },
            contentAlignment = Alignment.TopCenter,
            label = "islandTopBar",
        ) { isExpanded ->
            if (isExpanded) {
                // 展开态：原顶栏内容，无任何玻璃底（用户点名删除）。
                Box(modifier = Modifier.fillMaxWidth()) {
                    content()
                }
            } else {
                // 紧凑岛：黑色玻璃胶囊居中悬浮，点按展开。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 4.dp, bottom = 6.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Row(
                        modifier = Modifier
                            .width(216.dp)
                            .height(collapseFraction.coerceAtLeast(0.dp))
                            .xGlassBody(
                                backdrop = null,
                                shape = RoundedCornerShape(21.dp),
                                tint = Color.Black.copy(alpha = 0.52f),
                                rim = true,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { expanded = true }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        // 状态点：root 活性，呼吸明暗。
                        val breath = rememberInfiniteTransition(label = "islandBreath").animateFloat(
                            initialValue = 0.45f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(800, easing = FastOutSlowInEasing),
                                RepeatMode.Reverse,
                            ),
                            label = "islandBreathAlpha",
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(breath.value)
                                .background(
                                    if (statusActive) Color(0xFF66BB6A) else Color(0xFFFFB300),
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = islandTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.95f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!islandLive.isNullOrEmpty()) {
                                Text(
                                    text = islandLive,
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.66f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    text = " ",
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
