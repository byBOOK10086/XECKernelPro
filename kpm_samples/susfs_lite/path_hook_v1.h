/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef SUSFS_PATH_HOOK_V1_H
#define SUSFS_PATH_HOOK_V1_H
/* Stable LP64 engine contract. No kernel-private types cross this boundary.
 * Engine owns native probes, user copy (NUL-terminated path, max 96 bytes),
 * entry uid capture, and result update. Failed/truncated user copies skip
 * filter. Native callback -> KPM filter must use a noinline __nocfi bridge:
 * the freestanding KPM deliberately has no kernel CFI type metadata.
 * unregister is synchronous: no callback may access context after return.
 * register publishes *handle ONLY on success; failures leave it NULL.
 * Engine rejects unsupported symbols/argument indices before installation.
 */
typedef long (*kpm_path_filter_v1)(const char *path, unsigned int uid,
                                   long result, void *context);
extern unsigned int kpm_path_hook_abi_v1(void);
extern int kpm_path_hook_register_v1(const char *symbol, unsigned int filename_arg,
                                    kpm_path_filter_v1 filter, void *context,
                                    void **handle);
extern void kpm_path_hook_unregister_v1(void *handle);
#endif
