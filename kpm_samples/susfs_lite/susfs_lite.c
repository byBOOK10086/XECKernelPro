// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * susfs_lite.kpm — experimental stat/access hiding for stock kernels (③).
 *
 * WHAT IT DOES
 *   Registers kretprobes on the newfstatat family and faccessat. When a
 *   process whose uid is NOT in the allow set asks about a path under one of
 *   the configured prefixes (default "/data/adb,/debug_ramdisk"), the syscall
 *   return value is rewritten to -ENOENT — the path simply does not exist
 *   for that caller. Root (uid 0) and the manager uid (passed as args) keep
 *   the real view.
 *
 * WHY THIS SHAPE
 *   This repo's KPM engine (kernel/kpm/) is a loader for relocatable AArch64
 *   ELF modules: it resolves kernel symbols (kallsyms) at relocation time and
 *   calls .kpm.init/.kpm.ctl0/.kpm.exit. There is no inline-hook framework
 *   exported to modules, so the sample uses the standard register_kprobe()
 *   API — available on every kernel this repo builds for.
 *
 * HONEST LIMITS (read before shipping)
 *   - EXPERIMENTAL. Not wired into CI, not device-verified. Load manually:
 *       xudc kpm load /data/local/tmp/susfs_lite.kpm "allow_uids=10236,prefix=/data/adb"
 *   - Covers stat-family + faccessat only. getdents64 (directory listings)
 *     is NOT hidden: a detector that lists /data will still see "adb".
 *   - This is a stop-gap for BL-locked stock kernels that cannot run the
 *     real SUSFS (built into GKI kernels only). Where SUSFS exists, use it.
 */

#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/uaccess.h>
#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/string.h>
#include <linux/slab.h>
#include <linux/version.h>

#include "kpmodule.h"

KPM_NAME("susfs_lite");
KPM_VERSION("0.1.0");
KPM_AUTHOR("XECKernelPro");
KPM_LICENSE("GPL-2.0-or-later");
KPM_DESCRIPTION("Experimental stat/faccessat hiding via kretprobes (stock-kernel susfs-lite)");

#define MAX_PREFIXES 8
#define PREFIX_LEN 96
#define MAX_ALLOW_UIDS 8

struct hide_state {
    char prefixes[MAX_PREFIXES][PREFIX_LEN];
    int prefix_count;
    u32 allow_uids[MAX_ALLOW_UIDS];
    int allow_count;
};

static struct hide_state state;

struct stat_data {
    char path[PREFIX_LEN];
};

static bool path_hidden(const char *path)
{
    int i;
    if (!path[0])
        return false;
    for (i = 0; i < state.prefix_count; i++) {
        if (!strncmp(path, state.prefixes[i], strlen(state.prefixes[i])))
            return true;
    }
    return false;
}

static bool caller_allowed(void)
{
    u32 uid = current_uid().val;
    int i;
    if (uid == 0)
        return true;
    for (i = 0; i < state.allow_count; i++) {
        if (state.allow_uids[i] == uid)
            return true;
    }
    return false;
}

static int entry_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct stat_data *data = (struct stat_data *)ri->data;
    const char __user *filename;

    data->path[0] = '\0';
    if (caller_allowed())
        return 0;

    // do_sys_newfstatat(dirfd, filename, statbuf, flag): filename is x1.
    // sys_faccessat(dirfd, filename, mode): filename is x1 as well.
    filename = (const char __user *)regs->regs[1];
    if (!filename)
        return 0;
    if (strncpy_from_user(data->path, filename, sizeof(data->path)) < 0)
        data->path[0] = '\0';
    data->path[sizeof(data->path) - 1] = '\0';
    return 0;
}

static int ret_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct stat_data *data = (struct stat_data *)ri->data;

    if (!data->path[0])
        return 0;
    if (regs->regs[0] != 0)
        return 0; // already failing
    if (path_hidden(data->path)) {
        pr_info("susfs_lite: hiding '%s' from uid %u\n", data->path, current_uid().val);
        regs->regs[0] = (unsigned long)-ENOENT;
    }
    return 0;
}

static struct kretprobe probes[] = {
    {
        .kp.symbol_name = "do_sys_newfstatat",
        .entry_handler = entry_handler,
        .handler = ret_handler,
        .data_size = sizeof(struct stat_data),
        .maxactive = 64,
    },
    {
        .kp.symbol_name = "do_faccessat",
        .entry_handler = entry_handler,
        .handler = ret_handler,
        .data_size = sizeof(struct stat_data),
        .maxactive = 64,
    },
};

static long susfs_lite_init(const char *args, const char *event, void *reserved)
{
    int i, ret;

    memset(&state, 0, sizeof(state));

    // args: "allow_uids=10236,10250;prefix=/data/adb,/debug_ramdisk"
    if (args && args[0]) {
        const char *p = strstr(args, "allow_uids=");
        if (p) {
            p += strlen("allow_uids=");
            while (*p && *p != ';' && state.allow_count < MAX_ALLOW_UIDS) {
                u32 uid = 0;
                int n = 0;
                if (sscanf(p, "%u%n", &uid, &n) == 1 && n > 0) {
                    state.allow_uids[state.allow_count++] = uid;
                    p += n;
                }
                if (*p == ',')
                    p++;
                else
                    break;
            }
        }
        p = strstr(args, "prefix=");
        if (p) {
            p += strlen("prefix=");
            while (*p && *p != ';' && state.prefix_count < MAX_PREFIXES) {
                const char *comma = strchr(p, ',');
                size_t len = comma ? (size_t)(comma - p) : strlen(p);
                if (len >= PREFIX_LEN)
                    len = PREFIX_LEN - 1;
                memcpy(state.prefixes[state.prefix_count], p, len);
                state.prefixes[state.prefix_count][len] = '\0';
                state.prefix_count++;
                if (!comma)
                    break;
                p = comma + 1;
            }
        }
    }
    if (state.prefix_count == 0) {
        strscpy(state.prefixes[0], "/data/adb", PREFIX_LEN);
        state.prefix_count = 1;
    }

    for (i = 0; i < (int)(sizeof(probes) / sizeof(probes[0])); i++) {
        ret = register_kretprobe(&probes[i]);
        if (ret) {
            pr_err("susfs_lite: register %s failed: %d\n", probes[i].kp.symbol_name, ret);
            while (--i >= 0)
                unregister_kretprobe(&probes[i]);
            return ret;
        }
    }
    pr_info("susfs_lite: loaded (%d prefix(es), %d allowed uid(s))\n",
            state.prefix_count, state.allow_count);
    return 0;
}

static long susfs_lite_ctl0(const char *ctl_args, char __user *out_msg, int outlen)
{
    // Runtime re-apply: "reload allow_uids=...;prefix=..." — simplest contract
    // is "the same args string as init"; return 0 on success.
    return susfs_lite_init(ctl_args, NULL, NULL);
}

static long susfs_lite_exit(void *reserved)
{
    int i;
    for (i = 0; i < (int)(sizeof(probes) / sizeof(probes[0])); i++) {
        unregister_kretprobe(&probes[i]);
    }
    synchronize_rcu();
    pr_info("susfs_lite: unloaded\n");
    return 0;
}

KPM_INIT(susfs_lite_init);
KPM_CTL0(susfs_lite_ctl0);
KPM_EXIT(susfs_lite_exit);
