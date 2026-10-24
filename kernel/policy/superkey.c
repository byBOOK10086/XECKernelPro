// SPDX-License-Identifier: GPL-2.0-only
/*
 * XECKernelPro extension: APatch-style superkey guard for sensitive ioctls.
 *
 * Model (possession factor on top of the existing identity checks):
 *  - The manager sets a superkey; the kernel keeps only its SHA-256.
 *  - While the guard is enabled, a fixed set of *policy-mutating* ioctls
 *    (SET_APP_PROFILE / SET_SEPOLICY / SET_FEATURE / SET_EXEC_POLICY /
 *    SET_SUPERKEY) additionally requires the calling driver fd to have been
 *    authenticated with KSU_IOCTL_SUPERKEY_AUTH (plaintext key, kernel
 *    hashes and compares). Auth state lives on the file object and dies with
 *    the fd — no task-lifetime bookkeeping.
 *  - GRANT_ROOT is intentionally NOT guarded: app-level su already goes
 *    through the per-uid allow list, and guarding it would break every
 *    standard su client (no key slot in the su protocol).
 *
 * The hash is intentionally NOT persisted by the kernel: a reboot clears the
 * key (and thus opens the guard) until the manager re-arms it — same threat
 * model as an APatch superkey that lives in the patched image, except ours
 * is per-boot and manager-rearmed.
 */
#include "superkey.h"

#include <linux/mutex.h>
#include <linux/string.h>
#include <linux/uaccess.h>

#include "klog.h" // IWYU pragma: keep
#include "policy/feature.h"

static DEFINE_MUTEX(superkey_lock);
static u8 superkey_hash[KSU_SUPERKEY_HASH_LEN];
static bool superkey_present;

bool ksu_superkey_is_set(void)
{
    bool present;
    mutex_lock(&superkey_lock);
    present = superkey_present;
    mutex_unlock(&superkey_lock);
    return present;
}

/* SHA-256 over the NUL-terminated key; returns true when equal to stored. */
static bool key_matches(const u8 *key, size_t maxlen)
{
    u8 digest[KSU_SUPERKEY_HASH_LEN];
    bool match = false;
    size_t len = strnlen((const char *)key, maxlen);

    ksu_sha256(key, len, digest);
    mutex_lock(&superkey_lock);
    if (superkey_present && !memcmp(digest, superkey_hash, sizeof(digest)))
        match = true;
    mutex_unlock(&superkey_lock);
    return match;
}

int ksu_superkey_set(const void __user *arg)
{
    struct ksu_superkey_cmd cmd;
    bool had_key;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    if (!memchr(cmd.new_key, '\0', sizeof(cmd.new_key)))
        return -EINVAL; // must be NUL-terminated
    if (!cmd.new_key[0])
        return -EINVAL; // clearing uses old_key verification + empty new = clear? no: refuse here

    mutex_lock(&superkey_lock);
    had_key = superkey_present;
    mutex_unlock(&superkey_lock);

    // Replacing an existing key requires proving possession of the old one.
    if (had_key) {
        if (!cmd.has_old)
            return -EACCES;
        if (!memchr(cmd.old_key, '\0', sizeof(cmd.old_key)))
            return -EINVAL;
        if (!key_matches(cmd.old_key, sizeof(cmd.old_key))) {
            pr_warn("superkey: old key mismatch, refusing to change\n");
            return -EACCES;
        }
    }

    mutex_lock(&superkey_lock);
    ksu_sha256(cmd.new_key, strnlen((const char *)cmd.new_key, sizeof(cmd.new_key)), superkey_hash);
    superkey_present = true;
    mutex_unlock(&superkey_lock);
    pr_info("superkey: set (guard must be armed via SET_FEATURE)\n");
    return 0;
}

int ksu_superkey_clear(const void __user *arg)
{
    struct ksu_superkey_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;

    if (!key_matches(cmd.old_key, sizeof(cmd.old_key))) {
        pr_warn("superkey: clear refused (wrong key)\n");
        return -EACCES;
    }
    mutex_lock(&superkey_lock);
    superkey_present = false;
    memset(superkey_hash, 0, sizeof(superkey_hash));
    mutex_unlock(&superkey_lock);
    pr_info("superkey: cleared\n");
    return 0;
}

bool ksu_superkey_auth(const void __user *arg)
{
    struct ksu_superkey_auth_cmd cmd;

    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return false;
    if (!memchr(cmd.key, '\0', sizeof(cmd.key)))
        return false;
    return key_matches(cmd.key, sizeof(cmd.key));
}

int ksu_superkey_status(void __user *arg)
{
    struct ksu_superkey_status_cmd cmd = {};

    mutex_lock(&superkey_lock);
    cmd.set = superkey_present;
    mutex_unlock(&superkey_lock);
    cmd.guard_armed = cmd.set && ksu_superkey_guard_armed();

    if (copy_to_user(arg, &cmd, sizeof(cmd)))
        return -EFAULT;
    return 0;
}

void ksu_superkey_exit(void)
{
    mutex_lock(&superkey_lock);
    superkey_present = false;
    memset(superkey_hash, 0, sizeof(superkey_hash));
    mutex_unlock(&superkey_lock);
}

/* ---- guard arming (feature id KSU_FEATURE_SUPERKEY_GUARD) ---- */

static bool guard_armed;

bool ksu_superkey_guard_armed(void)
{
    return READ_ONCE(guard_armed);
}

void ksu_superkey_set_guard(bool armed)
{
    WRITE_ONCE(guard_armed, armed);
    pr_info("superkey: guard %s\n", armed ? "armed" : "disarmed");
}

/* ---- feature handler: KSU_FEATURE_SUPERKEY_GUARD ---- */

static int superkey_guard_get(u64 *value)
{
    *value = ksu_superkey_guard_armed() ? 1 : 0;
    return 0;
}

static int superkey_guard_set(u64 value)
{
    bool arm = value != 0;
    // Arming requires a key to exist; disarming is always allowed (the user
    // may need to recover from a lost key — disarm still needs nothing, but
    // the disarm path runs through SET_FEATURE which itself is guarded while
    // armed, so the auth fd requirement still applies. Recovery story:
    // reboot clears the guard (kernel hash is volatile), then disarm.)
    if (arm && !ksu_superkey_is_set())
        return -EINVAL;
    ksu_superkey_set_guard(arm);
    return 0;
}

static const struct ksu_feature_handler superkey_guard_handler = {
    .feature_id = KSU_FEATURE_SUPERKEY_GUARD,
    .name = "superkey_guard",
    .get_handler = superkey_guard_get,
    .set_handler = superkey_guard_set,
};

void ksu_superkey_init(void)
{
    int ret = ksu_register_feature_handler(&superkey_guard_handler);
    if (ret)
        pr_err("superkey: feature handler registration failed: %d\n", ret);
}
