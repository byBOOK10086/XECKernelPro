//! Magisk 兼容层的 CLI 实现（`magisk` 这个多调用名）。
//!
//! 为什么需要它：模块脚本里大量出现 `magisk --path` / `magisk -V` /
//! `magisk --install-module` / `magisk resetprop`；缺了这三样，脚本要么直接
//! `command not found`（退出码 127），要么把 `MAGISTMP=$(magisk --path)` 取成空串、
//! 于是 `$MAGISTMP/x` 落到根目录 `/x`。
//!
//! 行为规格来自独立调研（`_scratch/specs/magisk-compat-surface.md`，逐条附上游出处），
//! 代码本身是本项目自己写的，没有搬 Magisk（GPL-3.0）的实现。
//!
//! 两条刻意的取舍：
//!   1. 不假装成功：我们没实现的东西（`--sqlite`、`--daemon`、`--denylist` 写操作…）
//!      一律回明确错误 + 退出码 1。假装成功会让模块写出坏配置。
//!   2. 退出码只有 0 / 1（Magisk 从不用 2，clap 的默认 2 会误导脚本）。
#![cfg(target_os = "android")]

use anyhow::{Context, Result, bail};
use std::path::{Path, PathBuf};

use crate::defs;

/// Magisk 兼容层对外报的版本号与版本码。**必须与 `installer.sh` 里
/// `MAGISK_VER`/`MAGISK_VER_CODE` 保持一致**（模块用 `magisk -V` 做版本门限，
/// 安装器用环境变量，两者不一致会让模块判断自相矛盾）；
/// `_scratch/run_all_checks.sh` 里有一条守卫在盯这件事。
pub const MAGISK_COMPAT_NAME: &str = "xecpro-magisk-compat";

/// `magisk --path` 的返回值（MAGISKTMP）。
///
/// Magisk 自己用 tmpfs（`/debug_ramdisk` 或 `/sbin`）；我们优先复用同样的 tmpfs
/// （脚本常在这里放临时文件、当 bind 源），设备上没有就退到自己的工作目录。
pub fn magisk_tmp() -> PathBuf {
    for candidate in ["/debug_ramdisk", "/sbin"] {
        let dir = Path::new(candidate);
        if !dir.is_dir() {
            continue;
        }
        let probe = dir.join(".magisk");
        if std::fs::create_dir_all(&probe).is_ok() {
            return dir.to_path_buf();
        }
    }
    PathBuf::from(defs::WORKING_DIR).join(".magisk")
}

fn usage() -> String {
    format!(
        "XECPro Magisk compatibility ({MAGISK_COMPAT_NAME})\n\
         Usage: magisk [options] [--] [applet [arguments...]]\n\
         \x20  -c                 print the version string\n\
         \x20  -v                 print the version name\n\
         \x20  -V                 print the version code\n\
         \x20  --path             print MAGISKTMP\n\
         \x20  --list             list applets\n\
         \x20  --post-fs-data     trigger the post-fs-data stage\n\
         \x20  --service          trigger the late_start service stage\n\
         \x20  --boot-complete    trigger the boot-complete stage\n\
         \x20  --install-module ZIP\n\
         \x20  --remove-modules [-n]\n\
         \x20  --restorecon       restore SELinux contexts of our files\n\
         \x20  --clone-attr SRC DEST\n\
         \x20  --clone SRC DEST\n\
         Applets: su, resetprop"
    )
}

fn version_string() -> String {
    format!(
        "{}:XECPRO:R ({})",
        defs::MAGISK_COMPAT_VER,
        defs::MAGISK_COMPAT_VER_CODE
    )
}

/// 入口：`magisk_main` 自己读环境里的 argv（和 `su`/`resetprop` 的多调用名分发一致）。
pub fn magisk_main() -> Result<()> {
    let argv: Vec<String> = std::env::args().collect();
    let args = &argv[1..];

    // 无参数 → usage + 退出码 1（与 Magisk 一致）
    let Some(first) = args.first() else {
        eprintln!("{}", usage());
        std::process::exit(1);
    };

    // applet 分发：`magisk su ...` ≡ `su ...`（首参不以 '-' 开头）
    if !first.starts_with('-') {
        return match first.as_str() {
            "su" => crate::su::root_shell(),
            "resetprop" => {
                let mut forwarded = vec!["resetprop".to_string()];
                forwarded.extend(args[1..].iter().cloned());
                crate::resetprop::resetprop_main(&forwarded)
            }
            other => {
                eprintln!("{other}: applet not found");
                std::process::exit(1);
            }
        };
    }

    match first.as_str() {
        "-c" => {
            println!("{}", version_string());
        }
        "-v" => println!("{}", defs::MAGISK_COMPAT_VER),
        "-V" => println!("{}", defs::MAGISK_COMPAT_VER_CODE),
        "--path" => {
            let tmp = magisk_tmp();
            if let Err(e) = std::fs::create_dir_all(&tmp) {
                eprintln!("magisk: cannot create MAGISKTMP {}: {e}", tmp.display());
                std::process::exit(1);
            }
            println!("{}", tmp.display());
        }
        "--list" => {
            println!("su");
            println!("resetprop");
        }
        "--post-fs-data" => {
            crate::utils::switch_mnt_ns(1)?;
            crate::init_event::on_post_fs_data()?;
        }
        "--service" => {
            crate::utils::switch_mnt_ns(1)?;
            crate::init_event::on_services();
        }
        "--boot-complete" => {
            crate::utils::switch_mnt_ns(1)?;
            crate::init_event::on_boot_completed();
        }
        "--install-module" => {
            let zip = args
                .get(1)
                .context("--install-module requires a ZIP path")?;
            crate::utils::switch_mnt_ns(1)?;
            crate::module::install_module(zip)?;
        }
        "--remove-modules" => {
            let reboot = !args.iter().any(|a| a == "-n");
            remove_all_modules()?;
            if reboot {
                reboot_now();
            }
        }
        "--restorecon" => {
            crate::restorecon::restorecon()?;
        }
        "--clone-attr" => {
            let (src, dest) = two_paths(args, "--clone-attr")?;
            clone_attr(&dest, &src)?;
        }
        "--clone" => {
            let (src, dest) = two_paths(args, "--clone")?;
            recursive_clone(&src, &dest)?;
        }
        "--unlock-blocks" => {
            unlock_blocks();
        }
        "--sqlite" => bail!(
            "magisk --sqlite is not implemented by {MAGISK_COMPAT_NAME}: \
             this root implementation has no magisk.db. Use `xudc module config` instead."
        ),
        "--daemon" | "--stop" | "--zygote-restart" | "--denylist" | "--preinit-device" => bail!(
            "magisk {first} is not implemented by {MAGISK_COMPAT_NAME}; \
             KernelSU handles these stages itself (see `xudc --help`)."
        ),
        other => {
            eprintln!("magisk: unknown option {other}");
            eprintln!("{}", usage());
            std::process::exit(1);
        }
    }

    Ok(())
}

fn two_paths(args: &[String], opt: &str) -> Result<(String, String)> {
    let src = args
        .get(1)
        .with_context(|| format!("{opt} requires SRC DEST"))?;
    let dest = args
        .get(2)
        .with_context(|| format!("{opt} requires SRC DEST"))?;
    Ok((src.clone(), dest.clone()))
}

/// 删除全部模块（`--remove-modules`）。逐个走正常的卸载路径，保证
/// `uninstall.sh`、`remove` 标记这些约定仍然生效。
fn remove_all_modules() -> Result<()> {
    let dir = Path::new(defs::MODULE_DIR);
    let entries = match std::fs::read_dir(dir) {
        Ok(it) => it,
        Err(e) => {
            eprintln!("magisk: cannot read {}: {e}", dir.display());
            std::process::exit(1);
        }
    };

    let mut ids = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if entry.path().is_dir()
            && name != ".core"
            && crate::module::validate_module_id(&name).is_ok()
        {
            ids.push(name);
        }
    }

    if ids.is_empty() {
        println!("- no modules installed");
        return Ok(());
    }

    for id in &ids {
        println!("- removing module {id}");
        if let Err(e) = crate::module::uninstall_module(id) {
            // 与 Magisk 一致：单个模块失败不阻断整体
            eprintln!("! failed to remove {id}: {e:#}");
        }
    }
    Ok(())
}

/// `--clone-attr SRC DEST`：把 DEST 的权限/属主/SELinux 上下文套到 SRC 上。
fn clone_attr(src: &str, dest: &str) -> Result<()> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    let meta = std::fs::metadata(dest).with_context(|| format!("stat {dest}"))?;
    let con = crate::restorecon::lgetfilecon(dest).ok();

    std::fs::set_permissions(src, std::fs::Permissions::from_mode(meta.mode()))
        .with_context(|| format!("chmod {src}"))?;

    let rc = unsafe { libc::chown(cstr(src)?.as_ptr(), meta.uid(), meta.gid()) };
    if rc != 0 {
        eprintln!("! chown {src} failed: {}", std::io::Error::last_os_error());
    }

    if let Some(con) = con {
        if let Err(e) = crate::restorecon::lsetfilecon(src, &con) {
            eprintln!("! restorecon {src} failed: {e:#}");
        }
    }
    Ok(())
}

fn cstr(s: &str) -> Result<std::ffi::CString> {
    std::ffi::CString::new(s).with_context(|| format!("path contains NUL: {s}"))
}

/// `--clone SRC DEST`：递归复制（照抄 Magisk 的语义：SRC 内容复制到 DEST）。
fn recursive_clone(src: &str, dest: &str) -> Result<()> {
    if let Some(parent) = Path::new(dest).parent() {
        std::fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    // 用内置 busybox 的 cp -a：保留属性、跨设备可用，也不用自己写目录遍历
    let status = std::process::Command::new(crate::assets::BUSYBOX_PATH)
        .args(["cp", "-af", src, dest])
        .status()
        .context("run busybox cp")?;
    if !status.success() {
        bail!("busybox cp -af {src} {dest} failed: {status}");
    }
    Ok(())
}

/// `--remove-modules` 的收尾：重启（Magisk 默认行为，`-n` 才不重启）。
fn reboot_now() {
    // 与管理器里的写法一致：先 svc，再退回 /system/bin/reboot
    for (cmd, args) in [
        ("/system/bin/svc", vec!["power", "reboot"]),
        ("/system/bin/reboot", vec![]),
    ] {
        if !Path::new(cmd).exists() {
            continue;
        }
        match std::process::Command::new(cmd).args(&args).status() {
            Ok(s) if s.success() => return,
            Ok(s) => eprintln!("! {cmd} exited {s}"),
            Err(e) => eprintln!("! cannot run {cmd}: {e}"),
        }
    }
    eprintln!("! reboot failed; please reboot manually");
}

/// `--unlock-blocks`：把 /dev/block/* 全部块设备解除只读。
/// （Magisk 用它让后续写入不受"只读块设备"限制；失败只告警。）
fn unlock_blocks() {
    // BLKROSET = _IO(0x12, 93)
    const BLKROSET: libc::c_ulong = 0x125d;

    let Ok(entries) = std::fs::read_dir("/dev/block") else {
        eprintln!("! cannot read /dev/block");
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(name) = cstr(&path.to_string_lossy()) else {
            continue;
        };
        let fd = unsafe { libc::open(name.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
        if fd < 0 {
            continue;
        }
        let ro: libc::c_int = 0;
        unsafe {
            libc::ioctl(fd, BLKROSET, &ro);
            libc::close(fd);
        }
    }
}

/// 安装器脚本用到的 PATH 成员：把我们的 bin 目录暴露给模块脚本
/// （`magisk` 这个调用名就落在那里）。
pub fn ensure_magisk_cli_links() -> Result<()> {
    let bin = PathBuf::from(defs::BINARY_DIR);
    let link = bin.join("magisk");
    let daemon = PathBuf::from(defs::DAEMON_PATH);
    if !daemon.exists() {
        return Ok(());
    }
    if std::fs::read_link(&link).ok().as_deref() == Some(daemon.as_path()) {
        return Ok(());
    }
    if std::fs::symlink_metadata(&link).is_ok() {
        let _ = std::fs::remove_file(&link);
    }
    std::fs::create_dir_all(&bin).ok();
    std::os::unix::fs::symlink(&daemon, &link)
        .with_context(|| format!("link {} -> {}", link.display(), daemon.display()))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_string_keeps_code_in_sync() {
        // 版本串与版本码必须来自同一组常量，避免模块做版本门限时自相矛盾
        assert!(version_string().contains(defs::MAGISK_COMPAT_VER));
        assert!(version_string().contains(defs::MAGISK_COMPAT_VER_CODE));
    }
}
