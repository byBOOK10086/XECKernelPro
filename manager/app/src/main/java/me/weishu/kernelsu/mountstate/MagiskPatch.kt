package me.weishu.kernelsu.mountstate

import android.content.Context
import java.io.File

/**
 * 方案甲：5.10 以下的挂载态 root **直接使用 Magisk 官方的修补方式**。
 *
 * 这里不含任何 boot 修补算法 —— 只做三件事：
 *  1. 把 Magisk 官方载荷按 `boot_patch.sh` 头部写明的目录契约摆到同一个目录里
 *     （`magiskboot`/`magiskinit`/`magisk`/`init-ld`/`stub.apk`/`busybox`）；
 *  2. 用 `/system/bin/sh` 跑官方脚本（`BOOTMODE=true` 的安装器模式，日志走 stdout）；
 *  3. 把脚本自己写出的 `new-boot.img` 取回来交给上层导出。
 *
 * 载荷原样取自 topjohnwu/Magisk v30.7（GPL-3.0，见 `THIRD_PARTY_NOTICES.md`），
 * 由 CI 按逐文件 sha256 校验后放进 `assets/magisk/` 与 `jniLibs/<abi>/`；
 * 我们一个字节都不改。
 *
 * 为什么放 jniLibs：`useLegacyPackaging = true` 会把 native 库解压到
 * `nativeLibraryDir` 并带可执行位，所以原地复制/改名就能 exec（kptools 同理）。
 *
 * **只在非 GKI（<5.10）分支使用**：GKI / 内核态（5.10+）路线一行都不碰 Magisk。
 */
object MagiskPatch {

    data class PatchResult(
        val success: Boolean,
        val output: File?,
        val log: String,
    )

    /**
     * 高级选项，一一对应 `boot_patch.sh` 的五个环境变量。
     *
     * 官方 App 也是把这几个暴露在设置/高级里：脚本**不会自己探测**它们，
     * 未设置时脚本内默认全是 `false`（= 保持官方默认行为）。
     */
    data class Options(
        /** 保留 AVB 2.0 / dm-verity（默认 false = 按官方默认去掉）。 */
        val keepVerity: Boolean = false,
        /** 保留强制加密（默认 false = 按官方默认去掉）。 */
        val keepForceEncrypt: Boolean = false,
        /** 同时修补 vbmeta 的 flags。 */
        val patchVbmetaFlag: Boolean = false,
        /** Recovery 模式：把补丁打进 recovery 镜像（A-only legacy SAR 机型要用）。 */
        val recoveryMode: Boolean = false,
        /** legacy SAR：内核里 `skip_initramfs` → `want_initramfs`（脚本里只在这一项为真时打）。 */
        val legacySar: Boolean = false,
    )

    /** 载荷文件名（jniLibs 里的名字）→ 脚本要求的名字。 */
    private val NATIVE_PAYLOAD = mapOf(
        "libmagiskboot.so" to "magiskboot",
        "libmagiskinit.so" to "magiskinit",
        "libmagisk.so" to "magisk",
        "libinit-ld.so" to "init-ld",
        "libbusybox.so" to "busybox",
    )

    /** assets 里的载荷 → 脚本要求的名字（可执行位）。 */
    private val ASSET_PAYLOAD = mapOf(
        "magisk/boot_patch.sh" to true,
        "magisk/util_functions.sh" to true,
        "magisk/stub.apk" to false,
    )

    /** 载荷是否随这个 APK 打包（管理器单独构建的 APK 不带，界面要灰掉并说明）。 */
    fun available(context: Context): Boolean {
        val dir = context.applicationInfo.nativeLibraryDir ?: return false
        if (NATIVE_PAYLOAD.keys.any { !File(dir, it).isFile }) return false
        return ASSET_PAYLOAD.keys.all { name ->
            runCatching { context.assets.open(name).close() }.isSuccess
        }
    }

    /**
     * 这台设备是不是 legacy SAR（A-only、system-as-root）？
     *
     * 官方脚本里 `LEGACYSAR=true` 会额外把内核的 `skip_initramfs` 改成
     * `want_initramfs`；官方 App 也是自动判定后传进来的。判据取官方
     * `util_functions.sh` 的同款：`ro.build.system_root_image` 非空；
     * 再兜一层 `/proc/cmdline` 里是否带 `skip_initramfs`。
     */
    fun detectLegacySar(): Boolean {
        val prop = runCatching {
            ProcessBuilder("getprop", "ro.build.system_root_image")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        if (prop.isNotEmpty()) return true
        return runCatching { File("/proc/cmdline").readText().contains("skip_initramfs") }
            .getOrDefault(false)
    }

    /**
     * 用官方流程修补 [bootImage]（用户自己的原厂镜像），产物落在缓存目录。
     *
     * @param onLog 逐行回调官方脚本的输出（进度就靠它）。
     */
    fun patch(
        context: Context,
        bootImage: File,
        options: Options,
        onLog: (String) -> Unit,
    ): PatchResult {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val workDir = File(context.cacheDir, "magisk-patch")
        runCatching {
            // 每次从干净目录开始：残留的 ramdisk.cpio / kernel / config 会让脚本读到上一次的状态
            workDir.deleteRecursively()
            workDir.mkdirs()
        }.onFailure { return PatchResult(false, null, "cannot prepare ${workDir.absolutePath}: ${it.message}") }

        // 1) 载荷落位（名字必须与 boot_patch.sh 的契约一致）
        runCatching {
            for ((src, dst) in NATIVE_PAYLOAD) {
                val from = File(nativeDir, src)
                if (!from.isFile) error("missing payload: ${from.absolutePath}")
                val to = File(workDir, dst)
                from.copyTo(to, overwrite = true)
                to.setExecutable(true, false)
            }
            for ((asset, exec) in ASSET_PAYLOAD) {
                val to = File(workDir, File(asset).name)
                context.assets.open(asset).use { input -> to.outputStream().use(input::copyTo) }
                if (exec) to.setExecutable(true, false)
            }
        }.onFailure { return PatchResult(false, null, "cannot stage magisk payload: ${it.message}") }

        // 2) 把用户选的镜像放进工作目录
        val input = File(workDir, "boot.img")
        runCatching { bootImage.copyTo(input, overwrite = true) }
            .onFailure { return PatchResult(false, null, "cannot copy boot image: ${it.message}") }

        // 3) 跑官方脚本（BOOTMODE=true 的安装器模式；日志走 stdout）
        val argv = listOf("/system/bin/sh", "boot_patch.sh", input.name)
        onLog("\$ ${argv.joinToString(" ")}")
        val log = StringBuilder()
        val exit: Int = try {
            val process = ProcessBuilder(argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    val env = environment()
                    env["BOOTMODE"] = "true"
                    // ensure_bb() 找的是 $TMPDIR/busybox；abort() 也会 rm -rf $TMPDIR
                    env["TMPDIR"] = workDir.absolutePath
                    env["PATH"] = workDir.absolutePath + ":" + (env["PATH"] ?: "/system/bin")
                    env["KEEPVERITY"] = options.keepVerity.toString()
                    env["KEEPFORCEENCRYPT"] = options.keepForceEncrypt.toString()
                    env["PATCHVBMETAFLAG"] = options.patchVbmetaFlag.toString()
                    env["RECOVERYMODE"] = options.recoveryMode.toString()
                    env["LEGACYSAR"] = options.legacySar.toString()
                }
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    log.appendLine(it)
                    onLog(it)
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            return PatchResult(false, null, "${e::class.simpleName}: ${e.message}")
        }

        // 4) 官方脚本的产物就是工作目录下的 new-boot.img
        val produced = File(workDir, "new-boot.img")
        if (exit != 0 || !produced.isFile || produced.length() == 0L) {
            return PatchResult(false, null, log.toString().ifBlank { "boot_patch.sh exited $exit" })
        }
        val stamp = System.currentTimeMillis().toString(36)
        val output = File(context.cacheDir, "magisk-patched-$stamp.img")
        return runCatching {
            produced.copyTo(output, overwrite = true)
            onLog("[+] Magisk patched boot written: ${output.name} (${output.length()} bytes)")
            PatchResult(true, output, log.toString())
        }.getOrElse { PatchResult(false, null, "cannot keep output: ${it.message}") }
    }
}
