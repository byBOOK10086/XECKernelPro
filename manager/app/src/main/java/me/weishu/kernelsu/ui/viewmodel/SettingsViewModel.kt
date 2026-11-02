package me.weishu.kernelsu.ui.viewmodel

import android.system.OsConstants
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.privilege.SuperkeyStore
import me.weishu.kernelsu.ui.screen.settings.SettingsUiState
import me.weishu.kernelsu.ui.theme.ColorMode

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 守卫武装后，SET_FEATURE 一律要 fd 级认证；没有这一步，开关只会拿到
     * -EACCES，界面还会乐观地把开关翻过去（用户看到的「错误: -13」）。
     */
    private fun ensureSuperkey(): Boolean {
        if (SuperkeyStore.ensureAuthed(ksuApp)) return true
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(ksuApp, R.string.superkey_auth_required, Toast.LENGTH_LONG).show()
        }
        return false
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val checkUpdate = repo.checkUpdate
            val checkModuleUpdate = repo.checkModuleUpdate
            val themeMode = repo.themeMode
            val miuixMonet = repo.miuixMonet
            val keyColor = repo.keyColor
            val enablePredictiveBack = repo.enablePredictiveBack
            val enableBlur = repo.enableBlur
            val enableFloatingBottomBar = repo.enableFloatingBottomBar
            val enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur
            val enableNavigationBadge = repo.enableNavigationBadge
            val pageScale = repo.pageScale
            val enableWebDebugging = repo.enableWebDebugging
            val wallpaperLight = repo.wallpaperLight
            val wallpaperDark = repo.wallpaperDark
            val wallpaperBlur = repo.wallpaperBlur
            val colorStyle = repo.colorStyle
            val colorSpec = repo.colorSpec
            val isLkmMode = repo.isLkmMode()

            // Async loading for natives/features
            val suCompatStatus = repo.getSuCompatStatus()
            val suCompatPersistValue = repo.getSuCompatPersistValue()
            val isSuEnabled = repo.isSuEnabled()

            val suCompatMode = if (suCompatPersistValue == 0L) 2 else if (!isSuEnabled) 1 else 0

            val kernelUmountStatus = repo.getKernelUmountStatus()
            val isKernelUmountEnabled = repo.isKernelUmountEnabled()
            val selinuxHideStatus = repo.getSelinuxHideStatus()
            val isSelinuxHideEnabled = repo.isSelinuxHideEnabled()
            val sulogStatus = repo.getSulogStatus()
            val isSulogEnabled = repo.getSulogPersistValue() == 1L
            val adbRootStatus = repo.getAdbRootStatus()
            val isAdbRootEnabled = repo.getAdbRootPersistValue() == 1L
            val isDefaultUmountModules = repo.isDefaultUmountModules()
            val autoJailbreak = repo.autoJailbreak
            val useSoftReboot = repo.useSoftReboot
            val isLateLoadMode = Natives.isLateLoadMode

            _uiState.update {
                it.copy(
                    checkUpdate = checkUpdate,
                    checkModuleUpdate = checkModuleUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    enableNavigationBadge = enableNavigationBadge,
                    pageScale = pageScale,
                    enableWebDebugging = enableWebDebugging,
                    wallpaperLight = wallpaperLight,
                    wallpaperDark = wallpaperDark,
                    wallpaperBlur = wallpaperBlur,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    suCompatStatus = suCompatStatus,
                    suCompatMode = suCompatMode,
                    isSuEnabled = isSuEnabled,
                    adbRootStatus = adbRootStatus,
                    isAdbRootEnabled = isAdbRootEnabled,
                    kernelUmountStatus = kernelUmountStatus,
                    isKernelUmountEnabled = isKernelUmountEnabled,
                    selinuxHideStatus = selinuxHideStatus,
                    isSelinuxHideEnabled = isSelinuxHideEnabled,
                    sulogStatus = sulogStatus,
                    isSulogEnabled = isSulogEnabled,
                    isDefaultUmountModules = isDefaultUmountModules,
                    isLkmMode = isLkmMode,
                    autoJailbreak = autoJailbreak,
                    useSoftReboot = useSoftReboot,
                    isLateLoadMode = isLateLoadMode,
                )
            }
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setCheckModuleUpdate(enabled: Boolean) {
        repo.checkModuleUpdate = enabled
        _uiState.update { it.copy(checkModuleUpdate = enabled) }
    }

    fun setThemeMode(mode: Int) {
        val effectiveMode = if (_uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setEnableNavigationBadge(enabled: Boolean) {
        repo.enableNavigationBadge = enabled
        _uiState.update { it.copy(enableNavigationBadge = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    fun setEnableWebDebugging(enabled: Boolean) {
        repo.enableWebDebugging = enabled
        _uiState.update { it.copy(enableWebDebugging = enabled) }
    }

    /**
     * 切到自定义背景（[name] 为 [WallpaperStore] 落盘后的文件名）。
     *
     * 传空串即"恢复内置随机池"。这里只改设置项，不动文件——文件的生命周期由
     * [WallpaperStore] 负责（导入成功才写设置，清空时先改设置再删文件，任何一步
     * 中断最坏结果都只是回落到内置池，不会出现"设置指向不存在的图"这种状态）。
     */
    fun setWallpaperLight(name: String) {
        repo.wallpaperLight = name
        _uiState.update { it.copy(wallpaperLight = name) }
    }

    fun setWallpaperDark(name: String) {
        repo.wallpaperDark = name
        _uiState.update { it.copy(wallpaperDark = name) }
    }

    /** 背景模糊强度（百分比 0..100）。拖动结束才落盘，拖动过程只改本地状态。 */
    fun setWallpaperBlur(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        repo.wallpaperBlur = clamped
        _uiState.update { it.copy(wallpaperBlur = clamped) }
    }

    private val featureLocks = listOf("su_compat", "kernel_umount", "selinux_hide", "sulog", "adb_root")
        .associateWith { Mutex() }

    private fun changeFeature(feature: String, action: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            featureLocks.getValue(feature).withLock {
                if (!ensureSuperkey()) return@withLock
                try {
                    action()
                    _uiState.update { it.copy(featureErrors = it.featureErrors - feature) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Read back actual state; never present the requested value as saved.
                    runCatching {
                        when (feature) {
                            "su_compat" -> {
                                val enabled = repo.isSuEnabled()
                                val saved = repo.getSuCompatPersistValue()
                                _uiState.update { it.copy(isSuEnabled = enabled,
                                    suCompatMode = if (saved == 0L) 2 else if (!enabled) 1 else 0) }
                            }
                            "kernel_umount" -> _uiState.update { it.copy(isKernelUmountEnabled = repo.isKernelUmountEnabled()) }
                            "selinux_hide" -> _uiState.update { it.copy(isSelinuxHideEnabled = repo.isSelinuxHideEnabled()) }
                            "sulog" -> {
                                val saved = repo.getSulogPersistValue()
                                _uiState.update { it.copy(isSulogEnabled = saved == 1L) }
                            }
                            "adb_root" -> {
                                val saved = repo.getAdbRootPersistValue()
                                _uiState.update { it.copy(isAdbRootEnabled = saved == 1L) }
                            }
                        }
                    }
                    val message = ksuApp.getString(R.string.settings_feature_change_failed, feature)
                    _uiState.update { it.copy(featureErrors = it.featureErrors + (feature to message)) }
                    withContext(Dispatchers.Main) { Toast.makeText(ksuApp, message, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun saveFeature(feature: String, value: Long) {
        check(repo.execKsudFeatureSave(feature, value)) { "Feature persistence failed" }
    }

    fun setSuCompatMode(mode: Int) {
        if (mode !in 0..2) return
        changeFeature("su_compat") {
            val persistent = if (mode == 2) 0L else 1L
            check(repo.setSuEnabled(mode != 2))
            saveFeature("su_compat", persistent)
            if (mode == 1) check(repo.setSuEnabled(false))
            repo.setSuCompatModePref(if (mode == 2) 2 else 0)
            _uiState.update { it.copy(suCompatMode = mode, isSuEnabled = mode == 0) }
        }
    }

    fun setKernelUmountEnabled(enabled: Boolean) = changeFeature("kernel_umount") {
        check(repo.setKernelUmountEnabled(enabled))
        saveFeature("kernel_umount", if (enabled) 1L else 0L)
        _uiState.update { it.copy(isKernelUmountEnabled = enabled) }
    }

    fun setSelinuxHideEnabled(enabled: Boolean) = changeFeature("selinux_hide") {
        val status = repo.setSelinuxHideEnabled(enabled)
        check(status == 0 || status == -OsConstants.EAGAIN) { "Kernel rejected feature: $status" }
        saveFeature("selinux_hide", if (enabled) 1L else 0L)
        _uiState.update { it.copy(isSelinuxHideEnabled = enabled) }
        if (status == -OsConstants.EAGAIN) {
            withContext(Dispatchers.Main) {
                Toast.makeText(ksuApp, R.string.settings_selinux_hide_reboot_required, Toast.LENGTH_LONG).show()
            }
        }
    }
    fun setAutoJailbreak(enabled: Boolean) {
        repo.autoJailbreak = enabled
        _uiState.update { it.copy(autoJailbreak = enabled) }
    }

    fun setUseSoftReboot(enabled: Boolean) {
        repo.useSoftReboot = enabled
        _uiState.update { it.copy(useSoftReboot = enabled) }
    }

    fun setSulogEnabled(enabled: Boolean) = changeFeature("sulog") {
        check(repo.setSulogEnabled(enabled))
        saveFeature("sulog", if (enabled) 1L else 0L)
        _uiState.update { it.copy(isSulogEnabled = enabled) }
    }

    fun setAdbRootEnabled(enabled: Boolean) = changeFeature("adb_root") {
        check(repo.setAdbRootEnabled(enabled))
        saveFeature("adb_root", if (enabled) 1L else 0L)
        _uiState.update { it.copy(isAdbRootEnabled = enabled) }
    }
    fun setDefaultUmountModules(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setDefaultUmountModules(enabled)) {
                _uiState.update { it.copy(isDefaultUmountModules = enabled) }
            }
        }
    }
}
