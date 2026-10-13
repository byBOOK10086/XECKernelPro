package me.weishu.kernelsu.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.mountstate.MagiskRoot

/** 挂载态超级用户页的一行：Magisk 策略表里的一条记录 + 我们能查到的应用信息。 */
@Immutable
data class MagiskRootEntry(
    val uid: Int,
    val policy: Int,
    val until: Long,
    val logging: Boolean,
    val notification: Boolean,
    val packageName: String?,
    val label: String,
    val applicationInfo: ApplicationInfo?,
    val userId: Int,
    val isSelf: Boolean,
)

@Immutable
data class MagiskSuperUserUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val magiskVersion: String = "",
    val entries: List<MagiskRootEntry> = emptyList(),
    val zygiskEnabled: Boolean = false,
    val denylistEnforced: Boolean = false,
    val denylistCount: Int = 0,
    val error: String? = null,
) {
    /**
     * 能读到策略表就说明 magiskd 已经给了本管理器 root —— 那正是"Magisk 也管得到
     * 本管理器的 root"的证据：本管理器的授权就是策略表里 uid 为本应用的那一行。
     */
    val rootGranted: Boolean
        get() = error == null && hasLoaded
}

/**
 * 挂载态（5.10 以下，方案甲）的超级用户页。
 *
 * 数据源只有一处：Magisk 的 `/data/adb/magisk.db`（走官方 CLI，见 [MagiskRoot]）。
 * 不建影子表、不缓存授权 —— 所以这里的每一次改动 Magisk 应用立刻能看到，
 * 反过来 Magisk 应用里的改动（授权、撤销、通知、日志）回到本页也会立刻反映出来。
 */
class MagiskSuperUserViewModel : ViewModel() {

    companion object {
        private const val TAG = "MagiskSuperUserVM"
        private const val PER_USER_RANGE = 100000

        /** Magisk 的 app_id 区间：只有这个区间的 uid 才可能对应"已安装应用"。 */
        private const val AID_APP_START = 10000
        private const val AID_APP_END = 19999
    }

    private val _uiState = MutableStateFlow(MagiskSuperUserUiState())
    val uiState: StateFlow<MagiskSuperUserUiState> = _uiState.asStateFlow()

    private val refreshMutex = Mutex()

    fun load() {
        viewModelScope.launch { refresh() }
    }

    fun setPolicy(uid: Int, policy: Int) = updateEntry(uid) { it.copy(policy = policy) }

    fun setLogging(uid: Int, logging: Boolean) = updateEntry(uid) { it.copy(logging = logging) }

    fun setNotification(uid: Int, notification: Boolean) =
        updateEntry(uid) { it.copy(notification = notification) }

    fun revoke(uid: Int) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { MagiskRoot.removePolicy(uid) } }
                .onFailure { Log.w(TAG, "revoke $uid failed", it) }
            refresh()
        }
    }

    fun toggleZygisk() {
        val target = !_uiState.value.zygiskEnabled
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { MagiskRoot.setZygisk(target) } }
                .onFailure { Log.w(TAG, "set zygisk=$target failed", it) }
            refresh()
        }
    }

    fun toggleDenylist() {
        val target = !_uiState.value.denylistEnforced
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { MagiskRoot.setDenylistEnforced(target) } }
                .onFailure { Log.w(TAG, "set denylist=$target failed", it) }
            refresh()
        }
    }

    private fun updateEntry(uid: Int, transform: (MagiskRootEntry) -> MagiskRootEntry) {
        val current = _uiState.value.entries.firstOrNull { it.uid == uid } ?: return
        val next = transform(current)
        val nowSeconds = System.currentTimeMillis() / 1000
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Magisk 应用可以发限时授权（until 是 Unix 秒）。还没过期的原样带回，
                    // 免得在这里点一下"通知"就把 10 分钟授权变成永久。
                    val until = if (current.until > nowSeconds) current.until else 0L
                    MagiskRoot.writePolicy(uid, next.policy, until, next.logging, next.notification)
                }
            }.onFailure { Log.w(TAG, "write policy $uid failed", it) }
            refresh()
        }
    }

    private suspend fun refresh() {
        refreshMutex.withLock {
            _uiState.update { it.copy(isRefreshing = true) }
            runCatching { withContext(Dispatchers.IO) { read() } }
                .onSuccess { snapshot ->
                    _uiState.update { snapshot.copy(isRefreshing = false, hasLoaded = true) }
                }
                .onFailure { e ->
                    Log.w(TAG, "magisk policy table unavailable", e)
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            hasLoaded = true,
                            error = e.message ?: e.toString(),
                            magiskVersion = "",
                            entries = emptyList(),
                        )
                    }
                }
        }
    }

    private fun read(): MagiskSuperUserUiState {
        // 一次刷新只开一条 root shell（Magisk 的授权框最多弹一次），读到的也是同一时刻的一份。
        val snapshot = MagiskRoot.snapshot()
        val entries = snapshot.policies.map { describe(it) }.sortedWith(
            compareBy<MagiskRootEntry> { policyRank(it.policy) }.thenBy { it.label }
        )
        return MagiskSuperUserUiState(
            magiskVersion = snapshot.version,
            entries = entries,
            zygiskEnabled = snapshot.zygiskEnabled,
            denylistEnforced = snapshot.denylistEnforced,
            denylistCount = snapshot.denylistCount,
        )
    }

    private fun policyRank(policy: Int): Int = when (policy) {
        MagiskRoot.POLICY_ALLOW -> 0
        MagiskRoot.POLICY_DENY -> 1
        MagiskRoot.POLICY_QUERY -> 2
        else -> 3
    }

    private fun describe(policy: MagiskRoot.Policy): MagiskRootEntry {
        val pm = ksuApp.packageManager
        val uid = policy.uid
        val appId = uid % PER_USER_RANGE
        val userId = uid / PER_USER_RANGE
        // 多用户下 uid 带用户偏移，getPackagesForUid 只认主用户那一份，所以先试原 uid，
        // 再退回 app_id —— 与其显示成"未知"，不如显示成"这个包的另一个用户"。
        val packageName = runCatching { pm.getNameForUid(uid) }.getOrNull()
            ?: runCatching { pm.getPackagesForUid(appId)?.firstOrNull() }.getOrNull()
        val info = packageName?.let { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
        val label = info?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: packageName
            ?: "uid $uid"
        val isApp = appId in AID_APP_START..AID_APP_END
        return MagiskRootEntry(
            uid = uid,
            policy = policy.policy,
            until = policy.until,
            logging = policy.logging,
            notification = policy.notification,
            packageName = packageName,
            label = label,
            applicationInfo = info,
            userId = if (isApp) userId else 0,
            isSelf = uid == Process.myUid(),
        )
    }
}
