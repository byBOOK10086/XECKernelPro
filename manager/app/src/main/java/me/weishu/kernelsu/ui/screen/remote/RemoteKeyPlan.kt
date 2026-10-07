package me.weishu.kernelsu.ui.screen.remote

import android.content.Context
import androidx.compose.runtime.Immutable
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ui.util.getRootShell
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * 远程模块助手 · 音量键脚本。
 *
 * ## 为什么需要它
 *
 * 分发模块时有一类模块的安装脚本会**等用户按音量键**（`音量上=是 / 音量下=否`
 * 那一套），把选择权交给安装者。远程接收端是无人值守的：没有人按，脚本就一直
 * 等，模块要么装不上、要么按默认分支装错。
 *
 * 用户的要求是「不改模块源码」——这一条直接决定了实现方式：**不去改 install.sh，
 * 也不去 hook 脚本，而是在接收端伪造"真的有人按了音量键"这件事**。方法是以 root
 * 身份往音量键所在的那个 input 设备节点写原始事件（`sendevent`），这正是
 * `getevent` / Magisk `--keycheck` / 直接读 `/dev/input/event*` 的脚本看到的
 * 同一份数据——对模块来说，它和一次真实按键无法区分。
 *
 * ## 脚本语法
 *
 * 逗号（或空白、换行）分隔的一串步骤，每步：
 *
 * ```
 * up@1500            装到 1500ms 时按一下音量上
 * down@2500          装到 2500ms 时按一下音量下
 * up@1500,up@3000    用户举的例子：先按一下音量上，延迟后再按一下音量上
 * up~600             检测到安装脚本的"请按音量键"提示后 600ms 再按
 * up@1500x120        按住 120ms（默认 90ms）
 * power@9000         电源键（少数脚本用它确认）
 * ```
 *
 * 键名别名：`up` / `volup` / `音量上`，`down` / `voldown` / `音量下`，
 * `power` / `电源` / `ok` / `确认`。
 *
 * `@` 是"从开始刷入算起的绝对时间"，`~` 是"从脚本提示出现算起"——后者更稳，
 * 但需要安装脚本把提示打到 stdout/stderr（`ui_print` 会，静默 `sleep` 不会），
 * 所以两种都保留，由发送端决定。
 */
@Immutable
data class RemoteKeyStep(
    val key: RemoteKey,
    val delayMs: Int,
    /** true = 以"安装脚本的按键提示"为锚点（`~`），false = 以刷入开始为锚点（`@`）。 */
    val afterPrompt: Boolean = false,
    /** 按住时长（ms）。真实按键有几十毫秒的按住时间，太短有的脚本采不到。 */
    val holdMs: Int = DEFAULT_HOLD_MS,
) {
    companion object {
        const val DEFAULT_HOLD_MS = 90
    }
}

enum class RemoteKey(val keyCode: Int, val androidKeyCode: Int, val label: String) {
    UP(115, 24, "音量上"),
    DOWN(114, 25, "音量下"),
    POWER(116, 26, "电源键"),
}

/** 一套按键脚本。空脚本 = 这个模块不需要按键（绝大多数模块都是这一档）。 */
@Immutable
data class RemoteKeyPlan(val steps: List<RemoteKeyStep>) {

    val isNotEmpty: Boolean get() = steps.isNotEmpty()

    /** 回写成语法文本，用于存进链接片段、预置记忆与 UI 回显。 */
    fun format(): String = steps.joinToString(",") { step ->
        val anchor = if (step.afterPrompt) "~" else "@"
        val hold = if (step.holdMs == RemoteKeyStep.DEFAULT_HOLD_MS) "" else "x${step.holdMs}"
        "${step.key.name.lowercase(Locale.US)}$anchor${step.delayMs}$hold"
    }

    override fun toString(): String = format()

    companion object {
        val EMPTY = RemoteKeyPlan(emptyList())

        /**
         * 常见档位（UI 上的 chip）。标题直接用脚本本身 + 方向符号：
         * 一是语言无关（这个应用有四十多种语言资源，不值得为四个预置各加一遍翻译），
         * 二是顺手就把语法教了。
         */
        val PRESETS: List<Pair<String, String>> = listOf(
            "↑ up@1500" to "up@1500",
            "↑↑ up@1500,up@3000" to "up@1500,up@3000",
            "↓ down@1500" to "down@1500",
            "↑↓↑ up@1500,down@2500,up@3500" to "up@1500,down@2500,up@3500",
        )

        private const val MAX_STEPS = 16
        private const val MAX_DELAY_MS = 600_000
        private const val MAX_HOLD_MS = 5_000

        /**
         * 解析脚本文本。空文本解析成 [EMPTY]（合法：这个模块不需要按键）；
         * 语法错误返回 failure，错误信息是给用户看的人话。
         *
         * **书写顺序即执行顺序**，不重排。`@` 与 `~` 混写时按你写的先后依次执行：
         * `@` 等到"刷入开始 + 延迟"，`~` 等到"提示出现 + 延迟"（提示已经过了就立即执行）。
         * 这样发送端脑子里想的顺序就是实际顺序，不会出现"我写在前面的却后按"。
         */
        fun parse(text: String): Result<RemoteKeyPlan> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Result.success(EMPTY)

            val steps = mutableListOf<RemoteKeyStep>()
            val parts = trimmed.split(',', '\n', '\r', ' ', '\t', '，', '；', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size > MAX_STEPS) {
                return Result.failure(IllegalArgumentException("最多 $MAX_STEPS 步"))
            }
            for (part in parts) {
                val step = parseStep(part) ?: return Result.failure(
                    IllegalArgumentException("看不懂这一步：$part")
                )
                steps.add(step)
            }
            return Result.success(RemoteKeyPlan(steps))
        }

        /** 只写键名时的默认延迟：安装脚本解包/探测基本已经过了这一段。 */
        private const val BARE_KEY_DELAY_MS = 1500

        private fun parseStep(part: String): RemoteKeyStep? {
            val anchorIndex = part.indexOfFirst { it == '@' || it == '~' }
            // 只写 `up` 这种：按"装到 1500ms"处理，而不是报错——手写脚本时省一层
            // 心智负担，也让 UI 的预置 chip 可以简单地只放键名。
            if (anchorIndex < 0) {
                val key = parseKey(part) ?: return null
                return RemoteKeyStep(key, BARE_KEY_DELAY_MS)
            }
            if (anchorIndex == 0) return null
            val keyText = part.substring(0, anchorIndex)
            val rest = part.substring(anchorIndex + 1)
            val afterPrompt = part[anchorIndex] == '~'

            val key = parseKey(keyText) ?: return null

            val holdSplit = rest.split('x', 'X')
            val delay = holdSplit[0].toIntOrNull() ?: return null
            if (delay < 0 || delay > MAX_DELAY_MS) return null
            val hold = if (holdSplit.size > 1) {
                holdSplit[1].toIntOrNull() ?: return null
            } else {
                RemoteKeyStep.DEFAULT_HOLD_MS
            }
            if (hold < 10 || hold > MAX_HOLD_MS) return null
            return RemoteKeyStep(key, delay, afterPrompt, hold)
        }

        private fun parseKey(text: String): RemoteKey? = when (text.trim().lowercase(Locale.US)) {
            "up", "volup", "vol_up", "volumeup", "音量上", "上" -> RemoteKey.UP
            "down", "voldown", "vol_down", "volumedown", "音量下", "下" -> RemoteKey.DOWN
            "power", "电源", "电源键", "ok", "确认", "确定" -> RemoteKey.POWER
            else -> null
        }
    }
}

/**
 * 按键脚本的运行器：按脚本在指定时刻往音量键设备节点写原始事件。
 *
 * 设备发现只做一次并缓存（`getevent -pl` 扫 KEY_VOLUMEUP 能力 → 按设备名兜底 →
 * 最后一个 event 节点兜底），之后每次按键就是一条 `sendevent` 序列，
 * 走已经建好的 root shell，单次往返几十毫秒。
 *
 * ## 权限前提（已核对，不需要额外规则）
 *
 * 注入跑在 KernelSU 的 root 域里：`kernel/selinux/rules.c` 对该域既有
 * `ksu_permissive`（违规只记录不拦截），也有 `ksu_allow(KERNEL_SU_DOMAIN, ALL, ALL, ALL)`
 * 与 `chr_file` 的 allowxperm，因此写 `/dev/input/event*` **不需要**再补 sepolicy；
 * DAC 层面 uid 0 可直接写（节点一般是 660 root:input）。真正会失败的只有两种：
 * 节点不存在（无 input 设备）或该节点不接受写入——那时走框架层兜底并如实记日志。
 */
object RemoteKeyInjector {

    /** EV_KEY / EV_SYN，Linux input 子系统的两个事件类型。 */
    private const val EV_SYN = 0
    private const val EV_KEY = 1

    @Volatile
    private var cachedDevice: String? = null

    @Volatile
    private var deviceResolved = false

    /**
     * 设备发现脚本。
     *
     * 第一优先：能报出 KEY_VOLUMEUP 能力的节点（`getevent -pl` 会打印能力标签，
     * 这是最直接的判据）。第二优先：设备名带 key/kpd/gpio/volume 的节点
     * （`getevent` 不可用的老 toybox 上走这条）。第三优先：最后一个 event 节点
     * ——在多数机器上按键设备确实排在后面，且这是兜底，宁可按错设备也不能不按。
     */
    private val discoveryScript: String = listOf(
        "dev=",
        "for d in /dev/input/event*; do [ -e \"\$d\" ] || continue; " +
            "if getevent -pl \"\$d\" 2>/dev/null | grep -q KEY_VOLUMEUP; then dev=\"\$d\"; break; fi; done",
        "if [ -z \"\$dev\" ]; then for d in /dev/input/event*; do [ -e \"\$d\" ] || continue; " +
            "n=\$(cat /sys/class/input/\$(basename \"\$d\")/device/name 2>/dev/null); " +
            "case \"\$n\" in *key*|*Key*|*kpd*|*KPD*|*gpio*|*volume*|*Volume*) dev=\"\$d\"; break;; esac; done; fi",
        "if [ -z \"\$dev\" ]; then for d in /dev/input/event*; do [ -e \"\$d\" ] && dev=\"\$d\"; done; fi",
        "printf '%s' \"\$dev\"",
    ).joinToString("; ")

    /** 解析（并缓存）音量键设备节点；返回 null 表示这台设备上找不到可写的 input 节点。 */
    private fun resolveDevice(): String? {
        if (deviceResolved) return cachedDevice
        val found = runCatching {
            ShellUtils.fastCmd(getRootShell(), discoveryScript).trim()
        }.getOrNull().orEmpty()
        cachedDevice = found.ifBlank { null }
        deviceResolved = true
        return cachedDevice
    }

    /**
     * 注入一次按键。
     *
     * 事件序列与真实按键一致：EV_KEY down → EV_SYN → 按住 holdMs → EV_KEY up →
     * EV_SYN。少了后面的 SYN，读 `getevent` 的脚本会一直等不到这次按键；
     * 少了按住时间，`getevent -c` 这类"只取一个事件"的读法可能采不到。
     *
     * @return 成功返回设备节点（用于日志），失败返回 null。
     */
    fun inject(key: RemoteKey, holdMs: Int = RemoteKeyStep.DEFAULT_HOLD_MS): String? {
        val device = resolveDevice()
        if (device == null) {
            // 没有可写的 input 节点：退到框架层注入。这条路只有走 KeyEvent/InputManager
            // 的脚本能看到，读 /dev/input 的脚本看不到——所以它只是兜底，日志里要说明。
            val ok = runCatching {
                ShellUtils.fastCmdResult(
                    getRootShell(),
                    "/system/bin/input keyevent ${key.androidKeyCode}",
                )
            }.getOrDefault(false)
            return if (ok) "input keyevent ${key.androidKeyCode}（框架层兜底）" else null
        }
        val holdSeconds = String.format(Locale.US, "%.3f", holdMs / 1000f)
        val cmd = listOf(
            "sendevent $device $EV_KEY ${key.keyCode} 1",
            "sendevent $device $EV_SYN 0 0",
            "sleep $holdSeconds",
            "sendevent $device $EV_KEY ${key.keyCode} 0",
            "sendevent $device $EV_SYN 0 0",
        ).joinToString("; ")
        val ok = runCatching { ShellUtils.fastCmdResult(getRootShell(), cmd) }.getOrDefault(false)
        return if (ok) device else null
    }

    /** 只做发现与自检，供 UI 的"测试按键"用。 */
    fun probe(): String? = resolveDevice()
}

/**
 * 按模块记住"上次用过的脚本"。
 *
 * 这是"全自动化"的最后一环：第一次遇到需要按键的模块时（无论脚本是发送端带过来的、
 * 还是用户在现场手按的），把结果记下来；此后同一个模块再分发过来、即使发送端没带
 * 脚本，接收端也能按记忆自动按键，不需要任何人再操作。
 */
object RemoteKeyPresets {
    private const val PREFS = "remote_key_presets"
    private const val MAX_ENTRIES = 200

    fun load(context: Context, moduleId: String): String? {
        if (moduleId.isBlank()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(moduleId, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun save(context: Context, moduleId: String, planText: String) {
        if (moduleId.isBlank() || planText.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 上限保护：SharedPreferences 不是数据库，攒太多会拖慢每次读取。
        if (prefs.all.size >= MAX_ENTRIES && !prefs.contains(moduleId)) {
            prefs.edit().clear().apply()
        }
        prefs.edit().putString(moduleId, planText).apply()
    }

    fun forget(context: Context, moduleId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(moduleId).apply()
    }
}

/**
 * 带按键脚本的模块条目。
 *
 * ## 脚本怎么跟着模块走（不改任何服务端）
 *
 * 脚本搭在**链接的 fragment** 上：`http://host/a.zip#xec=up%401500%2Cup%403000`。
 * 这样三种分发方式全都自动成立：
 *
 * 1. **XEC 加密码**：载荷本来就是"每条链接一行"，带 fragment 的链接原样进出；
 * 2. **XEC Hub 短码**：服务端只是把链接字符串存下来再还给我们，不解析 URL；
 * 3. **直接粘贴链接**：用户手动贴的链接可以自带 fragment。
 *
 * 下载端不受影响：HTTP 请求不发送 fragment（`URL` 的 ref 部分不参与请求），
 * 真正下载前 [RemoteEntry.parse] 会把它摘下来。旧版本管理器拿到这种链接也能装，
 * 只是忽略按键脚本——前向兼容是白送的。
 */
@Immutable
data class RemoteEntry(
    val url: String,
    val planText: String,
) {
    val plan: RemoteKeyPlan get() = RemoteKeyPlan.parse(planText).getOrDefault(RemoteKeyPlan.EMPTY)

    val displayName: String
        get() = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "module.zip" }

    companion object {
        private const val FRAGMENT_KEY = "xec"

        /** 把脚本挂到链接上（空脚本原样返回，不留痕迹）。 */
        fun annotate(url: String, planText: String): String {
            val clean = url.trim()
            if (planText.isBlank()) return clean
            val base = clean.substringBefore('#')
            val encoded = URLEncoder.encode(planText.trim(), "UTF-8")
            return "$base#$FRAGMENT_KEY=$encoded"
        }

        /** 从链接上摘出脚本并去掉 fragment；返回的 url 可以直接丢给下载器。 */
        fun parse(raw: String): RemoteEntry {
            val trimmed = raw.trim()
            val hash = trimmed.indexOf('#')
            if (hash < 0) return RemoteEntry(trimmed, "")
            val url = trimmed.substring(0, hash)
            val fragment = trimmed.substring(hash + 1)
            val planText = fragment.split('&')
                .firstOrNull { it.startsWith("$FRAGMENT_KEY=") }
                ?.substringAfter('=')
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                .orEmpty()
            return RemoteEntry(url, planText)
        }
    }
}
