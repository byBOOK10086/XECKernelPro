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

/* fortify-proof fixed-array copy: member-array memcpy trips the field-
 * granularity __write/__read_overflow checks in 6.1+ fortify-string.h
 * (-Werror); a byte loop lowers to the same copy without the fortified
 * call. */
static inline void copy_path_row(u8 *dst, const u8 *src)
{
    u32 j;
    for (j = 0; j < KSU_EXEC_POLICY_PATH; j++)
        dst[j] = src[j];
}

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
    struct exec_policy_entry *entry;
    bool restricted;
    bool allowed = false;
    int i;

    if (!argv_user)
        return false;

    /*
     * Phase A: under RCU, snapshot only whether this uid is restricted. The
     * argv walk further down copies from user memory, and a page fault there
     * can sleep -- illegal inside an RCU read-side critical section (RCU
     * stall on non-preemptible RCU, which is what GKI android12-5.10 builds).
     * Keep this section free of anything that may fault or block.
     */
    rcu_read_lock();
    entry = lookup_entry(uid);
    restricted = entry && entry->restricted && entry->count > 0;
    rcu_read_unlock();

    // Not restricted at all: caller should not have asked.
    if (!restricted)
        return true;

    /*
     * Phase B, with no lock held: walk up to 8 argv slots, skip argv[0] and
     * any option ("-c", "-mm", ...). The first non-option slot is the command
     * string; take its first whitespace-delimited token. No non-option slot
     * found (interactive su) => denied, and argv read errors => denied (fail
     * closed).
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

    /*
     * Phase C: the entry may have been replaced while phase B slept, so look
     * the uid up again and decide against the current policy. A uid that is
     * no longer restricted is allowed, mirroring the phase A early return.
     */
    rcu_read_lock();
    entry = lookup_entry(uid);
    if (!entry || !entry->restricted || entry->count == 0) {
        rcu_read_unlock();
        return true;
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
    struct exec_policy_entry *entry = NULL, *old;
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

    /*
     * Allocate the replacement before touching the hash table. The old entry
     * must stay linked until the new one is ready: unlinking it first would
     * fail open, because an allocation failure would leave the uid with no
     * entry at all -- which readers treat as "unrestricted".
     */
    if (cmd.restricted && cmd.count > 0) {
        entry = kzalloc(sizeof(*entry), GFP_KERNEL);
        if (!entry)
            return -ENOMEM;
        entry->uid = cmd.uid;
        entry->restricted = true;
        entry->count = cmd.count;
        for (i = 0; i < cmd.count; i++)
            copy_path_row((u8 *)entry->paths[i], cmd.paths[i]);
    }

    mutex_lock(&policy_lock);
    old = lookup_entry(cmd.uid);
    if (old && entry) {
        /*
         * 2026-11-13 (audit F03): replace in place. De-linking first and
         * inserting afterwards publishes a window in which the uid has no
         * entry at all, and the lookup path interprets "no entry" as
         * "unrestricted" -- so a concurrent su request could pass a command
         * that is on neither the old nor the new list. hlist_replace_rcu keeps
         * exactly one version visible at every instant.
         */
        hlist_replace_rcu(&old->node, &entry->node);
        call_rcu(&old->rcu, free_entry_rcu);
        pr_info("exec_policy: uid %u restricted to %u command(s) (replaced)\n", cmd.uid, cmd.count);
    } else if (old) {
        hash_del_rcu(&old->node);
        call_rcu(&old->rcu, free_entry_rcu);
        pr_info("exec_policy: uid %u unrestricted\n", cmd.uid);
    } else if (entry) {
        hash_add_rcu(policy_ht, &entry->node, cmd.uid);
        pr_info("exec_policy: uid %u restricted to %u command(s)\n", cmd.uid, cmd.count);
    } else {
        pr_info("exec_policy: uid %u unrestricted (no entry)\n", cmd.uid);
    }
    mutex_unlock(&policy_lock);
    return 0;
}

int ksu_exec_policy_get(void __user *arg)
{
    struct ksu_exec_policy_cmd cmd;
    struct exec_policy_entry *entry;
    u32 i;

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
        // 字节循环（copy_path_row）：memcpy 成员数组在 6.1+ fortify 下触发
        // __write/__read_overflow_field（-Werror）。
        for (i = 0; i < KSU_EXEC_POLICY_MAX; i++)
            copy_path_row(cmd.paths[i], (const u8 *)entry->paths[i]);
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
