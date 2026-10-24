#include <linux/atomic.h>
#include <linux/cred.h>
#include <linux/hashtable.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/rcupdate.h>
#include <linux/sched.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/uidgid.h>

#include "uts_spoof.h"
#include "infra/symbol_resolver.h"
#include "klog.h"

static void do_spoof_version(struct rw_semaphore *sem, struct uts_namespace *ns, const char *release,
                             const char *version)
{
    if (sem) {
        down_write(sem);
    } else {
        down_write(&uts_sem);
    }

    if (ns) {
        if (release && release[0] != '\0') {
            strscpy(ns->name.release, release, sizeof(ns->name.release));
        }
        if (version && version[0] != '\0') {
            strscpy(ns->name.version, version, sizeof(ns->name.version));
        }
    } else {
        if (release && release[0] != '\0') {
            strscpy(init_uts_ns.name.release, release, sizeof(init_uts_ns.name.release));
        }
        if (version && version[0] != '\0') {
            strscpy(init_uts_ns.name.version, version, sizeof(init_uts_ns.name.version));
        }
    }

    if (sem) {
        up_write(sem);
    } else {
        up_write(&uts_sem);
    }

    if (ns) {
        pr_info("spoofed version: %s, release: %s\n", ns->name.version, ns->name.release);
    } else {
        pr_info("spoofed version: %s, release: %s\n", init_uts_ns.name.version, init_uts_ns.name.release);
    }
}

void ksu_spoof_version(const char *spoof_release, const char *spoof_version)
{
    struct rw_semaphore *sem = (struct rw_semaphore *)find_kernel_symbol_exact("uts_sem");
    struct uts_namespace *ns = (struct uts_namespace *)find_kernel_symbol_exact("init_uts_ns");

    do_spoof_version(sem, ns, spoof_release, spoof_version);
}

int ksu_set_spoof_version(const char *release, const char *version)
{
    struct rw_semaphore *sem = (struct rw_semaphore *)find_kernel_symbol_exact("uts_sem");
    struct uts_namespace *ns = (struct uts_namespace *)find_kernel_symbol_exact("init_uts_ns");

    do_spoof_version(sem, ns, release, version);

    return 0;
}

/* ---- per-app uname identity (XECKernelPro extension, ioctl 115/116) ---- */

#define UTS_SPOOF_BUCKETS 8

struct uts_spoof_entry {
    u32 uid;
    char release[KSU_UTS_SPOOF_LEN];
    char version[KSU_UTS_SPOOF_LEN];
    struct hlist_node node;
    struct rcu_head rcu;
};

static DEFINE_HASHTABLE(uts_spoof_ht, UTS_SPOOF_BUCKETS);
static DEFINE_MUTEX(uts_spoof_lock);
/* Cheap early-out for the uname hook when no overrides exist. */
static atomic_t uts_spoof_count = ATOMIC_INIT(0);

static struct uts_spoof_entry *uts_spoof_lookup(uid_t uid)
{
    struct uts_spoof_entry *entry;
    hash_for_each_possible_rcu (uts_spoof_ht, entry, node, uid) {
        if (entry->uid == uid)
            return entry;
    }
    return NULL;
}

/*
 * Called from the uname syscall hook after the real syscall returned 0.
 * old_utsname and new_utsname share the sysname[65]/nodename[65] prefix,
 * so one pair of offsets serves both buffer layouts.
 */
void ksu_uts_spoof_rewrite(const char __user *buf)
{
    struct uts_spoof_entry *entry;

    if (!buf || !atomic_read(&uts_spoof_count))
        return;

    rcu_read_lock();
    entry = uts_spoof_lookup(current_uid().val);
    if (entry) {
        if (entry->release[0])
            copy_to_user(buf + offsetof(struct new_utsname, release), entry->release,
                         KSU_UTS_SPOOF_LEN);
        if (entry->version[0])
            copy_to_user(buf + offsetof(struct new_utsname, version), entry->version,
                         KSU_UTS_SPOOF_LEN);
    }
    rcu_read_unlock();
}

static void uts_spoof_free_rcu(struct rcu_head *rcu)
{
    struct uts_spoof_entry *entry = container_of(rcu, struct uts_spoof_entry, rcu);
    kfree(entry);
}

int ksu_uts_spoof_set(const void __user *arg)
{
    struct ksu_uts_spoof_cmd cmd;
    struct uts_spoof_entry *entry, *old;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    if (!memchr(cmd.release, '\0', KSU_UTS_SPOOF_LEN) ||
        !memchr(cmd.version, '\0', KSU_UTS_SPOOF_LEN))
        return -EINVAL;

    mutex_lock(&uts_spoof_lock);
    old = uts_spoof_lookup(cmd.uid);
    if (old) {
        hash_del_rcu(&old->node);
        call_rcu(&old->rcu, uts_spoof_free_rcu);
        atomic_dec(&uts_spoof_count);
    }

    if (cmd.enabled && (cmd.release[0] || cmd.version[0])) {
        entry = kzalloc(sizeof(*entry), GFP_KERNEL);
        if (!entry) {
            mutex_unlock(&uts_spoof_lock);
            return -ENOMEM;
        }
        entry->uid = cmd.uid;
        memcpy(entry->release, cmd.release, KSU_UTS_SPOOF_LEN);
        memcpy(entry->version, cmd.version, KSU_UTS_SPOOF_LEN);
        hash_add_rcu(uts_spoof_ht, &entry->node, cmd.uid);
        atomic_inc(&uts_spoof_count);
        pr_info("uts_spoof: uid %u release '%s' version '%s'\n", cmd.uid, entry->release,
                entry->version);
    } else {
        pr_info("uts_spoof: uid %u cleared\n", cmd.uid);
    }
    mutex_unlock(&uts_spoof_lock);
    return 0;
}

int ksu_uts_spoof_get(void __user *arg)
{
    struct ksu_uts_spoof_cmd cmd;
    struct uts_spoof_entry *entry;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    memset(cmd.release, 0, sizeof(cmd.release));
    memset(cmd.version, 0, sizeof(cmd.version));
    cmd.enabled = 0;

    rcu_read_lock();
    entry = uts_spoof_lookup(cmd.uid);
    if (entry) {
        cmd.enabled = 1;
        memcpy(cmd.release, entry->release, KSU_UTS_SPOOF_LEN);
        memcpy(cmd.version, entry->version, KSU_UTS_SPOOF_LEN);
    }
    rcu_read_unlock();

    if (copy_to_user(arg, &cmd, sizeof(cmd)))
        return -EFAULT;
    return 0;
}

void ksu_uts_spoof_exit(void)
{
    struct uts_spoof_entry *entry;
    struct hlist_node *tmp;
    int bkt;

    mutex_lock(&uts_spoof_lock);
    hash_for_each_safe (uts_spoof_ht, bkt, tmp, entry, node) {
        hash_del_rcu(&entry->node);
        kfree_rcu(entry, rcu);
    }
    mutex_unlock(&uts_spoof_lock);
    synchronize_rcu();
}