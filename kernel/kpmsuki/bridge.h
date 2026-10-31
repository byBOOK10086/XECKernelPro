/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Kernel-only ABI v2, mirrored from SukiSU Ultra extension/kpm/bridge.h.
 * op 1..7 are the existing SUKISU_KPM_* extension operations. Op 8 is an
 * optional event-aware file load for boot-stage modules.
 * LOAD(path,args,user_reserved), UNLOAD(name,user_reserved), NUM(),
 * LIST(kernel_buf,size), INFO(name,kernel_buf,size),
 * CONTROL(name,args,user_output,size), VERSION(kernel_buf,size).
 */
#ifndef _KSU_KPMSUKI_BRIDGE_H_
#define _KSU_KPMSUKI_BRIDGE_H_

#include <linux/types.h>

/* Kernel-to-kernel bridge: probe the truncate supercall for a KP engine and
 * forward one operation. Returns the op result or a negative error
 * (-EOPNOTSUPP when no engine answers the probe). */
long sukisu_kpm_bridge(unsigned long op, unsigned long a1, unsigned long a2,
                       unsigned long a3, unsigned long a4);

/* SukiSU ksu_kpm_cmd control-plane entry (KSU_IOCTL_KPM_SUKI handler).
 * @arg is the user pointer to struct ksu_kpm_cmd; authorization is done by
 * the ioctl dispatcher (manager_or_root) before this is reached. */
int do_kpm_suki(void __user *arg);

#endif /* _KSU_KPMSUKI_BRIDGE_H_ */
