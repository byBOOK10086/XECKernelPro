#![allow(clippy::unreadable_literal)]
use libc::SYS_reboot;
use std::os::raw::{c_long, c_ulong};
#[cfg(target_os = "android")]
use std::os::unix::fs::MetadataExt;

use crate::defs;

const SUSFS_MAX_VERSION_BUFSIZE: usize = 16;
const SUSFS_ENABLED_FEATURES_SIZE: usize = 8192;
const SUSFS_MAX_VARIANT_BUFSIZE: usize = 16;
const SUSFS_MAX_LEN_PATHNAME: usize = 256;
const ERR_CMD_NOT_SUPPORTED: i32 = 126;
const KSU_INSTALL_MAGIC1: u32 = 0xDEADBEEF;
const CMD_SUSFS_SHOW_VERSION: u32 = 0x555e1;
const CMD_SUSFS_SHOW_ENABLED_FEATURES: u32 = 0x555e2;
const CMD_SUSFS_SHOW_VARIANT: u32 = 0x555e3;
const CMD_SUSFS_ADD_SUS_KSTAT: u32 = 0x55570;
const CMD_SUSFS_UPDATE_SUS_KSTAT: u32 = 0x55571;
const CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY: u32 = 0x55572;
const CMD_SUSFS_ADD_SUS_MAP: u32 = 0x60020;
const CMD_SUSFS_SET_UNAME: u32 = 0x55590;
const CMD_SUSFS_ENABLE_LOG: u32 = 0x555a0;
const CMD_SUSFS_ENABLE_AVC_LOG_SPOOFING: u32 = 0x60010;
const CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS: u32 = 0x55561;
const CMD_SUSFS_ADD_OPEN_REDIRECT: u32 = 0x555c0;
const CMD_SUSFS_ADD_SUS_PATH: u32 = 0x55550;
const CMD_SUSFS_ADD_SUS_PATH_LOOP: u32 = 0x55553;
const SUSFS_MAGIC: u32 = 0xFAFAFAFA;

// Field masks from SUSFS v2.3.0's `susfs_def.h`. The layout and flags follow
// ReSukiSU's SUSFS implementation (commit aa7c82a7f5b5f8693118854383d88d522bdcd0f1,
// GPL-3.0 userspace), which in turn targets simonpunk/susfs4ksu (GPL-2.0).
const KSTAT_SPOOF_INO: i32 = 1 << 0;
const KSTAT_SPOOF_DEV: i32 = 1 << 1;
const KSTAT_SPOOF_NLINK: i32 = 1 << 2;
const KSTAT_SPOOF_SIZE: i32 = 1 << 3;
const KSTAT_SPOOF_ATIME_TV_SEC: i32 = 1 << 4;
const KSTAT_SPOOF_ATIME_TV_NSEC: i32 = 1 << 5;
const KSTAT_SPOOF_MTIME_TV_SEC: i32 = 1 << 6;
const KSTAT_SPOOF_MTIME_TV_NSEC: i32 = 1 << 7;
const KSTAT_SPOOF_CTIME_TV_SEC: i32 = 1 << 8;
const KSTAT_SPOOF_CTIME_TV_NSEC: i32 = 1 << 9;
const KSTAT_SPOOF_BLOCKS: i32 = 1 << 10;
const KSTAT_SPOOF_BLKSIZE: i32 = 1 << 11;
const KSTAT_SPOOF_AUTO: i32 = KSTAT_SPOOF_INO
    | KSTAT_SPOOF_DEV
    | KSTAT_SPOOF_ATIME_TV_SEC
    | KSTAT_SPOOF_ATIME_TV_NSEC
    | KSTAT_SPOOF_MTIME_TV_SEC
    | KSTAT_SPOOF_MTIME_TV_NSEC
    | KSTAT_SPOOF_CTIME_TV_SEC
    | KSTAT_SPOOF_CTIME_TV_NSEC
    | KSTAT_SPOOF_BLOCKS
    | KSTAT_SPOOF_BLKSIZE;
const KSTAT_SPOOF_AUTO_FULL_CLONE: i32 = KSTAT_SPOOF_AUTO | KSTAT_SPOOF_NLINK | KSTAT_SPOOF_SIZE;

#[repr(C)]
struct SusfsVersion {
    susfs_version: [u8; SUSFS_MAX_VERSION_BUFSIZE],
    err: i32,
}

#[repr(C)]
struct SusfsFeatures {
    enabled_features: [u8; SUSFS_ENABLED_FEATURES_SIZE],
    err: i32,
}

#[repr(C)]
struct SusfsVariant {
    susfs_variant: [u8; SUSFS_MAX_VARIANT_BUFSIZE],
    err: i32,
}

#[repr(C)]
struct SusfsUname {
    release: [u8; 65],
    version: [u8; 65],
    err: i32,
}

#[repr(C)]
struct SusfsLog {
    enabled: u32,
    err: i32,
}

#[repr(C)]
struct SusfsAvcLogSpoofing {
    enabled: u32,
    err: i32,
}

#[repr(C)]
struct SusfsHideSusMnts {
    enabled: u32,
    err: i32,
}

#[repr(C)]
struct SusfsOpenRedirect {
    target_pathname: [u8; 256],
    redirected_pathname: [u8; 256],
    uid_scheme: u32,
    err: i32,
}

#[repr(C)]
struct SusfsKstat {
    is_statically: bool,
    target_ino: c_ulong,
    target_pathname: [u8; SUSFS_MAX_LEN_PATHNAME],
    spoofed_ino: c_ulong,
    spoofed_dev: c_ulong,
    spoofed_nlink: u32,
    spoofed_size: i64,
    spoofed_atime_tv_sec: c_long,
    spoofed_atime_tv_nsec: c_ulong,
    spoofed_mtime_tv_sec: c_long,
    spoofed_mtime_tv_nsec: c_ulong,
    spoofed_ctime_tv_sec: c_long,
    spoofed_ctime_tv_nsec: c_ulong,
    spoofed_blocks: i64,
    spoofed_blksize: c_long,
    flags: i32,
    err: i32,
}

#[cfg(target_arch = "aarch64")]
const _: () = {
    assert!(std::mem::size_of::<SusfsKstat>() == 376);
    assert!(std::mem::align_of::<SusfsKstat>() == 8);
    assert!(std::mem::offset_of!(SusfsKstat, target_ino) == 8);
    assert!(std::mem::offset_of!(SusfsKstat, target_pathname) == 16);
    assert!(std::mem::offset_of!(SusfsKstat, flags) == 368);
    assert!(std::mem::offset_of!(SusfsKstat, err) == 372);
};

#[repr(C)]
struct SusfsMap {
    target_pathname: [u8; 256],
    err: i32,
}

/// Mirrors `struct st_sus_path` from susfs `susfs_defs.h` — field order and
/// sizes must match the kernel-side declaration exactly (passed by pointer).
#[repr(C)]
struct SusfsSusPath {
    target_pathname: [u8; 256],
    err: i32,
}

pub fn get_susfs_version() -> String {
    let mut cmd = SusfsVersion {
        susfs_version: [0; SUSFS_MAX_VERSION_BUFSIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_SHOW_VERSION,
            &mut cmd,
        )
    };

    let ver = cmd.susfs_version.iter().position(|&b| b == 0).unwrap_or(16);
    let ver = String::from_utf8(cmd.susfs_version[..ver].to_vec())
        .unwrap_or_else(|_| "<invalid>".to_string());

    if ver.starts_with('v') {
        ver
    } else {
        "unsupport".to_string()
    }
}

pub fn get_susfs_status() -> bool {
    get_susfs_version() != "unsupport"
}

pub fn get_susfs_features() -> String {
    let mut cmd = SusfsFeatures {
        enabled_features: [0; SUSFS_ENABLED_FEATURES_SIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_SHOW_ENABLED_FEATURES,
            &mut cmd,
        )
    };

    let features = cmd
        .enabled_features
        .iter()
        .position(|&b| b == 0)
        .unwrap_or(SUSFS_ENABLED_FEATURES_SIZE);
    String::from_utf8(cmd.enabled_features[..features].to_vec())
        .unwrap_or_else(|_| "<invalid>".to_string())
}

pub fn get_susfs_variant() -> String {
    let mut cmd = SusfsVariant {
        susfs_variant: [0; SUSFS_MAX_VARIANT_BUFSIZE],
        err: ERR_CMD_NOT_SUPPORTED,
    };

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_SHOW_VARIANT,
            &mut cmd,
        )
    };

    let variant = cmd
        .susfs_variant
        .iter()
        .position(|&b| b == 0)
        .unwrap_or(SUSFS_MAX_VARIANT_BUFSIZE);
    String::from_utf8(cmd.susfs_variant[..variant].to_vec())
        .unwrap_or_else(|_| "<invalid>".to_string())
}

pub fn set_uname(release: &str, version: &str) -> anyhow::Result<()> {
    let mut cmd = SusfsUname {
        release: [0; 65],
        version: [0; 65],
        err: ERR_CMD_NOT_SUPPORTED,
    };

    let release_bytes = release.as_bytes();
    let version_bytes = version.as_bytes();

    if release_bytes.len() >= cmd.release.len() || version_bytes.len() >= cmd.version.len() {
        anyhow::bail!("String too long");
    }

    cmd.release[..release_bytes.len()].copy_from_slice(release_bytes);
    cmd.version[..version_bytes.len()].copy_from_slice(version_bytes);

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_SET_UNAME,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to set uname: err={}", cmd.err);
    }
    Ok(())
}

pub fn enable_log(enabled: bool) -> anyhow::Result<()> {
    let mut cmd = SusfsLog {
        enabled: u32::from(enabled),
        err: ERR_CMD_NOT_SUPPORTED,
    };

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_ENABLE_LOG,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to set enable_log: err={}", cmd.err);
    }
    Ok(())
}

pub fn enable_avc_log_spoofing(enabled: bool) -> anyhow::Result<()> {
    let mut cmd = SusfsAvcLogSpoofing {
        enabled: u32::from(enabled),
        err: ERR_CMD_NOT_SUPPORTED,
    };

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_ENABLE_AVC_LOG_SPOOFING,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to set enable_avc_log_spoofing: err={}", cmd.err);
    }
    Ok(())
}

pub fn hide_sus_mnts_for_non_su_procs(enabled: bool) -> anyhow::Result<()> {
    let mut cmd = SusfsHideSusMnts {
        enabled: u32::from(enabled),
        err: ERR_CMD_NOT_SUPPORTED,
    };

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_HIDE_SUS_MNTS_FOR_NON_SU_PROCS,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!(
            "Failed to set hide_sus_mnts_for_non_su_procs: err={}",
            cmd.err
        );
    }
    Ok(())
}

/// Copy a path into a fixed-size NUL-padded kernel buffer.
fn copy_path_into(dst: &mut [u8; 256], path: &str) -> anyhow::Result<()> {
    let bytes = path.as_bytes();
    anyhow::ensure!(bytes.len() < dst.len(), "path too long for susfs: {path}");
    dst[..bytes.len()].copy_from_slice(bytes);
    Ok(())
}

pub fn add_sus_path(path: &str) -> anyhow::Result<()> {
    let mut cmd = SusfsSusPath {
        target_pathname: [0; 256],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    copy_path_into(&mut cmd.target_pathname, path)?;

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_ADD_SUS_PATH,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to add sus path {path}: err={}", cmd.err);
    }
    Ok(())
}

/// Like [`add_sus_path`] but also follows the path across bind mounts.
pub fn add_sus_path_loop(path: &str) -> anyhow::Result<()> {
    let mut cmd = SusfsSusPath {
        target_pathname: [0; 256],
        err: ERR_CMD_NOT_SUPPORTED,
    };
    copy_path_into(&mut cmd.target_pathname, path)?;

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_ADD_SUS_PATH_LOOP,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to add sus path loop {path}: err={}", cmd.err);
    }
    Ok(())
}

/// Boot-time baseline hiding for kernels that actually ship SUSFS.
///
/// The bundled `susfs4ksu` module's scripts handle the bootloader-state
/// spoofing; this covers the root solution's own footprint so a SUSFS-enabled
/// kernel hides XEC's paths from non-su callers by default. The probe is a
/// no-op on kernels without SUSFS (the reboot vector just returns EINVAL), so
/// it is safe to call on every boot path.
pub fn provision_baseline() {
    if !get_susfs_status() {
        log::info!("SUSFS: kernel does not provide it, skip baseline");
        return;
    }
    log::info!("SUSFS: kernel support detected, applying baseline");

    // /data/adb is added with the loop variant so hiding follows the dir
    // across mount namespaces and bind mounts.
    for (path, loop_variant) in [
        (defs::ADB_DIR, true),
        (defs::WORKING_DIR, false),
        (defs::MODULE_DIR, false),
        (defs::DAEMON_PATH, false),
        (defs::BUILTIN_MODULE_DIR, false),
    ] {
        let result = if loop_variant {
            add_sus_path_loop(path)
        } else {
            add_sus_path(path)
        };
        if let Err(e) = result {
            log::warn!("SUSFS: baseline add {path} failed: {e:#}");
        }
    }

    if let Err(e) = hide_sus_mnts_for_non_su_procs(true) {
        log::warn!("SUSFS: hide sus mnts for non-su procs failed: {e:#}");
    }
}

pub fn add_open_redirect(target: &str, redirected: &str, uid_scheme: u32) -> anyhow::Result<()> {
    let mut cmd = SusfsOpenRedirect {
        target_pathname: [0; 256],
        redirected_pathname: [0; 256],
        uid_scheme,
        err: ERR_CMD_NOT_SUPPORTED,
    };

    let target_bytes = target.as_bytes();
    let redirected_bytes = redirected.as_bytes();

    if target_bytes.len() >= cmd.target_pathname.len()
        || redirected_bytes.len() >= cmd.redirected_pathname.len()
    {
        anyhow::bail!("Path too long");
    }

    cmd.target_pathname[..target_bytes.len()].copy_from_slice(target_bytes);
    cmd.redirected_pathname[..redirected_bytes.len()].copy_from_slice(redirected_bytes);

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_ADD_OPEN_REDIRECT,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to add open redirect: err={}", cmd.err);
    }
    Ok(())
}

pub fn add_sus_map(path: &str) -> anyhow::Result<()> {
    let mut cmd = SusfsMap {
        target_pathname: [0; 256],
        err: ERR_CMD_NOT_SUPPORTED,
    };

    let path_bytes = path.as_bytes();
    if path_bytes.len() >= cmd.target_pathname.len() {
        anyhow::bail!("Path too long");
    }

    cmd.target_pathname[..path_bytes.len()].copy_from_slice(path_bytes);

    unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            CMD_SUSFS_ADD_SUS_MAP,
            &mut cmd,
        )
    };

    if cmd.err != 0 {
        anyhow::bail!("Failed to add sus map: err={}", cmd.err);
    }
    Ok(())
}

fn submit_kstat(cmd_code: u32, cmd: &mut SusfsKstat, operation: &str) -> anyhow::Result<()> {
    let syscall_result = unsafe {
        libc::syscall(
            SYS_reboot,
            KSU_INSTALL_MAGIC1,
            SUSFS_MAGIC,
            cmd_code,
            // A raw pointer keeps `cmd` usable below: a `&mut` passed through a
            // C-variadic call is moved, so the kernel-written `err` field could
            // not be read afterwards.
            cmd as *mut SusfsKstat,
        )
    };
    if syscall_result < 0 {
        return Err(anyhow::anyhow!(
            "SUSFS {operation} syscall failed: {}",
            std::io::Error::last_os_error()
        ));
    }
    if cmd.err != 0 {
        anyhow::bail!("SUSFS {operation} failed: err={}", cmd.err);
    }
    Ok(())
}

fn copy_metadata_to_kstat(cmd: &mut SusfsKstat, metadata: &std::fs::Metadata) {
    cmd.spoofed_ino = metadata.ino() as c_ulong;
    cmd.spoofed_dev = metadata.dev() as c_ulong;
    cmd.spoofed_nlink = metadata.nlink() as u32;
    cmd.spoofed_size = metadata.size() as i64;
    cmd.spoofed_atime_tv_sec = metadata.atime() as c_long;
    cmd.spoofed_atime_tv_nsec = metadata.atime_nsec() as c_ulong;
    cmd.spoofed_mtime_tv_sec = metadata.mtime() as c_long;
    cmd.spoofed_mtime_tv_nsec = metadata.mtime_nsec() as c_ulong;
    cmd.spoofed_ctime_tv_sec = metadata.ctime() as c_long;
    cmd.spoofed_ctime_tv_nsec = metadata.ctime_nsec() as c_ulong;
    cmd.spoofed_blocks = metadata.blocks() as i64;
    cmd.spoofed_blksize = metadata.blksize() as c_long;
}

fn update_sus_kstat_impl(path: &str, full_clone: bool) -> anyhow::Result<()> {
    let metadata = std::fs::metadata(path)
        .map_err(|e| anyhow::anyhow!("stat SUSFS kstat target {path}: {e}"))?;
    let mut cmd = SusfsKstat {
        is_statically: false,
        target_ino: metadata.ino() as c_ulong,
        target_pathname: [0; SUSFS_MAX_LEN_PATHNAME],
        spoofed_ino: 0,
        spoofed_dev: 0,
        spoofed_nlink: 0,
        spoofed_size: 0,
        spoofed_atime_tv_sec: 0,
        spoofed_atime_tv_nsec: 0,
        spoofed_mtime_tv_sec: 0,
        spoofed_mtime_tv_nsec: 0,
        spoofed_ctime_tv_sec: 0,
        spoofed_ctime_tv_nsec: 0,
        spoofed_blocks: 0,
        spoofed_blksize: 0,
        flags: if full_clone {
            KSTAT_SPOOF_AUTO_FULL_CLONE
        } else {
            KSTAT_SPOOF_AUTO
        },
        err: ERR_CMD_NOT_SUPPORTED,
    };
    copy_path_into(&mut cmd.target_pathname, path)?;
    copy_metadata_to_kstat(&mut cmd, &metadata);
    submit_kstat(
        CMD_SUSFS_UPDATE_SUS_KSTAT,
        &mut cmd,
        if full_clone {
            "full-clone kstat update"
        } else {
            "kstat update"
        },
    )
}

pub fn add_sus_kstat(path: &str) -> anyhow::Result<()> {
    let metadata = std::fs::metadata(path)
        .map_err(|e| anyhow::anyhow!("stat SUSFS kstat target {path}: {e}"))?;
    let mut cmd = SusfsKstat {
        is_statically: false,
        target_ino: metadata.ino() as c_ulong,
        target_pathname: [0; SUSFS_MAX_LEN_PATHNAME],
        spoofed_ino: 0,
        spoofed_dev: 0,
        spoofed_nlink: 0,
        spoofed_size: 0,
        spoofed_atime_tv_sec: 0,
        spoofed_atime_tv_nsec: 0,
        spoofed_mtime_tv_sec: 0,
        spoofed_mtime_tv_nsec: 0,
        spoofed_ctime_tv_sec: 0,
        spoofed_ctime_tv_nsec: 0,
        spoofed_blocks: 0,
        spoofed_blksize: 0,
        flags: KSTAT_SPOOF_AUTO,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    copy_path_into(&mut cmd.target_pathname, path)?;
    copy_metadata_to_kstat(&mut cmd, &metadata);
    submit_kstat(CMD_SUSFS_ADD_SUS_KSTAT, &mut cmd, "kstat add")
}

pub fn update_sus_kstat(path: &str) -> anyhow::Result<()> {
    update_sus_kstat_impl(path, false)
}

pub fn update_sus_kstat_full_clone(path: &str) -> anyhow::Result<()> {
    update_sus_kstat_impl(path, true)
}

#[allow(clippy::too_many_arguments)]
pub fn add_sus_kstat_statically(
    path: &str,
    ino: u64,
    dev: u64,
    nlink: u32,
    size: u64,
    atime_sec: i64,
    atime_nsec_opt: u64,
    mtime_sec: i64,
    mtime_nsec_opt: u64,
    ctime_sec: i64,
    ctime_nsec_opt: u64,
    blocks: u64,
    blksize: i64,
) -> anyhow::Result<()> {
    let metadata = std::fs::metadata(path)
        .map_err(|e| anyhow::anyhow!("stat SUSFS kstat target {path}: {e}"))?;
    let mut cmd = SusfsKstat {
        is_statically: true,
        target_ino: metadata.ino() as c_ulong,
        target_pathname: [0; SUSFS_MAX_LEN_PATHNAME],
        spoofed_ino: ino as c_ulong,
        spoofed_dev: dev as c_ulong,
        spoofed_nlink: nlink,
        spoofed_size: size as i64,
        spoofed_atime_tv_sec: atime_sec as c_long,
        spoofed_atime_tv_nsec: atime_nsec_opt as c_ulong,
        spoofed_mtime_tv_sec: mtime_sec as c_long,
        spoofed_mtime_tv_nsec: mtime_nsec_opt as c_ulong,
        spoofed_ctime_tv_sec: ctime_sec as c_long,
        spoofed_ctime_tv_nsec: ctime_nsec_opt as c_ulong,
        spoofed_blocks: blocks as i64,
        spoofed_blksize: blksize as c_long,
        flags: KSTAT_SPOOF_AUTO_FULL_CLONE,
        err: ERR_CMD_NOT_SUPPORTED,
    };
    copy_path_into(&mut cmd.target_pathname, path)?;
    submit_kstat(
        CMD_SUSFS_ADD_SUS_KSTAT_STATICALLY,
        &mut cmd,
        "static kstat add",
    )
}
