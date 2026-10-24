package me.weishu.kernelsu.privilege

import android.content.Context
import me.weishu.kernelsu.Natives

/**
 * Per-app uname 身份（ioctl 115/116）的管理器侧存储与内核同步。
 *
 * 语义（与 kernel/feature/uts_spoof.c 对齐）：
 *  - uname(2) 的 release/version 按 uid 改写，内核 uts 状态不动；
 *    空串 = 保留真实字段；条目只存内核 RAM，重启即清零。
 *  - 这里用 SharedPreferences 做持久层，保存时即时推送，开机后由
 *    [pushAll] 重放（幂等）。仅覆盖 64 位进程（dispatcher 跳过 compat）。
 */
object UtsSpoofStore {

    private const val PREFS = "uts_spoofs"
    private const val MAX_LEN = 64 // KSU_UTS_SPOOF_LEN - 1

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** release 与 version 都为空 = 移除该 uid 的条目。@return 内核是否接受。 */
    fun set(context: Context, uid: Int, release: String, version: String): Boolean {
        val rel = release.trim().take(MAX_LEN)
        val ver = version.trim().take(MAX_LEN)
        return if (rel.isEmpty() && ver.isEmpty()) {
            prefs(context).edit().remove(uid.toString()).apply()
            push(uid, "", "")
        } else {
            prefs(context).edit().putString(uid.toString(), "$rel\n$ver").apply()
            push(uid, rel, ver)
        }
    }

    /** @return [release, version]；无条目返回 ["", ""]。 */
    fun get(context: Context, uid: Int): Pair<String, String> {
        val raw = prefs(context).getString(uid.toString(), null) ?: return "" to ""
        val parts = raw.split('\n', limit = 2)
        return (parts.getOrNull(0) ?: "").trim().take(MAX_LEN) to
            (parts.getOrNull(1) ?: "").trim().take(MAX_LEN)
    }

    fun push(uid: Int, release: String, version: String): Boolean =
        runCatching {
            Natives.setUtsSpoof(uid, release.isNotEmpty() || version.isNotEmpty(), release, version)
        }.getOrDefault(false)

    /** 开机重放：把持久层的全部条目推回内核（幂等，内核表重启后为空）。 */
    fun pushAll(context: Context) {
        val all = prefs(context).all ?: return
        for ((key, value) in all) {
            val uid = key.toIntOrNull() ?: continue
            val raw = value as? String ?: continue
            val parts = raw.split('\n', limit = 2)
            push(uid, (parts.getOrNull(0) ?: "").trim(), (parts.getOrNull(1) ?: "").trim())
        }
    }
}
