/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 * Copyright (c) 2026 XECKernelPro integration.
 *
 * XEC KPM core engine (KernelPatch LKM engine, fully vendored). This header
 * is the integration surface the rest of the tree talks to; the vendored
 * loader/hook/kstorage files keep their upstream kp_ names.
 */
#ifndef _KSU_KPMCORE_ENGINE_H_
#define _KSU_KPMCORE_ENGINE_H_

#include <linux/types.h>
#include <linux/compiler.h>

/* Module-registry surface used by the legacy kpm/kpm.c bridge when it routes
 * into this engine (load path prototypes already live in module.h; these
 * three were missing there-from and broke the LKM builds with implicit
 * declarations). Defined in module.c. */
long kp_module_control0(const char *name, const char *ctl_args, char __user *out_msg, int outlen);
int kp_list_modules(char *out_names, int size);
int kp_get_module_info(const char *name, char *out_info, int size);

/* Bring up / tear down the engine. Called from core/init.c (and the exit
 * path) when CONFIG_KSU_KPM_AP is on. Idempotent; safe to call when the
 * underlying syscall table is unavailable (init fails cleanly). */
int kp_engine_init(void);
void kp_engine_exit(void);

/* True once kp_engine_init() brought the loader + hook runtime up. When
 * active, the legacy kpm/kpm.c bridge functions and the apatch supercall
 * compat route through this engine instead of the legacy loader. */
bool kp_engine_active(void);

/* The KernelPatch supercall dispatch (user/uapi/scdefs.h command set).
 * Callers are responsible for authentication: the standalone syscall-45
 * handler installed by kp_supercall_install() checks manager/su-allow,
 * and apatch/compat.c only routes ADMIN-credentialed requests here. */
long kp_handle_supercall(long cmd, long a1, long a2, long a3, long a4);

/* Standalone syscall-45 channel (used only when CONFIG_KSU_APATCH_COMPAT is
 * off). With the compat layer enabled the apatch hook owns syscall 45 and
 * these are no-ops. */
int kp_supercall_install(void);
void kp_supercall_uninstall(void);

#endif /* _KSU_KPMCORE_ENGINE_H_ */
