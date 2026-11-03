/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 *
 * KernelPatch logging macros. Upstream also carried the version block here; the
 * version is now reported from apatch/scdefs.h (SC_REPORTED_KPVER), so only the
 * log helpers are left.
 */
#ifndef _KP_LKM_H_
#define _KP_LKM_H_

#include <linux/printk.h>

#define KPLKM_TAG "kernelpatch-lkm"
#define logki(fmt, ...) pr_info(KPLKM_TAG ": " fmt, ##__VA_ARGS__)
#define logke(fmt, ...) pr_err(KPLKM_TAG ": " fmt, ##__VA_ARGS__)
#define logkw(fmt, ...) pr_warn(KPLKM_TAG ": " fmt, ##__VA_ARGS__)
#define logkd(fmt, ...) pr_debug(KPLKM_TAG ": " fmt, ##__VA_ARGS__)

#endif /* _KP_LKM_H_ */
