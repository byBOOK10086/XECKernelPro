// SPDX-License-Identifier: GPL-2.0-or-later
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 * Copyright (c) 2026 XECKernelPro integration.
 *
 * Engine lifecycle, adapted from lkm/core/init.c. This engine is not a
 * standalone module: core/init.c calls kp_engine_init() after the host
 * module's syscall hooks are up, and kernelsu_exit() calls kp_engine_exit().
 * The manager scan / sucompat-execve parts of upstream init are replaced by
 * the host tree's manager identity and sucompat; everything KPM-visible is
 * vendored verbatim.
 */
#include <linux/module.h>
#include <linux/slab.h>
#include <linux/version.h>

#include "include/kp_lkm.h"

#include "kpm_engine.h"
#include "patch_memory.h"
#include "symbol_resolver.h"
#include "syscall_table.h"
#include "hook_runtime.h"
#include "secpass.h"
#include "accctl.h"
#include "suallow.h"
#include "supercall.h"
#include "module.h"

int kp_engine_init(void)
{
	int rc;

	if (!IS_ENABLED(CONFIG_KSU_KPM_AP))
		return 0; /* engine compiled out: legacy loader stays authoritative */

	logki("KPM core engine loading (KP ABI %x, kernel %x)\n", kpver,
	      (unsigned)LINUX_VERSION_CODE);

	kp_symres_init();

	rc = kp_syscall_table_init();
	if (rc) {
		logke("syscall table resolve failed: %d\n", rc);
		return rc;
	}

	rc = kp_patch_memory_init();
	if (rc) {
		logke("patch_memory init failed: %d\n", rc);
		return rc;
	}

	rc = kp_kstorage_init();
	if (rc) {
		logke("kstorage init failed: %d\n", rc);
		return rc;
	}

	rc = kp_accctl_init();
	if (rc) {
		logke("accctl init failed: %d\n", rc);
		return rc;
	}

	rc = kp_sucompat_init();
	if (rc) {
		logke("su allowlist init failed: %d\n", rc);
		return rc;
	}

	/* Auto-apply APatch config (su path + package allowlist) from
	 * /data/adb/ap when present; a plain XEC device has no such files and
	 * this becomes a no-op. */
	kp_su_load_config();

	rc = kp_hook_runtime_init();
	if (rc) {
		/* No executable-memory path for hook trampolines (set_memory_x
		 * and execmem_alloc both missing): fail cleanly instead of
		 * calling a NULL allocator later. */
		logke("hook runtime init failed: %d\n", rc);
		return rc;
	}

	rc = kp_bypass_kcfi();
	if (rc)
		logkw("CFI bypass failed: %d\n", rc);

	rc = kp_kpm_init();
	if (rc)
		logkw("kpm loader init failed: %d (KPM supercalls will return -ENOSYS)\n", rc);

	rc = kp_supercall_install();
	if (rc) {
		logke("standalone supercall install failed: %d\n", rc);
		return rc;
	}

	if (IS_ENABLED(CONFIG_KSU_KPM_SUKI)) {
		rc = sukisu_kpm_supercall_install();
		if (rc)
			logkw("SukiSU bridge install failed: %d (bridge answers -EOPNOTSUPP)
", rc);
	}

	WRITE_ONCE(kp_engine_active_state, true);
	logki("KPM core engine ready\n");
	return 0;
}

/* Unload every loaded KPM so their exit callbacks run while the engine is
 * still resident; leaving them behind would keep kernel-text hooks pointing
 * at images nobody can unload any more. */
static void kp_engine_unload_all(void)
{
	/* Heap, not stack: a 2 KiB frame trips -Wframe-larger-than (same reason
	 * the apatch compat layer heap-allocates its buffers). */
	char *names = kzalloc(2048, GFP_KERNEL);
	int guard = 0;

	if (!names)
		return;

	while (kp_get_module_nums() > 0 && guard++ < 64) {
		int n = kp_list_modules(names, 2048);
		char *name = names;
		char *end;

		if (n <= 0)
			break;
		names[min_t(int, n, 2047)] = '\0';
		end = strchrnul(name, '\n');
		*end = '\0';
		if (!*name)
			break;
		logki("unloading KPM %s on engine teardown\n", name);
		if (kp_unload_module(name, NULL))
			break; /* a module refusing to exit stops the drain */
	}
	kfree(names);
}

void kp_engine_exit(void)
{
	if (!READ_ONCE(kp_engine_active_state))
		return;
	WRITE_ONCE(kp_engine_active_state, false);

	kp_engine_unload_all();
	if (IS_ENABLED(CONFIG_KSU_KPM_SUKI))
		sukisu_kpm_supercall_uninstall();
	kp_supercall_uninstall();
	/* Unhook the CFI bypass last so it shields the other teardown from
	 * spurious CFI failures on LKM/KPM text. */
	kp_bypass_kcfi_exit();
	logki("KPM core engine unloaded\n");
}
