package me.weishu.kernelsu.ui.screen.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorKeyDiagnosticsTest {
    @Test fun serviceObservationDoesNotDefaultToMissing() {
        listOf(null, "", "unknown", "permission denied", "timeout").forEach {
            assertNull(VendorKeyDiagnostics.parseService(it))
        }
        assertEquals(true, VendorKeyDiagnostics.parseService("present"))
        assertEquals(false, VendorKeyDiagnostics.parseService("absent"))
    }
}
