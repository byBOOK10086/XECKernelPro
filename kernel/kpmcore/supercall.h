/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 * Copyright (c) 2026 XECKernelPro integration.
 */
#ifndef _KSU_KPMCORE_SUPERCALL_H_
#define _KSU_KPMCORE_SUPERCALL_H_

#include <linux/types.h>

/* Set once kp_engine_init() completed; cleared by kp_engine_exit(). Defined
 * in supercall.c, read through kp_engine_active() (kpm_engine.h). */
extern bool kp_engine_active_state;

int kp_supercall_install(void);
void kp_supercall_uninstall(void);

#endif /* _KSU_KPMCORE_SUPERCALL_H_ */
