// SPDX-License-Identifier: GPL-2.0-only
/*
 * APatch / KernelPatch supercall 兼容层（arm64）。
 *
 * 为什么需要它：本仓库用户态早就在等这套 ABI —— ksud 内置的 KPM 客户端
 * （Engine::Embedded，XOR "xdcv1" 解到 /dev/.kpmd 执行）说的就是 KPatch-Next
 * 的 supercall 方言，而内核侧从来没人应答那个 syscall。这个文件补上应答端：
 * 用户态不用改，KPM 生态（kpmd / APatch 工具链）能直接对上话。
 *
 * 安全设计（与上游的差别，都是刻意收紧的）：
 *   1. 只有 arg1 的 [31:16] 命中已知魔数（0x1158 / 0x2026）**且**低 16 位落在
 *      我们实现的命令集里才拦截；其余情况一律调用原始 syscall 表项放行真实
 *      brk/truncate —— 真实 brk 只带一个参数，x1 是残留值，必须保证它不会被
 *      误判（上游只取低 16 位，我们额外要求魔数命中）。
 *   2. 凭据不足时同样放行原 syscall，而不是回一个只有兼容内核才有的错误码，
 *      免得凭空多一个"这是 APatch 兼容内核"的探测指纹。
 *   3. 不实现 PANIC / TEST / rehook 这类危险或纯指纹命令。
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
#include "kpmcore/kpm_engine.h"

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

/* KPM 引擎的桥接函数（kpm/kpm.c 导出；本兼容层只调用，不改它的语义） */
extern void sukisu_kpm_load_module_path(const char *path, const char *args, void *ptr, int *result);
extern void sukisu_kpm_unload_module(const char *name, void *ptr, int *result);
extern void sukisu_kpm_num(int *result);
extern void sukisu_kpm_list_apatch(void *out, int bufferSize, int *result);
extern void sukisu_kpm_info_apatch(const char *name, char *buf, int bufferSize, int *result);
extern void sukisu_kpm_control_out(const char *name, const char *args, long arg_len, char __user *out, int outlen,
                                   int *result);

static syscall_fn_t sc_orig_syscall45;

/* 只把这些命令当成 supercall；其它一律放行原 syscall。
 *
 * kpmcore 引擎（CONFIG_KSU_KPM_AP）活跃时放宽到整个 scdefs 命令窗：SU 族 /
 * kstorage / 特性控制交给 kp_handle_supercall 分发（未实现命令回 -ENOSYS，
 * 不会落到原 truncate —— 与 KernelPatch 原生内核的 truncate 通道行为一致）；
 * PANIC/TEST 在引擎分发里同样回 -ENOSYS，不实现任何 panic 面。 */
static bool sc_cmd_supported(u16 cmd)
{
    if (kp_engine_active() && cmd >= SC_CMD_HELLO && cmd <= SC_CMD_MAX)
        return true;
    switch (cmd) {
    case SC_CMD_HELLO:
    case SC_CMD_BUILD_TIME:
    case SC_CMD_KERNELPATCH_VER:
    case SC_CMD_KERNEL_VER:
    case SC_CMD_SKEY_GET:
    case SC_CMD_SKEY_SET:
    case SC_CMD_KPM_LOAD:
    case SC_CMD_KPM_UNLOAD:
    case SC_CMD_KPM_CONTROL:
    case SC_CMD_KPM_NUMS:
    case SC_CMD_KPM_LIST:
    case SC_CMD_KPM_INFO:
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

static long sc_kpm_load(u64 path_ptr, u64 args_ptr, u64 result_ptr)
{
    struct sc_kpm_load_result res;
    char *path = NULL;
    char *args = NULL;
    int rc = -EINVAL;
    long n;

    if (!path_ptr)
        return -EINVAL;

    /* 路径/参数各 1KB，走堆：内核栈上的 2KB 大帧会被 -Wframe-larger-than 盯上 */
    path = kzalloc(SC_KPM_PATH_LEN, GFP_KERNEL);
    args = kzalloc(SC_KPM_ARGS_LEN, GFP_KERNEL);
    if (!path || !args) {
        rc = -ENOMEM;
        goto out;
    }

    n = strncpy_from_user(path, (const char __user *)path_ptr, SC_KPM_PATH_LEN - 1);
    if (n <= 0)
        goto out;

    if (args_ptr) {
        n = strncpy_from_user(args, (const char __user *)args_ptr, SC_KPM_ARGS_LEN - 1);
        if (n < 0)
            goto out;
    }

    sukisu_kpm_load_module_path(path, args[0] ? args : NULL, NULL, &rc);

    /* 回传结构：magic 与 size 都对上才写回（与上游 module.c 的判定一致） */
    if (result_ptr) {
        if (!copy_from_user(&res, (void __user *)result_ptr, sizeof(res)) && res.magic == SC_LOAD_RESULT_MAGIC &&
            res.size >= sizeof(res)) {
            res.code = rc;
            if (rc != 0)
                scnprintf(res.message, sizeof(res.message), "kpm load failed: %d", rc);
            else
                res.message[0] = '\0';
            if (copy_to_user((void __user *)result_ptr, &res, sizeof(res))) {
                rc = -EFAULT;
                goto out;
            }
        }
    }

out:
    kfree(path);
    kfree(args);
    return rc;
}

static long sc_kpm_unload(u64 name_ptr)
{
    char name[SC_KPM_NAME_LEN + 1] = { 0 };
    int rc = -EINVAL;
    long n;

    if (!name_ptr)
        return -EINVAL;

    n = strncpy_from_user(name, (const char __user *)name_ptr, sizeof(name) - 1);
    if (n <= 0)
        return -EINVAL;

    sukisu_kpm_unload_module(name, NULL, &rc);
    return rc;
}

static long sc_kpm_list(u64 buf_ptr, u64 buflen)
{
    char *buf;
    int len = 0;
    long rc;
    int want = (int)buflen;

    if (!buf_ptr || want <= 0)
        return -EINVAL;
    if (want > SC_LIST_BUF_LEN)
        want = SC_LIST_BUF_LEN;

    /* 上游用 4K 内核栈；我们走堆，避免大栈帧被 -Wframe-larger-than 拦下 */
    buf = kzalloc(SC_LIST_BUF_LEN, GFP_KERNEL);
    if (!buf)
        return -ENOMEM;

    sukisu_kpm_list_apatch(buf, SC_LIST_BUF_LEN, &len);
    if (len < 0)
        rc = len;
    else
        rc = sc_copy_out((char __user *)buf_ptr, want, buf, len);

    kfree(buf);
    return rc;
}

static long sc_kpm_info(u64 name_ptr, u64 buf_ptr, u64 buflen)
{
    char name[SC_KPM_INFO_NAME_LEN + 1] = { 0 };
    char *buf;
    int len = 0;
    long rc, n;

    if (!name_ptr || !buf_ptr || (int)buflen <= 0)
        return -EINVAL;

    n = strncpy_from_user(name, (const char __user *)name_ptr, sizeof(name) - 1);
    if (n <= 0)
        return -EINVAL;

    buf = kzalloc(SC_INFO_BUF_LEN, GFP_KERNEL);
    if (!buf)
        return -ENOMEM;

    sukisu_kpm_info_apatch(name, buf, SC_INFO_BUF_LEN, &len);
    if (len <= 0)
        rc = -ENOENT;
    else
        rc = sc_copy_out((char __user *)buf_ptr, (int)buflen, buf, len);

    kfree(buf);
    return rc;
}

static long sc_kpm_control(u64 name_ptr, u64 args_ptr, u64 out_ptr, u64 outlen)
{
    char name[SC_KPM_NAME_LEN + 1] = { 0 };
    char *args;
    int rc = -EINVAL;
    long n;

    if (!name_ptr || !args_ptr || !out_ptr || (int)outlen <= 0)
        return -EINVAL;

    n = strncpy_from_user(name, (const char __user *)name_ptr, sizeof(name) - 1);
    if (n <= 0)
        return -EINVAL;

    args = kzalloc(SC_KPM_ARGS_LEN, GFP_KERNEL);
    if (!args)
        return -ENOMEM;

    n = strncpy_from_user(args, (const char __user *)args_ptr, SC_KPM_ARGS_LEN - 1);
    if (n <= 0) {
        kfree(args);
        return -EINVAL;
    }

    sukisu_kpm_control_out(name, args, n, (char __user *)out_ptr, (int)outlen, &rc);
    kfree(args);
    return rc;
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

    /* 写侧（KPM / SKEY）只给 ADMIN */
    if (!admin)
        return false;

    switch (cmd) {
    case SC_CMD_KPM_LOAD:
        *out = sc_kpm_load(a1, a2, a3);
        return true;
    case SC_CMD_KPM_UNLOAD:
        *out = sc_kpm_unload(a1);
        return true;
    case SC_CMD_KPM_CONTROL:
        *out = sc_kpm_control(a1, a2, a3, a4);
        return true;
    case SC_CMD_KPM_NUMS: {
        int num = 0;

        sukisu_kpm_num(&num);
        *out = num;
        return true;
    }
    case SC_CMD_KPM_LIST:
        *out = sc_kpm_list(a1, a2);
        return true;
    case SC_CMD_KPM_INFO:
        *out = sc_kpm_info(a1, a2, a3);
        return true;
    case SC_CMD_SKEY_GET:
        *out = sc_auth_get_key((char __user *)a1, (int)a2);
        return true;
    case SC_CMD_SKEY_SET:
        *out = sc_auth_set_key((const char __user *)a1);
        return true;
    default:
        /* 引擎活跃：scdefs 其余命令（KPM 族已在上方经桥接函数路由、SU 族、
         * kstorage、CONTROL_FEATURE）走 kpmcore 分发表；SKEY_* 由本层持有
         * （超键存储在 policy/superkey.c），永远不进引擎。 */
        if (kp_engine_active()) {
            *out = kp_handle_supercall(cmd, a1, a2, a3, a4);
            return true;
        }
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
