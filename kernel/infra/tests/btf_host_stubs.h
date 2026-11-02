/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef KSU_BTF_HOST_STUBS_H
#define KSU_BTF_HOST_STUBS_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
/* Fixed Linux errno values avoid libc errno.h recursing through the shim. */
#define ENOENT 2
#define EIO 5
#define EINVAL 22
typedef uint32_t u32;
typedef int32_t s32;
struct btf { int unused; };
/* Match the UAPI BTF layout: INT data and members follow btf_type. */
struct btf_type { u32 name_off; u32 info; union { u32 size; u32 type; }; };
struct btf_member { u32 name_off; u32 type; u32 offset; };
#define BTF_KIND_INT 1
#define BTF_KIND_PTR 2
#define BTF_KIND_STRUCT 4
#define BTF_INFO_KIND(info) (((info) >> 24) & 0x1f)
#define BTF_INFO_KFLAG(info) ((info) >> 31)
#define BTF_INFO_VLEN(info) ((info) & 0xffff)
#define BTF_MEMBER_BITFIELD_SIZE(val) ((val) >> 24)
#define BTF_MEMBER_BIT_OFFSET(val) ((val) & 0xffffff)
#define BTF_INT_OFFSET(val) (((val) >> 16) & 0xff)
#define BTF_INT_BITS(val) ((val) & 0xff)
#define BTF_INT_ENCODING(val) ((val) >> 24)
#define BTF_INT_CHAR 2
#define BTF_INT_BOOL 4
#define for_each_member(i, t, m) \
    for ((i) = 0, (m) = (const struct btf_member *)((t) + 1); \
         (i) < BTF_INFO_VLEN((t)->info); (i)++, (m)++)
#define ERR_PTR(e) ((void *)(intptr_t)(e))
#define PTR_ERR(p) ((long)(intptr_t)(p))
#define IS_ERR_OR_NULL(p) (!(p) || (uintptr_t)(p) >= (uintptr_t)-4095)
#define pr_info(...) ((void)0)
#define pr_err(...) ((void)0)
const struct btf *bpf_get_btf_vmlinux(void);
s32 btf_find_by_name_kind(const struct btf *, const char *, u32);
const struct btf_type *btf_type_by_id(const struct btf *, u32);
const char *btf_name_by_offset(const struct btf *, u32);
const struct btf_type *btf_type_skip_modifiers(const struct btf *, u32, u32 *);
#endif
