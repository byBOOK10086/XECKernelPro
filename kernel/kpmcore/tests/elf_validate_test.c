/* SPDX-License-Identifier: GPL-2.0 */
/* Run: cc -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined
 * kernel/kpmcore/tests/elf_validate_test.c -o /tmp/kpm-elf-test && /tmp/kpm-elf-test */
#include <assert.h>
#include <elf.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "../elf_validate.h"
struct fixture {
    Elf64_Ehdr h;
    Elf64_Shdr s[4];
    char names[32];
    char strings[8];
    Elf64_Sym symbols[2];
};
static struct fixture valid(void)
{
    struct fixture f = {0};
    memcpy(f.h.e_ident, ELFMAG, SELFMAG);
    f.h.e_ident[EI_CLASS] = ELFCLASS64;
    f.h.e_ident[EI_DATA] = ELFDATA2LSB;
    f.h.e_ident[EI_VERSION] = EV_CURRENT;
    f.h.e_version = EV_CURRENT;
    f.h.e_ehsize = sizeof(Elf64_Ehdr);
    f.h.e_type = ET_REL;
    f.h.e_machine = EM_AARCH64;
    f.h.e_shentsize = sizeof(Elf64_Shdr);
    f.h.e_shnum = 4;
    f.h.e_shstrndx = 1;
    f.h.e_shoff = (char *)f.s - (char *)&f;
    memcpy(f.names, "\0.shstrtab\0.strtab\0.symtab", 27);
    f.s[1].sh_type = SHT_STRTAB;
    f.s[1].sh_offset = (char *)f.names - (char *)&f;
    f.s[1].sh_size = sizeof(f.names);
    f.s[2].sh_type = SHT_STRTAB;
    f.s[2].sh_offset = (char *)f.strings - (char *)&f;
    f.s[2].sh_size = sizeof(f.strings);
    f.s[3].sh_type = SHT_SYMTAB;
    f.s[3].sh_offset = (char *)f.symbols - (char *)&f;
    f.s[3].sh_size = sizeof(f.symbols);
    f.s[3].sh_entsize = sizeof(Elf64_Sym);
    f.s[3].sh_link = 2;
    return f;
}
int main(void)
{
    struct fixture f = valid();
    assert(!kpm_validate_elf(&f, sizeof(f)));
    assert(kpm_validate_elf(&f, 8));
    f.h.e_shstrndx = 99;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.s[2].sh_offset = UINT64_MAX - 3; f.s[2].sh_size = 8;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.s[3].sh_link = 99;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.symbols[1].st_name = 99;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.symbols[1].st_shndx = 99;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.s[2].sh_name = sizeof(f.names);
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); memset(f.names, 'x', sizeof(f.names));
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.s[2].sh_flags = SHF_ALLOC; f.s[2].sh_addralign = 3;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.s[2].sh_flags = SHF_ALLOC; f.s[2].sh_size = KPM_MAX_IMAGE + 1;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.h.e_ehsize = 1;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.h.e_version = 0;
    assert(kpm_validate_elf(&f, sizeof(f)));
    f = valid(); f.h.e_ident[EI_VERSION] = 0;
    assert(kpm_validate_elf(&f, sizeof(f)));
    puts("KPM ELF validation: valid image + 12 malformed cases passed");
    return 0;
}
