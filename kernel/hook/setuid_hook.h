#ifndef __KSU_H_KSU_CORE
#define __KSU_H_KSU_CORE

#include <linux/init.h>
#include <linux/types.h>

void ksu_setuid_hook_init(void);
void ksu_setuid_hook_exit(void);

// Handler functions for hook_manager
// SUSFS 模式下由 50_add_susfs 打进 kernel/sys.c __sys_setresuid 的 hook 调用，
// 签名必须是 (ruid, euid, suid)；LKM 模式走 syscall dispatcher，签名是
// (old_uid, new_uid)。
#ifdef CONFIG_KSU_SUSFS
int ksu_handle_setresuid(uid_t ruid, uid_t euid, uid_t suid);
#else
int ksu_handle_setresuid(uid_t old_uid, uid_t new_uid);
#endif

#endif
