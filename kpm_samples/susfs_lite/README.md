# susfs_lite.kpm

Experimental stat/access hiding for stock kernels. Root and configured UIDs keep
normal results; a successful query of a configured absolute path or descendant
returns `-ENOENT` for other UIDs. Directory listings are not hidden. Relative
paths, symlink aliases, and complete SUSFS semantics are outside this sample.

## Cross-KMI contract

The former APK sample embedded Android 6.6 `struct kretprobe` and accessed
`struct kretprobe_instance::data` and `pt_regs`. These are private, config- and
version-dependent layouts, not a universal KMI. Supplying a wrong object size,
callback offset or instance-data offset can corrupt kernel memory. Merely
matching release numbers is insufficient. CFI also rejects calls to callbacks
without the kernel's expected type metadata.

The sample now has **no kernel-header or kernel-layout dependencies**. It imports
only the three versioned functions in `path_hook_v1.h`; native probes, path copy,
entry UID capture, registers, and callback CFI bridging belong to the engine
compiled against the running kernel. `abi_v1()` must return 1 before registration.
Missing exports fail during relocation, before init or any probe registration;
unsupported runtime/target returns an errno and rolls back earlier registrations.
There is no direct-kretprobe fallback. The built-in feature remains implemented.

The engine adapter must enforce the contract documented in that header, including
synchronous unregister and a noinline `__nocfi` bridge into the freestanding
filter callback. Both targets must be available: `vfs_fstatat` and `do_faccessat`,
with filename argument 1. A kernel lacking either target fails closed at load.
The engine must check target support rather than guess function signatures.
Coverage is limited to callers reaching these two targets; statx has a separate
path on some kernels. No universal stat coverage claim.

Configuration stays immutable while loaded. `control` returns `-EBUSY`; unload
then load to change it. This prevents duplicate registration and callback/data
races. Installation is all-or-nothing, and unload drains handles in reverse order.

## Build and test

```sh
make                         # clang AArch64 ET_REL, no kernel tree needed
make test                    # actual C lifecycle/parser/filter with fake engine
xudc kpm load /data/local/tmp/susfs_lite.kpm \
  "allow_uids=10236;prefix=/data/adb,/debug_ramdisk"
xudc kpm unload susfs_lite
```

`KDIR` and `SRC` arguments from existing CI are harmless. Runtime exports must
land with this sample. Test ABI mismatch before registration, missing second hook
rollback, successful hiding and UID exceptions, boundary matching, parser overflow,
reload rejection, repeated exit and re-load. The mock uses opaque handles and no
native probe layout. Engine-native integration tests must separately verify CFI,
correct user-copy handling and unregister drain on each supported KMI. Host tests
do not establish device safety; no device validation has been performed.

All changes are original adaptation under the existing GPL-2.0-or-later license;
no new third-party source is incorporated.
