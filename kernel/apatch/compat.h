/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef __KSU_APATCH_COMPAT_H
#define __KSU_APATCH_COMPAT_H

/*
 * APatch / KernelPatch 兼容层：应答用户态在 syscall 45 上的 supercall
 * （KPatch-Next、APatch 的 apd/ap 等），提供 SU 方言（允许列表 + 凭据）与
 * kstorage KV 存储。仅 arm64；未开 CONFIG_KSU_APATCH_COMPAT 时是空实现。
 */
int ksu_apatch_compat_init(void);
void ksu_apatch_compat_exit(void);

#endif /* __KSU_APATCH_COMPAT_H */
