package me.weishu.kernelsu.ui.screen.tools

import me.weishu.kernelsu.ui.screen.tools.VendorKeyDiagnostics.Listing
import me.weishu.kernelsu.ui.screen.tools.VendorKeyDiagnostics.Visibility
import org.junit.Assert.assertEquals
import org.junit.Test

class VendorKeyDiagnosticsTest {
    @Test fun keystoreAndErrorTextDoNotCountAsHardwareServices() {
        val binder = Listing(listOf(
            "0  android.system.keystore2.IKeystoreService/default: [android.system.keystore2.IKeystoreService]",
            "Warning: soter lookup failed",
        ), complete = true)
        val observed = VendorKeyDiagnostics.observe(binder, Listing())
        assertEquals(Visibility.UNKNOWN, observed["KeyMint / Keymaster"])
        assertEquals(Visibility.UNKNOWN, observed["SOTER"])
    }

    @Test fun findsAidlAndHidlRegistrationsEvenWhenTheHalListIsPartial() {
        val binder = Listing(listOf(
            "12  android.hardware.security.keymint.IKeyMintDevice/default: [android.hardware.security.keymint.IKeyMintDevice]",
        ), complete = true)
        val hal = Listing(listOf("Y  vendor.oplus.hardware.soter@1.0::ISoter/default  741"))
        val observed = VendorKeyDiagnostics.observe(binder, hal)
        assertEquals(Visibility.LISTED, observed["KeyMint / Keymaster"])
        assertEquals(Visibility.LISTED, observed["SOTER"])
        assertEquals(Visibility.UNKNOWN, observed["RPMB"])
    }

    @Test fun completeListsWithoutMatchesOnlyReportNotListed() {
        val listing = Listing(complete = true)
        val observed = VendorKeyDiagnostics.observe(listing, listing)
        assertEquals(setOf(Visibility.NOT_LISTED), observed.values.toSet())
    }
}
