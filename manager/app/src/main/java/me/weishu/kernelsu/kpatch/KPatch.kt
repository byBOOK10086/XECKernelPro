package me.weishu.kernelsu.kpatch

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * KernelPatch 路线（5.10 以下内核的匹配刷写方案）。
 *
 * 对 5.10 以下（非 GKI）内核，KernelSU 的 LKM 路线不可用；本类按 APatch /
 * KPatch-Next 的方式用 kptools 把 kpimg 打进 boot 镜像（ftruncate 系统调用
 * 劫持作为 supercall 通道），产物由用户手动 fastboot/recovery 刷入。
 *
 * 二进制来源（CI 固定下载并校验 sha256，见 .github/workflows/build-xecpro.yml）：
 *  - kptools-android 0.13.5-2 → jniLibs/arm64-v8a/libkptools.so（GPL-2.0，
 *    KernelSU-Next/KPatch-Next，从 nativeLibraryDir 直接 exec）
 *  - kpimg-linux 0.13.5-2 → assets/kpatch/kpimg（内核侧 blob，arm64）
 *
 * superkey 生成一次后持久化：内核里持有它才能通过 supercall 认证。
 */
object KPatch {

    data class PatchResult(
        val success: Boolean,
        val output: File?,
        val log: String,
    )

    private const val PREFS = "kpatch_prefs"
    private const val KEY_SUPERKEY = "superkey"

    fun superkey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SUPERKEY, null)?.let { return it }
        val key = UUID.randomUUID().toString().replace("-", "").take(32)
        prefs.edit().putString(KEY_SUPERKEY, key).apply()
        return key
    }

    /** kptools 是否已随包安装（CI 缺位时降级为不可用，界面给提示）。 */
    fun available(context: Context): Boolean {
        val dir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(dir, "libkptools.so").isFile
    }

    /**
     * 把 kpimg 打进 boot.img。全自动失败的话把 kptools 的输出原样带回，UI
     * 直接展示（CONFIG_KALLSYMS 缺失等中止原因都在里面）。
     */
    fun patch(context: Context, bootImage: File, onLog: (String) -> Unit): PatchResult {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val kptools = File(nativeDir, "libkptools.so")
        if (!kptools.isFile) {
            return PatchResult(false, null, "libkptools.so missing from ${nativeDir}")
        }
        val kpimg = File(context.cacheDir, "kpatch_kpimg")
        runCatching {
            context.assets.open("kpatch/kpimg").use { input ->
                kpimg.outputStream().use(input::copyTo)
            }
        }.onFailure {
            return PatchResult(false, null, "extract kpimg failed: ${it.message}")
        }

        val workDir = File(context.cacheDir, "kpatch").apply { mkdirs() }
        val output = File(workDir, "boot-patched.img")
        output.delete()

        val key = superkey(context)
        val argv = listOf(
            kptools.absolutePath,
            "-p",
            "-i", bootImage.absolutePath,
            "-k", kpimg.absolutePath,
            "-S", key,
            "-o", output.absolutePath,
        )
        onLog("\$ ${argv.joinToString(" ")}")
        return try {
            val process = ProcessBuilder(argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val log = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    log.appendLine(it)
                    onLog(it)
                }
            }
            val code = process.waitFor()
            if (code == 0 && output.isFile && output.length() > 0) {
                onLog("[+] patched boot written: ${output.name} (${output.length()} bytes)")
                PatchResult(true, output, log.toString())
            } else {
                PatchResult(false, null, log.toString().ifBlank { "kptools exited $code" })
            }
        } catch (e: Exception) {
            PatchResult(false, null, "${e::class.simpleName}: ${e.message}")
        } finally {
            kpimg.delete()
        }
    }
}
