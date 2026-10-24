//
// Created by weishu on 2022/12/9.
//

#ifndef KERNELSU_KSU_H
#define KERNELSU_KSU_H

#include <cstdint>
#include <string>
#include <vector>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/prctl.h>
#include <utility>

#include "uapi/ksu.h"

uint32_t get_kernel_uapi_version();

uint32_t get_manager_uapi_version();

uint32_t get_version();

bool uid_should_umount(int uid);

bool is_safe_mode();

bool is_lkm_mode();

bool is_lkm_bundled();

bool is_late_load_mode();

bool is_manager();

bool is_pr_build();

using p_key_t = char[KSU_MAX_PACKAGE_NAME];

bool set_app_profile(const app_profile *profile);

// Capability-level restricted su authorization (XECKernelPro extension).
// A restricted uid may only root-run whitelisted commands via `su -c`.
bool set_exec_policy(uid_t uid, bool restricted, const std::vector<std::string> &paths);

// APatch-style superkey guard (XECKernelPro extension, ioctl 112/113/114).
struct superkey_status {
    bool set;
    bool guard_armed;
};
bool set_superkey(const std::string &new_key, const std::string &old_key, bool clear);
bool superkey_auth(const std::string &key);
superkey_status superkey_status_query();
bool set_superkey_guard(bool armed);
std::vector<std::string> get_exec_policy(uid_t uid, bool &restricted);

int get_app_profile(app_profile *profile);

// Su compat
bool set_su_enabled(bool enabled);

bool is_su_enabled();

// Kernel umount
bool set_kernel_umount_enabled(bool enabled);

bool is_kernel_umount_enabled();

// SELinux hide
int set_selinux_hide_enabled(bool enabled);

bool is_selinux_hide_enabled();

bool get_allow_list(struct ksu_new_get_allow_list_cmd *);

inline std::pair<int, int> legacy_get_info() {
    int32_t version = -1;
    int32_t flags = 0;
    int32_t result = 0;
    prctl(0xDEADBEEF, 2, &version, &flags, &result);
    return {version, flags};
}

#endif //KERNELSU_KSU_H
