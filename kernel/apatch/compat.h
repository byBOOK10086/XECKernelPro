/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef __KSU_APATCH_COMPAT_H
#define __KSU_APATCH_COMPAT_H

/*
 * APatch / KernelPatch 兼容层：应答用户态在 syscall 45 上的 supercall
 * （KPatch-Next 的 kpmd、APatch 的 apd/ap 等），把 KPM 命令转发到本项目
 * 已有的 KPM 引擎。仅 arm64；未开 CONFIG_KSU_APATCH_COMPAT 时是空实现。
 */
int ksu_apatch_compat_init(void);
void ksu_apatch_compat_exit(void);

#endif /* __KSU_APATCH_COMPAT_H */
