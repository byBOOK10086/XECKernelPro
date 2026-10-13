package me.weishu.kernelsu.mountstate

import android.content.Context
import java.io.File

/**
 * 挂载态 root 路线（5.10 以下非 GKI 内核）：把 `xecinit` 注入 boot 镜像的 ramdisk。
 *
 * 与 [me.weishu.kernelsu.kpatch.KPatch] 的关键区别是**这条路线不需要 root**：
 * `xudc ramdisk-patch --boot <in> --output <out>` 在非 `--flash` 分支只做文件 IO
 * （读镜像 → 改写 ramdisk → 写新镜像，见 `userspace/ksud/src/ramdisk_patch.rs`），
 * 所以可以先在 App 空间产出补丁镜像，再由用户 fastboot / recovery 刷入 ——
 * 这正是"还没有 root 的 <5.10 用户拿到 root"的第一步。
 *
 * 注入进去的东西：ramdisk 的 `/init` 换成 `xecinit`（PID 1 接管，挂 proc/sys/dev、
 * 合并 `overlay.d/`、把 `*.rc` 追加进 `/init.rc`，最后 execv 真 init），
 * `overlay.d/xecpro.rc` 里写三个阶段（post-fs-data / services / boot-completed，
 * magic mount 与模块脚本靠它们跑起来）加 su 通道 daemon。
 *
 * 载荷来源：`libxeccli.so`（= xudc，CI 在 repack 阶段塞进 APK 的 `lib/<abi>/`，
 * 见 `repack_apk.py`）。直接原地 exec，不需要拷贝、不需要 chmod ——
 * `useLegacyPackaging = true` 保证 native 库带可执行位落到磁盘。
 */
object RamdiskPatch {

    data class PatchResult(
        val success: Boolean,
        val output: File?,
        val log: String,
    )

    /** xudc 载荷是否随这个 APK 打包（build-manager.yml 单独构建的 APK 不带）。 */
    fun available(context: Context): Boolean {
        val dir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(dir, "libxeccli.so").isFile
    }

    /**
     * 把 xecinit 注入 [bootImage]，产物落在 `cacheDir/mountstate/boot-patched.img`。
     * 全程 App 空间完成，不需要 root；失败时把 xudc 的输出原样带回给 UI。
     */
    fun patch(context: Context, bootImage: File, onLog: (String) -> Unit): PatchResult {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val xudc = File(nativeDir, "libxeccli.so")
        if (!xudc.isFile) {
            return PatchResult(false, null, "libxeccli.so missing from $nativeDir")
        }

        val workDir = File(context.cacheDir, "mountstate").apply { mkdirs() }
        val output = File(workDir, "boot-patched.img")
        output.delete()

        val argv = listOf(
            xudc.absolutePath,
            "ramdisk-patch",
            "--boot", bootImage.absolutePath,
            "--output", output.absolutePath,
            "--force",
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
                PatchResult(false, null, log.toString().ifBlank { "xudc exited $code" })
            }
        } catch (e: Exception) {
            PatchResult(false, null, "${e::class.simpleName}: ${e.message}")
        }
    }
}
