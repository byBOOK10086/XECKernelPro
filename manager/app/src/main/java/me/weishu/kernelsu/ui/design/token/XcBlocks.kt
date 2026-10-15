package me.weishu.kernelsu.ui.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 可单独设背景的区块 id 清单。
 *
 * 为什么单开一份清单、而不是让卡片自己写字符串：
 *  * 区块 id 是**跨版本契约**（写进 `theme.json` 的 `blocks` 键，还要参与包内资源命名），
 *    散在六个调用点上就没人能一眼看全，改一个忘一个；
 *  * 主题编辑器的"区块背景"一节要按同一份清单列行，清单在这里它就不可能和渲染侧脱节。
 *
 * 这里刻意**不**引 Android 的 `R`：令牌层至今没有任何 Android 资源依赖（`XcThemeData` /
 * `XcNeon` 也都没有），把资源 id 塞进来会让这一层从"纯设计令牌"变成"带资源的东西"。
 * 区块 id -> 显示名那条映射放在用它画界面的编辑器文件里（`ThemeEditorTab.kt`）。
 *
 * 命名规则：`应用区域.部件`。已经用于首页六个卡片，其它页面将来各自开自己的前缀。
 */
object XcBlocks {

    /** 首页 · 状态卡（`StatusCard`：在跑 / 挂载态 / 未安装三支共用这一块）。 */
    const val HOME_STATUS = "home.status"

    /** 首页 · 更新卡（`UpdateCard`）。 */
    const val HOME_UPDATE = "home.update"

    /** 首页 · 告警卡（所有 `WarningCard` 共用这一块）。 */
    const val HOME_WARNING = "home.warning"

    /** 首页 · 提权入口卡（`PrivilegeEntryCard`）。 */
    const val HOME_PRIVILEGE = "home.privilege"

    /** 首页 · 支持链接卡（`SupportLinks`）。 */
    const val HOME_SUPPORT = "home.support"

    /** 首页 · 设备信息卡（`InfoCard`）。 */
    const val HOME_INFO = "home.info"

    /** 首页六张卡片，按界面上的先后顺序。编辑器按这个顺序列行。 */
    val HOME_ALL: List<String> = listOf(
        HOME_UPDATE,
        HOME_WARNING,
        HOME_STATUS,
        HOME_PRIVILEGE,
        HOME_INFO,
        HOME_SUPPORT,
    )
}

/**
 * 一张卡片当前生效的背景。
 *
 * [imagePath] 是**已经落盘的绝对路径**（只对当前生效的主题、且文件确实存在时才有值）：
 * 令牌层不做 IO，文件在不在是解析主题时（`ThemeResolver`）就定下来的事。
 * 这里放路径而不是 `Bitmap`：这是可以被主题切换整树刷新的值类型，不该拖着一个图像对象。
 *
 * [color] 是 ARGB int 而不是 Compose 的 [Color]：这一层是数据，渲染层才转 [Color]。
 *
 * [mode] 就是格式里的 `when`（`both` / `dark` / `light`）。用字符串而不是枚举，是为了
 * 让"未知取值"能像格式的其它部分一样被安静地当成 `both`，而不是解析失败。
 */
@Immutable
data class XcBlockBackground(
    val color: Int? = null,
    val imagePath: String? = null,
    val opacity: Float = 1f,
    val dim: Float = 0f,
    val mode: String = "both",
) {
    /** 这条到底有没有东西可画（没东西就不该让渲染层白跑一趟）。 */
    val isEmpty: Boolean get() = color == null && imagePath == null

    /** 这一档要不要画：`when` 只在这一个地方判，渲染侧不必各自解释一遍。 */
    fun visibleIn(isDark: Boolean): Boolean = when (mode) {
        "dark" -> isDark
        "light" -> !isDark
        else -> true
    }
}

/** ARGB int -> Compose 的 [Color]；没设背景色就是 null（等于"这一层不画"）。 */
fun XcBlockBackground.colorOrNull(): Color? = color?.let { Color(it) }
