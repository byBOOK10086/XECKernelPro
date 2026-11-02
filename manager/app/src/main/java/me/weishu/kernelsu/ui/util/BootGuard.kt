package me.weishu.kernelsu.ui.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Read-only daemon state; no direct access to boot protection files. */
data class BootGuardStatus(
    val enabled: Boolean,
    val rescue: Boolean,
    val failures: Int,
    val stage: String,
    val reason: String,
    val skipped: List<String>,
    val lastRecovery: String,
)

private fun bootGuardCommand(action: String): String = withNewRootShell {
    check(isRoot) { "Root access is required" }
    val path = getKsuDaemonPath().replace("'", "'\\''")
    val result = newJob().add("'$path' boot-guard $action")
        .to(ArrayList<String>(), ArrayList<String>()).exec()
    check(result.isSuccess) { "Boot protection command failed (${result.code})" }
    result.out.joinToString("\n")
}

suspend fun readBootGuardStatus(): BootGuardStatus = withContext(Dispatchers.IO) {
    val json = JSONObject(bootGuardCommand("status"))
    // Reject missing/malformed fields instead of presenting an unsafe default as healthy.
    val enabled = json.get("enabled")
    val rescue = json.get("rescue")
    val failures = json.get("failures")
    require(enabled is Boolean && rescue is Boolean && failures is Int && failures >= 0)
    val skipped = json.getJSONArray("skipped")
    BootGuardStatus(
        enabled = enabled,
        rescue = rescue,
        failures = failures,
        stage = json.get("stage").also { require(it is String) } as String,
        reason = json.get("reason").also { require(it is String) } as String,
        skipped = List(skipped.length()) { index ->
            skipped.get(index).also { require(it is String) } as String
        },
        lastRecovery = json.get("last_recovery").also { require(it is String) } as String,
    )
}

/** Clears rescue and failure state only; the user must reboot to retry skipped work. */
suspend fun resumeBootGuard() = withContext(Dispatchers.IO) {
    bootGuardCommand("resume")
    Unit
}
