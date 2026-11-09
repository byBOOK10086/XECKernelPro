package me.weishu.kernelsu.ui.design.panel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.design.motion.XcMotion
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
    // 轮盘上的「最近去过」：只记最后一个落点，不重排 —— 位置一旦会动，肌肉记忆就没了。
    val context = LocalContext.current
    val dockPrefs = remember {
        context.getSharedPreferences("xec_dock", Context.MODE_PRIVATE)
    }
    var lastPage by remember { mutableIntStateOf(dockPrefs.getInt("last_page", -1)) }
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
        // 展开时按钮自己也要有反馈：整体轻微放倒 + 图标旋转 45°，否则点下去只有
        // 弹层在动，指下的这个圆是死的。
        val open by animateFloatAsState(
            targetValue = if (consoleOpen) 1f else 0f,
            animationSpec = XcMotion.reveal(Xc.colors.isDark),
            label = "console-open",
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-13).dp)
                .size(56.dp)
                .graphicsLayer {
                    scaleX = 1f - 0.06f * open
                    scaleY = 1f - 0.06f * open
                }
                .clip(CircleShape)
                .background(accent)
                .clickable { consoleOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.graphicsLayer { rotationZ = 45f * open },
                contentAlignment = Alignment.Center,
            ) {
                ConsoleGlyph(tint = Color.White)
            }
        }

        // 超级用户 / 检测 / 设置：控制台改成**环绕弹层**（径向菜单），
        // 不是又一个竖排列表 —— 入口从按钮上方呈扇形展开，手指不用移动太远。
        //
        // 2026-11-09：三格 → 五格，全部主页面都上轮。三格时代"检测"和"模块"得
        // 先进底栏再加一步；现在五个页面都从圆心一次拖到位，底栏只保留最常用的
        // 两席（模块/设备）。这是和 KernelSU「底栏五格」在结构上的分岔：常驻入口
        // 只有两个，其余是**手势环**里的临时目标。
        if (consoleOpen) {
            Xc8RadialConsole(
                onDismiss = { consoleOpen = false },
                entries = listOf(
                    RadialEntry(Icons.Rounded.Cottage, "设备", 0, lastPage == 0),
                    RadialEntry(Icons.Rounded.Extension, "模块", 2, lastPage == 2),
                    RadialEntry(Icons.Rounded.Security, "超级用户", 1, lastPage == 1),
                    RadialEntry(Icons.Rounded.CheckCircleOutline, "检测", 3, lastPage == 3),
                    RadialEntry(Icons.Rounded.Settings, "设置", 4, lastPage == 4),
                ),
                onPick = { page ->
                    consoleOpen = false
                    lastPage = page
                    dockPrefs.edit().putInt("last_page", page).apply()
                    onSelectPage(page)
                },
            )
        }
    }
}

/** 径向菜单里的一格。 */
private data class RadialEntry(
    val icon: ImageVector,
    val label: String,
    val page: Int,
    val highlighted: Boolean,
)

/**
 * 控制台径向菜单（2026-11-09：加过渡动画 + 拖拽选择）。
 *
 * 动效分三层，都是在回答"东西从哪来"：
 *   1. 涟漪：一个细环从按钮圆心扩散到菜单半径，只在展开的那一下出现；
 *   2. 三格入口每隔 100ms 依次从圆心弹出（easeOutBack，带约 4% 过冲），
 *      缩放 0.42 -> 1、透明度 0 -> 1，标签比图标晚一点亮；
 *   3. 收起是 150ms 的反向收拢 —— 不是弹回原位，而是缩回圆心，于是"从哪来、
 *      回哪去"，和弹开共用同一条几何。
 *
 * 交互也一并换了：不再只是"点按钮 → 再点一项"。按住按钮往外拖，指到哪一格，
 * 哪一格放大、亮强调色，同时从圆心拉一条线连过去；松手选中，松在死区里取消。
 * 一次拖动就能选完，比两次点击少一次抬手。这是它和 KernelSU 那套「底栏五格」
 * 最直观的区别：入口是一条**手势轨迹**，不是固定格子。
 *
 * 用**全屏 Dialog** 而不是在底栏里画：底栏在 Scaffold 的 bottomBar 槽里，
 * 高度只有 68dp，超出边界的内容虽然能画出来，但**点击会落在父布局之外**收不到，
 * 于是"看得见点不中"。Dialog 有自己的一层窗口，绘制与命中的范围都是整屏。
 */
@Composable
private fun Xc8RadialConsole(
    onDismiss: () -> Unit,
    entries: List<RadialEntry>,
    onPick: (Int) -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var hover by remember { mutableIntStateOf(-1) }
    var closing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val surface = Xc.colors.surface
    val accent = Xc.colors.accent
    val muted = Xc.colors.textMuted
    val scrimBase = Xc.colors.text

    // 几何：以底栏中心为圆心，五格摊在 200°..340° 这条 140° 的扇面里
    // （全部落在按钮**上方**；180°/360° 会让最外侧两格和按钮同高，贴着底栏）。
    // 格数变化时步长自动算，将来加格子不用再改这里。
    val radiusDp = 126.dp
    val startDeg = 200.0
    val stepDeg = if (entries.size > 1) 140.0 / (entries.size - 1) else 0.0
    val deadZoneDp = 46.dp
    val reachDp = 196.dp
    val dark = Xc.colors.isDark

    LaunchedEffect(Unit) {
        reveal.animateTo(targetValue = 1f, animationSpec = XcMotion.reveal(dark))
    }

    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            reveal.animateTo(0f, XcMotion.collapse())
            then()
        }
    }

    Dialog(
        onDismissRequest = { close(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBase.copy(alpha = 0.30f * reveal.value))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { close(onDismiss) },
        ) {
            val pivotX = maxWidth / 2
            val pivotY = maxHeight - 96.dp
            val pivotPx = with(density) { Offset(pivotX.toPx(), pivotY.toPx()) }
            val radiusPx = with(density) { radiusDp.toPx() }
            val deadPx = with(density) { deadZoneDp.toPx() }
            val reachPx = with(density) { reachDp.toPx() }
            val piF = Math.PI.toFloat()
            val twoPiF = (2 * Math.PI).toFloat()
            val halfSectorF = Math.toRadians(38.0).toFloat()

            // 命中判定：按角度归到最近的一格；径向留死区，太远也不选。
            fun sectorAt(pos: Offset): Int {
                val dx = pos.x - pivotPx.x
                val dy = pos.y - pivotPx.y
                val dist = kotlin.math.hypot(dx, dy)
                if (dist < deadPx || dist > reachPx) return -1
                val ang = kotlin.math.atan2(dy, dx)
                var best = -1
                var bestDiff = Float.MAX_VALUE
                entries.forEachIndexed { index, _ ->
                    val target = Math.toRadians(startDeg + index * stepDeg).toFloat()
                    var diff = ang - target
                    while (diff > piF) diff -= twoPiF
                    while (diff < -piF) diff += twoPiF
                    val ad = kotlin.math.abs(diff)
                    if (ad < bestDiff) {
                        bestDiff = ad
                        best = index
                    }
                }
                return if (bestDiff <= halfSectorF) best else -1
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(entries.size) {
                        detectDragGestures(
                            onDragStart = { pos -> hover = sectorAt(pos) },
                            onDrag = { change, _ ->
                                change.consume()
                                hover = sectorAt(change.position)
                            },
                            onDragEnd = {
                                val picked = hover
                                hover = -1
                                if (picked in entries.indices) {
                                    close { onPick(entries[picked].page) }
                                } else {
                                    close(onDismiss)
                                }
                            },
                            onDragCancel = { hover = -1 },
                        )
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // ① 开场涟漪：进度收尾时环已经淡到看不见。
                    val r = reveal.value
                    if (r > 0.02f && r < 0.995f) {
                        drawCircle(
                            color = accent.copy(alpha = 0.42f * (1f - r)),
                            radius = radiusPx * 1.06f * r,
                            center = pivotPx,
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }
                    // ② 拖拽轨迹：圆心 -> 当前命中的那一格，末端点一个圆点。
                    if (hover in entries.indices) {
                        val angle = Math.toRadians(startDeg + hover * stepDeg)
                        val end = Offset(
                            pivotPx.x + (radiusPx * kotlin.math.cos(angle)).toFloat(),
                            pivotPx.y + (radiusPx * kotlin.math.sin(angle)).toFloat(),
                        )
                        drawLine(
                            color = accent.copy(alpha = 0.55f),
                            start = pivotPx,
                            end = end,
                            strokeWidth = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = accent.copy(alpha = 0.9f),
                            radius = 3.2.dp.toPx(),
                            center = end,
                        )
                    }
                }

                entries.forEachIndexed { index, entry ->
                    // 逐格错开 100ms 弹出：每格自己的 t 从 0 走到 1。
                    val start = index * 0.10f
                    val span = (1f - 0.10f * (entries.size - 1)).coerceAtLeast(0.01f)
                    val t = ((reveal.value - start) / span).coerceIn(0f, 1f)
                    val eased = easeOutBack(t)
                    val angle = Math.toRadians(startDeg + index * stepDeg)
                    val dx = (radiusDp.value * kotlin.math.cos(angle)).toFloat()
                    val dy = (radiusDp.value * kotlin.math.sin(angle)).toFloat()
                    val hovered = hover == index
                    val scale = (0.42f + 0.58f * eased) * if (hovered) 1.10f else 1f
                    Column(
                        modifier = Modifier
                            .offset(
                                x = pivotX + (dx * eased).dp - 34.dp,
                                y = pivotY + (dy * eased).dp - 34.dp,
                            )
                            .width(68.dp)
                            .graphicsLayer {
                                alpha = t
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable { close { onPick(entry.page) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (hovered) accent.copy(alpha = 0.18f) else surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hovered) {
                                Canvas(Modifier.size(56.dp)) {
                                    drawCircle(
                                        color = accent,
                                        radius = size.minDimension / 2f - 1.dp.toPx(),
                                        style = Stroke(width = 1.6.dp.toPx()),
                                    )
                                }
                            }
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = entry.label,
                                tint = if (entry.highlighted || hovered) accent else Xc.colors.text,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = entry.label,
                            fontSize = 10.sp,
                            color = if (hovered) accent else muted,
                            modifier = Modifier.graphicsLayer {
                                alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 轻微过冲的缓出：0 -> 1 途中越过 1 约 4%，手感上是"弹"一下而不是硬停。 */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.24f
    val c3 = c1 + 1f
    val p = t - 1f
    return 1f + c3 * p * p * p + c1 * p * p
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
