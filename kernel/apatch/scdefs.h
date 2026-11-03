/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * APatch / KernelPatch supercall ABI —— 只记录接口事实（常量、命令号、结构体
 * 布局）。本文件由 XECKernelPro 独立实现，不含上游 KernelPatch/APatch 的实现
 * 代码，也不 include 它们的头文件。
 *
 * 用户态调用形式（两族通用）：
 *   syscall(45, key_ptr,
 *           (ver << 32) | (magic << 16) | (cmd & 0xFFFF),
 *           a1, a2, a3, a4);
 * 内核侧只把 arg1 的低 16 位当命令号，魔数只用于挑 HELLO 的应答值。本实现额外
 * 要求魔数命中已知值（见 compat.c 的说明），以免真实 brk/truncate 调用被误判。
 *
 * 命令集只剩基础/版本、SU 方言与 kstorage 两族；内核模块装载面已从本内核移除。
 */
#ifndef __KSU_APATCH_SCDEFS_H
#define __KSU_APATCH_SCDEFS_H

/* arm64 的 45 是 brk，x86_64 是 truncate —— 两族客户端都打这个号 */
#define SC_NR_SUPERCALL 45

#define SC_MAGIC_KP 0x1158 /* KernelPatch / APatch / SukiSU */
#define SC_MAGIC_KPNEXT 0x2026 /* KernelSU-Next / KPatch-Next 客户端 */

#define SC_HELLO_MAGIC_KP 0x11581158u
#define SC_HELLO_MAGIC_KPNEXT 0x20262026u

/* 基础/版本 */
#define SC_CMD_HELLO 0x1000
#define SC_CMD_BUILD_TIME 0x1007
#define SC_CMD_KERNELPATCH_VER 0x1008
#define SC_CMD_KERNEL_VER 0x1009
#define SC_CMD_SKEY_GET 0x100a
#define SC_CMD_SKEY_SET 0x100b

/* SU 方言（允许列表 + 凭据），实现见 apatch/su_sc.c / suallow.c / accctl.c */
#define SC_CMD_SU 0x1010
#define SC_CMD_SU_TASK 0x1011
#define SC_CMD_SU_GRANT_UID 0x1100
#define SC_CMD_SU_REVOKE_UID 0x1101
#define SC_CMD_SU_NUMS 0x1102
#define SC_CMD_SU_LIST 0x1103
#define SC_CMD_SU_PROFILE 0x1104
#define SC_CMD_SU_GET_PATH 0x1110
#define SC_CMD_SU_RESET_PATH 0x1111

/* kstorage：SU 允许列表（group 0）/ 模块排除表（group 1）的载体 */
#define SC_CMD_KSTORAGE_ALLOC_GROUP 0x1040
#define SC_CMD_KSTORAGE_WRITE 0x1041
#define SC_CMD_KSTORAGE_READ 0x1042
#define SC_CMD_KSTORAGE_LIST_IDS 0x1043
#define SC_CMD_KSTORAGE_REMOVE 0x1044
#define SC_CMD_KSTORAGE_REMOVE_GROUP 0x1045

/* 字符串长度上限（接口事实） */
#define SC_KEY_MAX_LEN 0x40

/*
 * 本兼容层对外声明的 KernelPatch 版本号（(maj << 16) | (min << 8) | patch）。
 * 客户端据此决定命令字编码（>= 0x0a05 才用 (ver << 32) | (magic << 16) | cmd
 * 的形式），并据此判断可用命令集。我们实现的是 0.13.x 的命令集：0x0d05。
 */
#define SC_REPORTED_KPVER 0x0d05

#endif /* __KSU_APATCH_SCDEFS_H */
