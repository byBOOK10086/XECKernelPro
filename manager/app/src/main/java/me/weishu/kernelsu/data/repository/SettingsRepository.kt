package me.weishu.kernelsu.data.repository

interface SettingsRepository {
    var checkUpdate: Boolean
    var checkModuleUpdate: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var enableNavigationBadge: Boolean
    var navigationRailExpanded: Boolean
    var pageScale: Float

    /**
     * 自定义背景的文件名（空串 = 使用内置随机池）。
     *
     * 存的是 `filesDir/wallpapers/` 下的文件名而不是布尔开关：文件才是唯一事实来源，
     * 名字对不上（比如被清理、被换过）时读出来就是不存在的文件，调用侧自然回落内置池，
     * 不需要额外的一致性检查。
     */
    var wallpaperLight: String
    var wallpaperDark: String

    /**
     * 背景模糊强度，百分比 0..100（0 = 关）。
     *
     * 只作用于**根层壁纸**：模糊后的壁纸同时是液态玻璃的采样源，观感接近 iOS 桌面壁纸
     * 被模糊后透出图标层。与 [enableBlur]（玻璃本身的模糊开关）互相独立——用户可能想要
     * 模糊的壁纸 + 不透明的卡片，也可能反过来。
     */
    var wallpaperBlur: Int
    var enableWebDebugging: Boolean
    var moduleSortEnabledFirst: Boolean
    var moduleSortActionFirst: Boolean
    var moduleRepoSortOrder: Int
    var superuserShowSystemApps: Boolean
    var superuserShowOnlyPrimaryUserApps: Boolean
    var superuserSortOption: Int
    var suLogFilters: Set<String>?
    var autoJailbreak: Boolean
    var useSoftReboot: Boolean
    val intentToken: String

    suspend fun getSuCompatStatus(): String
    suspend fun getSuCompatPersistValue(): Long?
    fun isSuEnabled(): Boolean
    fun setSuEnabled(enabled: Boolean): Boolean
    fun setSuCompatModePref(mode: Int)
    fun getSuCompatModePref(): Int

    suspend fun getKernelUmountStatus(): String
    fun isKernelUmountEnabled(): Boolean
    fun setKernelUmountEnabled(enabled: Boolean): Boolean

    suspend fun getSelinuxHideStatus(): String
    fun isSelinuxHideEnabled(): Boolean
    fun setSelinuxHideEnabled(enabled: Boolean): Int

    suspend fun getSulogStatus(): String
    suspend fun getSulogPersistValue(): Long?
    fun setSulogEnabled(enabled: Boolean): Boolean

    suspend fun getAdbRootStatus(): String
    suspend fun getAdbRootPersistValue(): Long?
    fun setAdbRootEnabled(enabled: Boolean): Boolean

    fun isDefaultUmountModules(): Boolean
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    fun isLkmMode(): Boolean

    fun execKsudFeatureSave()
}
