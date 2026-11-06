use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::utils::is_safe_mode;
use crate::{
    assets, defs, ksucalls, metamodule, restorecon,
    utils::{self},
};
use anyhow::{Context, Result};
use log::{debug, error, info, warn};
use std::{path::Path, time::Instant};

pub fn on_post_fs_data() -> Result<()> {
    // 挂载态（<5.10）没有内核驱动：凡是"跟驱动说话"的步骤都得跳过，否则整条
    // 模块流水线会被 UAPI 校验挡在门外（那一句是 `return Ok(())`，会静默什么都不做）。
    // 反过来，挂载态也不许铺内核态的东西（`/data/adb/ksu` 那一套）。
    let mount_state = utils::is_mount_state();

    // Resolve a pending direct-flash transaction before any module, SELinux, or
    // SUSFS work can run. A successful rollback owns this boot and must return
    // immediately.
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

    // Clear all temporary module configs early
    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    let _ = catch_bootlog("logcat", &["logcat", "-b", "all"]);
    let _ = catch_bootlog("dmesg", &["dmesg", "-w", "-r"]);

    if utils::has_magisk_installed() {
        warn!("Magisk detected, skip post-fs-data!");
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

    crate::hot_install::clean_trash();

    // if we are in safe mode, we should disable all modules
    if safe_mode {
        warn!("safe mode, skip post-fs-data scripts and disable all modules!");
        if let Err(e) = crate::module::disable_all_modules() {
            warn!("disable all modules failed: {e}");
        }
        return Ok(());
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

    // Install/refresh the modules this xudc build bundles (the TEE engine and
    // the TA-enhancement module) before the module set is enumerated below.
    // They land in the normal module dir, so sepolicy, preinit and the
    // post-fs-data scripts all see them on the very boot they were installed.
    // Kernel state only: in mount state Magisk owns /data/adb/modules. A
    // failure is logged and must never abort the boot.
    if !mount_state {
        match crate::builtin::install_builtin_modules() {
            Ok(()) => info!("built-in modules ready"),
            Err(e) => warn!("install built-in modules failed: {e:#}"),
        }
    }

    // Refresh /metadata/watchdog/ksu/{modules.rc,preinit/} so the next boot's kernel hook sees the
    // current module set. Acts as a safety net when state was changed outside
    // of ksud's normal mutation commands.
    // 内核态专有：这个文件是给 KSU 内核 hook 读的，挂载态既没有内核 hook，
    // 也不该铺 `ksu` 名字的东西。
    if !mount_state && let Err(e) = crate::module::regenerate_preinit() {
        warn!("regenerate preinit failed: {e:#}");
    }

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // load sepolicy.rule
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::builtin::load_sepolicy_rules() {
        warn!("load built-in sepolicy rules failed: {e}");
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

    // The bundled engine and the enhancement tree are not modules any more: their
    // stage scripts run from /data/adb/ksu/builtin after the user modules, which is
    // exactly where they ran when they still were modules.
    if let Err(e) = crate::builtin::exec_stage_script("post-fs-data", wait) {
        warn!("exec built-in post-fs-data scripts failed: {e}");
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
/// （Magisk 同款算法，见 `magic_mount.rs`）。
///
/// 挂上之后要做两件事，缺一不可：
/// 1. 把**每个真实树挂载点**登记进内核的 try-umount 表；
/// 2. 通知内核"模块已挂载"。
///
/// 第 1 步以前只有 metamodule 的 mount 脚本在做（`xudc umount add`），走的这条内置兜底
/// 路径没有脚本，于是内核 `kernel_umount` 在 setuid 时遍历的是一张空表 —— 非 root 应用
/// 永远不会被卸载掉这些 bind，模块挂了等于没做到"只对 root 可见"。
#[cfg(target_os = "android")]
fn mount_modules_magic_fallback() -> Result<()> {
    let outcome = crate::magic_mount::mount_modules(Path::new(defs::MAGIC_WORK_DIR))?;
    if outcome.modules.is_empty() {
        info!("magic mount: 没有需要挂载的模块");
        return Ok(());
    }
    info!(
        "magic mount: 已挂载 {} 个模块: {}",
        outcome.modules.len(),
        outcome.modules.join(", ")
    );
    // MNT_DETACH：允许 busy / 有嵌套挂载时惰性 detach，与 `xudc umount add --flags 2` 同义。
    const MNT_DETACH: u32 = 2;
    let mut failed = 0usize;
    for point in &outcome.mount_points {
        let path = point.to_string_lossy();
        match ksucalls::umount_list_add(&path, MNT_DETACH) {
            Ok(()) => {}
            Err(e) if is_already_registered(&e) => {
                debug!("magic mount: {path} 已经在 try-umount 表里");
            }
            Err(e) => {
                failed += 1;
                warn!("magic mount: 把 {path} 登记进 try-umount 表失败: {e}");
            }
        }
    }
    if failed > 0 {
        warn!("magic mount: {failed} 个挂载点没能登记，这些模块对非 root 应用不会被卸载");
    }
    ksucalls::report_module_mounted();
    Ok(())
}

/// 内核 `add_try_umount` 对已登记的同一路径返回 `-EEXIST`，`ksuctl` 会把它转成错误串。
/// 重复登记不是失败：表里已经有这个路径，正是我们要的状态。
#[cfg(target_os = "android")]
fn is_already_registered(e: &anyhow::Error) -> bool {
    let text = format!("{e:#}").to_ascii_lowercase();
    text.contains("file exists") || text.contains("eexist")
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

    // built-in trees run after the modules at the same stage
    if let Err(e) = crate::builtin::exec_stage_script(stage, wait) {
        warn!("Failed to exec built-in {stage} scripts: {e}");
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
    run_boot_completed(true);
}

/// 软重启（`stop` → `start`）收尾用的入口：**不碰引导事务**。
///
/// 软重启不换 boot_id，`sys.boot_completed` 也不会回到 0，而且 zygote 这时候多半
/// 还没起来——这三条正好把 `commit_boot_success()` 的双条件判死。硬按真重启那条路
/// 走，commit 会失败、函数提前 return，于是 UAPI 校验、`report_boot_complete` 和
/// 模块的 boot-completed 脚本全被跳过，软重启就"看着成功、实际什么都没做"。
/// 引导事务本来就是用来确认"真重启之后新镜像起得来"的，软重启既没有新镜像、也没有
/// 新的 boot_id，不该参与这个判定。
pub fn on_boot_completed_soft() {
    run_boot_completed(false);
}

fn run_boot_completed(commit_boot_txn: bool) {
    // 挂载态没有内核驱动：boot 事务与 UAPI 校验都跳过，但 boot-completed 阶段
    // 脚本必须照跑（模块靠它收尾）。
    let mount_state = utils::is_mount_state();
    if !mount_state {
        if commit_boot_txn {
            if let Err(e) = crate::boot_txn::commit_boot_success() {
                error!("boot transaction commit failed: {e:#}; refusing boot-completed stages");
                return;
            }
        }

        if let Err(e) = ksucalls::ensure_uapi_version_matched() {
            error!("{e:#}, skip on_boot_completed");
            return;
        }

        ksucalls::report_boot_complete();
    }
    info!("on_boot_completed triggered!");

    crate::preinit::on_boot_completed();

    // 隐身入口守卫开机自启：配置在就拉起（失败不影响启动流程）。
    if let Err(e) = crate::stealth::on_boot() {
        error!("stealth entrance: {e:#}");
    }

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
