package me.weishu.kernelsu.ui.screen.install

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.R

@Parcelize
internal sealed class InstallMethod : Parcelable {
    data class SelectFile(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.select_file,
        override val summary: String?
    ) : InstallMethod()

    data class SelectFileForKpm(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.select_file_kpm,
        override val summary: String?
    ) : InstallMethod()

    data class DownloadFile(
        val url: String? = null,
        val partition: String? = null,
        @get:StringRes override val label: Int = R.string.download_file,
        override val summary: String?
    ) : InstallMethod()

    data class AnyKernel(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.install_anykernel,
        override val summary: String?
    ) : InstallMethod()

    data object DirectInstall : InstallMethod() {
        override val label: Int
            get() = R.string.direct_install
    }

    data object DirectInstallToInactiveSlot : InstallMethod() {
        override val label: Int
            get() = R.string.install_inactive_slot
    }

    /**
     * 提权更新：管理器升级后重放提权/late-load，让新版本管理器与内核模块重新
     * 对齐。点击直接进入提权控制台（自动识别机型），不走下面的刷写流程。
     */
    data object PrivilegeUpdate : InstallMethod() {
        override val label: Int
            get() = R.string.privilege_update_title
    }

    /**
     * KernelPatch 路线（5.10 以下的内核）：kptools 把 kpimg 打进 boot.img，
     * 产物手动 fastboot/recovery 刷入。只在自动检测到非 GKI 内核时出现。
     */
    data class KernelPatch(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.kernel_patch_install,
        override val summary: String?
    ) : InstallMethod()

    /**
     * Magisk 路线（5.10 以下非 GKI 的正式路线）：**直接使用 Magisk 官方的修补方式**。
     *
     * 管理器不实现任何 boot 修补算法：把官方载荷按 `boot_patch.sh` 的目录契约摆好，
     * 让脚本自己产出 `new-boot.img`，再由用户 fastboot/recovery 刷入。**全程不需要
     * root**，只在自动检测到非 GKI 内核时出现。
     */
    data class MagiskPatch(
        val uri: Uri? = null,
        @get:StringRes override val label: Int = R.string.magisk_patch_install,
        override val summary: String?
    ) : InstallMethod()

    abstract val label: Int

    @IgnoredOnParcel
    open val summary: String? = null
}

fun isKoFile(context: Context, uri: Uri): Boolean {
    val seg = uri.lastPathSegment ?: ""
    if (seg.endsWith(".ko", ignoreCase = true)) return true

    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                name?.endsWith(".ko", ignoreCase = true) == true
            } else {
                false
            }
        } ?: false
    } catch (_: Throwable) {
        false
    }
}
