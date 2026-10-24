/* SPDX-License-Identifier: GPL-2.0-only */
#ifndef __KSU_H_SUPERKEY
#define __KSU_H_SUPERKEY

#include "uapi/supercall.h"
#include <linux/types.h>

bool ksu_superkey_is_set(void);
bool ksu_superkey_guard_armed(void);
void ksu_superkey_set_guard(bool armed);

/* ioctl handlers (dispatch.c) */
int ksu_superkey_set(const void __user *arg);
int ksu_superkey_clear(const void __user *arg);
bool ksu_superkey_auth(const void __user *arg);
int ksu_superkey_status(void __user *arg);

void ksu_superkey_init(void);
void ksu_superkey_exit(void);

/* self-contained SHA-256 (no crypto API dependency) */
void ksu_sha256(const u8 *data, size_t len, u8 out[KSU_SUPERKEY_HASH_LEN]);

#endif
