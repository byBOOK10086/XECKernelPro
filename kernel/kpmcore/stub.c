// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (c) 2026 XECKernelPro integration.
 *
 * Non-ARM64 stub surface for the KPM core engine. The engine itself
 * (hook framework, loader, kstorage, supercall channel) is AArch64-only —
 * on x86_64/riscv builds this file provides the link-time surface the
 * arch-neutral callers (kpm/kpm.c routers, core/init.c, apatch/compat.c)
 * reference, so every entry keeps working and simply reports
 * "engine unavailable". The legacy kpm/ loader remains the sole engine
 * there, exactly as before this port landed.
 */
#include <linux/errno.h>
#include <linux/types.h>

#include "kpm_engine.h"

bool kp_engine_active(void)
{
	return false;
}

int kp_engine_init(void)
{
	return -EOPNOTSUPP;
}

void kp_engine_exit(void)
{
}

long kp_handle_supercall(long cmd, long a1, long a2, long a3, long a4)
{
	(void)cmd;
	(void)a1;
	(void)a2;
	(void)a3;
	(void)a4;
	return -EOPNOTSUPP;
}

/* Loader surface referenced by the legacy kpm/kpm.c routers. */
long __attribute__((unused)) kp_load_module_path(const char *path, const char *args,
						 void __user *reserved)
{
	(void)path;
	(void)args;
	(void)reserved;
	return -EOPNOTSUPP;
}

long __attribute__((unused)) kp_load_module_path_event(const char *path, const char *args,
						       const char *event, void __user *reserved)
{
	(void)path;
	(void)args;
	(void)event;
	(void)reserved;
	return -EOPNOTSUPP;
}

long __attribute__((unused)) kp_unload_module(const char *name, void __user *reserved)
{
	(void)name;
	(void)reserved;
	return -EOPNOTSUPP;
}

int __attribute__((unused)) kp_get_module_nums(void)
{
	return 0;
}

int __attribute__((unused)) kp_list_modules(char *out_names, int size)
{
	(void)out_names;
	(void)size;
	return 0;
}

int __attribute__((unused)) kp_get_module_info(const char *name, char *out_info, int size)
{
	(void)name;
	(void)out_info;
	(void)size;
	return -EOPNOTSUPP;
}

long __attribute__((unused)) kp_module_control0(const char *name, const char *ctl_args,
						char __user *out_msg, int outlen)
{
	(void)name;
	(void)ctl_args;
	(void)out_msg;
	(void)outlen;
	return -EOPNOTSUPP;
}

/* kpmsuki surface (kp_engine_init never installs the bridge off-arm64, and
 * CONFIG_KSU_KPM_SUKI cannot be selected without the arm64 engine, but the
 * symbols stay linkable for the IS_ENABLED() call sites in core.c). */
int __attribute__((unused)) sukisu_kpm_supercall_install(void)
{
	return -EOPNOTSUPP;
}

void __attribute__((unused)) sukisu_kpm_supercall_uninstall(void)
{
}
