/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 * Copyright (c) 2026 XECKernelPro integration.
 *
 * The slice of the KernelPatch supercall ABI that this kernel still implements:
 * the SU allowlist data plane and its kstorage backing groups.
 *
 * Everything else from upstream user/uapi/scdefs.h (the SUPERCALL_* command
 * numbers, panic/test/bootlog hooks, KERNELPATCH_* paths) was only consumed by
 * the removed module loader and is gone; the live command numbers are in
 * apatch/scdefs.h.
 */
#ifndef _KP_UAPI_SCDEF_H_
#define _KP_UAPI_SCDEF_H_

#include <linux/types.h>

/* kstorage groups: group 0 is the SU allowlist, group 1 the AP module exclude
 * flag table. Both are managed through GRANT_UID/REVOKE_UID and friends. */
#define KSTORAGE_SU_LIST_GROUP 0
#define KSTORAGE_EXCLUDE_LIST_GROUP 1

#define SUPERCALL_SCONTEXT_LEN 0x60

struct su_profile {
    uid_t uid;
    uid_t to_uid;
    char scontext[SUPERCALL_SCONTEXT_LEN];
};

#ifdef ANDROID
#define SU_PATH "/system/bin/kp"
#define ALL_ALLOW_SCONTEXT_MAGISK "u:r:magisk:s0"
#else
#define SU_PATH "/usr/bin/kp"
#define ALL_ALLOW_SCONTEXT_MAGISK "u:r:kernel:s0"
#endif

#endif /* _KP_UAPI_SCDEF_H_ */
