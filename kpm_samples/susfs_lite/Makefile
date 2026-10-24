# SPDX-License-Identifier: GPL-2.0-or-later
#
# susfs_lite.kpm build (experimental sample; built into the manager APK
# by build-xecpro.yml's build-kpm job -> assets/kpm/susfs_lite.kpm).
#
# A KPM is a relocatable AArch64 ELF (ET_REL). It MUST be compiled against a
# real kernel tree: struct layouts (kretprobe, pt_regs) come from the target
# kernel's headers, and every kernel function it calls stays an undefined
# symbol that the KPM loader resolves at load time (kernel/kpm/relo.c ->
# sukisu_compact_find_symbol: compact table first, kallsyms fallback).
# A freestanding build with stub structs would be layout-guessing — exactly
# what the "one universal binary" honest note in CHANGELOG.md rejects.
#
# Build (DDK container or any prepared arm64 kernel tree):
#   make KDIR=/path/to/prepared-output-tree SRC=/path/to/kernel-source
#
# The prepared output tree (O= dir) only carries GENERATED headers
# (include/generated/*); the static headers (include/linux/*, arch includes)
# live in the source tree. That is exactly how KBUILD composes LINUXINCLUDE
# (srctree + objtree), mirrored below. In-tree builds (KDIR == SRC) work too.
#
# Load: xudc kpm load /data/adb/kpm/susfs_lite.kpm "allow_uids=<uid>;prefix=/data/adb"

KDIR ?= $(KDIR)
SRC ?= $(KDIR)
CC := clang

KINCLUDES := -I$(SRC)/arch/arm64/include \
    -I$(KDIR)/arch/arm64/include/generated \
    -I$(SRC)/include \
    -I$(KDIR)/include \
    -I$(SRC)/arch/arm64/include/uapi \
    -I$(KDIR)/arch/arm64/include/generated/uapi \
    -I$(SRC)/include/uapi \
    -I$(KDIR)/include/generated/uapi

CFLAGS := --target=aarch64-linux-gnu \
    -nostdinc $(KINCLUDES) \
    -include $(KDIR)/include/generated/autoconf.h \
    -D__KERNEL__ \
    -ffreestanding -fno-builtin -fno-common -fno-pie \
    -fno-stack-protector -fno-asynchronous-unwind-tables \
    -mgeneral-regs-only \
    -O2 -Wall -Wextra -Wno-unused-parameter \
    -I.

all: susfs_lite.kpm

susfs_lite.kpm: susfs_lite.c kpmodule.h
	$(CC) $(CFLAGS) -r -o $@ susfs_lite.c
	@READELF=$(shell command -v llvm-readelf || command -v readelf) ; \
	printf '== .kpm sections: '; $$READELF -S $@ | grep -c '\.kpm\.' ; \
	$$READELF -S $@ | grep '\.kpm\.' | sed 's/^/   /'

clean:
	rm -f susfs_lite.kpm
