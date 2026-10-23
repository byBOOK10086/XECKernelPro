package me.weishu.kernelsu.ui.viewmodel

import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.mountstate.MagiskRoot
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.screen.home.HomeUiState
import me.weishu.kernelsu.ui.screen.home.SystemInfo
import me.weishu.kernelsu.ui.screen.home.XecScore
import me.weishu.kernelsu.ui.screen.home.getManagerVersion
import me.weishu.kernelsu.widget.XecQuickTileService
import me.weishu.kernelsu.widget.XecScoreWidgetProvider
import me.weishu.kernelsu.widget.XecWidgetSnapshot
import me.weishu.kernelsu.widget.XecWidgetStore
import me.weishu.kernelsu.ui.util.checkNewVersion
import me.weishu.kernelsu.ui.util.getKsuDaemonPath
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import me.weishu.kernelsu.ui.util.resolveDeviceName
import me.weishu.kernelsu.ui.util.rootAvailable

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            // 挂载态（唯一判据：没有 KSU 驱动应答且内核低于 5.10）：root 就是 Magisk 自己的，
            // 首页要把正在跑的 magiskd 版本显示出来。snapshot() 会开一条 root shell，
            // 所以只在 IO 线程里读；这条闸门之外（内核态，含 <5.10 的 KPatch）一次都不会执行到它。
            val magiskVersion = if (baseState.isMountState) {
                withContext(Dispatchers.IO) {
                    runCatching { MagiskRoot.snapshot().version }.getOrNull()
                }
            } else {
                null
            }
            _uiState.update { baseState.copy(magiskVersion = magiskVersion) }
            // 始终拉取一次版本信息，用于首页展示累计下载量；更新提示仍由 checkUpdateEnabled 控制
            val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
            _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            publishWidgetState(_uiState.value)
        }
    }

    /**
     * The widget is a cold-start surface: when the process is dead it cannot
     * recompute root/SELinux/TEE, so Home writes the last known result here.
     * The one-tap action itself is token-authenticated elsewhere.
     */
    private suspend fun publishWidgetState(state: HomeUiState) {
        val score = XecScore.evaluate(state)
        val summary = ksuApp.getString(
            when {
                score.total >= 90 -> R.string.xec_widget_status_s
                score.total >= 75 -> R.string.xec_widget_status_a
                score.total >= 60 -> R.string.xec_widget_status_b
                score.total >= 40 -> R.string.xec_widget_status_c
                else -> R.string.xec_widget_status_d
            },
        )
        val detail = ksuApp.getString(
            R.string.xec_widget_detail,
            score.rank.name,
            score.passed,
            score.items.size,
        )
        withContext(Dispatchers.IO) {
            runCatching {
                XecWidgetStore.write(
                    ksuApp,
                    XecWidgetSnapshot(
                        score = score.total,
                        rank = score.rank.name,
                        summary = summary,
                        detail = detail,
                        active = score.isHealthy,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                XecScoreWidgetProvider.updateAll(ksuApp)
                XecQuickTileService.requestUpdate(ksuApp)
            }
        }
    }

    private fun buildState(): HomeUiState {
        val kernelVersion = getKernelVersion()
        val isManager = Natives.isManager
        val ksuVersion = if (isManager) Natives.version else null
        val kernelUAPIVersion = if (isManager) Natives.kernelUAPIVersion else null
        val managerUAPIVersion = Natives.managerUAPIVersion
        val lkmMode = ksuVersion?.let { if (kernelVersion.isGKI()) Natives.isLkmMode else null }
        val isRootAvailable = rootAvailable()
        // 守护组件只对 root 可见（/data/adb 目录权限），app 进程直接 stat 永远是
        // false，必须走 root shell；未激活时跳过探测避免无谓的 shell 调用。
        val isDaemonPresent = isRootAvailable && runCatching {
            val shell = getRootShell()
            ShellUtils.fastCmd(shell, "test -e /data/adb/xudc && echo -n ok").trim() == "ok"
        }.getOrDefault(false)
        // SUSFS 状态探测：susfs 的 reboot 向量只在 root 进程内有效，走守护 CLI。
        // "unsupport" = 内核没打 SUSFS 补丁；null = 探测不了（无 root / shell 失败）→ UI 隐藏该行。
        val susfsVersion: String? = if (isRootAvailable) runCatching {
            val shell = getRootShell()
            ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} susfs version").trim().ifEmpty { null }
        }.getOrNull() else null
        val susfsVariant: String? = if (susfsVersion != null && susfsVersion != "unsupport") runCatching {
            val shell = getRootShell()
            ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} susfs variant").trim().ifEmpty { null }
        }.getOrNull() else null
        // TEE 注入是"真的拦到"的唯一硬证据：keystore2 的 maps 里有引擎 .so。
        // 只看 pidof 会把"进程活着但注入失败"误判为健康；这里用一次只读 root 探测，
        // 结果同时供首页 XEC Score 使用。
        val teeInjected: Boolean? = if (isRootAvailable) runCatching {
            val shell = getRootShell()
            val pid = ShellUtils.fastCmd(shell, "pidof keystore2 2>/dev/null")
                .trim()
                .substringBefore(' ')
            if (pid.isNotEmpty() && pid.all(Char::isDigit)) {
                ShellUtils.fastCmd(
                    shell,
                    "grep -q libTEESimulator /proc/$pid/maps 2>/dev/null && echo -n yes",
                ).trim() == "yes"
            } else {
                false
            }
        }.getOrNull() else null
        val managerVersion = getManagerVersion(ksuApp)

        return HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isLkmBundled = lkmMode == true && Natives.isLkmBundled,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = Natives.isPrBuild,
            requiresNewKernel = isManager && Natives.managerUAPIVersion > Natives.kernelUAPIVersion,
            requiresNewManager = isManager && Natives.managerUAPIVersion < Natives.kernelUAPIVersion,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            isSafeMode = Natives.isSafeMode,
            isLateLoadMode = Natives.isLateLoadMode,
            isDaemonPresent = isDaemonPresent,
            susfsVersion = susfsVersion,
            susfsVariant = susfsVariant,
            teeInjected = teeInjected,
            checkUpdateEnabled = settingsRepo.checkUpdate,
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.versionCode,
            systemInfo = SystemInfo(
                kernelVersion = Os.uname().release,
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",
                deviceModel = resolveDeviceName(),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = getSELinuxStatusRaw(),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
        )
    }
}
