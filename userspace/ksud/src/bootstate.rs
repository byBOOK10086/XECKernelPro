//! Boot-state consistency pass (runs without any module installed).
//!
//! The device really is unlocked: `ro.boot.verifiedbootstate=orange`,
//! `ro.boot.flash.locked=0`, `ro.boot.vbmeta.device_state=unlocked` and at least
//! one `partition.*.verified` disabled. Property-based integrity checks read
//! exactly those names *and* the coherence rules that tie them together — a green
//! colour with an unlocked flag, or a green colour with a disabled partition, is
//! reported as a contradiction of its own — so this pass rewrites the property
//! view to what a locked user build reports and keeps every rule of that set
//! self-consistent.
//!
//! Mechanism: `resetprop` with `skip_svc`, i.e. the write goes straight into the
//! bionic property area that every read path resolves through, so the four read
//! paths of a property (reflection, `getprop`, native libc, `System.getProperty`)
//! stay in agreement. Hooking a single read path is what creates a visible
//! contradiction, so nothing here hooks anything.
//!
//! The pass runs on every boot before zygote starts, so `android.os.Build` and
//! the properties never disagree, and it is idempotent. The second half,
//! [`apply_bootparam_overlay`], stages a rewritten copy of `/proc/cmdline` and
//! `/proc/bootconfig` in RAM and binds it over the originals, so the raw boot
//! parameters carry the same values as the properties they are compared with;
//! [`harden_readability`] additionally removes an untrusted-app read grant for the
//! cmdline where the vendor policy has one.

use crate::defs;
use log::{info, warn};
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use std::ffi::CString;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

/// The property view of a locked user build.
const LOCKED_PROPS: &[(&str, &str)] = &[
    ("ro.boot.verifiedbootstate", "green"),
    ("ro.boot.flash.locked", "1"),
    ("ro.boot.vbmeta.device_state", "locked"),
    ("ro.boot.veritymode", "enforcing"),
    ("ro.boot.secureboot", "1"),
    ("ro.boot.vbmeta.invalidate_on_error", "yes"),
    ("partition.system.verified", "1"),
    ("partition.vendor.verified", "1"),
    ("partition.product.verified", "1"),
    ("partition.system_ext.verified", "1"),
    ("partition.odm.verified", "1"),
    ("ro.oem_unlock_supported", "0"),
    ("service.adb.root", "0"),
    ("ro.debuggable", "0"),
    ("ro.secure", "1"),
    ("ro.adb.secure", "1"),
];

/// Android 16 (API 36) stopped publishing this property, so any value found there
/// is reported as written by something other than AOSP; on older releases the
/// check only looks at `1`/`true`, which is why removing it is safe on both: an
/// absent property leaves the rule unevaluated instead of firing.
const OEM_UNLOCK_PROP: &str = "sys.oem_unlock_allowed";

/// Best effort: `deny` removes an existing allow rule, so a policy that never
/// granted untrusted apps access to `/proc/cmdline` simply has nothing to remove.
const CMDLINE_DENY: &[&str] = &[
    "deny appdomain proc_cmdline file read",
    "deny untrusted_app proc_cmdline file read",
    "deny untrusted_app_all proc_cmdline file read",
];

const fn resetprop() -> ResetProp {
    ResetProp {
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    }
}

/// 开机环境伪装（属性 + cmdline 回锁）的开关。
///
/// **默认关闭**（2026-10-16 用户实测反馈后改的）：游戏/银行在设备上看到的"环境"
/// 由两半组成 —— 属性视图（这里改的）和密钥证明（TEE 引擎伪造的）。只改属性、
/// 密钥证明还是真实 TEE 时，两边**互相矛盾**（属性说已回锁、证明说 deviceLocked=false），
/// 而"矛盾"比"诚实的解锁态"更容易被判定成「环境异常」：原版 KernelSU 什么都不改，
/// 反而没有这种自相矛盾。
///
/// 所以这条开关与"装不装 TEE 模块"绑在一起用：
///   * 装了 TEE 引擎（外部模块）→ 可以打开，两半一致，整套伪装自洽；
///   * 没装 → 保持关闭，环境与原生系统完全一致（游戏的判定基准也一致）。
///
/// 开关文件仍是 [`defs::BOOTSTATE_FLAG_PATH`]，管理器检测页的开关写的也是它；
/// 文件不存在 = 关闭（把选择权交给用户）。
fn enabled() -> bool {
    fs::read_to_string(defs::BOOTSTATE_FLAG_PATH).is_ok_and(|value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "on" | "true" | "yes" | "enable" | "enabled"
        )
    })
}

/// Rewrite the boot-state properties to the locked view. Never fails the boot:
/// every step logs and continues, because a partially applied pass is still
/// better than none and the pass runs again on the next boot.
pub fn apply() {
    if !enabled() {
        info!("bootstate: disabled by {}", defs::BOOTSTATE_FLAG_PATH);
        return;
    }

    if let Err(e) = sys_prop::init() {
        warn!("bootstate: property API init failed: {e:#}");
        return;
    }

    let rp = resetprop();

    for (name, value) in LOCKED_PROPS {
        if let Err(e) = rp.set(name, value) {
            warn!("bootstate: set {name}={value} failed: {e:#}");
        }
    }

    match rp.delete(OEM_UNLOCK_PROP) {
        Ok(true) => info!("bootstate: removed {OEM_UNLOCK_PROP}"),
        Ok(false) => {}
        Err(e) => warn!("bootstate: delete {OEM_UNLOCK_PROP} failed: {e:#}"),
    }

    info!(
        "bootstate: locked-view properties applied ({} properties)",
        LOCKED_PROPS.len()
    );
}

/// Remove the untrusted-app read access to the raw boot parameters when the
/// loaded policy has one. The property pass above and this rule are two halves of
/// the same statement: the runtime value is rewritten, so the raw value must not
/// stay readable for a comparison.
pub fn harden_readability() {
    if !enabled() {
        return;
    }

    for rule in CMDLINE_DENY {
        match crate::sepolicy::live_patch(rule) {
            Ok(()) => info!("bootstate: applied `{rule}`"),
            Err(e) => warn!("bootstate: `{rule}` not applied: {e:#}"),
        }
    }
}

// ---------------------------------------------------------------------------
// Raw boot parameters
// ---------------------------------------------------------------------------

/// RAM-only staging directory for the rewritten boot parameters. It must not
/// live under `/data/adb`: a mount whose source path contains that string is
/// itself a signal for mount inspectors.
const OVERLAY_DIR: &str = "/dev/.xudc_hidden/bootparams";
const CMDLINE_PATH: &str = "/proc/cmdline";
const BOOTCONFIG_PATH: &str = "/proc/bootconfig";

/// `androidboot.*` keys whose runtime property counterpart [`apply`] rewrites.
/// Both sources have to agree: a reader that compares `ro.boot.*` with the raw
/// file reports a contradiction otherwise, and a reader that greps the raw file
/// for `verifiedbootstate=orange` or `vbmeta.device_state=unlocked` reads the
/// unlocked state straight out of it. Every other token is preserved verbatim, so
/// nothing else that parses these files changes behaviour.
const ANDROIDBOOT_OVERRIDES: &[(&str, &str)] = &[
    ("androidboot.verifiedbootstate", "green"),
    ("androidboot.vbmeta.device_state", "locked"),
    ("androidboot.flash.locked", "1"),
    ("androidboot.veritymode", "enforcing"),
    ("androidboot.secureboot", "1"),
    ("androidboot.vbmeta.invalidate_on_error", "yes"),
];

fn override_value(key: &str) -> Option<&'static str> {
    ANDROIDBOOT_OVERRIDES
        .iter()
        .find(|(name, _)| *name == key)
        .map(|(_, value)| *value)
}

/// `/proc/cmdline` is a single space-separated token list.
fn rewrite_cmdline(original: &str) -> String {
    let mut rewritten: Vec<String> = Vec::new();
    let mut seen: Vec<&str> = Vec::new();

    for token in original.split_whitespace() {
        let replacement = token
            .split_once('=')
            .and_then(|(key, _)| override_value(key).map(|value| (key, value)));

        match replacement {
            Some((key, value)) => {
                seen.push(key);
                rewritten.push(format!("{key}={value}"));
            }
            None => rewritten.push(token.to_string()),
        }
    }

    for (key, value) in ANDROIDBOOT_OVERRIDES {
        if !seen.contains(key) {
            rewritten.push(format!("{key}={value}"));
        }
    }

    let mut text = rewritten.join(" ");
    text.push('\n');
    text
}

/// `/proc/bootconfig` uses one `key = value` line per parameter.
fn rewrite_bootconfig(original: &str) -> String {
    // 逐行收集再 join（不用 push_str(&format!(..))：clippy::format_push_string
    // 在本 crate 是 deny）。
    let mut lines: Vec<String> = Vec::new();
    let mut seen: Vec<&str> = Vec::new();

    for line in original.lines() {
        let key = line.split('=').next().unwrap_or_default().trim();
        if let Some(value) = override_value(key) {
            seen.push(key);
            lines.push(format!("{key} = {value}"));
        } else {
            lines.push(line.to_string());
        }
    }

    for (key, value) in ANDROIDBOOT_OVERRIDES {
        if !seen.contains(key) {
            lines.push(format!("{key} = {value}"));
        }
    }

    if lines.is_empty() {
        return String::new();
    }
    let mut text = lines.join("\n");
    text.push('\n');
    text
}

/// Write the rewritten copy of `source` into the RAM staging directory.
fn stage(rewritten: &str, name: &str) -> Option<PathBuf> {
    if let Err(e) = fs::create_dir_all(OVERLAY_DIR) {
        warn!("bootstate: create {OVERLAY_DIR} failed: {e}");
        return None;
    }

    let path = Path::new(OVERLAY_DIR).join(name);
    if let Err(e) = fs::write(&path, rewritten) {
        warn!("bootstate: write {} failed: {e}", path.display());
        return None;
    }
    if let Err(e) = fs::set_permissions(&path, fs::Permissions::from_mode(0o644)) {
        warn!("bootstate: chmod {} failed: {e}", path.display());
    }
    Some(path)
}

fn bind_over(staged: &Path, target: &str) {
    let (Ok(source), Ok(destination)) = (
        CString::new(staged.as_os_str().as_encoded_bytes()),
        CString::new(target),
    ) else {
        warn!("bootstate: unusable path for {target}");
        return;
    };

    // SAFETY: both paths are NUL-terminated C strings that outlive the call,
    // `fstype`/`data` are a literal and NULL as `MS_BIND` requires, and the
    // syscall only reads them.
    let rc = unsafe {
        libc::mount(
            source.as_ptr(),
            destination.as_ptr(),
            c"none".as_ptr(),
            libc::MS_BIND,
            std::ptr::null(),
        )
    };

    if rc == 0 {
        info!("bootstate: {target} now serves the staged copy");
    } else {
        warn!(
            "bootstate: bind {target} failed: {}",
            std::io::Error::last_os_error()
        );
    }
}

fn unmount(target: &str) {
    let Ok(destination) = CString::new(target) else {
        return;
    };

    // SAFETY: the path is a NUL-terminated C string that outlives the call; the
    // syscall only reads it. A failure (normally EINVAL, nothing mounted) is
    // ignored on purpose.
    unsafe {
        libc::umount2(destination.as_ptr(), libc::MNT_DETACH);
    }
}

/// Stage and bind the rewritten boot parameters over `/proc/cmdline` and
/// `/proc/bootconfig`. Every step is best effort: a missing source, an unwritable
/// staging dir or a denied bind only costs that one file, and the property half
/// of the pass still applies.
pub fn apply_bootparam_overlay() {
    if !enabled() {
        unmount(CMDLINE_PATH);
        unmount(BOOTCONFIG_PATH);
        return;
    }

    let mut applied = 0_u32;

    if let Ok(original) = fs::read_to_string(CMDLINE_PATH) {
        if let Some(staged) = stage(&rewrite_cmdline(&original), "cmdline") {
            bind_over(&staged, CMDLINE_PATH);
            applied += 1;
        }
    } else {
        warn!("bootstate: {CMDLINE_PATH} not readable, skipping");
    }

    if let Ok(original) = fs::read_to_string(BOOTCONFIG_PATH) {
        if let Some(staged) = stage(&rewrite_bootconfig(&original), "bootconfig") {
            bind_over(&staged, BOOTCONFIG_PATH);
            applied += 1;
        }
    } else {
        info!("bootstate: {BOOTCONFIG_PATH} not readable (normal for apps, optional for root)");
    }

    info!("bootstate: boot parameter overlay staged for {applied} file(s)");
}
