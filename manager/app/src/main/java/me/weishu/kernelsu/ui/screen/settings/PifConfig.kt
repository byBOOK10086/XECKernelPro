package me.weishu.kernelsu.ui.screen.settings

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell
import org.json.JSONObject
import java.io.File

/**
 * 构建指纹伪装（PIF）的配置层（2026-11-10）。
 *
 * 这一层只做"把一份 pif.json 翻译成设备上的属性表"，**不解析、不写入任何东西在
 * 应用进程里独立生效** —— 真正的写入由模块脚本 `pif.sh` 在开机早期做（见
 * `userspace/ksud/builtin/TA_enhanced/pif.sh` 的注释：为什么不在脚本里解析 JSON）。
 *
 * 三层数据：
 *  - 来源：已装 PIF 模块的 `pif.json`、用户选的 JSON 文件、我们内置的机型 profile
 *  - 产物：`/data/adb/.xudc_secure/pif/props.txt`（`ro.xxx=值`，一行一条）
 *  - 开关：`/data/adb/.xudc_secure/pif/enabled`（存在=启用）
 *
 * Play Integrity 的 DEVICE/STRONG 判定要的是"整机身份 + 补丁 + 锁态"三件自洽：
 * 指纹里的品牌/机型/版本/构建号必须和 `ro.product.*`、`ro.build.version.*` 对得上，
 * 否则验机工具会判"指纹不一致"。所以这里把 pif.json 的每一项**展开到所有分区级
 * 属性**（Android 12+ 应用读的是 `ro.product.system.*` / `ro.product.vendor.*`），
 * 而不是只写 `ro.build.fingerprint` 一条。
 */
object PifConfig {

    private const val PIF_DIR = "/data/adb/.xudc_secure/pif"
    private const val PROPS = "$PIF_DIR/props.txt"
    private const val ENABLED = "$PIF_DIR/enabled"
    private const val SECURITY_PATCH_FILE = "$PIF_DIR/security_patch"

    /** 模块脚本位置（内置模块装在 ksud 的 builtin 树里，外部模块在 modules 下）。 */
    private val PIF_SCRIPTS = listOf(
        "/data/adb/ksu/builtin/TA_enhanced/pif.sh",
        "/data/adb/modules/TA_enhanced/pif.sh",
    )

    /** 已装 PIF 类模块的 pif.json 常见位置。 */
    private val JSON_SOURCES = listOf(
        "/data/adb/pif.json",
        "/data/adb/modules/playintegrityfix/pif.json",
        "/data/adb/modules/play_integrity_fix/pif.json",
        "/data/adb/modules/PIF-Next/pif.json",
        "/data/adb/modules/playintegrityfix/custom.pif.json",
    )

    data class State(
        val enabled: Boolean,
        val propCount: Int,
        val fingerprint: String?,
        val model: String?,
        val patchValue: String?,
        val importedFrom: String?,
        val availableSources: List<String>,
        val scriptPath: String?,
    )

    /** 内置兜底机型。内置值只是"没有别的来源时"的起步点，不保证 Play 接受。 */
    data class Profile(val name: String, val fields: Map<String, String>)

    val BUILTIN_PROFILES: List<Profile> = listOf(
        Profile(
            "Pixel 8 Pro（Android 15）",
            mapOf(
                "MANUFACTURER" to "Google",
                "BRAND" to "google",
                "MODEL" to "Pixel 8 Pro",
                "DEVICE" to "husky",
                "PRODUCT" to "husky",
                "FINGERPRINT" to "google/husky/husky:15/AP4A.250105.002/12823426:user/release-keys",
                "ID" to "AP4A.250105.002",
                "INCREMENTAL" to "12823426",
                "RELEASE" to "15",
                "TYPE" to "user",
                "TAGS" to "release-keys",
                "SECURITY_PATCH" to "2025-01-05",
            ),
        ),
        Profile(
            "Pixel 7（Android 14）",
            mapOf(
                "MANUFACTURER" to "Google",
                "BRAND" to "google",
                "MODEL" to "Pixel 7",
                "DEVICE" to "panther",
                "PRODUCT" to "panther",
                "FINGERPRINT" to "google/panther/panther:14/AP2A.240905.003/12231197:user/release-keys",
                "ID" to "AP2A.240905.003",
                "INCREMENTAL" to "12231197",
                "RELEASE" to "14",
                "TYPE" to "user",
                "TAGS" to "release-keys",
                "SECURITY_PATCH" to "2024-09-05",
            ),
        ),
    )

    class Refused(message: String) : IllegalStateException(message)

    // ------------------------------------------------------------------ 读取

    suspend fun state(): Result<State> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(Refused("没有 root，读不到 /data/adb"))
        val script = buildString {
            append("if [ -f '$ENABLED' ]; then echo 'ENABLED=1'; else echo 'ENABLED=0'; fi;")
            append(" echo \"COUNT=\$(grep -cE '^ro\\.' '$PROPS' 2>/dev/null)\";")
            append(" echo \"FP=\$(getprop ro.build.fingerprint)\";")
            append(" echo \"MODEL=\$(getprop ro.product.model)\";")
            append(" echo \"PATCH=\$(cat '$SECURITY_PATCH_FILE' 2>/dev/null)\";")
            append(" echo \"SRC=\$(head -n1 '$PIF_DIR/source.txt' 2>/dev/null)\";")
            PIF_SCRIPTS.forEachIndexed { i, p ->
                append(" [ -f '$p' ] && echo 'SCRIPT_$i=$p';")
            }
            JSON_SOURCES.forEachIndexed { i, p ->
                append(" [ -f '$p' ] && echo 'JSON_$i=$p';")
            }
        }
        val result = runCatching { shell.newJob().add(script).exec() }.getOrNull()
            ?: return@withContext Result.failure(Refused("读取状态失败"))
        val values = HashMap<String, String>()
        val sources = ArrayList<String>()
        for (line in result.out) {
            val text = line.trim()
            val eq = text.indexOf('=')
            if (eq <= 0) continue
            val key = text.substring(0, eq)
            val value = text.substring(eq + 1)
            if (key.startsWith("JSON_")) {
                if (value.isNotEmpty()) sources += value
            } else {
                values[key] = value
            }
        }
        val scriptPath = PIF_SCRIPTS.indices.firstNotNullOfOrNull { values["SCRIPT_$it"] }
        Result.success(
            State(
                enabled = values["ENABLED"] == "1",
                propCount = values["COUNT"]?.toIntOrNull() ?: 0,
                fingerprint = values["FP"]?.takeIf { it.isNotEmpty() },
                model = values["MODEL"]?.takeIf { it.isNotEmpty() },
                patchValue = values["PATCH"]?.takeIf { it.isNotEmpty() },
                importedFrom = values["SRC"]?.takeIf { it.isNotEmpty() },
                availableSources = sources,
                scriptPath = scriptPath,
            ),
        )
    }

    // ------------------------------------------------------------------ 导入

    /** 从设备上一个 pif.json 路径导入。 */
    suspend fun importFromPath(context: Context, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            val shell = awaitRootShell()
            if (!shell.isRoot) return@withContext Result.failure(Refused("没有 root"))
            val local = File(context.cacheDir, "import_${System.currentTimeMillis()}.json")
            val copied = shell.newJob()
                .add("cp -f '$path' '${local.absolutePath}' 2>/dev/null || cat '$path' > '${local.absolutePath}'")
                .exec()
            try {
                if (!copied.isSuccess || !local.isFile || local.length() == 0L) {
                    return@withContext Result.failure(Refused("读不到 $path"))
                }
                installJson(context, local, sourceName = path)
            } finally {
                local.delete()
            }
        }

    /** 从用户选的文件导入。 */
    suspend fun importFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val local = File(context.cacheDir, "import_${System.currentTimeMillis()}.json")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                local.outputStream().use { input.copyTo(it) }
            } != null && local.length() > 0
        }.getOrDefault(false)
        try {
            if (!ok) return@withContext Result.failure(Refused("读取你选的文件失败"))
            installJson(context, local, sourceName = uri.lastPathSegment ?: "所选文件")
        } finally {
            local.delete()
        }
    }

    /** 用内置 profile 生成属性表。 */
    suspend fun applyProfile(context: Context, profile: Profile): Result<String> =
        withContext(Dispatchers.IO) {
            val json = JSONObject()
            for ((k, v) in profile.fields) json.put(k, v)
            val local = File(context.cacheDir, "profile_${System.currentTimeMillis()}.json")
            runCatching { local.writeText(json.toString(2)) }
                .getOrElse { return@withContext Result.failure(Refused("写临时文件失败")) }
            try {
                installJson(context, local, sourceName = "内置 profile：${profile.name}")
            } finally {
                local.delete()
            }
        }

    /**
     * 把一份 JSON 翻译成属性表并落盘。
     *
     * 只接受"扁平 key/value"（PIF 的 pif.json 就是这种形态）；未知键忽略，
     * 缺 FINGERPRINT 直接拒绝 —— 没有指纹等于什么都没做，不能给用户一个假成功。
     */
    private suspend fun installJson(context: Context, jsonFile: File, sourceName: String): Result<String> {
        val shell = awaitRootShell()
        if (!shell.isRoot) return Result.failure(Refused("没有 root"))
        val obj = runCatching { JSONObject(jsonFile.readText()) }
            .getOrElse { return Result.failure(Refused("这不像是 PIF 的 pif.json（JSON 解析失败）")) }
        val fingerprint = obj.optString("FINGERPRINT").takeIf { it.isNotBlank() }
            ?: obj.optString("FINGERPRINT_1").takeIf { it.isNotBlank() }
            ?: return Result.failure(Refused("JSON 里没有 FINGERPRINT 字段"))
        val props = toProps(obj, fingerprint)
        if (props.isEmpty()) return Result.failure(Refused("没能从 JSON 里解析出任何属性"))
        val body = buildString {
            append("# 由 XECKernel Pro 管理器生成；来源：").append(sourceName).append('\n')
            for ((k, v) in props) append(k).append('=').append(v).append('\n')
        }
        val local = File(context.cacheDir, "pif_props_${System.currentTimeMillis()}.txt")
        runCatching { local.writeText(body) }
            .getOrElse { return Result.failure(Refused("写临时文件失败")) }
        val staged = "$PIF_DIR/props.txt.new"
        val install = shell.newJob().add(
            "mkdir -p '$PIF_DIR' && cp -f '${local.absolutePath}' '$staged' && " +
                "chmod 644 '$staged' && mv -f '$staged' '$PROPS' && " +
                "printf '%s\\n' '$sourceName' > '$PIF_DIR/source.txt' && " +
                "touch '$ENABLED'",
        ).exec()
        local.delete()
        if (!install.isSuccess) {
            return Result.failure(Refused("写入 $PROPS 失败：${install.err.joinToString(" ").take(160)}"))
        }
        // PIF 的 SECURITY_PATCH 交给守护进程当自定义日期：它每 2 小时会把
        // ro.*.security_patch 刷成这个值，和指纹里的构建日期保持自洽。
        val patch = obj.optString("SECURITY_PATCH").takeIf { it.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) }
        if (patch != null) {
            shell.newJob().add(
                "echo '$patch' > '$SECURITY_PATCH_FILE'; " +
                    "B=/data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced; " +
                    "[ -x \"\$B\" ] && \"\$B\" config set security_patch.custom_date '$patch' >/dev/null 2>&1; true",
            ).exec()
            shell.newJob().add("B=/data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced; [ -x \"\$B\" ] && \"\$B\" security-patch update --force >/dev/null 2>&1; true").exec()
        }
        return Result.success("已导入 ${props.size} 条属性（指纹：$fingerprint）")
    }

    // ------------------------------------------------------------------ 开关 / 应用

    suspend fun setEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(Refused("没有 root"))
        val cmd = if (enabled) {
            "mkdir -p '$PIF_DIR' && touch '$ENABLED'"
        } else {
            "rm -f '$ENABLED'"
        }
        val result = runCatching { shell.newJob().add(cmd).exec() }.getOrNull()
            ?: return@withContext Result.failure(Refused("执行失败"))
        if (result.isSuccess) Result.success(Unit)
        else Result.failure(Refused("写入开关失败"))
    }

    /** 立刻重新应用（不等下次开机）。重启后属性会由 post-fs-data 再写一次。 */
    suspend fun applyNow(): Result<String> = withContext(Dispatchers.IO) {
        val shell = awaitRootShell()
        if (!shell.isRoot) return@withContext Result.failure(Refused("没有 root"))
        val script = PIF_SCRIPTS.joinToString(" || ") { "[ -f '$it' ] && sh '$it' apply" }
        val result = runCatching { shell.newJob().add("$script; getprop ro.build.fingerprint").exec() }
            .getOrElse { return@withContext Result.failure(Refused("执行失败")) }
        val out = result.out.joinToString(" ").trim()
        if (result.isSuccess || out.isNotEmpty()) {
            Result.success(out.ifEmpty { "已应用" })
        } else {
            Result.failure(Refused("pif.sh 执行失败：${result.err.joinToString(" ").take(160)}"))
        }
    }

    // ------------------------------------------------------------------ 映射

    /**
     * pif.json（扁平字段）→ 属性表。
     *
     * 每个身份字段都展开到**所有分区级属性**：Android 12+ 的应用读
     * `ro.product.system.*` / `ro.product.vendor.*`，只写 `ro.product.model`
     * 会出现"应用里机型没变"的假象。
     */
    private fun toProps(obj: JSONObject, fingerprint: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(name: String, value: String) {
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        fun identity(base: String, value: String) {
            put("ro.product.$base", value)
            put("ro.product.system.$base", value)
            put("ro.product.vendor.$base", value)
            put("ro.product.odm.$base", value)
            put("ro.product.product.$base", value)
            put("ro.product.system_ext.$base", value)
            put("ro.vendor.product.$base", value)
        }

        obj.optString("MANUFACTURER").takeIf { it.isNotEmpty() }?.let { identity("manufacturer", it) }
        obj.optString("BRAND").takeIf { it.isNotEmpty() }?.let { identity("brand", it) }
        obj.optString("MODEL").takeIf { it.isNotEmpty() }?.let { identity("model", it) }
        obj.optString("DEVICE").takeIf { it.isNotEmpty() }?.let { v ->
            identity("device", v)
            put("ro.build.product", v)
        }
        obj.optString("PRODUCT").takeIf { it.isNotEmpty() }?.let { identity("name", it) }

        for (name in listOf(
            "ro.build.fingerprint",
            "ro.bootimage.build.fingerprint",
            "ro.system.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint",
            "ro.product.build.fingerprint",
            "ro.system_ext.build.fingerprint",
        )) {
            put(name, fingerprint)
        }

        obj.optString("RELEASE").takeIf { it.isNotEmpty() }?.let { put("ro.build.version.release", it) }
        obj.optString("ID").takeIf { it.isNotEmpty() }?.let {
            put("ro.build.id", it)
            put("ro.build.display.id", it)
        }
        obj.optString("INCREMENTAL").takeIf { it.isNotEmpty() }?.let { put("ro.build.version.incremental", it) }
        obj.optString("TYPE").takeIf { it.isNotEmpty() }?.let { put("ro.build.type", it) }
        obj.optString("TAGS").takeIf { it.isNotEmpty() }?.let { put("ro.build.tags", it) }
        obj.optString("SECURITY_PATCH").takeIf { it.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) }?.let {
            // 由守护进程写 ro.*.security_patch，这里不重复写（pif.sh 白名单也不放行）。
        }
        // 指纹里能推导出的字段（有些 pif.json 只给指纹）
        val parts = fingerprint.split("/")
        if (parts.size >= 5) {
            if (out.none { it.key == "ro.product.brand" }) identity("brand", parts[0])
            if (out.none { it.key == "ro.product.name" }) identity("name", parts[1])
            if (out.none { it.key == "ro.product.device" }) {
                identity("device", parts[2])
                put("ro.build.product", parts[2])
            }
        }
        return out
    }
}
