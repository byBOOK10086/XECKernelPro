#ifndef __KSU_H_PREINIT
#define __KSU_H_PREINIT

#include <linux/types.h>
#include <asm/ptrace.h>

struct filename;
struct user_arg_ptr;

#ifdef CONFIG_KSU_SUSFS
// 50_add_susfs 打进基线 fs/exec.c 的 hook 使用的入口（上游 KernelSU 新 preinit ABI）：
// setup 在 do_execveat_common 早期把 /system/bin/init 换成预置 runner 的 fd 路径；
// post 在 exec 失败时恢复原文件名并让调用方重走原流程。本文件提供与上游一致的语义，
// 使 SUSFS v2.4.0+ 的 50_ 补丁可以在本树上链接成功。
int ksu_setup_preinit_file(struct filename **filename_ptr, struct user_arg_ptr *argv_user);
int ksu_preinit_handle_post_execveat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                     int *flags, int *retval, int *preinit_fd);
#endif

#ifndef KSU_NO_PREINIT
void ksu_preinit_init(void);
void ksu_preinit_exit(void);

// Swap the runner in for PID 1's "/system/bin/init selinux_setup" execve.
// Returns true when it issued the syscall itself; the result is in *ret.
bool ksu_preinit_handle_execve(const char __user **filename_user, const char __user *const __user *argv_user,
                               int orig_nr, struct pt_regs *regs, long *ret);

// Drop the staged runner if selinux_setup was never seen.
void ksu_preinit_release(void);

// Whether the runner is built in and not disabled with preinit=0.
bool ksu_preinit_enabled_param(void);
#else
static inline void ksu_preinit_init(void)
{
}
static inline void ksu_preinit_exit(void)
{
}
static inline bool ksu_preinit_handle_execve(const char __user **filename_user,
                                             const char __user *const __user *argv_user, int orig_nr,
                                             struct pt_regs *regs, long *ret)
{
    return false;
}
static inline void ksu_preinit_release(void)
{
}
static inline bool ksu_preinit_enabled_param(void)
{
    return false;
}
#ifdef CONFIG_KSU_SUSFS
static inline int ksu_setup_preinit_file(struct filename **filename_ptr, struct user_arg_ptr *argv_user)
{
    return -EINVAL;
}
static inline int ksu_preinit_handle_post_execveat(int *fd, struct filename **filename_ptr, void *argv_user,
                                                   void *envp_user, int *flags, int *retval, int *preinit_fd)
{
    return -EINVAL;
}
#endif
#endif

#endif // __KSU_H_PREINIT
