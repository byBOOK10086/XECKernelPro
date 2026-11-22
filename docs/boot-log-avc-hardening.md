# Boot-log AVC leak hardening

## Threat model

Detection tools with root/shell access read `dmesg` and the Android log buffers
after boot and search them for evidence of a hidden root solution:

- `avc: denied` records whose source context is the root-implementation domain
  (`u:r:xecpro:s0` here, `u:r:ksu:s0` in upstream/forks), including the boot-time
  policy probes our own SELinux hide makes.
- daemon paths / binary names that only exist when the root implementation is
  installed.
- one-shot feature messages printed while the hide is being installed.

Our own boot-log collector keeps a 30 second window open from post-fs-data
(`defs::BOOTLOG_TIMEOUT`), so lines produced while it is attached also reach the
shared buffers.

## What was reversed from the reference manager

The competitor manager APK (`com.kaixin.ksu`) embeds a boot-completed script in
its `ksud` binary that clears the marked buffers twice:

1. `dmesg -C` plus `logcat -b <buffer> -c` for `main system radio events crash
   kernel security`, but only for buffers that match
   `avc:|kernelsu|u:r:ksu:s0`;
2. the same pass again after `sleep 35`, described in the script itself as
   "ksud captures boot logs for 30 seconds. Clear again after capture finishes."

Its manager also exposes `selinux_hide` as "sanitize /sys/fs/selinux access
results for app UIDs", i.e. the same app-UID sanitisation our kernel feature
already implements (`my_write_context`, `my_write_access`, fake status page,
`setprocattr` / `inode_setxattr` hooks).

## Changes in XECKernel Ultra

- `userspace/ksud/src/bootlog_scrub.{rs,sh}`: embedded two-phase scrubber, run from
  `run_boot_completed` and from the late-load path, so both the normal boot and a
  post-boot LKM load clean the buffers. Only buffers containing a marker are
  cleared; the marker set covers old and new domain names plus our feature
  strings left by older builds.
- `kernel/feature/selinux_hide.c`: every message that names the feature, the
  patched dispatch entries or the synthesized status counters is now `pr_debug`
  (silent unless dynamic debug is explicitly enabled). Failure paths that need an
  operator stay at `pr_err`/`pr_warn` and never print synthesized contexts.

## Residual risk

- Clearing buffers is a race: anything that read `dmesg`/`logcat` before the
  boot-completed hook runs has already seen the unfiltered log. The race window is
  bounded by the lifecycle hook ordering, not removed.
- `pr_err` failure paths still exist for real configuration failures and can
  reveal that the feature is installed; they are not attacker-triggerable.
- The SELinux hide only sanitises app UIDs (`uid >= 10000`); root/shell readers
  see the real state by design, matching the reference implementation.
