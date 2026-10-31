/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * aw_types.h — freestanding type + ABI surface for antiwipe_full.kpm
 *
 * Derived from KernelPatch (https://github.com/bmax121/KernelPatch)
 * kernel/include/kpmodule.h / hook.h / stdint.h, vendored and trimmed to
 * exactly what this module needs so it builds with any aarch64 clang and no
 * external SDK (see kpm/antiwipe_full/README.md).
 *
 * The engine side of this ABI is vendored in kernel/kpmcore/ (KP LKM runtime)
 * and the symbol names below MUST match kernel/kpmcore/symbols.c
 * (kp_kpm_symbols[]) and the kallsyms fallback in kernel/kpmcore/relo.c.
 */

#ifndef _AW_TYPES_H_
#define _AW_TYPES_H_

/* ------------------------------------------------------------------ types */

typedef __UINT8_TYPE__ u8;
typedef __UINT16_TYPE__ u16;
typedef __UINT32_TYPE__ u32;
typedef __UINT64_TYPE__ u64;
typedef __INT8_TYPE__ s8;
typedef __INT16_TYPE__ s16;
typedef __INT32_TYPE__ s32;
typedef __INT64_TYPE__ s64;
typedef __SIZE_TYPE__ size_t;
typedef __PTRDIFF_TYPE__ ptrdiff_t;
typedef unsigned long uintptr_a; /* kernel pointer-sized unsigned */
typedef _Bool bool;

#define NULL ((void *)0)
#define true 1
#define false 0

/* kernel address-space annotations: no-ops in freestanding KPM builds */
#ifndef __user
#define __user
#endif
#ifndef __kernel
#define __kernel
#endif

#define AW_ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))

/* KERN_* printk facility prefixes: SOH digit */
#define AW_KERN_INFO "\001" "6"
#define AW_KERN_WARN "\001" "4"
#define AW_KERN_ERR "\001" "3"

/* -------------------------------------------------- KernelPatch KPM ABI
 * (from KernelPatch kernel/include/kpmodule.h; section names are the module
 * loader contract shared by kernel/kpm.c and kernel/kpmcore/module.c) */

#define KPM_INFO(name, info, limit)                                     \
    _Static_assert(sizeof(info) <= limit, "Info string too long");      \
    static const char __kpm_info_##name[] __attribute__((__used__))     \
    __attribute__((section(".kpm.info"), unused, aligned(1))) = #name "=" info

#define KPM_NAME_LEN 32
#define KPM_VERSION_LEN 32
#define KPM_LICENSE_LEN 32
#define KPM_AUTHOR_LEN 32
#define KPM_DESCRIPTION_LEN 512

#define KPM_NAME(x) KPM_INFO(name, x, KPM_NAME_LEN)
#define KPM_VERSION(x) KPM_INFO(version, x, KPM_VERSION_LEN)
#define KPM_LICENSE(x) KPM_INFO(license, x, KPM_LICENSE_LEN)
#define KPM_AUTHOR(x) KPM_INFO(author, x, KPM_AUTHOR_LEN)
#define KPM_DESCRIPTION(x) KPM_INFO(description, x, KPM_DESCRIPTION_LEN)

typedef long (*mod_initcall_t)(const char *args, const char *event, void *reserved);
typedef long (*mod_ctl0call_t)(const char *ctl_args, char *__user out_msg, int outlen);
typedef long (*mod_exitcall_t)(void *reserved);

#define KPM_INIT(fn)                                                     \
    static mod_initcall_t __kpm_initcall_##fn __attribute__((__used__))  \
    __attribute__((__section__(".kpm.init"))) = fn
#define KPM_CTL0(fn)                                                       \
    static mod_ctl0call_t __kpm_ctlmodule_##fn __attribute__((__used__))   \
    __attribute__((__section__(".kpm.ctl0"))) = fn
#define KPM_EXIT(fn)                                                     \
    static mod_exitcall_t __kpm_exitcall_##fn __attribute__((__used__))  \
    __attribute__((__section__(".kpm.exit"))) = fn

/* ------------------------------------------------- KernelPatch hook ABI
 * (from KernelPatch kernel/include/hook.h). The engine-side chain code lives
 * in kernel/kpmcore/hook.c: befores run first, setting fargs->skip_origin
 * skips the original function and fargs->ret becomes the returned value. */

typedef enum {
    HOOK_NO_ERR = 0,
    HOOK_BAD_ADDRESS = 4095,
    HOOK_DUPLICATED = 4094,
    HOOK_NO_MEM = 4093,
    HOOK_BAD_RELO = 4092,
    HOOK_TRANSIT_NO_MEM = 4091,
    HOOK_CHAIN_FULL = 4090,
} hook_err_t;

#define HOOK_LOCAL_DATA_NUM 8

typedef struct {
    union {
        struct {
            u64 data0, data1, data2, data3, data4, data5, data6, data7;
        };
        u64 data[HOOK_LOCAL_DATA_NUM];
    };
} hook_local_t;

typedef struct {
    void *chain;
    int skip_origin;
    hook_local_t local;
    u64 ret;
    union {
        struct {
            u64 arg0;
            u64 arg1;
            u64 arg2;
            u64 arg3;
        };
        u64 args[4];
    };
} hook_fargs4_t __attribute__((aligned(8)));

typedef hook_fargs4_t hook_fargs1_t;
typedef hook_fargs4_t hook_fargs2_t;
typedef hook_fargs4_t hook_fargs3_t;

typedef struct {
    void *chain;
    int skip_origin;
    hook_local_t local;
    u64 ret;
    union {
        struct {
            u64 arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7;
        };
        u64 args[8];
    };
} hook_fargs8_t __attribute__((aligned(8)));

typedef hook_fargs8_t hook_fargs5_t;
typedef hook_fargs8_t hook_fargs6_t;
typedef hook_fargs8_t hook_fargs7_t;

typedef void (*hook_chain4_callback)(hook_fargs4_t *fargs, void *udata);
typedef void (*hook_chain8_callback)(hook_fargs8_t *fargs, void *udata);

/* ------------------------------------------------ engine-provided symbols
 * All resolved by kernel/kpmcore/relo.c: kp_kpm_symbols[] first, then
 * kallsyms. Slot symbols (fn-pointer variables) must be declared as
 * variables; function symbols as functions. */

/* printk: pointer-variable slot (symbols.c stores the fn pointer there). */
extern int (*printk)(const char *fmt, ...);
/* kallsyms_lookup_name: pointer-variable slot, backed by the host tree's
 * find_kernel_symbol_exact(). */
extern unsigned long (*kallsyms_lookup_name)(const char *name);

/* Kernel version stamp and task layout table exported by symbols.c. */
extern unsigned int kver;
struct aw_task_offsets {
    s16 pid_offset, tgid_offset, thread_pid_offset, ptracer_cred_offset;
    s16 real_cred_offset, cred_offset, comm_offset, fs_offset, files_offset;
    s16 loginuid_offset, sessionid_offset, seccomp_offset, security_offset;
    s16 stack_offset, tasks_offset, mm_offset, active_mm_offset;
};
extern struct aw_task_offsets task_struct_offset;
extern int has_syscall_wrapper;

/* Hook primitives (function symbols). */
extern int hook_wrap(void *func, int argno, void *before, void *after, void *udata);
extern void hook_unwrap_remove(void *func, void *before, void *after, int remove);
extern int hook_syscalln(int nr, int narg, void *before, void *after, void *udata);
extern void unhook_syscalln(int nr, void *before, void *after);

/* User string access + user copy (kernel symbols). */
extern long compat_strncpy_from_user(char *dst, const char *src, long count);
extern unsigned long _copy_to_user(void *to, const void *from, unsigned long n);

/* kf_* kfuncs: pointer-variable slots populated from the running kernel. */
extern int (*kf_snprintf)(char *buf, size_t size, const char *fmt, ...);
extern size_t (*kf_strlen)(const char *s);
extern size_t (*kf_strnlen)(const char *s, size_t count);
extern int (*kf_strcmp)(const char *cs, const char *ct);
extern int (*kf_strncmp)(const char *cs, const char *ct, size_t count);
extern char *(*kf_strchr)(const char *s, int c);
extern char *(*kf_strrchr)(const char *s, int c);
extern char *(*kf_strstr)(const char *s1, const char *s2);
extern void *(*kf_memset)(void *s, int c, size_t count);
extern void *(*kf_memcpy)(void *dest, const void *src, size_t count);
extern void *(*kf_memmove)(void *dest, const void *src, size_t count);
extern void *(*kf_memchr)(const void *s, int c, size_t n);
extern int (*kf_memcmp)(const void *cs, const void *ct, size_t count);

/* ------------------------------------------------ kernel symbols (kallsyms) */

struct aw_path {
    void *mnt;
    void *dentry;
}; /* matches struct path: mnt first, dentry second, LP64 */

/* opaque kernel types: pointers only, layouts are never assumed except the
 * two explicitly BTF-verified structs below (aw_path mirror, pt_regs) */
struct file_;

extern int kern_path(const char *name, unsigned flags, struct aw_path *path);
extern void path_put(struct aw_path *path);
extern void *dget_parent(void *dentry);
extern void dput(void *dentry);
extern char *dentry_path_raw(void *dentry, char *buf, int buflen);
extern char *d_path(const struct aw_path *path, char *buf, int buflen);
extern struct file_ *filp_open(const char *filename, int flags, unsigned short mode);
extern int filp_close(struct file_ *filp, void *id);
extern long kernel_read(struct file_ *file, void *buf, unsigned long count, s64 *pos);
extern void *vmalloc(unsigned long size);
extern void vfree(const void *addr);
extern long strncpy_from_user(char *dst, const char *src, long count);
extern unsigned int jiffies_to_msecs(const unsigned long j);

struct aw_timespec64 {
    s64 tv_sec;
    s64 tv_nsec;
}; /* matches struct timespec64 on LP64 */
extern void ktime_get_real_ts64(struct aw_timespec64 *ts);

extern void *memset(void *s, int c, size_t count);
extern void *memcpy(void *dest, const void *src, size_t count);

extern unsigned long jiffies;

/* ------------------------------------------------------------ constants */

#define LOOKUP_FOLLOW 1
#define FMODE_WRITE 0x2
#define S_IFMT 0170000
#define S_IFBLK 0060000
#define S_IFCHR 0020000
#define S_IFDIR 0040000
#define MAY_WRITE 2
#define TASK_COMM_LEN 16

#define ATTR_MODE 1
#define ATTR_UID 2
#define ATTR_GID 4
#define ATTR_SIZE 8

/* arm64 syscall number */
#define __AW_NR_reboot 142

/* reboot(2) constants */
#define AW_REBOOT_MAGIC1 0xfee1dead
#define AW_REBOOT_MAGIC2 0x28121969
#define AW_REBOOT_MAGIC2A 0x2812196a
#define AW_REBOOT_MAGIC2B 0x2812196b
#define AW_REBOOT_MAGIC2C 0x2812196c
#define AW_REBOOT_CMD_RESTART 0x01234567
#define AW_REBOOT_CMD_HALT 0x89abcdef
#define AW_REBOOT_CMD_POWER_OFF 0x4321fedc
#define AW_REBOOT_CMD_RESTART2 0xa1b2c3d4
#define AW_REBOOT_CMD_SW_SUSPEND 0xd000fce2
#define AW_REBOOT_CMD_KEXEC 0x45584543

/* FS_IOC_*_ENCRYPTION_KEY fscrypt ioctls: _IOWR('f', 18/19/20, ...).
 * Match on type+'f' and command number so struct growth never breaks us. */
#define AW_FSCRYPT_IOCTL_TYPE 0x66
#define AW_FSCRYPT_IOC_ADD_KEY 18
#define AW_FSCRYPT_IOC_REMOVE_KEY 19
#define AW_FSCRYPT_IOC_REMOVE_KEY_ALL 20

/* KERNEL_VERSION helper */
#define AW_KVER(ma, mi, pa) (((ma) << 16) + ((mi) << 8) + (pa))

/* errno values (aarch64 kernel numbering; freestanding module has no errno.h) */
#define EPERM 1
#define ENOENT 2
#define EIO 5
#define E2BIG 7
#define ENOMEM 12
#define EACCES 13
#define EFAULT 14
#define EBUSY 16
#define EEXIST 17
#define ENODEV 19
#define EINVAL 22
#define ENOSPC 28
#define EBADSLT 57
#define ENODATA 61
#define ENOSYS 38
#define EOPNOTSUPP 95

/* task_struct.flags bit */
#define AW_PF_KTHREAD 0x00200000

/* ------------------------------------------------------------- helpers */

#define aw_info(fmt, ...) printk(AW_KERN_INFO "antiwipe: " fmt, ##__VA_ARGS__)
#define aw_warn(fmt, ...) printk(AW_KERN_WARN "antiwipe: " fmt, ##__VA_ARGS__)
#define aw_err(fmt, ...) printk(AW_KERN_ERR "antiwipe: " fmt, ##__VA_ARGS__)

static inline u32 aw_i_mode(const void *inode, size_t off_mode)
{
    u16 mode;
    memcpy(&mode, (const char *)inode + off_mode, sizeof(mode));
    return mode;
}

#endif /* _AW_TYPES_H_ */
