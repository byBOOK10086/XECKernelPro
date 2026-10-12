// SPDX-License-Identifier: GPL-3.0-only

//! xecinit 的核心流程。
//!
//! 判据与顺序照 Magisk 的 `magiskinit`（`native/src/init/init.rs`）自己实现：
//!
//! 1. 挂 `/proc` `/sys` `/dev`（用各自的探测点判断是否已挂，避免重复挂）；
//! 2. 从 `/proc/cmdline` 读 `androidboot.*`：slot / boot_mode / skip_initramfs /
//!    force_normal_boot / hardware；
//! 3. **charger / recovery 模式直接交回真 init**，什么都不动（刷机、关机充电时
//!    绝不能因为我们的补丁出问题）；
//! 4. 其余情况按 skip_initramfs（legacy SAR）→ force_normal_boot（first stage）→
//!    2SI → legacy rootfs 判分支；v1 只记录判据，主路径做 overlay.d 应用；
//! 5. 把 `/init` 还原成真 init（`.backup/init`，没有备份时软链到 `/system/bin/init`）
//!    并 `execv` 过去 —— 这一步是**无论如何都要走**的，否则设备起不来。
//!
//! v1 的目标是"设备照常启动 + overlay.d 生效 + 判据落 kmsg"。`/data` 挂载、
//! sepolicy 注入、拉起我们的 daemon 属于 v2。

use std::fs;
use std::io::Write;
use std::path::Path;

use crate::{kerror, kinfo, kwarn};

/// 原 init 与配置的存放处（与 Magisk 的 `.backup` 同布局，方便互相识别/回滚）。
const BACKUP_DIR: &str = "/.backup";
const BACKUP_INIT: &str = "/.backup/init";
/// 覆盖层：注入进 ramdisk 的 `overlay.d/`，运行时合并到根。
const OVERLAY_DIR: &str = "/overlay.d";

/// 从 cmdline 解析出来的启动配置。
#[derive(Debug, Default)]
pub struct BootConfig {
    pub slot: String,
    pub boot_mode: String,
    pub skip_initramfs: bool,
    pub force_normal_boot: bool,
    pub hardware: String,
    pub dt_dir: String,
}

impl BootConfig {
    pub fn from_cmdline() -> Self {
        let mut cfg = Self::default();
        let Ok(cmdline) = fs::read_to_string("/proc/cmdline") else {
            kwarn!("读不到 /proc/cmdline，按默认配置继续");
            return cfg;
        };
        for token in cmdline.split_whitespace() {
            // 裸 token：skip_initramfs 在内核 cmdline 里就是单独一个词
            if token == "skip_initramfs" {
                cfg.skip_initramfs = true;
                continue;
            }
            let Some((key, value)) = token.split_once('=') else {
                continue;
            };
            match key {
                "androidboot.slot_suffix" => cfg.slot = value.trim_start_matches('_').to_string(),
                "androidboot.slot" => cfg.slot = value.to_string(),
                "androidboot.boot_mode" => cfg.boot_mode = value.to_string(),
                "androidboot.force_normal_boot" => cfg.force_normal_boot = value == "1",
                "androidboot.hardware" => cfg.hardware = value.to_string(),
                "androidboot.dtbo_idx" => cfg.dt_dir = value.to_string(),
                _ => {}
            }
        }
        cfg
    }

    fn is_charger(&self) -> bool {
        self.boot_mode == "charger"
    }
}

/// 入口。返回 `Err` 只用于日志；调用方无论如何都会兜底（见 `main.rs`）。
pub fn run(argv: &[std::ffi::CString]) -> Result<(), String> {
    mount_early_fs();

    let cfg = BootConfig::from_cmdline();
    kinfo!(
        "cmdline: slot={:?} boot_mode={:?} skip_initramfs={} force_normal_boot={} hardware={:?} dt_dir={:?}",
        cfg.slot,
        cfg.boot_mode,
        cfg.skip_initramfs,
        cfg.force_normal_boot,
        cfg.hardware,
        cfg.dt_dir
    );

    if cfg.is_charger() || is_recovery() {
        kinfo!("charger/recovery 模式：不打 overlay，直接交回真 init");
        return hand_off(argv);
    }

    // 分支判定：v2 会分别处理 /data 挂载与 sepolicy，这里先把判据落到 kmsg，
    // 真机排查时一眼就能看出走的是哪条路。
    if cfg.skip_initramfs {
        kinfo!("分支：legacy SAR（skip_initramfs）");
    } else if cfg.force_normal_boot {
        kinfo!("分支：first stage（force_normal_boot）");
    } else if check_two_stage() {
        kinfo!("分支：2SI（first stage 路径）");
    } else {
        kinfo!("分支：legacy rootfs（v1 主路径）");
    }

    if let Err(e) = apply_overlay() {
        kwarn!("overlay.d 应用失败（继续启动）：{e}");
    }

    hand_off(argv)
}

/// 挂 `/proc` `/sys` `/dev`。用各自的探测点判断"是否已经挂过"，
/// 重复挂载在某些内核上会失败并污染 errno，没必要试。
fn mount_early_fs() {
    if !Path::new("/proc/cmdline").exists() {
        let _ = mount_fs("proc", "/proc", "proc");
    }
    if !Path::new("/sys/block").exists() {
        let _ = mount_fs("sysfs", "/sys", "sysfs");
    }
    if !Path::new("/dev/null").exists() {
        // 优先 devtmpfs（内核帮我们建好设备节点），不行再退回 tmpfs。
        if mount_fs("devtmpfs", "/dev", "devtmpfs").is_err() {
            let _ = mount_fs("tmpfs", "/dev", "tmpfs");
        }
    }
}

fn mount_fs(source: &str, target: &str, fstype: &str) -> Result<(), String> {
    let target_c = cstr(target)?;
    let source_c = cstr(source)?;
    let fstype_c = cstr(fstype)?;
    let _ = fs::create_dir_all(target);
    let rc = unsafe {
        libc::mount(
            source_c.as_ptr(),
            target_c.as_ptr(),
            fstype_c.as_ptr(),
            0,
            std::ptr::null(),
        )
    };
    if rc != 0 {
        let err = std::io::Error::last_os_error();
        kwarn!("mount {source} -> {target} 失败：{err}");
        return Err(format!("{err}"));
    }
    kinfo!("已挂载 {target}（{fstype}）");
    Ok(())
}

fn is_recovery() -> bool {
    Path::new("/sbin/recovery").exists() || Path::new("/system/bin/recovery").exists()
}

/// 2SI（two-stage init）判定：系统里有 `/system/bin/init` 且 ramdisk 根没有 `init.rc`
/// （legacy rootfs 的 ramdisk 里一定有 init.rc）。判错也不会出事：v1 两条路都只是记日志。
fn check_two_stage() -> bool {
    Path::new("/system/bin/init").exists() && !Path::new("/init.rc").exists()
}

/// 把 `overlay.d/` 合并进根：
/// * 普通文件 → 覆盖到根（同名目录先建好）；
/// * 软链接 → 在根上重建同样的链接；
/// * `*.rc` 且目标已存在 → **追加**（这样我们能在不改原文件的前提下加 `on post-fs-data`
///   之类的启动项；Magisk 的 overlay.d 也是这个语义）。
/// * 目录 → 在根上建同名目录。
fn apply_overlay() -> Result<(), String> {
    let root = Path::new(OVERLAY_DIR);
    if !root.is_dir() {
        kinfo!("没有 {OVERLAY_DIR}，跳过 overlay 合并");
        return Ok(());
    }
    let mut applied = 0usize;
    copy_overlay(root, Path::new("/"), &mut applied)?;
    kinfo!("overlay.d 合并完成：{applied} 个条目");
    Ok(())
}

fn copy_overlay(from: &Path, to: &Path, applied: &mut usize) -> Result<(), String> {
    let entries = fs::read_dir(from).map_err(|e| format!("读 {} 失败：{e}", from.display()))?;
    for entry in entries.flatten() {
        let src = entry.path();
        let name = entry.file_name();
        let dst = to.join(&name);
        let meta =
            fs::symlink_metadata(&src).map_err(|e| format!("stat {}: {e}", src.display()))?;

        if meta.file_type().is_symlink() {
            let target = fs::read_link(&src).map_err(|e| format!("readlink: {e}"))?;
            let _ = fs::remove_file(&dst);
            std::os::unix::fs::symlink(&target, &dst)
                .map_err(|e| format!("symlink {}: {e}", dst.display()))?;
            *applied += 1;
        } else if meta.is_dir() {
            fs::create_dir_all(&dst).map_err(|e| format!("mkdir {}: {e}", dst.display()))?;
            *applied += 1;
            copy_overlay(&src, &dst, applied)?;
        } else {
            let data = fs::read(&src).map_err(|e| format!("读 {}: {e}", src.display()))?;
            let is_rc = name.to_string_lossy().ends_with(".rc");
            if is_rc && dst.exists() {
                // 追加而不是覆盖：原 rc 里的启动项一个都不能丢
                if let Ok(mut file) = fs::OpenOptions::new().append(true).open(&dst) {
                    let header = format!("\n# ---- 由 xecinit 追加（{}）----\n", src.display());
                    let _ = file.write_all(header.as_bytes());
                    file.write_all(&data)
                        .map_err(|e| format!("追加 {}: {e}", dst.display()))?;
                    *applied += 1;
                    continue;
                }
            }
            if let Some(parent) = dst.parent() {
                let _ = fs::create_dir_all(parent);
            }
            fs::write(&dst, &data).map_err(|e| format!("写 {}: {e}", dst.display()))?;
            *applied += 1;
        }
    }
    Ok(())
}

/// 把控制权交回真 init。**这是本程序存在的唯一必须成功的动作**。
///
/// * 有 `.backup/init`（注入器备份的原始 init）→ 把它放回 `/init`；
/// * 没有备份（ramdisk 是注入器从零建的）→ `/init` 软链到 `/system/bin/init`；
/// * 然后 `execv("/init")`。
fn hand_off(argv: &[std::ffi::CString]) -> Result<(), String> {
    let init = Path::new("/init");
    let backup = Path::new(BACKUP_INIT);

    let _ = fs::remove_file(init);
    if backup.exists() {
        fs::rename(backup, init).map_err(|e| format!("还原 {BACKUP_INIT} -> /init 失败：{e}"))?;
        kinfo!("已还原真 init（{BACKUP_INIT}）");
    } else {
        let _ = fs::remove_dir_all(BACKUP_DIR);
        std::os::unix::fs::symlink("/system/bin/init", init)
            .map_err(|e| format!("/init -> /system/bin/init 失败：{e}"))?;
        kinfo!("ramdisk 里没有原 init 备份，/init 软链到 /system/bin/init");
    }

    let path = cstr("/init")?;
    let mut exec_argv: Vec<*const libc::c_char> = Vec::with_capacity(argv.len() + 2);
    exec_argv.push(path.as_ptr()); // argv[0] 固定成 /init，否则 init 日志里名字会是我们自己
    for arg in argv.iter().skip(1) {
        exec_argv.push(arg.as_ptr());
    }
    exec_argv.push(std::ptr::null());

    kerror!("exec /init");
    unsafe { libc::execv(path.as_ptr(), exec_argv.as_ptr()) };
    Err(format!(
        "/init execv 失败：{}",
        std::io::Error::last_os_error()
    ))
}

/// 最后一道兜底：不依赖任何前期准备，直接把 `/init` 指回系统 init 并 exec。
/// `main.rs` 在 `run()` 报错后会再试一次它 —— PID 1 绝不能就这么停住。
pub fn force_hand_off_to_system_init() -> Result<(), String> {
    let init = Path::new("/init");
    let _ = fs::remove_file(init);
    if Path::new(BACKUP_INIT).exists() {
        let _ = fs::rename(BACKUP_INIT, init);
    } else {
        let _ = std::os::unix::fs::symlink("/system/bin/init", init);
    }
    let path = cstr("/init")?;
    let args = [path.as_ptr(), std::ptr::null()];
    unsafe { libc::execv(path.as_ptr(), args.as_ptr()) };
    Err(format!(
        "/init execv 失败：{}",
        std::io::Error::last_os_error()
    ))
}

fn cstr(value: &str) -> Result<std::ffi::CString, String> {
    std::ffi::CString::new(value).map_err(|_| format!("字符串里有 NUL：{value}"))
}
