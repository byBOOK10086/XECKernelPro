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

use anyhow::{Context, Result, anyhow, bail, ensure};
use log::{debug, info, warn};
use std::{
    collections::BTreeMap,
    ffi::OsString,
    fs::{self, File, Permissions},
    io::Write,
    os::unix::{fs::PermissionsExt, process::CommandExt},
    path::{Component, Path, PathBuf},
    time::{Duration, Instant},
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

/// What one install pass did.
#[derive(Debug, Default)]
pub struct InstallReport {
    /// Bundled module trees found inside this xudc build.
    pub modules: usize,
    /// Trees that were actually (re)written. A matching stamp with every file
    /// still present is a no-op, so this is 0 on an untouched device.
    pub rewritten: usize,
}

/// Stage of the bundled engine that [`start_tee_engine`] launched.
#[derive(Debug)]
pub struct ScriptRun {
    /// Module id the script belongs to, e.g. `tricky_store`.
    pub module: String,
    /// Stage name without the `.sh` suffix, e.g. `post-fs-data`.
    pub stage: String,
    /// Whether the script was spawned. A non-zero exit *inside* the script is
    /// not visible here; the manager reads the engine state afterwards.
    pub spawned: bool,
}

/// Result of [`start_tee_engine`]; the CLI prints it as `key=value` lines.
#[derive(Debug)]
pub struct StartReport {
    /// Runtime tree that holds the engine payload.
    pub engine_dir: String,
    /// What the install pass did before the scripts ran.
    pub install: InstallReport,
    /// Scripts launched, in the order they were launched.
    pub scripts: Vec<ScriptRun>,
    /// `TEESimulator` pid after the bounded wait; 0 when it never showed up.
    pub engine_pid: i32,
    /// Whether keystore2's maps already contain the injected library.
    pub injected: bool,
}

/// How long one stage script may take before `builtin start` moves on.
///
/// Every bundled script backgrounds its long-running work (daemon, watchdogs,
/// 45s probe), so this budget only covers the foreground part of the script.
const SCRIPT_BUDGET: Duration = Duration::from_secs(45);

/// How long [`start_tee_engine`] waits for the engine process / injection.
const ENGINE_WAIT: Duration = Duration::from_secs(15);

/// How long a previously running engine gets to disappear.
const STOP_BUDGET: Duration = Duration::from_secs(8);

/// Poll interval for the `/proc` probes below.
const POLL_STEP_MS: u64 = 250;

pub fn install_builtin_modules() -> Result<()> {
    install_builtin_modules_impl(false).map(|_| ())
}

/// Install the bundled trees even where the boot path would have skipped them.
///
/// The mount-state and safe-mode gates only mean "never populate the module set
/// behind the user's back at boot"; an explicit `xudc builtin install` from the
/// manager is the opposite situation, so it bypasses both.
///
/// # Errors
///
/// Returns the first per-module failure after trying every module.
pub fn install_builtin_modules_forced() -> Result<InstallReport> {
    install_builtin_modules_impl(true)
}

fn install_builtin_modules_impl(forced: bool) -> Result<InstallReport> {
    // Mount state (<5.10) hands the whole module tree to Magisk; `/data/adb/modules`
    // there is not ours to populate.
    if !forced && utils::is_mount_state() {
        info!("mount state, skip built-in modules");
        return Ok(InstallReport::default());
    }

    // Safe mode exists precisely so a broken device boots without modules;
    // installing bundled modules behind the user's back would defeat it.
    if !forced && utils::is_safe_mode() {
        warn!("safe mode, skip built-in modules");
        return Ok(InstallReport::default());
    }

    let modules = group_builtin_assets()?;
    if modules.is_empty() {
        info!("no built-in modules bundled");
        return Ok(InstallReport::default());
    }

    let mut report = InstallReport {
        modules: modules.len(),
        rewritten: 0,
    };
    let mut first_error: Option<anyhow::Error> = None;
    for (module_id, files) in &modules {
        // 用户关掉了内置 TEE/TS 引擎：这两棵树既不安装，存量也要清掉，
        // 否则"磁盘上还在"就等于开关没生效。其它内置树照常安装。
        if !tee_engine_enabled() && TEE_ENGINE_MODULES.contains(&module_id.as_str()) {
            remove_tree_if_tee_disabled(module_id);
            continue;
        }
        match install_module(module_id, files) {
            Ok(true) => report.rewritten += 1,
            Ok(false) => {}
            Err(error) => {
                warn!("install built-in module {module_id} failed: {error:#}");
                first_error
                    .get_or_insert_with(|| error.context(format!("built-in module {module_id}")));
            }
        }
    }

    first_error.map_or(Ok(report), Err)
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

/// Install one embedded module tree into `/data/adb/ksu/builtin/<id>/`.
///
/// Returns whether the tree was (re)written. A stamp alone is not enough to skip
/// the write: a cleaner that deletes files but leaves the stamp would otherwise
/// make the tree permanently "installed but broken" - exactly the state where
/// the engine script fails while every check claims it is up to date.
fn install_module(module_id: &str, files: &[BuiltinFile]) -> Result<bool> {
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

    // Common path: a small stamp read plus one stat per embedded file. A matching
    // stamp with every file present means this build already wrote the whole
    // tree, so user edits made afterwards are intentionally left untouched until
    // the next update.
    let stamp_ok = fs::read_to_string(&stamp_path).is_ok_and(|stamp| stamp.trim() == stamp_body);
    if stamp_ok && files.iter().all(|file| module_dir.join(&file.relative).is_file()) {
        debug!("built-in module {module_id} is up to date");
        return Ok(false);
    }

    // Update path: missing stamp, missing file or a different xudc build. Every
    // embedded file is (re)written, which overwrites local edits inside the
    // module; that is the bundled-update contract. Nothing is ever deleted: files
    // the user added, and files an older build shipped but this one no longer
    // does, stay.
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
    Ok(true)
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

/// `builtin start`: materialise the bundled engine and start it **now**.
///
/// This is what the manager's one-click repair calls. It deliberately does not
/// go through `init_event::run_stage`: that path only exists at boot and is
/// gated on mount state / Magisk / safe mode, while this one is an explicit
/// request from the user and has to work in the running session, without a
/// reboot and without a matching kernel.
///
/// # Errors
///
/// Fails when the engine switch is off, the payload is not embedded, or another
/// root solution's module already owns the engine.
pub fn start_tee_engine() -> Result<StartReport> {
    ensure!(
        tee_engine_enabled(),
        "内置 TEE/TS 引擎总开关是关的：先执行 `xudc builtin tee on`，再修复"
    );

    if let Some(dir) = external_engine_dir() {
        bail!(
            "外部模块 {} 正在接管引擎：内置引擎会让位（不抢同一个 keystore2）。\
             先禁用它，或直接重刷/重启让它的 service.sh 重新拉起",
            dir.display()
        );
    }

    let install = install_builtin_modules_forced().context("materialise built-in modules")?;

    let engine_dir = Path::new(ROOT).join("tricky_store");
    ensure!(
        engine_dir.join("service.sh").is_file(),
        "内置引擎没有落盘（{} 不存在）：这个 xudc 构建里没有编进内置载荷",
        engine_dir.display()
    );

    // 重入安全：先收掉在跑的那一套，否则下面再拉一次 supervisor 就是两个注入器
    // 抢同一个 keystore2。supervisor 会重启它的 daemon，所以必须先杀 supervisor。
    stop_engine();

    // 尽力而为：内核不是本项目时这条一定失败，但引擎本身不依赖它
    // （KernelSU 域的 su 是 permissive + allow-all，注入走的是 ptrace 而不是这些规则）。
    if let Err(error) = load_sepolicy_rules() {
        warn!("apply built-in sepolicy failed (engine still starts): {error:#}");
    }

    let mut scripts = Vec::new();
    for (module, stage) in [
        // 顺序与开机**故意不同**：TA 的 post-fs-data/service 都要读
        // /data/adb/tricky_store（keybox 兼容副本、target.txt），而那个目录要等
        // tricky_store 的 service.sh 才建出来；新设备上按开机顺序跑会白跑一轮。
        ("tricky_store", "service"),
        ("TA_enhanced", "post-fs-data"),
        ("TA_enhanced", "service"),
    ] {
        let script = Path::new(ROOT).join(module).join(format!("{stage}.sh"));
        let spawned = if script.is_file() {
            match spawn_stage_script(&script, module, Instant::now() + SCRIPT_BUDGET) {
                Ok(()) => true,
                Err(error) => {
                    warn!("built-in {module}/{stage} failed to launch: {error:#}");
                    false
                }
            }
        } else {
            warn!("built-in script missing: {}", script.display());
            false
        };
        info!("built-in {module}/{stage} spawned={spawned}");
        scripts.push(ScriptRun {
            module: module.to_string(),
            stage: stage.to_string(),
            spawned,
        });
    }

    let engine_pid = wait_for_comm("TEESimulator", ENGINE_WAIT).unwrap_or(0);
    let injected = wait_for_injection(ENGINE_WAIT);

    Ok(StartReport {
        engine_dir: engine_dir.display().to_string(),
        install,
        scripts,
        engine_pid,
        injected,
    })
}

/// Spawn one bundled stage script and wait for its foreground part.
///
/// `module::exec_script` always launches our own busybox, which only lands on disk
/// after `post-fs-data` extracted the bin assets. A device whose boot pipeline
/// bailed at an early gate (Magisk leftover / safe mode) has no
/// `/data/adb/ksu/bin/busybox`, so going through it would mean "repair installs
/// the engine and then starts nothing". Fall back to the system shell there.
fn spawn_stage_script(script: &Path, module_id: &str, deadline: Instant) -> Result<()> {
    let busybox = crate::assets::busybox_path();
    if Path::new(&busybox).is_file() {
        return crate::module::exec_script(script, crate::module::ScriptWait::Until(deadline));
    }

    info!(
        "busybox missing at {busybox}, spawning {} with /system/bin/sh",
        script.display()
    );
    let mut command = std::process::Command::new("/system/bin/sh");
    command
        .arg(script)
        .current_dir(script.parent().unwrap_or_else(|| Path::new("/")))
        .envs(crate::module::get_common_script_envs(Some(module_id)))
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    // SAFETY: 子进程里只做两件异步信号安全的事 —— 脱离进程组（su 退出时不连带 HUP）
    // 和写 cgroup 文件，与开机路径 `module::exec_script` 的 pre_exec 完全一致；
    // 出错通过返回 io::Error 处理，不在子进程里分配或加锁。
    unsafe {
        command.pre_exec(|| {
            crate::utils::detach_process_group(true);
            crate::utils::switch_cgroups();
            Ok(())
        });
    }
    let mut child = command
        .spawn()
        .with_context(|| format!("spawn {}", script.display()))?;
    while Instant::now() < deadline {
        match child.try_wait() {
            Ok(Some(_)) => return Ok(()),
            Ok(None) => std::thread::sleep(Duration::from_millis(100)),
            Err(error) => return Err(anyhow!("wait {}: {error}", script.display())),
        }
    }
    debug!(
        "stage script {} still running after its budget",
        script.display()
    );
    Ok(())
}

/// Another root solution's engine module, if one owns the injection surface.
///
/// The bundled `service.sh` already stands down in this case; `builtin start`
/// has to check the same thing *before* it kills the running engine, otherwise
/// repairing would leave the device with no engine at all.
fn external_engine_dir() -> Option<PathBuf> {
    let modules = Path::new(defs::MODULE_DIR);
    [modules.join("tricky_store"), modules.join(".tricky_store")]
        .into_iter()
        .find(|dir| {
            dir.join("module.prop").is_file()
                && !dir.join("disable").exists()
                && !dir.join("remove").exists()
        })
}

/// Kill a previously running engine so a second supervisor cannot race us.
fn stop_engine() {
    let supervisor = pid_by_comm("supervisor");
    if supervisor.is_none() && pid_by_comm("TEESimulator").is_none() {
        return;
    }
    debug!("stopping the running engine (supervisor pid {supervisor:?})");
    // supervisor restarts its daemon: kill it first, then the daemon it spawned.
    kill_comm("supervisor");
    kill_comm("TEESimulator");
    let deadline = Instant::now() + STOP_BUDGET;
    while Instant::now() < deadline {
        if pid_by_comm("supervisor").is_none() && pid_by_comm("TEESimulator").is_none() {
            return;
        }
        std::thread::sleep(Duration::from_millis(POLL_STEP_MS));
    }
    warn!("engine processes still around after {STOP_BUDGET:?}");
}

fn kill_comm(name: &str) {
    // toybox `killall` exits non-zero when nothing matches; that is not an error.
    if let Err(error) = std::process::Command::new("killall").arg(name).status() {
        debug!("killall {name} failed: {error}");
    }
}

/// Find a process by the name in `/proc/<pid>/comm`.
fn pid_by_comm(name: &str) -> Option<i32> {
    let entries = fs::read_dir("/proc").ok()?;
    for entry in entries.flatten() {
        let file_name = entry.file_name();
        let Some(pid) = file_name.to_str().and_then(|text| text.parse::<i32>().ok()) else {
            continue;
        };
        if fs::read_to_string(format!("/proc/{pid}/comm")).is_ok_and(|comm| comm.trim() == name) {
            return Some(pid);
        }
    }
    None
}

fn wait_for_comm(name: &str, budget: Duration) -> Option<i32> {
    let deadline = Instant::now() + budget;
    loop {
        if let Some(pid) = pid_by_comm(name) {
            return Some(pid);
        }
        if Instant::now() >= deadline {
            return None;
        }
        std::thread::sleep(Duration::from_millis(POLL_STEP_MS));
    }
}

/// Whether keystore2's memory map already contains the injected engine library.
fn injection_present() -> bool {
    let Some(pid) = pid_by_comm("keystore2") else {
        return false;
    };
    fs::read_to_string(format!("/proc/{pid}/maps"))
        .is_ok_and(|maps| maps.contains("libTEESimulator"))
}

fn wait_for_injection(budget: Duration) -> bool {
    let deadline = Instant::now() + budget;
    loop {
        if injection_present() {
            return true;
        }
        if Instant::now() >= deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(POLL_STEP_MS));
    }
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
