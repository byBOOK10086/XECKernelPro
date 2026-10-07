use std::path::Path;

use anyhow::Result;
use resetprop::PropSystem;
use tracing::{debug, info, warn};

use crate::config::Config;
use crate::platform::props::{getprop, set, delete, resetprop_wait};

const BOOT_PROPS: &[(&str, &str)] = &[
    ("ro.boot.vbmeta.device_state", "locked"),
    ("ro.boot.verifiedbootstate", "green"),
    ("ro.boot.flash.locked", "1"),
    ("ro.boot.veritymode", "enforcing"),
    ("ro.boot.warranty_bit", "0"),
    ("ro.warranty_bit", "0"),
    ("ro.debuggable", "0"),
    ("ro.force.debuggable", "0"),
    ("ro.secure", "1"),
    ("ro.adb.secure", "1"),
    ("ro.build.type", "user"),
    ("ro.build.tags", "release-keys"),
    ("ro.vendor.boot.warranty_bit", "0"),
    ("ro.vendor.warranty_bit", "0"),
    ("vendor.boot.vbmeta.device_state", "locked"),
    ("vendor.boot.verifiedbootstate", "green"),
    ("sys.oem_unlock_allowed", "0"),
    ("ro.secureboot.lockstate", "locked"),
    ("ro.boot.realmebootstate", "green"),
    ("ro.boot.realme.lockstate", "1"),
    ("ro.crypto.state", "encrypted"),
    ("ro.is_ever_orange", "0"),
];

const VBMETA_PROPS: &[(&str, &str)] = &[
    ("ro.boot.vbmeta.device_state", "locked"),
    ("ro.boot.vbmeta.invalidate_on_error", "yes"),
    ("ro.boot.vbmeta.avb_version", "1.0"),
    ("ro.boot.vbmeta.hash_alg", "sha256"),
];

const RECOVERY_PROPS: &[&str] = &[
    "ro.bootmode",
    "ro.boot.bootmode",
    "ro.boot.mode",
    "vendor.bootmode",
    "vendor.boot.bootmode",
    "vendor.boot.mode",
];

const BOOT_HASH_FILE: &str = "/data/adb/boot_hash";

#[derive(Debug)]
pub struct SpoofResult {
    pub spoofed: u32,
    pub failed: u32,
    pub deleted: u32,
}

pub fn handle_props(cfg: &Config, sys: &PropSystem) -> Result<()> {
    if !cfg.props.enabled {
        info!("props spoofing disabled");
        return Ok(());
    }
    let result = spoof_all(cfg, sys)?;
    info!(
        "props: {} spoofed, {} failed, {} deleted",
        result.spoofed, result.failed, result.deleted
    );
    Ok(())
}

pub fn spoof_all(cfg: &Config, sys: &PropSystem) -> Result<SpoofResult> {
    let mut spoofed = 0u32;
    let mut failed = 0u32;
    let mut deleted = 0u32;

    if let Err(e) = resetprop_wait("sys.boot_completed", "0") {
        warn!("resetprop -w timeout (non-fatal): {e}");
    }

    let zm = zeromount_active();

    if !zm {
        for &(name, value) in BOOT_PROPS {
            match check_reset_prop(sys, name, value) {
                Ok(true) => spoofed += 1,
                Ok(false) => {}
                Err(_) => failed += 1,
            }
        }

        if delete_qemu_prop(sys) {
            deleted += 1;
        }
    }

    for &name in RECOVERY_PROPS {
        match contains_reset_prop(sys, name, "recovery", "unknown") {
            Ok(true) => spoofed += 1,
            Ok(false) => {}
            Err(_) => failed += 1,
        }
    }

    if !zm {
        if apply_vbmeta_digest(sys) {
            spoofed += 1;
        }

        for &(name, value) in VBMETA_PROPS {
            match ensure_prop(sys, name, value) {
                Ok(true) => spoofed += 1,
                Ok(false) => {}
                Err(_) => failed += 1,
            }
        }

        match apply_vbmeta_size(sys) {
            Ok(true) => spoofed += 1,
            Ok(false) => {}
            Err(_) => failed += 1,
        }
    }

    if let Err(e) = delete(sys, "ro.sys.sdcardfs") {
        debug!("ro.sys.sdcardfs delete skipped: {e}");
    } else {
        deleted += 1;
    }

    for prop in &cfg.props.custom_props {
        if prop.len() == 2 && !prop[0].is_empty() {
            match check_reset_prop(sys, &prop[0], &prop[1]) {
                Ok(true) => spoofed += 1,
                Ok(false) => {}
                Err(_) => failed += 1,
            }
        }
    }

    Ok(SpoofResult { spoofed, failed, deleted })
}

fn zeromount_active() -> bool {
    let dir = Path::new("/data/adb/modules/meta-zeromount");
    dir.is_dir() && !dir.join("disable").exists() && !dir.join("remove").exists()
}

fn check_reset_prop(sys: &PropSystem, name: &str, expected: &str) -> Result<bool> {
    let current = match getprop(sys, name) {
        Some(v) if v.is_empty() => return Ok(false),
        Some(v) => v,
        None => return Ok(false),
    };
    if current == expected {
        return Ok(false);
    }
    set(sys, name, expected)?;
    debug!("spoofed {name} = {expected}");
    Ok(true)
}

fn contains_reset_prop(sys: &PropSystem, name: &str, contains: &str, new_val: &str) -> Result<bool> {
    if let Some(current) = getprop(sys, name) {
        if current.contains(contains) {
            set(sys, name, new_val)?;
            debug!("replaced {name}: {current} -> {new_val}");
            return Ok(true);
        }
    }
    Ok(false)
}

fn ensure_prop(sys: &PropSystem, name: &str, value: &str) -> Result<bool> {
    let current = getprop(sys, name);
    if current.as_ref().map(|s| !s.is_empty()).unwrap_or(false) {
        return Ok(false);
    }
    set(sys, name, value)?;
    debug!("ensured {name} = {value}");
    Ok(true)
}

fn delete_qemu_prop(sys: &PropSystem) -> bool {
    if getprop(sys, "ro.kernel.qemu").is_some() {
        if delete(sys, "ro.kernel.qemu").is_ok() {
            debug!("deleted ro.kernel.qemu");
            return true;
        }
        if set(sys, "ro.kernel.qemu", "").is_ok() {
            debug!("blanked ro.kernel.qemu");
            return true;
        }
    }
    false
}

fn apply_vbmeta_digest(sys: &PropSystem) -> bool {
    let hash = match std::fs::read_to_string(BOOT_HASH_FILE) {
        Ok(h) => h.trim().to_string(),
        Err(_) => return false,
    };

    if hash.len() != 64 || !hash.chars().all(|c| c.is_ascii_hexdigit()) {
        warn!("invalid boot_hash, skipping vbmeta digest");
        return false;
    }

    match set(sys, "ro.boot.vbmeta.digest", &hash) {
        Ok(_) => {
            debug!("applied vbmeta digest");
            true
        }
        Err(e) => {
            warn!("failed to set vbmeta digest: {e}");
            false
        }
    }
}

fn apply_vbmeta_size(sys: &PropSystem) -> Result<bool> {
    let size = get_vbmeta_size(sys);
    ensure_prop(sys, "ro.boot.vbmeta.size", &size)
}

fn get_vbmeta_size(sys: &PropSystem) -> String {
    let slot = getprop(sys, "ro.boot.slot_suffix").unwrap_or_default();

    let candidates: Vec<String> = if slot.is_empty() {
        vec!["/dev/block/by-name/vbmeta".into()]
    } else {
        vec![
            format!("/dev/block/by-name/vbmeta{slot}"),
            "/dev/block/by-name/vbmeta".into(),
        ]
    };

    for dev in &candidates {
        if let Ok(output) = std::process::Command::new("blockdev")
            .args(["--getsize64", dev])
            .output()
        {
            if output.status.success() {
                let s = String::from_utf8_lossy(&output.stdout);
                let trimmed = s.trim();
                if !trimmed.is_empty() && trimmed != "0" {
                    debug!("vbmeta size from {dev}: {trimmed}");
                    return trimmed.to_string();
                }
            }
        }
    }

    debug!("vbmeta partition not found, using default 4096");
    "4096".to_string()
}
