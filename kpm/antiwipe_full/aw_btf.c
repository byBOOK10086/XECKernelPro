/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * aw_btf.c — BTF offset bootstrap for antiwipe_full.kpm
 *
 * Reads /sys/kernel/btf/vmlinux (filp_open + kernel_read, both resolved
 * through the engine's kallsyms fallback) and extracts the byte offsets of
 * every kernel struct member the policy touches. This replaces compile-time
 * struct layouts: the module stays correct across KMI versions and refuses
 * to arm (fails closed) if any offset cannot be resolved.
 *
 * BTF format reference: Documentation/bpf/btf.rst (type section is a stream
 * of variable-length records; member offsets are bit offsets).
 */

#include "aw_offsets.h"

struct aw_offsets aw_off;

/* ----------------------------------------------------------- BTF layout */

#define BTF_MAGIC 0xeb9f
#define BTF_KIND_INT 1
#define BTF_KIND_PTR 2
#define BTF_KIND_ARRAY 3
#define BTF_KIND_STRUCT 4
#define BTF_KIND_UNION 5
#define BTF_KIND_ENUM 6
#define BTF_KIND_FWD 7
#define BTF_KIND_TYPEDEF 8
#define BTF_KIND_VOLATILE 9
#define BTF_KIND_CONST 10
#define BTF_KIND_RESTRICT 11
#define BTF_KIND_FUNC 12
#define BTF_KIND_FUNC_PROTO 13
#define BTF_KIND_VAR 14
#define BTF_KIND_DATASEC 15
#define BTF_KIND_FLOAT 16
#define BTF_KIND_DECL_TAG 17
#define BTF_KIND_TYPE_TAG 18
#define BTF_KIND_ENUM64 19

struct btf_header {
    u16 magic;
    u8 version;
    u8 flags;
    u32 hdr_len;
    u32 type_off;
    u32 type_len;
    u32 str_off;
    u32 str_len;
};

struct btf_type_rec {
    u32 name_off;
    u32 info;
    u32 size_type;
}; /* vlen = info & 0xffff, kind = (info >> 24) & 0x1f, kflag = (info >> 29) & 1 */

struct btf_member_rec {
    u32 name_off;
    u32 type;
    u32 offset; /* bit offset; when kflag is set the top 8 bits are bit size */
};

/* --------------------------------------------------------- wanted table */

struct aw_wanted {
    const char *sname;
    const char *mname;
    size_t *out;
    u32 width;
    bool found;
};

static struct aw_wanted aw_wanted[] = {
    { "file", "f_mode", &aw_off.file_f_mode, 4, false },
    { "file", "f_path", &aw_off.file_f_path, 16, false },
    { "file", "f_inode", &aw_off.file_f_inode, 8, false },
    { "path", "mnt", &aw_off.path_mnt, 8, false },
    { "path", "dentry", &aw_off.path_dentry, 8, false },
    { "inode", "i_mode", &aw_off.inode_i_mode, 2, false },
    { "inode", "i_rdev", &aw_off.inode_i_rdev, 4, false },
    { "dentry", "d_inode", &aw_off.dentry_d_inode, 8, false },
    { "linux_binprm", "filename", &aw_off.bprm_filename, 8, false },
    { "cred", "euid", &aw_off.cred_euid, 4, false },
    { "mm_struct", "exe_file", &aw_off.mm_exe_file, 8, false },
    { "task_struct", "pid", &aw_off.task_pid, 4, false },
    { "task_struct", "cred", &aw_off.task_cred, 8, false },
    { "task_struct", "comm", &aw_off.task_comm, 16, false },
    { "task_struct", "mm", &aw_off.task_mm, 8, false },
    { "task_struct", "flags", &aw_off.task_flags, 4, false },
    { "iattr", "ia_valid", &aw_off.iattr_ia_valid, 4, false },
};

#define AW_WANTED_N (AW_ARRAY_SIZE(aw_wanted))

/* --------------------------------------------------------------- parser */

static void *btf_buf;
static size_t btf_size;
static const char *types;
static u32 types_len;
int aw_setattr_args;
int aw_rename_args;
bool aw_layout_ready;
bool aw_bprm_valid, aw_truncate_valid;

static const char *btf_str(const char *str_base, u32 str_len, u32 name_off)
{
    if (name_off >= str_len)
        return NULL;
    /* A name offset without an in-section terminator is malformed. */
    for (u32 i = name_off; i < str_len; i++)
        if (!str_base[i])
            return str_base + name_off;
    return NULL;
}

static int btf_type_size(int kind, u32 vlen)
{
    switch (kind) {
    case BTF_KIND_INT:
    case BTF_KIND_DECL_TAG:
    case BTF_KIND_VAR:
        return 4;
    case BTF_KIND_ARRAY:
        return 12;
    case BTF_KIND_STRUCT:
    case BTF_KIND_UNION:
        return 12 * vlen;
    case BTF_KIND_ENUM:
        return 8 * vlen;
    case BTF_KIND_FUNC_PROTO:
        return 8 * vlen;
    case BTF_KIND_DATASEC:
        return 12 * vlen;
    case BTF_KIND_ENUM64:
        return 12 * vlen;
    case BTF_KIND_FLOAT:
    case BTF_KIND_PTR:
    case BTF_KIND_FWD:
    case BTF_KIND_TYPEDEF:
    case BTF_KIND_VOLATILE:
    case BTF_KIND_CONST:
    case BTF_KIND_RESTRICT:
    case BTF_KIND_FUNC:
    case BTF_KIND_TYPE_TAG:
        return 0;
    default:
        return -1; /* unknown kind: refuse to guess */
    }
}

/* Resolve an ID only inside the validated type stream; no untrusted pointers. */
static const struct btf_type_rec *btf_id(u32 id)
{
    u32 off = 0, cur = 1;
    while (off < types_len) {
        const struct btf_type_rec *t = (const void *)(types + off);
        int n = btf_type_size((t->info >> 24) & 31, t->info & 65535);
        if (cur++ == id)
            return t;
        off += sizeof(*t) + n;
    }
    return NULL;
}

static const struct btf_type_rec *btf_unqual(u32 id)
{
    for (int depth = 0; depth < 32; depth++) {
        const struct btf_type_rec *t = btf_id(id);
        u32 kind;
        if (!t)
            return NULL;
        kind = (t->info >> 24) & 31;
        if (kind != BTF_KIND_TYPEDEF && kind != BTF_KIND_CONST &&
            kind != BTF_KIND_VOLATILE && kind != BTF_KIND_RESTRICT &&
            kind != BTF_KIND_TYPE_TAG)
            return t;
        id = t->size_type;
    }
    return NULL;
}

static u32 btf_width(u32 id, int depth)
{
    const struct btf_type_rec *t = btf_unqual(id);
    u32 kind;
    if (!t || depth > 16)
        return 0;
    kind = (t->info >> 24) & 31;
    if (kind == BTF_KIND_PTR)
        return 8;
    if (kind == BTF_KIND_ARRAY) {
        const u32 *ar = (const void *)(t + 1);
        u32 elem = btf_width(ar[0], depth + 1);
        return elem && ar[2] <= 4096 / elem ? ar[2] * elem : 0;
    }
    if (kind == BTF_KIND_INT || kind == BTF_KIND_ENUM || kind == BTF_KIND_STRUCT ||
        kind == BTF_KIND_UNION || kind == BTF_KIND_ENUM64)
        return t->size_type;
    return 0;
}

static bool btf_ptr_to(u32 id, const char *name, const char *str, u32 len)
{
    const struct btf_type_rec *t = btf_unqual(id);
    const char *n;
    if (!t || ((t->info >> 24) & 31) != BTF_KIND_PTR)
        return false;
    t = btf_unqual(t->size_type);
    if (!t || ((t->info >> 24) & 31) != BTF_KIND_STRUCT)
        return false;
    n = btf_str(str, len, t->name_off);
    return n && !kf_strcmp(n, name);
}

struct aw_proto {
    const char *name;
    int nargs;
    const char *params[5];
    bool found;
};
static struct aw_proto protos[] = {
    { "security_bprm_check", 1, { "linux_binprm" }, false },
    { "security_bprm_check_security", 1, { "linux_binprm" }, false },
    { "security_file_truncate", 1, { "file" }, false },
    { "security_file_open", 1, { "file" }, false },
    { "security_inode_unlink", 2, { "inode", "dentry" }, false },
    { "security_inode_rmdir", 2, { "inode", "dentry" }, false },
    { "security_inode_rename", 5, { "inode", "dentry", "inode", "dentry", NULL }, false },
    { "security_inode_setattr", 0, { NULL }, false },
    { "security_file_permission", 2, { "file", NULL }, false },
    { "security_file_ioctl", 3, { "file", NULL, NULL }, false },
};

static int btf_parse(void)
{
    struct btf_header hdr;
    const struct btf_header *h = &hdr;
    const char *str;
    size_t base, off = 0;
    u32 len;
    aw_layout_ready = false;
    aw_bprm_valid = aw_truncate_valid = false;
    aw_setattr_args = aw_rename_args = 0;
    for (size_t w = 0; w < AW_WANTED_N; w++)
        aw_wanted[w].found = false;
    for (size_t w = 0; w < AW_ARRAY_SIZE(protos); w++)
        protos[w].found = false;
    if (btf_size < sizeof(*h))
        return -EBADSLT;
    memcpy(&hdr, btf_buf, sizeof(hdr));
    if (h->magic != BTF_MAGIC || h->version != 1 || h->flags ||
        h->hdr_len < sizeof(*h) || h->hdr_len > btf_size)
        return -EBADSLT;
    base = h->hdr_len;
    /* Subtraction checks before pointer arithmetic avoid u32 wrapping. */
    if (h->type_off > btf_size - base || h->type_len > btf_size - base - h->type_off ||
        h->str_off > btf_size - base || h->str_len > btf_size - base - h->str_off ||
        ((base + h->type_off) & 3) || (h->type_len & 3) || !h->str_len)
        return -EBADSLT;
    types = (const char *)btf_buf + base + h->type_off;
    types_len = h->type_len;
    str = (const char *)btf_buf + base + h->str_off;
    len = h->str_len;
    if (str[0] || str[len - 1])
        return -EBADSLT;
    /* Pass one validates the complete stream before any type-ID traversal. */
    while (off < types_len) {
        const struct btf_type_rec *t;
        int extra;
        if (types_len - off < sizeof(*t))
            return -EBADSLT;
        t = (const void *)(types + off);
        extra = btf_type_size((t->info >> 24) & 31, t->info & 65535);
        if (extra < 0 || (u32)extra > types_len - off - sizeof(*t) ||
            !btf_str(str, len, t->name_off))
            return -EBADSLT;
        off += sizeof(*t) + extra;
    }
    off = 0;
    while (off < types_len) {
        const struct btf_type_rec *t = (const void *)(types + off);
        u32 kind = (t->info >> 24) & 31, vlen = t->info & 65535;
        const char *name = btf_str(str, len, t->name_off);
        if (kind == BTF_KIND_STRUCT) {
            const struct btf_member_rec *m = (const void *)(t + 1);
            for (u32 i = 0; i < vlen; i++) {
                const char *mn = btf_str(str, len, m[i].name_off);
                bool kflag = !!(t->info & 0x80000000u);
                u32 bits = kflag ? m[i].offset & 0xffffffu : m[i].offset;
                if (!mn)
                    return -EBADSLT;
                for (size_t w = 0; w < AW_WANTED_N; w++) {
                    struct aw_wanted *want = &aw_wanted[w];
                    if (kf_strcmp(name, want->sname) || kf_strcmp(mn, want->mname))
                        continue;
                    if (want->found || (kflag && (m[i].offset >> 24)) || (bits & 7) ||
                        bits / 8 > 65536 || bits / 8 > t->size_type ||
                        want->width > t->size_type - bits / 8 ||
                        btf_width(m[i].type, 0) != want->width)
                        return -ENODATA;
                    *want->out = bits / 8;
                    want->found = true;
                if (!kf_strcmp(name, "security_bprm_check_security") ||
                    !kf_strcmp(name, "security_bprm_check"))
                    aw_bprm_valid = true;
                if (!kf_strcmp(name, "security_file_truncate"))
                    aw_truncate_valid = true;
                }
            }
        }
        if (kind == BTF_KIND_FUNC) {
            for (size_t w = 0; w < AW_ARRAY_SIZE(protos); w++) {
                struct aw_proto *want = &protos[w];
                const struct btf_type_rec *fn, *ret;
                const u32 *params;
                u32 n;
                if (kf_strcmp(name, want->name))
                    continue;
                fn = btf_id(t->size_type);
                if (!fn || ((fn->info >> 24) & 31) != BTF_KIND_FUNC_PROTO || want->found)
                    return -EOPNOTSUPP;
                n = fn->info & 65535;
                ret = btf_unqual(fn->size_type);
                if (!ret || ((ret->info >> 24) & 31) != BTF_KIND_INT || ret->size_type != 4)
                    return -EOPNOTSUPP;
                params = (const void *)(fn + 1);
                if (!want->nargs) {
                    if (n == 2 && btf_ptr_to(params[1], "dentry", str, len) &&
                        btf_ptr_to(params[3], "iattr", str, len))
                        aw_setattr_args = 2;
                    else if (n == 3 && btf_ptr_to(params[1], "mnt_idmap", str, len) &&
                             btf_ptr_to(params[3], "dentry", str, len) &&
                             btf_ptr_to(params[5], "iattr", str, len))
                        aw_setattr_args = 3;
                    else
                        return -EOPNOTSUPP;
                } else {
                    if (n != (u32)want->nargs)
                        return -EOPNOTSUPP;
                    for (u32 i = 0; i < n; i++) {
                        const struct btf_type_rec *pt = btf_unqual(params[2*i+1]);
                        if (want->params[i]) {
                            if (!btf_ptr_to(params[2*i+1], want->params[i], str, len))
                                return -EOPNOTSUPP;
                        } else if (!pt || ((pt->info >> 24) & 31) != BTF_KIND_INT ||
                                   (pt->size_type != 4 && pt->size_type != 8)) {
                            return -EOPNOTSUPP;
                        }
                    }
                    if (!kf_strcmp(name, "security_inode_rename"))
                        aw_rename_args = n;
                }
                want->found = true;
                if (!kf_strcmp(name, "security_bprm_check_security") ||
                    !kf_strcmp(name, "security_bprm_check"))
                    aw_bprm_valid = true;
                if (!kf_strcmp(name, "security_file_truncate"))
                    aw_truncate_valid = true;
            }
        }
        off += sizeof(*t) + btf_type_size(kind, vlen);
    }
    for (size_t w = 0; w < AW_WANTED_N; w++) {
        if (!aw_wanted[w].found) {
            aw_err("btf: missing or incompatible %s.%s\n", aw_wanted[w].sname, aw_wanted[w].mname);
            return -ENODATA;
        }
    }
    for (size_t w = 0; w < AW_ARRAY_SIZE(protos); w++) {
        if (w < 3 && !protos[w].found)
            continue; /* absent optional dispatcher */
        if (!protos[w].found) {
            aw_err("btf: missing hook prototype %s\n", protos[w].name);
            return -EOPNOTSUPP;
        }
    }
    if (aw_off.path_mnt != 0 || aw_off.path_dentry != 8)
        return -EOPNOTSUPP;
    aw_layout_ready = true;
    return 0;
}

/* ------------------------------------------------------------ file load */

static int btf_load_file(void)
{
    struct file_ *f;
    char *buf;
    size_t cap = 1024 * 1024;
    size_t used = 0;
    s64 pos = 0;

    f = filp_open("/sys/kernel/btf/vmlinux", 0 /* O_RDONLY */, 0);
    if ((unsigned long)f >= (unsigned long)-4096 || !f)
        return -ENOENT;

    buf = vmalloc(cap);
    if (!buf) {
        filp_close(f, NULL);
        return -ENOMEM;
    }

    for (;;) {
        long r;

        if (used == cap) {
            char *nb;
            if (cap >= 64u * 1024 * 1024) {
                vfree(buf);
                filp_close(f, NULL);
                return -E2BIG;
            }
            nb = vmalloc(cap * 2);
            if (!nb) {
                vfree(buf);
                filp_close(f, NULL);
                return -ENOMEM;
            }
            memcpy(nb, buf, used);
            vfree(buf);
            buf = nb;
            cap *= 2;
        }

        r = kernel_read(f, buf + used, cap - used, &pos);
        if (r < 0) {
            vfree(buf);
            filp_close(f, NULL);
            return -EIO;
        }
        if (r == 0)
            break;
        used += (size_t)r;
    }

    filp_close(f, NULL);

    btf_buf = buf;
    btf_size = used;
    return 0;
}

/* ------------------------------------------------------------------ api */

int aw_btf_init(void)
{
    int rc;

    aw_btf_shutdown();
    memset(&aw_off, 0, sizeof(aw_off));

    rc = btf_load_file();
    if (rc)
        return rc;

    rc = btf_parse();
    if (rc) {
        vfree(btf_buf);
        btf_buf = NULL;
        return rc;
    }


    return 0;
}

void aw_btf_shutdown(void)
{
    if (btf_buf) {
        vfree(btf_buf);
        btf_buf = NULL;
    }
    btf_size = 0;
    aw_layout_ready = false;
}
