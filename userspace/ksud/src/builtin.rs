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
/// Where the bundled trees live. **Not** a module directory: ksud runs their
/// stage scripts and applies their sepolicy rules itself, so nothing of ours
/// appears under /data/adb/modules and there is no module for a user to delete,
/// disable or have resurrected after a reboot.
pub const ROOT: &str = "/data/adb/ksu/builtin";

/// 内置 TEE/TS 引擎的总开关（文件存在 = 下一轮开机不注入）。
///
/// 引擎是纯用户态注入（`tricky_store` 的 daemon + `TA_enhanced` 的阶段脚本），
/// **不是**内核模块，所以关掉它不影响 root：LKM 照常加载，su、模块、隐藏全部照常，
/// 只是 keystore2 里那条伪造的证明链不再出现，设备回到真实硬件证明。
///
/// 只在开机路径上读，所以改完必须重启才生效。
pub const TEE_ENGINE_SWITCH: &str = ".tee_engine_off";

/// 唯一作用就是伪造证明链的两棵内置树；关开关时它们既不安装也不执行。
pub const TEE_ENGINE_MODULES: [&str; 2] = ["tricky_store", "TA_enhanced"];

fn tee_engine_switch_path() -> PathBuf {
    Path::new(defs::WORKING_DIR).join(TEE_ENGINE_SWITCH)
}

pub fn tee_engine_enabled() -> bool {
    !tee_engine_switch_path().exists()
}

/// 打开/关闭内置 TEE/TS 引擎，返回生效后的状态。
///
/// 关闭时顺手把已经落地的树删掉：只写开关文件的话磁盘上那份还在，任何"直接跑树里
/// 脚本"的路径都会让开关形同虚设（阶段脚本、sepolicy 规则、TA 的 webroot 都在树里）。
pub fn set_tee_engine_enabled(enabled: bool) -> Result<bool> {
    let flag = tee_engine_switch_path();
    if enabled {
        match fs::remove_file(&flag) {
            Ok(()) => info!("built-in TEE/TS engine enabled"),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(error).with_context(|| format!("删除 {}", flag.display()));
            }
        }
        return Ok(true);
    }

    if let Some(parent) = flag.parent() {
        fs::create_dir_all(parent).with_context(|| format!("创建 {}", parent.display()))?;
    }
    fs::write(&flag, b"1\n").with_context(|| format!("写入 {}", flag.display()))?;
    for id in TEE_ENGINE_MODULES {
        let tree = Path::new(ROOT).join(id);
        if !tree.exists() {
            continue;
        }
        match fs::remove_dir_all(&tree) {
            Ok(()) => info!("removed built-in tree {}", tree.display()),
            Err(error) => warn!("failed to remove {}: {error:#}", tree.display()),
        }
    }
    Ok(false)
}

/// 关掉开关时把某棵树清掉（安装/执行两条路径都要能自愈）。
fn remove_tree_if_tee_disabled(module_id: &str) {
    if tee_engine_enabled() || !TEE_ENGINE_MODULES.contains(&module_id) {
        return;
    }
    let tree = Path::new(ROOT).join(module_id);
    if !tree.exists() {
        return;
    }
    match fs::remove_dir_all(&tree) {
        Ok(()) => info!("built-in TEE/TS engine off, removed {}", tree.display()),
        Err(error) => warn!("failed to remove {}: {error:#}", tree.display()),
    }
}

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
        // 用户关掉了内置 TEE/TS 引擎：这两棵树既不安装，存量也要清掉，
        // 否则"磁盘上还在"就等于开关没生效。其它内置树照常安装。
        if !tee_engine_enabled() && TEE_ENGINE_MODULES.contains(&module_id.as_str()) {
            remove_tree_if_tee_disabled(module_id);
            continue;
        }
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
    let module_dir = Path::new(ROOT).join(module_id);

    // Migration: builds that shipped this tree as a module left it under
    // /data/adb/modules/<id>. Remove that copy once so the device converges on a
    // single, module-free layout.
    let legacy = Path::new(defs::MODULE_DIR).join(module_id);
    if legacy.exists() {
        match fs::remove_dir_all(&legacy) {
            Ok(()) => info!("removed the module-era copy at {}", legacy.display()),
            Err(error) => warn!("failed to remove {}: {error}", legacy.display()),
        }
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

/// Run `<root>/<id>/<stage>.sh` for every staged built-in tree.
///
/// Same launcher the module scripts use (`module::exec_script`), so the shell,
/// environment, cgroup and process-group handling stay identical; the only
/// difference is where the tree lives.
pub fn exec_stage_script(stage: &str, wait: crate::module::ScriptWait) -> Result<()> {
    let root = Path::new(ROOT);
    if !root.is_dir() {
        return Ok(());
    }
    let mut ids: Vec<_> = fs::read_dir(root)?
        .filter_map(Result::ok)
        .filter(|entry| entry.path().is_dir())
        .map(|entry| entry.path())
        .collect();
    ids.sort();

    for dir in ids {
        // 开关关掉后树应该已经被删了；这里再挡一层，防"删失败但仍然去跑"。
        if let Some(id) = dir
            .file_name()
            .and_then(|name| name.to_str())
            .filter(|id| !tee_engine_enabled() && TEE_ENGINE_MODULES.contains(id))
        {
            debug!("skip built-in stage script of disabled TEE/TS tree: {id}");
            continue;
        }
        let script = dir.join(format!("{stage}.sh"));
        if !script.is_file() {
            continue;
        }
        info!("built-in stage script: {}", script.display());
        if let Err(error) = crate::module::exec_script(&script, wait) {
            warn!("built-in {stage} script failed ({}): {error:#}", script.display());
        }
    }
    Ok(())
}

/// Apply `<root>/<id>/sepolicy.rule` for every staged tree.
pub fn load_sepolicy_rules() -> Result<()> {
    let root = Path::new(ROOT);
    if !root.is_dir() {
        return Ok(());
    }
    for entry in fs::read_dir(root)?.filter_map(Result::ok) {
        // 先取出 OsString 再借出 &str：`file_name().to_str()` 直接链在临时值上
        // 会把借用挂到已经释放的 OsString 上（E0716）。
        let file_name = entry.file_name();
        if let Some(id) = file_name
            .to_str()
            .filter(|id| !tee_engine_enabled() && TEE_ENGINE_MODULES.contains(id))
        {
            debug!("skip built-in policy of disabled TEE/TS tree: {id}");
            continue;
        }
        let rule = entry.path().join("sepolicy.rule");
        if !rule.is_file() {
            continue;
        }
        info!("load built-in policy: {}", rule.display());
        if crate::sepolicy::apply_file(&rule).is_err() {
            warn!("failed to apply {}", rule.display());
        }
    }
    Ok(())
}
