# GhostLock (vendored)

- **Project:** GhostLock — rtmutex stack UAF (CVE-2026-43499) local privilege
  escalation payload used to late-load the KernelSU kernel module on GKI
  devices without flashing boot.
- **Upstream:** https://github.com/YuKongA/ghostlock-app
- **Vendored revision:** `4ebc726b9001d79f3c374c917d51569e310df6bc` (main,
  2026-11-08, 24 commits ahead of the previous pin `67215cfe`)
- **License:** Apache License 2.0 — see `LICENSE`. Original work by the
  GhostLock authors (YuKongA / Nebu Security). All vendored files keep their
  original headers.
- **What is vendored here:**
  - `src/` — the native attack payload sources (built by CI into a PIE
    executable, shipped in the manager APK as
    `jniLibs/arm64-v8a/libghostlock.so`).
  - `../../manager/app/src/main/assets/kernel_profiles/` — the bundled device
    kernel profile HOCON assets (`index.conf` + per-release `.conf`).
  - `../../manager/app/src/main/java/com/ghostlock/` — the Kotlin profile
    pipeline (GLK1 v2 writer, resolver, merger, builtin catalog), copied from
    `profile-core/` and the app's `data/` + `domain/` packages, unchanged.

## What the 2026-11-08 update brought (67215cfe → 4ebc726b90)

- `src/core/session/runtime_config.{h,cpp}` — new `ksud_path`, taken from the
  `GHOSTLOCK_KSUD` environment variable and shell-escaped before being embedded
  in the root script. This replaces "guess the manager by `find /data/app`" as
  the primary route.
- `src/core/attack/ops.cpp` — root script takes `KSUD='%s'` from that value;
  manager discovery gained `com.sukisu.ultra*` and `org.bakasu.bakasu-*`;
  upstream **dropped** `--allow-shell` from its `ksud late-load` call.
- 10 new kernel profiles + a refreshed `kernel_profiles/index.conf`.

## Local patches on top of upstream (marked `XEC:` in comments)

1. `src/core/attack/ops.cpp` — manager discovery: `com.xecpro.kernel*` ships the
   daemon as `libxeccli.so`; the `/data/app` find list is extended for it, and
   this fork's stealth names (`/data/adb/ksu/bin/xudc`, `/data/adb/xudc`) stay as
   fallbacks.
2. `src/core/attack/ops.cpp` — `ksud late-load` keeps `--allow-shell` **with a
   fallback**: this fork's `xudc late-load` needs `allow_shell=1` so the freshly
   loaded module grants the shell UID, while other KernelSU-family daemons reject
   the flag. The script tries with the flag first and repeats the call without it.
3. `manager/.../privilege/GhostLockRunner.kt` — exports `GHOSTLOCK_KSUD` pointing
   at the staged `workDir/ksud` copy, so the script never has to guess.

## Not vendored

`CVE-2026-43284` (a second, unverified bug) exists only on upstream's dev branch
and is deliberately **not** vendored; everything here comes from `main`.

Thanks to YuKongA and the GhostLock contributors for releasing the payload
and profile toolchain under a permissive license.
