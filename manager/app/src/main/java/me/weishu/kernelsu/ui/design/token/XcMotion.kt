package me.weishu.kernelsu.ui.design.token

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * XEC · 动效令牌（第三轮重建，2026-11-11）。
 *
 * ## 为什么重建
 *
 * 上一版这张表是**液态玻璃时代**的产物：`pressDuration = 320ms`、水滴扩散 620ms、
 * 页面切换用 `spring(0.85, 380)`。玻璃那一层已经下线，但时长分散的问题留了下来：
 * 现在应用里同时存在三套数值 —— 本文件的 320、[me.weishu.kernelsu.ui.design.panel.Xc8Motion]
 * 的 120/150/180/220、以及各页面自己写的 `tween(300)`。同一个"按一下"能差一倍，
 * 手感就是从这里开始散的。
 *
 * 这一版把表**按动作类型**重排，取值来自第三轮设计要求给的区间（取区间中点，
 * 不贴边界，留出可调余量）：
 *
 * | 动作 | 建议区间 | 取值 | 成员 |
 * |---|---|---|---|
 * | 按钮按压 | 120–180 | **140** | [press] |
 * | 开关变化 | 180–220 | **200** | [toggle] |
 * | 页面切换 | 220–300 | **260** | [page] |
 * | 状态变化 | 180–250 | **220** | [state] |
 * | Toast 出现 | 180–220 | **200** | [toast] |
 * | 列表入场 | 220–280 | **240** | [listIn] |
 * | 详情展开 | 250–320 | **280** | [expand] |
 *
 * 还有三个**周期性**的值（不是过渡，是持续动画）：
 * [breath] 呼吸 3000ms、[refreshTurn] 刷新转一圈 900ms、[waterDropDuration] 水波纹 620ms。
 *
 * ## 与旧 `Xc8Motion` 的关系
 *
 * `Xc8Motion` 不删。它被 `Xc8Switch` / `Xc8Segmented` / 徽标光带直接引用着，
 * 删掉要同步改十几处调用点，收益为零。新代码一律读 `Xc.motion.*`；
 * 老代码在后续页面精修时逐个迁过来。
 *
 * ## 两个缩放值不是一回事
 *
 * * [pressScale] = 0.965：**水波/触摸反馈**的缩放，幅度大一点才看得出"被按住"；
 * * [cardPressScale] = 0.98：**整张卡片**按压，幅度必须小 —— 0.98 是设计要求给的值，
 *   卡片面积大，再深一点就变成"整页在抖"。
 */
@Immutable
class XcMotion {

    // ── 过渡时长（ms）─────────────────────────────────────────────────

    /** 140ms：按压/松开。**最短的一档**，超过 180 就开始"黏手"。 */
    val press: Int = 140

    /** 200ms：开关轨道与滑块这类"有位移"的小控件。 */
    val toggle: Int = 200

    /** 260ms：页面之间的进入/退出。快于 220 生硬，慢于 300 拖。 */
    val page: Int = 260

    /**
     * 90ms：页面**退场**的时长，刻意远短于 [page]。
     *
     * 两边同长就是交叉淡入淡出 —— 中途两屏各 50% 不透明度叠在一起，用户看到的是
     * "上一个页面的残留"。把退场压到 1/3、并让入场**等退场结束再开始**（见
     * [xcPageEnter] 的 delay），两段在时间轴上完全不重叠，就没有重影了。
     *
     * 这是 Material 的 fade-through 语义：先走完，再进来。
     */
    val pageExit: Int = 90

    /** 220ms：同页内的状态变化（徽章变色、行内容替换、数据刷新）。 */
    val state: Int = 220

    /** 200ms：Toast 进入与退出。 */
    val toast: Int = 200

    /** 240ms：列表项入场。 */
    val listIn: Int = 240

    /** 280ms：面板/详情展开与收起。 */
    val expand: Int = 280

    // ── 循环周期（ms）─────────────────────────────────────────────────

    /** 3000ms：ROOT 就绪指示点的呼吸周期（设计要求 2.5–3.5s）。 */
    val breath: Int = 3000

    /** 900ms：刷新图标完整转一圈。 */
    val refreshTurn: Int = 900

    /** 620ms：水波扩散，沿用液态玻璃时代调好的手感，**不要动**。 */
    val waterDropDuration: Int = 620

    /** 水波扩散曲线：前段快、后段长尾，才像水面被点了一下。 */
    val waterDropEasing: Easing = CubicBezierEasing(0.16f, 0.84f, 0.30f, 1.0f)

    // ── 幅度 ─────────────────────────────────────────────────────────

    /** 0.965：触摸水波/图标的按压缩放。 */
    val pressScale: Float = 0.965f

    /** 0.98：整张卡片按压（设计要求取值，面积大所以幅度小）。 */
    val cardPressScale: Float = 0.98f

    /** 8dp：页面切换时内容区的纵向位移（设计要求 6–10dp）。 */
    val pageShiftDp: Int = 8

    /** 1.04：底栏图标选中时的轻微放大（设计要求"图标可有轻微缩放反馈"）。 */
    val navIconScale: Float = 1.04f

    // ── 规格构造函数 ──────────────────────────────────────────────────

    fun pressSpec(): FiniteAnimationSpec<Float> = tween(press, easing = XcEasing.standard)

    fun toggleSpec(): FiniteAnimationSpec<Float> = tween(toggle, easing = XcEasing.standard)

    fun stateSpec(): FiniteAnimationSpec<Float> = tween(state, easing = XcEasing.standard)

    /** 入场用 [XcEasing.decelerate]：冲进来然后停住。 */
    fun pageSpec(): FiniteAnimationSpec<Float> = tween(page, easing = XcEasing.decelerate)

    /** 退场用 [XcEasing.accelerate]：先慢后快，视觉上"让开"。 */
    fun pageExitSpec(): FiniteAnimationSpec<Float> = tween(page, easing = XcEasing.accelerate)

    fun toastSpec(): FiniteAnimationSpec<Float> = tween(toast, easing = XcEasing.decelerate)

    fun listInSpec(): FiniteAnimationSpec<Float> = tween(listIn, easing = XcEasing.decelerate)

    fun expandSpec(): FiniteAnimationSpec<Float> = tween(expand, easing = XcEasing.decelerate)

    fun waterDropSpec(): FiniteAnimationSpec<Float> =
        tween(durationMillis = waterDropDuration, easing = waterDropEasing)

    /**
     * 旧调用点兼容（`Xc8Motion` 时代的"页面切换弹簧"）。
     *
     * 新的页面过渡一律走 [pageSpec] —— 弹簧的过冲在页面级别会被读成"抖"，
     * 淡入位移才是这一版要的观感。保留它只是为了让还没迁过来的调用点编译得过。
     */
    fun <T> pageSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 380f)
}

val XcMotionDefault = XcMotion()

val LocalXcMotion = staticCompositionLocalOf { XcMotionDefault }

/**
 * 缓动曲线（Material 3 标准集）。
 *
 * 自己调一条 Bezier 出来"感觉更顺"的概率极低，代价却是不可预期；
 * 这套是被几亿台设备验证过的。
 */
object XcEasing {
    /** 进和出都能用的对称曲线。页面内状态变化用它。 */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** **只用于入场**：起点速度高、终点为 0。 */
    val decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** **只用于退场**：先慢后快。 */
    val accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 只给循环动画（呼吸、光带、旋转）。任何有起止的动作都不要用它。 */
    val linear: Easing = LinearEasing
}

/**
 * 系统"减少动态效果"的判定结果，由 [XcMotionProvider] 在本进程内**只算一次**。
 *
 * 刻意做成 `staticCompositionLocalOf`：这个值一旦变化（用户在系统设置里拨开关），
 * 本来就该整树刷新一次，而正常浏览时它永远不参与重组。
 */
val LocalXcReducedMotion = staticCompositionLocalOf { false }

/**
 * 系统是否要求减少动态效果。
 *
 * 两个判据**同时**成立才算"要动画"：系统的 `ANIMATOR_DURATION_SCALE` 不为 0，
 * 且 [ValueAnimator.areAnimatorsEnabled] 为真。只读前者会漏掉低电量模式里 ROM
 * 单方面把进程内动画关掉的机型。
 *
 * 注册 ContentObserver 是必须的：用户在系统设置里拨开/关掉动画时长时，本进程
 * 不用重启就能跟上 —— 这是唯一会被用户当场改的开关。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(!animationsEnabled(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = !animationsEnabled(context)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return reduced
}

private fun animationsEnabled(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
    return scale > 0f && ValueAnimator.areAnimatorsEnabled()
}

/**
 * 把令牌时长翻译成**这次真正该用的时长**：系统要求减少动态效果时归零。
 *
 * 归零而不是缩到 1ms：`tween(0)` 在 Compose 里等于"立刻到终值"，动画仍会提交
 * 一次状态，但用户看不到中间帧 —— 这正是"关闭非必要入场动画"的定义。
 * 需要保留的可感知反馈（开关位置变化、选中指示器移动）**不要**套这个函数。
 */
@Composable
fun xcDuration(millis: Int): Int = if (LocalXcReducedMotion.current) 0 else millis

/** [xcDuration] 的规格版本。 */
@Composable
fun <T> xcSpec(
    millis: Int,
    easing: Easing = XcEasing.standard,
    delayMillis: Int = 0,
): FiniteAnimationSpec<T> {
    val duration = xcDuration(millis)
    // 减少动态效果时连延迟一起归零：否则"先等 90ms 再瞬间到达"会变成一次无意义的空等。
    return tween(duration, delayMillis = if (duration == 0) 0 else delayMillis, easing = easing)
}

/**
 * 页面进入：**等退场跑完**（[XcMotion.pageExit]）再淡入 + 自下而上 8dp。
 *
 * 关键是那个 delay：它把"旧页退"和"新页进"排成前后两段，而不是同时进行。
 * 同时进行就是交叉淡入淡出，中途两屏各半透明地叠着 —— 那正是"上一个页面残留"
 * 的来源。位移方向只有一种（从下往上），量小到读不出"滑动"，只留下落位感。
 */
@Composable
fun xcPageEnter(): EnterTransition {
    val shift = with(LocalDensity.current) { XcMotionDefault.pageShiftDp.dp.roundToPx() }
    val enterMillis = (XcMotionDefault.page - XcMotionDefault.pageExit).coerceAtLeast(1)
    val fade = xcSpec<Float>(enterMillis, XcEasing.decelerate, delayMillis = XcMotionDefault.pageExit)
    // slideInVertically 的 spec 泛型是 IntOffset，不是 Float —— 两者不能共用一个 spec。
    val slide = xcSpec<IntOffset>(enterMillis, XcEasing.decelerate, delayMillis = XcMotionDefault.pageExit)
    return fadeIn(fade) + slideInVertically(slide) { shift }
}

/**
 * 页面退出：只做一次快速淡出，**不带走位**。
 *
 * 退场位移是上一版"残留感"的帮凶：旧页一边淡出一边往上飘，会让人以为它还在。
 * 现在它只是让开 —— 90ms 内消失，把整个舞台交给紧接着的入场。
 */
@Composable
fun xcPageExit(): ExitTransition {
    val spec = xcSpec<Float>(XcMotionDefault.pageExit, XcEasing.accelerate)
    return fadeOut(spec)
}
