package me.weishu.kernelsu.privilege

import android.content.Context
import me.weishu.kernelsu.Natives

/**
 * 受限授权（exec 白名单）的管理器侧存储与内核同步。
 *
 * 语义（与 kernel/policy/exec_policy.c 对齐）：
 *  - 受限 uid 只能通过 `su -c <command>` 拿 root，且命令首 token（basename
 *    匹配）必须在白名单里；GRANT_ROOT 与交互式 su 一律拒绝（fail closed）。
 *  - 白名单策略只存内核 RAM——重启即清零。这里用 SharedPreferences 做持久
 *    层，保存时即时推送，开机后由 [pushAll] 重放（内核表为空时推送幂等）。
 */
object ExecPolicyStore {

    private const val PREFS = "exec_policies"
    private const val MAX_ENTRIES = 8
    private const val MAX_PATH = 96

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** paths 为空 = 解除限制。 */
    fun set(context: Context, uid: Int, paths: List<String>) {
        val cleaned = paths.map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_ENTRIES)
        if (cleaned.isEmpty()) {
            prefs(context).edit().remove(uid.toString()).apply()
        } else {
            prefs(context).edit().putString(uid.toString(), cleaned.joinToString("\n")).apply()
        }
        push(uid, cleaned)
    }

    fun get(context: Context, uid: Int): List<String> =
        prefs(context).getString(uid.toString(), null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun push(uid: Int, paths: List<String>): Boolean =
        runCatching {
            Natives.setExecPolicy(uid, paths.isNotEmpty(), paths.toTypedArray())
        }.getOrDefault(false)

    /** 开机重放：把持久层的全部策略推回内核（幂等，内核表重启后为空）。 */
    fun pushAll(context: Context) {
        val all = prefs(context).all ?: return
        for ((key, value) in all) {
            val uid = key.toIntOrNull() ?: continue
            val paths = (value as? String)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            push(uid, paths)
        }
    }
}
