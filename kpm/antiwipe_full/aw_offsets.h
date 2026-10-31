/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * aw_offsets.h — runtime struct-offset bootstrap shared by the antiwipe_full
 * KPM module. Offsets are extracted from the running kernel's BTF
 * (/sys/kernel/btf/vmlinux) at load time, so the module carries no
 * compile-time struct layout assumptions across KMI versions.
 */

#ifndef _AW_OFFSETS_H_
#define _AW_OFFSETS_H_

#include "aw_types.h"

/* Every kernel struct member the policy reads. All are resolved through BTF
 * before the module will arm; a single miss fails the load (fail closed). */
struct aw_offsets {
    /* struct file */
    size_t file_f_mode;
    size_t file_f_path;   /* struct path by value */
    size_t file_f_inode;
    /* struct path (members verified, expected 0 / 8) */
    size_t path_mnt;
    size_t path_dentry;
    /* struct inode */
    size_t inode_i_mode;
    size_t inode_i_rdev;
    /* struct dentry */
    size_t dentry_d_inode;
    /* struct linux_binprm */
    size_t bprm_filename;
    /* struct cred */
    size_t cred_euid;
    /* struct mm_struct */
    size_t mm_exe_file;
    /* struct task_struct (flags; comm/cred/mm come from task_struct_offset) */
    size_t task_flags;
    /* struct iattr */
    size_t iattr_ia_valid;
};

extern struct aw_offsets aw_off;

/* Load and parse /sys/kernel/btf/vmlinux, fill aw_off. Returns 0 on success,
 * negative errno on failure. Allocates a vmalloc buffer freed by
 * aw_btf_shutdown(). */
int aw_btf_init(void);

/* Free the BTF buffer (offsets stay valid in aw_off). */
void aw_btf_shutdown(void);

#endif /* _AW_OFFSETS_H_ */
