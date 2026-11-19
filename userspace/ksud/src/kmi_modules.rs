//! Prepare the pinned module releases before Android starts Zygote.

use anyhow::{Context, Result, ensure};
use log::{info, warn};
use std::{
    fs::{self, DirBuilder, File, Permissions},
    io::{self, Cursor},
    os::unix::fs::{DirBuilderExt, PermissionsExt},
    path::Path,
    process::{Command, Stdio},
};
use zip::ZipArchive;

use crate::{defs, module, restorecon, utils};

const REZYGISK: &str = "rezygisk";
const HMA: &str = "hma_oss_zygisk";
const REZYGISK_ZIP: &[u8] = include_bytes!("../bundled-modules/rezygisk.zip");
const HMA_ZIP: &[u8] = include_bytes!("../bundled-modules/hma_oss_zygisk.zip");
const BOOT_READY: &str = "/data/adb/ksu/kmi-modules/.boot-ready";
const MANAGER_READY: &str = "/data/adb/ksu/kmi-modules/.manager-ready";
const APP_PENDING: &str = ".xec-manager-pending";
const HMA_PACKAGE: &str = "org.frknkrc44.hma_oss";
const KNOWN_OTHER_LOADERS: &[&str] = &[
    "zygisksu",
    "zygisknext",
    "admirepowered",
    "zygisk_on_ksu",
    "yukizygisk",
    "onyxzygisk",
];

type Payload = ZipArchive<Cursor<&'static [u8]>>;

fn missing(path: &Path) -> Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(false),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(true),
        Err(error) => Err(error).with_context(|| format!("inspect {}", path.display())),
    }
}

fn mkdir(path: &Path) -> io::Result<()> {
    DirBuilder::new().recursive(true).mode(0o755).create(path)
}

fn has_loader_binary(path: &Path) -> bool {
    ["zygiskd", "zygiskd64", "zygiskd32"]
        .iter()
        .any(|name| path.join("bin").join(name).is_file())
}

fn has_other_loader() -> Result<bool> {
    for root in [defs::MODULE_DIR, defs::MODULE_UPDATE_DIR] {
        let entries = match fs::read_dir(root) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        for entry in entries {
            let entry = entry?;
            if entry.file_name() != REZYGISK
                && entry.path().is_dir()
                && (KNOWN_OTHER_LOADERS
                    .iter()
                    .any(|id| entry.file_name() == *id)
                    || has_loader_binary(&entry.path()))
            {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

fn is_enabled(path: &Path) -> bool {
    path.join("module.prop").is_file()
        && !path.join(defs::DISABLE_FILE_NAME).exists()
        && !path.join(defs::REMOVE_FILE_NAME).exists()
}

fn extract(payload: &mut Payload, entry: &str, path: &Path, mode: u32) -> Result<()> {
    let parent = path.parent().context("payload file without parent")?;
    mkdir(parent)?;
    let mut input = payload.by_name(entry)?;
    ensure!(input.is_file(), "invalid payload entry {entry}");
    let mut output = File::create_new(path)?;
    io::copy(&mut input, &mut output)?;
    output.set_permissions(Permissions::from_mode(mode))?;
    Ok(())
}

fn status_hook(module: &Path, source: &str, stage: &str) -> Result<()> {
    let dir = Path::new(defs::ADB_DIR).join(stage);
    let destination = dir.join(source);
    if !missing(&destination)? {
        return Ok(());
    }
    mkdir(&dir)?;
    let mut file = tempfile::NamedTempFile::new_in(dir)?;
    io::copy(&mut File::open(module.join(source))?, file.as_file_mut())?;
    file.as_file()
        .set_permissions(Permissions::from_mode(0o755))?;
    restorecon::setsyscon(file.path())?;
    file.persist_noclobber(destination)?;
    Ok(())
}

// Keep the layout of the pinned upstream installers, without calling the
// package manager or running a recovery installer during post-fs-data.
fn prepare_rezygisk(dir: &Path, arches: &[(bool, &str, &str)]) -> Result<()> {
    let mut zip = ZipArchive::new(Cursor::new(REZYGISK_ZIP))?;
    for (file, mode) in [
        ("module.prop", 0o644),
        ("post-fs-data.sh", 0o755),
        ("service.sh", 0o755),
        ("uninstall.sh", 0o755),
        ("sepolicy.rule", 0o644),
        ("rezygisk.sh", 0o755),
    ] {
        extract(&mut zip, file, &dir.join(file), mode)?;
    }
    crate::sepolicy::check_rule(
        dir.join("sepolicy.rule")
            .to_str()
            .context("invalid rule path")?,
    )?;
    fs::copy(dir.join("module.prop"), dir.join("module.prop.bak"))?;
    for (&(supported, abi, machikado), (width, lib)) in
        arches.iter().zip([(64, "lib64"), (32, "lib")])
    {
        if !supported {
            continue;
        }
        extract(
            &mut zip,
            &format!("bin/{abi}/zygiskd"),
            &dir.join(format!("bin/zygiskd{width}")),
            0o755,
        )?;
        extract(
            &mut zip,
            &format!("lib/{abi}/libzygisk_ptrace.so"),
            &dir.join(format!("bin/zygisk-ptrace{width}")),
            0o755,
        )?;
        extract(
            &mut zip,
            &format!("lib/{abi}/libzygisk.so"),
            &dir.join(lib).join("libzygisk.so"),
            0o644,
        )?;
        extract(&mut zip, machikado, &dir.join(machikado), 0o644)?;
    }
    let web_files: Vec<_> = zip
        .file_names()
        .filter(|name| {
            name.starts_with("webroot/") && !name.ends_with('/') && !name.ends_with(".sha256")
        })
        .map(str::to_owned)
        .collect();
    for file in web_files {
        extract(&mut zip, &file, &dir.join(&file), 0o644)?;
    }
    restorecon::restore_syscon(dir)?;
    for lib in ["lib", "lib64"]
        .map(|lib| dir.join(lib))
        .iter()
        .filter(|lib| lib.exists())
    {
        for path in [lib.clone(), lib.join("libzygisk.so")] {
            restorecon::lsetfilecon(path, "u:object_r:system_lib_file:s0")?;
        }
    }
    status_hook(dir, "rezygisk.sh", "post-mount.d")?;
    status_hook(dir, "rezygisk.sh", "post-fs-data.d")
}

fn prepare_hma(dir: &Path, arches: &[(bool, &str, &str)]) -> Result<()> {
    let mut zip = ZipArchive::new(Cursor::new(HMA_ZIP))?;
    for file in [
        "module.prop",
        "classes.dex",
        "packages/android",
        "manager.apk",
        "action.sh",
        "uninstall.sh",
        "hmaoss.sh",
        "update_desc.sh",
    ] {
        extract(&mut zip, file, &dir.join(file), 0o644)?;
    }
    for &(_, abi, _) in arches {
        let file = format!("zygisk/{abi}.so");
        extract(&mut zip, &file, &dir.join(&file), 0o644)?;
    }
    fs::copy(dir.join("module.prop"), dir.join("module.prop.bak"))?;
    File::create_new(dir.join(APP_PENDING))?;
    restorecon::restore_syscon(dir)?;
    status_hook(dir, "hmaoss.sh", "boot-completed.d")
}

fn install_once(state: &Path, id: &str, prepare: impl FnOnce(&Path) -> Result<()>) -> Result<()> {
    let stamp = state.join(id);
    if !missing(&stamp)? {
        return Ok(());
    }
    let live = Path::new(defs::MODULE_DIR).join(id);
    if missing(&live)? && missing(&Path::new(defs::MODULE_UPDATE_DIR).join(id))? {
        let staging = tempfile::tempdir_in(state)?;
        fs::set_permissions(staging.path(), Permissions::from_mode(0o755))?;
        prepare(staging.path())?;
        mkdir(Path::new(defs::MODULE_DIR))?;
        fs::rename(staging.path(), &live)?;
        info!("KMI bundle: {id} installed for this boot");
    }
    // A manual removal must not cause another automatic install.
    File::create_new(stamp)?;
    Ok(())
}

/// Runs under the existing module mutation lock, before policies and scripts.
pub fn prepare_first_boot() -> Result<()> {
    let state = Path::new(defs::WORKING_DIR).join("kmi-modules");
    if !missing(&state.join(REZYGISK))? && !missing(&state.join(HMA))? {
        return Ok(());
    }
    let sdk = utils::getprop("ro.build.version.sdk")
        .and_then(|sdk| sdk.trim().parse::<u32>().ok())
        .unwrap_or_default();
    if sdk < 29 || has_other_loader()? {
        info!("KMI bundle: skipped on unsupported Android or with another Zygisk loader");
        return Ok(());
    }
    let product = utils::getprop("ro.product.cpu.abilist").unwrap_or_default();
    let system = utils::getprop("ro.system.product.cpu.abilist").unwrap_or_default();
    let abis = if product.len() >= system.len() {
        product
    } else {
        system
    };
    let supports = |abi| abis.split(',').any(|supported| supported.trim() == abi);
    let arches = if cfg!(target_arch = "aarch64") {
        [
            (supports("arm64-v8a"), "arm64-v8a", "machikado.arm64"),
            (supports("armeabi-v7a"), "armeabi-v7a", "machikado.arm"),
        ]
    } else {
        [
            (supports("x86_64"), "x86_64", "machikado.x86_64"),
            (supports("x86"), "x86", "machikado.x86"),
        ]
    };
    ensure!(arches[0].0, "unsupported Zygote ABI: {abis}");
    mkdir(&state)?;
    install_once(&state, REZYGISK, |dir| prepare_rezygisk(dir, &arches))
        .context("prepare ReZygisk")?;
    let loader = Path::new(defs::MODULE_DIR).join(REZYGISK);
    if is_enabled(&loader) && has_loader_binary(&loader) {
        install_once(&state, HMA, |dir| prepare_hma(dir, &arches)).context("prepare HMA-OSS")?;
    }
    Ok(())
}

pub fn mark_boot_ready() {
    let mark = || -> Result<()> {
        if manager_install_pending()? {
            fs::write(BOOT_READY, module::boot_id()?)?;
        }
        Ok(())
    };
    if let Err(error) = mark() {
        warn!("KMI bundle: could not record boot readiness: {error:#}");
    }
}

fn manager_install_pending() -> Result<bool> {
    let hma = Path::new(defs::MODULE_DIR).join(HMA);
    if !missing(Path::new(MANAGER_READY))? || !is_enabled(&hma) {
        return Ok(false);
    }
    let loader = Path::new(defs::MODULE_DIR).join(REZYGISK);
    if !is_enabled(&loader) || !has_loader_binary(&loader) {
        return Ok(false);
    }
    if hma.join(APP_PENDING).is_file() {
        return Ok(true);
    }
    // 31445 tried its own APK install in the upstream installer, but that
    // installer didn't fail the module when PackageManager rejected the APK.
    let state = Path::new(BOOT_READY)
        .parent()
        .context("no KMI state directory")?;
    if missing(&state.join(HMA))? || missing(Path::new(BOOT_READY))? {
        return Ok(false);
    }
    let mut zip = ZipArchive::new(Cursor::new(HMA_ZIP))?;
    let mut expected = Vec::new();
    io::Read::read_to_end(&mut zip.by_name("module.prop")?, &mut expected)?;
    Ok(fs::read(hma.join("module.prop.bak"))? == expected)
}

fn package_command() -> Command {
    let mut command = Command::new("/system/bin/timeout");
    command.args(["30", "/system/bin/cmd", "package"]);
    command
}

fn install_manager() -> Result<()> {
    if !manager_install_pending()? {
        return Ok(());
    }
    let installed = package_command()
        .args(["path", "--user", "0", HMA_PACKAGE])
        .output()?;
    if !installed.status.success() || !installed.stdout.starts_with(b"package:") {
        // Stream the original APK to PackageManager; it cannot read /data/adb.
        let mut zip = ZipArchive::new(Cursor::new(HMA_ZIP))?;
        let mut apk = zip.by_name("manager.apk")?;
        let mut install = package_command()
            .args(["install", "--user", "0", "-S", &apk.size().to_string(), "-"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        let result = io::copy(
            &mut apk,
            &mut install.stdin.take().context("no install stdin")?,
        );
        let output = install.wait_with_output()?;
        ensure!(
            output.status.success(),
            "PackageManager: {}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        result?;
        info!("KMI bundle: HMA-OSS manager installed for Android user 0");
    }
    File::create_new(MANAGER_READY)?;
    Ok(())
}

pub fn on_boot_completed() {
    if utils::is_safe_mode() || utils::has_magisk_installed() || module::module_startup_blocked() {
        return;
    }
    if fs::read_to_string(BOOT_READY)
        .ok()
        .zip(module::boot_id().ok())
        .is_none_or(|(ready, boot_id)| ready != boot_id)
    {
        return;
    }
    if let Err(error) = install_manager() {
        warn!("KMI bundle: HMA-OSS manager installation deferred: {error:#}");
    }
}
