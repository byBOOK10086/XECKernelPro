// SPDX-License-Identifier: GPL-2.0-only
/*
 * APatch / KernelPatch supercall 兼容层（arm64）。
 *
 * 为什么需要它：APatch / KernelPatch 用户在 syscall 45 上讲话（arm64 是 brk，
 * x86_64 是 truncate）。这个文件补上应答端，让它们能管理 SU 允许列表、超键和
 * kstorage KV 存储，而不必改用户态。
 *
 * 安全设计（与上游的差别，都是刻意收紧的）：
 *   1. 只有 arg1 的 [31:16] 命中已知魔数（0x1158 / 0x2026）**且**低 16 位落在
 *      我们实现的命令集里才拦截；其余情况一律调用原始 syscall 表项放行真实
 *      brk/truncate —— 真实 brk 只带一个参数，x1 是残留值，必须保证它不会被
 *      误判（上游只取低 16 位，我们额外要求魔数命中）。
 *   2. 凭据不足时同样放行原 syscall，而不是回一个只有兼容内核才有的错误码，
 *      免得凭空多一个"这是 APatch 兼容内核"的探测指纹。
 *   3. 不实现 PANIC / TEST / rehook 这类危险或纯指纹命令；内核模块装载面已从
 *      本内核移除，所以那些命令也按未实现放行。
 */
#include <linux/errno.h>
#include <linux/kernel.h>
#include <linux/ptrace.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/utsname.h>
#include <linux/version.h>

#include "klog.h" // IWYU pragma: keep
#include "hook/syscall_hook.h"
#include "apatch/auth.h"
#include "apatch/compat.h"
#include "apatch/scdefs.h"
#include "apatch/kstorage.h"
#include "apatch/accctl.h"
#include "apatch/su_sc.h"
#include "apatch/suallow.h"

#if !defined(__aarch64__)
/*
 * 只有 arm64 的 syscall 45 是我们要应答的 supercall；x86_64 上 45 是 truncate，
 * 绝不能被拦。其它架构下兼容层整体退化成空实现。
 */
int ksu_apatch_compat_init(void)
{
    return 0;
}

void ksu_apatch_compat_exit(void)
{
}
#else

/* SU 允许列表（kstorage group 0/1）与凭据切换都在本目录实现；帮助函数
 * 直接调用，不在本层之外转发。 */

static syscall_fn_t sc_orig_syscall45;

/* 只把这些命令当成 supercall；其它一律放行原 syscall。
 *
 * SU / kstorage 这两族是 APatch 方言的实体（允许列表 + 凭据），由本层直接
 * 应答；不存在的命令回 -ENOSYS，不落回原 truncate —— 与 KernelPatch 原生
 * 内核的 truncate 通道行为一致。PANIC/TEST 一类不实现，也不应答。 */
static bool sc_cmd_supported(u16 cmd)
{
    switch (cmd) {
    case SC_CMD_HELLO:
    case SC_CMD_BUILD_TIME:
    case SC_CMD_KERNELPATCH_VER:
    case SC_CMD_KERNEL_VER:
    case SC_CMD_SKEY_GET:
    case SC_CMD_SKEY_SET:
    case SC_CMD_SU:
    case SC_CMD_SU_TASK:
    case SC_CMD_SU_GRANT_UID:
    case SC_CMD_SU_REVOKE_UID:
    case SC_CMD_SU_NUMS:
    case SC_CMD_SU_LIST:
    case SC_CMD_SU_PROFILE:
    case SC_CMD_SU_GET_PATH:
    case SC_CMD_SU_RESET_PATH:
    case SC_CMD_KSTORAGE_ALLOC_GROUP:
    case SC_CMD_KSTORAGE_WRITE:
    case SC_CMD_KSTORAGE_READ:
    case SC_CMD_KSTORAGE_LIST_IDS:
    case SC_CMD_KSTORAGE_REMOVE:
    case SC_CMD_KSTORAGE_REMOVE_GROUP:
        return true;
    default:
        return false;
    }
}

static long sc_copy_out(char __user *dst, int dstlen, const char *src, int len)
{
    if (!dst || dstlen <= 0)
        return -EINVAL;
    if (len > dstlen)
        return -ENOBUFS;
    if (len <= 0)
        return 0; /* 没有内容也算成功 */

    return copy_to_user(dst, src, len) ? -EFAULT : len;
}

/*
 * 命令分派。返回 true 表示"这是给我们的 supercall，结果写在 *out"；
 * 返回 false 表示按原 syscall 放行。
 */
static bool sc_dispatch(u16 magic, u64 key, u16 cmd, u64 a1, u64 a2, u64 a3, u64 a4, long *out)
{
    enum sc_cred cred = sc_cred_of(key);
    bool admin = (cred == SC_CRED_ADMIN);

    /*
	 * 拿不到凭据时放行原 syscall：既不给探测者指纹，也不制造"兼容内核专属
	 * 错误码"这种可枚举特征。
	 */
    if (cred == SC_CRED_NONE)
        return false;

    /* HELLO 与版本查询是只读的，SU 档也放行 */
    switch (cmd) {
    case SC_CMD_HELLO:
        pr_info("apatch-compat: hello from %s client\n", magic == SC_MAGIC_KPNEXT ? "KPatch-Next" : "KernelPatch");
        *out = magic == SC_MAGIC_KPNEXT ? (long)SC_HELLO_MAGIC_KPNEXT : (long)SC_HELLO_MAGIC_KP;
        return true;
    case SC_CMD_KERNELPATCH_VER:
        *out = SC_REPORTED_KPVER;
        return true;
    case SC_CMD_KERNEL_VER:
        *out = LINUX_VERSION_CODE;
        return true;
    case SC_CMD_BUILD_TIME: {
        char buf[80];

        /* UTS_RELEASE 在部分内核树里不可直接引用，用运行时 utsname 更稳 */
        scnprintf(buf, sizeof(buf), "%s (%s)", init_uts_ns.name.release, KSU_VERSION_FULL);
        *out = sc_copy_out((char __user *)a1, (int)a2, buf, strlen(buf) + 1);
        return true;
    }
    default:
        break;
    }

    /* 写侧（SU / kstorage / SKEY）只给 ADMIN */
    if (!admin)
        return false;

    switch (cmd) {
    case SC_CMD_SKEY_GET:
        *out = sc_auth_get_key((char __user *)a1, (int)a2);
        return true;
    case SC_CMD_SKEY_SET:
        *out = sc_auth_set_key((const char __user *)a1);
        return true;
    case SC_CMD_SU:
        *out = kp_su_sc((const struct su_profile __user *)a1);
        return true;
    case SC_CMD_SU_TASK:
        *out = kp_su_task_sc((pid_t)a1, (const struct su_profile __user *)a2);
        return true;
    case SC_CMD_SU_GRANT_UID:
        *out = kp_su_grant_uid_sc((const struct su_profile __user *)a1);
        return true;
    case SC_CMD_SU_REVOKE_UID:
        *out = kp_su_revoke_uid_sc((uid_t)a1);
        return true;
    case SC_CMD_SU_NUMS:
        *out = kp_su_allow_uid_nums_sc();
        return true;
    case SC_CMD_SU_LIST:
        *out = kp_su_allow_uid_list_sc((uid_t __user *)a1, (int)a2);
        return true;
    case SC_CMD_SU_PROFILE:
        *out = kp_su_allow_uid_profile_sc((uid_t)a1, (struct su_profile __user *)a2);
        return true;
    case SC_CMD_SU_GET_PATH:
        *out = kp_su_get_path_sc((char __user *)a1, (int)a2);
        return true;
    case SC_CMD_SU_RESET_PATH:
        *out = kp_su_reset_path_sc((const char __user *)a1);
        return true;
    case SC_CMD_KSTORAGE_ALLOC_GROUP:
        if (a1 || a2 || a3 || a4)
            return false;
        *out = kp_kstorage_alloc_group();
        return true;
    case SC_CMD_KSTORAGE_WRITE:
        *out = kp_kstorage_write((int)a1, (long)a2, (void __user *)a3, 0, (int)a4, true);
        return true;
    case SC_CMD_KSTORAGE_READ:
        *out = kp_kstorage_read((int)a1, (long)a2, (void __user *)a3, 0, (int)a4, true);
        return true;
    case SC_CMD_KSTORAGE_LIST_IDS:
        *out = kp_kstorage_list_ids((int)a1, (long __user *)a2, (int)a3, true);
        return true;
    case SC_CMD_KSTORAGE_REMOVE:
        if (a4)
            return false;
        *out = kp_kstorage_remove((int)a1, (long)a2);
        return true;
    case SC_CMD_KSTORAGE_REMOVE_GROUP: {
        /* kstorage 一次只删一个 key，组清空靠 list_ids 逐个排干。 */
        long ids[64];
        int gid = (int)a1;
        long rc = 0, i, n;

        if (a2 || a3 || a4)
            return false;
        if (gid < 0 || gid >= KP_KSTORAGE_MAX_GROUP_NUM)
            return false;
        do {
            n = kp_kstorage_list_ids(gid, ids, ARRAY_SIZE(ids), false);
            if (n < 0) {
                *out = n;
                return true;
            }
            for (i = 0; i < n; i++) {
                rc = kp_kstorage_remove(gid, ids[i]);
                if (rc) {
                    *out = rc;
                    return true;
                }
            }
        } while (n == ARRAY_SIZE(ids));
        *out = 0;
        return true;
    }
    default:
        /* 未实现的命令一律放行原 syscall：不给探测者"兼容内核专属错误码"
         * 这种可枚举指纹。 */
        return false;
    }
}

static long __nocfi ksu_apatch_syscall45(const struct pt_regs *regs)
{
    u64 key = regs->regs[0];
    u64 word = regs->regs[1];
    u16 magic = (word >> 16) & 0xFFFF;
    u16 cmd = word & 0xFFFF;
    long out = 0;

    if ((magic != SC_MAGIC_KP && magic != SC_MAGIC_KPNEXT) || !sc_cmd_supported(cmd))
        return sc_orig_syscall45(regs);

    if (sc_dispatch(magic, key, cmd, regs->regs[2], regs->regs[3], regs->regs[4], regs->regs[5], &out))
        return out;

    /* 凭据不足 / 未实现：按原 syscall 放行 */
    return sc_orig_syscall45(regs);
}

int ksu_apatch_compat_init(void)
{
    if (!IS_ENABLED(CONFIG_KSU_APATCH_COMPAT))
        return 0;

    if (!ksu_syscall_table) {
        pr_warn("apatch-compat: syscall table unavailable, disabled\n");
        return -ENOENT;
    }

    sc_auth_init();
    /* SU 方言的底座：KV 存储 → 凭据切换 → 允许列表。任一步失败只让对应命令
     * 退化成错误码，不影响内核模块本身加载（这是可选兼容层）。 */
    if (kp_kstorage_init())
        pr_warn("apatch-compat: kstorage init failed, SU supercalls degrade\n");
    else {
        if (kp_accctl_init())
            pr_warn("apatch-compat: accctl init failed, su grant degrades\n");
        if (kp_sucompat_init())
            pr_warn("apatch-compat: su allowlist init failed\n");
        /* 旧引擎在 core.c 里调过一次：把 /data/adb/ap/{su_path,package_config}
         * 的既有配置导入允许列表。设备上没这俩文件时直接失败，忽略即可。 */
        if (kp_su_load_config() < 0)
            pr_debug("apatch-compat: no APatch config to import\n");
    }
    ksu_syscall_table_hook(SC_NR_SUPERCALL, ksu_apatch_syscall45, &sc_orig_syscall45);
    pr_info("apatch-compat: syscall %d hooked (kpver=0x%04x)\n", SC_NR_SUPERCALL, SC_REPORTED_KPVER);
    return 0;
}

void ksu_apatch_compat_exit(void)
{
    if (!IS_ENABLED(CONFIG_KSU_APATCH_COMPAT))
        return;

    ksu_syscall_table_unhook(SC_NR_SUPERCALL);
    pr_info("apatch-compat: syscall %d unhooked\n", SC_NR_SUPERCALL);
}

#endif /* __aarch64__ */
