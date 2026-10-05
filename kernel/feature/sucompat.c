#include "linux/file.h"
#include "linux/fcntl.h"
#include "linux/namei.h"
#include <linux/compiler_types.h>
#include <linux/preempt.h>
#include <linux/printk.h>
#include <linux/mm.h>
#include <linux/pgtable.h>
#include <linux/uaccess.h>
#include <asm/current.h>
#include <linux/cred.h>
#include <linux/fs.h>
#include <linux/types.h>
#include <linux/version.h>
#include <linux/sched/task_stack.h>
#include <linux/ptrace.h>
#ifdef CONFIG_KSU_SUSFS
#include <linux/susfs_def.h>
#include <linux/fs_struct.h>
#include <linux/slab.h>
#include "selinux/selinux.h"
#include "feature/adb_root.h"
#endif

#include "arch.h"
#include "policy/allowlist.h"
#include "policy/feature.h"
#include "klog.h" // IWYU pragma: keep
#include "runtime/ksud.h"
#include "feature/sucompat.h"
#include "policy/app_profile.h"
#include "hook/syscall_hook.h"
#include "supercall/supercall.h"
#include "sulog/event.h"
#include "ksu.h"
#include "util.h"

#define SU_PATH "/system/bin/su"
#define SH_PATH "/system/bin/sh"

#ifdef CONFIG_KSU_SUSFS
static const char sh_path[] = SH_PATH;
static const char ksud_path[] = KSUD_PATH;

// SUSFS 模式下 sucompat 开关是 static key：50_add_susfs 打进 fs/exec.c 与
// fs/stat.c 的 hook 用 static_branch_likely 读取它（extern 声明在 sucompat.h）。
DEFINE_STATIC_KEY_TRUE(ksu_su_compat_enabled);

static int su_compat_feature_get(u64 *value)
{
    if (static_key_enabled(&ksu_su_compat_enabled))
        *value = 1;
    else
        *value = 0;
    return 0;
}

static int su_compat_feature_set(u64 value)
{
    bool enable = value != 0;
    if (enable)
        static_branch_enable(&ksu_su_compat_enabled);
    else
        static_branch_disable(&ksu_su_compat_enabled);
    pr_info("su_compat: set to %d\n", enable);
    return 0;
}

#else /* !CONFIG_KSU_SUSFS */

bool ksu_su_compat_enabled __read_mostly = true;

static int su_compat_feature_get(u64 *value)
{
    *value = ksu_su_compat_enabled ? 1 : 0;
    return 0;
}

static int su_compat_feature_set(u64 value)
{
    bool enable = value != 0;
    ksu_su_compat_enabled = enable;
    pr_info("su_compat: set to %d\n", enable);
    return 0;
}

#endif /* CONFIG_KSU_SUSFS */

static const struct ksu_feature_handler su_compat_handler = {
    .feature_id = KSU_FEATURE_SU_COMPAT,
    .name = "su_compat",
    .get_handler = su_compat_feature_get,
    .set_handler = su_compat_feature_set,
};

#ifndef CONFIG_KSU_SUSFS
// 下面这组 user-buffer 助手与 pt_regs 版 handler 只在 LKM 模式编译。
static void __user *userspace_stack_buffer(const void *d, size_t len)
{
    // To avoid having to mmap a page in userspace, just write below the stack
    // pointer.
    char __user *p = (void __user *)current_user_stack_pointer() - len;

    return copy_to_user(p, d, len) ? NULL : p;
}

static char __user *ksud_user_path(void)
{
    static const char ksud_path[] = KSUD_PATH;

    return userspace_stack_buffer(ksud_path, sizeof(ksud_path));
}

static char __user *empty_user_path(void)
{
    return userspace_stack_buffer("", sizeof(""));
}

#endif /* !CONFIG_KSU_SUSFS */

static const char su_path[] = SU_PATH;

#ifndef CONFIG_KSU_SUSFS
static bool is_ksud_exists()
{
    struct path path;

    if (kern_path(KSUD_PATH, 0, &path) < 0) {
        return false;
    }
    path_put(&path);
    return true;
}

long ksu_handle_faccessat_sucompat(int orig_nr, struct pt_regs *regs)
{
    const char __user **filename_user, *orig_filename;
    long ret;
    const struct cred *old_cred;

    if (!ksu_is_allow_uid_for_current(current_uid().val)) {
        goto do_orig_facessat;
    }

    filename_user = (const char __user **)&PT_REGS_PARM2(regs);

    char path[sizeof(su_path) + 1];
    memset(path, 0, sizeof(path));
    strncpy_from_user_nofault(path, *filename_user, sizeof(path));

    if (unlikely(!memcmp(path, su_path, sizeof(su_path)))) {
        old_cred = override_creds(ksu_cred);
        if (is_ksud_exists()) {
            pr_info("faccessat su->ksud!\n");
            orig_filename = *filename_user;
            *filename_user = ksud_user_path();
            ret = ksu_syscall_table[orig_nr](regs);
            revert_creds(old_cred);
            *filename_user = orig_filename;
            return ret;
        } else {
            revert_creds(old_cred);
        }
    }

do_orig_facessat:
    return ksu_syscall_table[orig_nr](regs);
}

long ksu_handle_stat_sucompat(int orig_nr, struct pt_regs *regs)
{
    const char __user **filename_user, *orig_filename;
    long ret;
    const struct cred *old_cred;

    if (!ksu_is_allow_uid_for_current(current_uid().val)) {
        goto do_orig_stat;
    }

    filename_user = (const char __user **)&PT_REGS_PARM2(regs);

    char path[sizeof(su_path) + 1];
    memset(path, 0, sizeof(path));
    strncpy_from_user_nofault(path, *filename_user, sizeof(path));

    if (unlikely(!memcmp(path, su_path, sizeof(su_path)))) {
        old_cred = override_creds(ksu_cred);
        if (is_ksud_exists()) {
            pr_info("newfstatat su->ksud!\n");
            orig_filename = *filename_user;
            *filename_user = ksud_user_path();
            ret = ksu_syscall_table[orig_nr](regs);
            revert_creds(old_cred);
            *filename_user = orig_filename;
            return ret;
        } else {
            revert_creds(old_cred);
        }
    }

do_orig_stat:
    return ksu_syscall_table[orig_nr](regs);
}

static long ksu_handle_execve_sucompat_common(const char __user **filename_user,
                                              const char __user *const __user *argv_user, unsigned long envp,
                                              bool execveat, int orig_nr, struct pt_regs *regs)
{
    const char __user *fn;
    struct ksu_sulog_pending_event *pending_sucompat = NULL;
    char path[sizeof(su_path) + 1];
    long ret, orig_regs[5];
    unsigned long addr;
    int su_fd = -1;
    int tmp_fd;
    struct file *ksud_file;
    const struct cred *old_cred;

    if (execveat && ((int)PT_REGS_PARM1(regs) != AT_FDCWD || (int)PT_REGS_PARM5(regs) != 0))
        goto do_orig_execve;

    if (unlikely(!filename_user))
        goto do_orig_execve;

    if (!ksu_is_allow_uid_for_current(current_uid().val))
        goto do_orig_execve;

    addr = untagged_addr((unsigned long)*filename_user);
    fn = (const char __user *)addr;
    memset(path, 0, sizeof(path));

    ret = strncpy_from_user(path, fn, sizeof(path));

    if (ret < 0) {
        pr_warn("Access filename when execve failed: %ld", ret);
        goto do_orig_execve;
    }

    if (likely(memcmp(path, su_path, sizeof(su_path))))
        goto do_orig_execve;

    pr_info("sys_execve su found\n");

    tmp_fd = get_unused_fd_flags(O_CLOEXEC);
    if (tmp_fd < 0) {
        pr_err("alloc tmp fd err: %d\n", tmp_fd);
        goto do_orig_execve;
    }

    old_cred = override_creds(ksu_cred);
    ksud_file = filp_open(KSUD_PATH, O_PATH, 0);
    revert_creds(old_cred);
    if (IS_ERR(ksud_file)) {
        pr_err("open ksud err: %ld\n", PTR_ERR(ksud_file));
        put_unused_fd(tmp_fd);
        goto do_orig_execve;
    }

    fd_install(tmp_fd, ksud_file);

    pending_sucompat = ksu_sulog_capture_sucompat(*filename_user, argv_user, GFP_KERNEL);
    // execve(file, argv, environ)
    // execveat(fd, file, argv, environ, flags)
    orig_regs[0] = regs->__PT_PARM1_REG;
    orig_regs[1] = regs->__PT_PARM2_REG;
    orig_regs[2] = regs->__PT_PARM3_REG;
    orig_regs[3] = regs->__PT_SYSCALL_PARM4_REG;
    orig_regs[4] = regs->__PT_PARM5_REG;
    regs->__PT_PARM5_REG = AT_EMPTY_PATH;
    regs->__PT_SYSCALL_PARM4_REG = envp;
    regs->__PT_PARM3_REG = (unsigned long)argv_user;
    regs->__PT_PARM2_REG = empty_user_path();
    regs->__PT_PARM1_REG = tmp_fd;

    ret = escape_with_root_profile();
    if (ret) {
        pr_err("escape_with_root_profile failed: %ld\n", ret);
    }
    ksu_sulog_emit_pending(pending_sucompat, ret, GFP_KERNEL);

    ret = ksu_syscall_table[__NR_execveat](regs);
    if (ret < 0) {
        ksu_close_fd(tmp_fd);
        regs->__PT_PARM1_REG = orig_regs[0];
        regs->__PT_PARM2_REG = orig_regs[1];
        regs->__PT_PARM3_REG = orig_regs[2];
        regs->__PT_SYSCALL_PARM4_REG = orig_regs[3];
        regs->__PT_PARM5_REG = orig_regs[4];
    } else {
        // Only grant the scoped driver capability after the selected root
        // profile has been applied successfully.
        su_fd = ksu_install_su_fd();
        if (su_fd < 0) {
            pr_warn("install su session fd failed: %d\n", su_fd);
        }
    }
    return ret;

do_orig_execve:
    return ksu_syscall_table[orig_nr](regs);
}

long ksu_handle_execve_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs)
{
    return ksu_handle_execve_sucompat_common(filename_user, (const char __user *const __user *)PT_REGS_PARM2(regs),
                                             PT_REGS_PARM3(regs), false, orig_nr, regs);
}

long ksu_handle_execveat_sucompat(const char __user **filename_user, int orig_nr, struct pt_regs *regs)
{
    return ksu_handle_execve_sucompat_common(filename_user, (const char __user *const __user *)PT_REGS_PARM3(regs),
                                             PT_REGS_SYSCALL_PARM4(regs), true, orig_nr, regs);
}
#endif /* !CONFIG_KSU_SUSFS */

#ifdef CONFIG_KSU_SUSFS
// ---------------- SUSFS 模式（50_add_susfs 基线 hook 入口） ----------------
// 与上面的 LKM pt_regs 版并存：SUSFS GKI 内核里 hook 挂在 fs/exec.c、
// fs/open.c、fs/stat.c 源码层，操作对象是 struct filename（内核态路径），
// allow-uid 门控由 50_ 的调用点完成。签名必须与 50_ 的 extern 声明一致。

// init/zygote 阶段的 execve 处理。返回 0 表示已处理（外层结束），
// 非 0 表示继续后续 sucompat 判定。
static int ksu_handle_execveat_init(struct filename *filename, struct user_arg_ptr *argv_user,
                                    struct user_arg_ptr *envp_user)
{
    int ret;

    if (current->pid == 1)
        return -EINVAL;

    if (!is_init(current_cred()))
        return -EINVAL;

    if (unlikely(!strcmp(filename->name, KSUD_PATH))) {
        struct ksu_sulog_pending_event *pending_sucompat = NULL;

        pr_info("hook_manager: escape to root for init executing ksud: %d\n", current->pid);
        if (filename->uptr && argv_user->ptr.native)
            pending_sucompat = ksu_sulog_capture_sucompat(filename->uptr, argv_user->ptr.native, GFP_KERNEL);
        escape_to_root_for_init();
        ksu_sulog_emit_pending(pending_sucompat, 0, GFP_KERNEL);
        return 0;
    }

    if (likely(!strstr(filename->name, "/app_process") && !strstr(filename->name, "/adbd") &&
               !strstr(filename->name, "/stub_zygote"))) {
        pr_info("susfs: mark no sucompat checks for pid: '%d', exec: '%s'\n", current->pid, filename->name);
        susfs_set_current_proc_no_su();
        // - marking proc umounted here is useless because only zygote spawned processes will umount
        //   the sus mounts, tho other susfs features still rely on proc_umounted check, but
        //   it is fine to not spoof for init spawned processes.
        return 0;
    }

#ifdef CONFIG_COMPAT
    if (unlikely(envp_user->is_compat))
        ret = ksu_adb_root_handle_execveat_susfs(filename->name, (void ***)&envp_user->ptr.compat);
    else
#endif
        ret = ksu_adb_root_handle_execveat_susfs(filename->name, (void ***)&envp_user->ptr.native);

    if (ret)
        pr_err("adb root failed: %d\n", ret);

    return ret;
}

// 允许的进程 exec "/system/bin/su"：提权成功后原地改写 struct filename 为
// ksud 路径。返回 0 表示进入 su 会话（50_ 的调用点据此在 exec 后补装 su fd）。
int ksu_handle_execveat_sucompat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                 int *__never_use_flags)
{
    struct filename *filename;
    struct ksu_sulog_pending_event *pending_sucompat = NULL;
    int ret;

    (void)fd;
    (void)__never_use_flags;

    if (unlikely(!filename_ptr || !argv_user || !envp_user))
        return -EINVAL;

    filename = *filename_ptr;
    if (IS_ERR_OR_NULL(filename) || !filename->name)
        return -EINVAL;

    if (!ksu_handle_execveat_init(filename, (struct user_arg_ptr *)argv_user, (struct user_arg_ptr *)envp_user))
        return -EINVAL;

    if (!(__ksu_is_allow_uid_for_current(current_uid().val)))
        return -EINVAL;

    if (strcmp(filename->name, su_path))
        return -EINVAL;

    if (current_chrooted()) {
        pr_err(
            "ksu_handle_execveat_sucompat: su found but NOT allowed! Because current process is running in chrooted environment\n");
        return -EINVAL;
    }

    {
#ifdef CONFIG_COMPAT
        if (filename->uptr && !((struct user_arg_ptr *)argv_user)->is_compat)
#else
        if (filename->uptr)
#endif
            pending_sucompat =
                ksu_sulog_capture_sucompat(filename->uptr, ((struct user_arg_ptr *)argv_user)->ptr.native, GFP_KERNEL);

        ret = escape_with_root_profile();
        ksu_sulog_emit_pending(pending_sucompat, ret, GFP_KERNEL);
        if (ret) {
            pr_err("escape_with_root_profile() failed: %d\n", ret);
            return ret;
        }
    }

    // 上游 susfs4ksu 同款原地改写：filename->name 指向 struct filename 内联的
    // iname 缓冲（EMBEDDED_NAME_MAX，约 4KB），写入 15 字节的 ksud 路径安全，
    // 不引入 putname/引用计数的跨代差异。
    pr_info("ksu_handle_execveat_sucompat: su->ksud!\n");
    memcpy((void *)filename->name, ksud_path, sizeof(ksud_path));
    return 0;
}

int ksu_handle_post_execveat_sucompat(int *fd, struct filename **filename_ptr, void *argv_user, void *envp_user,
                                      int *__never_use_flags, int *retval)
{
    (void)fd;
    (void)filename_ptr;
    (void)argv_user;
    (void)envp_user;
    (void)__never_use_flags;

    if (*retval >= 0) {
        (void)ksu_install_su_fd();
    }
    return 0;
}

// 50_ 的 fs/exec.c 在 sdcard 未解密阶段（susfs_is_sdcard_android_data_not_decrypted
// static key 开启时）走本入口；先做 ksud/init 阶段标记，再走 sucompat。
int ksu_handle_execveat(int *fd, struct filename **filename_ptr, void *argv, void *envp, int *flags)
{
    struct filename *filename;

    (void)flags;

    if (filename_ptr) {
        filename = *filename_ptr;
        if (!IS_ERR(filename) && filename->name)
            ksu_handle_execveat_ksud(filename->name, (struct user_arg_ptr *)argv);
    }

    return ksu_handle_execveat_sucompat(fd, filename_ptr, argv, envp, flags);
}

int ksu_handle_faccessat(int *dfd, struct filename **filename, int *mode, int *__unused_flags)
{
    (void)dfd;
    (void)mode;

    if (unlikely(IS_ERR(*filename) || (*filename)->name == NULL))
        return 0;

    if (likely(memcmp((*filename)->name, su_path, sizeof(su_path))))
        return 0;

    if (current_chrooted()) {
        pr_err(
            "ksu_handle_faccessat: su found but NOT allowed! Because current process is running in chrooted environment\n");
        return 0;
    }

    pr_info("ksu_handle_faccessat: su->sh!\n");
    memcpy((void *)(*filename)->name, sh_path, sizeof(sh_path));
    return 0;
}

int ksu_handle_stat(int *dfd, struct filename **filename, int *flags)
{
    (void)dfd;
    (void)flags;

    if (unlikely(IS_ERR(*filename) || (*filename)->name == NULL))
        return 0;

    if (likely(memcmp((*filename)->name, su_path, sizeof(su_path))))
        return 0;

    if (current_chrooted()) {
        pr_err(
            "ksu_handle_stat: su found but NOT allowed! Because current process is running in chrooted environment\n");
        return 0;
    }

    pr_info("ksu_handle_stat: su->sh!\n");
    memcpy((void *)(*filename)->name, sh_path, sizeof(sh_path));
    return 0;
}
#endif /* CONFIG_KSU_SUSFS */

// sucompat: permitted process can execute 'su' to gain root access.
void __init ksu_sucompat_init()
{
    if (ksu_register_feature_handler(&su_compat_handler)) {
        pr_err("Failed to register su_compat feature handler\n");
    }
}

void __exit ksu_sucompat_exit()
{
    ksu_unregister_feature_handler(KSU_FEATURE_SU_COMPAT);
}
