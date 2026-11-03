/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef __KSU_APATCH_AUTH_H
#define __KSU_APATCH_AUTH_H

#include <linux/types.h>

/*
 * 调用者凭据等级：
 *   NONE  —— 非 root、没给 key，也不是管理器；
 *   SU    —— 通过 "su" 字面量/白名单路径进来的普通授权；
 *   ADMIN —— 真 superkey、或 root、或管理器。
 *
 * 实际放行面（与上游 KernelPatch 的差别，刻意收紧）：当前只有 HELLO 与版本
 * 查询对 SU 档开放，其余命令（含全部 SU / kstorage 族）都要求 ADMIN。上游把
 * SU 档也放给 SUPERCALL_SU 一族，那等于让白名单里的任意 uid 直接换 root；本
 * 内核对 root 的唯一权威是 KernelSU 允许列表（policy/allowlist.c），所以这里
 * 不再开第二条提权路径。要放开必须先想清楚这个前提。
 */
enum sc_cred {
    SC_CRED_NONE = 0,
    SC_CRED_SU,
    SC_CRED_ADMIN,
};

/* 依据用户态传入的 arg0（key 指针，可为 NULL）判定凭据等级 */
enum sc_cred sc_cred_of(u64 key_ptr);

/* 启动时调用一次：尝试读取 /data/adb/ap/superkey（读不到就只认 root/管理器） */
void sc_auth_init(void);

/* 供 SKEY_SET 使用；key 长度不足 1 或超过 SC_KEY_MAX_LEN 时返回 -EINVAL */
int sc_auth_set_key(const char __user *ukey);

/* 供 SKEY_GET 使用；返回写入长度或负 errno */
long sc_auth_get_key(char __user *ubuf, int buflen);

#endif /* __KSU_APATCH_AUTH_H */
