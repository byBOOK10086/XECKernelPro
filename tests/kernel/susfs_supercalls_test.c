/* SPDX-License-Identifier: GPL-2.0 */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define __user
#define __nocfi
#define KSU_INSTALL_MAGIC1 0xDEADBEEF
#define SUSFS_MAGIC 0xFAFAFAFA
#define GFP_ATOMIC 0
#define TWA_RESUME 0
#define THIS_MODULE 0
#define pr_warn(...) ((void)0)
#define SUSFS_LOGI(...) ((void)0)
struct pt_regs { uintptr_t regs[31]; uintptr_t pc; };
struct kprobe { int unused; };
struct callback_head { void (*func)(struct callback_head *); };
struct susfs_tw { struct callback_head cb; unsigned int cmd; void *payload; };
static struct { unsigned int val; } uid;
#define current_uid() (uid)
static int current, original_calls, command_checks, module_refs, queued;
static bool fail_alloc, fail_queue;
static struct callback_head *pending;
static long original(const struct pt_regs *regs) { original_calls++; return -EPERM; }
static long (*reboot_orig)(const struct pt_regs *) = original;
static bool susfs_cmd_handled(unsigned int cmd) { command_checks++; return cmd == 123; }
static bool try_module_get(int module) { module_refs++; return true; }
static void module_put(int module) { module_refs--; }
static void *kzalloc(size_t size, int flags) { return fail_alloc ? NULL : calloc(1, size); }
static void kfree(void *p) { free(p); }
static void susfs_tw_func(struct callback_head *cb) { }
static int task_work_add(int task, struct callback_head *cb, int mode)
{
    if (fail_queue) return -ESRCH;
    assert(!pending);
    pending = cb;
    queued++;
    return 0;
}

/* KERNEL_FUNCTIONS */

static void reset(void)
{
    free(pending);
    pending = NULL;
    uid.val = 10000;
    original_calls = command_checks = module_refs = queued = 0;
    fail_alloc = fail_queue = false;
}
int main(void)
{
    struct pt_regs user = { .regs = {KSU_INSTALL_MAGIC1, SUSFS_MAGIC, 123} };
    struct pt_regs entry = { .regs = {(uintptr_t)&user}, .pc = 44 };
    struct kprobe probe = {0};
    reset();
    assert(reboot_wrapper(&user) == -EPERM);
    assert(original_calls == 1 && command_checks == 0 && module_refs == 0 && queued == 0);
    /* Even an unusable register-wrapper pointer is untouched for non-root. */
    entry.regs[0] = 1;
    assert(reboot_pre(&probe, &entry) == 0);
    assert(entry.regs[0] == 1 && entry.pc == 44 && command_checks == 0);

    reset();
    uid.val = 0;
    assert(reboot_wrapper(&user) == 0);
    assert(queued == 1 && module_refs == 1 && original_calls == 0);
    assert(((struct susfs_tw *)pending)->cmd == 123);
    reset();
    uid.val = 0;
    entry.regs[0] = (uintptr_t)&user;
    entry.regs[30] = 88;
    assert(reboot_pre(&probe, &entry) == 1);
    assert(entry.regs[0] == 0 && entry.pc == 88 && queued == 1 && module_refs == 1);

    reset();
    uid.val = 0;
    fail_alloc = true;
    assert(reboot_wrapper(&user) == -EPERM);
    assert(module_refs == 0 && queued == 0);
    reset();
    uid.val = 0;
    fail_queue = true;
    entry.regs[0] = (uintptr_t)&user;
    entry.pc = 44;
    assert(reboot_pre(&probe, &entry) == 0);
    assert(module_refs == 0 && queued == 0 && entry.pc == 44);
    reset();
    uid.val = 0;
    user.regs[2] = 456;
    assert(reboot_wrapper(&user) == -EPERM);
    assert(module_refs == 0 && queued == 0);
    reset();
    puts("SUSFS native fallback, kprobe refusal, task-work and module references passed");
    return 0;
}
