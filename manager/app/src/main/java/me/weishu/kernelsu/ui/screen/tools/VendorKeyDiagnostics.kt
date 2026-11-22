package me.weishu.kernelsu.ui.screen.tools

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell

/** Service visibility is evidence only; Android has no generic RPMB provisioning API. */
internal object VendorKeyDiagnostics {
    data class Report(val device: String, val services: Map<String, Boolean?>)

    fun parseService(value: String?): Boolean? = when (value) {
        "present" -> true
        "absent" -> false
        else -> null
    }

    suspend fun inspect(): Report = withContext(Dispatchers.IO) {
        val names = listOf("keymint", "soter", "rpmb")
        val device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
        val shell = awaitRootShell()
        val unknown = Report(device, names.associateWith { null })
        if (!shell.isRoot) return@withContext unknown
        val result = runCatching {
            shell.newJob().add(
                """
                B=${'$'}(timeout 8 service list 2>/dev/null); BR=${'$'}?
                H=${'$'}(timeout 8 lshal 2>/dev/null); HR=${'$'}?
                for name in keymint soter rpmb; do
                    pattern="${'$'}name"
                    [ "${'$'}name" = keymint ] && pattern='keymint|keymaster|keystore'
                    if printf '%s\n%s\n' "${'$'}B" "${'$'}H" | grep -qiE "${'$'}pattern"; then
                        echo "${'$'}name=present"
                    elif [ "${'$'}BR" = 0 ] && [ "${'$'}HR" = 0 ]; then
                        echo "${'$'}name=absent"
                    else
                        echo "${'$'}name=unknown"
                    fi
                done
                """.trimIndent(),
            ).to(ArrayList<String>(), ArrayList<String>()).exec()
        }.getOrNull() ?: return@withContext unknown
        if (!result.isSuccess) return@withContext unknown
        val values = result.out.mapNotNull { line ->
            val parts = line.trim().split('=', limit = 2)
            if (parts.size == 2 && parts[0] in names) parts[0] to parseService(parts[1]) else null
        }.toMap()
        Report(device, names.associateWith { values[it] })
    }
}
