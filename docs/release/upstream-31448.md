# Upstream synchronization: 31448

Base: local 31447 cd22e198; upstream KernelSU through ff70c90d.
Changes are ported onto the XEC tree rather than replacing its product integration.

| Commit | Subject | Disposition |
| --- | --- | --- |
| `96770dfb` | Handle execveat selinux_setup exec on Android 17 QPR3 (#3911) | Already present in 31447 base. |
| `e9ef3540` | fix func change in 6.19+ (#3917) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `9f429f6b` | templates: add sdmaidse.root (#3914) | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `0f58f12c` | su_mount_ns: move ksys_unshare to ksyscall (#3916) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `6b736083` | manager: decode params.json with plain kotlinx.serialization (#3919) | Not applicable: removed one-shot script UI is absent in XEC. |
| `605e4269` | manager: remove block kernel modules UI, make it developer only (#3920) | User-facing block-module UI already absent; no new behavior needed. |
| `b609757f` | manager: fix predictive back in dialog-hosted m3 components (#3923) | Not applicable: Material dialog components are absent; XEC uses Miuix. |
| `469c4282` | kernel: Fix init.rc detection for bind-mounted files (#3924) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `46636100` | manager: remove DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (#3927) | Retained AndroidX receiver permission for backward compatibility; no security permission removal solely for fingerprint changes. |
| `6d31a613` | build(deps): bump the npm group across 1 directory with 14 updates (#3932) | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `3352209f` | userspace: Update waitsys to fix crash | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `528bafc5` | kernel: remove useless code (#3945) | Retained status_lock to preserve XEC status-page lifetime handling. |
| `75e179d3` | update ads.txt | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `10efe664` | Fix ads | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `db6263d3` | fix(kernel): align SELinux hide policy parsing (#3936) | Already present in 31447 base; retained with later single-parse changes. |
| `bb5f9faa` | manager: add File interface to WebUI (#3732) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `ab05a062` | fix: add new pm interface (#3955) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `7bab12df` | js: auto publish npm (#3956) | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `446c2920` | website: add llms.txt for AI agents | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `51c21786` | ci: pin website deploy to Node LTS | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `2fe18831` | kernel: Balance file references when reopening wrapped files (#3958) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `b1fce49e` | kernel: Remove noisy logs from critical and frequent paths (#3960) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `bec9c134` | kernel: use vmlinux.btf query to workaround 6.6.144's ABI changes (#3962) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `28db74f6` | kernel: Fix SELinux generation tracking for policy updates (#3964) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `019a3c01` | refactor(kernel): Mark original type datum to save symtab search (#3969) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `730f6446` | manager: accept x-zip MIME types when opening module zips (#3929) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `ca346659` | fix(kernel): parse setprocattr context once in selinux hide (#3981) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `9ae6e65a` | fix(kernel): Fix arm64 syscall first param register on 7.3+ (#3983) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `c6f773ce` | docs: clarify current KernelSU installation paths (#3642) | Excluded: upstream website, advertising, or publishing infrastructure; not used by XEC distribution. |
| `c680b548` | kernel: fix x64 preinit runner ELF alignment for non-4K page sizes (#3933) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `2e661cdd` | manager: Handle external URL launch failures | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `dd1a0c0d` | kernel: x86_64: don't run syscalls skipped by seccomp in my_do_syscall_64 (#3966) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `02ffbd44` | kernel: x86_64: fall back to patching do_syscall_64 when x64_sys_call is missing (#3963) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `a23184a0` | manager: KsuCli: prevent variables from escaping to a root shell (#3987) | Adapted: existing shQuote preserved; added missing module-action ID validation. |
| `dbb73665` | fix(ksud): Prevent path traversal caused by profile name | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `f5643b0a` | fix(ksud): Fix clippy warnings | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `098be426` | fix(ksud): Fix even more clippy warnings | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `64833c00` | feat(kernel): Add LSM hook for inode_setxattr (#3996) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `f49be695` | kernel: Use the first match for exact symbol lookup (#3999) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `f4603d14` | manager: enhance module flashing screen (#3998) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `318d202d` | manager: update Brazilian Portuguese (pt-BR) translation (#4007) | Ported; adapted where required for XEC identity, UI, and lifecycle. |
| `ff70c90d` | manager: Update Spanish translations (#4006) | Ported; adapted where required for XEC identity, UI, and lifecycle. |

## Bundled components

- HMA-OSS oss-177, source 0f4618be83c034c3a4935f4c33825c948ce171b3; release ZIP SHA-256 fad6d3224398f09ad2fbbd52ee5328b9b38fc6ad65d751cb6a8e8acfec1fd770.
- SUSFS LKM v2.3.0-r18.9-1, source 8619b6ee0d0629836e9c6ce668e8a785dfd53517. Experimental main hook changes excluded.
- ReZygisk v1.0.0, OhMyKeymint 1.2.6-9069c6c and Tricky Addon v5.53.1: stable versions unchanged.
- Existing installed HMA/ReZygisk modules retain install-once ownership semantics; bundling a new payload does not overwrite user-managed modules.
- Separate built-in SUSFS GKI workflow pins are not part of this LKM release update.

## Validation

- Local manager unit tests and assembleRelease passed. CI supplies the complete, signed distribution payload.
- Kernel regression harness includes original context conversion cases and new single-parse/stale-generation/retry cases.
- Android Rust check/clippy/fmt runs in Linux CI: Windows host lacks the MSVC linker.
- No physical device connected. Build results do not establish on-device timing, flashing, or universal detection resistance.
