#include <linux/rcupdate.h>
#include <linux/limits.h>
#include <linux/rculist.h>
#include <linux/mutex.h>
#include <linux/task_work.h>
#include <linux/capability.h>
#include <linux/compiler.h>
#include <linux/fs.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/list.h>
#include <linux/printk.h>
#include <linux/rculist.h>
#include <linux/slab.h>
#include <linux/types.h>
#include <linux/version.h>
#include <linux/vmalloc.h>
#include <linux/compiler_types.h>
#include <linux/hashtable.h>
#include <linux/kref.h>

#include "klog.h" // IWYU pragma: keep
#include "ksu.h"
#include "runtime/ksud_boot.h"
#include "selinux/selinux.h"
#include "policy/allowlist.h"
#include "policy/allowlist_journal.h"
#include "manager/manager_identity.h"
#include "infra/su_mount_ns.h"
#include "feature/shell_su_adb_only.h"

#define FILE_MAGIC 0x7f4b5355 // ' KSU', u32
#define FILE_FORMAT_VERSION 4 // u32

#define KSU_APP_PROFILE_PRESERVE_UID 9999 // NOBODY_UID
#define KSU_DEFAULT_SELINUX_DOMAIN "u:r:" KERNEL_SU_DOMAIN ":s0"

static DEFINE_MUTEX(allowlist_mutex);

// default profiles, these may be used frequently, so we cache it
static struct root_profile default_root_profile;
static struct non_root_profile default_non_root_profile;

static void __init init_default_profiles()
{
    kernel_cap_t full_cap = CAP_FULL_SET;

    default_root_profile.uid = 0;
    default_root_profile.gid = 0;
    default_root_profile.groups_count = 1;
    default_root_profile.groups[0] = 0;
    memcpy(&default_root_profile.capabilities.effective, &full_cap,
           sizeof(default_root_profile.capabilities.effective));
    default_root_profile.namespaces = KSU_NS_INHERITED;
    strcpy(default_root_profile.selinux_domain, KSU_DEFAULT_SELINUX_DOMAIN);

    // This means that we will umount modules by default!
    default_non_root_profile.umount_modules = true;

    default_root_profile.flags = 0;
}

struct perm_data {
    struct hlist_node list;
    struct rcu_head rcu;
    struct kref ref;
    struct app_profile profile;
};

// protected by rcu
#define ALLOW_LIST_BITS 8
static DEFINE_HASHTABLE(allow_list, ALLOW_LIST_BITS);
static u16 allow_list_count = 0;

#define KERNEL_SU_ALLOWLIST "/data/adb/ksu/.allowlist"

void ksu_persistent_allow_list(void);

struct app_profile *ksu_get_app_profile(uid_t uid)
{
    struct perm_data *p = NULL;
    bool found;

retry:
    found = false;
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid) {
            // found it, override it with ours
            found = true;
            break;
        }
    }

    if (!found)
        return NULL;

    if (!kref_get_unless_zero(&p->ref)) {
        goto retry;
    }

    return &p->profile;
}

static inline bool forbid_system_uid(uid_t uid)
{
#define SHELL_UID 2000
#define SYSTEM_UID 1000
    return uid < SHELL_UID && uid != SYSTEM_UID;
}

static bool profile_valid(struct app_profile *profile)
{
    if (!profile) {
        return false;
    }

    if (strnlen(profile->key, sizeof(profile->key)) >= sizeof(profile->key)) {
        pr_err("invalid app_profile key\n");
        return false;
    }

    if (profile->version != KSU_APP_PROFILE_VER) {
        pr_info("Unsupported profile version: %d\n", profile->version);
        return false;
    }

    if (profile->allow_su) {
#ifndef CONFIG_KSU_DISABLE_POLICY
        if (profile->rp_config.profile.groups_count > KSU_MAX_GROUPS) {
            pr_err("invalid groups_count in app_profile: %s\n", profile->key);
            return false;
        }

        static const size_t domain_len = sizeof(profile->rp_config.profile.selinux_domain);
        size_t len = strnlen(profile->rp_config.profile.selinux_domain, domain_len);

        if (len == 0 || len >= domain_len) {
            pr_err("invalid selinux_domain in app_profile: %s\n", profile->key);
            return false;
        }
#endif
    }

    return true;
}

static void release_perm_data(struct kref *ref)
{
    struct perm_data *p = container_of(ref, struct perm_data, ref);
    kfree_rcu(p, rcu);
}

static void put_perm_data(struct perm_data *data)
{
    kref_put(&data->ref, release_perm_data);
}

int ksu_set_app_profile(struct app_profile *profile)
{
    struct perm_data *p, *np;
    int result = 0;

    if (!profile_valid(profile)) {
        pr_err("Failed to set app profile: invalid profile!\n");
        return -EINVAL;
    }

#ifdef CONFIG_KSU_DISABLE_POLICY
    if (profile->allow_su) {
        profile->rp_config.use_default = true;
        memset(profile->rp_config.template_name, 0, sizeof(profile->rp_config.template_name));
        memset(&profile->rp_config.profile, 0, sizeof(profile->rp_config.profile));
    } else {
        profile->nrp_config.use_default = true;
        memset(&profile->nrp_config.profile, 0, sizeof(profile->nrp_config.profile));
    }
#endif

    // only allow default non root profile
    if (unlikely(profile->curr_uid == KSU_APP_PROFILE_PRESERVE_UID && strcmp(profile->key, "$") != 0)) {
        return -EINVAL;
    }

    mutex_lock(&allowlist_mutex);

    hash_for_each_possible (allow_list, p, list, profile->curr_uid) {
        if (profile->curr_uid == p->profile.curr_uid) {
            if (strcmp(profile->key, p->profile.key) != 0) {
                pr_warn("ksu_set_app_profile: key changed: uid=%d orig=%s new=%s\n", profile->curr_uid, p->profile.key,
                        profile->key);
            }
            // found it, just override it all!
            np = (struct perm_data *)kzalloc(sizeof(struct perm_data), GFP_KERNEL);
            if (!np) {
                result = -ENOMEM;
                goto out_unlock;
            }
            kref_init(&np->ref);
            memcpy(&np->profile, profile, sizeof(*profile));
            hlist_replace_rcu(&p->list, &np->list);
            put_perm_data(p);
            goto out;
        }
    }

    if (unlikely(allow_list_count == U16_MAX)) {
        pr_err("too many app profile\n");
        result = -E2BIG;
        goto out_unlock;
    }

    // not found, alloc a new node!
    np = (struct perm_data *)kzalloc(sizeof(struct perm_data), GFP_KERNEL);
    if (!np) {
        pr_err("ksu_set_app_profile alloc failed\n");
        result = -ENOMEM;
        goto out_unlock;
    }

    kref_init(&np->ref);
    memcpy(&np->profile, profile, sizeof(*profile));
    if (profile->allow_su) {
        pr_info("set root profile, key: %s, uid: %d, gid: %d, context: %s\n", profile->key, profile->curr_uid,
                profile->rp_config.profile.gid, profile->rp_config.profile.selinux_domain);
    } else {
        pr_info("set app profile, key: %s, uid: %d, umount modules: %d\n", profile->key, profile->curr_uid,
                profile->nrp_config.profile.umount_modules);
    }

    hash_add_rcu(allow_list, &np->list, np->profile.curr_uid);
    ++allow_list_count;

out:
    result = 0;

    if (unlikely(profile->curr_uid == KSU_APP_PROFILE_PRESERVE_UID)) {
        // set default non root profile
        default_non_root_profile.umount_modules = profile->nrp_config.profile.umount_modules;
    }

out_unlock:
    mutex_unlock(&allowlist_mutex);
    return result;
}

bool __ksu_is_allow_uid(uid_t uid)
{
    struct perm_data *p;

    if (forbid_system_uid(uid)) {
        // do not bother going through the list if it's system
        return false;
    }

    if (unlikely(is_uid_manager(uid))) {
        // manager is always allowed!
        return true;
    }

    if (unlikely(allow_shell) && uid == SHELL_UID) {
        return true;
    }

    rcu_read_lock();
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid && p->profile.allow_su) {
            rcu_read_unlock();
            return true;
        }
    }
    rcu_read_unlock();

    return false;
}

bool ksu_is_uid_granted_root(uid_t uid)
{
    if (unlikely(uid == 0)) {
        // already root, but only allow our domain.
        return is_ksu_domain();
    }
    return __ksu_is_allow_uid(uid);
}

bool __ksu_is_allow_uid_for_current(uid_t uid)
{
    if (unlikely(uid == SHELL_UID) && !ksu_shell_su_allowed_for_current()) {
        return false;
    }
    return ksu_is_uid_granted_root(uid);
}

bool ksu_uid_should_umount(uid_t uid)
{
    struct app_profile *profile;
    bool res;
    if (likely(ksu_is_manager_appid_valid()) && unlikely(ksu_get_manager_appid() == uid % PER_USER_RANGE)) {
        // we should not umount on manager!
        return false;
    }
#ifdef CONFIG_KSU_DISABLE_POLICY
    return !__ksu_is_allow_uid(uid);
#else
    rcu_read_lock();
    profile = ksu_get_app_profile(uid);
    if (!profile) {
        // no app profile found, it must be non root app
        res = default_non_root_profile.umount_modules;
    } else if (profile->allow_su) {
        // if found and it is granted to su, we shouldn't umount for it
        res = false;
    } else {
        // found an app profile
        if (profile->nrp_config.use_default) {
            res = default_non_root_profile.umount_modules;
        } else {
            res = profile->nrp_config.profile.umount_modules;
        }
    }
    rcu_read_unlock();

    if (profile)
        ksu_put_app_profile(profile);
    return res;
#endif
}

void ksu_put_app_profile(struct app_profile *profile)
{
    struct perm_data *p = container_of(profile, struct perm_data, profile);
    put_perm_data(p);
}

struct root_profile *ksu_get_root_profile(uid_t uid)
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    (void)uid;
    return &default_root_profile;
#else
    struct perm_data *p = NULL;
    struct root_profile *res;

    rcu_read_lock();
    if (is_uid_manager(uid)) {
        goto use_default;
    }

    if (unlikely(allow_shell && uid == SHELL_UID)) {
        goto use_default;
    }

retry:
    res = NULL;
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid && p->profile.allow_su) {
            if (!p->profile.rp_config.use_default) {
                if (!kref_get_unless_zero(&p->ref)) {
                    goto retry;
                }
                res = &p->profile.rp_config.profile;
            }
            break;
        }
    }

    if (unlikely(!res)) {
    use_default:
        res = &default_root_profile;
    }

    rcu_read_unlock();
    return res;
#endif
}

void ksu_put_root_profile(struct root_profile *profile)
{
    if (likely(profile == &default_root_profile))
        return;
    struct perm_data *p = container_of(profile, struct perm_data, profile.rp_config.profile);
    put_perm_data(p);
}

bool ksu_get_allow_list(int *array, u16 length, u16 *out_length, u16 *out_total, bool allow)
{
    struct perm_data *p = NULL;
    u16 i = 0, j = 0;
    int iter;
    rcu_read_lock();
    hash_for_each_rcu (allow_list, iter, p, list) {
        if (p->profile.allow_su == allow && !is_uid_manager(p->profile.curr_uid)) {
            if (j < length) {
                array[j++] = p->profile.curr_uid;
            }
            ++i;
        }
    }
    rcu_read_unlock();
    if (out_length) {
        *out_length = j;
    }
    if (out_total) {
        *out_total = i;
    }

    return true;
}

/* Fixed two-slot journal: never truncate the newest fully validated snapshot.
 * Recovery may select the previous committed generation after corruption; that
 * can restore an older grant/revocation state. This is crash recovery, not an
 * anti-rollback mechanism. A post-commit legacy mirror supports older kernels;
 * only the journal is crash-safe, so downgrade still has a mirror-write window.
 */
static DEFINE_MUTEX(allowlist_persist_mutex);
static const char *const journal_paths[] = { KERNEL_SU_ALLOWLIST ".0", KERNEL_SU_ALLOWLIST ".1" };

static int allowlist_io(struct file *fp, void *data, size_t size, loff_t *off, bool write)
{
    size_t done = 0;
    while (done < size) {
        ssize_t n = write ? kernel_write(fp, (char *)data + done, size - done, off) :
                            kernel_read(fp, (char *)data + done, size - done, off);
        if (n == -EINTR)
            continue;
        if (n <= 0)
            return n < 0 ? (int)n : (write ? -EIO : -EINVAL);
        done += n;
    }
    return 0;
}

static int allowlist_sync(struct file *fp)
{
    /* Same stable f_op interface already used by infra/file_wrapper.c. */
    if (!fp->f_op || !fp->f_op->fsync)
        return -EOPNOTSUPP;
    return fp->f_op->fsync(fp, 0, LLONG_MAX, 0);
}

static bool journal_profile_valid(struct app_profile *p)
{
    return profile_valid(p) &&
           (p->curr_uid != KSU_APP_PROFILE_PRESERVE_UID || !strcmp(p->key, "$"));
}

static int journal_read(unsigned int slot, unsigned char **image, struct ksu_j_meta *meta)
{
    unsigned char header[KSU_J_HEADER], extra;
    unsigned char *data = NULL;
    struct ksu_j_meta m;
    struct file *fp;
    loff_t off = 0;
    unsigned long length;
    unsigned int i;
    int rc;
    *image = NULL;
    fp = filp_open(journal_paths[slot], O_RDONLY | O_NOFOLLOW, 0);
    if (IS_ERR(fp))
        return PTR_ERR(fp);
    if (!S_ISREG(file_inode(fp)->i_mode)) {
        rc = -EINVAL;
        goto out;
    }
    rc = allowlist_io(fp, header, sizeof(header), &off, false);
    if (rc || ksu_j_header(header, sizeof(struct app_profile), &m)) {
        rc = rc ?: -EINVAL;
        goto out;
    }
    length = ksu_j_length(m.count, sizeof(struct app_profile));
    data = vmalloc(length);
    if (!data) {
        rc = -ENOMEM;
        goto out;
    }
    memcpy(data, header, sizeof(header));
    rc = allowlist_io(fp, data + sizeof(header), length - sizeof(header), &off, false);
    if (rc)
        goto out;
    /* Exact EOF is part of validity; a valid prefix is not a snapshot. */
    if (kernel_read(fp, &extra, 1, &off) != 0 ||
        ksu_j_validate(data, length, sizeof(struct app_profile), &m)) {
        rc = -EINVAL;
        goto out;
    }
    for (i = 0; i < m.count; i++) {
        struct app_profile *p = (void *)(data + KSU_J_HEADER + i * sizeof(*p));
        if (!journal_profile_valid(p)) {
            rc = -EINVAL;
            goto out;
        }
    }
    *meta = m;
    *image = data;
    data = NULL;
    rc = 0;
out:
    vfree(data);
    filp_close(fp, 0);
    return rc;
}

/* Compatibility only: current kernels never prefer this over a journal. */
static int allowlist_legacy_mirror(unsigned char *image, unsigned int count)
{
    u32 header[2] = { FILE_MAGIC, FILE_FORMAT_VERSION };
    struct file *fp;
    loff_t off = 0;
    int rc;
    fp = filp_open(KERNEL_SU_ALLOWLIST, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    if (IS_ERR(fp))
        return PTR_ERR(fp);
    rc = S_ISREG(file_inode(fp)->i_mode) ?
         allowlist_io(fp, header, sizeof(header), &off, true) : -EINVAL;
    if (!rc)
        rc = allowlist_io(fp, image + KSU_J_HEADER, count * sizeof(struct app_profile), &off, true);
    if (!rc)
        rc = allowlist_sync(fp);
    filp_close(fp, 0);
    if (rc)
        return rc;
    fp = filp_open("/data/adb/ksu", O_RDONLY | O_DIRECTORY, 0);
    if (IS_ERR(fp))
        return PTR_ERR(fp);
    rc = allowlist_sync(fp);
    filp_close(fp, 0);
    return rc;
}

static void do_persistent_allow_list(struct callback_head *_cb)
{
    struct ksu_j_meta meta[2];
    unsigned char *old[2] = { NULL, NULL }, *image = NULL;
    struct perm_data *p;
    struct file *fp;
    const struct cred *saved;
    unsigned long length;
    unsigned long long generation = 1;
    unsigned int count, n = 0, target = 0;
    int status[2], i, rc = 0;
    loff_t off = 0;

    mutex_lock(&allowlist_persist_mutex);
    saved = override_creds(ksu_cred);
    status[0] = journal_read(0, &old[0], &meta[0]);
    status[1] = journal_read(1, &old[1], &meta[1]);
    /* Resource/permission/I/O errors are not evidence that a slot is corrupt. */
    for (i = 0; i < 2; i++) {
        if (status[i] && status[i] != -ENOENT && status[i] != -EINVAL) {
            rc = status[i];
            goto out;
        }
    }
    if (status[0] && status[1] && (status[0] != -ENOENT || status[1] != -ENOENT)) {
        rc = -EINVAL; /* preserve corrupt journal evidence; do not overwrite */
        goto out;
    }
    if (!status[0] || !status[1]) {
        unsigned int newest = status[0] ? 1 : status[1] ? 0 :
                              (meta[1].generation > meta[0].generation ? 1 : 0);
        if (meta[newest].generation == ~0ULL) {
            rc = -EOVERFLOW;
            goto out;
        }
        generation = meta[newest].generation + 1;
        target = newest ^ 1;
    }
    mutex_lock(&allowlist_mutex);
    count = allow_list_count;
    length = ksu_j_length(count, sizeof(struct app_profile));
    image = vmalloc(length);
    if (!image) {
        rc = -ENOMEM;
        goto unlock_list;
    }
    hash_for_each(allow_list, i, p, list) {
        if (n >= count || !journal_profile_valid(&p->profile)) {
            rc = -EINVAL;
            goto unlock_list;
        }
        memcpy(image + KSU_J_HEADER + n++ * sizeof(p->profile), &p->profile, sizeof(p->profile));
    }
    if (n != count)
        rc = -EINVAL;
unlock_list:
    mutex_unlock(&allowlist_mutex);
    if (rc)
        goto out;
    ksu_j_seal(image, count, sizeof(struct app_profile), generation);
    /* The complete validated in-memory snapshot exists before O_TRUNC. */
    fp = filp_open(journal_paths[target], O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    if (IS_ERR(fp)) {
        rc = PTR_ERR(fp);
        goto out;
    }
    rc = S_ISREG(file_inode(fp)->i_mode) ? allowlist_io(fp, image, length, &off, true) : -EINVAL;
    if (!rc)
        rc = allowlist_sync(fp);
    filp_close(fp, 0);
    if (!rc) {
        /* Persist newly created slot directory entries as well as file data. */
        fp = filp_open("/data/adb/ksu", O_RDONLY | O_DIRECTORY, 0);
        if (IS_ERR(fp))
            rc = PTR_ERR(fp);
        else {
            rc = allowlist_sync(fp);
            filp_close(fp, 0);
        }
    }
    if (!rc) {
        int mirror_rc = allowlist_legacy_mirror(image, count);
        if (mirror_rc)
            pr_err("allowlist legacy mirror failed: %d; journal committed, downgrade permissions may be stale\n",
                   mirror_rc);
    }
out:
    if (rc)
        pr_err("allowlist journal save failed: %d; previous slot preserved\n", rc);
    vfree(image);
    vfree(old[0]);
    vfree(old[1]);
    revert_creds(saved);
    mutex_unlock(&allowlist_persist_mutex);
    kfree(_cb);
}

void ksu_persistent_allow_list()
{
    struct task_struct *tsk;

    rcu_read_lock();
    tsk = get_pid_task(find_vpid(1), PIDTYPE_PID);
    if (!tsk) {
        rcu_read_unlock();
        pr_err("save_allow_list find init task err\n");
        return;
    }
    rcu_read_unlock();

    struct callback_head *cb = kzalloc(sizeof(struct callback_head), GFP_KERNEL);
    if (!cb) {
        pr_err("save_allow_list alloc cb err\b");
        goto put_task;
    }
    cb->func = do_persistent_allow_list;
    if (task_work_add(tsk, cb, TWA_RESUME)) {
        kfree(cb);
        pr_warn("save_allow_list add task_work failed\n");
    }

put_task:
    put_task_struct(tsk);
}

static void migrate_profile(u32 version, struct app_profile *profile)
{
    char *domain;
    static const size_t domain_len = sizeof(profile->rp_config.profile.selinux_domain);

    switch (version) {
    case 2:
        if (profile->allow_su) {
            domain = profile->rp_config.profile.selinux_domain;
            if (strncmp(domain, "u:r:su:s0", domain_len) == 0) {
                strscpy_pad(domain, KSU_DEFAULT_SELINUX_DOMAIN, domain_len);
                pr_info("migrated domain of profile: %s\n", profile->key);
            }
        }
        fallthrough;
    case 3:
        if (profile->allow_su) {
            profile->rp_config.profile.flags = FLAG_KSU_NO_NEW_PRIVS;
        }
        break;
    }

    profile->version = KSU_APP_PROFILE_VER;
}

/* Allocate and validate everything before publishing any grant. */
static int allowlist_import(const unsigned char *profiles, unsigned int count, size_t stride, u32 version)
{
    struct hlist_head *staged;
    struct perm_data *p, *other;
    struct hlist_node *tmp;
    unsigned int n;
    int i, rc = 0;
    staged = kcalloc(1U << ALLOW_LIST_BITS, sizeof(*staged), GFP_KERNEL);
    if (!staged)
        return -ENOMEM;
    for (i = 0; i < (1U << ALLOW_LIST_BITS); i++)
        INIT_HLIST_HEAD(&staged[i]);
    for (n = 0; n < count; n++) {
        p = kzalloc(sizeof(*p), GFP_KERNEL);
        if (!p) {
            rc = -ENOMEM;
            goto free_staged;
        }
        memcpy(&p->profile, profiles + n * stride, stride);
        if (version < KSU_APP_PROFILE_VER)
            migrate_profile(version, &p->profile);
        if (!journal_profile_valid(&p->profile)) {
            kfree(p);
            rc = -EINVAL;
            goto free_staged;
        }
        hash_for_each_possible(staged, other, list, p->profile.curr_uid) {
            if (other->profile.curr_uid == p->profile.curr_uid) {
                kfree(p);
                rc = -EINVAL;
                goto free_staged;
            }
        }
        kref_init(&p->ref);
        hash_add(staged, &p->list, p->profile.curr_uid);
    }
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe(allow_list, i, tmp, p, list) {
        hash_del_rcu(&p->list);
        put_perm_data(p);
    }
    allow_list_count = 0;
    default_non_root_profile.umount_modules = true;
    hash_for_each_safe(staged, i, tmp, p, list) {
        hash_del(&p->list);
        hash_add_rcu(allow_list, &p->list, p->profile.curr_uid);
        ++allow_list_count;
        if (p->profile.curr_uid == KSU_APP_PROFILE_PRESERVE_UID)
            default_non_root_profile.umount_modules = p->profile.nrp_config.profile.umount_modules;
    }
    mutex_unlock(&allowlist_mutex);
free_staged:
    hash_for_each_safe(staged, i, tmp, p, list) {
        hash_del(&p->list);
        kfree(p);
    }
    kfree(staged);
    return rc;
}

void ksu_load_allow_list(void)
{
    struct ksu_j_meta meta[2];
    unsigned char *data[2] = { NULL, NULL }, *legacy = NULL;
    struct file *fp;
    loff_t off = 0, size;
    u32 header[2];
    unsigned int count, chosen;
    size_t stride;
    int status[2], rc = 0;
    bool migrate = false;
#ifdef CONFIG_KSU_DISABLE_POLICY
    pr_info("allowlist load skipped because policy is disabled\n");
    return;
#endif
    mutex_lock(&allowlist_persist_mutex);
    status[0] = journal_read(0, &data[0], &meta[0]);
    status[1] = journal_read(1, &data[1], &meta[1]);
    if (!status[0] || !status[1]) {
        /* Never silently roll back on transient read/permission failures. */
        int bad = status[0] ? status[0] : status[1];
        if (bad && bad != -ENOENT && bad != -EINVAL) {
            rc = bad;
            goto out;
        }
        chosen = status[0] ? 1 : status[1] ? 0 :
                 (meta[1].generation > meta[0].generation ? 1 : 0);
        rc = allowlist_import(data[chosen] + KSU_J_HEADER, meta[chosen].count,
                              sizeof(struct app_profile), KSU_APP_PROFILE_VER);
        goto out;
    }
    if (status[0] != -ENOENT || status[1] != -ENOENT) {
        pr_err("allowlist journal exists but is invalid/unreadable; refusing legacy grants\n");
        rc = -EINVAL;
        goto out;
    }
    /* Legacy migration is permitted ONLY when neither journal slot exists. */
    fp = filp_open(KERNEL_SU_ALLOWLIST, O_RDONLY | O_NOFOLLOW, 0);
    if (IS_ERR(fp)) {
        rc = PTR_ERR(fp);
        goto out;
    }
    size = i_size_read(file_inode(fp));
    rc = allowlist_io(fp, header, sizeof(header), &off, false);
    if (rc || !S_ISREG(file_inode(fp)->i_mode) || header[0] != FILE_MAGIC ||
        header[1] < 2 || header[1] > KSU_APP_PROFILE_VER) {
        rc = rc ?: -EINVAL;
        goto close_legacy;
    }
    stride = header[1] < KSU_APP_PROFILE_VER ? 776 : sizeof(struct app_profile);
    if (size < sizeof(header) || stride > sizeof(struct app_profile) ||
        (size - sizeof(header)) % stride ||
        (size - sizeof(header)) / stride > KSU_J_MAX_COUNT) {
        rc = -EINVAL;
        goto close_legacy;
    }
    count = (size - sizeof(header)) / stride;
    legacy = vmalloc(count ? count * stride : 1);
    if (!legacy) {
        rc = -ENOMEM;
        goto close_legacy;
    }
    rc = allowlist_io(fp, legacy, count * stride, &off, false);
    if (!rc) {
        unsigned char extra;
        if (kernel_read(fp, &extra, 1, &off) != 0)
            rc = -EINVAL;
    }
    if (!rc)
        rc = allowlist_import(legacy, count, stride, header[1]);
    migrate = !rc;
close_legacy:
    filp_close(fp, 0);
out:
    if (rc && rc != -ENOENT) {
        /* A repeated load must not retain grants from an earlier epoch. */
        allowlist_import(NULL, 0, sizeof(struct app_profile), KSU_APP_PROFILE_VER);
        pr_err("allowlist load failed: %d; grants cleared, evidence preserved\n", rc);
    }
    vfree(data[0]);
    vfree(data[1]);
    vfree(legacy);
    mutex_unlock(&allowlist_persist_mutex);
    if (migrate)
        ksu_persistent_allow_list();
}

void ksu_prune_allowlist(bool (*is_uid_valid)(uid_t, char *, void *), void *data)
{
    struct perm_data *np = NULL;
    struct hlist_node *tmp;
    int i;

    if (!ksu_boot_completed) {
        pr_info("boot not completed, skip prune\n");
        return;
    }

    bool modified = false;
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe (allow_list, i, tmp, np, list) {
        uid_t uid = np->profile.curr_uid;
        char *package = np->profile.key;
        // we use this uid for special cases, don't prune it!
        bool is_preserved_uid = uid == KSU_APP_PROFILE_PRESERVE_UID || uid == WEBVIEW_ZYGOTE_UID;
        if (!is_preserved_uid && !is_uid_valid(uid, package, data)) {
            modified = true;
            hlist_del_rcu(&np->list);
            put_perm_data(np);
            --allow_list_count;
        }
    }
    mutex_unlock(&allowlist_mutex);

    if (modified) {
        smp_mb();
        ksu_persistent_allow_list();
    }
}

void __init ksu_allowlist_init(void)
{
    init_default_profiles();
}

void __exit ksu_allowlist_exit(void)
{
    struct perm_data *np = NULL;
    struct hlist_node *tmp;
    int i;

    // free allowlist
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe (allow_list, i, tmp, np, list) {
        hlist_del(&np->list);
        put_perm_data(np);
    }
    mutex_unlock(&allowlist_mutex);
}
