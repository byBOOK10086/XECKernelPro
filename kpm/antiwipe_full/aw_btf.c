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
};

static struct aw_wanted aw_wanted[] = {
    { "file", "f_mode", &aw_off.file_f_mode },
    { "file", "f_path", &aw_off.file_f_path },
    { "file", "f_inode", &aw_off.file_f_inode },
    { "path", "mnt", &aw_off.path_mnt },
    { "path", "dentry", &aw_off.path_dentry },
    { "inode", "i_mode", &aw_off.inode_i_mode },
    { "inode", "i_rdev", &aw_off.inode_i_rdev },
    { "dentry", "d_inode", &aw_off.dentry_d_inode },
    { "linux_binprm", "filename", &aw_off.bprm_filename },
    { "cred", "euid", &aw_off.cred_euid },
    { "mm_struct", "exe_file", &aw_off.mm_exe_file },
    { "task_struct", "flags", &aw_off.task_flags },
    { "iattr", "ia_valid", &aw_off.iattr_ia_valid },
};

#define AW_WANTED_N (AW_ARRAY_SIZE(aw_wanted))

/* --------------------------------------------------------------- parser */

static void *btf_buf;
static size_t btf_size;

static const char *btf_str(const char *str_base, u32 str_len, u32 name_off)
{
    if (name_off >= str_len)
        return NULL;
    return str_base + name_off;
}

static int btf_type_size(int kind, u32 vlen)
{
    switch (kind) {
    case BTF_KIND_INT:
    case BTF_KIND_FLOAT:
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

static void btf_scan_struct(const struct btf_type_rec *t, const struct btf_member_rec *m, u32 vlen,
                            const char *sname, const char *str_base, u32 str_len)
{
    u32 i;
    size_t w;

    for (i = 0; i < vlen; i++) {
        const char *mname = btf_str(str_base, str_len, m[i].name_off);
        u32 bit_off = m[i].offset & 0xffffffu; /* kflag packing: top 8 bits are bit size */

        if (!mname)
            continue;

        for (w = 0; w < AW_WANTED_N; w++) {
            if (!kf_strcmp(sname, aw_wanted[w].sname) && !kf_strcmp(mname, aw_wanted[w].mname)) {
                /* Bitfields land on byte boundaries for every member this
                 * module reads; anything weird fails the sanity check below. */
                if ((bit_off & 7) == 0 && bit_off / 8 < 4096)
                    *aw_wanted[w].out = bit_off / 8;
            }
        }
    }
}

static int btf_parse(void)
{
    const struct btf_header *h = (const struct btf_header *)btf_buf;
    const char *type_base;
    const char *str_base;
    u32 type_len, str_len;
    u32 off = 0;

    if (btf_size < sizeof(*h))
        return -EBADSLT;
    if (h->magic != BTF_MAGIC)
        return -EBADSLT;
    if (h->version != 1)
        return -EBADSLT;
    if (h->hdr_len < sizeof(*h) || h->hdr_len > btf_size)
        return -EBADSLT;

    type_base = btf_buf + h->hdr_len + h->type_off;
    type_len = h->type_len;
    str_base = btf_buf + h->hdr_len + h->str_off;
    str_len = h->str_len;

    if ((size_t)(h->hdr_len + h->type_off) + type_len > btf_size)
        return -EBADSLT;
    if ((size_t)(h->hdr_len + h->str_off) + str_len > btf_size)
        return -EBADSLT;

    while (off + sizeof(struct btf_type_rec) <= type_len) {
        const struct btf_type_rec *t = (const struct btf_type_rec *)(type_base + off);
        u32 vlen = t->info & 0xffffu;
        u32 kind = (t->info >> 24) & 0x1fu;
        u32 kflag = (t->info >> 29) & 1u;
        int extra = btf_type_size(kind, vlen);
        const char *sname;

        if (extra < 0)
            return -EBADSLT;

        sname = btf_str(str_base, str_len, t->name_off);

        if ((kind == BTF_KIND_STRUCT || kind == BTF_KIND_UNION) && sname && vlen &&
            off + sizeof(struct btf_type_rec) + extra <= type_len) {
            btf_scan_struct(t, (const struct btf_member_rec *)(t + 1), vlen, sname, str_base,
                            str_len);
        }

        /* kflag'd structs keep bitfield info in the same member slots; we
         * already masked to the low 24 bits, nothing else to do. */
        (void)kflag;

        off += sizeof(struct btf_type_rec) + extra;
    }

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
    size_t w;
    int rc;

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

    /* All members must have resolved to plausible byte offsets. */
    for (w = 0; w < AW_WANTED_N; w++) {
        if (*aw_wanted[w].out == 0 || *aw_wanted[w].out >= 4096) {
            aw_err("btf: missing offset for %s.%s\n", aw_wanted[w].sname, aw_wanted[w].mname);
            vfree(btf_buf);
            btf_buf = NULL;
            return -ENODATA;
        }
    }

    /* struct path members back our aw_path mirror; anything else means the
     * mirror assumption is wrong on this kernel. */
    if (aw_off.path_mnt != 0 || aw_off.path_dentry != 8) {
        aw_err("btf: struct path layout unexpected (mnt=%zu dentry=%zu)\n", aw_off.path_mnt,
               aw_off.path_dentry);
        vfree(btf_buf);
        btf_buf = NULL;
        return -ENODATA;
    }

    aw_info("btf: file.f_mode=%zu file.f_path=%zu inode.i_mode=%zu i_rdev=%zu "
            "dentry.d_inode=%zu cred.euid=%zu task.flags=%zu\n",
            aw_off.file_f_mode, aw_off.file_f_path, aw_off.inode_i_mode, aw_off.inode_i_rdev,
            aw_off.dentry_d_inode, aw_off.cred_euid, aw_off.task_flags);

    return 0;
}

void aw_btf_shutdown(void)
{
    if (btf_buf) {
        vfree(btf_buf);
        btf_buf = NULL;
    }
    btf_size = 0;
}
