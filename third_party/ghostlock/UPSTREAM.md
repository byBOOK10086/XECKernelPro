# GhostLock (vendored)

- **Project:** GhostLock — rtmutex stack UAF (CVE-2026-43499) local privilege
  escalation payload used to late-load the KernelSU kernel module on GKI
  devices without flashing boot.
- **Upstream:** https://github.com/YuKongA/ghostlock-app
- **Vendored revision:** `67215cfe204940fe71ff67213e76329e1adce7d2` (main)
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
- **Local patches on top of upstream (marked `XEC:` in comments):**
  - `src/core/attack/ops.cpp` — ksud discovery: added our own manager package
    (`com.xecpro.kernel*`, payload `libxeccli.so`) to the `/data/app` find
    list, and the `xudc` daemon paths (`/data/adb/ksu/bin/xudc`,
    `/data/adb/xudc`) used by this fork's stealth renames.
- **CVE-2026-43284** (a second, unverified bug) exists only on upstream's dev
  branch and was deliberately **not** vendored; everything here comes from
  `main` at the pinned revision.

Thanks to YuKongA and the GhostLock contributors for releasing the payload
and profile toolchain under a permissive license.
