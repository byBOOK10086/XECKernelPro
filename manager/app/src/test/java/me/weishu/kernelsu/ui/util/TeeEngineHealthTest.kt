package me.weishu.kernelsu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeeEngineHealthTest {
    @Test fun unavailableObservationNeverRequestsRecovery() {
        assertEquals(TeeEngineHealth.Status.Unknown, TeeEngineHealth.assess(true, 123, null))
        assertEquals(TeeEngineHealth.Status.Unknown, TeeEngineHealth.assess(null, null, null))
        assertEquals(TeeEngineHealth.Status.Unknown, TeeEngineHealth.assess(null, null, true))
    }

    @Test fun allEvidenceIsRequiredForReady() {
        assertEquals(TeeEngineHealth.Status.Ready, TeeEngineHealth.assess(true, 123, true))
        assertEquals(TeeEngineHealth.Status.Broken, TeeEngineHealth.assess(false, 123, true))
        assertEquals(TeeEngineHealth.Status.Broken, TeeEngineHealth.assess(true, 0, true))
        assertEquals(TeeEngineHealth.Status.Broken, TeeEngineHealth.assess(true, 123, false))
    }

    @Test fun malformedProbeIsUnknown() {
        listOf(null, "unknown", "", "2", "true").forEach {
            assertNull(TeeEngineHealth.parseInjection(it))
        }
        assertEquals(true, TeeEngineHealth.parseInjection("1"))
        assertEquals(false, TeeEngineHealth.parseInjection("0"))
    }
}
