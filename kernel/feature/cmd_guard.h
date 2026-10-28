/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * XECKernelPro: anti-brick command guard (feature KSU_FEATURE_CMD_GUARD).
 *
 * The guard vetoes the operations that can brick a locked device instead of
 * trying to parse command lines: a shell script may be encrypted, a busybox
 * applet may be renamed, and the next command may be assembled at runtime, but
 * the final raw write to a boot partition and the final unlink always reach the
 * same kernel entry points. Two LSM hooks cover them:
 *
 *   file_open      - write access to a boot-critical block device (or a whole
 *                    disk) from uid 0 is denied;
 *   inode_unlink / - removal of boot-critical paths (the top of the filesystem
 *   inode_rmdir      tree, /data/<critical>, /data/adb/<subtree>, ...) from
 *                    uid 0 is denied.
 *
 * The manager can open a bounded trust window (ioctl 117, OP_TRUST) around its
 * own flashing flows; the window expires on its own.
 */
#ifndef __KSU_H_CMD_GUARD
#define __KSU_H_CMD_GUARD

#include <linux/types.h>

void __init ksu_cmd_guard_init(void);
void __exit ksu_cmd_guard_exit(void);

/* ioctl 117 handler, arg points at struct ksu_cmd_guard_cmd (user memory). */
int ksu_cmd_guard_ioctl(void __user *arg);

/* Re-resolve the boot-critical block devices (process context only). */
void ksu_cmd_guard_refresh_devices(void);

#endif /* __KSU_H_CMD_GUARD */
