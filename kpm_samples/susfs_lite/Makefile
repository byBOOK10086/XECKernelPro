# SPDX-License-Identifier: GPL-2.0-or-later
# Header-independent LP64 AArch64 ET_REL. KDIR/SRC accepted for old CI callers.
CC := clang
CFLAGS := --target=aarch64-linux-gnu -nostdinc -ffreestanding -fno-builtin \
    -fno-common -fno-pie -fno-stack-protector -fno-asynchronous-unwind-tables \
    -mgeneral-regs-only -mbranch-protection=bti -O2 -Wall -Wextra -Werror -I.
all: susfs_lite.kpm
susfs_lite.kpm: susfs_lite.c kpmodule.h path_hook_v1.h
	$(CC) $(CFLAGS) -c -o $@ susfs_lite.c
	@READELF=$$(command -v llvm-readelf || command -v readelf); \
	$$READELF -h $@; $$READELF -S $@ | grep '\.kpm\.'
test:
	$(CC) -std=c11 -Wall -Wextra -Werror -O2 -o /tmp/susfs-lite-test tests/contract_test.c
	/tmp/susfs-lite-test
clean:
	rm -f susfs_lite.kpm
.PHONY: all test clean
