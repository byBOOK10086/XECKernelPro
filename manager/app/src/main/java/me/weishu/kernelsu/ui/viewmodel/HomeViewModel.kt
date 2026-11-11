package me.weishu.kernelsu.ui.viewmodel

import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.getKernelVersion
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
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.getSELinuxStatusRaw
import me.weishu.kernelsu.ui.util.KsuCli
import me.weishu.kernelsu.ui.util.module.LatestVersionInfo
import me.weishu.kernelsu.ui.util.resolveDeviceName
import me.weishu.kernelsu.ui.util.rootAvailable

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    // 2026-11-11 审计 P0：这里原来是 `MutableStateFlow(buildState())` —— 构造器跑在
    // **主线程**（HomeScreen.kt:40 用 viewModel() 创建），而 buildState() 里有 3 条
    // root shell `fastCmd` + `getSELinuxStatusRaw()`（每次都 new 一条 `sh`）+ 设备名解析。
    // 冷启动就等于在主线程上等 2 次进程创建和最多 5 次 root shell 往返。
    //
    // 现在首帧只做**纯内存**的部分（deep = false：不碰任何 shell），真正的探测交给
    // init 里的 refresh()，那条路本来就在 Dispatchers.IO 上。
    // 首帧会显示「正在获取 Root」而不是「获取 Root 失败」，这正是 rootProbeSettled
    // 那道闸门存在的意义（见 HomeUiState.kt:56 的注释）。
    private val _uiState = MutableStateFlow(buildState(deep = false))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 当前在跑的那一轮刷新；用于重入合并（见 [refresh]）。 */
    private var refreshJob: Job? = null

    init {
        // 首帧只是"占位"，真正的状态靠这一轮拿到（在 IO 线程上）。
        viewModelScope.launch { refresh() }
        // 开机早期被拉起时首帧算出的是"没有 root"。KsuCli 的后台补探晚一点拿到 root
        // 之后必须让首页重算一次，否则界面会一直停在无 root —— 用户看到的"重启后
        // root 掉了"有一半是这种界面滞留，而不是 root 真的丢。
        viewModelScope.launch {
            KsuCli.rootReady
                .filter { it }
                .collect { refresh() }
        }
        // 第一次探测落定（成功或失败）也要重算一次：首帧的 isRootAvailable 只是
        // "还没问到"，探测落定之后才允许出现「获取 Root 失败」这类告警。
        viewModelScope.launch {
            KsuCli.probeSettled
                .filter { it }
                .collect { refresh() }
        }
    }

    fun refresh() {
        // 2026-11-09：首页、工具箱、下拉刷新、顶栏刷新会同时打进来；以前每次调用都
        // 新开一个协程，几条探测（root shell / 更新检查）并发跑会互相覆盖状态流。
        // 这里做**重入合并**：已经有一轮在跑就忽略新请求 —— 一轮跑完状态一定比旧的新。
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update {
                // 审计 P2：baseState 是新建的 HomeUiState，isRefreshing 默认 false；
                // 整体覆盖会把这一轮刚置上的 true 打回 false（转圈提前收起、
                // 刷新按钮提前可点）。把当前值带过去。
                baseState.copy(isRefreshing = it.isRefreshing)
            }
            // 始终拉取一次版本信息，用于首页展示累计下载量；更新提示仍由 checkUpdateEnabled 控制
            val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
            _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            publishWidgetState(_uiState.value)
            } finally {
                // 失败也要收指示器：宁可让用户看到"没刷出来"，也不要一直转圈。
                _uiState.update { it.copy(isRefreshing = false) }
            }
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

    /**
     * 算一份完整状态。
     *
     * @param deep false = **只做纯内存计算**，一次 shell 都不起（首帧用）；
     *             true  = 完整探测（root 壳、SELinux、SUSFS、TEE），只能在 IO 线程调用。
     */
    private fun buildState(deep: Boolean = true): HomeUiState {
        val kernelVersion = getKernelVersion()
        val isManager = Natives.isManager
        val ksuVersion = if (isManager) Natives.version else null
        val kernelUAPIVersion = if (isManager) Natives.kernelUAPIVersion else null
        val managerUAPIVersion = Natives.managerUAPIVersion
        val lkmMode = ksuVersion?.let { if (kernelVersion.isGKI()) Natives.isLkmMode else null }
        val isRootAvailable = deep && rootAvailable()
        // 守护组件只对 root 可见（/data/adb 目录权限），app 进程直接 stat 永远是
        // false，必须走 root shell；未激活时跳过探测避免无谓的 shell 调用。
        val isDaemonPresent = deep && isRootAvailable && runCatching {
            val shell = getRootShell()
            ShellUtils.fastCmd(shell, "test -e /data/adb/xudc && echo -n ok").trim() == "ok"
        }.getOrDefault(false)
        // SUSFS 状态探测：susfs 的 reboot 向量只在 root 进程内有效，走守护 CLI。
        // "unsupport" = 内核没打 SUSFS 补丁；null = 探测不了（无 root / shell 失败）→ UI 隐藏该行。
        val susfsVersion: String? = if (deep && isRootAvailable) runCatching {
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
        val teeInjected: Boolean? = if (deep && isRootAvailable) runCatching {
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
            baseVersionCode = BuildConfig.BASE_VERSION_CODE.toLong(),
            systemInfo = SystemInfo(
                kernelVersion = Os.uname().release,
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",
                deviceModel = resolveDeviceName(),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = if (deep) getSELinuxStatusRaw() else "Unknown",
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
            rootProbeSettled = deep && KsuCli.probeSettled.value,
        )
    }
}
