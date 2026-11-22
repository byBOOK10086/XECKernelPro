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
