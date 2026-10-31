/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Kernel-to-kernel ABI v2, ported verbatim from SukiSU Ultra
 * (SukiSU_KernelPatch_patch kernel/patch/sukisu/supercall.h). Keep in sync
 * with the bridge side (kpmsuki/bridge.h) and SukiSU extension/kpm/bridge.h.
 *
 * x0=magic, x1=op, x2=a1, x3=a2, x4=a3, x5=a4.
 * Called directly through __arm64_sys_truncate with an EL1 pt_regs frame.
 * Never expose kernel pointers through a userspace syscall.
 * The magic is a PUBLIC protocol tag, not a secret or authentication token.
 * EL0 requests always fall through to the original syscall.
 * EL1h is a calling convention, not proof of SukiSU caller identity.
 */
#ifndef _KSU_KPMSUKI_SUPERCALL_H_
#define _KSU_KPMSUKI_SUPERCALL_H_

/* Public framing tags: ALL must match. These are not authentication. */
#define SUKISU_KPM_SUPERCALL_MAGIC 0x53554b504d000002UL
#define SUKISU_KPM_SUPERCALL_MAGIC2 0x4b504d4252494447UL
#define SUKISU_KPM_SUPERCALL_MAGIC3 0x53554b494e544552UL
/* x6=magic2, x7=magic3, x8=ABI version; x9..x30 reserved (zero).
 * Internal synthetic pt_regs only; the userspace extension ABI is unchanged.
 * Event-aware loading is an optional operation; older SukiSU callers remain
 * compatible with the ABI and newer callers can fall back when op 8 is absent.
 */
#define SUKISU_KPM_OUTPUT_MAX 4096
/* Op numbers 1..8 live in include/uapi/supercall.h (SUKISU_KPM_LOAD ..
 * SUKISU_KPM_LOAD_EVENT, static const __u32); do NOT #define them here. */
#define SUKISU_KPM_PROBE 0
#define SUKISU_KPM_ABI_VERSION 2

/* Install the inline hook on __arm64_sys_truncate that answers the SukiSU
 * bridge protocol (ops 1..8) by dispatching into the kpmcore loader.
 * Requires CONFIG_KSU_KPM_AP (the engine provides hook_wrap1 and the
 * loader). Returns 0 on success. */
int sukisu_kpm_supercall_install(void);
void sukisu_kpm_supercall_uninstall(void);

#endif /* _KSU_KPMSUKI_SUPERCALL_H_ */
