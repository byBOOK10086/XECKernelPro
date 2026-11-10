package me.weishu.kernelsu.ui.design.token

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「减量 XEC」设计语言 —— 唯一真源。
 *
 * 来源是 2026-11-11 那份参考稿（`xec_reduced_material_ui.html`）：奶白纸面、鼠尾草绿、
 * 1px 发丝线、17px 卡角、8 档字号。这里只放**原始取值**，语义映射在 [XcColors] 里做——
 * 页面永远读 `Xc.colors.*`，不直接读这里的色板，这样主题商店仍然能整套覆盖。
 *
 * 为什么单独一个文件：以前颜色散在 166 处 `Color(0x…)`、字号 28 种、dp 74 种；
 * 参考稿只有 3 个色相 + 8 档字号 + 4 档圆角。把这些数写死在一处，
 * 评审时只需要看这一个文件。
 */
object XcPaletteReduced {
    // ---------------------------------------------------------------- 浅色（奶白纸面）
    val bg = Color(0xFFF7F8F6)
    val paper = Color(0xFFFFFFFF)
    val paperMuted = Color(0xFFF2F5F1)
    val ink = Color(0xFF29332E)
    val inkSecondary = Color(0xFF4E5A52)
    val muted = Color(0xFF87928B)
    val line = Color(0xFFE9EDE9)

    /** 主色三档：图标底 / 选中态 / 强调文字。 */
    val green = Color(0xFF668674)
    val greenSoft = Color(0xFFEAF1EB)
    val greenDeep = Color(0xFF466452)

    /** 辅助色一：待处理、授权请求。 */
    val rose = Color(0xFFB18F84)
    val roseSoft = Color(0xFFF5EEEB)

    /** 辅助色二：提醒、到期。 */
    val amber = Color(0xFFA98758)
    val amberSoft = Color(0xFFF5F0E7)

    /** 语义色（参考稿没画错误态，这里按同一色温压暗，保证 4.5:1 以上）。 */
    val danger = Color(0xFFC0473C)
    val success = Color(0xFF3F7F5F)

    // ---------------------------------------------------------------- 深色（墨绿黑）
    val dBg = Color(0xFF111512)
    val dPaper = Color(0xFF191E1B)
    val dPaperMuted = Color(0xFF202622)
    val dInk = Color(0xFFECEFEA)
    val dInkSecondary = Color(0xFFB9C2BB)
    val dMuted = Color(0xFF8B958D)
    val dLine = Color(0xFF2E3630)
    val dGreen = Color(0xFF8FAE9B)
    val dGreenSoft = Color(0xFF24302A)
    val dGreenDeep = Color(0xFFB7CFC0)
    val dDanger = Color(0xFFE58079)
    val dAmber = Color(0xFFD6A46B)
    val dSuccess = Color(0xFF6FC49A)
    val dRose = Color(0xFFCBA79B)
    val dRoseSoft = Color(0xFF2C2422)
    val dAmberSoft = Color(0xFF2A2419)
}

/** 间距：只允许这几档。参考稿的 20 / 23 / 11 / 62 原样保留。 */
object XcSpace {
    /** 页面左右内边距。 */
    val pageHorizontal = 20.dp

    /** 分区间距。 */
    val section = 23.dp

    /** 分区标题到内容的距离。 */
    val sectionHead = 11.dp

    /** 列表行最小高度（参考稿 62）。 */
    val row = 62.dp

    /** 卡内边距。 */
    val card = 14.dp

    /** 密集档：组件内部小间距。 */
    val tight = 8.dp

    /** 宽松档：页面顶部/底部。 */
    val loose = 28.dp

    /** 图标方块的边长（参考稿 34）。 */
    val iconBox = 34.dp
}

// 圆角不在这里另起一套：数值圆角统一挂在 XcShape.kt 的 XcRadius 上，
// 参考稿需要的 17 / 14 / 11 / 13 四档已补进那个对象。

/**
 * 字号：与 [XcTypography] 的 9 个角色一一对应。
 *
 * 保留这个对象只是为了让布局代码（行高、图标尺寸、间距）能直接拿到数值，
 * **真正决定文字长相的是 [XcTypography]**；两边的数字必须一致，改一处要改两处。
 */
object XcType {
    /** 26sp · 页面名称。 */
    val h1 = 26.sp

    /** 28sp · 主标题。 */
    val hero = 28.sp

    /** 24sp · 数据数字。 */
    val stat = 24.sp

    /** 32sp · 主数值（评分）。 */
    val statHero = 32.sp

    /** 15sp · 分组标题。 */
    val section = 15.sp

    /** 15sp · 列表主文字。 */
    val body = 15.sp

    /** 13sp · 辅助说明。 */
    val sub = 13.sp

    /** 12sp · 状态标签。 */
    val caption = 12.sp

    /** 11sp · 底栏标签。 */
    val nav = 11.sp
}

/** 字重：参考稿只有 650 / 620 / 600 三档。 */
object XcWeight {
    val title = FontWeight(650)
    val body = FontWeight(620)
    val label = FontWeight(600)
}

/** 动效：三处，全部 200ms 量级，不用弹簧。 */
object XcDuration {
    const val page = 180
    const val control = 200
    const val toast = 200
}
