# SELinux / AVC audit (31452)

## Sample and evidence

The inspected APK is `com.kaixin.ksu` (`kaixinsu`), version code 32715,
version name `2026/11/21/6:00`.
SHA-256: `ad5fd5052ef5956b440e5b9eb4408b343cd67f08fe02b1998b368b246e9f5af8`.
Analysis was static: Java/JNI, the embedded daemon, and eight recovered LKM
images. The APK was not executed.

| Surface | Recovered implementation | XECKernel source comparison |
| --- | --- | --- |
| Context conversion | A global one-entry rejected-context cache, up to 128 bytes; a hit returns `-EINVAL` before parsing | The current parser has no rejected-context cache. Existing regressions exercise single/dual parsing and policy reloads. |
| Policy conversion | Parse the backup policy, then `sync_context_sids` looks up the live user/role/type, MLS and SID tables | The current code has a single-parse path with generation validation and a dual-parse fallback. |
| Audit rules | Wildcard `dontaudit` from `untrusted_app_all`, `isolated_app_all`, `sdk_sandbox_all` to `domain`, `selinuxfs`, `ksu_file`, `adb_data_file`, `binderfs_logs` | Ported all 15 combinations, mapping `ksu_file` to `KERNEL_SU_FILE` (`xecpro_file`). Missing policy symbols are skipped. |
| Boot logs | Clear dmesg and matching Android buffers at boot completion, then again after 35 seconds | The preceding release already contains this userspace mechanism. This audit additionally covers the persistent log copies. |
| Manager status | `getenforce`; permission denial can be displayed as Enforcing | Status now requires a successful command and one exact recognized value. |
| Diagnostic export | Temporarily set global `kptr_restrict=1`, collect kallsyms, then restore | Export now reads under the existing policy without writing this sysctl. |

The cache has different cold/hit instruction paths and shared state. That is
static evidence of a possible timing channel, not a measured device latency
distribution. No timing-detector threshold was recovered from this manager.

## Confirmed fixes

- The app audit rules are applied to the unpublished policy clone. Allocation
  failure discards that clone before registering its type metadata. The rules
  clear auditdeny masks; they do not grant access or change enforcement. Later
  explicit module policy updates retain their existing override semantics.
- SELinux queries keep the shared root shell open, return Unknown without root,
  and bound command waits to five seconds. `setenforce` still requires a
  successful readback. Denied probes no longer claim Enforcing.
- Boot-log directories and files use owner-only access (0700/0600). Directory
  traversal rejects symbolic links; existing logs must be singly linked regular
  files owned by the collector. Rotation is committed after the collector
  starts; repeated start failures preserve both existing generations. A failed
  publication stops and reaps the provisional collector.
- Diagnostic archives use a unique app-created file, mode 0600 and the app UID.
  The app checks readability and nonzero length before sharing through its
  FileProvider.
- Kallsyms collection no longer temporarily relaxes a global pointer policy.

## Verification boundaries

The focused manager tests cover shared-shell lifetime, absence of an app-UID
probe, failed queries and exact status parsing. The Linux filesystem tests
compile the production log implementation directly; they cover umask-zero
creation, existing-log permissions, rotation, repeated collector failures,
links and FIFOs. Kernel host
tests cover parsing semantics and reload generations, not LSM execution or
device timing.
The audit selector tests cover the 15 source/target combinations, missing
symbols and failure propagation. Their mocked policy backend does not prove
kernel policy loading; the auditdeny mask behavior was also checked directly
in the existing `add_rule_raw` implementation.

Reproduce the focused checks with:

```sh
python3 scripts/test_kernel_regressions.py
cargo test --manifest-path tests/private-bootlog/Cargo.toml --locked
cd manager
./gradlew :app:testDebugUnitTest --tests me.weishu.kernelsu.ui.util.SELinuxCheckerTest
```

No Android device was attached during this audit. Device timing, boot behavior,
SELinux labels and FileProvider export require an on-device check; build and
host-test success do not establish that a detector's device checks pass.
