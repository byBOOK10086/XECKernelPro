#!/bin/bash
# SPDX-License-Identifier: GPL-2.0-only
# Build antiwipe_full.kpm — freestanding aarch64 KPM module.
# Requires: clang with aarch64 backend + lld (any host: apt clang/lld, NDK, LLVM).
set -e
cd "$(dirname "$0")"

CLANG="${CLANG:-clang}"
LLD="${LLD:-ld.lld}"

CFLAGS="--target=aarch64-linux-gnu -O2 -std=gnu11 -nostdinc -ffreestanding
 -fno-pic -fno-PIC -fno-pie -fno-stack-protector
 -fno-asynchronous-unwind-tables -fno-unwind-tables
 -fno-exceptions -fno-common -mgeneral-regs-only -mno-outline-atomics
 -Wall -Wno-unused-function"

"$CLANG" $CFLAGS -c -o aw_btf.o aw_btf.c
"$CLANG" $CFLAGS -c -o antiwipe_full.o antiwipe_full.c
"$LLD" -r -o antiwipe.kpm aw_btf.o antiwipe_full.o

echo "built: $(pwd)/antiwipe.kpm"
