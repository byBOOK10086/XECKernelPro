#ifndef __KSU_H_KSUD
#define __KSU_H_KSUD

#include <asm/syscall.h>
#include <linux/compat.h>
#include <linux/uaccess.h>

#define KSUD_PATH "/data/adb/xudc"

// exec 系统调用的用户态 argv/envp 数组描述，布局与 fs/exec.c 内部的
// struct user_arg_ptr 一致（50_add_susfs 的 fs/exec.c hook 直接把
// do_execveat_common 的 &argv/&envp 传给 SUSFS 模式的 handler）。
struct user_arg_ptr {
#ifdef CONFIG_COMPAT
    bool is_compat;
#endif
    union {
        const char __user *const __user *native;
#ifdef CONFIG_COMPAT
        const compat_uptr_t __user *compat;
#endif
    } ptr;
};

const char __user *get_user_arg_ptr(struct user_arg_ptr argv, int nr);

void ksu_ksud_init();
void ksu_ksud_exit();

void ksu_execve_hook_ksud(const struct pt_regs *regs);
void ksu_execveat_hook_ksud(const struct pt_regs *regs);
void ksu_stop_input_hook_runtime(void);

// SUSFS 模式下由 feature/sucompat.c 的 ksu_handle_execveat() 链式调用
// （LKM 模式由本文件的 pt_regs hook 驱动）。
void ksu_handle_execveat_ksud(const char *path, struct user_arg_ptr *argv);

#endif
