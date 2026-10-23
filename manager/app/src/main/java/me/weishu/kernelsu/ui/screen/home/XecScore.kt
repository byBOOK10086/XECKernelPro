package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Immutable

/**
 * XEC Score: a deterministic, local-only health score for the current device.
 *
 * It is intentionally built from facts the manager can already read on the Home
 * screen: no network, no detector APK, no shell string parsing beyond what
 * [HomeUiState] already carries. A missing/unknown fact simply earns no points;
 * the card lists every item so the number is explainable instead of magic.
 */
@Immutable
data class XecScoreItem(
    val id: XecScoreItemId,
    val earned: Int,
    val max: Int,
    val ok: Boolean,
)

enum class XecScoreItemId {
    ROOT,
    SELINUX,
    SUSFS,
    TEE,
    DAEMON,
    LKM,
    COMPAT,
    STABLE,
    MODE,
}

enum class XecRank(val min: Int) {
    S(90),
    A(75),
    B(60),
    C(40),
    D(0),
}

@Immutable
data class XecScoreResult(
    val total: Int,
    val items: List<XecScoreItem>,
) {
    init {
        require(total in 0..100)
    }

    val max: Int get() = items.sumOf { it.max }
    val passed: Int get() = items.count { it.ok }
    val rank: XecRank get() = XecRank.entries.first { total >= it.min }
    val isHealthy: Boolean get() = total >= XecRank.A.min

    val headline: String
        get() = when (rank) {
            XecRank.S -> "S"
            XecRank.A -> "A"
            XecRank.B -> "B"
            XecRank.C -> "C"
            XecRank.D -> "D"
        }
}

object XecScore {

    fun evaluate(state: HomeUiState): XecScoreResult {
        val rootOk = state.isRootAvailable && (state.ksuVersion != null || state.isMountState)
        val selinuxOk = state.systemInfo.selinuxStatus == "Enforcing"
        val susfsOk = state.susfsVersion != null && state.susfsVersion != "unsupport"
        val teeOk = state.teeInjected == true
        val daemonOk = state.isDaemonPresent
        val lkmOk = state.ksuVersion != null && state.lkmMode == true
        val compatOk = state.ksuVersion != null &&
            !state.requiresNewKernel &&
            !state.requiresNewManager
        val stableOk = !state.isManagerPrBuild && !state.isKernelPrBuild
        val modeOk = !state.isSafeMode && !state.isLateLoadMode

        val items = listOf(
            item(XecScoreItemId.ROOT, 20, rootOk),
            item(XecScoreItemId.SELINUX, 10, selinuxOk),
            item(XecScoreItemId.SUSFS, 15, susfsOk),
            item(XecScoreItemId.TEE, 15, teeOk),
            item(XecScoreItemId.DAEMON, 10, daemonOk),
            item(XecScoreItemId.LKM, 10, lkmOk),
            item(XecScoreItemId.COMPAT, 10, compatOk),
            item(XecScoreItemId.STABLE, 5, stableOk),
            item(XecScoreItemId.MODE, 5, modeOk),
        )
        return XecScoreResult(items.sumOf { it.earned }, items)
    }

    private fun item(id: XecScoreItemId, max: Int, ok: Boolean) =
        XecScoreItem(id = id, earned = if (ok) max else 0, max = max, ok = ok)
}
