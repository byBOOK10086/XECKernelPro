package me.weishu.kernelsu.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProviderPolicyTest {
    @Test fun externalRootCanBootstrapAnInstallation() {
        val policy = RootProviderPolicy()
        policy.observeOwnDriver(false)
        assertTrue(policy.accepts(RootProvider.EXTERNAL))
        assertTrue(policy.accepts(RootProvider.OWN_XEC))
    }

    @Test fun lateDriverLoadingRetiresAnExternalShell() {
        val policy = RootProviderPolicy()
        assertTrue(policy.accepts(RootProvider.EXTERNAL))
        policy.observeOwnDriver(true)
        assertFalse(policy.accepts(RootProvider.EXTERNAL))
        assertTrue(policy.accepts(RootProvider.OWN_XEC))
        assertTrue(policy.accepts(RootProvider.NONE))
    }

    @Test fun transientProbeFailureDoesNotReenableTheOldManager() {
        val policy = RootProviderPolicy()
        policy.observeOwnDriver(true)
        repeat(8) { policy.observeOwnDriver(false) }
        assertFalse(policy.accepts(RootProvider.EXTERNAL))
        assertTrue(policy.accepts(RootProvider.OWN_XEC))
    }
}
