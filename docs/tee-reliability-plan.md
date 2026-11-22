# TEE integration reliability

Scope: conservative observation, authenticated updates, user ownership, serialized
file replacement and explicit rollback limits. No release until validation.

1. Use one tri-state injection probe in the manager, watchdog and diagnostics.
2. Require a validator, complete successful validation, and HTTPS for every feed.
3. Preserve custom files regardless of health; unknown ownership is never automatic.
4. Serialize update and mirror writes, retain backups and report file-level outcomes.
5. Test observation failures, invalid candidates, fallback, ownership, lock contention
   and interruption recovery; build the manager.
6. Assess upstream 1.3.0 artifacts and integration compatibility. Add read-only OnePlus
   service diagnostics with explicit unknown states and recovery guidance.

There is no configured HTTPS feed (confirmed by the user). HTTP automatic replacement
will be blocked with an explicit diagnostic. No private keys, hardware secure storage,
RPMB provisioning, SOTER enrollment or generated crypto configuration are reset.
File rollback cannot restore application keys retired by the upstream engine.

## Release decision (2026-11-22)

The user asked to ship this branch as 31450. Adopted the read-only vendor
diagnostics from the smaller carrier branch verbatim; kept this branch's
OMY 1.3.0 payload and serialized HTTPS-only feed. Automatic keybox replacement
stays off because no HTTPS feed is configured, so it reports
blocked-no-https-source instead of downloading anything.

Still unverified: no physical-device run of the new keystore2 injection path.
The built-in box expires 2026-11-25; a follow-up build with a fresh keybox is
required as soon as upstream rotates.
