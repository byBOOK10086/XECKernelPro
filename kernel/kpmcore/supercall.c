// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 * Copyright (c) 2026 XECKernelPro integration.
 *
 * KernelPatch supercall dispatch, ported from lkm/supercall/{supercall,dispatch}.c.
 * Two authentication fronts share this table:
 *   - apatch/compat.c (syscall-45 hook, 0x1158/0x2026 dialects) routes
 *     ADMIN-credentialed requests here and keeps HELLO/ver/build-time replies
 *     byte-identical to the legacy compat layer;
 *   - kp_supercall_install() below is the upstream standalone channel that
 *     hooks syscall 45 itself; it is only wired when CONFIG_KSU_APATCH_COMPAT
 *     is off (the compat hook owns syscall 45 otherwise).
 * The command surface is the full user/uapi/scdefs.h set that an engine can
 * answer: KPM load/unload/control/nums/list/info, kstorage CRUD, su family,
 * versions, hello, feature-control stub.
 */
#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/ptrace.h>
#include <linux/string.h>
#include <linux/syscalls.h>
#include <linux/user_namespace.h>

#include "include/scdefs.h"
#include "include/kp_lkm.h"
#include "include/kstorage.h"

#include "kpm_engine.h"
#include "kpm_sc.h"
#include "module.h"
#include "su_sc.h"
#include "suallow.h"
#include "syscall_table.h"

#ifndef CONFIG_KSU_DISABLE_MANAGER
#include "../../manager/manager_identity.h"
#endif
#include "../../policy/allowlist.h"

#define KP_SUPERCALL_NR 45 /* __NR3264_truncate */

static kp_syscall_fn_t kp_orig_truncate;

bool kp_engine_active_state;

bool kp_engine_active(void)
{
	return kp_engine_active_state;
}

long kp_handle_supercall(long cmd, long a1, long a2, long a3, long a4)
{
	switch (cmd) {
	case SUPERCALL_HELLO:
		return SUPERCALL_HELLO_MAGIC;
	case SUPERCALL_KERNELPATCH_VER:
		return kpver;
	case SUPERCALL_KERNEL_VER:
		return kp_kver();
	case SUPERCALL_BUILD_TIME:
		return kp_su_unsupported_buildtime((char __user *)a1, (int)a2);

	case SUPERCALL_SU:
		return kp_su_sc((struct su_profile __user *)a1);
	case SUPERCALL_SU_TASK:
		return kp_su_task_sc((pid_t)a1, (struct su_profile __user *)a2);
	case SUPERCALL_SU_GRANT_UID:
		return kp_su_grant_uid_sc((struct su_profile __user *)a1);
	case SUPERCALL_SU_REVOKE_UID:
		return kp_su_revoke_uid_sc((uid_t)a1);
	case SUPERCALL_SU_NUMS:
		return kp_su_allow_uid_nums_sc();
	case SUPERCALL_SU_LIST:
		return kp_su_allow_uid_list_sc((uid_t __user *)a1, (int)a2);
	case SUPERCALL_SU_PROFILE:
		return kp_su_allow_uid_profile_sc((uid_t)a1, (struct su_profile __user *)a2);
	case SUPERCALL_SU_GET_PATH:
		return kp_su_get_path_sc((char __user *)a1, (int)a2);
	case SUPERCALL_SU_RESET_PATH:
		return kp_su_reset_path_sc((const char __user *)a1);

	case SUPERCALL_KPM_LOAD:
		return kp_kpm_load_sc((const char __user *)a1, (const char __user *)a2,
				      (void __user *)a3);
	case SUPERCALL_KPM_UNLOAD:
		return kp_kpm_unload_sc((const char __user *)a1, (void __user *)a2);
	case SUPERCALL_KPM_CONTROL:
		return kp_kpm_control_sc((const char __user *)a1, (const char __user *)a2,
					 (char __user *)a3, (int)a4);
	case SUPERCALL_KPM_NUMS:
		return kp_kpm_nums_sc();
	case SUPERCALL_KPM_LIST:
		return kp_kpm_list_sc((char __user *)a1, (int)a2);
	case SUPERCALL_KPM_INFO:
		return kp_kpm_info_sc((const char __user *)a1, (char __user *)a2, (int)a3);

	case SUPERCALL_KSTORAGE_ALLOC_GROUP:
		if (a1 || a2 || a3 || a4)
			return -EINVAL;
		return kp_kstorage_alloc_group();
	case SUPERCALL_KSTORAGE_WRITE:
		return kp_kstorage_write((int)a1, (long)a2, (void __user *)a3, 0, (int)a4, true);
	case SUPERCALL_KSTORAGE_READ:
		return kp_kstorage_read((int)a1, (long)a2, (void __user *)a3, 0, (int)a4, true);
	case SUPERCALL_KSTORAGE_LIST_IDS:
		return kp_kstorage_list_ids((int)a1, (long __user *)a2, (int)a3, true);
	case SUPERCALL_KSTORAGE_REMOVE:
		if (a4)
			return -EINVAL;
		return kp_kstorage_remove((int)a1, (long)a2);
	case SUPERCALL_KSTORAGE_REMOVE_GROUP: {
		/* The LKM kstorage API removes one element at a time; a group
		 * wipe drains it through list_kstorage_ids(). */
		long ids[64];
		int gid = (int)a1;
		long rc = 0, i, n;

		if (a2 || a3 || a4)
			return -EINVAL;
		if (gid < 0 || gid >= KP_KSTORAGE_MAX_GROUP_NUM)
			return -EINVAL;
		do {
			n = kp_kstorage_list_ids(gid, ids, ARRAY_SIZE(ids), false);
			if (n < 0)
				return n;
			for (i = 0; i < n; i++) {
				rc = kp_kstorage_remove(gid, ids[i]);
				if (rc)
					return rc;
			}
		} while (n == ARRAY_SIZE(ids));
		return 0;
	}

	case SUPERCALL_SKEY_GET:
		/* The preset superkey store is owned by policy/superkey.c; the
		 * apatch compat layer answers SKEY_* itself, so reaching here
		 * means the standalone channel asked without a key store. */
		return -EOPNOTSUPP;

	default:
		logkd("supercall: unsupported cmd 0x%lx\n", cmd);
		return -ENOSYS;
	}
}

/* Commands an unauthenticated (su-allow but not manager) caller may use,
 * mirroring kp_supercall_allowed_for_su() from kernel/include/kpscauth.h. */
static bool kp_supercall_allowed_for_su(long cmd)
{
	switch (cmd) {
	case SUPERCALL_HELLO:
	case SUPERCALL_KLOG:
	case SUPERCALL_BUILD_TIME:
	case SUPERCALL_KERNELPATCH_VER:
	case SUPERCALL_KERNEL_VER:
	case SUPERCALL_SU:
	case SUPERCALL_SU_TASK:
	case SUPERCALL_SU_PROFILE:
	case SUPERCALL_SU_GET_PATH:
	case SUPERCALL_SU_RESET_PATH:
	case SUPERCALL_KSTORAGE_READ:
	case SUPERCALL_KSTORAGE_WRITE:
	case SUPERCALL_KSTORAGE_LIST_IDS:
	case SUPERCALL_KSTORAGE_REMOVE:
		return true;
	default:
		return false;
	}
}

static long kp_supercall_handler(const struct pt_regs *regs)
{
	uid_t uid = from_kuid(current_user_ns(), current_uid());
	bool manager;

#ifndef CONFIG_KSU_DISABLE_MANAGER
	manager = is_manager();
#endif
	/* The manager may grant/revoke allowlist entries; an already-allowed
	 * uid may use the SU supercalls. Anything else is a plain truncate. */
	if (!manager && !ksu_is_allow_uid_for_current(uid) && !kp_is_su_allow_uid(uid)) {
		if (kp_orig_truncate)
			return kp_orig_truncate(regs);
		return -ENOSYS;
	}

	long ver_xx_cmd = regs->regs[1];
	long cmd = ver_xx_cmd & 0xFFFF;
	if (cmd < SUPERCALL_HELLO || cmd > SUPERCALL_MAX) {
		return kp_orig_truncate ? kp_orig_truncate(regs) : -EINVAL;
	}

	if (!manager && !kp_supercall_allowed_for_su(cmd))
		return -EPERM;
	if (!manager && cmd == SUPERCALL_SU_PROFILE && (uid_t)regs->regs[2] != uid)
		return -EPERM;
	return kp_handle_supercall(cmd, regs->regs[2], regs->regs[3], regs->regs[4],
				   regs->regs[5]);
}

int kp_supercall_install(void)
{
	int rc;

	/* With the apatch compat layer on, its hook owns syscall 45 and this
	 * channel stays dormant; the compat dispatch routes to
	 * kp_handle_supercall() instead. */
	if (IS_ENABLED(CONFIG_KSU_APATCH_COMPAT))
		return 0;
	if (!kp_sys_call_table)
		return -ENOENT;
	rc = kp_syscall_hook(KP_SUPERCALL_NR, kp_supercall_handler, &kp_orig_truncate);
	if (rc)
		return rc;
	logki("supercall installed on syscall %d\n", KP_SUPERCALL_NR);
	return 0;
}

void kp_supercall_uninstall(void)
{
	if (!kp_orig_truncate)
		return;
	kp_syscall_unhook(KP_SUPERCALL_NR, kp_orig_truncate);
	kp_orig_truncate = NULL;
}
