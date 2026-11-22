package me.weishu.kernelsu.ui.screen.tools

import android.os.Build
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell

internal object VendorKeyDiagnostics {
    private val serviceNames = mapOf(
        "KeyMint / Keymaster" to listOf("keymint", "keymaster"),
        "SOTER" to listOf("soter"),
        "RPMB" to listOf("rpmb"),
    )
    private val binderLine = Regex("""^\s*\d+\s+(\S+):\s+\[([\w.\$]*)]\s*$""")
    private val halName = Regex("""^[\w.]+@\d+\.\d+::\w+/[\w.-]+$""")

    enum class Visibility { LISTED, NOT_LISTED, UNKNOWN }

    data class Listing(val lines: List<String> = emptyList(), val complete: Boolean = false)
    data class Report(val device: String, val build: String, val services: Map<String, Visibility>)

    // Match registered names and interface descriptors, never free-form command output.
    fun observe(binder: Listing, hal: Listing): Map<String, Visibility> {
        val binderNames = binder.lines.flatMap { line ->
            binderLine.matchEntire(line)?.groupValues?.drop(1).orEmpty()
        }
        val halNames = hal.lines.flatMap { line ->
            line.trim().split(Regex("""\s+""")).filter(halName::matches)
        }
        val registered = binderNames + halNames
        return serviceNames.mapValues { (_, aliases) ->
            when {
                registered.any { name -> aliases.any { name.contains(it, ignoreCase = true) } } ->
                    Visibility.LISTED
                binder.complete && hal.complete -> Visibility.NOT_LISTED
                else -> Visibility.UNKNOWN
            }
        }
    }

    suspend fun inspect(): Report = withContext(Dispatchers.IO) {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
        val shell = try {
            awaitRootShell(timeoutMs = 2_500)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val binder = shell?.takeIf { it.isRoot }?.list("service list") ?: Listing()
        val hal = shell?.takeIf { it.isRoot }?.list("lshal") ?: Listing()
        Report(device, Build.DISPLAY, observe(binder, hal))
    }

    private fun Shell.list(command: String): Listing = try {
        val result = newJob().add("timeout 4 $command")
            .to(ArrayList<String>(), ArrayList<String>()).exec()
        Listing(result.out, result.isSuccess && result.err.isEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Listing()
    }
}
