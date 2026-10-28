#ifndef __KSU_UAPI_FEATURE_H
#define __KSU_UAPI_FEATURE_H

enum ksu_feature_id {
    KSU_FEATURE_SU_COMPAT = 0,
    KSU_FEATURE_KERNEL_UMOUNT = 1,
    KSU_FEATURE_SULOG = 2,
    KSU_FEATURE_ADB_ROOT = 3,
    KSU_FEATURE_SELINUX_HIDE = 4,
    // 5 was webview_zygote_umount (v3.3.0), keep it unused so stale configs don't apply
    KSU_FEATURE_SHELL_SU_ADB_ONLY = 6,

    // XECKernelPro extension: arm the APatch-style superkey guard over
    // policy-mutating ioctls (requires a superkey to be set first).
    KSU_FEATURE_SUPERKEY_GUARD = 7,

    // XECKernelPro extension: anti-brick command guard. While on, the kernel
    // vetoes writes to boot-critical block devices and removal of
    // boot-critical paths performed by root (see feature/cmd_guard.c).
    KSU_FEATURE_CMD_GUARD = 8,

    KSU_FEATURE_MAX
};

#endif
