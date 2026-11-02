/* SPDX-License-Identifier: GPL-2.0 */
#ifndef XEC_KPM_PATH_HOOK_H
#define XEC_KPM_PATH_HOOK_H
#include <linux/types.h>
unsigned int kpm_path_hook_abi_v1(void);
int kpm_path_hook_register_v1(const char *symbol, unsigned int filename_arg,
    long (*filter)(const char *path, unsigned int uid, long result, void *context),
    void *context, void **handle);
void kpm_path_hook_unregister_v1(void *handle);
void kp_path_hooks_drain_owner(unsigned long start, unsigned long size);
#endif
