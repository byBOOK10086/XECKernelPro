/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 *
 * Credential switching. Ported from kernel/patch/common/accctl.c but uses the
 * real struct cred fields directly (the ko is KMI-bound, so offsets match),
 * avoiding KP's hardcoded cred_offset. SELinux translabel is implemented
 * directly (security_secctx_to_secid + a write into the cred's LSM blob): the
 * set_security_override_from_ctx helper it would otherwise use is gone from
 * GKI 5.15+, so there is nothing left to borrow from KP's sepolicy machinery.
 */
#ifndef _KP_LKM_ACCCTL_H_
#define _KP_LKM_ACCCTL_H_
#include <linux/types.h>

/* Resolve the SELinux translabel helper at init. */
int kp_accctl_init(void);

/* Grant root to the current task (uid/gid -> to_uid, all caps). */
int kp_commit_su(uid_t to_uid, const char *sctx);

/* Grant root to another task by pid. */
int kp_task_su(pid_t pid, uid_t to_uid, const char *sctx);

#endif /* _KP_LKM_ACCCTL_H_ */
