// SPDX-License-Identifier: GPL-2.0
/* Native kernel ABI boundary: a KPM never embeds kretprobe or pt_regs layouts. */
#include <linux/atomic.h>
#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/kprobes.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/rcupdate.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/wait.h>
#include <linux/version.h>
#include "include/kp_lkm.h"
#include "symbol_resolver.h"
#include "path_hook.h"

struct kp_path_instance {
    char path[96];
    unsigned int uid;
};
struct kp_path_hook {
    struct list_head list;
    struct kretprobe probe;
    long (*filter)(const char *, unsigned int, long, void *);
    void *context;
    char symbol[32];
    atomic_t callbacks;
    wait_queue_head_t drained;
};
static LIST_HEAD(kp_path_hooks);
static DEFINE_MUTEX(kp_path_hooks_lock);
static long (*kp_path_copy)(char *, const char __user *, long);

__nocfi __attribute__((no_sanitize("cfi"), __noinline__))
static long kp_path_filter(struct kp_path_hook *hook, struct kp_path_instance *data,
                           long result)
{
    return hook->filter(data->path, data->uid, result, hook->context);
}
__nocfi __attribute__((no_sanitize("cfi"), __noinline__))
static long kp_path_capture(char *dst, const char __user *src, long size)
{
    return kp_path_copy(dst, src, size);
}
static int kp_path_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct kp_path_instance *data = (void *)ri->data;
    long copied;
    /* Only filename_arg=1 is accepted. Capture user bytes without sleeping or
     * taking a fault in the probe's atomic context. Truncated paths are skipped. */
    copied = kp_path_capture(data->path, (const char __user *)regs->regs[1],
                             sizeof(data->path));
    if (copied <= 0 || copied >= sizeof(data->path))
        return 1;
    data->path[sizeof(data->path) - 1] = '\0';
    data->uid = __kuid_val(current_uid());
    return 0;
}
static int kp_path_return(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 11, 0)
    struct kretprobe *probe = ri->rp;
#else
    struct kretprobe *probe = get_kretprobe(ri);
#endif
    struct kp_path_hook *hook = container_of(probe, struct kp_path_hook, probe);
    struct kp_path_instance *data = (void *)ri->data;
    atomic_inc(&hook->callbacks);
    regs->regs[0] = kp_path_filter(hook, data, (long)regs_return_value(regs));
    if (atomic_dec_and_test(&hook->callbacks))
        wake_up_all(&hook->drained);
    return 0;
}
unsigned int kpm_path_hook_abi_v1(void)
{
    return 1;
}
int kpm_path_hook_register_v1(const char *symbol, unsigned int filename_arg,
    long (*filter)(const char *, unsigned int, long, void *), void *context, void **handle)
{
    struct kp_path_hook *hook, *pos;
    int error;
    if (!handle)
        return -EINVAL;
    *handle = NULL;
    if (!symbol || !filter || filename_arg != 1 ||
        (strcmp(symbol, "vfs_fstatat") && strcmp(symbol, "do_faccessat")))
        return -EINVAL;
    if (in_atomic() || irqs_disabled())
        return -EWOULDBLOCK;
    mutex_lock(&kp_path_hooks_lock);
    list_for_each_entry(pos, &kp_path_hooks, list) {
        if (!strcmp(pos->symbol, symbol)) {
            error = -EBUSY;
            goto out;
        }
    }
    if (!kp_path_copy)
        kp_path_copy = (void *)kp_resolve_symbol("strncpy_from_user_nofault");
    if (!kp_path_copy) {
        error = -EOPNOTSUPP;
        goto out;
    }
    hook = kzalloc(sizeof(*hook), GFP_KERNEL);
    if (!hook) {
        error = -ENOMEM;
        goto out;
    }
    strscpy(hook->symbol, symbol, sizeof(hook->symbol));
    hook->filter = filter;
    hook->context = context;
    atomic_set(&hook->callbacks, 0);
    init_waitqueue_head(&hook->drained);
    hook->probe.kp.symbol_name = hook->symbol;
    hook->probe.entry_handler = kp_path_entry;
    hook->probe.handler = kp_path_return;
    hook->probe.data_size = sizeof(struct kp_path_instance);
    hook->probe.maxactive = 128;
    error = register_kretprobe(&hook->probe);
    if (error) {
        kfree(hook);
        goto out;
    }
    list_add_tail(&hook->list, &kp_path_hooks);
    *handle = hook;
out:
    mutex_unlock(&kp_path_hooks_lock);
    return error;
}
/* Caller holds registration mutex and may sleep. Kernel unregister prevents
 * new callbacks, flushes native instances, then the explicit drain proves no
 * filter is executing before its KPM text/context may be released. */
static void kp_path_remove(struct kp_path_hook *hook)
{
    unregister_kretprobe(&hook->probe);
    synchronize_rcu();
    wait_event(hook->drained, !atomic_read(&hook->callbacks));
    list_del(&hook->list);
    kfree(hook);
}
void kpm_path_hook_unregister_v1(void *handle)
{
    struct kp_path_hook *hook, *next;
    if (!handle)
        return;
    /* A void ABI cannot report an unsafe atomic unregister: retain ownership
     * instead of freeing live callbacks. KPM lifecycle/control calls may sleep. */
    if (WARN_ON_ONCE(in_atomic() || irqs_disabled()))
        return;
    mutex_lock(&kp_path_hooks_lock);
    list_for_each_entry_safe(hook, next, &kp_path_hooks, list) {
        if (hook == handle) {
            kp_path_remove(hook);
            break;
        }
    }
    mutex_unlock(&kp_path_hooks_lock);
}
void kp_path_hooks_drain_owner(unsigned long start, unsigned long size)
{
    struct kp_path_hook *hook, *next;
    mutex_lock(&kp_path_hooks_lock);
    list_for_each_entry_safe(hook, next, &kp_path_hooks, list) {
        unsigned long callback = (unsigned long)hook->filter;
        if (callback >= start && callback - start < size)
            kp_path_remove(hook);
    }
    mutex_unlock(&kp_path_hooks_lock);
}
