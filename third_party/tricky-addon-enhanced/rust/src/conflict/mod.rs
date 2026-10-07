use std::path::Path;
use std::process::Command;
use serde::Serialize;
use crate::config::Config;
use crate::cli::ConflictAction;

const MODULES_DIR: &str = "/data/adb/modules";

const REGULAR_MODULES: &[&str] = &[
    "Yurikey", "xiaocaiye", "safetynet-fix", "vbmeta-fixer",
    "playintegrity", "integrity_box", "SukiSU_module", "Reset_BootHash",
    "Tricky_store-bm", "Hide_Bootloader", "ShamikoManager",
    "extreme_hide_root", "Tricky_Store-xiaoyi", "tricky_store_assistant",
    "extreme_hide_bootloader", "wjw_hiderootauxiliarymod",
    "PlayIntegrityFork",
];

const AGGRESSIVE_MODULES: &[&str] = &["Yamabukiko", "TA_utl"];

const CONFLICT_APPS: &[&str] = &[
    "com.lingqian.appbl",
    "com.topmiaohan.hidebllist",
];

#[derive(Debug, Serialize)]
pub struct ConflictStatus {
    pub aggressive_conflicts: Vec<String>,
    pub regular_conflicts: Vec<String>,
    pub app_conflicts: Vec<String>,
    pub boot_manager: String,
}

pub fn handle_conflict(action: ConflictAction, cfg: &Config) -> anyhow::Result<()> {
    if !cfg.conflict.enabled {
        println!("conflict checking disabled");
        return Ok(());
    }

    match action {
        ConflictAction::Check { install } => {
            let status = check_all(cfg.conflict.auto_remove)?;
            if install && !status.aggressive_conflicts.is_empty() {
                let names = status.aggressive_conflicts.join(", ");
                anyhow::bail!("aggressive conflict modules found: {names}");
            }
            println!("{}", serde_json::to_string_pretty(&status)?);
            Ok(())
        }
        ConflictAction::Status => {
            let status = check_all(false)?;
            println!("{}", serde_json::to_string_pretty(&status)?);
            Ok(())
        }
    }
}

pub fn check_all(auto_remove: bool) -> anyhow::Result<ConflictStatus> {
    let (aggressive, regular) = check_modules(auto_remove)?;
    let apps = check_apps();
    let boot_manager = detect_boot_manager().to_string();

    Ok(ConflictStatus {
        aggressive_conflicts: aggressive,
        regular_conflicts: regular,
        app_conflicts: apps,
        boot_manager,
    })
}

fn check_modules(auto_remove: bool) -> anyhow::Result<(Vec<String>, Vec<String>)> {
    let modules_dir = Path::new(MODULES_DIR);
    if !modules_dir.is_dir() {
        return Ok((Vec::new(), Vec::new()));
    }

    let mut aggressive = Vec::new();
    let mut regular = Vec::new();

    for &id in AGGRESSIVE_MODULES {
        let dir = modules_dir.join(id);
        if dir.is_dir() && !dir.join("remove").exists() {
            aggressive.push(id.to_string());
            log_to_conflict_file(&format!("AGGRESSIVE: {id}"));
        }
    }

    for &id in REGULAR_MODULES {
        let dir = modules_dir.join(id);
        if dir.is_dir() && !dir.join("remove").exists() {
            regular.push(id.to_string());
            log_to_conflict_file(&format!("REGULAR: {id}"));
            if auto_remove {
                let _ = tag_module_for_removal(id);
            }
        }
    }

    Ok((aggressive, regular))
}

fn check_apps() -> Vec<String> {
    let mut found = Vec::new();
    for &pkg in CONFLICT_APPS {
        if is_app_installed(pkg) {
            found.push(pkg.to_string());
            log_to_conflict_file(&format!("APP: {pkg}"));
        }
    }
    found
}

fn is_app_installed(pkg: &str) -> bool {
    Command::new("pm")
        .args(["path", pkg])
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn detect_boot_manager() -> &'static str {
    if Path::new("/data/adb/modules/.TA_enhanced").is_dir() {
        "OverlayFS"
    } else {
        "MagicMount"
    }
}

fn tag_module_for_removal(module_id: &str) -> anyhow::Result<()> {
    let dir = Path::new(MODULES_DIR).join(module_id);
    std::fs::write(dir.join("disable"), "")?;
    std::fs::write(dir.join("remove"), "")?;
    tracing::info!("tagged {module_id} for removal");
    Ok(())
}

fn log_to_conflict_file(msg: &str) {
    let log_path = Path::new("/data/adb/tricky_store/ta-enhanced/logs/conflict.log");
    if let Some(parent) = log_path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if log_path.metadata().map(|m| m.len() > 1_048_576).unwrap_or(false) {
        let _ = std::fs::write(log_path, "");
    }
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let entry = format!("[{ts}] {msg}\n");
    let _ = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .and_then(|mut f| {
            use std::io::Write;
            f.write_all(entry.as_bytes())
        });
}
