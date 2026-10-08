package me.weishu.kernelsu.ui.viewmodel

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val enableNavigationBadge: Boolean,
    /**
     * 自定义背景文件名（空串 = 内置随机池），按当前深浅档取其中之一。
     *
     * 放在这里而不是让根层自己读设置：文件名是设置页写进去的，根层必须跟着它重组才会
     * 换图；`MainActivityViewModel` 已经在监听 settings 的 SharedPreferences，搭同一趟车
     * 就能做到"选完图立刻换，不用重启"。
     */
    val wallpaperLight: String,
    val wallpaperDark: String,
)
