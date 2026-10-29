/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * XECKernelPro anti-wipe guard — KPM-controlled variant.
 *
 * The guard implementation (block device writes + protected path removal) is
 * compiled into the kernel but stays completely inert until a KPM module calls
 * ksu_antiwipe_arm(). There is no feature id, no manager switch and no boot
 * path that arms it, so an unfinished/buggy build can never lock a device in a
 * reboot loop: not loading the module is enough.
 *
 * The module (antiwipe.kpm) resolves these symbols from kallsyms through the
 * KSU KPM engine, which only exists in XECKernelPro kernels.
 */
#ifndef __KSU_H_ANTIWIPE
#define __KSU_H_ANTIWIPE

#include <linux/types.h>

/* Install the guard hooks. Returns 0 on success, negative errno otherwise. */
int ksu_antiwipe_arm(void);

/* Remove the hooks (idempotent). */
void ksu_antiwipe_disarm(void);

/* 1 while the guard is armed. */
int ksu_antiwipe_active(void);

/* Trust window for the manager's own flashing flow (seconds, 0 = drop now). */
void ksu_antiwipe_trust(u32 seconds);

/* Total number of blocked operations since the guard was armed. */
u64 ksu_antiwipe_blocked(void);

/*
 * Last blocked event. Any output pointer may be NULL; strings are copied with
 * a NUL terminator and truncated to the given buffer length.
 */
void ksu_antiwipe_last(u32 *kind, u32 *pid, u64 *ts_ms,
                       char *comm, u32 comm_len,
                       char *target, u32 target_len);

#endif /* __KSU_H_ANTIWIPE */
