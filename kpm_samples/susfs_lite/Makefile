# SPDX-License-Identifier: GPL-2.0-or-later
#
# susfs_lite.kpm build (experimental sample, NOT wired into CI).
#
# A KPM is a relocatable AArch64 ELF (ET_REL). Build against the kernel's
# kpmodule.h ABI header with a bare-metal aarch64 toolchain:
#
#   make NDK=/path/to/android-ndk
#
# Load: xudc kpm load /data/local/tmp/susfs_lite.kpm "allow_uids=<manager_uid>;prefix=/data/adb"

NDK ?= $(HOME/Android/Sdk/ndk/latest)
CC := $(NDK)/toolchains/llvm/prebuilt/*/bin/clang

CFLAGS := --target=aarch64-linux-android \
    -ffreestanding -nostdlib -fno-builtin -fno-stack-protector \
    -fno-asynchronous-unwind-tables -mgeneral-regs-only \
    -O2 -Wall -Wextra -I. \
    -DKERNEL_VERSION_GUESS=0

all: susfs_lite.kpm

susfs_lite.kpm: susfs_lite.c kpmodule.h
	$(CC) $(CFLAGS) -r -o $@ susfs_lite.c

clean:
	rm -f susfs_lite.kpm
