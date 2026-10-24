/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * XECKernelPro extension: capability-level restricted su authorization.
 *
 * A restricted uid may only obtain root through `su -c <command>` when the
 * command (first token, basename match) is on its whitelist. The GRANT_ROOT
 * ioctl path is denied outright for restricted uids, and an interactive su
 * (no command) is denied as well — an arbitrary shell would bypass the
 * whitelist by construction.
 */
#ifndef __KSU_H_EXEC_POLICY
#define __KSU_H_EXEC_POLICY

#include "uapi/supercall.h"
#include <linux/types.h>

bool ksu_exec_policy_is_restricted(uid_t uid);

/**
 * Check whether the su invocation described by @argv (user pointer, may be
 * NULL) is allowed for the restricted @uid. argv layout follows toybox su:
 * argv[0] = "su", then options ("-c" etc.), then the command string.
 * Returns true when allowed (or when argv cannot be read and the caller
 * already treats that as "cannot prove it is allowed" — see callers).
 */
bool ksu_exec_policy_allows_argv(uid_t uid, const char __user *const __user *argv_user);

int ksu_exec_policy_set(const void __user *arg);
int ksu_exec_policy_get(void __user *arg);

void ksu_exec_policy_exit(void);

#endif
