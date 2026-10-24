package me.weishu.kernelsu.mountstate

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.getKernelVersion
import me.weishu.kernelsu.privilege.BootlockStatus
import me.weishu.kernelsu.ui.util.LkmSelection
import me.weishu.kernelsu.ui.util.installBoot

/**
 * 一键永久 Root（①修正版）：把当前的临时 root（提权/late-load）转成持久 root，
 * 全程免电脑、免 fastboot。
 *
 * **硬闸门：bootloader 必须已解锁**（[BootlockStatus]）。锁定的设备写 boot =
 * AVB 验签失败 = 变砖，因此锁定/未知状态直接拒绝并提示走解锁通道或自动重放。
 *
 * 按内核形态分流：
 *  - GKI（5.10+）：走现成的 `installBoot`（ksud `boot-patch --flash`，
 *    含 boot_txn 备份与失败回滚机制），内核态持久。
 *  - <5.10：Magisk 修补路线——dd 导出当前 boot → 官方 boot_patch.sh 修补
 *    （[MagiskPatch]）→ 备份原镜像到 /data/adb/xec/ → dd 写回 → 读回校验。
 *
 * 全部 shell 操作走 libsu root shell（调用前提：此刻 root 可用）。
 */
object AutoPermanent {

    data class Outcome(
        val success: Boolean,
        /** 成功后是否需要重启才生效。 */
        val needReboot: Boolean,
        val message: String,
    )

    private const val BACKUP_DIR = "/data/adb/xec"
    private const val KEEP_BACKUPS = 3

    suspend fun run(context: Context, onLog: (String) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            // 闸门：只认「确认已解锁」。未知按锁定处理（安全侧倒）。
            val bl = BootlockStatus.detect()
            if (bl.locked != false) {
                return@withContext Outcome(
                    success = false,
                    needReboot = false,
                    message = "bootloader locked or unknown (${bl.source})",
                )
            }
            onLog("[*] bootloader: unlocked (${bl.source})")

            val kernel = getKernelVersion()
            return@withContext if (kernel.isGKI()) {
                gkiFlow(onLog)
            } else {
                magiskFlow(context, onLog)
            }
        }

    /** GKI：内核态持久安装（ksud boot-patch --flash，自带备份/回滚）。 */
    private suspend fun gkiFlow(onLog: (String) -> Unit): Outcome {
        onLog("[*] GKI kernel: ksud boot-patch --flash (backup forced)")
        val result = installBoot(
            bootUri = null,
            lkm = LkmSelection.KmiNone,
            ota = false,
            partition = null,
            allowShell = false,
            enableAdb = false,
            forceBackup = true,
            onStdout = onLog,
            onStderr = onLog,
        )
        return if (result.code == 0) {
            Outcome(true, needReboot = true, message = "kernel-state installed")
        } else {
            Outcome(false, needReboot = false, message = result.err.ifBlank { "ksud exited ${result.code}" })
        }
    }

    /** <5.10：Magisk 修补 + dd 写回（写回前备份 + 写回后读回校验）。 */
    private suspend fun magiskFlow(context: Context, onLog: (String) -> Unit): Outcome {
        // 1) 找当前槽位的 boot 分区（A/B 设备带 _a/_b 后缀）。
        val slot = shell("getprop ro.boot.slot_suffix").trim()
        val candidates = listOf(
            "/dev/block/by-name/boot$slot",
            "/dev/block/bootdevice/by-name/boot$slot",
        )
        val partition = candidates.firstOrNull { shell("[ -e $it ] && echo yes").trim() == "yes" }
        if (partition == null) {
            return Outcome(false, false, "boot partition not found (slot='$slot')")
        }
        onLog("[*] partition: $partition")

        // 2) 导出当前 boot。
        val dump = File(context.cacheDir, "autopermanent-boot.img")
        val dd = shell("dd if=$partition of=${dump.absolutePath} bs=4M")
        if (!dump.isFile || dump.length() == 0L) {
            return Outcome(false, false, "dump failed: $dd")
        }
        onLog("[*] dumped current boot: ${dump.length()} bytes")

        // 3) 备份到 /data/adb/xec/（保留最近 3 份），失败即中止——没有退路不写。
        val stamp = System.currentTimeMillis()
        val backupCmd = """
            mkdir -p $BACKUP_DIR &&
            cp ${dump.absolutePath} $BACKUP_DIR/boot-backup-$stamp.img &&
            ls -t $BACKUP_DIR/boot-backup-*.img | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f
        """.trimIndent().replace("\n", " ")
        if (!shellOk(backupCmd)) {
            return Outcome(false, false, "backup failed; refusing to flash without rollback path")
        }
        onLog("[*] stock backup: $BACKUP_DIR/boot-backup-$stamp.img")

        // 4) 官方流程修补。
        val options = MagiskPatch.Options(legacySar = MagiskPatch.detectLegacySar())
        val patched = MagiskPatch.patch(context, dump, options, onLog)
        val image = patched.output
        if (!patched.success || image == null) {
            return Outcome(false, false, patched.log.lineSequence().lastOrNull { it.isNotBlank() } ?: "patch failed")
        }
        onLog("[*] patched: ${image.name} (${image.length()} bytes)")

        // 5) 写回 + 6) 读回校验（整镜像 sha256 比对，不一致视为失败并提示恢复）。
        if (!shellOk("dd if=${image.absolutePath} of=$partition bs=4M conv=fsync")) {
            return Outcome(false, false, "flash failed; restore with $BACKUP_DIR/boot-backup-$stamp.img")
        }
        val verify = File(context.cacheDir, "autopermanent-verify.img")
        verify.delete()
        if (!shellOk("dd if=$partition of=${verify.absolutePath} bs=4M")) {
            return Outcome(false, false, "verify readback failed")
        }
        val want = shell("sha256sum ${image.absolutePath}").trim().split_whitespace_first()
        val got = shell("sha256sum ${verify.absolutePath}").trim().split_whitespace_first()
        dump.delete()
        verify.delete()
        if (want.isEmpty() || want != got) {
            return Outcome(
                false,
                false,
                "verify mismatch (want ${want.take(12)}, got ${got.take(12)}); " +
                    "restore with $BACKUP_DIR/boot-backup-$stamp.img",
            )
        }
        onLog("[*] readback verified: ${want.take(16)}…")
        return Outcome(true, needReboot = true, message = "Magisk-patched boot flashed and verified")
    }

    private fun shell(cmd: String): String =
        Shell.cmd(cmd).exec().out.joinToString("\n")

    private fun shellOk(cmd: String): Boolean = Shell.cmd(cmd).exec().isSuccess

    private fun String.split_whitespace_first(): String = split(' ', limit = 2).firstOrNull().orEmpty()
}
