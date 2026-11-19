//! One-time installation of the optional release ZIPs embedded in xudc.

use anyhow::{Context, Result, ensure};
use log::{info, warn};
use std::{fs, io::Write, path::Path};

use crate::{defs, module, utils};

const REZYGISK: &str = "rezygisk";
const HMA: &str = "hma_oss_zygisk";
const REZYGISK_ZIP: &[u8] = include_bytes!("../bundled-modules/rezygisk.zip");
const HMA_ZIP: &[u8] = include_bytes!("../bundled-modules/hma_oss_zygisk.zip");
const KNOWN_OTHER_LOADERS: &[&str] = &[
    "zygisksu",
    "zygisknext",
    "admirepowered",
    "zygisk_on_ksu",
    "yukizygisk",
    "onyxzygisk",
];

fn missing(path: &Path) -> Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(false),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(true),
        Err(error) => Err(error).with_context(|| format!("inspect {}", path.display())),
    }
}

fn has_other_loader() -> Result<bool> {
    for root in [defs::MODULE_DIR, defs::MODULE_UPDATE_DIR] {
        let entries = match fs::read_dir(root) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        for entry in entries {
            let entry = entry?;
            if entry.file_name() == REZYGISK || !entry.path().is_dir() {
                continue;
            }
            if KNOWN_OTHER_LOADERS
                .iter()
                .any(|id| entry.file_name() == *id)
                || ["zygiskd", "zygiskd64", "zygiskd32"]
                    .iter()
                    .any(|file| entry.path().join("bin").join(file).is_file())
            {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

fn rezygisk_available() -> bool {
    let active = Path::new(defs::MODULE_DIR).join(REZYGISK);
    if active.join("disable").exists() || active.join("remove").exists() {
        return false;
    }
    let staged = Path::new(defs::MODULE_UPDATE_DIR).join(REZYGISK);
    [staged, active].iter().any(|tree| {
        tree.join("module.prop").is_file()
            && !tree.join("disable").exists()
            && !tree.join("remove").exists()
            && ["zygiskd", "zygiskd64"]
                .iter()
                .any(|file| tree.join("bin").join(file).is_file())
    })
}

fn stage_once(dir: &Path, id: &str, zip_data: &[u8]) -> Result<()> {
    let stamp = dir.join(id);
    if !missing(&stamp)? {
        return Ok(());
    }
    if missing(&Path::new(defs::MODULE_DIR).join(id))?
        && missing(&Path::new(defs::MODULE_UPDATE_DIR).join(id))?
    {
        let mut zip = tempfile::Builder::new()
            .prefix(id)
            .suffix(".zip")
            .tempfile_in(dir)
            .context("create temporary bundled module ZIP")?;
        zip.write_all(zip_data)
            .context("write bundled module ZIP")?;
        let path = zip.path().to_str().context("invalid module ZIP path")?;
        module::install_bundled_module(path, id)?;
        info!("KMI bundle: processed {id}");
    }
    // Keep the marker after uninstall, so a removed module is not resurrected.
    let mut marker = tempfile::NamedTempFile::new_in(dir)?;
    marker.write_all(b"1\n")?;
    marker.as_file().sync_all()?;
    marker
        .persist(stamp)
        .context("record bundled module installation")?;
    Ok(())
}

fn provision() -> Result<()> {
    let dir = Path::new(defs::WORKING_DIR).join("kmi-modules");
    if !missing(&dir.join(REZYGISK))? && !missing(&dir.join(HMA))? {
        return Ok(());
    }
    let sdk = utils::getprop("ro.build.version.sdk")
        .and_then(|value| value.trim().parse::<u32>().ok())
        .unwrap_or_default();
    if sdk < 29 || has_other_loader()? {
        info!("KMI bundle: skipped on unsupported Android or with another Zygisk loader");
        return Ok(());
    }

    fs::create_dir_all(&dir)?;
    stage_once(&dir, REZYGISK, REZYGISK_ZIP).context("stage bundled ReZygisk")?;
    ensure!(
        rezygisk_available(),
        "ReZygisk is not enabled or failed to stage"
    );
    stage_once(&dir, HMA, HMA_ZIP).context("stage bundled HMA-OSS")
}

pub fn on_boot_completed() {
    if utils::is_safe_mode() || utils::has_magisk_installed() || module::module_startup_blocked() {
        return;
    }
    if let Err(error) = provision() {
        warn!("KMI bundle installation deferred: {error:#}");
    }
}
