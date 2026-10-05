#[allow(clippy::wildcard_imports)]
use crate::utils::*;
use crate::{
    assets, defs, ksucalls, metamodule,
    restorecon::{restore_syscon, setsyscon},
    sepolicy,
};

use anyhow::{Context, Result, anyhow, bail, ensure};
use const_format::concatcp;
use is_executable::is_executable;
use log::{debug, error, info, warn};
use regex_lite::Regex;

use std::{
    collections::{BTreeMap, HashMap},
    env::var as env_var,
    fs::{File, Permissions, canonicalize, remove_dir_all, set_permissions},
    path::{Path, PathBuf},
    process::Command,
    str::FromStr,
};
use std::{
    fs::{copy, rename},
    io::Write,
};
use zip_extensions::inflate::zip_extract::zip_extract_file_to_memory;

use crate::defs::{MODULE_DIR, MODULE_UPDATE_DIR, UPDATE_FILE_NAME};
use crate::module::ModuleType::{Active, All};
#[cfg(unix)]
use std::os::unix::{prelude::PermissionsExt, process::CommandExt};

const INSTALLER_CONTENT: &str = include_str!("./installer.sh");
const INSTALL_MODULE_SCRIPT: &str = concatcp!(
    INSTALLER_CONTENT,
    "\n",
    "install_module",
    "\n",
    "exit 0",
    "\n"
);

// ---------------------------------------------------------------------------
// Built-in module storage & materialization
//
// Built-in modules execute like ordinary modules but live encrypted at rest
// (under BUILTIN_STORE_DIR) and are decrypted into a tmpfs runtime dir
// (BUILTIN_MODULE_DIR) at boot. The on-disk blobs use innocuous token names so
// a casual `ls`/`cat` reveals neither the module ids nor their contents.
// ---------------------------------------------------------------------------

const BUILTIN_XOR_KEY: &[u8] = b"xdcv1";

const BUILTIN_MODULES: &[(&str, &str)] = &[
    ("tricky_store", "0.cfg"),
    ("TA_enhanced", "1.cfg"),
    ("susfs4ksu", "2.cfg"),
    ("SelinuxFix", "3.cfg"),
];

const BUILTIN_MAGIC: &[u8; 4] = b"BCFG";

fn xor_builtin(data: &[u8]) -> Vec<u8> {
    data.iter()
        .enumerate()
        .map(|(i, b)| b ^ BUILTIN_XOR_KEY[i % BUILTIN_XOR_KEY.len()])
        .collect()
}

fn builtin_file_mode(rel: &str) -> u32 {
    let is_shell = Path::new(rel)
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("sh"));
    if is_shell || rel.starts_with("bin/") || matches!(rel, "daemon" | "inject" | "supervisor") {
        0o755
    } else {
        0o644
    }
}

fn pack_builtin_module(entries: &[(String, u32, Vec<u8>)]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64 * 1024);
    buf.extend_from_slice(BUILTIN_MAGIC);
    buf.push(1u8);
    buf.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for (path, mode, data) in entries {
        let path = path.as_bytes();
        buf.extend_from_slice(&(path.len() as u16).to_le_bytes());
        buf.extend_from_slice(path);
        buf.extend_from_slice(&mode.to_le_bytes());
        buf.extend_from_slice(&(data.len() as u32).to_le_bytes());
        buf.extend_from_slice(data);
    }
    xor_builtin(&buf)
}

fn unpack_builtin_module(blob: &[u8]) -> Result<Vec<(String, u32, Vec<u8>)>> {
    let raw = xor_builtin(blob);
    ensure!(raw.len() >= 9, "builtin blob too short");
    ensure!(&raw[..4] == BUILTIN_MAGIC, "bad builtin blob magic");
    let count = u32::from_le_bytes(raw[5..9].try_into().unwrap()) as usize;
    let mut off = 9usize;
    let mut entries = Vec::with_capacity(count);
    for _ in 0..count {
        ensure!(off + 2 <= raw.len(), "truncated builtin blob");
        let plen = u16::from_le_bytes(raw[off..off + 2].try_into().unwrap()) as usize;
        off += 2;
        ensure!(off + plen + 8 <= raw.len(), "truncated builtin blob");
        let path = String::from_utf8_lossy(&raw[off..off + plen]).into_owned();
        off += plen;
        let mode = u32::from_le_bytes(raw[off..off + 4].try_into().unwrap());
        off += 4;
        let dlen = u32::from_le_bytes(raw[off..off + 4].try_into().unwrap()) as usize;
        off += 4;
        ensure!(off + dlen <= raw.len(), "truncated builtin blob");
        let data = raw[off..off + dlen].to_vec();
        off += dlen;
        entries.push((path, mode, data));
    }
    Ok(entries)
}

/// Validate module_id format and security
/// Module ID must match: ^[a-zA-Z][a-zA-Z0-9._-]+$
/// - Must start with a letter (a-zA-Z)
/// - Followed by one or more alphanumeric, dot, underscore, or hyphen characters
/// - Minimum length: 2 characters
pub fn validate_module_id(module_id: &str) -> Result<()> {
    let re = Regex::new(r"^[a-zA-Z][a-zA-Z0-9._-]+$")?;
    if re.is_match(module_id) {
        Ok(())
    } else {
        Err(anyhow!(
            "Invalid module ID: '{module_id}'. Must match /^[a-zA-Z][a-zA-Z0-9._-]+$/"
        ))
    }
}

/// Get common environment variables for script execution
pub fn get_common_script_envs(module_id: Option<&str>) -> Vec<(&'static str, String)> {
    let mut envs = vec![
        ("ASH_STANDALONE", "1".to_string()),
        ("KSU", "true".to_string()),
        // The kernel deliberately reports a Zygisk-Next-compatible version code
        // (KSU_COMPAT_REPORTED_VERSION) to every non-manager caller, so the live
        // value must not leak into module scripts: modules would conclude they
        // run on an ancient kernel and silently disable their modern code paths.
        // ksud and the kernel always come from the same build, so the compile
        // time code is the truthful number here.
        ("KSU_KERNEL_VER_CODE", defs::VERSION_CODE.to_string()),
        ("KSU_VER_CODE", defs::VERSION_CODE.to_string()),
        ("KSU_VER", defs::VERSION_NAME.to_string()),
        ("KSU_UAPI_VER", ksucalls::uapi_version().to_string()),
        ("KSU_RUNTIME_MODE", ksucalls::runtime_mode().to_string()),
        (
            "PATH",
            format!(
                "{}:{}",
                env_var("PATH").unwrap_or_default(),
                defs::BINARY_DIR.trim_end_matches('/')
            ),
        ),
    ];

    if let Some(id) = module_id {
        if validate_module_id(id).is_ok() {
            envs.push(("KSU_MODULE", id.to_string()));
        } else {
            error!("Invalid module_id provided: {id}");
        }
    }

    if ksucalls::is_late_load() {
        envs.push(("KSU_LATE_LOAD", "1".to_string()));
    }

    envs
}

fn exec_install_script(module_file: &str, is_metamodule: bool, module_id: &str) -> Result<()> {
    let realpath = std::fs::canonicalize(module_file)
        .with_context(|| format!("realpath: {module_file} failed"))?;

    // Get install script from metamodule module
    let install_script =
        metamodule::get_install_script(is_metamodule, INSTALLER_CONTENT, INSTALL_MODULE_SCRIPT)?;

    let result = Command::new(assets::BUSYBOX_PATH)
        .args(["sh", "-c", &install_script])
        .envs(get_common_script_envs(Some(module_id)))
        .env("OUTFD", "1")
        .env("ZIPFILE", realpath)
        .status()?;
    ensure!(result.success(), "Failed to install module script");
    Ok(())
}

// Check if Android boot is completed before installing modules
fn ensure_boot_completed() -> Result<()> {
    // ensure getprop sys.boot_completed == 1
    if getprop("sys.boot_completed").as_deref() != Some("1") {
        bail!("Android is Booting!");
    }
    Ok(())
}

#[derive(PartialEq, Eq)]
pub enum ModuleType {
    All,
    Active,
    Updated,
}

#[allow(clippy::needless_pass_by_value)]
pub fn foreach_module(
    module_type: ModuleType,
    mut f: impl FnMut(&Path) -> Result<()>,
) -> Result<()> {
    let modules_dir = Path::new(match module_type {
        ModuleType::Updated => MODULE_UPDATE_DIR,
        _ => defs::MODULE_DIR,
    });
    let dir = std::fs::read_dir(modules_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            warn!("{} is not a directory, skip", path.display());
            continue;
        }

        if module_type == Active && path.join(defs::DISABLE_FILE_NAME).exists() {
            info!("{} is disabled, skip", path.display());
            continue;
        }
        if module_type == Active && path.join(defs::REMOVE_FILE_NAME).exists() {
            warn!("{} is removed, skip", path.display());
            continue;
        }

        f(&path)?;
    }

    Ok(())
}

fn foreach_active_module(f: impl FnMut(&Path) -> Result<()>) -> Result<()> {
    foreach_module(Active, f)
}

/// Iterate decrypted built-in modules under the tmpfs runtime dir. These
/// execute like normal modules but are never enumerated by the manager panel
/// or `adb ls modules/`, and the encrypted on-disk store is not readable.
fn foreach_builtin_module(mut f: impl FnMut(&Path) -> Result<()>) -> Result<()> {
    let modules_dir = Path::new(defs::BUILTIN_MODULE_DIR);
    let Ok(dir) = std::fs::read_dir(modules_dir) else {
        return Ok(());
    };
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        f(&path)?;
    }
    Ok(())
}

pub fn load_sepolicy_rule() -> Result<()> {
    foreach_active_module(|path| {
        let rule_file = path.join("sepolicy.rule");
        if !rule_file.exists() {
            return Ok(());
        }
        info!("load policy: {}", rule_file.display());

        if sepolicy::apply_file(&rule_file).is_err() {
            warn!("Failed to load sepolicy.rule for {}", rule_file.display());
        }
        Ok(())
    })?;

    load_builtin_sepolicy_rule()
}

/// Load only the built-in modules' `sepolicy.rule`.
///
/// Used when the regular module pipeline is skipped (external Magisk detected)
/// but the built-in modules still run, so their required policy types stay
/// available.
pub fn load_builtin_sepolicy_rule() -> Result<()> {
    foreach_builtin_module(|path| {
        let rule_file = path.join("sepolicy.rule");
        if !rule_file.exists() {
            return Ok(());
        }
        info!("load built-in policy: {}", rule_file.display());

        if sepolicy::apply_file(&rule_file).is_err() {
            warn!("Failed to load sepolicy.rule for {}", rule_file.display());
        }
        Ok(())
    })
}

/// Extract the module id from a module script path. Returns whether the path
/// is inside a module dir (built-in included) and the id if resolvable.
fn extract_module_id(path: &Path) -> (bool, Option<String>) {
    for base in [defs::MODULE_DIR, defs::BUILTIN_MODULE_DIR] {
        if let Some(id) = path
            .strip_prefix(base)
            .ok()
            .and_then(|p| p.components().next())
            .and_then(|c| c.as_os_str().to_str())
            .map(ToString::to_string)
        {
            return (true, Some(id));
        }
    }
    (false, None)
}

pub fn exec_script<T: AsRef<Path>>(path: T, wait: bool) -> Result<()> {
    info!("exec {}", path.as_ref().display());

    let (is_module_script, module_id) = extract_module_id(path.as_ref());

    // Validate and log module_id extraction
    let validated_module_id = module_id
        .as_ref()
        .and_then(|id| match validate_module_id(id) {
            Ok(()) => {
                debug!("Module ID extracted from script path: '{id}'");
                Some(id.as_str())
            }
            Err(e) => {
                warn!(
                    "Invalid module ID '{id}' extracted from script path '{}': {e}",
                    path.as_ref().display(),
                );
                None
            }
        });

    if is_module_script && module_id.is_none() {
        debug!(
            "Failed to extract module_id from script path '{}'. Script will run without KSU_MODULE environment variable.",
            path.as_ref().display()
        );
    }

    let mut command = &mut Command::new(assets::BUSYBOX_PATH);
    #[cfg(unix)]
    {
        command = unsafe {
            command.pre_exec(|| {
                detach_process_group(true);
                // ignore the error?
                switch_cgroups();
                Ok(())
            })
        };
    }
    command = command
        .current_dir(path.as_ref().parent().unwrap())
        .arg("sh")
        .arg(path.as_ref())
        .envs(get_common_script_envs(validated_module_id));

    let result = if wait {
        command.status().map(|_| ())
    } else {
        command.spawn().map(|_| ())
    };
    result.map_err(|e| anyhow!("Failed to exec {}: {e}", path.as_ref().display()))
}

/// Execute `<stage>.sh` of the built-in modules only.
///
/// Built-in modules are shipped and provisioned by ksud itself, so they must
/// keep running even when an external Magisk installation is detected: in that
/// case the generic module pipeline is skipped on purpose, but skipping our own
/// modules as well leaves Zygisk silently disabled.
pub fn exec_builtin_stage_script(stage: &str, block: bool) -> Result<()> {
    foreach_builtin_module(|module| {
        let script_path = module.join(format!("{stage}.sh"));
        if !script_path.exists() {
            return Ok(());
        }

        exec_script(&script_path, block)
    })
}

pub fn exec_stage_script(stage: &str, block: bool) -> Result<()> {
    let metamodule_dir = metamodule::get_metamodule_path().and_then(|path| canonicalize(path).ok());

    foreach_active_module(|module| {
        if metamodule_dir.as_ref().is_some_and(|meta_dir| {
            canonicalize(module).is_ok_and(|resolved| resolved == *meta_dir)
        }) {
            return Ok(());
        }

        let script_path = module.join(format!("{stage}.sh"));
        if !script_path.exists() {
            return Ok(());
        }

        exec_script(&script_path, block)
    })?;

    exec_builtin_stage_script(stage, block)
}

pub fn exec_common_scripts(dir: &str, wait: bool) -> Result<()> {
    let script_dir = Path::new(defs::ADB_DIR).join(dir);
    if !script_dir.exists() {
        info!("{} not exists, skip", script_dir.display());
        return Ok(());
    }

    let dir = std::fs::read_dir(&script_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();

        if !is_executable(&path) {
            warn!("{} is not executable, skip", path.display());
            continue;
        }

        exec_script(path, wait)?;
    }

    Ok(())
}

pub fn load_system_prop() -> Result<()> {
    foreach_active_module(|module| {
        let system_prop = module.join("system.prop");
        if !system_prop.exists() {
            return Ok(());
        }
        info!("load {} system.prop", module.display());

        crate::resetprop::load_system_prop_file(&system_prop)?;

        Ok(())
    })?;

    foreach_builtin_module(|module| {
        let system_prop = module.join("system.prop");
        if !system_prop.exists() {
            return Ok(());
        }
        info!("load built-in {} system.prop", module.display());

        crate::resetprop::load_system_prop_file(&system_prop)?;

        Ok(())
    })?;

    Ok(())
}

/// Path of the build stamp that pins a stored blob to the ksud build that
/// wrote it. Kept next to the blob inside [`defs::BUILTIN_STORE_DIR`].
fn builtin_store_stamp_path(token: &str) -> PathBuf {
    Path::new(defs::BUILTIN_STORE_DIR).join(format!("{token}.ver"))
}

/// Whether the stored blob for `token` was written by the running build.
///
/// Without this check a blob written by an older ksud would survive forever
/// (`dest.exists()` used to short-circuit the refresh), so updating ksud could
/// never deliver a fixed built-in module such as `TA_enhanced`.
fn builtin_store_is_current(token: &str) -> bool {
    std::fs::read_to_string(builtin_store_stamp_path(token))
        .is_ok_and(|stamp| stamp.trim() == defs::VERSION_CODE)
}

/// Persistent encrypted store + tmpfs materialization for built-in modules.
///
/// On-disk blobs are rewritten whenever the running ksud build changes, so a
/// stale blob can never outlive an update and keep e.g. an obsolete module
/// in place; the runtime dir is decrypted from those blobs every boot into
/// RAM-only tmpfs.
pub fn ensure_builtin_modules() -> Result<()> {
    // 1. Encrypt the bundled modules into the on-disk store (refreshed per build).
    for (module_id, token) in BUILTIN_MODULES {
        let dest = Path::new(defs::BUILTIN_STORE_DIR).join(token);
        if dest.exists() && builtin_store_is_current(token) {
            continue;
        }
        let prefix = format!("{module_id}/");
        let mut entries = Vec::new();
        for file in assets::list_builtin_assets() {
            if let Some(rel) = file.strip_prefix(&prefix) {
                let data = assets::get_builtin_asset(&file)?;
                let mode = builtin_file_mode(rel);
                entries.push((rel.to_string(), mode, data));
            }
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("Failed to create {}", parent.display()))?;
        }
        let blob = pack_builtin_module(&entries);
        std::fs::write(&dest, blob)
            .with_context(|| format!("Failed to write {}", dest.display()))?;
        let stamp = builtin_store_stamp_path(token);
        std::fs::write(&stamp, defs::VERSION_CODE)
            .with_context(|| format!("Failed to write {}", stamp.display()))?;
    }

    // 2. Decrypt each store blob into the tmpfs runtime dir.
    for (module_id, token) in BUILTIN_MODULES {
        let store = Path::new(defs::BUILTIN_STORE_DIR).join(token);
        let blob =
            std::fs::read(&store).with_context(|| format!("Failed to read {}", store.display()))?;
        let entries = unpack_builtin_module(&blob)?;
        let base = Path::new(defs::BUILTIN_MODULE_DIR).join(module_id);
        for (rel, mode, data) in entries {
            let dest = base.join(&rel);
            if dest.exists() {
                continue;
            }
            if let Some(parent) = dest.parent() {
                std::fs::create_dir_all(parent)
                    .with_context(|| format!("Failed to create {}", parent.display()))?;
            }
            std::fs::write(&dest, &data)
                .with_context(|| format!("Failed to write {}", dest.display()))?;
            #[cfg(unix)]
            std::fs::set_permissions(&dest, Permissions::from_mode(mode))?;
        }

        // /dev is tmpfs: freshly written files inherit the tmpfs context, which
        // the zygote is not allowed to map — the built-in Zygisk daemon hands
        // these libs to the zygote for injection. Label the whole runtime dir
        // system_file, same as a regular module dir gets from restorecon().
        if let Err(e) = restore_syscon(&base) {
            warn!("label built-in module dir {} failed: {e}", base.display());
        }
    }

    Ok(())
}

pub fn prune_modules() -> Result<()> {
    foreach_module(All, |module| {
        if !module.join(defs::REMOVE_FILE_NAME).exists() {
            return Ok(());
        }

        info!("remove module: {}", module.display());

        // Execute metamodule's metauninstall.sh first
        let module_id = module.file_name().and_then(|n| n.to_str()).unwrap_or("");

        // Check if this is a metamodule
        let is_metamodule =
            read_module_prop(module).is_ok_and(|props| metamodule::is_metamodule(&props));

        if is_metamodule {
            info!("Removing metamodule symlink");
            if let Err(e) = metamodule::remove_symlink() {
                warn!("Failed to remove metamodule symlink: {e}");
            }
        } else if let Err(e) = metamodule::exec_metauninstall_script(module_id) {
            warn!("Failed to exec metamodule uninstall for {module_id}: {e}");
        }

        // Then execute module's own uninstall.sh
        let uninstaller = module.join("uninstall.sh");
        if uninstaller.exists()
            && let Err(e) = exec_script(uninstaller, true)
        {
            warn!("Failed to exec uninstaller: {e}");
        }

        // Clear module configs before removing module directory
        if let Err(e) = crate::module_config::clear_module_configs(module_id) {
            warn!("Failed to clear configs for {module_id}: {e}");
        }

        // Finally remove the module directory
        if let Err(e) = remove_dir_all(module) {
            warn!("Failed to remove {}: {e}", module.display());
        }

        Ok(())
    })?;

    // collect remaining modules, if none, clean up metamodule record
    let remaining_modules: Vec<_> = std::fs::read_dir(defs::MODULE_DIR)?
        .filter_map(std::result::Result::ok)
        .filter(|entry| entry.path().join("module.prop").exists())
        .collect();

    if remaining_modules.is_empty() {
        info!("no remaining modules.");
    }

    Ok(())
}

const METADATA_FILE_CON: &str = "u:object_r:metadata_file:s0";

// Prefer /metadata/watchdog/ when present, else /metadata.
fn preinit_ksu_dir() -> &'static str {
    if Path::new("/metadata/watchdog").is_dir() {
        defs::PREINIT_DIR_WATCHDOG
    } else {
        defs::PREINIT_DIR_DEFAULT
    }
}

fn collect_rc_files<P: AsRef<Path>>(
    dir: P,
    mod_id: Option<&str>,
    out: &mut dyn Write,
) -> Result<()> {
    let dir = dir.as_ref();
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Ok(());
    };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let path = entry.path();
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_file() && path.extension().and_then(|s| s.to_str()) == Some("rc") {
            if let Some(mod_id) = mod_id {
                writeln!(out, "# === from {mod_id}:{} ===", path.display())?;
            } else {
                // Although the rc file itself is not executable, we still use its executable bit as a switch.
                if !is_executable(&path) {
                    continue;
                }
                writeln!(out, "# === from {} ===", path.display())?;
            }
            let content = std::fs::read(&path)
                .with_context(|| format!("Failed to read rc {}", path.display()))?;
            out.write_all(&content)?;
            writeln!(out)?;
        }
    }
    Ok(())
}

/// Rebuild PREINITDIR/modules.rc by concatenating *.rc from every enabled
/// module. The kernel-side read hook splices this file into init.rc on the
/// next boot.
pub fn regenerate_preinit_rc() -> Result<()> {
    let preinit_str = preinit_ksu_dir();
    let preinit_dir = Path::new(preinit_str);
    std::fs::create_dir_all(preinit_dir)
        .with_context(|| format!("Failed to create {}", preinit_dir.display()))?;

    let tmp_path_buf = preinit_dir.join(defs::MODULES_RC_TMP_FILE);
    let out_path_buf = preinit_dir.join(defs::MODULES_RC_FILE);
    let tmp_path = tmp_path_buf.as_path();
    let out_path = out_path_buf.as_path();

    {
        let mut tmp = File::create(tmp_path)
            .with_context(|| format!("Failed to create {}", tmp_path.display()))?;

        // collect modules in alphabetical order, with their effective module path in the next boot
        let mut modules: BTreeMap<String, Option<PathBuf>> = BTreeMap::new();
        // collect common initrc first
        collect_rc_files(Path::new(defs::ADB_DIR).join("initrc.d"), None, &mut tmp)?;
        // modules_update/ first so freshly-installed modules win on id collision.
        for src_dir in [defs::MODULE_UPDATE_DIR, defs::MODULE_DIR] {
            let Ok(entries) = std::fs::read_dir(src_dir) else {
                continue;
            };
            for entry in entries.flatten() {
                let module_path = entry.path();
                if !module_path.is_dir() {
                    continue;
                }
                let Some(id) = module_path.file_name().and_then(|s| s.to_str()) else {
                    continue;
                };
                let id = id.to_string();
                if module_path.join(defs::DISABLE_FILE_NAME).exists()
                    || module_path.join(defs::REMOVE_FILE_NAME).exists()
                {
                    modules.insert(id, None);
                    continue;
                }
                modules.entry(id).or_insert(Some(module_path));
            }
        }
        // Built-in modules always contribute their rc and are never listed in
        // the panel, so they ignore any disable/remove marker.
        if let Ok(entries) = std::fs::read_dir(defs::BUILTIN_MODULE_DIR) {
            for entry in entries.flatten() {
                let module_path = entry.path();
                if !module_path.is_dir() {
                    continue;
                }
                let Some(id) = module_path.file_name().and_then(|s| s.to_str()) else {
                    continue;
                };
                modules.insert(id.to_string(), Some(module_path));
            }
        }
        for (id, path) in modules {
            if let Some(path) = path {
                collect_rc_files(path.join(defs::MODULE_INIT_RC_DIR), Some(&id), &mut tmp)?;
            }
        }
        tmp.sync_all()?;
    }

    std::fs::rename(tmp_path, out_path).with_context(|| {
        format!(
            "Failed to rename {} -> {}",
            tmp_path.display(),
            out_path.display()
        )
    })?;

    // SELinux label so the kernel's filp_open in init context can read it.
    if let Err(e) = crate::restorecon::lsetfilecon(out_path, METADATA_FILE_CON) {
        debug!("set context on {} failed: {e}", out_path.display());
    }

    // Clear stale file at the other candidate path.
    let stale_dir = if preinit_str == defs::PREINIT_DIR_WATCHDOG {
        defs::PREINIT_DIR_DEFAULT
    } else {
        defs::PREINIT_DIR_WATCHDOG
    };
    std::fs::remove_file(Path::new(stale_dir).join(defs::MODULES_RC_FILE)).ok();

    Ok(())
}

pub fn handle_updated_modules() -> Result<()> {
    let modules_root = Path::new(MODULE_DIR);
    foreach_module(ModuleType::Updated, |updated_module| {
        if !updated_module.is_dir() {
            return Ok(());
        }

        if let Some(name) = updated_module.file_name() {
            let module_dir = modules_root.join(name);
            // Preserve the "remove" marker so a pending uninstall still takes
            // effect, but do NOT carry over the "disable" marker: the installer
            // (installer.sh + Rust cleanup) explicitly clears it, and carrying
            // it forward makes freshly-flashed modules appear "disabled by
            // default" — the user's #1 complaint about ZY modules.
            let removed = if module_dir.exists() {
                let r = module_dir.join(defs::REMOVE_FILE_NAME).exists();
                remove_dir_all(&module_dir)?;
                r
            } else {
                false
            };
            rename(updated_module, &module_dir)?;
            if removed {
                let path = module_dir.join(defs::REMOVE_FILE_NAME);
                if let Err(e) = ensure_file_exists(&path) {
                    warn!("Failed to create {}: {e}", path.display());
                }
            }
        }
        Ok(())
    })?;
    Ok(())
}

/// Zygisk Next's own persistent directory (its runtime state, not a module).
const ZYGISK_STATE_DIR: &str = "/data/adb/zygisksu";

/// Remove the failure leftovers a Zygisk Next style module writes when its
/// daemon cannot start.
///
/// `disable` + `.abort_msg` **together** are the signature of an aborted
/// daemon start and get self-healed here. A `disable` marker that stands alone
/// is a *user* decision (manager toggle) and is deliberately respected —
/// recovery from a bad boot must not silently re-enable what the user switched
/// off on purpose. An `.abort_msg` without `disable` is kept as a diagnostic.
pub fn clear_zygisk_failure_markers() {
    // Directory ids that look like Zygisk Next (`zygisksu`, `Zygisk-Next`, ...)
    // are matched on the substring "ygisk", which covers both spellings.
    for root in [
        defs::MODULE_DIR,
        defs::MODULE_UPDATE_DIR,
        defs::BUILTIN_MODULE_DIR,
    ] {
        let Ok(entries) = std::fs::read_dir(root) else {
            continue;
        };
        for entry in entries.flatten() {
            let name = entry.file_name();
            if name.to_string_lossy().contains("ygisk") {
                clear_zygisk_markers_in(&entry.path());
            }
        }
    }

    // Zygisk Next also stores a copy of these markers outside the module tree.
    clear_zygisk_markers_in(Path::new(ZYGISK_STATE_DIR));
}

/// Self-heal the abort signature (`disable` + `.abort_msg`) in one directory.
fn clear_zygisk_markers_in(dir: &Path) {
    let abort_path = dir.join(defs::ABORT_MSG_FILE_NAME);
    let aborted = std::fs::symlink_metadata(&abort_path).is_ok_and(|meta| meta.is_file());
    if !aborted {
        return;
    }
    for marker in [defs::DISABLE_FILE_NAME, defs::ABORT_MSG_FILE_NAME] {
        let path = dir.join(marker);
        // `symlink_metadata` deliberately: a symlink is not a real marker.
        let is_real_file = std::fs::symlink_metadata(&path).is_ok_and(|meta| meta.is_file());
        if !is_real_file {
            continue;
        }
        match std::fs::remove_file(&path) {
            Ok(()) => info!("Cleared stale Zygisk marker: {}", path.display()),
            Err(e) => warn!("Failed to remove {}: {e}", path.display()),
        }
    }
}

fn install_module_to_system(zip: &str) -> Result<()> {
    ensure_boot_completed()?;

    // print banner
    println!(include_str!("banner"));

    assets::ensure_binaries(false).with_context(|| "Failed to extract assets")?;

    // first check if working dir is usable
    ensure_dir_exists(defs::WORKING_DIR).with_context(|| "Failed to create working dir")?;
    ensure_dir_exists(defs::BINARY_DIR).with_context(|| "Failed to create bin dir")?;

    // read the module_id from zip, if failed it will return early.
    let mut buffer: Vec<u8> = Vec::new();
    let entry_path = PathBuf::from_str("module.prop")?;
    let zip_path = PathBuf::from_str(zip)?;
    let zip_path = zip_path.canonicalize()?;
    zip_extract_file_to_memory(&zip_path, &entry_path, &mut buffer)?;

    let module_prop = parse_module_prop(&buffer);
    info!("module prop: {module_prop:?}");

    let Some(module_id) = module_prop.get("id") else {
        bail!("module id not found in module.prop!");
    };
    let module_id = module_id.trim();

    // Validate module_id format
    validate_module_id(module_id)
        .with_context(|| format!("Invalid module ID in module.prop: '{module_id}'"))?;

    // Check if this module is a metamodule
    let is_metamodule = metamodule::is_metamodule(&module_prop);

    // Check if it's safe to install regular module
    if !is_metamodule && let Err(is_disabled) = metamodule::check_install_safety() {
        println!("\n❌ Installation Blocked");
        println!("┌────────────────────────────────");
        println!("│ A metamodule with custom installer is active");
        println!("│");
        if is_disabled {
            println!("│ Current state: Disabled");
            println!("│ Action required: Re-enable or uninstall it, then reboot");
        } else {
            println!("│ Current state: Pending changes");
            println!("│ Action required: Reboot to apply changes first");
        }
        println!("└─────────────────────────────────\n");
        bail!("Metamodule installation blocked");
    }

    // All modules (including metamodules) are installed to MODULE_UPDATE_DIR
    let updated_dir = Path::new(defs::MODULE_UPDATE_DIR).join(module_id);

    if is_metamodule {
        info!("Installing metamodule: {module_id}");

        // Check if there's already a metamodule installed
        if metamodule::has_metamodule()
            && let Some(existing_path) = metamodule::get_metamodule_path()
        {
            let existing_id = read_module_prop(&existing_path)
                .ok()
                .and_then(|m| m.get("id").cloned())
                .unwrap_or_else(|| "unknown".to_string());

            if existing_id != module_id {
                println!("\n❌ Installation Failed");
                println!("┌────────────────────────────────");
                println!("│ A metamodule is already installed");
                println!("│   Current metamodule: {existing_id}");
                println!("│");
                println!("│ Only one metamodule can be active at a time.");
                println!("│");
                println!("│ To install this metamodule:");
                println!("│   1. Uninstall the current metamodule");
                println!("│   2. Reboot your device");
                println!("│   3. Install the new metamodule");
                println!("└─────────────────────────────────\n");
                bail!("Cannot install multiple metamodules");
            }
        }
    }

    let zip_uncompressed_size = get_zip_uncompressed_size(zip)?;
    info!(
        "zip uncompressed size: {}",
        humansize::format_size(zip_uncompressed_size, humansize::DECIMAL)
    );
    println!(
        "- Module size: {}",
        humansize::format_size(zip_uncompressed_size, humansize::DECIMAL)
    );

    // Ensure module directory exists and set SELinux context
    ensure_dir_exists(defs::MODULE_UPDATE_DIR)?;
    setsyscon(defs::MODULE_UPDATE_DIR)?;

    // Prepare target directory
    println!("- Installing to {}", updated_dir.display());
    ensure_clean_dir(&updated_dir)?;
    info!("target dir: {}", updated_dir.display());

    // Extract zip to target directory
    println!("- Extracting module files");
    let file = File::open(zip)?;
    let mut archive = zip::ZipArchive::new(file)?;
    archive.extract(&updated_dir)?;

    // Set permission and selinux context for $MOD/system
    let module_system_dir = updated_dir.join("system");
    if module_system_dir.exists() {
        #[cfg(unix)]
        set_permissions(&module_system_dir, Permissions::from_mode(0o755))?;
        restore_syscon(&module_system_dir)?;
    }

    // Execute install script
    println!("- Running module installer");
    exec_install_script(zip, is_metamodule, module_id)?;

    let module_dir = Path::new(MODULE_DIR).join(module_id);
    ensure_dir_exists(&module_dir)?;

    // Clear stale control markers from both the freshly extracted payload and
    // the currently active module dir, so that flashing a module always leaves
    // it enabled:
    //   * installer.sh only removes $NVBASE/modules/$MODID/{disable,remove}
    //     (the active dir) and never touches $MODPATH, so a zip shipping a
    //     root level "disable" file would carry it into the active dir when
    //     the payload is activated on the next boot.
    //   * if customize.sh aborted before installer.sh reached that cleanup,
    //     markers left over from a previous install survive here and
    //     handle_updated_modules() then re-creates them for the new version.
    for dir in [module_dir.as_path(), updated_dir.as_path()] {
        for marker in [defs::DISABLE_FILE_NAME, defs::REMOVE_FILE_NAME] {
            let path = dir.join(marker);
            if path.exists() {
                if let Err(e) = std::fs::remove_file(&path) {
                    warn!("Failed to remove {}: {e}", path.display());
                } else {
                    info!("Cleared stale marker: {}", path.display());
                }
            }
        }
    }

    copy(
        updated_dir.join("module.prop"),
        module_dir.join("module.prop"),
    )?;
    ensure_file_exists(module_dir.join(UPDATE_FILE_NAME))?;

    // Create symlink for metamodule
    if is_metamodule {
        println!("- Creating metamodule symlink");
        metamodule::ensure_symlink(&module_dir)?;
    }

    println!("- Module installed successfully!");
    info!("Module {module_id} installed successfully!");

    Ok(())
}

pub fn install_module(zip: &str) -> Result<()> {
    ksucalls::ensure_uapi_version_matched()?;

    let result = install_module_to_system(zip);
    if let Err(ref e) = result {
        println!("- Error: {e}");
    } else if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }
    result
}

pub fn undo_uninstall_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    // Remove the remove mark
    let remove_file = module_path.join(defs::REMOVE_FILE_NAME);
    if remove_file.exists() {
        std::fs::remove_file(&remove_file)
            .with_context(|| format!("Failed to delete remove file for module '{id}'"))?;
        info!("Removed the remove mark for module {id}");
    }

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn uninstall_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    // Mark for removal
    let remove_file = module_path.join(defs::REMOVE_FILE_NAME);
    File::create(remove_file).with_context(|| "Failed to create remove file")?;

    info!("Module {id} marked for removal");

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn run_action(id: &str) -> Result<()> {
    validate_module_id(id)?;
    ksucalls::ensure_uapi_version_matched()?;

    let action_script_path = format!("/data/adb/modules/{id}/action.sh");
    exec_script(&action_script_path, true)
}

pub fn enable_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    let disable_path = module_path.join(defs::DISABLE_FILE_NAME);
    if disable_path.exists() {
        std::fs::remove_file(&disable_path).with_context(|| {
            format!("Failed to remove disable file: {}", disable_path.display())
        })?;
        info!("Module {id} enabled");
    }

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn disable_module(id: &str) -> Result<()> {
    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    let disable_path = module_path.join(defs::DISABLE_FILE_NAME);
    ensure_file_exists(disable_path)?;

    // A user-disabled module must survive the Zygisk self-heal at next boot,
    // which re-enables anything carrying the abort signature (`disable` +
    // `.abort_msg`). Dropping a leftover `.abort_msg` here marks this disable
    // as an explicit user decision. The marker is Zygisk-owned; no KernelSU
    // module system uses it.
    let abort_path = module_path.join(defs::ABORT_MSG_FILE_NAME);
    if abort_path.is_file() {
        let _ = std::fs::remove_file(&abort_path);
    }

    info!("Module {id} disabled");

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn disable_all_modules() -> Result<()> {
    mark_all_modules(defs::DISABLE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }
    Ok(())
}

pub fn uninstall_all_modules() -> Result<()> {
    info!("Uninstalling all modules");
    mark_all_modules(defs::REMOVE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }
    Ok(())
}

fn mark_all_modules(flag_file: &str) -> Result<()> {
    // we assume the module dir is already mounted
    let dir = std::fs::read_dir(defs::MODULE_DIR)?;
    for entry in dir.flatten() {
        let path = entry.path();
        let flag = path.join(flag_file);
        if let Err(e) = ensure_file_exists(flag) {
            warn!("Failed to mark module: {}: {e}", path.display());
        }
    }

    Ok(())
}

/// Parse module.prop content into a key-value map.
///
/// Uses plain `key=value` line parsing (split on the first '='), which is
/// more tolerant than java_properties for values containing spaces, parens or
/// commas (e.g. `version=1.3.4 (746-d1b76b3-release)`, `author=5ec1cff, Nullptr`).
fn parse_module_prop(content: &[u8]) -> HashMap<String, String> {
    let text = String::from_utf8_lossy(content);
    let mut prop_map: HashMap<String, String> = HashMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        prop_map.insert(key.trim().to_string(), value.trim().to_string());
    }
    prop_map
}

/// Read module.prop from the given module path and return as a HashMap
pub fn read_module_prop(module_path: &Path) -> Result<HashMap<String, String>> {
    let module_prop = module_path.join("module.prop");
    ensure!(
        module_prop.exists(),
        "module.prop not found in {}",
        module_path.display()
    );

    let content = std::fs::read(&module_prop)
        .with_context(|| format!("Failed to read module.prop: {}", module_prop.display()))?;

    Ok(parse_module_prop(&content))
}

/// Resolve a module icon path to an absolute on-disk path
fn resolve_module_icon_path(
    module_prop_map: &mut HashMap<String, String>,
    key: &str,
    module_path: &Path,
) {
    if let Some(icon_value) = module_prop_map.get(key) {
        let icon_value = icon_value.trim();
        if icon_value.is_empty() {
            return;
        }
        let path = std::path::Path::new(icon_value);
        if path.is_absolute() {
            log::warn!(
                "Rejected {} with absolute path for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                icon_value
            );
            return;
        }
        let has_parent = path
            .components()
            .any(|c| matches!(c, std::path::Component::ParentDir));
        if has_parent {
            log::warn!(
                "Rejected {} with parent traversal for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                icon_value
            );
            return;
        }
        let candidate = module_path.join(path);
        if candidate.exists() && candidate.is_file() {
            if let Some(s) = candidate.to_str() {
                module_prop_map.insert(key.to_owned(), s.to_string());
            }
        } else {
            log::debug!(
                "{} not found for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                candidate.display()
            );
        }
    }
}

fn list_module(path: &str) -> Vec<HashMap<String, String>> {
    // Load all module configs once to minimize I/O overhead
    let all_configs = match crate::module_config::get_all_module_configs() {
        Ok(configs) => configs,
        Err(e) => {
            warn!("Failed to load module configs: {e}");
            HashMap::new()
        }
    };

    // first check enabled modules
    let dir = std::fs::read_dir(path);
    let Ok(dir) = dir else {
        return Vec::new();
    };

    let mut modules: Vec<HashMap<String, String>> = Vec::new();

    for entry in dir.flatten() {
        let path = entry.path();
        info!("path: {}", path.display());

        if !path.join("module.prop").exists() {
            continue;
        }

        let mut module_prop_map = match read_module_prop(&path) {
            Ok(prop) => prop,
            Err(e) => {
                warn!("Failed to read module.prop for {}: {e}", path.display());
                continue;
            }
        };

        // If id is missing or empty, use directory name as fallback
        if !module_prop_map.contains_key("id") || module_prop_map["id"].is_empty() {
            if let Some(id) = entry.file_name().to_str() {
                info!("Use dir name as module id: {id}");
                module_prop_map.insert("id".to_owned(), id.to_owned());
            } else {
                info!("Failed to get module id from dir name");
                continue;
            }
        }

        // Always materialize name/author/version/versionCode/description so the
        // manager never renders "Unknown" for a module whose module.prop omits
        // them (or had them rewritten/truncated by customize.sh). The fallback
        // name is computed up-front: calling module_prop_map.get() inside an
        // entry() closure would conflict with the mutable borrow entry() holds.
        let fallback_name = module_prop_map.get("id").cloned().unwrap_or_default();
        module_prop_map
            .entry("name".to_owned())
            .or_insert(fallback_name);
        module_prop_map.entry("author".to_owned()).or_default();
        module_prop_map.entry("version".to_owned()).or_default();
        module_prop_map
            .entry("versionCode".to_owned())
            .or_insert_with(|| "0".to_owned());
        module_prop_map.entry("description".to_owned()).or_default();

        // Add enabled, update, remove, web, action flags
        let enabled = !path.join(defs::DISABLE_FILE_NAME).exists();
        let update = path.join(defs::UPDATE_FILE_NAME).exists();
        let remove = path.join(defs::REMOVE_FILE_NAME).exists();
        let web = path.join(defs::MODULE_WEB_DIR).exists();
        let action = path.join(defs::MODULE_ACTION_SH).exists();
        let need_mount = path.join("system").exists() && !path.join("skip_mount").exists();

        module_prop_map.insert("enabled".to_owned(), enabled.to_string());
        module_prop_map.insert("update".to_owned(), update.to_string());
        module_prop_map.insert("remove".to_owned(), remove.to_string());
        module_prop_map.insert("web".to_owned(), web.to_string());
        module_prop_map.insert("action".to_owned(), action.to_string());
        module_prop_map.insert("mount".to_owned(), need_mount.to_string());

        resolve_module_icon_path(&mut module_prop_map, "actionIcon", &path);
        resolve_module_icon_path(&mut module_prop_map, "webuiIcon", &path);

        // Apply module config overrides and extract managed features
        if let Some(module_id) = module_prop_map.get("id")
            && let Some(config) = all_configs.get(module_id.as_str())
        {
            // Apply override.description
            if let Some(desc) = config.get("override.description") {
                module_prop_map.insert("description".to_owned(), desc.clone());
            }

            // Extract managed features from manage.* config entries
            let managed_features: Vec<String> = config
                .iter()
                .filter_map(|(k, v)| {
                    if k.starts_with("manage.") && crate::module_config::parse_bool_config(v) {
                        k.strip_prefix("manage.")
                            .map(std::string::ToString::to_string)
                    } else {
                        None
                    }
                })
                .collect();

            if !managed_features.is_empty() {
                module_prop_map.insert("managedFeatures".to_owned(), managed_features.join(","));
            }
        }

        modules.push(module_prop_map);
    }

    modules
}

pub fn list_modules() -> Result<()> {
    let modules = list_module(defs::MODULE_DIR);
    println!("{}", serde_json::to_string_pretty(&modules)?);
    Ok(())
}

/// Get all managed features from active modules
/// Modules declare managed features via config system (manage.<feature>=true)
/// Returns: HashMap<ModuleId, Vec<ManagedFeature>>
pub fn get_managed_features() -> Result<HashMap<String, Vec<String>>> {
    let mut managed_features_map: HashMap<String, Vec<String>> = HashMap::new();

    foreach_active_module(|module_path| {
        // Get module ID
        let Some(module_id) = module_path.file_name().and_then(|n| n.to_str()) else {
            warn!(
                "Failed to get module id from path: {}",
                module_path.display()
            );
            return Ok(());
        };

        // Read module config
        let config = match crate::module_config::merge_configs(module_id) {
            Ok(c) => c,
            Err(e) => {
                warn!("Failed to merge configs for module '{module_id}': {e}");
                return Ok(()); // Skip this module
            }
        };

        // Extract manage.* config entries
        let mut feature_list = Vec::new();
        for (key, value) in &config {
            if key.starts_with("manage.") {
                // Parse feature name
                if let Some(feature_name) = key.strip_prefix("manage.")
                    && crate::module_config::parse_bool_config(value)
                {
                    feature_list.push(feature_name.to_string());
                }
            }
        }

        if !feature_list.is_empty() {
            managed_features_map.insert(module_id.to_string(), feature_list);
        }

        Ok(())
    })?;

    Ok(managed_features_map)
}
