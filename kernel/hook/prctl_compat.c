// SPDX-License-Identifier: GPL-2.0-or-later

/*
 * Legacy KernelSU prctl() supercall compatibility layer.
 *
 * Upstream KernelSU historically exposed a prctl(0xdeadbeef, CMD, ...) interface
 * that userspace clients (notably Zygisk Next) use to detect the root
 * implementation and to query the per-UID exclude list. This fork moved to the
 * ioctl()-based supercall and stopped hooking __NR_prctl, so Zygisk Next reports
 * "无法确定 root 实现" and its exclude list silently fails.
 *
 * This file restores that legacy ABI on top of the existing syscall dispatcher.
 */

#include <asm/syscall.h>
#include <linux/compiler.h>
#include <linux/cred.h>
#include <linux/printk.h>
#include <linux/ptrace.h>
#include <linux/types.h>
#include <linux/uaccess.h>

#include "arch.h"
#include "klog.h" // IWYU pragma: keep
#include "uapi/supercall.h"
#include "hook/syscall_hook.h"
#include "manager/manager_identity.h"
#include "policy/allowlist.h"

#define KERNEL_SU_OPTION 0xdeadbeef

#define CMD_GET_VERSION 2
#define CMD_UID_GRANTED_ROOT 12
#define CMD_UID_SHOULD_UMOUNT 13
#define CMD_GET_MANAGER_UID 16

/*
 * Version reported to Zygisk Next. Consumers reject anything below their
 * MIN_KSU_VERSION (10940) as "TooOld", and older builds of the ZN/ReZygisk
 * lineage also rejected anything above MAX_KSU_VERSION (20000) as "Abnormal",
 * which disables every feature including the denylist. This fork reports
 * >= 30000 natively, so KSU_COMPAT_REPORTED_VERSION - shared with the ioctl
 * path so both interfaces agree - clamps it into a window both generations
 * accept. See the definition in uapi/supercall.h for the full rationale.
 */
#define PRCTL_COMPAT_KSU_VERSION KSU_COMPAT_REPORTED_VERSION

static long ksu_handle_prctl(unsigned long arg2, unsigned long arg3, unsigned long arg4, unsigned long arg5)
{
    u32 *result = (u32 *)arg5;
    u32 reply_ok = KERNEL_SU_OPTION;
    /* Success in this interface is signalled by writing the magic to arg5. */

    switch (arg2) {
    case CMD_GET_VERSION: {
        u32 version = PRCTL_COMPAT_KSU_VERSION;
        if (copy_to_user((void __user *)arg3, &version, sizeof(version))) {
            pr_err("prctl compat: GET_VERSION copy err\n");
            return -EFAULT;
        }
        if (arg4) {
            u32 version_flags = 0;
#ifdef MODULE
            version_flags |= KSU_GET_INFO_FLAG_LKM;
#endif
            /*
             * Keep the flag set consistent with the ioctl path: if the manager
             * itself ever falls back to this legacy interface it must still be
             * recognised as the manager (otherwise its UI loses every
             * privileged capability).
             */
            if (is_manager()) {
                version_flags |= KSU_GET_INFO_FLAG_MANAGER;
            }
            if (copy_to_user((void __user *)arg4, &version_flags, sizeof(version_flags))) {
                pr_err("prctl compat: GET_VERSION flags copy err\n");
                return -EFAULT;
            }
        }
        /*
         * This write-back is what ZN actually tests for: it calls
         * prctl(0xdeadbeef, CMD_GET_VERSION, &version, &flags, &reply) and
         * treats the call as successful only when the kernel echoed the
         * magic into the 5th argument. Without it ZN concludes "no KernelSU
         * found" even though the version itself was copied correctly.
         */
        if (arg5 && copy_to_user(result, &reply_ok, sizeof(reply_ok))) {
            pr_err("prctl compat: GET_VERSION reply copy err\n");
            return -EFAULT;
        }
        return 0;
    }
    case CMD_GET_MANAGER_UID: {
        /* ZN needs the manager appid to keep it out of the denylist. */
        u32 manager_uid = 0;

        if (ksu_is_manager_appid_valid()) {
            manager_uid = (u32)ksu_get_manager_appid() * (u32)KSU_PER_USER_RANGE;
        }

        if (!arg3) {
            pr_err("prctl compat: GET_MANAGER_UID null arg\n");
            return -EINVAL;
        }
        if (copy_to_user((void __user *)arg3, &manager_uid, sizeof(manager_uid))) {
            pr_err("prctl compat: GET_MANAGER_UID copy err\n");
            return -EFAULT;
        }
        if (arg5 && copy_to_user(result, &reply_ok, sizeof(reply_ok))) {
            pr_err("prctl compat: GET_MANAGER_UID reply copy err\n");
            return -EFAULT;
        }
        return 0;
    }
    case CMD_UID_GRANTED_ROOT:
    case CMD_UID_SHOULD_UMOUNT: {
        uid_t target_uid = (uid_t)arg3;
        bool allow = false;
        if (arg2 == CMD_UID_GRANTED_ROOT) {
            allow = ksu_is_allow_uid(target_uid);
        } else {
            allow = ksu_uid_should_umount(target_uid);
        }
        if (copy_to_user((void __user *)arg4, &allow, sizeof(allow))) {
            pr_err("prctl compat: allow copy err\n");
            return -EFAULT;
        }
        if (copy_to_user(result, &reply_ok, sizeof(reply_ok))) {
            pr_err("prctl compat: reply copy err\n");
            return -EFAULT;
        }
        return 0;
    }
    default:
        /* Unrecognized legacy command: swallow it, as upstream did. */
        return 0;
    }
}

long __nocfi ksu_hook_prctl(int orig_nr, const struct pt_regs *regs)
{
    int option = (int)PT_REGS_PARM1(regs);
    unsigned long arg2;
    unsigned long arg3;
    unsigned long arg4;
    unsigned long arg5;

    /* Not our magic: hand it to the real prctl syscall. */
    if (option != KERNEL_SU_OPTION) {
        return ksu_syscall_table[orig_nr](regs);
    }

    arg2 = (unsigned long)PT_REGS_PARM2(regs);
    arg3 = (unsigned long)PT_REGS_PARM3(regs);
    arg4 = (unsigned long)PT_REGS_SYSCALL_PARM4(regs);
    arg5 = (unsigned long)PT_REGS_PARM5(regs);

    // 这个 ABI 只有只读查询（GET_VERSION / UID_GRANTED_ROOT / UID_SHOULD_UMOUNT），
    // 必须对任意进程开放：Zygisk Next 在 app 进程（已 setuid，非 root、非管理器）
    // 里查询 denylist 时若被拦截，denylist 就会整体失效，表现为「无法识别 root 管理器」。
    return ksu_handle_prctl(arg2, arg3, arg4, arg5);
}
