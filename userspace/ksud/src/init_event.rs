use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::utils::is_safe_mode;
use crate::{
    assets, defs, ksucalls, metamodule, restorecon,
    utils::{self},
};
use anyhow::{Context, Result};
use log::{error, info, warn};
use std::{path::Path, time::Instant};

/// Provision the built-in modules: extract the bundled binaries they are
/// executed with, materialize them into the RAM-only module dir and apply their
/// SELinux rules.
fn provision_builtin_modules() {
    // Built-in scripts run through the bundled busybox, so the binaries have to
    // be extracted before any of them runs.
    if let Err(e) = assets::ensure_binaries(true) {
        warn!("Failed to extract bin assets: {e}");
    }

    if let Err(e) = crate::module::ensure_builtin_modules() {
        warn!("ensure built-in modules failed: {e}");
    }

    // Drop Zygisk Next's failure leftovers (disable + .abort_msg) before its
    // stage scripts run, so a previous failed daemon start cannot keep Zygisk
    // disabled for this boot as well.
    crate::module::clear_zygisk_failure_markers();

    if crate::module::load_builtin_sepolicy_rule().is_err() {
        warn!("load built-in sepolicy.rule failed");
    }

    // SUSFS baseline (kernel-support probe; no-op on kernels without SUSFS).
    // 内核态专有：挂载态没有 KSU 内核侧可探，跳过。
    #[cfg(target_arch = "aarch64")]
    if !utils::is_mount_state() {
        crate::susfs::provision_baseline();
    }
}

/// Run the built-in modules' `<stage>.sh` scripts.
///
/// Only used on paths where the regular module pipeline is deliberately skipped
/// because an external Magisk installation was detected. Built-in modules are
/// shipped by this kernel itself, so leaving them on that skip path silently
/// disables Zygisk whenever a stray `magisk` binary happens to be in PATH.
fn exec_builtin_stage(stage: &str, wait: ScriptWait) {
    if stage == "post-fs-data" {
        provision_builtin_modules();
    }

    if let Err(e) = crate::module::exec_builtin_stage_script(stage, wait) {
        warn!("Failed to exec built-in {stage} scripts: {e}");
    }
}

pub fn on_post_fs_data() -> Result<()> {
    // 挂载态（<5.10）没有内核驱动：凡是"跟驱动说话"的步骤都得跳过，否则整条
    // 模块流水线会被 UAPI 校验挡在门外（那一句是 `return Ok(())`，会静默什么都不做）。
    // 反过来，挂载态也不许铺内核态的东西（`/data/adb/ksu` 那一套）。
    let mount_state = utils::is_mount_state();

    // Resolve a pending direct-flash transaction before any module, built-in
    // engine, SELinux, or SUSFS work can run. A successful rollback owns this
    // boot and must return immediately.
    if !mount_state && crate::boot_txn::on_post_fs_data()? {
        warn!("boot transaction rollback completed; skipping module startup");
        return Ok(());
    }
    if !mount_state {
        if let Err(e) = ksucalls::ensure_uapi_version_matched() {
            error!("{e:#}, skip on_post_fs_data");
            return Ok(());
        }

        ksucalls::report_post_fs_data();
    }

    utils::umask(0);

    // Locked-view properties first: the module scripts below (and every integrity
    // check that follows) read them, and zygote snapshots Build.* from the same
    // property area once post-fs-data returns.
    crate::bootstate::apply();
    crate::bootstate::harden_readability();
    crate::bootstate::apply_bootparam_overlay();

    // Re-assert the KernelSU-compatibility daemon links before anything else
    // touches the module tree. Zygisk Next / ReZygisk identify the root
    // implementation by probing the *official* daemon paths, so the mirror must
    // exist before the first module script (ZN's own included) is executed.
    if let Err(e) = utils::refresh_daemon_links() {
        warn!("refresh daemon links failed: {e}");
    }

    #[cfg(target_arch = "aarch64")]
    if !mount_state && let Err(e) = crate::kpm::booted_load() {
        warn!("kpm booted_load failed: {e}");
    }

    // Clear all temporary module configs early
    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    let _ = catch_bootlog("logcat", &["logcat", "-b", "all"]);
    let _ = catch_bootlog("dmesg", &["dmesg", "-w", "-r"]);

    if utils::has_magisk_installed() {
        warn!("Magisk detected, skip post-fs-data!");
        // Built-in modules belong to this kernel, not to Magisk: they still have
        // to be provisioned and started, otherwise a stray `magisk` binary in
        // PATH silently disables Zygisk for the whole boot.
        if !crate::utils::is_safe_mode() {
            exec_builtin_stage(
                "post-fs-data",
                ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT),
            );
        }
        return Ok(());
    }

    let safe_mode = crate::utils::is_safe_mode();
    let wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);

    if safe_mode {
        // we should still ensure module directory exists in safe mode
        // because we may need to operate the module dir in safe mode
        warn!("safe mode, skip common post-fs-data.d scripts");
    } else {
        // Then exec common post-fs-data scripts
        if let Err(e) = crate::module::exec_common_scripts("post-fs-data.d", wait) {
            warn!("exec common post-fs-data scripts failed: {e}");
        }
    }

    let module_dir = defs::MODULE_DIR;

    assets::ensure_binaries(true).with_context(|| "Failed to extract bin assets")?;

    // if we are in safe mode, we should disable all modules
    if safe_mode {
        warn!("safe mode, skip post-fs-data scripts and disable all modules!");
        if let Err(e) = crate::module::disable_all_modules() {
            warn!("disable all modules failed: {e}");
        }
        return Ok(());
    }

    // Provision bundled built-in modules before any module enumeration/execution.
    if let Err(e) = crate::module::ensure_builtin_modules() {
        warn!("ensure built-in modules failed: {e}");
    }

    // Clear Zygisk Next's failure leftovers (disable + .abort_msg) so that a
    // failed daemon start on a previous boot cannot keep Zygisk disabled here.
    // Runs before the module set is enumerated / the preinit rc is refreshed.
    crate::module::clear_zygisk_failure_markers();

    // SUSFS baseline (kernel-support probe; no-op on kernels without SUSFS).
    // 内核态专有：挂载态没有 KSU 内核侧可探，跳过。
    #[cfg(target_arch = "aarch64")]
    if !mount_state {
        crate::susfs::provision_baseline();
    }

    if let Err(e) = handle_updated_modules() {
        warn!("handle updated modules failed: {e}");
    }

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    // Refresh /metadata/watchdog/ksu/modules.rc so the next boot's kernel hook sees the
    // current module set. Acts as a safety net when state was changed outside
    // of ksud's normal mutation commands.
    // 内核态专有：这个文件是给 KSU 内核 hook 读的，挂载态既没有内核 hook，
    // 也不该铺 `ksu` 名字的东西。
    if !mount_state && let Err(e) = crate::module::regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // load sepolicy.rule
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    crate::feature::init_forced_features();

    // load feature config
    if is_safe_mode() {
        warn!("safe mode, skip load feature config");
    } else if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // execute metamodule post-fs-data script first (priority)
    if let Err(e) = metamodule::exec_stage_script("post-fs-data", wait) {
        warn!("exec metamodule post-fs-data script failed: {e}");
    }

    // exec modules post-fs-data scripts
    if let Err(e) = crate::module::exec_stage_script("post-fs-data", wait) {
        warn!("exec post-fs-data scripts failed: {e}");
    }

    // load system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // execute metamodule mount script；没装 metamodule 时退到内置 magic mount 引擎
    if metamodule::has_metamodule() {
        if let Err(e) = metamodule::exec_mount_script(module_dir) {
            warn!("execute metamodule mount failed: {e}");
        }
    } else if let Err(e) = mount_modules_magic_fallback() {
        warn!("magic mount failed: {e}");
    }

    run_stage("post-mount", wait);

    std::env::set_current_dir("/").with_context(|| "failed to chdir to /")?;

    Ok(())
}

/// 没有 metamodule 时的兜底：用内置 magic mount 引擎把 `/data/adb/modules/*` 映射到真实根
/// （Magisk 同款算法，见 `magic_mount.rs`）。挂上之后要通知内核，否则非 root 应用的
/// umount 逻辑不知道模块已经挂载 —— metamodule 的 mount 脚本做的也是这一句。
#[cfg(target_os = "android")]
fn mount_modules_magic_fallback() -> Result<()> {
    let mounted = crate::magic_mount::mount_modules(Path::new(defs::MAGIC_WORK_DIR))?;
    if mounted.is_empty() {
        info!("magic mount: 没有需要挂载的模块");
        return Ok(());
    }
    info!(
        "magic mount: 已挂载 {} 个模块: {}",
        mounted.len(),
        mounted.join(", ")
    );
    ksucalls::report_module_mounted();
    Ok(())
}

#[cfg(not(target_os = "android"))]
fn mount_modules_magic_fallback() -> Result<()> {
    Ok(())
}

pub fn run_stage(stage: &str, wait: ScriptWait) {
    utils::umask(0);

    if crate::utils::is_safe_mode() {
        warn!("safe mode, skip {stage} scripts");
        return;
    }

    if utils::has_magisk_installed() {
        warn!("Magisk detected, skip {stage}");
        // Built-in modules ship with this kernel and must not be skipped along
        // with the external Magisk module pipeline (see `exec_builtin_stage`).
        exec_builtin_stage(stage, wait);
        return;
    }

    if let Err(e) = crate::module::exec_common_scripts(&format!("{stage}.d"), wait) {
        warn!("Failed to exec common {stage} scripts: {e}");
    }

    // execute metamodule stage script first (priority)
    if let Err(e) = metamodule::exec_stage_script(stage, wait) {
        warn!("Failed to exec metamodule {stage} script: {e}");
    }

    // execute regular modules stage scripts
    if let Err(e) = crate::module::exec_stage_script(stage, wait) {
        warn!("Failed to exec {stage} scripts: {e}");
    }
}

pub fn on_services() {
    if let Err(e) = ksucalls::ensure_uapi_version_matched() {
        error!("{e:#}, skip on_services");
        return;
    }

    match ksucalls::report_services() {
        Ok(true) => {}
        Ok(false) => {
            info!("services already started, skipping");
            return;
        }
        Err(e) => {
            error!("Failed to report services: {e:#}");
            return;
        }
    }

    info!("on_services triggered!");
    run_stage("service", ScriptWait::NoWait);
}

pub fn on_boot_completed() {
    // 挂载态没有内核驱动：boot 事务与 UAPI 校验都跳过，但 boot-completed 阶段
    // 脚本必须照跑（模块靠它收尾）。
    let mount_state = utils::is_mount_state();
    if !mount_state {
        if let Err(e) = crate::boot_txn::commit_boot_success() {
            error!("boot transaction commit failed: {e:#}; refusing boot-completed stages");
            return;
        }

        if let Err(e) = ksucalls::ensure_uapi_version_matched() {
            error!("{e:#}, skip on_boot_completed");
            return;
        }

        ksucalls::report_boot_complete();
    }
    info!("on_boot_completed triggered!");

    run_stage("boot-completed", ScriptWait::NoWait);
}

fn catch_bootlog(logname: &str, command: &[&str]) -> Result<()> {
    use std::os::unix::process::CommandExt;
    use std::process::Stdio;

    let logdir = Path::new(defs::LOG_DIR);
    utils::ensure_dir_exists(logdir)?;
    let bootlog = logdir.join(format!("{logname}.log"));
    let oldbootlog = logdir.join(format!("{logname}.old.log"));

    if bootlog.exists() {
        std::fs::rename(&bootlog, oldbootlog)?;
    }

    let bootlog = std::fs::File::create(bootlog)?;

    let mut args = vec!["-s", "9", defs::BOOTLOG_TIMEOUT];
    args.extend_from_slice(command);
    // timeout -s 9 30s logcat > boot.log
    let result = unsafe {
        std::process::Command::new("timeout")
            .process_group(0)
            .pre_exec(|| {
                utils::switch_cgroups();
                Ok(())
            })
            .args(args)
            .stdout(Stdio::from(bootlog))
            .spawn()
    };

    if let Err(e) = result {
        warn!("Failed to start logcat: {e:#}");
    }

    Ok(())
}
