/* SPDX-License-Identifier: GPL-2.0 */
/* Check real entry functions with observable mocks, not wall-clock thresholds. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define __user
#define __nocfi
#define GFP_ATOMIC 0
#define TWA_RESUME 0
#define KSU_INSTALL_MAGIC1 0xDEADBEEF
#define KSU_INSTALL_MAGIC2 0xCAFEBABE
#define KERNEL_SU_OPTION 0xdeadbeef
#define SUSFS_MAGIC 0xFAFAFAFA
#define CMD_SUSFS_ENABLE_AVC_LOG_SPOOFING 1
#define CMD_SUSFS_SHOW_ENABLED_FEATURES 2
#define CMD_SUSFS_SHOW_VARIANT 3
#define CMD_SUSFS_SHOW_VERSION 4
#define pr_warn(...) ((void)0)

struct pt_regs { unsigned long args[5]; };
struct kprobe { int unused; };
struct callback_head { void (*func)(struct callback_head *); };
struct ksu_install_fd_tw { struct callback_head cb; int *outp; };
static bool trusted;
static unsigned int uid;
static int gate_calls, argument_reads, allocations, queued, original_calls, compat_calls, susfs_calls;
static struct callback_head *pending;
static int current;
static struct { unsigned int val; } fake_uid;
#define current_uid() (fake_uid.val = uid, fake_uid)

static bool __attribute__((unused)) ksu_is_trusted_abi_client(void)
{
    gate_calls++;
    return trusted;
}
static unsigned long argument(const struct pt_regs *regs, int index)
{
    argument_reads++;
    return regs->args[index];
}
#define PT_REAL_REGS(regs) (regs)
#define PT_REGS_SYSCALL_PARM1(regs) argument(regs, 0)
#define PT_REGS_PARM1(regs) argument(regs, 0)
#define PT_REGS_PARM2(regs) argument(regs, 1)
#define PT_REGS_PARM3(regs) argument(regs, 2)
#define PT_REGS_SYSCALL_PARM4(regs) argument(regs, 3)
#define PT_REGS_PARM5(regs) argument(regs, 4)
static void *kzalloc(size_t length, int flags)
{
    allocations++;
    return calloc(1, length);
}
static void kfree(void *p) { free(p); }
static void ksu_install_fd_tw_func(struct callback_head *cb) { (void)cb; }
static int task_work_add(int task, struct callback_head *cb, int mode)
{
    assert(!pending);
    pending = cb;
    queued++;
    return 0;
}
static long original_prctl(const struct pt_regs *regs)
{
    original_calls++;
    return regs->args[0] == 42 ? 77 : -EINVAL;
}
static long (*ksu_syscall_table[])(const struct pt_regs *) = { original_prctl };
static long ksu_handle_prctl(unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5)
{
    compat_calls++;
    return 123;
}
static int ksu_supercall_reboot_handler(void **arg) { queued++; return 0; }
static void susfs_set_avc_log_spoofing(void **arg) { susfs_calls++; }
static void susfs_show_variant(void **arg) { susfs_calls++; }
static void susfs_show_version(void **arg) { susfs_calls++; }
static void susfs_get_enabled_features(void **arg) { susfs_calls++; }

/* KERNEL_FUNCTIONS */

static void reset(bool allow)
{
    free(pending);
    pending = NULL;
    trusted = allow;
    uid = 10000;
    gate_calls = argument_reads = allocations = queued = original_calls = compat_calls = susfs_calls = 0;
}
int main(void)
{
    struct pt_regs regs = {{0}};
    struct kprobe probe = {0};
    void *arg = NULL;
    reset(false);
    regs.args[0] = KSU_INSTALL_MAGIC1;
    regs.args[1] = KSU_INSTALL_MAGIC2;
    assert(reboot_handler_pre(&probe, &regs) == 0);
#ifndef CONFIG_KSU_OPEN_LEGACY_ABI
    assert(gate_calls == 1 && argument_reads == 0 && allocations == 0 && queued == 0);
    regs.args[0] = 0;
    regs.args[1] = 0;
    assert(reboot_handler_pre(&probe, &regs) == 0);
    assert(gate_calls == 2 && argument_reads == 0 && allocations == 0 && queued == 0);
#else
    assert(gate_calls == 0 && allocations == 1 && queued == 1);
#endif

    reset(true);
    regs.args[0] = KSU_INSTALL_MAGIC1;
    regs.args[1] = KSU_INSTALL_MAGIC2;
    assert(reboot_handler_pre(&probe, &regs) == 0);
    assert(allocations == 1 && queued == 1 && pending);

    reset(false);
    regs.args[0] = KERNEL_SU_OPTION;
#ifndef CONFIG_KSU_OPEN_LEGACY_ABI
    assert(ksu_hook_prctl(0, &regs) == -EINVAL);
    assert(gate_calls == 1 && argument_reads == 0 && compat_calls == 0 && original_calls == 1);
    regs.args[0] = KERNEL_SU_OPTION - 1;
    assert(ksu_hook_prctl(0, &regs) == -EINVAL);
    assert(gate_calls == 2 && argument_reads == 0 && compat_calls == 0 && original_calls == 2);
#else
    assert(ksu_hook_prctl(0, &regs) == 123);
    assert(gate_calls == 0 && compat_calls == 1 && original_calls == 0);
#endif
    regs.args[0] = 42;
    assert(ksu_hook_prctl(0, &regs) == 77);

    reset(true);
    regs.args[0] = KERNEL_SU_OPTION;
    assert(ksu_hook_prctl(0, &regs) == 123);
    assert(compat_calls == 1 && original_calls == 0);

    reset(false);
#ifndef CONFIG_KSU_OPEN_LEGACY_ABI
    assert(ksu_handle_sys_reboot(KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &arg) == -EINVAL);
    assert(ksu_handle_sys_reboot(0, 0, 0, &arg) == -EINVAL);
    assert(gate_calls == 2 && queued == 0 && susfs_calls == 0);
#else
    assert(ksu_handle_sys_reboot(KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &arg) == 0);
    assert(gate_calls == 0 && queued == 1);
#endif

    reset(true);
    assert(ksu_handle_sys_reboot(KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &arg) == 0);
    assert(queued == 1);
    assert(ksu_handle_sys_reboot(KSU_INSTALL_MAGIC1, SUSFS_MAGIC, CMD_SUSFS_SHOW_VERSION, &arg) == -EINVAL);
    assert(susfs_calls == 0);
    uid = 0;
    assert(ksu_handle_sys_reboot(KSU_INSTALL_MAGIC1, SUSFS_MAGIC, CMD_SUSFS_SHOW_VERSION, &arg) == 0);
    assert(susfs_calls == 1);
    reset(false);
    puts("Legacy authorization, native fallback, and root-only SUSFS checks passed");
    return 0;
}
