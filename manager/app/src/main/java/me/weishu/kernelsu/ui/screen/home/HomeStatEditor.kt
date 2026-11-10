package me.weishu.kernelsu.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 首页数据面板的编辑页（第三轮）。
 *
 * ## 为什么是全屏 Dialog 而不是底部弹层
 *
 * 候选 8 项 + 3 个槽位 + 两段标题，在小屏机上竖排会超过半屏。底部弹层只能做成
 * 「内部再套一个滚动列表」，滚动嵌套在 Compose 里是最容易出诡异手势问题的结构；
 * 而全屏页天然就是"一个 LazyColumn 到底"，且和「编辑模式」这个语义更贴 —— 用户
 * 清楚知道自己进了一个不同的界面，而不是首页上多了一层浮窗。
 *
 * 进入/退出都带位移 + 淡入淡出：设计要求「编辑模式进入和退出时采用柔和的过渡动画」，
 * 所以退出**不能**直接摘掉 Dialog —— 那样只剩窗口自己的消失。这里先把 `closing`
 * 置真、播完退场、再真正 dismiss。
 *
 * ## 交互模型
 *
 * 上：三个槽位。点一行 = 选中这个位置；左右箭头 = 与相邻槽位对调。
 * 下：候选清单。点一项 = 放进当前选中的槽位；已被其它槽位占用的项置灰不可点
 * （三个格子显示同一个数字没有意义，"点了没反应"比"悄悄换掉别的格子"好理解）。
 */
@Composable
fun HomeStatEditorDialog(onDismissRequest: () -> Unit) {
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(closing) {
        if (closing) {
            delay(200)
            onDismissRequest()
        }
    }
    Dialog(
        onDismissRequest = { closing = true },
        // usePlatformDefaultWidth=false 让它占满整屏；decorFitsSystemWindows=false
        // 让自己处理状态栏/导航栏，否则顶部会多出一条系统留白。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { opened = true }
        AnimatedVisibility(
            visible = opened && !closing,
            enter = fadeIn(tween(200)) + slideInVertically(tween(280)) { it / 6 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 8 },
            modifier = Modifier.fillMaxSize(),
        ) {
            EditorBody(onClose = { closing = true })
        }
    }
}

@Composable
private fun EditorBody(onClose: () -> Unit) {
    val slots by HomeStatStore.slots.collectAsState()
    var selectedSlot by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Xc.colors.backdrop)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_stat_edit_title),
                style = Xc.typography.title,
                color = Xc.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_stat_edit_done),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Xc.colors.accent,
                modifier = Modifier
                    .clip(Xc.shapes.xs)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 16.dp),
        ) {
            items(slots.size) { index ->
                SlotRow(
                    index = index,
                    item = slots[index],
                    selected = index == selectedSlot,
                    onSelect = { selectedSlot = index },
                    onMoveLeft = { HomeStatStore.move(index, -1) },
                    onMoveRight = { HomeStatStore.move(index, 1) },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.home_stat_edit_pick),
                    fontSize = 13.sp,
                    color = Xc.colors.textMuted,
                    modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
                )
            }

            items(HomeStatItem.ALL.size) { index ->
                val item = HomeStatItem.ALL[index]
                CandidateRow(
                    item = item,
                    current = slots.getOrNull(selectedSlot),
                    usedElsewhere = slots.withIndex().any {
                        it.index != selectedSlot && it.value == item
                    },
                    onClick = { HomeStatStore.assign(selectedSlot, item) },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.home_stat_edit_reset),
                    fontSize = 15.sp,
                    color = Xc.colors.textSecondary,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .clip(Xc.shapes.xs)
                        .clickable { HomeStatStore.reset() }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

/** 一个槽位：序号 + 当前项目 + 左右对调。选中行换成强调色底 + 描边。 */
@Composable
private fun SlotRow(
    index: Int,
    item: HomeStatItem,
    selected: Boolean,
    onSelect: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val shape = Xc.shapes.lg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(if (selected) Xc.colors.accentSoft else Xc.surface, shape)
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) Xc.colors.accent.copy(alpha = 0.55f) else Xc.colors.glassRim,
                shape = shape,
            )
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_stat_edit_slot, index + 1),
                fontSize = 12.sp,
                color = Xc.colors.textMuted,
            )
            Spacer(Modifier.height(3.dp))
            // 项目变化时淡入淡出，而不是硬换字。
            Crossfade(targetState = item, animationSpec = tween(180), label = "statSlot$index") { shown ->
                Text(
                    text = stringResource(shown.label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Xc.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MoveArrow(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.home_stat_edit_move_left),
            enabled = index > 0,
            onClick = onMoveLeft,
        )
        MoveArrow(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.home_stat_edit_move_right),
            enabled = index < HomeStatStore.SLOT_COUNT - 1,
            onClick = onMoveRight,
        )
    }
}

@Composable
private fun MoveArrow(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Xc.colors.textSecondary else Xc.colors.textMuted.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 候选行：当前选中的打勾；已被别的槽位占用的置灰并标「已占用」。 */
@Composable
private fun CandidateRow(
    item: HomeStatItem,
    current: HomeStatItem?,
    usedElsewhere: Boolean,
    onClick: () -> Unit,
) {
    val isCurrent = item == current
    val enabled = !usedElsewhere
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Xc.shapes.sm)
            .then(if (enabled && !isCurrent) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Xc.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(item.label),
            fontSize = 15.sp,
            color = when {
                isCurrent -> Xc.colors.accent
                usedElsewhere -> Xc.colors.textMuted.copy(alpha = 0.55f)
                else -> Xc.colors.text
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (usedElsewhere) {
            Text(
                text = stringResource(R.string.home_stat_edit_used),
                fontSize = 12.sp,
                color = Xc.colors.textMuted,
            )
        }
    }
}
