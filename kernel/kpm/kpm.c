/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Copyright (C) 2025 Liankong (xhsw.new@outlook.com). All Rights Reserved.
 * 适配 KernelSU 的 KPM 内核模块加载器兼容实现。
 *
 * 集成了 ELF 解析、内存布局、符号处理、重定位（支持 ARM64 重定位类型），
 * 并参照 KernelPatch 的标准 KPM 格式实现加载和控制。
 *
 * 与 KernelPatch 的差异（适配点）：
 *  - 符号解析走 compact.c 的 sukisu_compact_find_symbol()，不再依赖 kallsyms_lookup_name。
 *  - 模块镜像内存用 vmalloc/vfree 分配（本内核不可用 module_alloc/execmem_alloc）。
 *  - supercall 分发复用 sukisu_handle_kpm()，本文件只实现 7 个被桥接函数。
 */

#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/fcntl.h>
#include <linux/file.h>
#include <linux/vmalloc.h>
#include <linux/uaccess.h>
#include <linux/elf.h>
#include <linux/version.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/err.h>
#include <linux/errno.h>
#include <linux/string.h>
#include <linux/slab.h>
#include <linux/uio.h>
#include <linux/mm.h>
#include <linux/module.h>
#include <linux/export.h>
#include <asm/barrier.h>
#include "kpm.h"
#include "compact.h"
#include "relo.h"
#include <kpmodule.h>

#define KPM_ERROR_MSG_LEN 160

/* access_ok() lost its leading "type" argument in Linux 5.0
 * (upstream 96d4f267e40f), so VERIFY_WRITE only exists on 4.x. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 0, 0)
#define kpm_access_ok(addr, size) access_ok((void __user *)(addr), (size))
#else
#define kpm_access_ok(addr, size) access_ok(VERIFY_WRITE, (void __user *)(addr), (size))
#endif

/* ITER_DEST/ITER_SOURCE replaced READ/WRITE only in v6.2 (upstream
 * de4eda9de2d9), so 5.10/5.15/6.1 still need READ here. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 2, 0)
#define KPM_ITER_DEST ITER_DEST
#else
#define KPM_ITER_DEST READ
#endif

#ifndef NO_OPTIMIZE
#if defined(__GNUC__) && !defined(__clang__)
#define NO_OPTIMIZE __attribute__((optimize("O0")))
#elif defined(__clang__)
#define NO_OPTIMIZE __attribute__((optnone))
#else
#define NO_OPTIMIZE
#endif
#endif

#if defined(CONFIG_CFI_CLANG) && defined(__clang__)
#define KPM_NO_CFI __attribute__((no_sanitize("cfi")))
#else
#define KPM_NO_CFI
#endif

/* ---------------------------------------------------------------------- */
/* Loader state                                                           */
/* ---------------------------------------------------------------------- */

struct kp_load_info {
    struct {
        const char *base;
        const char *name, *version, *license, *author, *description;
        char error_msg[KPM_ERROR_MSG_LEN];
    } info;
    const Elf64_Ehdr *hdr;
    unsigned long len;
    Elf64_Shdr *sechdrs;
    char *secstrings, *strtab;
    struct {
        unsigned int sym, str, info;
    } index;
};

struct kp_module {
    struct {
        const char *base, *name, *version, *license, *author, *description;
    } info;

    char *args, *ctl_args;

    mod_initcall_t *init;
    mod_ctl0call_t *ctl0;
    mod_ctl1call_t *ctl1;
    mod_exitcall_t *exit;
    mod_eventcall_t *event;

    unsigned int size;
    unsigned int text_size;
    unsigned int ro_size;

    void *start;

    struct list_head list;
};

static LIST_HEAD(kpm_modules);
static DEFINE_MUTEX(kpm_lock);

static int (*kpm_set_memory_x)(unsigned long addr, int numpages);
static int (*kpm_set_memory_nx)(unsigned long addr, int numpages);
static bool kpm_symbols_ready;

static void kpm_resolve_symbols(void)
{
    if (kpm_symbols_ready)
        return;

    kpm_set_memory_x = (int (*)(unsigned long, int))sukisu_compact_find_symbol("set_memory_x");
    kpm_set_memory_nx = (int (*)(unsigned long, int))sukisu_compact_find_symbol("set_memory_nx");
    kpm_symbols_ready = true;

    pr_info("kpm: set_memory_x=%px set_memory_nx=%px\n", kpm_set_memory_x, kpm_set_memory_nx);
}

static void kpm_set_module_exec(void *addr, unsigned int size)
{
    if (!kpm_set_memory_x || !addr)
        return;

    int npages = (size + PAGE_SIZE - 1) >> PAGE_SHIFT;
    int ret = kpm_set_memory_x((unsigned long)addr, npages);
    if (ret)
        pr_err("kpm: set_memory_x(%px, %d) = %d\n", addr, npages, ret);
}

static void kpm_set_module_nx(void *addr, unsigned int size)
{
    if (!kpm_set_memory_nx || !addr)
        return;

    int npages = (size + PAGE_SIZE - 1) >> PAGE_SHIFT;
    kpm_set_memory_nx((unsigned long)addr, npages);
}

static void *kpm_alloc_exec(unsigned long size)
{
    return vmalloc(size);
}

static void kpm_free_exec(void *region)
{
    vfree(region);
}

static void kpm_flush_icache(void *start, size_t size)
{
#ifdef CONFIG_ARM64
    (void)start;
    (void)size;
    dsb(ishst);
    asm volatile("ic iallu");
    dsb(ish);
    isb();
#else
    /* KPM 是 AArch64-only；其它架构在 ELF 校验阶段就会被拒绝。 */
    (void)start;
    (void)size;
#endif
}

static const char *kpm_str(const char *s)
{
    return s ? s : "";
}

/* Calls into bare-metal KPM code must be exempted from CFI. */
static noinline KPM_NO_CFI long kpm_call_init(mod_initcall_t *fn, const char *args, const char *event, void *reserved)
{
    return (*fn)(args, event, reserved);
}

static noinline KPM_NO_CFI long kpm_call_exit(mod_exitcall_t *fn, void *reserved)
{
    return (*fn)(reserved);
}

static noinline KPM_NO_CFI long kpm_call_ctl0(mod_ctl0call_t *fn, const char *args, char __user *out, int outlen)
{
    return (*fn)(args, out, outlen);
}

static struct kp_module *kpm_find_module(const char *name)
{
    struct kp_module *pos;

    list_for_each_entry (pos, &kpm_modules, list) {
        if (!strcmp(name, pos->info.name))
            return pos;
    }
    return NULL;
}

static void kpm_set_error(struct kp_load_info *info, const char *message)
{
    if (!info || !message)
        return;
    snprintf(info->info.error_msg, sizeof(info->info.error_msg), "%s", message);
}

/* ---------------------------------------------------------------------- */
/* ELF helpers                                                            */
/* ---------------------------------------------------------------------- */

static char *kpm_next_string(char *string, unsigned long *secsize)
{
    while (string[0]) {
        string++;
        if ((*secsize)-- <= 1)
            return 0;
    }
    while (!string[0]) {
        string++;
        if ((*secsize)-- <= 1)
            return 0;
    }
    return string;
}

static long kpm_get_offset(struct kp_module *mod, unsigned int *size, Elf64_Shdr *sechdr)
{
    long ret = ALIGN(*size, sechdr->sh_addralign ?: 1);
    *size = ret + sechdr->sh_size;
    return ret;
}

static char *kpm_get_next_modinfo(const struct kp_load_info *info, const char *tag, char *prev)
{
    char *p;
    unsigned int taglen = strlen(tag);
    Elf64_Shdr *infosec = &info->sechdrs[info->index.info];
    unsigned long size = infosec->sh_size;
    char *modinfo = (char *)info->hdr + infosec->sh_offset;

    if (prev) {
        size -= prev - modinfo;
        modinfo = kpm_next_string(prev, &size);
    }
    for (p = modinfo; p; p = kpm_next_string(p, &size)) {
        if (strncmp(p, tag, taglen) == 0 && p[taglen] == '=')
            return p + taglen + 1;
    }
    return NULL;
}

static char *kpm_get_modinfo(const struct kp_load_info *info, const char *tag)
{
    return kpm_get_next_modinfo(info, tag, 0);
}

static int kpm_find_sec(const struct kp_load_info *info, const char *name)
{
    for (int i = 1; i < info->hdr->e_shnum; i++) {
        Elf64_Shdr *shdr = &info->sechdrs[i];
        if ((shdr->sh_flags & SHF_ALLOC) && strcmp(info->secstrings + shdr->sh_name, name) == 0)
            return i;
    }
    return 0;
}

static void *kpm_get_sh_base(struct kp_load_info *info, const char *secname)
{
    int idx = kpm_find_sec(info, secname);
    if (!idx)
        return NULL;
    return (void *)info->hdr + info->sechdrs[idx].sh_offset;
}

static int kpm_elf_header_check(struct kp_load_info *info)
{
    if (info->len <= sizeof(*(info->hdr))) {
        kpm_set_error(info, "ELF header is truncated");
        return -ENOEXEC;
    }
    if (memcmp(info->hdr->e_ident, ELFMAG, SELFMAG) || info->hdr->e_type != ET_REL ||
        info->hdr->e_machine != EM_AARCH64 || info->hdr->e_shentsize != sizeof(Elf64_Shdr)) {
        kpm_set_error(info, "ELF header is not a supported AArch64 relocatable module");
        return -ENOEXEC;
    }
    if (info->hdr->e_shoff >= info->len ||
        (unsigned long)info->hdr->e_shnum * sizeof(Elf64_Shdr) > info->len - info->hdr->e_shoff) {
        kpm_set_error(info, "ELF section headers are invalid");
        return -ENOEXEC;
    }
    return 0;
}

static int kpm_rewrite_section_headers(struct kp_load_info *info)
{
    info->sechdrs[0].sh_addr = 0;
    for (int i = 1; i < info->hdr->e_shnum; i++) {
        Elf64_Shdr *shdr = &info->sechdrs[i];
        if (shdr->sh_type != SHT_NOBITS && info->len < (unsigned long)(shdr->sh_offset + shdr->sh_size))
            return -ENOEXEC;
        /* Mark all sections sh_addr with their address in the temporary image. */
        shdr->sh_addr = (size_t)info->hdr + shdr->sh_offset;
    }
    return 0;
}

static int kpm_setup_load_info(struct kp_load_info *info)
{
    int rc;

    info->sechdrs = (void *)info->hdr + info->hdr->e_shoff;
    info->secstrings = (void *)info->hdr + info->sechdrs[info->hdr->e_shstrndx].sh_offset;

    rc = kpm_rewrite_section_headers(info);
    if (rc) {
        kpm_set_error(info, "rewrite section headers failed");
        return rc;
    }

    if (!kpm_find_sec(info, ".kpm.init") || !kpm_find_sec(info, ".kpm.exit")) {
        kpm_set_error(info, "no .kpm.init or .kpm.exit section");
        return -ENOEXEC;
    }

    info->index.info = kpm_find_sec(info, ".kpm.info");
    if (!info->index.info) {
        kpm_set_error(info, "no .kpm.info section");
        return -ENOEXEC;
    }
    info->info.base = kpm_get_sh_base(info, ".kpm.info");

    const char *name = kpm_get_modinfo(info, "name");
    if (!name) {
        kpm_set_error(info, "module name not found");
        return -ENOEXEC;
    }
    info->info.name = name;

    const char *version = kpm_get_modinfo(info, "version");
    if (!version) {
        kpm_set_error(info, "module version not found");
        return -ENOEXEC;
    }
    info->info.version = version;

    info->info.license = kpm_get_modinfo(info, "license");
    info->info.author = kpm_get_modinfo(info, "author");
    info->info.description = kpm_get_modinfo(info, "description");

    for (int i = 1; i < info->hdr->e_shnum; i++) {
        if (info->sechdrs[i].sh_type == SHT_SYMTAB) {
            info->index.sym = i;
            info->index.str = info->sechdrs[i].sh_link;
            info->strtab = (char *)info->hdr + info->sechdrs[info->index.str].sh_offset;
            break;
        }
    }

    if (info->index.sym == 0) {
        kpm_set_error(info, "module has no symbols (stripped?)");
        return -ENOEXEC;
    }
    return 0;
}

static void kpm_layout_sections(struct kp_module *mod, struct kp_load_info *info)
{
    static const unsigned long masks[][2] = {
        /* executable code must be first; text_size finder relies on it */
        { SHF_EXECINSTR | SHF_ALLOC, 0 },
        { SHF_ALLOC, SHF_WRITE },
        { SHF_WRITE | SHF_ALLOC, 0 },
        { SHF_ALLOC, 0 },
    };

    for (int i = 0; i < info->hdr->e_shnum; i++)
        info->sechdrs[i].sh_entsize = ~0UL;

    for (int m = 0; m < (int)(sizeof(masks) / sizeof(masks[0])); ++m) {
        for (int i = 0; i < info->hdr->e_shnum; ++i) {
            Elf64_Shdr *s = &info->sechdrs[i];
            if ((s->sh_flags & masks[m][0]) != masks[m][0] || (s->sh_flags & masks[m][1]) || s->sh_entsize != ~0UL)
                continue;
            s->sh_entsize = kpm_get_offset(mod, &mod->size, s);
        }
        switch (m) {
        case 0: /* executable */
            mod->size = ALIGN(mod->size, PAGE_SIZE);
            mod->text_size = mod->size;
            break;
        case 1: /* RO: text and ro-data */
            mod->size = ALIGN(mod->size, PAGE_SIZE);
            mod->ro_size = mod->size;
            break;
        case 2:
            break;
        case 3: /* whole */
            mod->size = ALIGN(mod->size, PAGE_SIZE);
            break;
        }
    }
}

static bool kpm_is_core_symbol(const Elf64_Sym *src, const Elf64_Shdr *sechdrs, unsigned int shnum)
{
    const Elf64_Shdr *sec;

    if (src->st_shndx == SHN_UNDEF || src->st_shndx >= shnum || !src->st_name)
        return false;
    sec = sechdrs + src->st_shndx;
    if (!(sec->sh_flags & SHF_ALLOC) || !(sec->sh_flags & SHF_EXECINSTR))
        return false;
    return true;
}

/* Change all symbols so that st_value encodes the pointer directly. */
static int kpm_simplify_symbols(struct kp_module *mod, struct kp_load_info *info)
{
    Elf64_Shdr *symsec = &info->sechdrs[info->index.sym];
    Elf64_Sym *sym = (void *)symsec->sh_addr;
    unsigned long secbase;
    unsigned int i;
    int ret = 0;

    (void)mod;

    for (i = 1; i < symsec->sh_size / sizeof(Elf64_Sym); i++) {
        const char *name = info->strtab + sym[i].st_name;
        switch (sym[i].st_shndx) {
        case SHN_COMMON:
            if (!strncmp(name, "__gnu_lto", 9)) {
                kpm_set_error(info, "Please compile with -fno-common");
                ret = -ENOEXEC;
            }
            break;
        case SHN_ABS:
            break;
        case SHN_UNDEF: {
            unsigned long addr = sukisu_compact_find_symbol(name);
            if (!addr) {
                pr_err("kpm: unknown symbol: %s\n", name);
                if (!info->info.error_msg[0])
                    snprintf(info->info.error_msg, sizeof(info->info.error_msg), "unknown symbol: %s", name);
                ret = -ENOENT;
                break;
            }
            sym[i].st_value = addr;
            break;
        }
        default:
            secbase = info->sechdrs[sym[i].st_shndx].sh_addr;
            sym[i].st_value += secbase;
            break;
        }
    }
    return ret;
}

static int kpm_apply_relocations(struct kp_module *mod, const struct kp_load_info *info)
{
    int rc = 0;
    unsigned int i;

    (void)mod;

    for (i = 1; i < info->hdr->e_shnum; i++) {
        unsigned int infosec = info->sechdrs[i].sh_info;
        if (infosec >= info->hdr->e_shnum)
            continue;
        if (!(info->sechdrs[infosec].sh_flags & SHF_ALLOC))
            continue;
        if (info->sechdrs[i].sh_type == SHT_REL) {
            rc = kp_apply_relocate(info->sechdrs, info->strtab, info->index.sym, i, mod);
        } else if (info->sechdrs[i].sh_type == SHT_RELA) {
            rc = kp_apply_relocate_add(info->sechdrs, info->strtab, info->index.sym, i, mod);
        }
        if (rc < 0)
            break;
    }
    return rc;
}

static void kpm_layout_symtab(struct kp_module *mod, struct kp_load_info *info)
{
    Elf64_Shdr *symsect = info->sechdrs + info->index.sym;
    Elf64_Shdr *strsect = info->sechdrs + info->index.str;
    const Elf64_Sym *src;
    unsigned int i, nsrc, ndst, strtab_size = 0;

    /* Put symbol section at end of module. */
    symsect->sh_flags |= SHF_ALLOC;
    symsect->sh_entsize = kpm_get_offset(mod, &mod->size, symsect);

    src = (void *)info->hdr + symsect->sh_offset;
    nsrc = symsect->sh_size / sizeof(*src);

    /* strtab always starts with a nul, so offset 0 is the empty string. */
    strtab_size = 1;
    for (ndst = i = 0; i < nsrc; i++) {
        if (i == 0 || kpm_is_core_symbol(src + i, info->sechdrs, info->hdr->e_shnum)) {
            strtab_size += strlen(&info->strtab[src[i].st_name]) + 1;
            ndst++;
        }
    }

    /* Append room for core symbols at end. */
    mod->size = ALIGN(mod->size, symsect->sh_addralign ?: 1);
    mod->size += ndst * sizeof(Elf64_Sym);
    mod->size += strtab_size;

    /* Put string table section at end of module. */
    strsect->sh_flags |= SHF_ALLOC;
    strsect->sh_entsize = kpm_get_offset(mod, &mod->size, strsect);
}

static int kpm_move_module(struct kp_module *mod, struct kp_load_info *info)
{
    mod->start = kpm_alloc_exec(mod->size);
    if (!mod->start)
        return -ENOMEM;
    memset(mod->start, 0, mod->size);

    for (int i = 1; i < info->hdr->e_shnum; i++) {
        void *dest;
        Elf64_Shdr *shdr = &info->sechdrs[i];
        if (!(shdr->sh_flags & SHF_ALLOC))
            continue;

        dest = mod->start + shdr->sh_entsize;
        const char *sname = info->secstrings + shdr->sh_name;

        if (shdr->sh_type != SHT_NOBITS)
            memcpy(dest, (void *)shdr->sh_addr, shdr->sh_size);

        shdr->sh_addr = (unsigned long)dest;

        if (!mod->init && !strcmp(".kpm.init", sname))
            mod->init = (mod_initcall_t *)dest;
        if (!strcmp(".kpm.ctl0", sname))
            mod->ctl0 = (mod_ctl0call_t *)dest;
        if (!strcmp(".kpm.ctl1", sname))
            mod->ctl1 = (mod_ctl1call_t *)dest;
        if (!mod->exit && !strcmp(".kpm.exit", sname))
            mod->exit = (mod_exitcall_t *)dest;
        if (!mod->event && !strcmp(".kpm.event", sname))
            mod->event = (mod_eventcall_t *)dest;
        if (!mod->info.base && !strcmp(".kpm.info", sname))
            mod->info.base = (const char *)dest;
    }

    mod->info.name = info->info.name - info->info.base + mod->info.base;
    mod->info.version = info->info.version - info->info.base + mod->info.base;
    if (info->info.license)
        mod->info.license = info->info.license - info->info.base + mod->info.base;
    if (info->info.author)
        mod->info.author = info->info.author - info->info.base + mod->info.base;
    if (info->info.description)
        mod->info.description = info->info.description - info->info.base + mod->info.base;

    return 0;
}

/* ---------------------------------------------------------------------- */
/* Loader core                                                            */
/* ---------------------------------------------------------------------- */

static long kpm_load_module(const void *data, int len, const char *args, const char *event)
{
    struct kp_load_info load_info = { .len = len, .hdr = data };
    struct kp_load_info *info = &load_info;
    struct kp_module *mod = NULL;
    long rc = 0;

    rc = kpm_elf_header_check(info);
    if (rc)
        goto out;
    rc = kpm_setup_load_info(info);
    if (rc)
        goto out;

    if (kpm_find_module(info->info.name)) {
        pr_err("kpm: module %s already exists\n", info->info.name);
        kpm_set_error(info, "module already exists");
        rc = -EEXIST;
        goto out;
    }

    mod = kzalloc(sizeof(*mod), GFP_KERNEL);
    if (!mod) {
        kpm_set_error(info, "allocate module state failed");
        rc = -ENOMEM;
        goto out;
    }

    if (args && args[0]) {
        mod->args = kstrdup(args, GFP_KERNEL);
        if (!mod->args) {
            kpm_set_error(info, "allocate module args failed");
            rc = -ENOMEM;
            goto free_mod;
        }
    }

    kpm_layout_sections(mod, info);
    kpm_layout_symtab(mod, info);

    rc = kpm_move_module(mod, info);
    if (rc) {
        kpm_set_error(info, "allocate executable module memory failed");
        goto free_args;
    }

    rc = kpm_simplify_symbols(mod, info);
    if (rc)
        goto free_exec;

    rc = kpm_apply_relocations(mod, info);
    if (rc) {
        kpm_set_error(info, "apply relocations failed");
        goto free_exec;
    }

    if (!mod->init || !*mod->init) {
        kpm_set_error(info, "module has no init function");
        rc = -ENOEXEC;
        goto free_exec;
    }

    kpm_set_module_exec(mod->start, mod->size);
    kpm_flush_icache(mod->start, mod->size);

    rc = kpm_call_init(mod->init, mod->args, event, NULL);
    if (!rc) {
        list_add_tail(&mod->list, &kpm_modules);
        pr_info("kpm: module [%s] loaded, image=%px size=%u\n", mod->info.name, mod->start, mod->size);
        goto out;
    }

    kpm_set_error(info, "module init failed");
    pr_err("kpm: module [%s] init failed rc=%ld, calling exit\n", mod->info.name, rc);
    if (mod->exit && *mod->exit)
        kpm_call_exit(mod->exit, NULL);

free_exec:
    kpm_set_module_nx(mod->start, mod->size);
    kpm_free_exec(mod->start);
free_args:
    if (mod->args)
        kfree(mod->args);
free_mod:
    kfree(mod);
out:
    if (rc && info->info.error_msg[0])
        pr_err("kpm: load failed: %s\n", info->info.error_msg);
    return rc;
}

/* kernel_read() was removed in newer kernels; read into a kernel buffer via
 * f_op->read when available, otherwise via the read_iter + ITER_KVEC path. */
static ssize_t kpm_kernel_read(struct file *file, void *buf, size_t count, loff_t *pos)
{
    if (file->f_op->read)
        return file->f_op->read(file, (char __user *)buf, count, pos);

    if (file->f_op->read_iter) {
        struct kvec iov = { .iov_base = buf, .iov_len = count };
        struct kiocb kiocb = { .ki_filp = file };
        struct iov_iter iter;
        ssize_t ret;

        kiocb.ki_pos = *pos;
        iov_iter_kvec(&iter, KPM_ITER_DEST, &iov, 1, count);
        ret = file->f_op->read_iter(&kiocb, &iter);
        if (ret >= 0)
            *pos = kiocb.ki_pos;
        return ret;
    }
    return -EINVAL;
}

static long kpm_load_module_path(const char *path, const char *args)
{
    struct file *filp = NULL;
    void *data = NULL;
    loff_t len;
    long rc = 0;

    if (!path) {
        rc = -EINVAL;
        goto out;
    }

    filp = filp_open(path, O_RDONLY, 0);
    if (IS_ERR(filp)) {
        rc = PTR_ERR(filp);
        filp = NULL;
        pr_err("kpm: open module %s failed: %ld\n", path, rc);
        goto out;
    }

    len = vfs_llseek(filp, 0, SEEK_END);
    if (len < 0) {
        rc = len;
        goto close;
    }
    if (vfs_llseek(filp, 0, SEEK_SET) < 0) {
        rc = -EIO;
        goto close;
    }
    if (len <= 0) {
        rc = -EINVAL;
        goto close;
    }

    data = vmalloc(len);
    if (!data) {
        rc = -ENOMEM;
        goto close;
    }
    memset(data, 0, len);

    {
        loff_t pos = 0;
        ssize_t rd = kpm_kernel_read(filp, data, len, &pos);
        if (rd != len) {
            pr_err("kpm: read module %s failed: %zd\n", path, rd);
            rc = -EIO;
            goto free_data;
        }
    }

    mutex_lock(&kpm_lock);
    kpm_resolve_symbols();
    rc = kpm_load_module(data, (int)len, args, "load-file");
    mutex_unlock(&kpm_lock);

free_data:
    vfree(data);
close:
    filp_close(filp, NULL);
out:
    if (rc)
        pr_err("kpm: load %s failed: %ld\n", path ? path : "(null)", rc);
    return rc;
}

static long kpm_unload_module(const char *name)
{
    long rc = 0;

    if (!name)
        return -EINVAL;

    mutex_lock(&kpm_lock);
    kpm_resolve_symbols();

    struct kp_module *mod = kpm_find_module(name);
    if (!mod) {
        rc = -ENOENT;
        goto out;
    }

    list_del(&mod->list);
    if (mod->exit && *mod->exit)
        rc = kpm_call_exit(mod->exit, NULL);
    else
        rc = 0;

    if (mod->args)
        kfree(mod->args);
    if (mod->ctl_args)
        kfree(mod->ctl_args);

    kpm_set_module_nx(mod->start, mod->size);
    kpm_free_exec(mod->start);
    kfree(mod);

    pr_info("kpm: module [%s] unloaded, rc=%ld\n", name, rc);

out:
    mutex_unlock(&kpm_lock);
    return rc;
}

static int kpm_build_list(char *out, int size)
{
    struct kp_module *pos;
    int total = 0;
    int off = 0;

    if (!out || size <= 0)
        return -EINVAL;

    out[0] = '\0';

    list_for_each_entry (pos, &kpm_modules, list) {
        int n = strlen(pos->info.name) + 1; /* "name\n" */
        total += n;

        if (off < size - 1) {
            int avail = size - off;
            int w = snprintf(out + off, avail, "%s\n", pos->info.name);
            if (w < 0)
                w = 0;
            if (w >= avail) {
                out[size - 1] = '\0';
                off = size;
            } else {
                off += w;
            }
        }
    }

    if (off > 0 && off < size)
        out[off - 1] = '\0';
    else if (size > 0)
        out[size - 1] = '\0';

    return total;
}

/* ---------------------------------------------------------------------- */
/* 7 bridge functions (signatures unchanged)                              */
/* ---------------------------------------------------------------------- */

noinline NO_OPTIMIZE void sukisu_kpm_load_module_path(const char *path, const char *args, void *ptr, int *result)
{
    long rc;

    (void)ptr;

    rc = kpm_load_module_path(path, args);
    if (result)
        *result = (int)rc;
}
EXPORT_SYMBOL(sukisu_kpm_load_module_path);

noinline NO_OPTIMIZE void sukisu_kpm_unload_module(const char *name, void *ptr, int *result)
{
    long rc;

    (void)ptr;

    rc = kpm_unload_module(name);
    if (result)
        *result = (int)rc;
}
EXPORT_SYMBOL(sukisu_kpm_unload_module);

noinline NO_OPTIMIZE void sukisu_kpm_num(int *result)
{
    int n = 0;
    struct kp_module *pos;

    mutex_lock(&kpm_lock);
    list_for_each_entry (pos, &kpm_modules, list) {
        n++;
    }
    mutex_unlock(&kpm_lock);

    if (result)
        *result = n;
}
EXPORT_SYMBOL(sukisu_kpm_num);

noinline NO_OPTIMIZE void sukisu_kpm_info(const char *name, char *buf, int bufferSize, int *size)
{
    int sz = 0;

    if (size)
        *size = 0;
    if (!name || !buf || bufferSize <= 0)
        return;

    mutex_lock(&kpm_lock);
    struct kp_module *mod = kpm_find_module(name);
    if (mod) {
        snprintf(buf, bufferSize,
                 "name=%s\n"
                 "version=%s\n"
                 "license=%s\n"
                 "author=%s\n"
                 "description=%s\n"
                 "args=%s\n",
                 mod->info.name, kpm_str(mod->info.version), kpm_str(mod->info.license), kpm_str(mod->info.author),
                 kpm_str(mod->info.description), kpm_str(mod->args));
        buf[bufferSize - 1] = '\0';
        sz = strlen(buf) + 1;
    }
    mutex_unlock(&kpm_lock);

    if (size)
        *size = sz;
}
EXPORT_SYMBOL(sukisu_kpm_info);

noinline NO_OPTIMIZE void sukisu_kpm_list(void *out, int bufferSize, int *result)
{
    int rc;

    mutex_lock(&kpm_lock);
    rc = kpm_build_list((char *)out, bufferSize);
    mutex_unlock(&kpm_lock);

    if (result)
        *result = rc;
}
EXPORT_SYMBOL(sukisu_kpm_list);

noinline NO_OPTIMIZE void sukisu_kpm_control(const char *name, const char *args, long arg_len, int *result)
{
    long rc = -EINVAL;

    if (name && args && arg_len > 0) {
        mutex_lock(&kpm_lock);
        struct kp_module *mod = kpm_find_module(name);
        if (!mod) {
            rc = -ENOENT;
        } else if (!mod->ctl0 || !*mod->ctl0) {
            rc = -ENOSYS;
        } else {
            if (mod->ctl_args)
                kfree(mod->ctl_args);
            mod->ctl_args = kstrndup(args, arg_len, GFP_KERNEL);
            if (!mod->ctl_args) {
                rc = -ENOMEM;
            } else {
                rc = kpm_call_ctl0(mod->ctl0, mod->ctl_args, NULL, 0);
            }
        }
        mutex_unlock(&kpm_lock);
    }

    if (result)
        *result = (int)rc;
}
EXPORT_SYMBOL(sukisu_kpm_control);

noinline NO_OPTIMIZE void sukisu_kpm_version(char *buf, int bufferSize)
{
    if (buf && bufferSize > 0)
        strscpy(buf, KSU_VERSION_FULL, bufferSize);
}
EXPORT_SYMBOL(sukisu_kpm_version);

/* ---------------------------------------------------------------------- */
/* supercall 分发（保持不变）                                             */
/* ---------------------------------------------------------------------- */

noinline int sukisu_handle_kpm(unsigned long control_code, unsigned long arg1, unsigned long arg2,
                               unsigned long result_code)
{
    int res = -1;
    if (control_code == SUKISU_KPM_LOAD) {
        char kernel_load_path[256] = { 0 };
        char kernel_args_buffer[256] = { 0 };

        if (arg1 == 0) {
            res = -EINVAL;
            goto exit;
        }

        if (!kpm_access_ok(arg1, 255)) {
            goto invalid_arg;
        }

        if (strncpy_from_user(kernel_load_path, (const char __user *)arg1, 255) < 0) {
            res = -EINVAL;
            goto exit;
        }

        if (arg2 != 0) {
            if (!kpm_access_ok(arg2, 255)) {
                goto invalid_arg;
            }

            if (strncpy_from_user(kernel_args_buffer, (const char __user *)arg2, 255) < 0) {
                res = -EINVAL;
                goto exit;
            }
        }

        sukisu_kpm_load_module_path((const char *)&kernel_load_path, (const char *)&kernel_args_buffer, NULL, &res);
    } else if (control_code == SUKISU_KPM_UNLOAD) {
        char kernel_name_buffer[256] = { 0 };

        if (arg1 == 0) {
            res = -EINVAL;
            goto exit;
        }

        if (!kpm_access_ok(arg1, sizeof(kernel_name_buffer))) {
            goto invalid_arg;
        }

        if (strncpy_from_user(kernel_name_buffer, (const char __user *)arg1, sizeof(kernel_name_buffer)) < 0) {
            res = -EINVAL;
            goto exit;
        }

        sukisu_kpm_unload_module((const char *)&kernel_name_buffer, NULL, &res);
    } else if (control_code == SUKISU_KPM_NUM) {
        sukisu_kpm_num(&res);
    } else if (control_code == SUKISU_KPM_INFO) {
        char kernel_name_buffer[256] = { 0 };
        char buf[256] = { 0 };
        int size = 0;

        if (arg1 == 0 || arg2 == 0) {
            res = -EINVAL;
            goto exit;
        }

        if (!kpm_access_ok(arg1, sizeof(kernel_name_buffer))) {
            goto invalid_arg;
        }

        if (strncpy_from_user(kernel_name_buffer, (const char __user *)arg1, sizeof(kernel_name_buffer)) < 0) {
            res = -EINVAL;
            goto exit;
        }

        sukisu_kpm_info((const char *)&kernel_name_buffer, (char *)&buf, sizeof(buf), &size);

        if (!kpm_access_ok(arg2, size)) {
            goto invalid_arg;
        }

        res = copy_to_user((void __user *)arg2, &buf, size);

    } else if (control_code == SUKISU_KPM_LIST) {
        char buf[1024];
        int len = (int)arg2;

        if (len <= 0) {
            res = -EINVAL;
            goto exit;
        }

        if (len > (int)sizeof(buf)) {
            len = (int)sizeof(buf);
        }

        if (!kpm_access_ok(arg1, len)) {
            goto invalid_arg;
        }

        sukisu_kpm_list((char *)&buf, sizeof(buf), &res);

        if (res < 0 || res > len) {
            res = -ENOBUFS;
            goto exit;
        }

        if (res > 0 && copy_to_user((void __user *)arg1, &buf, res) != 0)
            pr_info("kpm: Copy to user failed.");

    } else if (control_code == SUKISU_KPM_CONTROL) {
        char kpm_name[KPM_NAME_LEN] = { 0 };
        char kpm_args[KPM_ARGS_LEN] = { 0 };

        if (!kpm_access_ok(arg1, sizeof(kpm_name))) {
            goto invalid_arg;
        }

        if (!kpm_access_ok(arg2, sizeof(kpm_args))) {
            goto invalid_arg;
        }

        long name_len = strncpy_from_user((char *)&kpm_name, (const char __user *)arg1, sizeof(kpm_name));
        if (name_len <= 0) {
            res = -EINVAL;
            goto exit;
        }

        long arg_len = strncpy_from_user((char *)&kpm_args, (const char __user *)arg2, sizeof(kpm_args));

        sukisu_kpm_control((const char *)&kpm_name, (const char *)&kpm_args, arg_len, &res);

    } else if (control_code == SUKISU_KPM_VERSION) {
        char buffer[256] = { 0 };

        sukisu_kpm_version((char *)&buffer, sizeof(buffer));

        unsigned int outlen = (unsigned int)arg2;
        int len = strlen(buffer);
        if (len >= outlen)
            len = outlen - 1;

        res = copy_to_user((void __user *)arg1, &buffer, len + 1);
    }

exit:
    if (copy_to_user((void __user *)result_code, &res, sizeof(res)) != 0)
        pr_info("kpm: Copy to user failed.");

    return 0;
invalid_arg:
    pr_err("kpm: invalid pointer detected! arg1: %px arg2: %px\n", (void *)arg1, (void *)arg2);
    res = -EFAULT;
    goto exit;
}
EXPORT_SYMBOL(sukisu_handle_kpm);

int sukisu_is_kpm_control_code(unsigned long control_code)
{
    return (control_code >= CMD_KPM_CONTROL && control_code <= CMD_KPM_CONTROL_MAX) ? 1 : 0;
}

int do_kpm(void __user *arg)
{
    struct ksu_kpm_cmd cmd;
    int control_code;

    if (copy_from_user(&cmd, arg, sizeof(cmd))) {
        pr_err("kpm: copy_from_user failed\n");
        return -EFAULT;
    }

    if (!kpm_access_ok(cmd.control_code, sizeof(int))) {
        pr_err("kpm: invalid control_code pointer %px\n", (void *)cmd.control_code);
        return -EFAULT;
    }

    if (!kpm_access_ok(cmd.result_code, sizeof(int))) {
        pr_err("kpm: invalid result_code pointer %px\n", (void *)cmd.result_code);
        return -EFAULT;
    }

    /* control_code is a user pointer to the actual command value; the old
	 * code passed the pointer itself, so every command comparison failed. */
    if (get_user(control_code, (int __user *)cmd.control_code)) {
        pr_err("kpm: get_user control_code failed\n");
        return -EFAULT;
    }

    return sukisu_handle_kpm((unsigned long)control_code, (unsigned long)cmd.arg1, (unsigned long)cmd.arg2,
                             (unsigned long)cmd.result_code);
}
