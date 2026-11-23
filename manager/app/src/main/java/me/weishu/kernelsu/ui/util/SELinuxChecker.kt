package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.Shell

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Safe to call from any thread (IO recommended).
 */
fun getSELinuxStatusRaw(): String {
    return KsuCli.runCapture(globalMnt = false, timeoutMs = 5_000) {
        readSELinuxStatus(getRootShell())
    }.ifEmpty { "Unknown" }
}

internal fun readSELinuxStatus(shell: Shell): String {
    // The cached root shell belongs to KsuCli. Do not close it or fall back to
    // an app-UID probe when root is unavailable: that probe only adds a denial.
    if (!shell.isRoot) return "Unknown"

    val stdoutList = ArrayList<String>()
    val stderrList = ArrayList<String>()
    val result = shell.newJob().add("getenforce").to(stdoutList, stderrList).exec()
    val stdout = stdoutList.joinToString("\n").trim()
    if (!result.isSuccess) return "Unknown"

    return when (stdout) {
        "Enforcing", "Permissive", "Disabled" -> stdout
        else -> "Unknown"
    }
}
