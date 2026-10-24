// SPDX-License-Identifier: GPL-2.0-only
/*
 * XECKernelPro extension: capability-level restricted su authorization.
 *
 * Storage: a hash table keyed by uid, guarded by a mutex for writers and
 * RCU for readers (the exec path). Entries are replaced wholesale by
 * KSU_IOCTL_SET_EXEC_POLICY; count == 0 or restricted == 0 removes the uid.
 */
#include "exec_policy.h"

#include <linux/hashtable.h>
#include <linux/mm.h>
#include <linux/mutex.h>
#include <linux/rcupdate.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>

#include "klog.h" // IWYU pragma: keep

#define POLICY_BUCKETS 8

struct exec_policy_entry {
    u32 uid;
    bool restricted;
    u32 count;
    char paths[KSU_EXEC_POLICY_MAX][KSU_EXEC_POLICY_PATH];
    struct hlist_node node;
    struct rcu_head rcu;
};

static DEFINE_HASHTABLE(policy_ht, POLICY_BUCKETS);
static DEFINE_MUTEX(policy_lock);

static struct exec_policy_entry *lookup_entry(uid_t uid)
{
    struct exec_policy_entry *entry;
    hash_for_each_possible_rcu (policy_ht, entry, node, uid) {
        if (entry->uid == uid)
            return entry;
    }
    return NULL;
}

bool ksu_exec_policy_is_restricted(uid_t uid)
{
    bool restricted = false;
    rcu_read_lock();
    struct exec_policy_entry *entry = lookup_entry(uid);
    if (entry && entry->restricted && entry->count > 0)
        restricted = true;
    rcu_read_unlock();
    return restricted;
}

/* Does @cmd (first token of the -c string, possibly a full path) match the
 * whitelist? Full-string match or basename-on-both-sides match. */
static bool token_matches(const char *cmd, const char *rule)
{
    if (!cmd[0] || !rule[0])
        return false;
    if (!strcmp(cmd, rule))
        return true;
    const char *cmd_base = strrchr(cmd, '/');
    cmd_base = cmd_base ? cmd_base + 1 : cmd;
    const char *rule_base = strrchr(rule, '/');
    rule_base = rule_base ? rule_base + 1 : rule;
    return !strcmp(cmd_base, rule_base);
}

bool ksu_exec_policy_allows_argv(uid_t uid, const char __user *const __user *argv_user)
{
    char command[KSU_EXEC_POLICY_PATH];
    bool allowed = false;
    int i;

    if (!argv_user)
        return false;

    rcu_read_lock();
    struct exec_policy_entry *entry = lookup_entry(uid);
    if (!entry || !entry->restricted || entry->count == 0) {
        rcu_read_unlock();
        // Not restricted at all: caller should not have asked.
        return true;
    }

    /*
     * Walk up to 8 argv slots: skip argv[0] and any option ("-c", "-mm",
     * ...). The first non-option slot is the command string; take its first
     * whitespace-delimited token. No non-option slot found (interactive su)
     * => denied, and argv read errors => denied (fail closed).
     */
    command[0] = '\0';
    for (i = 0; i < 8; i++) {
        const char __user *slot;
        char buf[KSU_EXEC_POLICY_PATH];

        if (get_user(slot, argv_user + i)) {
            command[0] = '\0';
            break;
        }
        if (!slot) {
            // End of argv without a command: interactive shell.
            command[0] = '\0';
            break;
        }
        if (i == 0)
            continue;
        if (strncpy_from_user(buf, slot, sizeof(buf)) < 0) {
            command[0] = '\0';
            break;
        }
        buf[sizeof(buf) - 1] = '\0';
        if (buf[0] == '-')
            continue;

        // First token of the command string.
        char *sp = strnchr(buf, sizeof(buf), ' ');
        if (sp)
            *sp = '\0';
        strscpy(command, buf, sizeof(command));
        break;
    }

    if (command[0]) {
        u32 k;
        for (k = 0; k < entry->count && k < KSU_EXEC_POLICY_MAX; k++) {
            if (token_matches(command, entry->paths[k])) {
                allowed = true;
                break;
            }
        }
    }

    if (!allowed)
        pr_info("exec_policy: uid %u denied command '%s'\n", uid, command);
    rcu_read_unlock();
    return allowed;
}

static void free_entry_rcu(struct rcu_head *rcu)
{
    struct exec_policy_entry *entry =
        container_of(rcu, struct exec_policy_entry, rcu);
    kfree(entry);
}

int ksu_exec_policy_set(const void __user *arg)
{
    struct ksu_exec_policy_cmd cmd;
    struct exec_policy_entry *entry, *old;
    u32 i;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    // Validate strings are NUL terminated.
    for (i = 0; i < cmd.count && i < KSU_EXEC_POLICY_MAX; i++) {
        bool terminated = false;
        u32 j;
        for (j = 0; j < KSU_EXEC_POLICY_PATH; j++) {
            if (cmd.paths[i][j] == '\0') {
                terminated = true;
                break;
            }
        }
        if (!terminated)
            return -EINVAL;
    }
    if (cmd.count > KSU_EXEC_POLICY_MAX)
        return -EINVAL;

    mutex_lock(&policy_lock);
    old = lookup_entry(cmd.uid);
    if (old) {
        hash_del_rcu(&old->node);
        call_rcu(&old->rcu, free_entry_rcu);
    }

    if (cmd.restricted && cmd.count > 0) {
        entry = kzalloc(sizeof(*entry), GFP_KERNEL);
        if (!entry) {
            mutex_unlock(&policy_lock);
            return -ENOMEM;
        }
        entry->uid = cmd.uid;
        entry->restricted = true;
        entry->count = cmd.count;
        for (i = 0; i < cmd.count; i++)
            memcpy(entry->paths[i], cmd.paths[i], KSU_EXEC_POLICY_PATH);
        hash_add_rcu(policy_ht, &entry->node, cmd.uid);
        pr_info("exec_policy: uid %u restricted to %u command(s)\n", cmd.uid, cmd.count);
    } else {
        pr_info("exec_policy: uid %u unrestricted\n", cmd.uid);
    }
    mutex_unlock(&policy_lock);
    return 0;
}

int ksu_exec_policy_get(void __user *arg)
{
    struct ksu_exec_policy_cmd cmd;
    struct exec_policy_entry *entry;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    memset(cmd.paths, 0, sizeof(cmd.paths));
    cmd.restricted = 0;
    cmd.count = 0;

    rcu_read_lock();
    entry = lookup_entry(cmd.uid);
    if (entry) {
        cmd.restricted = entry->restricted;
        cmd.count = entry->count;
        memcpy(cmd.paths, entry->paths, sizeof(cmd.paths));
    }
    rcu_read_unlock();

    if (copy_to_user(arg, &cmd, sizeof(cmd)))
        return -EFAULT;
    return 0;
}

void ksu_exec_policy_exit(void)
{
    struct exec_policy_entry *entry;
    struct hlist_node *tmp;
    int bkt;

    mutex_lock(&policy_lock);
    hash_for_each_safe (policy_ht, bkt, tmp, entry, node) {
        hash_del_rcu(&entry->node);
        kfree_rcu(entry, rcu);
    }
    mutex_unlock(&policy_lock);
    synchronize_rcu();
}
