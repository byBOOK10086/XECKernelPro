// SPDX-License-Identifier: GPL-2.0-only
/*
 * XECKernelPro: anti-brick command guard.
 *
 * See feature/cmd_guard.h for the design. Everything here runs in process
 * context (open/unlink paths and the management ioctl) and errs on the safe
 * side: a failed hook registration leaves the feature disabled instead of
 * pretending to protect anything.
 */

#include <linux/atomic.h>
#include <linux/cred.h>
#include <linux/dcache.h>
#include <linux/errno.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/jiffies.h>
#include <linux/kernel.h>
#include <linux/math64.h>
#include <linux/mutex.h>
#include <linux/namei.h>
#include <linux/path.h>
#include <linux/printk.h>
#include <linux/sched.h>
#include <linux/spinlock.h>
#include <linux/stat.h>
#include <linux/string.h>
#include <linux/time64.h>
#include <linux/timekeeping.h>
#include <linux/types.h>
#include <linux/version.h>
#include <linux/binfmts.h>
#include <linux/uaccess.h>
#include <linux/uidgid.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 9, 0)
#include <linux/mnt_idmapping.h>
#endif

#include "feature/antiwipe.h"
#include "hook/lsm_hook.h"
#include "klog.h" // IWYU pragma: keep

/* Event kinds reported through ksu_antiwipe_last(). */
#define KSU_CMD_GUARD_KIND_NONE 0
#define KSU_CMD_GUARD_KIND_BLOCKDEV 1
#define KSU_CMD_GUARD_KIND_UNLINK 2
#define KSU_CMD_GUARD_KIND_WRITE 3
#define KSU_CMD_GUARD_KIND_RENAME 4
#define KSU_CMD_GUARD_KIND_TRUNCATE 5
#define KSU_CMD_GUARD_KIND_EXEC 6
#define KSU_CMD_GUARD_COMM_LEN 16
#define KSU_CMD_GUARD_PATH_LEN 160

#define KSU_GUARD_MAX_DEVS 128
/* How many dentry levels to walk when looking for a protected ancestor. */
#define KSU_GUARD_MAX_WALK 32
#define KSU_GUARD_MAX_TRUST_SECONDS 3600

/* ------------------------------------------------------------------ state */

static bool ksu_cmd_guard_enabled;

/* jiffies deadline of the trust window, 0 = disarmed. */
static unsigned long ksu_cmd_guard_trust_until;

static atomic64_t ksu_cmd_guard_blocked = ATOMIC64_INIT(0);

struct ksu_cmd_guard_last {
    u64 ts_ms;
    u32 pid;
    u32 kind;
    u8 comm[KSU_CMD_GUARD_COMM_LEN];
    u8 path[KSU_CMD_GUARD_PATH_LEN];
};

static struct ksu_cmd_guard_last ksu_cmd_guard_last;
static DEFINE_SPINLOCK(ksu_cmd_guard_last_lock);

static dev_t ksu_cmd_guard_devs[KSU_GUARD_MAX_DEVS];
static int ksu_cmd_guard_dev_count;
static bool ksu_cmd_guard_dev_ready;
static DEFINE_SPINLOCK(ksu_cmd_guard_dev_lock);
static DEFINE_MUTEX(ksu_cmd_guard_dev_fill_lock);

/* ------------------------------------------------------- protected targets */

/*
 * Top level entries of a mounted filesystem that must never disappear:
 * removing one of these breaks the boot in a way a locked bootloader cannot
 * recover from.
 */
static const char *const ksu_guard_root_top[] = {
    "system",     "system_ext",  "vendor", "product",      "odm",
    "data",       "dev",         "proc",   "sys",          "metadata",
    "mnt",        "storage",     "sdcard", "persist",      "efs",
    "apex",       "linkerconfig", "odm_dlkm", "vendor_dlkm", "system_dlkm",
};

/* Second level entries under /data that carry boot or root state. */
static const char *const ksu_guard_data_top[] = {
    "adb",   "app",  "data",  "system",  "misc",  "vendor", "property",
    "user",  "user_de", "tombstones", "ota", "nfc", "media", "backup",
    "resource-cache", "apw", "unencrypted", "dalvik-cache",
};

/* Sub trees under /data/adb that carry the root installation itself. */
static const char *const ksu_guard_adb_top[] = {
    "modules",
    "ksu",
    "kpm",
    "xecpro",
};

/*
 * /data sub trees that carry user data or boot-critical state. Nothing below
 * them may be removed by uid 0 at any depth: this is what makes "rm -rf /data"
 * and "rm -rf /data/data" harmless instead of a wipe. /data/local and
 * /data/tmp stay writable on purpose (module scripts and temporary work).
 */
static const char *const ksu_guard_deep_dirs[] = {
    "/data/data",         "/data/app",          "/data/app-lib",
    "/data/user",         "/data/user_de",      "/data/user_ce",
    "/data/system",       "/data/system_ce",    "/data/system_de",
    "/data/misc",         "/data/misc_ce",      "/data/misc_de",
    "/data/vendor",       "/data/vendor_ce",    "/data/vendor_de",
    "/data/property",     "/data/media",        "/data/dalvik-cache",
    "/data/tombstones",   "/data/resource-cache", "/data/backup",
    "/data/ota",          "/data/nfc",          "/data/apw",
    "/data/unencrypted",  "/data/ssh",          "/data/drm",
    "/data/anr",          "/data/log",          "/data/bootchart",
    "/data/gsm",          "/data/dpm",          "/data/mediadrm",
    "/data/secure",
    /*
     * Read-only system mounts. Their contents cannot normally be removed,
     * but this also closes the "remount rw, then rm -rf /system" corner.
     */
    "/system",          "/system_ext",   "/vendor",
    "/product",         "/odm",          "/apex",
    "/system_dlkm",     "/vendor_dlkm",  "/odm_dlkm",
};

/*
 * Internal storage. A root script that wipes this destroys every photo and
 * document on the phone, so nothing below these roots may be unlinked by
 * uid 0 either. Apps delete their own files with their own uid and never
 * reach this check.
 */
static const char *const ksu_guard_storage_dirs[] = {
    "/storage/emulated",
    "/storage/emulated/0",
    "/storage/self/primary",
    "/mnt/user/0/primary",
    "/mnt/media_rw",
    "/sdcard",
    "/storage/sdcard",
    "/storage/sdcard0",
};

/*
 * Critical files: a root process that opens anything below these for
 * writing can arrange a boot loop (framework state), a lost lock (keystore)
 * or a broken property without deleting a single file. system_server,
 * installd, vold and keystore2 are either not uid 0 or explicitly exempt,
 * so the running system never meets this check.
 */
static const char *const ksu_guard_write_dirs[] = {
    "/data/system",
    "/data/system_ce",
    "/data/system_de",
    "/data/property",
    "/data/misc/keystore",
    "/metadata",
    /*
     * Read-only system mounts. On a locked/verity device nothing can write
     * here anyway, so this only bites the "remount rw first" trick; on an
     * unlocked device the trust window still allows deliberate surgery.
     */
    "/system",
    "/system_ext",
    "/vendor",
    "/product",
    "/odm",
    "/apex",
};

/*
 * Tools whose only purpose on a phone is to destroy a filesystem.
 * Executing them from a root shell is refused unless a trust window is
 * open (the manager opens one around its own flashing flows).
 */
static const char *const ksu_guard_blocked_bins[] = {
    "fastboot",
    "flash_image",
    "flash_erase",
    "flash_eraseall",
    "nandwrite",
};

/* Daemons that legitimately maintain these paths as uid 0. */
static const char *const ksu_guard_exempt_comms[] = {
    "init",
    "vold",
    "installd",
    "fsck.f2fs",
    "e2fsck",
    "keystore2",
    "apexd",
};

/* Boot critical partitions, resolved through /dev/block/by-name at enable. */
static const char *const ksu_guard_part_bases[] = {
    "boot",
    "init_boot",
    "vendor_boot",
    "recovery",
    "dtbo",
    "vbmeta",
    "vbmeta_system",
    "vbmeta_vendor",
    "super",
    "system",
    "vendor",
    "product",
    "odm",
    "metadata",
    "persist",
    "misc",
    "userdata",
};

static const char *const ksu_guard_slots[] = { "", "_a", "_b" };

static const char *const ksu_guard_by_name_dirs[] = {
    "/dev/block/by-name",
    "/dev/block/bootdevice/by-name",
};

/* Whole disks are brick targets as well; resolve the usual ones directly. */
static const char *const ksu_guard_whole_disks[] = {
    "/dev/block/mmcblk0",
    "/dev/block/sda",
    "/dev/block/sdd",
    "/dev/block/vda",
    "/dev/block/nvme0n1",
};

/* Inodes pinned at enable time. */
static struct inode *ksu_guard_inode_data;
static struct inode *ksu_guard_inode_adb;
static struct inode *ksu_guard_inode_deep[ARRAY_SIZE(ksu_guard_deep_dirs)];
static struct inode *ksu_guard_inode_storage[ARRAY_SIZE(ksu_guard_storage_dirs)];
static struct inode *ksu_guard_inode_write[ARRAY_SIZE(ksu_guard_write_dirs)];

/* ------------------------------------------------------------- LSM hooks */

static int ksu_guard_file_open(struct file *file);
static int ksu_guard_inode_unlink(struct inode *dir, struct dentry *dentry);
static int ksu_guard_inode_rmdir(struct inode *dir, struct dentry *dentry);

static struct ksu_lsm_hook ksu_guard_file_open_hook =
    KSU_LSM_HOOK_INIT(file_open, "selinux_file_open", ksu_guard_file_open, 0);
static struct ksu_lsm_hook ksu_guard_inode_unlink_hook =
    KSU_LSM_HOOK_INIT(inode_unlink, "selinux_inode_unlink", ksu_guard_inode_unlink, 0);
static struct ksu_lsm_hook ksu_guard_inode_rmdir_hook =
    KSU_LSM_HOOK_INIT(inode_rmdir, "selinux_inode_rmdir", ksu_guard_inode_rmdir, 0);

static int ksu_guard_inode_rename(struct inode *old_dir, struct dentry *old_dentry,
                                  struct inode *new_dir, struct dentry *new_dentry);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 9, 0)
static int ksu_guard_inode_setattr(struct mnt_idmap *idmap, struct dentry *dentry,
                                   struct iattr *attr);
#else
static int ksu_guard_inode_setattr(struct dentry *dentry, struct iattr *attr);
#endif
static int ksu_guard_bprm_check(struct linux_binprm *bprm);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0)
static int ksu_guard_file_truncate(struct file *file);
#endif

/*
 * The extra hooks are best effort: when a kernel variant does not register
 * the LSM symbol we expect, the guard keeps its core coverage (block device
 * writes and path removal) instead of refusing to arm at all.
 */
static struct ksu_lsm_hook ksu_guard_inode_rename_hook =
    KSU_LSM_HOOK_INIT(inode_rename, "selinux_inode_rename", ksu_guard_inode_rename, 0);
static struct ksu_lsm_hook ksu_guard_inode_setattr_hook =
    KSU_LSM_HOOK_INIT(inode_setattr, "selinux_inode_setattr", ksu_guard_inode_setattr, 0);
static struct ksu_lsm_hook ksu_guard_bprm_check_hook =
    KSU_LSM_HOOK_INIT(bprm_check_security, "selinux_bprm_check_security", ksu_guard_bprm_check, 0);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0)
static struct ksu_lsm_hook ksu_guard_file_truncate_hook =
    KSU_LSM_HOOK_INIT(file_truncate, "selinux_file_truncate", ksu_guard_file_truncate, 0);
#endif

static bool ksu_guard_hooks_installed;

/* ---------------------------------------------------------------- helpers */

static bool ksu_guard_trust_active(void)
{
    unsigned long until = READ_ONCE(ksu_cmd_guard_trust_until);

    if (!until)
        return false;

    if (time_after(jiffies, until)) {
        WRITE_ONCE(ksu_cmd_guard_trust_until, 0);
        return false;
    }

    return true;
}

static bool ksu_guard_comm_exempt(void)
{
    size_t i;

    for (i = 0; i < ARRAY_SIZE(ksu_guard_exempt_comms); i++) {
        if (!strcmp(current->comm, ksu_guard_exempt_comms[i]))
            return true;
    }

    return false;
}

/* True when the current task is a root task the guard must watch. */
static bool ksu_guard_watches_current(void)
{
    if (current->flags & PF_KTHREAD)
        return false;

    if (!uid_eq(current_euid(), GLOBAL_ROOT_UID))
        return false;

    if (ksu_guard_trust_active())
        return false;

    return !ksu_guard_comm_exempt();
}

static bool ksu_guard_dev_is_critical(dev_t dev)
{
    unsigned long flags;
    bool found = false;
    int i;

    spin_lock_irqsave(&ksu_cmd_guard_dev_lock, flags);
    for (i = 0; i < ksu_cmd_guard_dev_count; i++) {
        if (ksu_cmd_guard_devs[i] == dev) {
            found = true;
            break;
        }
    }
    spin_unlock_irqrestore(&ksu_cmd_guard_dev_lock, flags);

    return found;
}

static void ksu_guard_add_dev(dev_t dev)
{
    unsigned long flags;
    int i;

    spin_lock_irqsave(&ksu_cmd_guard_dev_lock, flags);

    for (i = 0; i < ksu_cmd_guard_dev_count; i++) {
        if (ksu_cmd_guard_devs[i] == dev)
            goto out;
    }

    if (ksu_cmd_guard_dev_count < KSU_GUARD_MAX_DEVS)
        ksu_cmd_guard_devs[ksu_cmd_guard_dev_count++] = dev;

out:
    spin_unlock_irqrestore(&ksu_cmd_guard_dev_lock, flags);
}

static void ksu_guard_add_dev_path(const char *path)
{
    struct path p;
    dev_t dev;

    /* Every by-name entry is a symlink to the real partition node, so the
     * lookup has to follow it (flags = 0 would hand back the symlink inode). */
    if (kern_path(path, LOOKUP_FOLLOW, &p))
        return;

    if (!p.dentry || !p.dentry->d_inode || !S_ISBLK(p.dentry->d_inode->i_mode)) {
        path_put(&p);
        return;
    }

    dev = p.dentry->d_inode->i_rdev;
    path_put(&p);

    ksu_guard_add_dev(dev);
    /*
     * The primary disk keeps minor 0 for itself and hands the partitions
     * 1..n of the same major, so MKDEV(MAJOR(dev), 0) is its whole-disk
     * device. Guarding it blocks "dd of=/dev/block/mmcblk0" style writes.
     */
    ksu_guard_add_dev(MKDEV(MAJOR(dev), 0));
}

static void ksu_guard_fill_devices(void)
{
    char path[96];
    size_t d, b, s;

    mutex_lock(&ksu_cmd_guard_dev_fill_lock);

    if (ksu_cmd_guard_dev_ready) {
        mutex_unlock(&ksu_cmd_guard_dev_fill_lock);
        return;
    }

    for (d = 0; d < ARRAY_SIZE(ksu_guard_by_name_dirs); d++) {
        for (b = 0; b < ARRAY_SIZE(ksu_guard_part_bases); b++) {
            for (s = 0; s < ARRAY_SIZE(ksu_guard_slots); s++) {
                snprintf(path, sizeof(path), "%s/%s%s", ksu_guard_by_name_dirs[d],
                         ksu_guard_part_bases[b], ksu_guard_slots[s]);
                ksu_guard_add_dev_path(path);
            }
        }
    }

    for (d = 0; d < ARRAY_SIZE(ksu_guard_whole_disks); d++)
        ksu_guard_add_dev_path(ksu_guard_whole_disks[d]);

    ksu_cmd_guard_dev_ready = ksu_cmd_guard_dev_count > 0;

    mutex_unlock(&ksu_cmd_guard_dev_fill_lock);

    pr_info("cmd_guard: %d critical block devices resolved\n", ksu_cmd_guard_dev_count);
}

static struct inode *ksu_guard_grab_inode(const char *path)
{
    struct path p;
    struct inode *inode = NULL;

    if (kern_path(path, LOOKUP_FOLLOW, &p))
        return NULL;

    if (p.dentry && p.dentry->d_inode)
        inode = igrab(p.dentry->d_inode);
    if (!inode)
        pr_warn("cmd_guard: cannot pin %s\n", path);

    path_put(&p);

    return inode;
}

static void ksu_guard_release_inodes(void)
{
    size_t i;

    if (ksu_guard_inode_data) {
        iput(ksu_guard_inode_data);
        ksu_guard_inode_data = NULL;
    }
    if (ksu_guard_inode_adb) {
        iput(ksu_guard_inode_adb);
        ksu_guard_inode_adb = NULL;
    }
    for (i = 0; i < ARRAY_SIZE(ksu_guard_inode_deep); i++) {
        if (ksu_guard_inode_deep[i]) {
            iput(ksu_guard_inode_deep[i]);
            ksu_guard_inode_deep[i] = NULL;
        }
    }
    for (i = 0; i < ARRAY_SIZE(ksu_guard_inode_storage); i++) {
        if (ksu_guard_inode_storage[i]) {
            iput(ksu_guard_inode_storage[i]);
            ksu_guard_inode_storage[i] = NULL;
        }
    }
    for (i = 0; i < ARRAY_SIZE(ksu_guard_inode_write); i++) {
        if (ksu_guard_inode_write[i]) {
            iput(ksu_guard_inode_write[i]);
            ksu_guard_inode_write[i] = NULL;
        }
    }
}

static void ksu_guard_fill_inodes(void)
{
    size_t i;

    if (!ksu_guard_inode_data)
        ksu_guard_inode_data = ksu_guard_grab_inode("/data");

    if (!ksu_guard_inode_adb)
        ksu_guard_inode_adb = ksu_guard_grab_inode("/data/adb");

    for (i = 0; i < ARRAY_SIZE(ksu_guard_inode_deep); i++) {
        if (!ksu_guard_inode_deep[i])
            ksu_guard_inode_deep[i] = ksu_guard_grab_inode(ksu_guard_deep_dirs[i]);
    }
    for (i = 0; i < ARRAY_SIZE(ksu_guard_inode_storage); i++) {
        if (!ksu_guard_inode_storage[i])
            ksu_guard_inode_storage[i] = ksu_guard_grab_inode(ksu_guard_storage_dirs[i]);
    }
    for (i = 0; i < ARRAY_SIZE(ksu_guard_inode_write); i++) {
        if (!ksu_guard_inode_write[i])
            ksu_guard_inode_write[i] = ksu_guard_grab_inode(ksu_guard_write_dirs[i]);
    }
}

/* ---------------------------------------------------------------- reports */

static const char *ksu_guard_kind_name(u32 kind)
{
    switch (kind) {
    case KSU_CMD_GUARD_KIND_BLOCKDEV:
        return "blockdev-write";
    case KSU_CMD_GUARD_KIND_UNLINK:
        return "path-remove";
    case KSU_CMD_GUARD_KIND_WRITE:
        return "critical-file-write";
    case KSU_CMD_GUARD_KIND_RENAME:
        return "path-rename";
    case KSU_CMD_GUARD_KIND_TRUNCATE:
        return "file-truncate";
    case KSU_CMD_GUARD_KIND_EXEC:
        return "dangerous-exec";
    default:
        return "none";
    }
}

static void ksu_guard_record(u32 kind, const char *path)
{
    u64 ts_ms = div_u64(ktime_get_mono_fast_ns(), NSEC_PER_MSEC);
    unsigned long flags;

    atomic64_inc(&ksu_cmd_guard_blocked);

    spin_lock_irqsave(&ksu_cmd_guard_last_lock, flags);
    memset(&ksu_cmd_guard_last, 0, sizeof(ksu_cmd_guard_last));
    ksu_cmd_guard_last.ts_ms = ts_ms;
    ksu_cmd_guard_last.pid = current->pid;
    ksu_cmd_guard_last.kind = kind;
    memcpy(ksu_cmd_guard_last.comm, current->comm, TASK_COMM_LEN);
    ksu_cmd_guard_last.comm[KSU_CMD_GUARD_COMM_LEN - 1] = '\0';
    strscpy((char *)ksu_cmd_guard_last.path, path ? path : "", KSU_CMD_GUARD_PATH_LEN);
    spin_unlock_irqrestore(&ksu_cmd_guard_last_lock, flags);

    pr_warn("cmd_guard: blocked %s pid=%d comm=%s target=%s\n", ksu_guard_kind_name(kind),
            current->pid, current->comm, path ? path : "");
}

/* ------------------------------------------------------------ path checks */

static bool ksu_guard_name_eq(const struct dentry *dentry, const char *name)
{
    size_t len;

    if (!dentry || !dentry->d_name.name)
        return false;

    len = strlen(name);

    return dentry->d_name.len == len && !memcmp(dentry->d_name.name, name, len);
}

static bool ksu_guard_name_in(const struct dentry *dentry, const char *const *set, size_t count)
{
    size_t i;

    for (i = 0; i < count; i++) {
        if (ksu_guard_name_eq(dentry, set[i]))
            return true;
    }

    return false;
}

/*
 * True when the target dentry or one of its ancestors within
 * KSU_GUARD_MAX_WALK levels is one of the pinned inodes. Walking up instead of
 * comparing the parent is what makes the check depth-independent: an encrypted
 * script nesting ten directories deep still hits the same ancestor.
 */
static bool ksu_guard_ancestor_matches(struct dentry *dentry, struct inode *const *set,
                                       size_t count, int max_depth)
{
    struct dentry *cur;
    bool matched = false;
    int depth = 0;

    if (!dentry)
        return false;

    /*
     * Every step takes a reference: the VFS may reclaim or rename a dentry
     * concurrently, and reading d_parent/d_inode without a pin is a
     * use-after-free (that is what rebooted devices a few seconds after the
     * launcher came up).
     */
    cur = dget(dentry);

    while (cur && depth++ < max_depth) {
        struct inode *inode = d_inode(cur);
        size_t i;

        if (inode) {
            for (i = 0; i < count; i++) {
                if (set[i] && set[i] == inode) {
                    matched = true;
                    goto out;
                }
            }
        }

        if (IS_ROOT(cur))
            break;

        {
            struct dentry *parent = dget_parent(cur);
            dput(cur);
            cur = parent;
        }
    }

out:
    dput(cur);
    return matched;
}

/* True when the dentry sits below one of the write-protected trees. */
static bool ksu_guard_write_protected(struct dentry *dentry)
{
    return ksu_guard_ancestor_matches(dentry, ksu_guard_inode_write,
                                      ARRAY_SIZE(ksu_guard_inode_write), KSU_GUARD_MAX_WALK);
}

/*
 * Basename of a hardware flashing tool? Formatting tools (mke2fs, mkfs.*) are
 * deliberately NOT listed: they are legitimately used on image files, and
 * their dangerous use - a real partition - is already denied at open().
 */
static bool ksu_guard_bin_blocked(const char *base)
{
    size_t i;

    if (!base || !base[0])
        return false;

    for (i = 0; i < ARRAY_SIZE(ksu_guard_blocked_bins); i++) {
        if (!strcmp(base, ksu_guard_blocked_bins[i]))
            return true;
    }

    return false;
}

/* "/data/system/packages.xml" style hint, at most three components. */
static void ksu_guard_append_name(char *buf, size_t len, size_t *pos, const struct dentry *dentry)
{
    const char *name;
    size_t name_len;

    if (!dentry || !dentry->d_name.name || *pos >= len)
        return;

    name = dentry->d_name.name;
    name_len = dentry->d_name.len;

    /* Filesystem roots carry "/" as their name; the parents are already
     * stitched together, so skip them instead of printing "//". */
    if (name_len == 1 && name[0] == '/')
        return;

    *pos += scnprintf(buf + *pos, len - *pos, "/%.*s", (int)name_len, name);
}

static void ksu_guard_path_hint(struct dentry *dentry, char *buf, size_t len)
{
    struct dentry *self;
    struct dentry *parent;
    size_t pos = 0;

    if (!buf || !len)
        return;

    buf[0] = '\0';
    if (!dentry)
        return;

    /* Same rule as the walk above: pin before touching d_parent. */
    self = dget(dentry);
    parent = dget_parent(self);

    if (parent && parent != self) {
        struct dentry *grand = dget_parent(parent);
        if (grand && grand != parent)
            ksu_guard_append_name(buf, len, &pos, grand);
        if (grand)
            dput(grand);
        ksu_guard_append_name(buf, len, &pos, parent);
    }
    ksu_guard_append_name(buf, len, &pos, self);

    dput(parent);
    dput(self);

    if (!buf[0])
        scnprintf(buf, len, "(unknown path)");
}

static bool ksu_guard_dentry_protected(struct inode *dir, struct dentry *dentry)
{
    struct dentry *self;
    struct dentry *parent;
    bool protected = false;

    if (!dentry)
        return false;

    /* Pin the whole chain we are going to read. */
    self = dget(dentry);
    parent = dget_parent(self);

    /* The filesystem/mount root itself: never a legal removal target. */
    if (IS_ROOT(self) || !parent || parent == self) {
        protected = true;
        goto out;
    }

    /* First level of a mounted tree: /system, /vendor, /data/system, ... */
    if (IS_ROOT(parent) && d_is_dir(self) &&
        ksu_guard_name_in(self, ksu_guard_root_top, ARRAY_SIZE(ksu_guard_root_top))) {
        protected = true;
        goto out;
    }

    /* Direct children of /data: /data/adb, /data/app, ... */
    if (dir && dir == ksu_guard_inode_data &&
        ksu_guard_name_in(self, ksu_guard_data_top, ARRAY_SIZE(ksu_guard_data_top))) {
        protected = true;
        goto out;
    }

    /* Direct children of /data/adb: modules, ksu, kpm, ... */
    if (dir && dir == ksu_guard_inode_adb &&
        ksu_guard_name_in(self, ksu_guard_adb_top, ARRAY_SIZE(ksu_guard_adb_top))) {
        protected = true;
        goto out;
    }

    /*
     * Anything at any depth below the pinned /data sub trees (app data, user
     * storage, framework state, vendor state, ...) or below internal storage.
     * This is what turns "rm -rf /data" and "rm -rf /storage/emulated/0" into
     * a no-op instead of a wipe.
     */
    if (ksu_guard_ancestor_matches(self, ksu_guard_inode_deep,
                                   ARRAY_SIZE(ksu_guard_inode_deep), KSU_GUARD_MAX_WALK) ||
        ksu_guard_ancestor_matches(self, ksu_guard_inode_storage,
                                   ARRAY_SIZE(ksu_guard_inode_storage), KSU_GUARD_MAX_WALK)) {
        protected = true;
        goto out;
    }

out:
    dput(parent);
    dput(self);
    return protected;
}

/* ------------------------------------------------------------- LSM bodies */

static int ksu_guard_file_open(struct file *file)
{
    typedef int (*file_open_fn)(struct file *);
    file_open_fn orig = (file_open_fn)ksu_guard_file_open_hook.original;
    struct inode *inode;
    const char *base;
    char buf[KSU_CMD_GUARD_PATH_LEN];
    char *full;
    int ret = 0;

    if (orig)
        ret = orig(file);
    if (ret)
        return ret;

    if (!READ_ONCE(ksu_cmd_guard_enabled) || !file)
        return 0;

    if (!ksu_guard_watches_current())
        return 0;

    if (!(file->f_mode & FMODE_WRITE))
        return 0;

    full = d_path(&file->f_path, buf, sizeof(buf));
    if (IS_ERR(full))
        full = NULL;

    /*
     * Critical files: opening /data/system, /data/property, keystore state,
     * /data/system_ce|de or /metadata for writing is enough to arrange a
     * boot loop or a lost credential without deleting anything.
     */
    if (ksu_guard_write_protected(file->f_path.dentry)) {
        ksu_guard_record(KSU_CMD_GUARD_KIND_WRITE, full ? full : "(critical file)");
        return -EACCES;
    }

    inode = file_inode(file);
    if (!inode || !S_ISBLK(inode->i_mode))
        return 0;

    /*
     * Loop images and zram are how module images and swap work. They are not
     * brick targets (their backing file or the RAM is), so they stay open.
     */
    base = full ? strrchr(full, 0x2f) : NULL;
    base = base ? base + 1 : full;
    if (base && (!strncmp(base, "loop", 4) || !strncmp(base, "zram", 4)))
        return 0;

    /*
     * Every raw partition write is a brick candidate. The by-name table is
     * the precise list; the /dev/block prefix is the safety net that also
     * catches unnamed partitions (xbl/abl/tz/... have no friendly name) and
     * the case where the table could not be resolved at all.
     */
    if (!ksu_guard_dev_is_critical(inode->i_rdev) &&
        (!full || strncmp(full, "/dev/block/", 11)))
        return 0;

    ksu_guard_record(KSU_CMD_GUARD_KIND_BLOCKDEV, full ? full : "(raw block device)");

    return -EACCES;
}

static int ksu_guard_inode_rmdir(struct inode *dir, struct dentry *dentry);

static int ksu_guard_remove(struct inode *dir, struct dentry *dentry, bool is_rmdir)
{
    typedef int (*inode_remove_fn)(struct inode *, struct dentry *);
    struct ksu_lsm_hook *hook =
        is_rmdir ? &ksu_guard_inode_rmdir_hook : &ksu_guard_inode_unlink_hook;
    inode_remove_fn orig = (inode_remove_fn)hook->original;
    char hint[KSU_CMD_GUARD_PATH_LEN];
    int ret = 0;

    if (orig)
        ret = orig(dir, dentry);
    if (ret)
        return ret;

    if (!READ_ONCE(ksu_cmd_guard_enabled))
        return 0;

    if (!ksu_guard_watches_current())
        return 0;

    if (!ksu_guard_dentry_protected(dir, dentry))
        return 0;

    ksu_guard_path_hint(dentry, hint, sizeof(hint));
    ksu_guard_record(KSU_CMD_GUARD_KIND_UNLINK, hint);

    return -EACCES;
}

static int ksu_guard_inode_unlink(struct inode *dir, struct dentry *dentry)
{
    return ksu_guard_remove(dir, dentry, false);
}

static int ksu_guard_inode_rmdir(struct inode *dir, struct dentry *dentry)
{
    return ksu_guard_remove(dir, dentry, true);
}

static int ksu_guard_setattr_check(struct dentry *dentry, struct iattr *attr)
{
    char hint[KSU_CMD_GUARD_PATH_LEN];

    if (!attr)
        return 0;

    if (!(attr->ia_valid & (ATTR_SIZE | ATTR_MODE | ATTR_UID | ATTR_GID)))
        return 0;

    if (!READ_ONCE(ksu_cmd_guard_enabled))
        return 0;

    if (!ksu_guard_watches_current())
        return 0;

    if (!ksu_guard_write_protected(dentry))
        return 0;

    ksu_guard_path_hint(dentry, hint, sizeof(hint));
    ksu_guard_record(KSU_CMD_GUARD_KIND_TRUNCATE, hint);

    return -EACCES;
}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 9, 0)
static int ksu_guard_inode_setattr(struct mnt_idmap *idmap, struct dentry *dentry,
                                   struct iattr *attr)
{
    typedef int (*inode_setattr_fn)(struct mnt_idmap *, struct dentry *, struct iattr *);
    inode_setattr_fn orig = (inode_setattr_fn)ksu_guard_inode_setattr_hook.original;
    int ret = orig ? orig(idmap, dentry, attr) : 0;

    if (ret)
        return ret;

    return ksu_guard_setattr_check(dentry, attr);
}
#else
static int ksu_guard_inode_setattr(struct dentry *dentry, struct iattr *attr)
{
    typedef int (*inode_setattr_fn)(struct dentry *, struct iattr *);
    inode_setattr_fn orig = (inode_setattr_fn)ksu_guard_inode_setattr_hook.original;
    int ret = orig ? orig(dentry, attr) : 0;

    if (ret)
        return ret;

    return ksu_guard_setattr_check(dentry, attr);
}
#endif

static int ksu_guard_inode_rename(struct inode *old_dir, struct dentry *old_dentry,
                                  struct inode *new_dir, struct dentry *new_dentry)
{
    typedef int (*inode_rename_fn)(struct inode *, struct dentry *, struct inode *,
                                   struct dentry *);
    inode_rename_fn orig = (inode_rename_fn)ksu_guard_inode_rename_hook.original;
    char hint[KSU_CMD_GUARD_PATH_LEN];
    int ret = orig ? orig(old_dir, old_dentry, new_dir, new_dentry) : 0;

    if (ret)
        return ret;

    if (!READ_ONCE(ksu_cmd_guard_enabled))
        return 0;

    if (!ksu_guard_watches_current())
        return 0;

    if (!ksu_guard_dentry_protected(old_dir, old_dentry) &&
        !ksu_guard_dentry_protected(new_dir, new_dentry) &&
        !ksu_guard_write_protected(old_dentry) &&
        !ksu_guard_write_protected(new_dentry))
        return 0;

    ksu_guard_path_hint(new_dentry ? new_dentry : old_dentry, hint, sizeof(hint));
    ksu_guard_record(KSU_CMD_GUARD_KIND_RENAME, hint);

    return -EACCES;
}

static int ksu_guard_bprm_check(struct linux_binprm *bprm)
{
    typedef int (*bprm_check_fn)(struct linux_binprm *);
    bprm_check_fn orig = (bprm_check_fn)ksu_guard_bprm_check_hook.original;
    const char *name;
    const char *base;
    int ret = orig ? orig(bprm) : 0;

    if (ret)
        return ret;

    if (!READ_ONCE(ksu_cmd_guard_enabled) || !bprm)
        return 0;

    if (!ksu_guard_watches_current())
        return 0;

    name = bprm->filename;
    if (!name)
        return 0;

    base = strrchr(name, 0x2f);
    base = base ? base + 1 : name;

    if (!ksu_guard_bin_blocked(base))
        return 0;

    ksu_guard_record(KSU_CMD_GUARD_KIND_EXEC, name);

    return -EACCES;
}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0)
static int ksu_guard_file_truncate(struct file *file)
{
    typedef int (*file_truncate_fn)(struct file *);
    file_truncate_fn orig = (file_truncate_fn)ksu_guard_file_truncate_hook.original;
    char buf[KSU_CMD_GUARD_PATH_LEN];
    char *full;
    int ret = orig ? orig(file) : 0;

    if (ret)
        return ret;

    if (!READ_ONCE(ksu_cmd_guard_enabled) || !file)
        return 0;

    if (!ksu_guard_watches_current())
        return 0;

    if (!ksu_guard_write_protected(file->f_path.dentry))
        return 0;

    full = d_path(&file->f_path, buf, sizeof(buf));
    if (IS_ERR(full))
        full = NULL;
    ksu_guard_record(KSU_CMD_GUARD_KIND_TRUNCATE, full ? full : "(critical file)");

    return -EACCES;
}
#endif

/* ------------------------------------------------------------ enable path */

static int ksu_cmd_guard_enable(void)
{
    int ret;

    ksu_guard_fill_devices();
    ksu_guard_fill_inodes();

    ret = ksu_lsm_hook(&ksu_guard_file_open_hook);
    if (ret) {
        pr_err("cmd_guard: hook file_open failed: %d\n", ret);
        return ret;
    }

    ret = ksu_lsm_hook(&ksu_guard_inode_unlink_hook);
    if (ret) {
        pr_err("cmd_guard: hook inode_unlink failed: %d\n", ret);
        ksu_lsm_unhook(&ksu_guard_file_open_hook);
        return ret;
    }

    ret = ksu_lsm_hook(&ksu_guard_inode_rmdir_hook);
    if (ret) {
        pr_err("cmd_guard: hook inode_rmdir failed: %d\n", ret);
        ksu_lsm_unhook(&ksu_guard_inode_unlink_hook);
        ksu_lsm_unhook(&ksu_guard_file_open_hook);
        return ret;
    }

    /* Best effort extras: degrade gracefully, never block the core. */
    if (ksu_lsm_hook(&ksu_guard_inode_rename_hook))
        pr_warn("cmd_guard: rename hook unavailable\n");
    if (ksu_lsm_hook(&ksu_guard_inode_setattr_hook))
        pr_warn("cmd_guard: setattr hook unavailable\n");
    if (ksu_lsm_hook(&ksu_guard_bprm_check_hook))
        pr_warn("cmd_guard: exec hook unavailable\n");
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0)
    if (ksu_lsm_hook(&ksu_guard_file_truncate_hook))
        pr_warn("cmd_guard: truncate hook unavailable\n");
#endif

    ksu_guard_hooks_installed = true;
    WRITE_ONCE(ksu_cmd_guard_enabled, true);
    pr_info("cmd_guard: armed, %d critical block devices\n", ksu_cmd_guard_dev_count);

    return 0;
}

static void ksu_cmd_guard_disable(void)
{
    if (ksu_guard_hooks_installed) {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 6, 0)
        ksu_lsm_unhook(&ksu_guard_file_truncate_hook);
#endif
        ksu_lsm_unhook(&ksu_guard_bprm_check_hook);
        ksu_lsm_unhook(&ksu_guard_inode_setattr_hook);
        ksu_lsm_unhook(&ksu_guard_inode_rename_hook);
        ksu_lsm_unhook(&ksu_guard_inode_rmdir_hook);
        ksu_lsm_unhook(&ksu_guard_inode_unlink_hook);
        ksu_lsm_unhook(&ksu_guard_file_open_hook);
        ksu_guard_hooks_installed = false;
    }

    WRITE_ONCE(ksu_cmd_guard_enabled, false);
    WRITE_ONCE(ksu_cmd_guard_trust_until, 0);
    ksu_guard_release_inodes();
    pr_info("cmd_guard: disarmed\n");
}

/* ------------------------------------------------------------ feature glue */

/* ------------------------------------------------------------- KPM API */

/*
 * Called by the antiwipe.kpm module (KernelPatch module loaded through the
 * KSU KPM engine). Nothing arms the guard automatically: no feature id, no
 * boot-time hook installation, so a bad build can never lock a device in a
 * reboot loop — unloading the module (or not loading it) is enough.
 */

__attribute__((used, noinline)) int ksu_antiwipe_arm(void)
{
    return ksu_cmd_guard_enable();
}

__attribute__((used, noinline)) void ksu_antiwipe_disarm(void)
{
    ksu_cmd_guard_disable();
}

__attribute__((used, noinline)) int ksu_antiwipe_active(void)
{
    return READ_ONCE(ksu_cmd_guard_enabled) ? 1 : 0;
}

/* Trust window for the manager's own flashing flow (seconds, 0 = drop now). */
__attribute__((used, noinline)) void ksu_antiwipe_trust(u32 seconds)
{
    if (!seconds)
        WRITE_ONCE(ksu_cmd_guard_trust_until, 0);
    else
        WRITE_ONCE(ksu_cmd_guard_trust_until, jiffies + (unsigned long)seconds * HZ);
}

__attribute__((used, noinline)) u64 ksu_antiwipe_blocked(void)
{
    return (u64)atomic64_read(&ksu_cmd_guard_blocked);
}

/*
 * Copy the last blocked event: kind / pid / ts_ms / comm / target.
 * Any output pointer may be NULL; strings are always NUL-terminated.
 */
__attribute__((used, noinline)) void ksu_antiwipe_last(u32 *kind, u32 *pid, u64 *ts_ms,
                                                       char *comm, u32 comm_len,
                                                       char *target, u32 target_len)
{
    struct ksu_cmd_guard_last snapshot;
    unsigned long flags;

    spin_lock_irqsave(&ksu_cmd_guard_last_lock, flags);
    snapshot = ksu_cmd_guard_last;
    spin_unlock_irqrestore(&ksu_cmd_guard_last_lock, flags);

    if (kind)
        *kind = snapshot.kind;
    if (pid)
        *pid = snapshot.pid;
    if (ts_ms)
        *ts_ms = snapshot.ts_ms;
    if (comm && comm_len) {
        u32 n = comm_len - 1 < KSU_CMD_GUARD_COMM_LEN ? comm_len - 1 : KSU_CMD_GUARD_COMM_LEN;
        memcpy(comm, snapshot.comm, n);
        comm[n] = '\0';
    }
    if (target && target_len) {
        u32 n = target_len - 1 < KSU_CMD_GUARD_PATH_LEN ? target_len - 1 : KSU_CMD_GUARD_PATH_LEN;
        memcpy(target, snapshot.path, n);
        target[n] = '\0';
    }
}

/*
 * Keep the exported surface alive even under LTO (the KPM resolves these by
 * name from kallsyms, so they must exist in the final image).
 */
static const void *const ksu_antiwipe_api[] __attribute__((used)) = {
    (const void *)ksu_antiwipe_arm,
    (const void *)ksu_antiwipe_disarm,
    (const void *)ksu_antiwipe_active,
    (const void *)ksu_antiwipe_trust,
    (const void *)ksu_antiwipe_blocked,
    (const void *)ksu_antiwipe_last,
};
