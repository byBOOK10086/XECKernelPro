use anyhow::{Context, Result};
use log::{info, warn};
use rustix::cstr;
use std::{process::Command, time::Instant};

use crate::module::{ScriptWait, handle_updated_modules, prune_modules};
use crate::{assets, defs, init_event, metamodule, restorecon, utils};

fn dump_process_info(label: &str) {
    use rustix::process::{getgid, getgroups, getpid, getuid};

    let pid = getpid().as_raw_nonzero();
    let uid = getuid().as_raw();
    let gid = getgid().as_raw();
    let groups: Vec<String> = getgroups()
        .unwrap_or_default()
        .iter()
        .map(|g| g.as_raw().to_string())
        .collect();
    let selinux = std::fs::read_to_string("/proc/self/attr/current")
        .unwrap_or_else(|_| "unknown".to_string());
    let seccomp = std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("Seccomp:"))
                .map(|l| l.trim().to_string())
        })
        .unwrap_or_else(|| "unknown".to_string());

    info!(
        "[{label}] pid={pid}, uid={uid}, gid={gid}, groups=[{}], selinux={}, {seccomp}",
        groups.join(","),
        selinux.trim(),
    );
}

pub fn run(package_name: &String, kmi: Option<String>, allow_shell: bool) -> Result<()> {
    utils::daemonize(false)?;
    info!("late-load command triggered!");
    dump_process_info("late-load start");

    // 1. Detect the KMI once: both the KernelSU module and the optional SUSFS
    // module are embedded per KMI, so the lookup key is shared.
    let kmi = kmi.map_or_else(
        || crate::boot_patch::get_current_kmi().context("Failed to detect current KMI version"),
        Ok,
    )?;
    info!("Detected KMI: {kmi}");

    // 2. Check if KernelSU is already loaded
    if ksuinit::has_kernelsu() {
        info!("KernelSU already loaded, skip loading ko");
    } else {
        // 3. Get kernelsu.ko from embedded assets
        let ko_name = format!("{kmi}_kernelsu.ko");
        let ko_data = assets::get_asset_data(&ko_name)
            .with_context(|| format!("Failed to get {ko_name} from assets"))?;

        // 4. Load kernelsu.ko from memory with manual relocation
        info!("Loading kernelsu.ko for KMI {kmi}...");
        // bundled flag is meaningless in jailbreak mode since we can't flash boot to update it.
        let params = if allow_shell {
            cstr!("allow_shell=1")
        } else {
            cstr!("")
        };
        ksuinit::load_module(&ko_data, params).context("Failed to load kernelsu.ko")?;
        info!("kernelsu.ko loaded successfully!");
        dump_process_info("after load_module");
    }

    // 4b. SUSFS：随 xudc 发布的第三方 LKM（third_party/susfs4ksu-lkm）。
    //
    // 顺序是硬要求：必须排在 kernelsu.ko 之后。它的 susfs_hide_syms 会过滤
    // /proc/kallsyms，而我们的符号解析是加载时做的；反过来先装会让后面的解析失败。
    //
    // 失败一律降级：SUSFS 是增强功能，装不上不能影响 root 通道。
    load_susfs_lkm(&kmi);

    // We need to reset stdin/stdout/stderr; otherwise, sending file descriptors via cmd transactions
    // will be blocked by SELinux because its fsec->sid is still u:r:su:s0 instead of u:r:ksu:s0.
    utils::reset_std()?;

    utils::umask(0);

    if let Err(e) = crate::module_config::clear_all_temp_configs() {
        warn!("clear temp configs failed: {e}");
    }

    utils::install(None, None).context("Failed to install ksud")?;

    crate::hot_install::clean_trash();

    // Late-load bypasses on_post_data_fs, so apply the same direct-flash
    // transaction guard before any module startup work.
    if crate::boot_txn::on_post_fs_data()? {
        warn!("boot transaction rollback completed during late-load; skipping module startup");
        return Ok(());
    }

    // Module stage scripts run through the bundled busybox, so extract the bin
    // assets here too: late-load activates the kernel without a patched
    // init_boot and therefore never runs on_post_fs_data.
    if let Err(e) = assets::ensure_binaries(true) {
        warn!("Failed to extract bin assets: {e}");
    }

    // Drop Zygisk Next's failure leftovers before its stage scripts run, so a
    // previously failed daemon start cannot keep Zygisk disabled this boot.
    crate::module::clear_zygisk_failure_markers();

    // Late-load activates the kernel without a patched init_boot, so the
    // boot-state pass has to run here as well (it is idempotent).
    crate::bootstate::apply();
    crate::bootstate::harden_readability();
    crate::bootstate::apply_bootparam_overlay();

    // SUSFS baseline (kernel-support probe; no-op on kernels without SUSFS).
    #[cfg(target_arch = "aarch64")]
    crate::susfs::provision_baseline();

    // 5. Handle module updates
    if let Err(e) = handle_updated_modules() {
        warn!("handle updated modules failed: {e}");
    }

    if let Err(e) = prune_modules() {
        warn!("prune modules failed: {e}");
    }

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    // 6. Load SELinux rules
    if crate::module::load_sepolicy_rule().is_err() {
        warn!("load sepolicy.rule failed");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    // 7. Initialize features
    crate::feature::init_forced_features();
    if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // 8. Execute late-load stage scripts with a shared boot deadline
    let wait = ScriptWait::Until(Instant::now() + defs::BOOT_STAGE_TIMEOUT);
    init_event::run_stage("late-load", wait);

    // 9. Load system.prop
    if let Err(e) = crate::module::load_system_prop() {
        warn!("load system.prop failed: {e}");
    }

    // 10. Execute metamodule mount script (OverlayFS)
    if let Err(e) = metamodule::exec_mount_script(defs::MODULE_DIR) {
        warn!("execute metamodule mount failed: {e}");
    }

    // 11. Execute post-mount stage scripts using the same deadline
    init_event::run_stage("post-mount", wait);

    // 12. Execute service stage scripts (non-blocking)
    init_event::run_stage("service", ScriptWait::NoWait);

    // 13. Execute boot-completed stage scripts (non-blocking)
    init_event::run_stage("boot-completed", ScriptWait::NoWait);

    // 14. Restart Manager so it gets a fresh ksu fd from the newly loaded kernel module
    info!("Restarting KernelSU Manager {package_name}...");
    let _ = Command::new("am")
        .args(["force-stop", package_name])
        .status();
    let _ = Command::new("am")
        .args([
            "start",
            "-n",
            &format!("{package_name}/com.xecpro.kernel.XecMainActivity"),
        ])
        .status();

    Ok(())
}

/// 加载 SUSFS 的可加载内核模块（若这一 KMI 带了那份载荷）。
///
/// 它是 `third_party/susfs4ksu-lkm` 编出来的独立 .ko，由我们自己的内置装载器从
/// xudc 的资源里取出并加载 —— 不走模块体系，设备上不会有可停用/可删除的模块。
///
/// **必须在 kernelsu.ko 之后**：它的 `susfs_hide_syms` 会过滤 `/proc/kallsyms`
/// 和 `/proc/modules`，而我们的符号解析发生在内核模块初始化时。
///
/// 任何失败都只记日志：SUSFS 是增强能力，装不上不能影响 root 通道。
fn load_susfs_lkm(kmi: &str) {
    // 已经有人装过（内置内核，或这一轮已经加载）就不要叠第二份。
    if std::path::Path::new("/proc/susfs_path").exists() {
        info!("SUSFS already present, skip loading the bundled module");
        return;
    }

    let name = format!("{kmi}_susfs_guard_lkm.ko");
    let data = match assets::get_asset_data(&name) {
        Ok(data) => data,
        Err(_) => {
            // x86_64 的 xudc 不带这份载荷，这是正常情况。
            info!("no bundled {name}, skipping SUSFS");
            return;
        }
    };

    info!("Loading {name}...");
    // expose_proc=1 才会建 /proc/susfs_* 控制节点，ksud 靠这些节点下发规则。
    // avc_su_ctx 必须是我们自己的域：默认值是别的变体，填错会让 avc 伪造
    // 直接 -EOPNOTSUPP（它要用 security_secctx_to_secid 解析这个串）。
    let params = cstr!("expose_proc=1 avc_su_ctx=u:r:xecpro:s0");
    match ksuinit::load_module(&data, params) {
        Ok(()) => info!("{name} loaded successfully"),
        Err(error) => warn!("failed to load {name}: {error:#}"),
    }
}
