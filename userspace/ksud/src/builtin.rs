//! Modules bundled inside the xudc binary itself.
//!
//! `userspace/ksud/builtin/` is embedded at compile time (`assets::BuiltinAsset`).
//! On the kernel-state boot path the trees are materialised into
//! `/data/adb/modules/<id>/` before the module set is enumerated, so the normal
//! pipeline in `init_event.rs` (sepolicy rules, preinit rc, `post-fs-data.sh`)
//! runs them from the very first boot after an xudc update.
//!
//! This is deliberately *not* the removed encrypted store: no blob under
//! `/data/adb/ksu/cfg` and no repair/status JSON. `/dev/.xudc_hidden` is not used
//! for the module trees either — note that the TA_enhanced payload still keeps a
//! live `/dev/.xudc_hidden/bootparams` overlay of its own (see `bootstate.rs`),
//! which is unrelated to how the module gets installed. The installed module is a
//! plain module directory the user can inspect, disable or edit like any other
//! module.

use anyhow::{Context, Result, anyhow, ensure};
use log::{debug, info, warn};
use std::{
    collections::BTreeMap,
    ffi::OsString,
    fs::{self, File, Permissions},
    io::Write,
    os::unix::fs::PermissionsExt,
    path::{Component, Path, PathBuf},
};

use crate::{assets, defs, module, utils};

/// Stamp file written into every module this installer owns.
///
/// Its presence and body are the only ownership marker: the manager never
/// writes it, so a stamp mismatch (or a missing stamp on a freshly flashed
/// external module with the same id) is the update path.
const STAMP_FILE_NAME: &str = ".xudc_builtin_stamp";

/// Layout version of the stamp body and the materialisation rules.
///
/// Bump it when the file layout or the permission rules change so an installed
/// copy is refreshed once even if `VERSION_CODE` happens to be identical.
const STAMP_VERSION: u32 = 1;

/// One file embedded under `builtin/`, split into its module id and the path
/// relative to the module root.
struct BuiltinFile {
    /// Full embedded name, e.g. `tricky_store/bin/arm64-v8a/daemon`.
    asset: String,
    /// Module-relative path, e.g. `bin/arm64-v8a/daemon`.
    relative: PathBuf,
}

/// Install or refresh every module embedded under `builtin/`.
///
/// The common path is a no-op: each module directory carries a stamp file and
/// only a mismatching stamp (first boot after an xudc update, or a module that
/// was never installed) rewrites files.
///
/// # Errors
///
/// Returns the first per-module failure after trying every module. Callers must
/// log it and continue booting: bundled modules are auxiliary and an install
/// failure must never take the boot down.
pub fn install_builtin_modules() -> Result<()> {
    // Mount state (<5.10) hands the whole module tree to Magisk; `/data/adb/modules`
    // there is not ours to populate.
    if utils::is_mount_state() {
        info!("mount state, skip built-in modules");
        return Ok(());
    }

    // Safe mode exists precisely so a broken device boots without modules;
    // installing bundled modules behind the user's back would defeat it.
    if utils::is_safe_mode() {
        warn!("safe mode, skip built-in modules");
        return Ok(());
    }

    let modules = group_builtin_assets()?;
    if modules.is_empty() {
        info!("no built-in modules bundled");
        return Ok(());
    }

    let mut first_error: Option<anyhow::Error> = None;
    for (module_id, files) in &modules {
        if let Err(error) = install_module(module_id, files) {
            warn!("install built-in module {module_id} failed: {error:#}");
            first_error
                .get_or_insert_with(|| error.context(format!("built-in module {module_id}")));
        }
    }

    first_error.map_or_else(|| Ok(()), Err)
}

/// Group the embedded files by their first path component, the module id.
///
/// Top-level files such as `builtin/README.md` have no module component and are
/// skipped: they document the directory, they are not part of any module.
fn group_builtin_assets() -> Result<BTreeMap<String, Vec<BuiltinFile>>> {
    let mut modules: BTreeMap<String, Vec<BuiltinFile>> = BTreeMap::new();

    for asset in assets::list_builtin_assets() {
        let Some((module_id, relative)) = asset.split_once('/') else {
            continue;
        };

        module::validate_module_id(module_id)
            .with_context(|| format!("invalid module id in built-in asset '{asset}'"))?;
        let relative = relative_module_path(relative)
            .with_context(|| format!("unsafe path in built-in asset '{asset}'"))?;

        modules
            .entry(module_id.to_string())
            .or_default()
            .push(BuiltinFile { asset, relative });
    }

    Ok(modules)
}

/// Install one embedded module tree into `/data/adb/modules/<id>/`.
fn install_module(module_id: &str, files: &[BuiltinFile]) -> Result<()> {
    let module_dir = Path::new(defs::MODULE_DIR).join(module_id);

    // A user-disabled module is left exactly as it is: the manager toggle writes
    // `disable`, and re-creating files around that marker would silently
    // resurrect something the user switched off.
    if module_dir.join(defs::DISABLE_FILE_NAME).exists() {
        info!("built-in module {module_id} is disabled, skip");
        return Ok(());
    }

    let stamp_body = builtin_stamp(files.len());
    let stamp_path = module_dir.join(STAMP_FILE_NAME);

    // Common path: exactly one small file read. A matching stamp means this
    // build already wrote the whole tree, so user edits made afterwards are
    // intentionally left untouched until the next update.
    if fs::read_to_string(&stamp_path).is_ok_and(|stamp| stamp.trim() == stamp_body) {
        debug!("built-in module {module_id} is up to date");
        return Ok(());
    }

    // Update path: missing stamp or a different xudc build. Every embedded file
    // is (re)written, which overwrites local edits inside the module; that is
    // the bundled-update contract. Nothing is ever deleted: files the user
    // added, and files an older build shipped but this one no longer does, stay.
    info!(
        "installing built-in module {module_id} ({} files) into {}",
        files.len(),
        module_dir.display()
    );
    for file in files {
        let destination = module_dir.join(&file.relative);
        // Components were validated at grouping time; assert again because this
        // is the join that turns them into a path on disk.
        ensure!(
            destination.starts_with(&module_dir),
            "built-in asset '{}' would escape {}",
            file.asset,
            module_dir.display()
        );
        let data = assets::get_builtin_asset(&file.asset)?;
        write_atomic(&destination, &data, builtin_file_mode(&file.relative))?;
    }

    // The stamp is the commit point: if anything above fails (or the process
    // dies), the stamp stays absent/stale and the next boot retries the whole
    // module instead of treating a half-written tree as installed.
    write_atomic(&stamp_path, stamp_body.as_bytes(), 0o644)?;
    info!("built-in module {module_id} installed");
    Ok(())
}

/// Stamp body identifying the embedded tree of this build.
///
/// `VERSION_CODE` is the git commit count baked in at build time, so it moves
/// whenever the committed `builtin/` tree changes. The file count makes a
/// hand-edited stamp look different too, and costs nothing because the grouped
/// list is already in memory.
fn builtin_stamp(file_count: usize) -> String {
    format!("v{STAMP_VERSION} {} {file_count}", defs::VERSION_CODE)
}

/// Validate one module-relative embedded path and turn it into a `PathBuf`.
///
/// The embedded list is a compile-time constant, but the destination is joined
/// at runtime, so traversal (`..`), absolute paths and Windows prefixes are
/// rejected explicitly instead of trusted.
fn relative_module_path(relative: &str) -> Result<PathBuf> {
    ensure!(!relative.is_empty(), "empty built-in asset path");
    let path = Path::new(relative);
    ensure!(
        path.components()
            .all(|component| matches!(component, Component::Normal(_))),
        "built-in asset path '{relative}' must stay inside its module directory"
    );
    Ok(path.to_path_buf())
}

/// POSIX mode for one module-relative file.
///
/// Mirrors the executable bits the upstream module zips carry: shell scripts,
/// everything under `bin/` / `lib/`, and extensionless files in the module root
/// (`daemon`, `inject`, `supervisor`).
fn builtin_file_mode(relative: &Path) -> u32 {
    let shell_script = relative
        .extension()
        .is_some_and(|extension| extension.eq_ignore_ascii_case("sh"));
    let native_payload = relative.starts_with("bin") || relative.starts_with("lib");
    let root_program = relative.components().count() == 1 && relative.extension().is_none();

    if shell_script || native_payload || root_program {
        0o755
    } else {
        0o644
    }
}

/// Write `contents` to a hidden temp file next to `dest`, then rename it into
/// place, so a crash can never leave a truncated file at the real path.
fn write_atomic(dest: &Path, contents: &[u8], mode: u32) -> Result<()> {
    let parent = dest
        .parent()
        .ok_or_else(|| anyhow!("built-in asset path has no parent: {}", dest.display()))?;
    fs::create_dir_all(parent).with_context(|| format!("Failed to create {}", parent.display()))?;

    let file_name = dest
        .file_name()
        .ok_or_else(|| anyhow!("built-in asset path has no file name: {}", dest.display()))?;
    // OsString::push keeps this a path component, not string concatenation.
    let mut temp_name = OsString::from(".");
    temp_name.push(file_name);
    temp_name.push(".xudc.tmp");
    let temp_path = dest.with_file_name(temp_name);

    {
        let mut file = File::create(&temp_path)
            .with_context(|| format!("Failed to create {}", temp_path.display()))?;
        file.write_all(contents)
            .with_context(|| format!("Failed to write {}", temp_path.display()))?;
        file.sync_all()
            .with_context(|| format!("Failed to sync {}", temp_path.display()))?;
        file.set_permissions(Permissions::from_mode(mode))
            .with_context(|| format!("Failed to chmod {}", temp_path.display()))?;
    }

    fs::rename(&temp_path, dest).with_context(|| {
        format!(
            "Failed to rename {} -> {}",
            temp_path.display(),
            dest.display()
        )
    })?;
    Ok(())
}
