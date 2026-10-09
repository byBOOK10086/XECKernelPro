#ifndef __KSU_H_ALLOWLIST
#define __KSU_H_ALLOWLIST

#include <linux/cred.h>
#include <linux/types.h>
#include <linux/uidgid.h>
#include "uapi/app_profile.h"
#include "manager/manager_identity.h"

#define PER_USER_RANGE 100000
#define WEBVIEW_ZYGOTE_UID 1053
#define FIRST_APPLICATION_UID 10000
#define LAST_APPLICATION_UID 19999
#define FIRST_ISOLATED_UID 99000
#define LAST_ISOLATED_UID 99999

void ksu_allowlist_init(void);

void ksu_allowlist_exit(void);

void ksu_load_allow_list(void);

void ksu_show_allow_list(void);

// Check if the uid is in allow list
bool __ksu_is_allow_uid(uid_t uid);
#define ksu_is_allow_uid(uid) unlikely(__ksu_is_allow_uid(uid))

// Check if the uid is in allow list, or current is ksu domain root
bool __ksu_is_allow_uid_for_current(uid_t uid);
#define ksu_is_allow_uid_for_current(uid) unlikely(__ksu_is_allow_uid_for_current(uid))

bool ksu_get_allow_list(int *array, u16 length, u16 *out_length, u16 *out_total, bool allow);

void ksu_prune_allowlist(bool (*is_uid_exist)(uid_t, char *, void *), void *data);
void ksu_persistent_allow_list();

// should be called with rcu read lock
struct app_profile *ksu_get_app_profile(uid_t uid);
// only used to put the app_profile returned by ksu_get_app_profile
void ksu_put_app_profile(struct app_profile *);
int ksu_set_app_profile(struct app_profile *);

bool ksu_uid_should_umount(uid_t uid);
struct root_profile *ksu_get_root_profile(uid_t uid);
// only used to put the root_profile returned by ksu_get_root_profile
void ksu_put_root_profile(struct root_profile *);

static inline bool is_appuid(uid_t uid)
{
    uid_t appid = uid % PER_USER_RANGE;
    return appid >= FIRST_APPLICATION_UID && appid <= LAST_APPLICATION_UID;
}

static inline bool is_isolated_process(uid_t uid)
{
    uid_t appid = uid % PER_USER_RANGE;
    return appid >= FIRST_ISOLATED_UID && appid <= LAST_ISOLATED_UID;
}

/*
 * Legacy KernelSU userspace ABIs: the prctl(0xdeadbeef, ...) supercall and the
 * reboot-magic driver install. Upstream answers both for every process so that
 * third-party consumers (Zygisk Next and friends) keep working, but that also
 * lets any installed app fingerprint the root implementation and install a
 * driver descriptor, i.e. it is an unauthenticated root tell as well as an
 * unnecessary attack surface. Restrict both to clients that are already
 * privileged: root, the manager app, and uids that hold a root grant.
 * CONFIG_KSU_OPEN_LEGACY_ABI restores the upstream open behaviour.
 */
static inline bool ksu_is_trusted_abi_client(void)
{
    uid_t uid = current_uid().val;

    if (uid == 0)
        return true;
    if (ksu_is_manager_appid_valid() && is_manager())
        return true;
    return ksu_is_allow_uid(uid);
}
#endif
