/* SPDX-License-Identifier: GPL-2.0 */
#ifndef XEC_KPM_ELF_VALIDATE_H
#define XEC_KPM_ELF_VALIDATE_H
/* Caller supplies Elf64_* types and memchr/memcmp (kernel or host libc). */
#define KPM_MAX_IMAGE (64UL * 1024 * 1024)
static int kpm_validate_elf(const void *data, unsigned long len)
{
    const Elf64_Ehdr *h = data;
    const Elf64_Shdr *s, *names;
    unsigned int i, j;
    unsigned long budget = 0;
    if (len < sizeof(*h) || len > KPM_MAX_IMAGE)
        return -1;
    if (memcmp(h->e_ident, ELFMAG, SELFMAG) || h->e_ident[EI_CLASS] != ELFCLASS64 ||
        h->e_ident[EI_DATA] != ELFDATA2LSB || h->e_type != ET_REL ||
        h->e_machine != EM_AARCH64 || h->e_shentsize != sizeof(*s) ||
        !h->e_shnum || !h->e_shstrndx || h->e_shstrndx >= h->e_shnum ||
        h->e_shoff % __alignof__(Elf64_Shdr) || h->e_shoff > len ||
        h->e_shnum > (len - h->e_shoff) / sizeof(*s))
        return -1;
    s = (const Elf64_Shdr *)((const char *)data + h->e_shoff);
    names = s + h->e_shstrndx;
    if (names->sh_type != SHT_STRTAB || !names->sh_size || names->sh_offset > len ||
        names->sh_size > len - names->sh_offset)
        return -1;
    for (i = 0; i < h->e_shnum; i++) {
        const Elf64_Shdr *sh = s + i;
        const char *name;
        if ((sh->sh_type != SHT_NOBITS &&
             (sh->sh_offset > len || sh->sh_size > len - sh->sh_offset)) ||
            sh->sh_name >= names->sh_size ||
            !memchr((const char *)data + names->sh_offset + sh->sh_name, 0,
                    names->sh_size - sh->sh_name))
            return -1;
        if (sh->sh_addralign > KPM_MAX_IMAGE ||
            (sh->sh_addralign && (sh->sh_addralign & (sh->sh_addralign - 1))))
            return -1;
        if (sh->sh_flags & SHF_ALLOC) {
            /* Bound layout arithmetic, including per-section alignment. */
            if (sh->sh_size > KPM_MAX_IMAGE ||
                budget > KPM_MAX_IMAGE - sh->sh_size ||
                sh->sh_addralign > KPM_MAX_IMAGE - budget - sh->sh_size)
                return -1;
            budget += sh->sh_size + sh->sh_addralign;
        }
        name = (const char *)data + names->sh_offset + sh->sh_name;
        if (!strcmp(name, ".kpm.info") &&
            (sh->sh_type == SHT_NOBITS || !sh->sh_size ||
             ((const char *)data)[sh->sh_offset + sh->sh_size - 1]))
            return -1;
        if ((!strcmp(name, ".kpm.init") || !strcmp(name, ".kpm.exit") ||
             !strcmp(name, ".kpm.ctl0") || !strcmp(name, ".kpm.ctl1") ||
             !strcmp(name, ".kpm.event")) &&
            (!(sh->sh_flags & SHF_ALLOC) || sh->sh_type == SHT_NOBITS ||
             sh->sh_size != sizeof(unsigned long)))
            return -1;
        if (sh->sh_type == SHT_SYMTAB) {
            const Elf64_Sym *sym;
            const Elf64_Shdr *str;
            if (sh->sh_link >= h->e_shnum || s[sh->sh_link].sh_type != SHT_STRTAB ||
                sh->sh_entsize != sizeof(*sym) || sh->sh_size % sizeof(*sym) ||
                sh->sh_offset % __alignof__(Elf64_Sym))
                return -1;
            str = s + sh->sh_link;
            if (str->sh_offset > len || str->sh_size > len - str->sh_offset)
                return -1;
            sym = (const Elf64_Sym *)((const char *)data + sh->sh_offset);
            for (j = 0; j < sh->sh_size / sizeof(*sym); j++) {
                if (sym[j].st_name >= str->sh_size ||
                    !memchr((const char *)data + str->sh_offset + sym[j].st_name, 0,
                            str->sh_size - sym[j].st_name))
                    return -1;
                if (sym[j].st_shndx == SHN_COMMON ||
                    (sym[j].st_shndx != SHN_UNDEF && sym[j].st_shndx != SHN_ABS &&
                     sym[j].st_shndx >= h->e_shnum))
                    return -1;
                if (sym[j].st_shndx && sym[j].st_shndx < h->e_shnum &&
                    (sym[j].st_value > s[sym[j].st_shndx].sh_size ||
                     sym[j].st_size > s[sym[j].st_shndx].sh_size - sym[j].st_value))
                    return -1;
            }
        }
        if (sh->sh_type == SHT_REL || sh->sh_type == SHT_RELA) {
            unsigned long ent = sh->sh_type == SHT_RELA ? sizeof(Elf64_Rela) : sizeof(Elf64_Rel);
            if (sh->sh_link >= h->e_shnum || sh->sh_info >= h->e_shnum ||
                s[sh->sh_link].sh_type != SHT_SYMTAB || sh->sh_entsize != ent ||
                sh->sh_size % ent || sh->sh_offset % __alignof__(Elf64_Rela))
                return -1;
            if (sh->sh_type == SHT_RELA) {
                const Elf64_Rela *rel = (const Elf64_Rela *)((const char *)data + sh->sh_offset);
                unsigned long count = s[sh->sh_link].sh_size / sizeof(Elf64_Sym);
                for (j = 0; j < sh->sh_size / ent; j++) {
                    unsigned long width = 4;
                    unsigned int type = ELF64_R_TYPE(rel[j].r_info);
                    if (!type)
                        continue;
                    /* ABS64/PREL64 write eight bytes; narrow relocations are
                     * conservatively bounded to an instruction width. */
                    if (type == 257 || type == 260)
                        width = 8;
                    if (ELF64_R_SYM(rel[j].r_info) >= count ||
                        rel[j].r_offset > s[sh->sh_info].sh_size ||
                        width > s[sh->sh_info].sh_size - rel[j].r_offset)
                        return -1;
                }
            } else if (sh->sh_flags & SHF_ALLOC) {
                /* AArch64 modules require explicit addends. */
                return -1;
            }
        }
    }
    return 0;
}
#endif
