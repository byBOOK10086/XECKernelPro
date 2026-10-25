pub mod bulletin;

use std::path::Path;

use anyhow::{Context, Result, bail};
use serde::Serialize;
use tracing::{info, warn};

use crate::cli::SecurityPatchAction;
use crate::config::Config;
use crate::platform::fs::atomic_write;
use crate::platform::props::{getprop_once as getprop, set_once as resetprop};

const TS_DIR: &str = "/data/adb/tricky_store";
const SECURITY_PATCH_FILE: &str = "/data/adb/tricky_store/security_patch.txt";
const DEVCONFIG_TOML: &str = "/data/adb/tricky_store/devconfig.toml";
const TS_MODULE_PROP: &str = "/data/adb/modules/tricky_store/module.prop";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum TrickyStoreVariant {
    James,
    Standard,
    Legacy,
}

impl std::fmt::Display for TrickyStoreVariant {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::James => write!(f, "James"),
            Self::Standard => write!(f, "Standard"),
            Self::Legacy => write!(f, "Legacy"),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct PatchDates {
    pub system: String,
    pub boot: String,
    pub vendor: String,
}

pub fn handle_security_patch(action: SecurityPatchAction, cfg: &Config) -> Result<()> {
    match action {
        SecurityPatchAction::Set => {
            set(cfg)?;
            println!("security patch dates applied");
            Ok(())
        }
        SecurityPatchAction::Update { force } => {
            if force {
                update_force(cfg)?;
            } else {
                update(cfg)?;
            }
            println!("security patch dates updated");
            Ok(())
        }
        SecurityPatchAction::Show => {
            let output = show_current()?;
            println!("{output}");
            Ok(())
        }
        SecurityPatchAction::SetCustom { system, boot, vendor } => {
            set_custom(&system, &boot, &vendor)?;
            println!("custom security patch dates applied");
            Ok(())
        }
    }
}

pub fn read_device_dates() -> PatchDates {
    PatchDates {
        system: get_system_patch_date().unwrap_or_default(),
        boot: get_boot_patch_date().unwrap_or_default(),
        vendor: get_vendor_patch_date().unwrap_or_default(),
    }
}

pub fn get_system_patch_date() -> Option<String> {
    getprop("ro.build.version.security_patch")
}

pub fn get_boot_patch_date() -> Option<String> {
    getprop("ro.bootimage.build.date.security_patch")
        .or_else(|| getprop("ro.vendor.build.security_patch"))
        .or_else(|| getprop("ro.build.version.security_patch"))
}

pub fn get_vendor_patch_date() -> Option<String> {
    getprop("ro.vendor.build.security_patch")
        .or_else(|| getprop("ro.build.version.security_patch"))
}

pub fn detect_variant() -> TrickyStoreVariant {
    let prop_content = std::fs::read_to_string(TS_MODULE_PROP).unwrap_or_default();

    if prop_content.contains("James") && !prop_content.contains("beakthoven") {
        return TrickyStoreVariant::James;
    }

    if prop_content.contains("TEESimulator") || prop_content.contains("beakthoven") {
        return TrickyStoreVariant::Standard;
    }

    if let Some(version) = extract_version_code(&prop_content) {
        if version >= 158 {
            return TrickyStoreVariant::Standard;
        }
    }

    TrickyStoreVariant::Legacy
}

pub fn set(config: &Config) -> Result<()> {
    let dates = if config.security_patch.custom_date.is_empty() {
        device_dates_for_patch()
    } else {
        PatchDates {
            system: config.security_patch.custom_date.clone(),
            boot: config.security_patch.custom_date.clone(),
            vendor: config.security_patch.custom_date.clone(),
        }
    };

    if dates.system.is_empty() {
        bail!("cannot read system security patch date");
    }

    let variant = detect_variant();
    info!("detected TrickyStore variant: {variant}");
    write_patch_dates(&variant, &dates)
}

pub fn set_custom(system: &str, boot: &str, vendor: &str) -> Result<()> {
    // "prop" 占位替换走与自动更新一致的格式（system=YYYYMM），避免同一文件里
    // 两种日期形态混用。
    let device = device_dates_for_patch();
    let dates = PatchDates {
        system: if system == "prop" { device.system } else { system.to_string() },
        boot: if boot == "prop" { device.boot } else { boot.to_string() },
        vendor: if vendor == "prop" { device.vendor } else { vendor.to_string() },
    };

    let variant = detect_variant();
    write_patch_dates(&variant, &dates)
}

pub fn update(config: &Config) -> Result<()> {
    if !config.security_patch.auto_update {
        info!("security patch auto-update is disabled");
        return Ok(());
    }
    update_force(config)
}

/// 证书 patch tag 必须与设备 OS 一致（春秋检测 Tampered Attestation Key(26) 的
/// 判据）。默认（follow_bulletin=false）写设备真实补丁级别，格式按社区验证过的
/// 方案：system=YYYYMM、boot/vendor=完整日期；只有显式打开 follow_bulletin 才
/// 去爬安卓公告的最新日期——那是与 OS 属性脱节的自找检测项。
pub fn update_force(config: &Config) -> Result<()> {
    let dates = if config.security_patch.follow_bulletin {
        let latest = bulletin::fetch_latest_patch()?;
        PatchDates {
            system: latest.clone(),
            boot: latest.clone(),
            vendor: latest,
        }
    } else {
        device_dates_for_patch()
    };

    let variant = detect_variant();
    info!("updating security patch to {} (variant: {variant})", dates.system);
    write_patch_dates(&variant, &dates)
}

/// 设备真实补丁级别，system 压成 YYYYMM（"2023-12-01" → "202312"），
/// boot/vendor 保持完整日期。任一属性读不到就留空，由调用方判空报错。
pub fn device_dates_for_patch() -> PatchDates {
    let system_full = get_system_patch_date().unwrap_or_default();
    let system = if system_full.is_empty() {
        String::new()
    } else {
        system_full
            .split('-')
            .take(2)
            .collect::<Vec<_>>()
            .join("")
            .trim()
            .to_string()
    };
    PatchDates {
        system,
        boot: get_boot_patch_date().unwrap_or_default(),
        vendor: get_vendor_patch_date().unwrap_or_default(),
    }
}

pub fn show_current() -> Result<String> {
    let variant = detect_variant();
    let dates = read_device_dates();
    let mut output = String::new();

    output.push_str(&format!("variant: {variant}\n"));
    output.push_str(&format!("system: {}\n", dates.system));
    output.push_str(&format!("boot: {}\n", dates.boot));
    output.push_str(&format!("vendor: {}\n", dates.vendor));

    match variant {
        TrickyStoreVariant::James => {
            let path = Path::new(DEVCONFIG_TOML);
            if path.exists() {
                let content = std::fs::read_to_string(path)?;
                if let Some(line) = content.lines().find(|l| l.starts_with("securityPatch")) {
                    output.push_str(&format!("config: {line}\n"));
                }
            }
            output.push_str(&format!("config_file: {DEVCONFIG_TOML}\n"));
        }
        TrickyStoreVariant::Standard => {
            let path = Path::new(SECURITY_PATCH_FILE);
            if path.exists() {
                let content = std::fs::read_to_string(path)?;
                output.push_str(&format!("config_content:\n{content}"));
            }
            output.push_str(&format!("config_file: {SECURITY_PATCH_FILE}\n"));
        }
        TrickyStoreVariant::Legacy => {
            output.push_str("config_file: (resetprop only)\n");
        }
    }

    Ok(output)
}

fn write_patch_dates(variant: &TrickyStoreVariant, dates: &PatchDates) -> Result<()> {
    crate::platform::fs::ensure_dir(Path::new(TS_DIR))?;

    match variant {
        TrickyStoreVariant::James => write_james(dates),
        TrickyStoreVariant::Standard => write_standard(dates),
        TrickyStoreVariant::Legacy => write_legacy(dates),
    }
}

fn write_james(dates: &PatchDates) -> Result<()> {
    let path = Path::new(DEVCONFIG_TOML);
    let content = if path.exists() {
        std::fs::read_to_string(path)?
    } else {
        String::new()
    };

    let new_line = format!("securityPatch = \"{}\"", dates.system);

    let updated = if content.contains("securityPatch") {
        content.lines()
            .map(|line| {
                if line.trim_start().starts_with("securityPatch") {
                    new_line.as_str()
                } else {
                    line
                }
            })
            .collect::<Vec<_>>()
            .join("\n")
    } else if content.contains("[deviceProps]") {
        content.lines()
            .flat_map(|line| {
                if line.trim() == "[deviceProps]" {
                    vec![line, &new_line as &str]
                } else {
                    vec![line]
                }
            })
            .collect::<Vec<_>>()
            .join("\n")
    } else {
        if content.is_empty() {
            new_line
        } else {
            format!("{content}\n{new_line}")
        }
    };

    atomic_write(path, updated.as_bytes())
        .context("failed to write devconfig.toml")?;
    info!("wrote security patch to devconfig.toml: {}", dates.system);
    Ok(())
}

fn write_standard(dates: &PatchDates) -> Result<()> {
    let content = format!(
        "system={}\nboot={}\nvendor={}\n",
        dates.system, dates.boot, dates.vendor
    );
    atomic_write(Path::new(SECURITY_PATCH_FILE), content.as_bytes())
        .context("failed to write security_patch.txt")?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(
            SECURITY_PATCH_FILE,
            std::fs::Permissions::from_mode(0o644),
        );
    }

    info!("wrote security patch to security_patch.txt");
    Ok(())
}

fn write_legacy(dates: &PatchDates) -> Result<()> {
    if let Err(e) = resetprop("ro.build.version.security_patch", &dates.system) {
        warn!("resetprop system patch failed: {e}");
    }
    if !dates.vendor.is_empty() {
        if let Err(e) = resetprop("ro.vendor.build.security_patch", &dates.vendor) {
            warn!("resetprop vendor patch failed: {e}");
        }
    }
    info!("applied security patch via resetprop");
    Ok(())
}

fn extract_version_code(prop_content: &str) -> Option<u32> {
    prop_content.lines()
        .find(|l| l.starts_with("versionCode="))
        .and_then(|l| l.strip_prefix("versionCode="))
        .and_then(|v| v.trim().parse().ok())
}
