package me.weishu.kernelsu.privilege

/** Keeps recovery available throughout a component-state transaction. */
internal class StealthController(private val backend: Backend) {
    interface Backend {
        fun launchers(): List<String>
        fun enableRecovery()
        fun setLauncher(name: String, enabled: Boolean)
        fun launcherEnabled(name: String): Boolean
        fun save(hidden: Boolean, launchers: List<String>)
        fun startWatcher(): Boolean
        fun stopWatcher()
    }

    fun hide(): Boolean = try {
        backend.enableRecovery()
        val launchers = backend.launchers()
        check(launchers.isNotEmpty()) { "No launcher component" }
        check(backend.startWatcher()) { "Touch recovery is unavailable" }
        backend.save(true, launchers)
        launchers.forEach { backend.setLauncher(it, false) }
        check(launchers.none(backend::launcherEnabled))
        true
    } catch (_: Exception) {
        if (restore()) runCatching { backend.stopWatcher() }
        false
    }

    fun restore(): Boolean {
        val launchers = runCatching { backend.launchers() }.getOrElse { return false }
        if (launchers.isEmpty()) return false
        var restored = runCatching { backend.enableRecovery() }.isSuccess
        launchers.forEach { name ->
            if (runCatching {
                    backend.setLauncher(name, true)
                    check(backend.launcherEnabled(name))
                }.isFailure
            ) restored = false
        }
        if (!restored) return false
        return runCatching { backend.save(false, launchers) }.isSuccess
    }
}
