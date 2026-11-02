# Native boot protection

This change is developed on `fix/rescue-integration-31261`, based on the published 31261 revision. It does not change the 31261 release tag.

## Scope

Protection starts when native ksud can execute with `/data` available. It cannot recover a bootloader/kernel/preinit failure that prevents that point from being reached. Device-supported A/B fallback or external fastboot/recovery remains necessary for those failures. Android hardware fault injection has not been performed.

The native guard tracks distinct boot IDs, pending startup, consecutive unconfirmed starts, last stage and a finite automatic reboot budget. Two unconfirmed starts activate persistent rescue mode. Rescue skips user KPM loading, common scripts, metamodule scripts, ordinary module scripts/mounts/properties and built-in TEE/TA startup without rewriting their user enable configuration. The guard detects old lite_modules conflicts and reports them; it does not merge directories or delete application package restrictions.

A detached, exclusively locked observer uses CLOCK_BOOTTIME. Ordinary startup has a 300-second deadline; a pending OTA has 1800 seconds. Completion requires pipeline readiness and sys.boot_completed=1 continuously observed for 60 seconds. State is atomically replaced and synced. Corrupt state activates explicit rescue. Automatic reboot is limited to two until explicit resume or normal successful startup resets the budget.

## Manager and CLI

Tools contains Boot protection status and Restore normal startup. Root-only commands are `xudc boot-guard status` and `xudc boot-guard resume`. Resume clears rescue/failure counters for the next reboot; it does not replay skipped stages into a live system. A persistent legacy conflict can require resolution before normal startup is allowed.

The old Automatic_brick_rescue module is disabled when detected in live/update module directories. Hidepack no longer installs 9.zip. Existing files are retained for diagnosis.

## Flash transaction

Daemon direct-flash paths create verified backups and arm a transaction. External fastboot flashing of an exported image does not establish this transaction automatically. Every backup is bounded and verified before any restore write; each restored partition is read back. Verified restore remains durable until a later boot confirms it. The guard stops later stages and owns bounded reboot requests after rollback.

Observation and commit validate the actual booted slot. OTA activation uses separate checked bootctl arguments. Premature post-fs-data mark-boot-successful is removed; the helper remains until the stable success observer commits.

## Verification

The integration workflow performs Android arm64 cargo-ndk check/clippy, formatting checks, standalone guard policy tests, host-executed transaction state/parser tests with partition-write stubs that panic if invoked, and the existing full signed manager artifact build. A successful workflow establishes build/policy evidence, not hardware recovery certification. See Actions for the exact tested commit and outcome.
