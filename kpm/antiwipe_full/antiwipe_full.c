/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * antiwipe_full.kpm — XECKernelPro full anti-wipe / anti-brick guard as a
 * self-contained KPM module.
 *
 * Unlike the legacy kpm/antiwipe thin switch (which only arms the in-kernel
 * feature/antiwipe.c guard), ALL interception logic lives here:
 *
 *   - no dependency on any XEC kernel-side guard API;
 *   - hooks are installed through the dual-engine KPM core
 *     (kernel/kpmcore): hook_wrap() on the security_* LSM dispatch
 *     functions plus hook_syscalln() on sys_reboot;
 *   - struct member offsets are bootstrapped from the running kernel's BTF
 *     at load time (aw_btf.c) — no compile-time layout assumptions;
 *   - unload (or never loading) leaves the kernel completely unguarded.
 *
 * Preset dangerous-command matrix (each THREAT id is enforced at exactly one
 * marked site and audited by _scratch/check_antiwipe_kpm.py):
 *
 *   THREAT-01 rm -rf /data|/data/<top>|/data/adb/<top>   unlink/rmdir
 *   THREAT-02 rm -rf /sdcard|/storage/emulated/0         unlink/rmdir
 *   THREAT-03 rm -rf /system /vendor /persist /efs ...   unlink/rmdir
 *   THREAT-04 mv across protected trees                  rename
 *   THREAT-05 dd to by-name partitions                   file_open
 *   THREAT-06 dd to the whole disk                       file_open
 *   THREAT-07 raw /dev/block, /dev/mtd*, /dev/ubi* write file_open
 *   THREAT-09 overwrite key/state files in place         file_open
 *   THREAT-10 inject /cache/recovery/command             file_open
 *   THREAT-11 truncate -s 0 on protected files           setattr + truncate
 *   THREAT-12 chmod/chown attacks on protected files     setattr
 *   THREAT-13 ftruncate through an open fd               file_truncate
 *   THREAT-14 fastboot/flash_image/nandwrite execution   bprm_check
 *   THREAT-15 FS_IOC_REMOVE_ENCRYPTION_KEY crypto-erase  file_ioctl
 *   THREAT-16 reboot bootloader/recovery/edl + kexec     sys_reboot
 *   THREAT-17 writes through pre-opened fds              file_permission
 *   (THREAT-08 is the loop/zram exemption marker, THREAT-18 the hardened
 *    comm+exe-path exemption, both enforced below)
 *
 * ctl0 protocol (superset of the legacy thin module; the manager only sends
 * "trust <sec>" and the ksud list/unload paths are name based):
 *   ""        status      "armed=N blocked=N trust=<s> hooks=N devs=N pins=N"
 *   "on"      arm         "armed=1 ..." / error text
 *   "off"     disarm      "disarmed"
 *   "trust N" open window "trust=N"   (N capped at 3600)
 *   "last"    last block  "last=<kind> pid=<p> comm=<c> target=<t>"
 *   "selftest" dry verify "SELFTEST PASS ..." / "SELFTEST FAIL ..."
 */

#include "aw_offsets.h"
#include "aw_types.h"

/* --------------------------------------------------- LSM dispatcher targets
 * Hooked through the engine (hook_wrap); never called directly, only their
 * addresses are taken. All exist on every supported kernel (5.10-6.12);
 * security_file_truncate (6.6+) and the bprm_check dispatcher (renamed in
 * 6.8) are resolved optionally via kallsyms at init. */
extern int security_file_open(void *file);
extern int security_inode_unlink(void *dir, void *dentry);
extern int security_inode_rmdir(void *dir, void *dentry);
extern int security_inode_rename(void *old_dir, void *old_dentry, void *new_dir,
                                 void *new_dentry);
extern int security_inode_setattr(void *idmap_or_dentry, void *dentry, void *iattr);
extern int security_file_permission(void *file, int mask);
extern int security_file_ioctl(void *file, unsigned int cmd, unsigned long arg);

/* ------------------------------------------------------------ policy data */

/* Mount-root top level entries that must never disappear. */
static const char *const aw_root_top[] = {
    "system",     "system_ext",   "vendor",      "product",     "odm",
    "data",       "dev",          "proc",        "sys",         "metadata",
    "mnt",        "storage",      "sdcard",      "persist",     "efs",
    "apex",       "linkerconfig", "odm_dlkm",    "vendor_dlkm", "system_dlkm",
};

/* Direct children of /data carrying boot or root state. */
static const char *const aw_data_top[] = {
    "adb",   "app",     "data",       "system",  "misc",   "vendor", "property",
    "user",  "user_de", "tombstones", "ota",     "nfc",    "media",  "backup",
    "apw",   "resource-cache", "unencrypted", "dalvik-cache",
};

/* Direct children of /data/adb carrying the root installation itself. */
static const char *const aw_adb_top[] = {
    "modules",
    "ksu",
    "kpm",
    "xecpro",
};

/* Sub trees that must never lose anything at any depth (rm -rf /data and
 * friends become no-ops). /data/local and /data/tmp stay writable on
 * purpose. The read-only mounts close the "remount rw, then rm -rf" corner. */
static const char *const aw_deep_dirs[] = {
    "/data/data",         "/data/app",            "/data/app-lib",
    "/data/user",         "/data/user_de",        "/data/user_ce",
    "/data/system",       "/data/system_ce",      "/data/system_de",
    "/data/misc",         "/data/misc_ce",        "/data/misc_de",
    "/data/vendor",       "/data/vendor_ce",      "/data/vendor_de",
    "/data/property",     "/data/media",          "/data/dalvik-cache",
    "/data/tombstones",   "/data/resource-cache", "/data/backup",
    "/data/ota",          "/data/nfc",            "/data/apw",
    "/data/unencrypted",  "/data/ssh",            "/data/drm",
    "/data/anr",          "/data/log",            "/data/bootchart",
    "/data/gsm",          "/data/dpm",            "/data/mediadrm",
    "/data/secure",
    "/system",            "/system_ext",          "/vendor",
    "/product",           "/odm",                 "/apex",
    "/system_dlkm",       "/vendor_dlkm",         "/odm_dlkm",
};

/* Internal storage: a root wipe here destroys every photo and document. */
static const char *const aw_storage_dirs[] = {
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
 * Write-protected trees: opening anything below for writing arranges a boot
 * loop / lost credential / broken property without deleting a single file.
 * Compared to the legacy guard this adds /data/misc(+_ce/_de),
 * /data/unencrypted (vold + metadata encryption keys live there) and
 * /cache/recovery (the wipe-command injection file).
 */
static const char *const aw_write_dirs[] = {
    "/data/system",      "/data/system_ce",   "/data/system_de",
    "/data/property",    "/data/misc",        "/data/misc_ce",
    "/data/misc_de",     "/data/unencrypted", "/metadata",
    "/cache/recovery",
    "/system",           "/system_ext",       "/vendor",
    "/product",          "/odm",              "/apex",
};

/* Tools whose only purpose on a phone is destruction (target opens are
 * covered separately; mkfs/blkdiscard stay unlisted on purpose — their
 * dangerous use is a real partition, denied at open()). */
static const char *const aw_blocked_bins[] = {
    "fastboot",
    "flash_image",
    "flash_erase",
    "flash_eraseall",
    "nandwrite",
};

/* Daemons that legitimately maintain protected paths as uid 0. THREAT-18:
 * exemption requires BOTH the comm and an executable under /system, /apex
 * or /vendor, so prctl(PR_SET_NAME) spoofing from a shell binary fails. */
static const char *const aw_exempt_comms[] = {
    "init",
    "vold",
    "installd",
    "fsck.f2fs",
    "e2fsck",
    "keystore2",
    "apexd",
};

/* Boot-critical partitions resolved through /dev/block/by-name at arm time. */
static const char *const aw_part_bases[] = {
    "boot",     "init_boot", "vendor_boot", "recovery", "dtbo",
    "vbmeta",   "vbmeta_system", "vbmeta_vendor", "super", "system",
    "vendor",   "product",   "odm",         "metadata", "persist",
    "misc",     "userdata",
};

static const char *const aw_slots[] = { "", "_a", "_b" };

static const char *const aw_by_name_dirs[] = {
    "/dev/block/by-name",
    "/dev/block/bootdevice/by-name",
};

/* Whole disks are brick targets as well. */
static const char *const aw_whole_disks[] = {
    "/dev/block/mmcblk0",
    "/dev/block/sda",
    "/dev/block/sdd",
    "/dev/block/vda",
    "/dev/block/nvme0n1",
};

/* reboot RESTART2 targets that lead to a wipe or a locked-bootloader brick.
 * There is deliberately NO comm exemption on the reboot hook: init-mediated
 * `setprop sys.powerctl bootloader` would otherwise be a clean escape. */
static const char *const aw_reboot_targets[] = {
    "bootloader", "fastboot", "fastbootd", "recovery", "edl", "qcom_edl",
    "dload", "download", "emergency", "ffbm", "ftm", "dump", "meta",
};

/* ------------------------------------------------------------------ state */

#define AW_MAX_DEVS 128
#define AW_WALK_MAX 32
#define AW_MAX_TRUST_SECONDS 3600
#define AW_PATH_HINT_LEN 160

enum {
    AW_KIND_NONE = 0,
    AW_KIND_BLOCKDEV = 1,
    AW_KIND_UNLINK = 2,
    AW_KIND_WRITE = 3,
    AW_KIND_RENAME = 4,
    AW_KIND_TRUNCATE = 5,
    AW_KIND_EXEC = 6,
    AW_KIND_IOCTL = 7,
    AW_KIND_REBOOT = 8,
};

struct aw_last_event {
    u64 ts_ms;
    u32 pid;
    u32 kind;
    char comm[TASK_COMM_LEN];
    char target[AW_PATH_HINT_LEN];
};

struct aw_pin {
    struct aw_path p;
    bool ok;
    const char *name; /* root prefix for hints */
};

static bool aw_armed;
static unsigned long aw_trust_until; /* jiffies deadline, 0 = closed */
static unsigned long aw_hz = 250;
static u64 aw_blocked;
static unsigned char aw_last_lock;
static struct aw_last_event aw_last;

static u32 aw_devs[AW_MAX_DEVS];
static int aw_dev_count;
static bool aw_dev_ready;

static struct aw_pin aw_pin_data;
static struct aw_pin aw_pin_adb;
static struct aw_pin aw_pin_deep[AW_ARRAY_SIZE(aw_deep_dirs)];
static struct aw_pin aw_pin_storage[AW_ARRAY_SIZE(aw_storage_dirs)];
static struct aw_pin aw_pin_write[AW_ARRAY_SIZE(aw_write_dirs)];

/* Installed hooks, unwrapped in reverse at disarm/exit. */
struct aw_hook_slot {
    void *func;      /* hooked function address */
    void *before;    /* chain callback */
    bool installed;
};
static struct aw_hook_slot aw_hooks[16];
static int aw_hook_count;

/* Optional targets resolved through kallsyms at init. */
static void *aw_fn_file_truncate;
static void *aw_fn_bprm_check;

/* Optional dispatcher resolution: security_file_truncate only exists 6.6+,
 * the bprm_check dispatcher was renamed to security_bprm_check_security in
 * 6.8. Absent targets simply leave the corresponding hook uninstalled. */
static void *aw_opt_bprm_target(void)
{
    unsigned long addr = kallsyms_lookup_name("security_bprm_check_security");

    if (!addr)
        addr = kallsyms_lookup_name("security_bprm_check");
    return (void *)addr;
}

/* -------------------------------------------------------------- tiny libc */

static size_t aw_strlen(const char *s)
{
    return kf_strlen(s);
}

static int aw_strcmp(const char *a, const char *b)
{
    return kf_strcmp(a, b);
}

static bool aw_str_prefix(const char *s, const char *prefix)
{
    size_t n = kf_strlen(prefix);

    return kf_strncmp(s, prefix, n) == 0;
}

/* Boundary-aware directory prefix: /data/system must not match /data/system_ce. */
static bool aw_dir_prefix(const char *path, const char *prefix)
{
    size_t n = kf_strlen(prefix);

    if (kf_strncmp(path, prefix, n) != 0)
        return false;
    return path[n] == '\0' || path[n] == '/';
}

static const char *aw_last_component(const char *path)
{
    const char *slash = kf_strrchr(path, '/');

    return slash ? slash + 1 : path;
}

static unsigned int aw_parse_uint(const char *s)
{
    unsigned int v = 0;

    if (!s)
        return 0;
    while (*s == ' ')
        s++;
    while (*s >= '0' && *s <= '9') {
        v = v * 10u + (unsigned int)(*s - '0');
        s++;
    }
    return v;
}

/* ------------------------------------------------------------ task access */

static inline void *aw_current_task(void)
{
    u64 t;

    __asm__ volatile("mrs %0, sp_el0" : "=r"(t));
    return (void *)(unsigned long)t;
}

static u32 aw_task_flags(void *task)
{
    u32 flags;

    memcpy(&flags, (const char *)task + aw_off.task_flags, sizeof(flags));
    return flags;
}

static u32 aw_task_euid(void *task)
{
    void *cred = *(void **)((char *)task + task_struct_offset.cred_offset);
    u32 euid;

    if (!cred)
        return 1; /* no cred: not root, not watched */
    memcpy(&euid, (const char *)cred + aw_off.cred_euid, sizeof(euid));
    return euid;
}

static u32 aw_task_pid(void *task)
{
    s16 off = task_struct_offset.pid_offset;
    u32 pid = 0;

    if (off > 0)
        memcpy(&pid, (const char *)task + off, sizeof(pid));
    return pid;
}

static void aw_task_comm(void *task, char *out /* TASK_COMM_LEN */)
{
    memcpy(out, (const char *)task + task_struct_offset.comm_offset, TASK_COMM_LEN);
    out[TASK_COMM_LEN - 1] = '\0';
}

/* THREAT-18 enforcement: exemption needs BOTH the comm and an executable
 * path under /system, /apex or /vendor — prctl(PR_SET_NAME) spoofing from a
 * shell binary in /data fails the path half. */
static bool aw_exe_path_exempt(void *task)
{
    void *mm = *(void **)((char *)task + task_struct_offset.mm_offset);
    void *exe;
    struct aw_path fp;
    char buf[192];
    char *full;

    if (!mm)
        return false;
    exe = *(void **)((char *)mm + aw_off.mm_exe_file);
    if (!exe)
        return false;
    memcpy(&fp, (const char *)exe + aw_off.file_f_path, sizeof(fp));
    full = d_path(&fp, buf, sizeof(buf));
    if ((unsigned long)full >= (unsigned long)-4096 || !full)
        return false;
    return aw_dir_prefix(full, "/system") || aw_dir_prefix(full, "/apex") ||
           aw_dir_prefix(full, "/vendor");
}

static bool aw_comm_exempt(void *task)
{
    char comm[TASK_COMM_LEN];
    size_t i;

    aw_task_comm(task, comm);
    for (i = 0; i < AW_ARRAY_SIZE(aw_exempt_comms); i++) {
        if (!aw_strcmp(comm, aw_exempt_comms[i]))
            return aw_exe_path_exempt(task);
    }
    return false;
}

static bool aw_trust_active(void)
{
    unsigned long until = aw_trust_until;

    if (!until)
        return false;
    if ((long)(jiffies - until) > 0) {
        aw_trust_until = 0;
        return false;
    }
    return true;
}

/* euid 0 user-space task, trust window closed, not comm/path exempt. */
static bool aw_watches_current(void)
{
    void *task = aw_current_task();

    if (!task)
        return false;
    if (aw_task_flags(task) & AW_PF_KTHREAD)
        return false;
    if (aw_task_euid(task) != 0)
        return false;
    if (aw_trust_active())
        return false;
    if (aw_comm_exempt(task))
        return false;
    return true;
}

/* root/uid0 check without the comm exemption (reboot hook). */
static bool aw_watches_root_no_comm(void)
{
    void *task = aw_current_task();

    if (!task)
        return false;
    if (aw_task_flags(task) & AW_PF_KTHREAD)
        return false;
    if (aw_task_euid(task) != 0)
        return false;
    return !aw_trust_active();
}

/* ---------------------------------------------------------------- reports */

static const char *aw_kind_name(u32 kind)
{
    switch (kind) {
    case AW_KIND_BLOCKDEV:
        return "blockdev-write";
    case AW_KIND_UNLINK:
        return "path-remove";
    case AW_KIND_WRITE:
        return "critical-file-write";
    case AW_KIND_RENAME:
        return "path-rename";
    case AW_KIND_TRUNCATE:
        return "file-truncate";
    case AW_KIND_EXEC:
        return "dangerous-exec";
    case AW_KIND_IOCTL:
        return "keyring-ioctl";
    case AW_KIND_REBOOT:
        return "reboot-escape";
    default:
        return "none";
    }
}

static void aw_record(u32 kind, const char *target)
{
    struct aw_timespec64 ts;
    struct aw_last_event ev;

    __atomic_fetch_add(&aw_blocked, 1, __ATOMIC_RELAXED);

    ktime_get_real_ts64(&ts);
    memset(&ev, 0, sizeof(ev));
    ev.ts_ms = (u64)ts.tv_sec * 1000u + (u64)ts.tv_nsec / 1000000u;
    ev.pid = aw_task_pid(aw_current_task());
    ev.kind = kind;
    aw_task_comm(aw_current_task(), ev.comm);
    if (target)
        kf_memcpy(ev.target, target, AW_PATH_HINT_LEN - 1);

    while (__atomic_test_and_set(&aw_last_lock, __ATOMIC_ACQUIRE))
        ;
    aw_last = ev;
    aw_last_lock = 0;

    aw_warn("blocked %s pid=%u comm=%s target=%s\n", aw_kind_name(kind), ev.pid, ev.comm,
            target ? target : "");
}

/* ------------------------------------------------------------ path checks */

static bool aw_name_in(const char *name, const char *const *set, size_t count)
{
    size_t i;

    for (i = 0; i < count; i++) {
        if (!aw_strcmp(name, set[i]))
            return true;
    }
    return false;
}

static bool aw_dev_is_critical(u32 dev)
{
    int i;

    for (i = 0; i < aw_dev_count; i++) {
        if (aw_devs[i] == dev)
            return true;
    }
    return false;
}

/*
 * Ancestor walk against pinned dentries. The first cur is the hook argument
 * (borrowed, alive for the duration of the hook); every parent acquired via
 * dget_parent() is owned and released. Matches on dentry pointer OR inode
 * pointer so aliased dentries still hit.
 */
static bool aw_ancestor_pinned(void *dentry, const struct aw_pin *pins, size_t count,
                               const struct aw_pin **hit)
{
    void *cur = dentry;
    void *held = NULL;
    bool matched = false;
    int depth = 0;
    size_t i;

    while (cur && depth++ < AW_WALK_MAX) {
        for (i = 0; i < count; i++) {
            const struct aw_pin *pin = &pins[i];
            void *pd, *pi, *ci;

            if (!pin->ok)
                continue;
            pd = pin->p.dentry;
            if (!pd)
                continue;
            if (cur == pd) {
                matched = true;
                if (hit)
                    *hit = pin;
                goto out;
            }
            pi = *(void **)((char *)pd + aw_off.dentry_d_inode);
            ci = *(void **)((char *)cur + aw_off.dentry_d_inode);
            if (pi && ci && pi == ci) {
                matched = true;
                if (hit)
                    *hit = pin;
                goto out;
            }
        }

        {
            void *parent = dget_parent(cur);

            if (!parent || parent == cur) {
                /* fs root reached; dget_parent returned a reference either
                 * way, drop it. */
                if (parent)
                    dput(parent);
                break;
            }
            if (held)
                dput(held);
            held = parent;
            cur = parent;
        }
    }

out:
    if (held)
        dput(held);
    return matched;
}

/* Build "<root><fs-relative path>" hint for a dentry hook. */
static void aw_dentry_hint(void *dentry, char *buf, size_t len)
{
    const struct aw_pin *hit = NULL;
    char rel[96];
    char *p;

    buf[0] = '\0';
    if (!dentry)
        return;

    (void)aw_ancestor_pinned(dentry, aw_pin_deep, AW_ARRAY_SIZE(aw_pin_deep), &hit);
    if (!hit)
        (void)aw_ancestor_pinned(dentry, aw_pin_storage, AW_ARRAY_SIZE(aw_pin_storage), &hit);

    p = dentry_path_raw(dentry, rel, sizeof(rel));
    if ((unsigned long)p >= (unsigned long)-4096 || !p) {
        kf_snprintf(buf, len, "(unknown path)");
        return;
    }
    if (hit && hit->ok && hit->name)
        kf_snprintf(buf, len, "%s%s", hit->name, p);
    else
        kf_snprintf(buf, len, "%s", p);
}

/* fs-relative last components for the name checks. */
static bool aw_dentry_parent_is_mount_root(void *dentry)
{
    void *parent = dget_parent(dentry);
    void *grand;
    bool is_root = false;

    if (!parent)
        return false;
    grand = dget_parent(parent);
    is_root = (grand == parent);
    if (grand)
        dput(grand);
    dput(parent);
    return is_root;
}

static void *aw_dentry_inode(void *dentry)
{
    return *(void **)((char *)dentry + aw_off.dentry_d_inode);
}

static bool aw_is_dir_dentry(void *dentry)
{
    void *inode = aw_dentry_inode(dentry);

    if (!inode)
        return false;
    return (aw_i_mode(inode, aw_off.inode_i_mode) & S_IFMT) == S_IFDIR;
}

/*
 * The unlink/rmdir policy. Returns true when the victim is protected.
 * THREAT-01 / THREAT-02 / THREAT-03 enforcement (shared by the
 * unlink/rmdir/rename hooks below).
 */
static bool aw_dentry_protected(void *dir, void *dentry, char *hint, size_t hint_len)
{
    char namebuf[96];
    char *rel;
    void *dir_inode;

    if (!dentry)
        return false;

    /* The filesystem/mount root itself: never a legal removal target. */
    {
        void *self_parent = dget_parent(dentry);

        if (self_parent) {
            void *grand = dget_parent(self_parent);
            bool self_is_root = (grand == self_parent);

            if (grand)
                dput(grand);
            dput(self_parent);
            if (self_is_root) {
                aw_dentry_hint(dentry, hint, hint_len);
                return true;
            }
        }
    }

    /* Anything below a pinned deep / storage tree, at any depth. */
    if (aw_ancestor_pinned(dentry, aw_pin_deep, AW_ARRAY_SIZE(aw_pin_deep), NULL) ||
        aw_ancestor_pinned(dentry, aw_pin_storage, AW_ARRAY_SIZE(aw_pin_storage), NULL)) {
        aw_dentry_hint(dentry, hint, hint_len);
        return true;
    }

    /* Direct children of /data and /data/adb by name. */
    rel = dentry_path_raw(dentry, namebuf, sizeof(namebuf));
    if ((unsigned long)rel < (unsigned long)-4096 && rel) {
        const char *name = aw_last_component(rel);
        dir_inode = dir ? dir : NULL;

        if (dir_inode && aw_pin_data.ok &&
            dir_inode == aw_dentry_inode(aw_pin_data.p.dentry) &&
            aw_name_in(name, aw_data_top, AW_ARRAY_SIZE(aw_data_top))) {
            kf_snprintf(hint, hint_len, "/data/%s", name);
            return true;
        }
        if (dir_inode && aw_pin_adb.ok &&
            dir_inode == aw_dentry_inode(aw_pin_adb.p.dentry) &&
            aw_name_in(name, aw_adb_top, AW_ARRAY_SIZE(aw_adb_top))) {
            kf_snprintf(hint, hint_len, "/data/adb/%s", name);
            return true;
        }

        /* Top level directory of any mounted filesystem (root_top list). */
        if (aw_is_dir_dentry(dentry) && aw_dentry_parent_is_mount_root(dentry) &&
            aw_name_in(name, aw_root_top, AW_ARRAY_SIZE(aw_root_top))) {
            kf_snprintf(hint, hint_len, "/%s", name);
            return true;
        }
    }

    return false;
}

/* Write-protected tree check for a struct file (string + pinned walk). */
static bool aw_file_write_protected(const struct aw_path *fp, char *buf, size_t buf_len)
{
    char *full;
    size_t i;

    full = d_path(fp, buf, (int)buf_len);
    if ((unsigned long)full < (unsigned long)-4096 && full) {
        for (i = 0; i < AW_ARRAY_SIZE(aw_write_dirs); i++) {
            if (aw_dir_prefix(full, aw_write_dirs[i]))
                return true;
        }
    }
    return aw_ancestor_pinned(fp->dentry, aw_pin_write, AW_ARRAY_SIZE(aw_pin_write), NULL);
}

/* ---------------------------------------------------------------- hooks */

/* THREAT-09 / THREAT-10 enforcement site (open side: overwrite + inject). */
static void aw_file_open_before(hook_fargs1_t *args, void *udata)
{
    void *file = (void *)args->arg0;
    struct aw_path fp;
    char buf[192];
    char *full = NULL;
    void *inode;
    u32 mode;
    u32 f_mode;

    (void)udata;

    if (!aw_armed || !file)
        return;
    if (!aw_watches_current())
        return;

    memcpy(&f_mode, (const char *)file + aw_off.file_f_mode, sizeof(f_mode));
    if (!(f_mode & FMODE_WRITE))
        return;

    memcpy(&fp, (const char *)file + aw_off.file_f_path, sizeof(fp));

    if (aw_file_write_protected(&fp, buf, sizeof(buf))) {
        full = d_path(&fp, buf, sizeof(buf));
        aw_record(AW_KIND_WRITE,
                  ((unsigned long)full < (unsigned long)-4096 && full) ? full : "(critical file)");
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
        return;
    }

    inode = *(void **)((const char *)file + aw_off.file_f_inode);
    if (!inode)
        return;
    mode = aw_i_mode(inode, aw_off.inode_i_mode);

    if ((mode & S_IFMT) == S_IFBLK) {
        u32 rdev;
        const char *base;

        full = d_path(&fp, buf, sizeof(buf));
        base = ((unsigned long)full < (unsigned long)-4096 && full) ? aw_last_component(full) : NULL;

        /* THREAT-08 enforcement (exemption): loop images and zram are not
         * brick targets — their backing file or RAM is, and that is covered
         * elsewhere. */
        if (base && (aw_str_prefix(base, "loop") || aw_str_prefix(base, "zram")))
            return;

        memcpy(&rdev, (const char *)inode + aw_off.inode_i_rdev, sizeof(rdev));
        if (aw_dev_is_critical(rdev)) {
            /* THREAT-05 / THREAT-06 enforcement site (by-name + whole disk). */
            aw_record(AW_KIND_BLOCKDEV, (base && full) ? full : "(raw block device)");
            args->ret = (u64)(long)-EACCES;
            args->skip_origin = 1;
            return;
        }
        /* THREAT-07 enforcement site: /dev/block safety net (unnamed
         * partitions, unresolved tables) plus MTD/UBI raw flash character
         * devices (plain prefix: node names continue with digits,
         * e.g. /dev/mtd0ro). */
        if (base && full &&
            (aw_str_prefix(full, "/dev/block/") || aw_str_prefix(full, "/dev/mtd") ||
             aw_str_prefix(full, "/dev/ubi"))) {
            aw_record(AW_KIND_BLOCKDEV, full);
            args->ret = (u64)(long)-EACCES;
            args->skip_origin = 1;
            return;
        }
    }
}

/* unlink/rmdir hook — policy in aw_dentry_protected(). */
static void aw_unlink_before(hook_fargs2_t *args, void *udata)
{
    void *dentry = (void *)args->arg1;
    char hint[AW_PATH_HINT_LEN];

    (void)udata;

    if (!aw_armed || !dentry)
        return;
    if (!aw_watches_current())
        return;

    if (aw_dentry_protected((void *)args->arg0, dentry, hint, sizeof(hint))) {
        aw_record(AW_KIND_UNLINK, hint);
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
    }
}

/* THREAT-04 enforcement site. */
static void aw_rename_before(hook_fargs4_t *args, void *udata)
{
    char hint[AW_PATH_HINT_LEN];

    (void)udata;

    if (!aw_armed)
        return;
    if (!aw_watches_current())
        return;

    if (aw_dentry_protected((void *)args->arg0, (void *)args->arg1, hint, sizeof(hint)) ||
        aw_dentry_protected((void *)args->arg2, (void *)args->arg3, hint, sizeof(hint))) {
        aw_record(AW_KIND_RENAME, hint);
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
    }
}

/* THREAT-11 / THREAT-12 enforcement site. */
static void aw_setattr_before(hook_fargs4_t *args, void *udata)
{
    void *dentry;
    void *iattr;
    u32 ia_valid;

    (void)udata;

    if (!aw_armed)
        return;
    if (!aw_watches_current())
        return;

    if (kver >= AW_KVER(6, 9, 0)) {
        dentry = (void *)args->arg1; /* arg0 is mnt_idmap */
        iattr = (void *)args->arg2;
    } else {
        dentry = (void *)args->arg0;
        iattr = (void *)args->arg1;
    }
    if (!dentry || !iattr)
        return;

    memcpy(&ia_valid, (const char *)iattr + aw_off.iattr_ia_valid, sizeof(ia_valid));
    if (!(ia_valid & (ATTR_SIZE | ATTR_MODE | ATTR_UID | ATTR_GID)))
        return;

    if (aw_ancestor_pinned(dentry, aw_pin_write, AW_ARRAY_SIZE(aw_pin_write), NULL)) {
        char hint[AW_PATH_HINT_LEN];

        aw_dentry_hint(dentry, hint, sizeof(hint));
        aw_record(AW_KIND_TRUNCATE, hint);
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
    }
}

/* THREAT-14 enforcement site. */
static void aw_bprm_before(hook_fargs1_t *args, void *udata)
{
    void *bprm = (void *)args->arg0;
    const char *filename;
    const char *base;
    size_t i;

    (void)udata;

    if (!aw_armed || !bprm)
        return;
    if (!aw_watches_current())
        return;

    filename = *(char **)((const char *)bprm + aw_off.bprm_filename);
    if (!filename)
        return;

    base = aw_last_component(filename);
    for (i = 0; i < AW_ARRAY_SIZE(aw_blocked_bins); i++) {
        if (!aw_strcmp(base, aw_blocked_bins[i])) {
            aw_record(AW_KIND_EXEC, filename);
            args->ret = (u64)(long)-EACCES;
            args->skip_origin = 1;
            return;
        }
    }
}

/* THREAT-13 enforcement site (ftruncate through a long-lived fd, 6.6+). */
static void aw_truncate_before(hook_fargs1_t *args, void *udata)
{
    void *file = (void *)args->arg0;
    struct aw_path fp;
    char buf[192];
    char *full;

    (void)udata;

    if (!aw_armed || !file)
        return;
    if (!aw_watches_current())
        return;

    memcpy(&fp, (const char *)file + aw_off.file_f_path, sizeof(fp));
    if (!aw_file_write_protected(&fp, buf, sizeof(buf)))
        return;

    full = d_path(&fp, buf, sizeof(buf));
    aw_record(AW_KIND_TRUNCATE,
              ((unsigned long)full < (unsigned long)-4096 && full) ? full : "(critical file)");
    args->ret = (u64)(long)-EACCES;
    args->skip_origin = 1;
}

/* THREAT-17 enforcement site: net for fds opened before the module armed. */
static void aw_permission_before(hook_fargs2_t *args, void *udata)
{
    void *file = (void *)args->arg0;
    u64 mask = args->arg1;
    struct aw_path fp;
    char buf[192];
    char *full = NULL;
    void *inode;
    u32 mode;
    u32 rdev;

    (void)udata;

    if (!aw_armed || !file)
        return;
    if (!(mask & MAY_WRITE))
        return;
    if (!aw_watches_current())
        return;

    memcpy(&fp, (const char *)file + aw_off.file_f_path, sizeof(fp));

    if (aw_file_write_protected(&fp, buf, sizeof(buf))) {
        full = d_path(&fp, buf, sizeof(buf));
        aw_record(AW_KIND_WRITE,
                  ((unsigned long)full < (unsigned long)-4096 && full) ? full : "(critical file)");
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
        return;
    }

    inode = *(void **)((const char *)file + aw_off.file_f_inode);
    if (!inode)
        return;
    mode = aw_i_mode(inode, aw_off.inode_i_mode);
    if ((mode & S_IFMT) != S_IFBLK)
        return;

    {
        const char *base;
        full = d_path(&fp, buf, sizeof(buf));
        base = ((unsigned long)full < (unsigned long)-4096 && full) ? aw_last_component(full) : NULL;
        if (base && (aw_str_prefix(base, "loop") || aw_str_prefix(base, "zram")))
            return;
        memcpy(&rdev, (const char *)inode + aw_off.inode_i_rdev, sizeof(rdev));
        if (aw_dev_is_critical(rdev) ||
            (base && full && aw_str_prefix(full, "/dev/block/"))) {
            aw_record(AW_KIND_BLOCKDEV, (base && full) ? full : "(raw block device)");
            args->ret = (u64)(long)-EACCES;
            args->skip_origin = 1;
        }
    }
}

/* THREAT-15 enforcement site: fscrypt key removal = crypto-erase. */
static void aw_ioctl_before(hook_fargs4_t *args, void *udata)
{
    void *file = (void *)args->arg0;
    u64 cmd = args->arg1;

    (void)udata;

    if (!aw_armed || !file)
        return;
    if (!aw_watches_current())
        return;

    if ((unsigned char)((cmd >> 8) & 0xff) == AW_FSCRYPT_IOCTL_TYPE &&
        ((cmd & 0xff) == AW_FSCRYPT_IOC_REMOVE_KEY || (cmd & 0xff) == AW_FSCRYPT_IOC_REMOVE_KEY_ALL)) {
        struct aw_path fp;
        char buf[192];
        char *full;

        memcpy(&fp, (const char *)file + aw_off.file_f_path, sizeof(fp));
        full = d_path(&fp, buf, sizeof(buf));
        aw_record(AW_KIND_IOCTL,
                  ((unsigned long)full < (unsigned long)-4096 && full) ? full : "(fscrypt mount)");
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
    }
}

struct aw_pt_regs_head {
    u64 x[31]; /* x0..x30, x0 at offset 0 */
};

/* THREAT-16 enforcement site. No comm exemption here on purpose. */
static void aw_reboot_before(hook_fargs4_t *args, void *udata)
{
    u64 magic1, magic2, cmd, arg;
    char target[64];
    long n;
    size_t i;

    (void)udata;

    if (!aw_armed)
        return;
    if (!aw_watches_root_no_comm())
        return;

    if (has_syscall_wrapper) {
        const struct aw_pt_regs_head *regs = (const struct aw_pt_regs_head *)(unsigned long)args->arg0;
        if (!regs)
            return;
        magic1 = regs->x[0];
        magic2 = regs->x[1];
        cmd = regs->x[2];
        arg = regs->x[3];
    } else {
        magic1 = args->arg0;
        magic2 = args->arg1;
        cmd = args->arg2;
        arg = args->arg3;
    }

    if (magic1 != AW_REBOOT_MAGIC1)
        return;
    if (magic2 != AW_REBOOT_MAGIC2 && magic2 != AW_REBOOT_MAGIC2A &&
        magic2 != AW_REBOOT_MAGIC2B && magic2 != AW_REBOOT_MAGIC2C)
        return;

    if (cmd == AW_REBOOT_CMD_KEXEC) {
        aw_record(AW_KIND_REBOOT, "kexec");
        args->ret = (u64)(long)-EACCES;
        args->skip_origin = 1;
        return;
    }

    if (cmd != AW_REBOOT_CMD_RESTART2)
        return;

    memset(target, 0, sizeof(target));
    n = strncpy_from_user(target, (const char *)(unsigned long)arg, sizeof(target) - 1);
    if (n <= 0)
        target[0] = '\0'; /* unreadable target: falls through the block list */
    /* target stays NUL-terminated by the memset above. */

    for (i = 0; i < AW_ARRAY_SIZE(aw_reboot_targets); i++) {
        if (!aw_strcmp(target, aw_reboot_targets[i]) ||
            aw_str_prefix(target, aw_reboot_targets[i])) {
            aw_record(AW_KIND_REBOOT, target[0] ? target : "(empty)");
            args->ret = (u64)(long)-EACCES;
            args->skip_origin = 1;
            return;
        }
    }
}

/* ------------------------------------------------------- arm / disarm */

static void aw_add_dev(u32 dev)
{
    int i;

    for (i = 0; i < aw_dev_count; i++) {
        if (aw_devs[i] == dev)
            return;
    }
    if (aw_dev_count < AW_MAX_DEVS)
        aw_devs[aw_dev_count++] = dev;
}

static void aw_add_dev_path(const char *path)
{
    struct aw_path p;
    void *inode;
    u32 mode;
    u32 rdev;

    /* by-name entries are symlinks to the real node: follow them. */
    if (kern_path(path, LOOKUP_FOLLOW, &p))
        return;
    inode = aw_dentry_inode(p.dentry);
    if (!inode) {
        path_put(&p);
        return;
    }
    mode = aw_i_mode(inode, aw_off.inode_i_mode);
    if ((mode & S_IFMT) != S_IFBLK) {
        path_put(&p);
        return;
    }
    memcpy(&rdev, (const char *)inode + aw_off.inode_i_rdev, sizeof(rdev));
    path_put(&p);

    aw_add_dev(rdev);
    /* The primary disk keeps minor 0: guarding MKDEV(major, 0) blocks
     * "dd of=/dev/block/mmcblk0" style whole-disk writes. */
    aw_add_dev((rdev >> 8) << 8);
}

static void aw_fill_devices(void)
{
    size_t d, b, s;
    char path[96];

    if (aw_dev_ready)
        return;

    for (d = 0; d < AW_ARRAY_SIZE(aw_by_name_dirs); d++) {
        for (b = 0; b < AW_ARRAY_SIZE(aw_part_bases); b++) {
            for (s = 0; s < AW_ARRAY_SIZE(aw_slots); s++) {
                kf_snprintf(path, sizeof(path), "%s/%s%s", aw_by_name_dirs[d], aw_part_bases[b],
                            aw_slots[s]);
                aw_add_dev_path(path);
            }
        }
    }
    for (d = 0; d < AW_ARRAY_SIZE(aw_whole_disks); d++)
        aw_add_dev_path(aw_whole_disks[d]);

    aw_dev_ready = aw_dev_count > 0;
    aw_info("armed prep: %d critical block devices resolved\n", aw_dev_count);
}

static void aw_unpin_all(void)
{
    size_t i;

    if (aw_pin_data.ok) {
        path_put(&aw_pin_data.p);
        aw_pin_data.ok = false;
    }
    if (aw_pin_adb.ok) {
        path_put(&aw_pin_adb.p);
        aw_pin_adb.ok = false;
    }
    for (i = 0; i < AW_ARRAY_SIZE(aw_pin_deep); i++) {
        if (aw_pin_deep[i].ok) {
            path_put(&aw_pin_deep[i].p);
            aw_pin_deep[i].ok = false;
        }
    }
    for (i = 0; i < AW_ARRAY_SIZE(aw_pin_storage); i++) {
        if (aw_pin_storage[i].ok) {
            path_put(&aw_pin_storage[i].p);
            aw_pin_storage[i].ok = false;
        }
    }
    for (i = 0; i < AW_ARRAY_SIZE(aw_pin_write); i++) {
        if (aw_pin_write[i].ok) {
            path_put(&aw_pin_write[i].p);
            aw_pin_write[i].ok = false;
        }
    }
}

static void aw_fill_pins(void)
{
    size_t i;

    if (!aw_pin_data.ok && !kern_path("/data", LOOKUP_FOLLOW, &aw_pin_data.p)) {
        aw_pin_data.ok = true;
        aw_pin_data.name = "/data";
    }
    if (!aw_pin_adb.ok && !kern_path("/data/adb", LOOKUP_FOLLOW, &aw_pin_adb.p)) {
        aw_pin_adb.ok = true;
        aw_pin_adb.name = "/data/adb";
    }
    for (i = 0; i < AW_ARRAY_SIZE(aw_deep_dirs); i++) {
        if (aw_pin_deep[i].ok)
            continue;
        if (!kern_path(aw_deep_dirs[i], LOOKUP_FOLLOW, &aw_pin_deep[i].p)) {
            aw_pin_deep[i].ok = true;
            aw_pin_deep[i].name = aw_deep_dirs[i];
        }
    }
    for (i = 0; i < AW_ARRAY_SIZE(aw_storage_dirs); i++) {
        if (aw_pin_storage[i].ok)
            continue;
        if (!kern_path(aw_storage_dirs[i], LOOKUP_FOLLOW, &aw_pin_storage[i].p)) {
            aw_pin_storage[i].ok = true;
            aw_pin_storage[i].name = aw_storage_dirs[i];
        }
    }
    for (i = 0; i < AW_ARRAY_SIZE(aw_write_dirs); i++) {
        if (aw_pin_write[i].ok)
            continue;
        if (!kern_path(aw_write_dirs[i], LOOKUP_FOLLOW, &aw_pin_write[i].p)) {
            aw_pin_write[i].ok = true;
            aw_pin_write[i].name = aw_write_dirs[i];
        }
    }
}

/* Count of currently pinned entries (for status/selftest). */
static int aw_count_pins(void)
{
    int n = 0;
    size_t i;

    if (aw_pin_data.ok)
        n++;
    if (aw_pin_adb.ok)
        n++;
    for (i = 0; i < AW_ARRAY_SIZE(aw_pin_deep); i++)
        if (aw_pin_deep[i].ok)
            n++;
    for (i = 0; i < AW_ARRAY_SIZE(aw_pin_storage); i++)
        if (aw_pin_storage[i].ok)
            n++;
    for (i = 0; i < AW_ARRAY_SIZE(aw_pin_write); i++)
        if (aw_pin_write[i].ok)
            n++;
    return n;
}

static int aw_install_hook(void *func, int argno, void *before)
{
    int rc;
    size_t i;

    if (!func)
        return 0; /* optional target absent on this kernel */

    rc = hook_wrap(func, argno, before, NULL, NULL);
    if (rc != HOOK_NO_ERR)
        return rc;

    for (i = 0; i < AW_ARRAY_SIZE(aw_hooks); i++) {
        if (!aw_hooks[i].installed) {
            aw_hooks[i].func = func;
            aw_hooks[i].before = before;
            aw_hooks[i].installed = true;
            aw_hook_count++;
            return 0;
        }
    }
    return -ENOSPC;
}

static void aw_uninstall_all(void)
{
    int i;

    for (i = AW_ARRAY_SIZE(aw_hooks) - 1; i >= 0; i--) {
        if (!aw_hooks[i].installed)
            continue;
        hook_unwrap_remove(aw_hooks[i].func, aw_hooks[i].before, NULL, 1);
        aw_hooks[i].installed = false;
        aw_hook_count--;
    }
}

static int aw_arm(void)
{
    int rc;
    int narg_reboot = has_syscall_wrapper ? 1 : 4;

    if (aw_armed)
        return 0;

    aw_fill_devices();
    aw_fill_pins();

    /* Core three: open / unlink / rmdir. Failure unwinds everything. */
    rc = aw_install_hook((void *)security_file_open, 1, aw_file_open_before);
    if (rc)
        goto fail;
    rc = aw_install_hook((void *)security_inode_unlink, 2, aw_unlink_before);
    if (rc)
        goto fail;
    rc = aw_install_hook((void *)security_inode_rmdir, 2, aw_unlink_before);
    if (rc)
        goto fail;

    /* Best effort extras: degrade gracefully, never block arming. */
    if (aw_install_hook((void *)security_inode_rename, 4, aw_rename_before))
        aw_warn("rename hook unavailable\n");
    if (aw_install_hook((void *)security_inode_setattr, kver >= AW_KVER(6, 9, 0) ? 3 : 2,
                        aw_setattr_before))
        aw_warn("setattr hook unavailable\n");
    if (aw_fn_bprm_check &&
        aw_install_hook(aw_fn_bprm_check, 1, aw_bprm_before))
        aw_warn("exec hook unavailable\n");
    if (aw_fn_file_truncate &&
        aw_install_hook(aw_fn_file_truncate, 1, aw_truncate_before))
        aw_warn("truncate hook unavailable\n");
    if (aw_install_hook((void *)security_file_permission, 2, aw_permission_before))
        aw_warn("permission hook unavailable\n");
    if (aw_install_hook((void *)security_file_ioctl, 3, aw_ioctl_before))
        aw_warn("ioctl hook unavailable\n");
    if (hook_syscalln(__AW_NR_reboot, narg_reboot, aw_reboot_before, NULL, NULL))
        aw_warn("reboot hook unavailable\n");
    else if (aw_hook_count < (int)AW_ARRAY_SIZE(aw_hooks)) {
        aw_hooks[aw_hook_count].func = (void *)(unsigned long)__AW_NR_reboot;
        aw_hooks[aw_hook_count].before = aw_reboot_before;
        aw_hooks[aw_hook_count].installed = true;
        aw_hook_count++;
    }

    aw_armed = true;
    aw_info("armed: %d hooks, %d devices, %d pinned trees\n", aw_hook_count, aw_dev_count,
            aw_count_pins());
    return 0;

fail:
    aw_err("arm failed: %d, unwinding\n", rc);
    aw_uninstall_all();
    aw_unpin_all();
    return rc;
}

static void aw_disarm(void)
{
    if (!aw_armed && !aw_hook_count)
        return;

    /* The reboot hook went through hook_syscalln, not hook_wrap. */
    int i;
    for (i = AW_ARRAY_SIZE(aw_hooks) - 1; i >= 0; i--) {
        if (!aw_hooks[i].installed)
            continue;
        if (aw_hooks[i].before == aw_reboot_before)
            unhook_syscalln(__AW_NR_reboot, aw_reboot_before, NULL);
        else
            hook_unwrap_remove(aw_hooks[i].func, aw_hooks[i].before, NULL, 1);
        aw_hooks[i].installed = false;
        aw_hook_count--;
    }

    aw_armed = false;
    aw_trust_until = 0;
    aw_unpin_all();
    aw_dev_count = 0;
    aw_dev_ready = false;
    aw_info("disarmed\n");
}

/* ------------------------------------------------------------- ctl0 */

static void aw_status_line(char *buf, size_t len)
{
    u64 blocked = __atomic_add_fetch(&aw_blocked, 0, __ATOMIC_RELAXED);
    unsigned long left = 0;

    if (aw_trust_until) {
        long delta = (long)(aw_trust_until - jiffies);
        if (delta > 0)
            left = (unsigned long)delta * 1000UL / aw_hz + 1;
    }
    kf_snprintf(buf, len, "armed=%u blocked=%llu trust=%lu hooks=%d devs=%d pins=%d", aw_armed ? 1u : 0u,
                (unsigned long long)blocked, left, aw_hook_count, aw_dev_count, aw_count_pins());
}

static long aw_ctl0(const char *ctl_args, char *out_msg, int outlen)
{
    char buf[256];

    buf[0] = '\0';

    if (ctl_args && aw_str_prefix(ctl_args, "trust")) {
        unsigned int sec = aw_parse_uint(ctl_args + 5);

        if (sec > AW_MAX_TRUST_SECONDS)
            sec = AW_MAX_TRUST_SECONDS;
        aw_trust_until = sec ? (jiffies + (unsigned long)sec * aw_hz) : 0;
        kf_snprintf(buf, sizeof(buf), "trust=%u", sec);
    } else if (ctl_args && aw_str_prefix(ctl_args, "off")) {
        aw_disarm();
        kf_snprintf(buf, sizeof(buf), "disarmed hooks=%d", aw_hook_count);
    } else if (ctl_args && aw_str_prefix(ctl_args, "on")) {
        int rc = aw_arm();

        if (rc)
            kf_snprintf(buf, sizeof(buf), "arm error=%d", rc);
        else
            aw_status_line(buf, sizeof(buf));
    } else if (ctl_args && aw_str_prefix(ctl_args, "last")) {
        kf_snprintf(buf, sizeof(buf), "last=%s pid=%u comm=%s target=%s", aw_kind_name(aw_last.kind),
                    aw_last.pid, aw_last.comm, aw_last.target);
    } else if (ctl_args && aw_str_prefix(ctl_args, "selftest")) {
        u64 blocked = __atomic_add_fetch(&aw_blocked, 0, __ATOMIC_RELAXED);
        int offsets_ok = aw_off.file_f_mode && aw_off.file_f_path && aw_off.file_f_inode &&
                         aw_off.inode_i_mode && aw_off.inode_i_rdev && aw_off.dentry_d_inode &&
                         aw_off.bprm_filename && aw_off.cred_euid && aw_off.mm_exe_file &&
                         aw_off.task_flags && aw_off.iattr_ia_valid;

        kf_snprintf(buf, sizeof(buf),
                    "SELFTEST %s offsets=%u armed=%u hooks=%d devs=%d pins=%d blocked=%llu",
                    (offsets_ok && (!aw_armed || aw_hook_count >= 3) && aw_count_pins() > 0 && aw_dev_ready)
                        ? "PASS"
                        : "FAIL",
                    offsets_ok ? 1u : 0u, aw_armed ? 1u : 0u, aw_hook_count, aw_dev_count,
                    aw_count_pins(), (unsigned long long)blocked);
    } else {
        aw_status_line(buf, sizeof(buf));
    }

    if (out_msg && outlen > 0) {
        unsigned long n = (unsigned long)outlen < sizeof(buf) ? (unsigned long)outlen : sizeof(buf);

        _copy_to_user(out_msg, buf, n);
    }

    return 0;
}

/* ------------------------------------------------------------- takeover */

/*
 * The legacy guard (feature/antiwipe.c) may be armed through its own KPM API
 * by an older thin module. Two guards double-block harmlessly, but single
 * ownership is cleaner: disarm the built-in one when present. Both symbols
 * are resolved at runtime so this module also loads on kernels without the
 * legacy feature compiled in.
 */
static void aw_takeover_legacy(void)
{
    int (*active)(void);
    void (*disarm_fn)(void);

    active = (int (*)(void))kallsyms_lookup_name("ksu_antiwipe_active");
    if (!active)
        return;
    if (!active())
        return;
    disarm_fn = (void (*)(void))kallsyms_lookup_name("ksu_antiwipe_disarm");
    if (disarm_fn) {
        disarm_fn();
        aw_warn("legacy in-kernel guard disarmed (single-owner takeover)\n");
    }
}

/* --------------------------------------------------------- init / exit */

static long antiwipe_init(const char *args, const char *event, void *reserved)
{
    int rc;
    bool auto_arm = true;

    (void)reserved;
    (void)event;

    if (args && kf_strstr(args, "armed=0"))
        auto_arm = false;

    /* Engine facts must line up with the assumptions baked into this module. */
    if (kver < AW_KVER(5, 10, 0)) {
        aw_err("kernel %u too old\n", kver);
        return -EINVAL;
    }
    if (!has_syscall_wrapper)
        aw_info("no syscall wrappers: reboot hook reads args directly\n");

    /* jiffies_to_msecs(100000) = 100000 * 1000 / HZ  =>  HZ = 1e8 / ms */
    {
        unsigned int ms = jiffies_to_msecs(100000);

        aw_hz = ms ? (100000000u / ms) : 250;
        if (aw_hz < 50 || aw_hz > 2000)
            aw_hz = 250;
    }

    aw_fn_file_truncate = (void *)kallsyms_lookup_name("security_file_truncate");
    aw_fn_bprm_check = aw_opt_bprm_target();

    rc = aw_btf_init();
    if (rc) {
        aw_err("btf bootstrap failed: %d — refusing to load (fail closed)\n", rc);
        return rc;
    }

    aw_takeover_legacy();

    if (auto_arm) {
        rc = aw_arm();
        if (rc) {
            aw_btf_shutdown();
            return rc;
        }
    }

    aw_info("loaded (event=%s args=%s) armed=%u\n", event ? event : "-", args ? args : "-",
            aw_armed ? 1u : 0u);
    return 0;
}

static long antiwipe_exit(void *reserved)
{
    (void)reserved;
    aw_disarm();
    aw_btf_shutdown();
    aw_info("unloaded\n");
    return 0;
}

KPM_NAME("antiwipe");
KPM_VERSION("2.0.0-full");
KPM_LICENSE("GPL v2");
KPM_AUTHOR("XECKernelPro");
KPM_DESCRIPTION("Full anti-wipe guard: blocks partition/disk writes, protected path removal, key-file overwrite, fscrypt key removal, wipe reboot escapes and dangerous flashing tools. All logic in-module, BTF-offset based, unload = unguarded.");

KPM_INIT(antiwipe_init);
KPM_CTL0(aw_ctl0);
KPM_EXIT(antiwipe_exit);
