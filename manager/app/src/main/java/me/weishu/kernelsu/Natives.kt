package me.weishu.kernelsu

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.Natives.Profile.RootProfileFlag
import me.weishu.kernelsu.ui.util.rootAvailable

/**
 * @author weishu
 * @date 2022/12/8.
 */
object Natives {
    // minimal supported kernel version
    // 10915: allowlist breaking change, add app profile
    // 10931: app profile struct add 'version' field
    // 10946: add capabilities
    // 10977: change groups_count and groups to avoid overflow write
    // 11071: Fix the issue of failing to set a custom SELinux type.
    // 12143: breaking: new supercall impl
    // 32310: new get_allow_list ioctl
    // 32336: new set_sepolicy ioctl
    // 32377: add set_init_pgrp ioctl
    // 32513: add uapi version
    // 31000: XECKernelPro version base (kernel/Kbuild; matches the manager
    // versionCode base). The old 32513 predated the base migration and no
    // kernel of this repository can ever report it.
    const val MINIMAL_SUPPORTED_KERNEL = 31000

    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"

    const val ROOT_UID = 0
    const val ROOT_GID = 0

    init {
        System.loadLibrary("kernelsu")
    }

    val version: Int
        external get

    val isSafeMode: Boolean
        external get

    val isLkmMode: Boolean
        external get

    val isLkmBundled: Boolean
        external get

    val isLateLoadMode: Boolean
        external get

    val isManager: Boolean
        external get

    val isPrBuild: Boolean
        external get

    external fun uidShouldUmount(uid: Int): Boolean

    /**
     * Get the profile of the given package.
     * @param key usually the package name
     * @return return null if failed.
     */
    external fun getAppProfile(key: String?, uid: Int): Profile
    external fun setAppProfile(profile: Profile?): Boolean

    /**
     * 受限授权（XECKernelPro 扩展）：限制 uid 只能以 `su -c <白名单命令>` 拿 root。
     * @param restricted false = 解除限制
     * @return 内核是否接受（老内核不认识 ioctl 会返回 false）
     */
    external fun setExecPolicy(uid: Int, restricted: Boolean, paths: Array<String>): Boolean

    /** @return 受限时返回白名单；未受限返回 null。 */
    external fun getExecPolicy(uid: Int): Array<String>?

    /**
     * Per-app uname 身份（XECKernelPro 扩展，ioctl 115/116）：按 uid 改写
     * uname(2) 的 release/version（内核 uts 状态不动，真 syscall 成功后才
     * 改写用户缓冲）。空串 = 保留真实字段；enabled=false 移除该 uid 条目。
     */
    external fun setUtsSpoof(uid: Int, enabled: Boolean, release: String, version: String): Boolean

    /** @return [release, version]；无条目或内核不支持返回 null。 */
    external fun getUtsSpoof(uid: Int): Array<String>?

    /**
     * APatch 式超级密钥（XECKernelPro 扩展，ioctl 112/113/114）。
     * @param clear true = 用 oldKey 清除内核密钥（newKey 忽略）
     */
    external fun setSuperkey(newKey: String, oldKey: String?, clear: Boolean): Boolean
    external fun superkeyAuth(key: String): Boolean
    /** @return [已设置, 守卫已武装]；null = 内核不认识（老内核） */
    external fun superkeyStatus(): BooleanArray?

    /** 守卫开关（feature 通道：KSU_FEATURE_SUPERKEY_GUARD）。 */
    external fun setSuperkeyGuard(armed: Boolean): Boolean

    /**
     * 拦截高危指令（XECKernelPro 扩展，ioctl 117）：内核在真正动手之前拦下
     * 「往 boot/vbmeta/super 等关键块设备裸写」和「删除根目录关键路径」这类
     * 会变砖的操作。脚本加密与否无关紧要——判定发生在 open/unlink 本身。
     *
     * @return [已开启(0/1), 累计拦截次数, 临时放行剩余秒数, 上次类型, 上次 pid,
     *         上次时间(单调时钟 ms)]；内核没有该 ioctl 时返回 null。
     */
    external fun cmdGuardStatus(): LongArray?

    /** @return [进程名, 目标路径]；无记录或内核不支持返回 null。 */
    external fun cmdGuardLast(): Array<String>?

    /** 临时放行窗口（秒），0 = 立即结束；@return 内核是否接受。 */
    external fun cmdGuardTrust(seconds: Int): Boolean

    /**
     * `su` compat mode can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isSuEnabled(): Boolean
    external fun setSuEnabled(enabled: Boolean): Boolean

    /**
     * Kernel module umount can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isKernelUmountEnabled(): Boolean
    external fun setKernelUmountEnabled(enabled: Boolean): Boolean

    /**
     * SELinux hide can be disabled temporarily.
     *  0: disabled
     *  1: enabled
     *  negative : error
     */
    external fun isSelinuxHideEnabled(): Boolean
    external fun setSelinuxHideEnabled(enabled: Boolean): Int

    /**
     * Get the user name for the uid.
     */
    external fun getUserName(uid: Int): String?

    external fun getSuperuserCount(): Int

    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    fun setDefaultUmountModules(umountModules: Boolean): Boolean {
        Profile(
            NON_ROOT_DEFAULT_PROFILE_KEY,
            NOBODY_UID,
            false,
            umountModules = umountModules
        ).let {
            return setAppProfile(it)
        }
    }

    fun isDefaultUmountModules(): Boolean {
        getAppProfile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID).let {
            return it.umountModules
        }
    }

    val kernelUAPIVersion: Int
        external get

    val managerUAPIVersion: Int
        external get

    fun isFullFeatured(): Boolean {
        return isManager && kernelUAPIVersion == managerUAPIVersion && rootAvailable()
    }

    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        // and there is a default profile for root and non-root
        val name: String,
        // current uid for the package, this is convivent for kernel to check
        // if the package name doesn't match uid, then it should be invalidated.
        val currentUid: Int = 0,

        // if this is true, kernel will grant root permission to this package
        val allowSu: Boolean = false,

        // these are used for root profile
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(),
        val context: String = KERNEL_SU_DOMAIN,
        val namespace: Int = Namespace.INHERITED.ordinal,

        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
        var rules: String = "", // this field is save in ksud!!

        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }

    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
