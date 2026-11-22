package me.weishu.kernelsu.ui.util

/** Probe evidence, not a guarantee that a KeyMint request has succeeded. */
internal object TeeEngineHealth {
    enum class Status { Ready, Broken, Unknown }

    const val injectionProbe =
        "sh /data/adb/ksu/builtin/tricky_store/injection-status.sh 2>/dev/null; " +
            "S=\$?; case \$S in 0) I=1;; 1) I=0;; *) I=unknown;; esac; "

    fun parseInjection(value: String?): Boolean? = when (value) {
        "1" -> true
        "0" -> false
        else -> null
    }

    fun assess(treePresent: Boolean?, keymintPid: Int?, injected: Boolean?): Status = when {
        treePresent == false || keymintPid == 0 || injected == false -> Status.Broken
        treePresent == true && keymintPid != null && keymintPid > 0 && injected == true -> Status.Ready
        else -> Status.Unknown
    }
}
