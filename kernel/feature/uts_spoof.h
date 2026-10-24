#ifndef __KSU_H_UTS_SPOOF
#define __KSU_H_UTS_SPOOF

#include <linux/rwsem.h>
#include <linux/utsname.h>

#include "uapi/supercall.h"

void ksu_spoof_version(const char *spoof_release, const char *spoof_version);

int ksu_set_spoof_version(const char *release, const char *version);

/*
 * XECKernelPro extension (ioctl 115/116): per-app uname identity.
 * ksu_uts_spoof_rewrite() is called from the uname syscall hook after the
 * real syscall succeeded; it copies the uid's release/version overrides
 * into the userspace buffer (kernel uts state untouched).
 */
void ksu_uts_spoof_rewrite(const char __user *buf);

int ksu_uts_spoof_set(const void __user *arg);
int ksu_uts_spoof_get(void __user *arg);

void ksu_uts_spoof_exit(void);

#endif
