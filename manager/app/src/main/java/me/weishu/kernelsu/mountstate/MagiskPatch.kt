package me.weishu.kernelsu.mountstate

import android.content.Context
import android.system.Os
import java.io.File

/**
 * 方案甲：5.10 以下的挂载态 root **直接使用 Magisk 官方的修补方式**。
 *
 * 这里不含任何 boot 修补算法 —— 只做三件事：
 *  1. 把 Magisk 官方载荷按 `boot_patch.sh` 头部写明的目录契约摆到同一个目录里
 *     （`magiskboot`/`magiskinit`/`magisk`/`init-ld`/`stub.apk`/`busybox`），并用
 *     `busybox --install -s <workdir>` 铺出 applet 软链、把 `<workdir>` 放在 PATH 最前
 *     （官方 `grep_prop()` 依赖 busybox 的 `dos2unix` applet 才能取回继承的 SHA1）；
 *  2. 用 `/system/bin/sh` 跑官方脚本（`BOOTMODE=true` 的安装器模式，日志走 stdout）；
 *  3. 把脚本自己写出的 `new-boot.img` 取回来交给上层导出。
 *
 * 载荷原样取自 topjohnwu/Magisk v30.7（GPL-3.0，见 `THIRD_PARTY_NOTICES.md`），
 * 由 CI 按逐文件 sha256 校验后放进 `assets/magisk/` 与 `jniLibs/<abi>/`；
 * 我们一个字节都不改。
 *
 * 为什么放 jniLibs：`useLegacyPackaging = true` 会把 native 库解压到
 * `nativeLibraryDir` 并带可执行位，所以脚本里的 `./magiskboot` 能跑起来。
 *
 * **载荷不能拷进 app 私有目录再跑**：Android 10+ 的 W^X 让 `untrusted_app` 对
 * `app_data_file`（含 `cacheDir`）没有 execute，拷进去的 `magiskboot`/`busybox`
 * 一律 `Permission denied`。官方 App 的做法是**软链接**——
 * `MagiskInstaller.kt::extractFiles()` 的非 stub 分支就是
 * `Os.symlink(lib.path, "$installDir/$name")`：execve 沿软链解析到 `nativeLibraryDir`
 * 里的真实 inode，SELinux 检查的也是那个 inode，于是脚本能正常调用它。这里照抄。
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
                to.delete()
                // 软链而不是拷贝：cacheDir 属于 app_data_file，Android 10+ 的 W^X 下
                // untrusted_app 对它没有 execute，拷进来的二进制跑不起来（见类头注释）。
                try {
                    Os.symlink(from.absolutePath, to.absolutePath)
                } catch (e: Exception) {
                    // 极少数设备/文件系统不允许在这里建软链：退回拷贝（老行为），
                    // 并把"能不能执行"的后果交给脚本输出，别把可用性赌在软链上。
                    from.copyTo(to, overwrite = true)
                    to.setExecutable(true, false)
                    onLog("[!] symlink failed for ${to.name} (${e.message}); copied instead")
                }
            }
            for ((asset, exec) in ASSET_PAYLOAD) {
                val to = File(workDir, File(asset).name)
                context.assets.open(asset).use { input -> to.outputStream().use(input::copyTo) }
                if (exec) to.setExecutable(true, false)
            }
        }.onFailure { return PatchResult(false, null, "cannot stage magisk payload: ${it.message}") }

        // 1b) busybox 的 applet 必须能**按名字**调用：官方 `grep_prop()`
        //（util_functions.sh:49 `cat $FILES 2>/dev/null | dos2unix | sed ...`）
        // 里的 `dos2unix` 就是一个 busybox applet，而 boot_patch.sh:161
        //（`SHA1=$(grep_prop SHA1 config.orig)`）正靠它取回**继承的 SHA1** ——
        // 取不到就等于丢掉原厂备份链（还原到原厂 / OTA 不再可用）。
        // 官方 magiskd 也是这么装的：在放 busybox 的目录里跑 `busybox --install -s`
        //（`setup_magisk_env()`），applet 软链与 busybox 同目录；再靠 PATH 让脚本找到它们。
        val busybox = File(workDir, "busybox")
        if (!busybox.isFile) {
            return PatchResult(false, null, "missing payload: ${busybox.absolutePath}")
        }
        val installLog = try {
            val install =
                ProcessBuilder(busybox.absolutePath, "--install", "-s", workDir.absolutePath)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
            val output = install.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = install.waitFor()
            if (code != 0) "busybox --install -s exited $code: $output" else output
        } catch (e: Exception) {
            // 连 staged 的 busybox 都起不来，脚本里的 ./magiskboot 也一定起不来 —— 直接报错
            return PatchResult(false, null, "cannot run staged busybox: ${e.message}")
        }
        if (installLog.isNotEmpty()) onLog("[*] busybox --install -s: $installLog")
        // 不硬失败：设备自带 toybox 的 dos2unix 时，缺一个 applet 也不影响刷原厂镜像；
        // 但要把后果写清楚（只有"再修补已打过 Magisk 的镜像"这一条路会丢 SHA1）。
        if (!File(workDir, "dos2unix").exists() && !File("/system/bin/dos2unix").exists()) {
            onLog(
                "[!] no dos2unix applet in PATH: patching an already Magisk-patched image " +
                    "will lose the inherited SHA1 (stock backup chain)"
            )
        }

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
                    // TMPDIR 在这里**保护不了**工作目录：util_functions.sh:763 会把 TMPDIR
                    // 硬写成 /dev/tmp（abort() 的 `rm -rf $TMPDIR` 删的也是它，不是这里）。
                    // 仍然照传，只当脚本在 source util_functions.sh 之前就用 TMPDIR 的兜底。
                    env["TMPDIR"] = workDir.absolutePath
                    // workDir 必须排在最前：1b) 装好的 busybox applet 软链就在这里，
                    // 官方 grep_prop() 依赖的 `dos2unix` 只有靠它才能被解析到。
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
