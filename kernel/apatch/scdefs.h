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
 */
#ifndef __KSU_APATCH_SCDEFS_H
#define __KSU_APATCH_SCDEFS_H

/* arm64 的 45 是 brk，x86_64 是 truncate —— 两族客户端都打这个号 */
#define SC_NR_SUPERCALL 45

#define SC_MAGIC_KP 0x1158 /* KernelPatch / APatch / SukiSU */
#define SC_MAGIC_KPNEXT 0x2026 /* KernelSU-Next / KPatch-Next（本仓库内置的 kpmd） */

#define SC_HELLO_MAGIC_KP 0x11581158u
#define SC_HELLO_MAGIC_KPNEXT 0x20262026u

/* 命令号有效区间：0x1000 … 0x1200 */
#define SC_CMD_MIN 0x1000
#define SC_CMD_MAX 0x1200

/* 基础/版本 */
#define SC_CMD_HELLO 0x1000
#define SC_CMD_KLOG 0x1004
#define SC_CMD_BUILD_TIME 0x1007
#define SC_CMD_KERNELPATCH_VER 0x1008
#define SC_CMD_KERNEL_VER 0x1009

/* KPM（本兼容层的主体） */
#define SC_CMD_KPM_LOAD 0x1020
#define SC_CMD_KPM_UNLOAD 0x1021
#define SC_CMD_KPM_CONTROL 0x1022
#define SC_CMD_KPM_NUMS 0x1030
#define SC_CMD_KPM_LIST 0x1031
#define SC_CMD_KPM_INFO 0x1032

/* 字符串长度上限（接口事实） */
#define SC_KEY_MAX_LEN 0x40
#define SC_KPM_NAME_LEN 32
#define SC_KPM_PATH_LEN 1024
#define SC_KPM_ARGS_LEN 1024
#define SC_KPM_INFO_NAME_LEN 64
#define SC_LIST_BUF_LEN 4096
#define SC_INFO_BUF_LEN 2048

/* KPM_LOAD 的回传结构（magic 必须匹配且 size 足够才启用） */
#define SC_LOAD_RESULT_MAGIC 0x4b504d52u /* "KPMR" */
#define SC_LOAD_ERROR_MSG_LEN 160

struct sc_kpm_load_result {
    unsigned int magic; /* in: SC_LOAD_RESULT_MAGIC */
    unsigned int size; /* in: >= sizeof(struct sc_kpm_load_result) */
    int code; /* out: 加载返回码 */
    char message[SC_LOAD_ERROR_MSG_LEN]; /* out */
};

/*
 * 本兼容层对外声明的 KernelPatch 版本号（(maj << 16) | (min << 8) | patch）。
 * 客户端据此决定命令字编码（>= 0x0a05 才用 (ver << 32) | (magic << 16) | cmd
 * 的形式），并据此判断可用命令集。我们实现的是 0.13.x 的命令集：0x0d05。
 */
#define SC_REPORTED_KPVER 0x0d05

#endif /* __KSU_APATCH_SCDEFS_H */
