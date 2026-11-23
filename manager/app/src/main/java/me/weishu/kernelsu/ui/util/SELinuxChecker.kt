package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.Shell

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Safe to call from any thread (IO recommended).
 */
fun getSELinuxStatusRaw(): String {
    // 优先走 root shell：app 身份 exec `getenforce` 会在 audit 里留下
    // comm="getenforce" + selinuxfs read denied（真机 dmesg 已抓到），等于把
    // “这个管理器在查 SELinux”写进日志。root shell 属于 xecpro 域，selinuxfs
    // 读取是设计内权限，不会再落一条 untrusted_app 的拒绝记录。
    val shell = runCatching { getRootShell() }.getOrNull()?.takeIf { it.isRoot }
        ?: Shell.Builder.create().build("sh")

    val stdoutList = ArrayList<String>()
    val stderrList = ArrayList<String>()
    val result = shell.use {
        it.newJob().add("getenforce").to(stdoutList, stderrList).exec()
    }
    val stdout = stdoutList.joinToString("\n").trim()
    val stderr = stderrList.joinToString("\n").trim()

    if (result.isSuccess) {
        return when (stdout) {
            "Enforcing", "Permissive", "Disabled" -> stdout
            else -> "Unknown"
        }
    }

    return if (stderr.endsWith("Permission denied")) {
        "Enforcing"
    } else {
        "Unknown"
    }
}
