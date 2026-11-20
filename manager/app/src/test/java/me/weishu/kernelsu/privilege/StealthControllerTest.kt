package me.weishu.kernelsu.privilege

import org.junit.Assert.*
import org.junit.Test

class StealthControllerTest {
    private class Backend : StealthController.Backend {
        val components = linkedMapOf("main" to true, "alias" to true)
        val events = mutableListOf<String>()
        var ready = true
        var hidden = false
        var failDisable: String? = null
        var failRestore: String? = null
        override fun launchers() = components.keys.toList()
        override fun enableRecovery() { events += "recovery" }
        override fun setLauncher(name: String, enabled: Boolean) {
            events += "$name=$enabled"
            if ((!enabled && name == failDisable) || (enabled && name == failRestore)) error("PM rejected change")
            components[name] = enabled
        }
        override fun launcherEnabled(name: String) = components.getValue(name)
        override fun save(hidden: Boolean, launchers: List<String>) { this.hidden = hidden; events += "save=$hidden" }
        override fun startWatcher(): Boolean { events += "ready"; return ready }
        override fun stopWatcher() { events += "stop" }
    }

    @Test fun hideWaitsForRecoveryBeforeDisablingAnyLauncher() {
        val b = Backend()
        assertTrue(StealthController(b).hide())
        assertEquals(listOf("recovery", "ready", "save=true", "main=false", "alias=false"), b.events)
        assertTrue(b.hidden)
    }

    @Test fun failedReadinessLeavesEveryLauncherAccessible() {
        val b = Backend().apply { ready = false }
        assertFalse(StealthController(b).hide())
        assertFalse(b.events.any { it.endsWith("=false") && it != "save=false" })
        assertTrue(b.components.values.all { it })
        assertFalse(b.hidden)
    }

    @Test fun partialDisableRollsBackAllComponents() {
        val b = Backend().apply { failDisable = "alias" }
        assertFalse(StealthController(b).hide())
        assertTrue(b.components.values.all { it })
        assertFalse(b.hidden)
        assertEquals("stop", b.events.last())
    }

    @Test fun failedRollbackKeepsRecoveryWatcherAndSavedState() {
        val b = Backend().apply { failDisable = "alias"; failRestore = "main" }
        assertFalse(StealthController(b).hide())
        assertTrue(b.hidden)
        assertFalse("stop" in b.events)
        assertTrue("alias=true" in b.events)
    }

    @Test fun restoringDoesNotRequireRootAndTriesEveryComponent() {
        val b = Backend().apply { components.replaceAll { _, _ -> false }; failRestore = "main" }
        assertFalse(StealthController(b).restore())
        assertTrue(b.components.getValue("alias"))
        assertFalse("ready" in b.events)
        b.failRestore = null
        assertTrue(StealthController(b).restore())
        assertFalse(b.hidden)
    }

    @Test fun emptyLauncherDiscoveryNeverReportsSuccess() {
        val b = Backend().apply { components.clear() }
        assertFalse(StealthController(b).hide())
        assertFalse(StealthController(b).restore())
        assertFalse("ready" in b.events)
    }
}
