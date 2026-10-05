#ifndef __KSU_H_ADB_ROOT
#define __KSU_H_ADB_ROOT
#include <asm/ptrace.h>

long ksu_adb_root_handle_execve(struct pt_regs *regs);
long ksu_adb_root_handle_execveat(struct pt_regs *regs);

// SUSFS 模式入口：文件名是内核态字符串（struct filename->name），envp 是
// do_execveat_common 的 &envp->ptr.native（可写回新的 env 数组地址）。
#ifdef CONFIG_KSU_SUSFS
long ksu_adb_root_handle_execveat_susfs(const char *filename, void ***envp);
#endif

void ksu_adb_root_init(void);

void ksu_adb_root_exit(void);

#endif
