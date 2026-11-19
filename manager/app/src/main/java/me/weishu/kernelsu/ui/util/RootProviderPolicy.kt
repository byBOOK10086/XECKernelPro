package me.weishu.kernelsu.ui.util

internal enum class RootProvider { OWN_XEC, EXTERNAL, NONE }

/** A transient driver/crowning failure must not switch an established XEC session to system su. */
internal class RootProviderPolicy {
    @Volatile
    private var ownObserved = false

    fun observeOwnDriver(available: Boolean) {
        if (available) ownObserved = true
    }

    fun accepts(provider: RootProvider): Boolean =
        provider != RootProvider.EXTERNAL || !ownObserved
}
