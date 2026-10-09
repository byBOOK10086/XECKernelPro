//! Boot-state consistency pass (runs without any module installed).
//!
//! The device really is unlocked: `ro.boot.verifiedbootstate=orange`,
//! `ro.boot.flash.locked=0`, `ro.boot.vbmeta.device_state=unlocked` and at least
//! one `partition.*.verified` disabled. Property-based integrity checks read
//! exactly those names *and* the coherence rules that tie them together — a green
//! colour with an unlocked flag, or a green colour with a disabled partition, is
//! reported as a contradiction of its own — so this pass rewrites the property
//! view to what a locked user build reports and keeps every rule of that set
//! self-consistent.
//!
//! Mechanism: `resetprop` with `skip_svc`, i.e. the write goes straight into the
//! bionic property area that every read path resolves through, so the four read
//! paths of a property (reflection, `getprop`, native libc, `System.getProperty`)
//! stay in agreement. Hooking a single read path is what creates a visible
//! contradiction, so nothing here hooks anything.
//!
//! The pass runs on every boot before zygote starts, so `android.os.Build` and
//! the properties never disagree, and it is idempotent. The raw boot parameters
//! (`/proc/cmdline`, `/proc/bootconfig`) are produced by the kernel and are not
//! touched here; [`harden_readability`] only removes the untrusted-app read
//! permission for the cmdline where the vendor policy grants it.

use crate::defs;
use log::{info, warn};
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use std::fs;

/// The property view of a locked user build.
const LOCKED_PROPS: &[(&str, &str)] = &[
    ("ro.boot.verifiedbootstate", "green"),
    ("ro.boot.flash.locked", "1"),
    ("ro.boot.vbmeta.device_state", "locked"),
    ("ro.boot.veritymode", "enforcing"),
    ("ro.boot.secureboot", "1"),
    ("ro.boot.vbmeta.invalidate_on_error", "yes"),
    ("partition.system.verified", "1"),
    ("partition.vendor.verified", "1"),
    ("partition.product.verified", "1"),
    ("partition.system_ext.verified", "1"),
    ("partition.odm.verified", "1"),
    ("ro.oem_unlock_supported", "0"),
    ("service.adb.root", "0"),
    ("ro.debuggable", "0"),
    ("ro.secure", "1"),
    ("ro.adb.secure", "1"),
];

/// Android 16 (API 36) stopped publishing this property, so any value found there
/// is reported as written by something other than AOSP; on older releases the
/// check only looks at `1`/`true`, which is why removing it is safe on both: an
/// absent property leaves the rule unevaluated instead of firing.
const OEM_UNLOCK_PROP: &str = "sys.oem_unlock_allowed";

/// Best effort: `deny` removes an existing allow rule, so a policy that never
/// granted untrusted apps access to `/proc/cmdline` simply has nothing to remove.
const CMDLINE_DENY: &[&str] = &[
    "deny appdomain proc_cmdline file read",
    "deny untrusted_app proc_cmdline file read",
    "deny untrusted_app_all proc_cmdline file read",
];

const fn resetprop() -> ResetProp {
    ResetProp {
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    }
}

/// On unless the flag file says otherwise, so a fresh install looks locked from
/// the first boot; the file is also the switch the manager UI writes.
fn enabled() -> bool {
    match fs::read_to_string(defs::BOOTSTATE_FLAG_PATH) {
        Ok(value) => !matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "0" | "off" | "false" | "no" | "disable" | "disabled"
        ),
        Err(_) => true,
    }
}

/// Rewrite the boot-state properties to the locked view. Never fails the boot:
/// every step logs and continues, because a partially applied pass is still
/// better than none and the pass runs again on the next boot.
pub fn apply() {
    if !enabled() {
        info!("bootstate: disabled by {}", defs::BOOTSTATE_FLAG_PATH);
        return;
    }

    if let Err(e) = sys_prop::init() {
        warn!("bootstate: property API init failed: {e:#}");
        return;
    }

    let rp = resetprop();

    for (name, value) in LOCKED_PROPS {
        if let Err(e) = rp.set(name, value) {
            warn!("bootstate: set {name}={value} failed: {e:#}");
        }
    }

    match rp.delete(OEM_UNLOCK_PROP) {
        Ok(true) => info!("bootstate: removed {OEM_UNLOCK_PROP}"),
        Ok(false) => {}
        Err(e) => warn!("bootstate: delete {OEM_UNLOCK_PROP} failed: {e:#}"),
    }

    info!(
        "bootstate: locked-view properties applied ({} properties)",
        LOCKED_PROPS.len()
    );
}

/// Remove the untrusted-app read access to the raw boot parameters when the
/// loaded policy has one. The property pass above and this rule are two halves of
/// the same statement: the runtime value is rewritten, so the raw value must not
/// stay readable for a comparison.
pub fn harden_readability() {
    if !enabled() {
        return;
    }

    for rule in CMDLINE_DENY {
        match crate::sepolicy::live_patch(rule) {
            Ok(()) => info!("bootstate: applied `{rule}`"),
            Err(e) => warn!("bootstate: `{rule}` not applied: {e:#}"),
        }
    }
}
