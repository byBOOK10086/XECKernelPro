#ifndef __KSU_H_SUCOMPAT
#define __KSU_H_SUCOMPAT
#include <asm/ptrace.h>
#include <linux/types.h>
#include <linux/static_key.h>

void ksu_sucompat_init(void);
void ksu_sucompat_exit(void);

#ifdef CONFIG_KSU_SUSFS
// SUSFS 模式下 sucompat 开关是 static key：50_add_susfs 打进 fs/exec.c 与
// fs/stat.c 的 hook 用 static_branch_likely 读取它。
extern struct static_key_true ksu_su_compat_enabled;

// Handler functions called from the susfs base-kernel hooks (fs/exec.c,
// fs/open.c, fs/stat.c). Signatures must match the 50_add_susfs externs.
int ksu_handle_execveat(int *fd, struct filename **filename_ptr, void *argv, void *envp, int *flags);
int ksu_handle_execveat_sucompat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                 int *__never_use_flags);
int ksu_handle_post_execveat_sucompat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                      int *__never_use_flags, int *retval);
int ksu_handle_faccessat(int *dfd, struct filename **filename, int *mode, int *__unused_flags);
int ksu_handle_stat(int *dfd, struct filename **filename, int *flags);
#else
extern bool ksu_su_compat_enabled;

// Handler functions exported for hook_manager
long ksu_handle_faccessat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_stat_sucompat(int orig_nr, struct pt_regs *regs);
long ksu_handle_execve_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
long ksu_handle_execveat_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs);
#endif

#endif
