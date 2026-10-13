package me.weishu.kernelsu.mountstate

import com.topjohnwu.superuser.Shell
import me.weishu.kernelsu.ui.util.withNewRootShell

/**
 * 方案甲：5.10 以下挂载态设备的 root **就是** Magisk 的 root。
 *
 * 所以这里不另建一套策略库，而是直接读写 Magisk 自己那份 `/data/adb/magisk.db`，
 * 全部走 Magisk 官方 CLI（`magisk --sqlite` / `magisk --denylist`）。行为事实取自
 * topjohnwu/Magisk v30.7（GPL-3.0，见 `THIRD_PARTY_NOTICES.md`；只按接口事实写，
 * 没有搬运实现）：
 *
 * - `policies(uid INT, policy INT, until INT, logging INT, notification INT, PRIMARY KEY(uid))`
 * - `settings(key TEXT, value INT, PRIMARY KEY(key))`：`zygisk` / `denylist` / `multiuser_mode` …
 * - policy 取值：0 = 询问、1 = 拒绝、2 = 允许、3 = 受限（丢弃全部 capability）
 * - 一行生效的条件是 `until = 0 或 until > now`；因此新授权写 `until = 0`（永久），
 *   改已有行时保留还没过期的 `until`（Magisk 应用可以发限时授权，不能悄悄变成永久）
 * - `magisk --sqlite` 只服务 root（magiskd 对 `SQLITE_CMD` 要求 uid 0），输出一行一行的
 *   `列=值|列=值`；SQL 失败不改退出码，所以写完必须回读
 *
 * 效果是双向的：Magisk 应用和本管理器读写同一张表，一边改另一边立刻一致 ——
 * 本管理器的 root 授权也在里面（它同样是通过 `su` 申请 root 的普通 app）。
 *
 * 每条命令都用**新开的 root shell**：挂载态的 `su` 就是 Magisk 的 su，用户可能在 Magisk
 * 应用里随时授权或撤销，缓存一条 shell 会在"第一次请求被拒/超时"之后变成死壳，
 * 之后再授权也一直是"读不到"。一次刷新只开一条 shell（最多弹一次授权框），
 * 所以代价只是一次 `su` 进程。
 *
 * 只在挂载态被引用：内核态（5.10+ GKI / LKM / KPatch）设备一行都不会走到这里。
 */
object MagiskRoot {

    /** 与 Magisk 的 `SuPolicy` 一一对应。 */
    const val POLICY_QUERY = 0
    const val POLICY_DENY = 1
    const val POLICY_ALLOW = 2
    const val POLICY_RESTRICT = 3

    const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

    data class Policy(
        val uid: Int,
        val policy: Int,
        val until: Long,
        val logging: Boolean,
        val notification: Boolean,
    )

    /** 一次刷新的全部数据：一条 shell 读完，保证同一时刻的一致性。 */
    data class Snapshot(
        val version: String,
        val policies: List<Policy>,
        val denylistEnforced: Boolean,
        val denylistCount: Int,
        val zygiskEnabled: Boolean,
    )

    /** 设备上没有可用的 magisk CLI，或者 magiskd 拒绝了这次调用（例如还没授权 root）。 */
    class Unavailable(message: String) : Exception(message)

    private const val PROBE = """
for p in /data/adb/magisk/magisk /system/bin/magisk /debug_ramdisk/magisk; do
  [ -x "${'$'}p" ] && { printf '%s\n' "${'$'}p"; exit 0; }
done
command -v magisk 2>/dev/null || true
"""

    @Volatile
    private var cachedBinary: String? = null

    fun snapshot(): Snapshot = withNewRootShell { shell ->
        val version = version(shell)
        val policies = policies(shell)
        val enforced = runMagisk(shell, "--denylist status").isSuccess
        val denylist = runMagisk(shell, "--denylist ls").out
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('|') }
            .distinct()
        val zygisk = query(shell, "SELECT value FROM settings WHERE key='zygisk'")
            .firstOrNull()?.get("value") == "1"
        Snapshot(
            version = version,
            policies = policies,
            denylistEnforced = enforced,
            denylistCount = denylist.size,
            zygiskEnabled = zygisk,
        )
    }

    /**
     * 写一行策略。四个值都给全：Magisk 自己也是整行替换，缺列会被当成 0。
     */
    fun writePolicy(uid: Int, policy: Int, until: Long, logging: Boolean, notification: Boolean) {
        val log = if (logging) 1 else 0
        val notify = if (notification) 1 else 0
        exec(
            "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) " +
                "VALUES ($uid,$policy,$until,$log,$notify)"
        )
    }

    fun removePolicy(uid: Int) = exec("DELETE FROM policies WHERE uid=$uid")

    fun setDenylistEnforced(enabled: Boolean) {
        val result = withNewRootShell { shell ->
            runMagisk(shell, if (enabled) "--denylist enable" else "--denylist disable")
        }
        if (!result.isSuccess) throw Unavailable(message("magisk --denylist", result))
    }

    /** Zygisk 由 magiskd 在开机时装载，改完要重启才生效（Magisk 应用也是这么提示的）。 */
    fun setZygisk(enabled: Boolean) =
        exec("INSERT OR REPLACE INTO settings (key,value) VALUES ('zygisk',${if (enabled) 1 else 0})")

    // ---- 内部：全部在调用方给的那条 root shell 里跑 ----

    /** magisk CLI 的绝对路径；找不到就是这条路线还没装好。 */
    private fun binary(shell: Shell): String {
        cachedBinary?.let { return it }
        val result = run(shell, PROBE)
        val path = result.out.firstOrNull { it.trim().startsWith("/") }?.trim()
            ?: throw Unavailable(message("magisk CLI not found", result))
        cachedBinary = path
        return path
    }

    /** 正在跑的 magiskd 版本（`-v` 走守护进程，顺便证明 daemon 活着；失败退回二进制版本）。 */
    private fun version(shell: Shell): String {
        val running = runMagisk(shell, "-v")
        running.out.firstOrNull { it.isNotBlank() }?.let { return it.trim() }
        val local = runMagisk(shell, "-c")
        return local.out.firstOrNull { it.isNotBlank() }?.trim()
            ?: throw Unavailable(message("magisk -v", running))
    }

    private fun policies(shell: Shell): List<Policy> =
        query(shell, "SELECT uid, policy, until, logging, notification FROM policies")
            .mapNotNull { row ->
                val uid = row["uid"]?.toIntOrNull() ?: return@mapNotNull null
                Policy(
                    uid = uid,
                    policy = row["policy"]?.toIntOrNull() ?: POLICY_DENY,
                    until = row["until"]?.toLongOrNull() ?: 0L,
                    logging = row["logging"] == "1",
                    notification = row["notification"] == "1",
                )
            }

    private fun runMagisk(shell: Shell, args: String): Shell.Result = run(shell, "${binary(shell)} $args")

    private fun query(shell: Shell, sql: String): List<Map<String, String>> {
        val result = runMagisk(shell, "--sqlite ${quote(sql)}")
        if (!result.isSuccess) throw Unavailable(message("magisk --sqlite", result))
        return result.out.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val row = mutableMapOf<String, String>()
                trimmed.split('|').forEach { field ->
                    val eq = field.indexOf('=')
                    if (eq > 0) row[field.substring(0, eq)] = field.substring(eq + 1)
                }
                row.ifEmpty { null }
            }
        }
    }

    private fun exec(sql: String) {
        val result = withNewRootShell { shell -> runMagisk(shell, "--sqlite ${quote(sql)}") }
        if (!result.isSuccess) throw Unavailable(message("magisk --sqlite", result))
    }

    private fun run(shell: Shell, command: String): Shell.Result {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        return shell.newJob().add(command).to(stdout, stderr).exec()
    }

    /** 进 shell 的一切都要过这一层：SQL 里带单引号，包一层单引号后按 POSIX 规则转义。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun message(prefix: String, result: Shell.Result): String {
        val detail = (result.err + result.out)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (detail.isEmpty()) "$prefix: exit ${result.code}" else "$prefix: $detail"
    }
}
