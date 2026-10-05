use std::ffi::{CString, OsStr};
use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::Command;
use std::sync::OnceLock;

use anyhow::{Context, Result, bail};

use crate::ksu_uapi;
use crate::ksucalls::ksuctl;

const KPM_DIR: &str = "/data/adb/kpm";
// Fallback client for a KPatch-Next engine embedded into the boot kernel
// (see kpm_patch.rs). The driver engine needs no staging at all, so nothing
// lands in tmpfs unless that fallback is actually taken.
const KPM_CORE_BIN: &str = "/dev/.kpmd";
// Kernel-side caps: sukisu_kpm_list fills a 1024 B buffer, the string ops 256 B.
const KPM_LIST_BUF: usize = 1024;
const KPM_STR_BUF: usize = 256;

/// Which KPM engine is reachable on this kernel.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Engine {
    /// CONFIG_KPM engine built into the KSU driver, reached through the
    /// supercall ioctl (KSU_IOCTL_KPM). Every kernel this repo builds has it.
    /// This is the transport upstream SukiSU uses; the brk-magic kpmd client
    /// below cannot reach this engine, so it must be preferred here.
    Driver,
    /// KPatch-Next engine embedded into the boot kernel, reached through the
    /// bundled kpmd client. Only present after the user ran the boot embed.
    Embedded,
    /// Neither engine answered; every operation must fail loudly instead of
    /// silently pretending success.
    None,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();

fn detect_engine() -> Engine {
    if driver_version().is_ok() {
        return Engine::Driver;
    }
    // Only stage the embedded core when the driver engine is absent, so a
    // plain device never gets tmpfs files just for asking.
    if ensure_kpm_core().is_ok() && check_embedded_core().is_ok() {
        return Engine::Embedded;
    }
    drop_kpm_core();
    Engine::None
}

fn engine() -> Engine {
    *ENGINE.get_or_init(detect_engine)
}

/// Decode a NUL-terminated kernel buffer without relying on unsafe CStr use.
fn buf_str(buf: &[u8]) -> String {
    let head = buf.split(|&b| b == 0).next().unwrap_or(&[]);
    String::from_utf8_lossy(head).into_owned()
}

fn io_err(ret: i32) -> io::Error {
    io::Error::from_raw_os_error(-ret)
}

// ---------------------------------------------------------------- driver ---
// ABI (kernel/kpm/kpm.c do_kpm): control_code/result_code are USER POINTERS
// to int; the kernel get_user's the command value and copy_to_user's the
// operation result. arg1/arg2 carry the operation's own user pointers.

fn driver_ctl(control: u32, arg1: u64, arg2: u64) -> Result<i32> {
    let mut code: i32 = control as i32;
    let mut ret: i32 = -1;
    let mut cmd = ksu_uapi::ksu_kpm_cmd {
        control_code: &raw mut code as u64,
        arg1,
        arg2,
        result_code: &raw mut ret as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_KPM, &raw mut cmd)
        .context("KPM: driver engine not reachable (CONFIG_KPM?)")?;
    Ok(ret)
}

fn driver_version() -> Result<String> {
    let mut buf = vec![0u8; KPM_STR_BUF];
    let ret = driver_ctl(
        ksu_uapi::SUKISU_KPM_VERSION,
        buf.as_mut_ptr() as u64,
        buf.len() as u64,
    )?;
    if ret != 0 {
        bail!("KPM: version query failed: {}", io_err(ret));
    }
    let ver = buf_str(&buf);
    if ver.is_empty() {
        bail!("KPM: driver engine returned an empty version");
    }
    Ok(ver)
}

fn driver_load(path: &str, args: Option<&str>) -> Result<()> {
    let path = CString::new(path)?;
    let args = args.map_or_else(|| CString::new(String::new()), CString::new)?;
    let ret = driver_ctl(
        ksu_uapi::SUKISU_KPM_LOAD,
        path.as_ptr() as u64,
        args.as_ptr() as u64,
    )?;
    if ret < 0 {
        bail!("KPM: load failed: {}", io_err(ret));
    }
    Ok(())
}

fn driver_unload(name: &str) -> Result<()> {
    let name = CString::new(name)?;
    let ret = driver_ctl(ksu_uapi::SUKISU_KPM_UNLOAD, name.as_ptr() as u64, 0)?;
    if ret < 0 {
        bail!("KPM: unload failed: {}", io_err(ret));
    }
    Ok(())
}

fn driver_num() -> Result<i32> {
    let ret = driver_ctl(ksu_uapi::SUKISU_KPM_NUM, 0, 0)?;
    if ret < 0 {
        bail!("KPM: num query failed: {}", io_err(ret));
    }
    Ok(ret)
}

fn driver_list() -> Result<String> {
    let mut buf = vec![0u8; KPM_LIST_BUF];
    let ret = driver_ctl(
        ksu_uapi::SUKISU_KPM_LIST,
        buf.as_mut_ptr() as u64,
        buf.len() as u64,
    )?;
    if ret < 0 {
        bail!("KPM: list failed: {}", io_err(ret));
    }
    Ok(buf_str(&buf))
}

fn driver_info(name: &str) -> Result<String> {
    let c_name = CString::new(name)?;
    let mut buf = vec![0u8; KPM_STR_BUF];
    let ret = driver_ctl(
        ksu_uapi::SUKISU_KPM_INFO,
        c_name.as_ptr() as u64,
        buf.as_mut_ptr() as u64,
    )?;
    if ret < 0 {
        bail!("KPM: info failed: {}", io_err(ret));
    }
    let out = buf_str(&buf);
    if out.is_empty() {
        bail!("KPM: module not found: {name}");
    }
    Ok(out)
}

fn driver_control(name: &str, args: &str) -> Result<i32> {
    let c_name = CString::new(name)?;
    let c_args = CString::new(args)?;
    let ret = driver_ctl(
        ksu_uapi::SUKISU_KPM_CONTROL,
        c_name.as_ptr() as u64,
        c_args.as_ptr() as u64,
    )?;
    if ret == -libc::ENOENT {
        bail!("KPM: module not found: {name}");
    }
    if ret == -libc::ENOSYS {
        bail!("KPM: module has no ctl0: {name}");
    }
    Ok(ret)
}

// ------------------------------------------------------------- embedded ---

/// Run the embedded-engine client and return its stdout. The client must exit
/// zero AND have an opinion in stdout; a silent run is a dead engine, not a
/// success.
fn run_kpm_core(args: &[&str]) -> Result<String> {
    ensure_kpm_core()?;
    let out = Command::new(KPM_CORE_BIN)
        .args(args)
        .output()
        .with_context(|| format!("failed to run core {args:?}"))?;
    if !out.status.success() {
        bail!(
            "KPM: core {} failed: {}",
            args.first().unwrap_or(&""),
            String::from_utf8_lossy(&out.stderr).trim()
        );
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Decode the bundled core (obfuscated at rest) before staging it.
fn decode_core(data: &[u8]) -> Vec<u8> {
    const KEY: &[u8] = b"xdcv1";
    data.iter()
        .enumerate()
        .map(|(i, b)| b ^ KEY[i % KEY.len()])
        .collect()
}

/// Stage the bundled core into tmpfs if it is not already there.
fn ensure_kpm_core() -> Result<()> {
    let bin = Path::new(KPM_CORE_BIN);
    if bin.exists() {
        return Ok(());
    }
    let asset = crate::assets::get_asset("kpmd").with_context(|| "core asset not bundled")?;
    let decoded = decode_core(asset.as_ref().as_ref());
    fs::write(bin, decoded).with_context(|| format!("failed to write {}", bin.display()))?;
    fs::set_permissions(bin, fs::Permissions::from_mode(0o755))?;
    Ok(())
}

/// Remove the staged core so nothing lingers after use.
fn drop_kpm_core() {
    let _ = fs::remove_file(KPM_CORE_BIN);
}

fn check_embedded_core() -> Result<String> {
    let out = run_kpm_core(&["hello"])?;
    let out = out.trim();
    if out.is_empty() {
        bail!("KPM: core not ready (hello returned empty)");
    }
    log::info!("KPM: core ok: {out}");
    Ok(out.to_owned())
}

// ------------------------------------------------------------ public API ---

/// Resolve and return the reachable engine; `None` is a hard error so CLI
/// operations surface a clear message instead of pretending success.
fn require_engine() -> Result<Engine> {
    match engine() {
        Engine::Driver | Engine::Embedded => Ok(engine()),
        Engine::None => {
            bail!("KPM: no engine available (driver CONFIG_KPM off and no boot-embedded engine)")
        }
    }
}

pub fn load_module<P>(path: P, args: Option<&str>) -> Result<()>
where
    P: AsRef<Path>,
{
    let path = path.as_ref().to_string_lossy().into_owned();
    if require_engine()? == Engine::Embedded {
        let mut kpm_argv: Vec<&str> = vec!["kpm", "load", path.as_str()];
        if let Some(a) = args
            && !a.is_empty()
        {
            kpm_argv.push(a);
        }
        let out = run_kpm_core(&kpm_argv)?;
        let out = out.trim();
        if !out.is_empty() {
            println!("{out}");
        }
        return Ok(());
    }
    driver_load(&path, args)
}

pub fn list() -> Result<()> {
    if require_engine()? == Engine::Embedded {
        print!("{}", run_kpm_core(&["kpm", "list"])?);
        return Ok(());
    }
    println!("{}", driver_list()?);
    Ok(())
}

pub fn unload_module(name: &str) -> Result<()> {
    if require_engine()? == Engine::Embedded {
        let out = run_kpm_core(&["kpm", "unload", name])?;
        let out = out.trim();
        if !out.is_empty() {
            println!("{out}");
        }
        return Ok(());
    }
    driver_unload(name)
}

pub fn info(name: &str) -> Result<()> {
    if require_engine()? == Engine::Embedded {
        print!("{}", run_kpm_core(&["kpm", "info", name])?);
        return Ok(());
    }
    println!("{}", driver_info(name)?);
    Ok(())
}

pub fn control(name: &str, args: &str) -> Result<i32> {
    if require_engine()? == Engine::Embedded {
        let out = run_kpm_core(&["kpm", "ctl0", name, args])?;
        let out = out.trim();
        if !out.is_empty() {
            println!("{out}");
        }
        return Ok(0);
    }
    driver_control(name, args)
}

pub fn num() -> Result<i32> {
    let n = if require_engine()? == Engine::Embedded {
        run_kpm_core(&["kpm", "num"])?
            .trim()
            .parse::<i32>()
            .unwrap_or(0)
    } else {
        driver_num()?
    };
    println!("{n}");
    Ok(n)
}

pub fn version() -> Result<()> {
    if require_engine()? == Engine::Embedded {
        print!("{}", run_kpm_core(&["kpver"])?);
        return Ok(());
    }
    println!("{}", driver_version()?);
    Ok(())
}

pub fn check_version() -> Result<String> {
    if require_engine()? == Engine::Embedded {
        return check_embedded_core();
    }
    driver_version()
}

fn ensure_dir() -> Result<()> {
    let dir = Path::new(KPM_DIR);

    if !dir.exists() {
        let _ = fs::create_dir_all(KPM_DIR);
    }

    if dir.metadata()?.permissions().mode() != 0o777 {
        fs::set_permissions(KPM_DIR, fs::Permissions::from_mode(0o777))?;
    }

    Ok(())
}

/// True when the user actually placed at least one module to load.
fn has_modules() -> bool {
    let Ok(dir) = fs::read_dir(KPM_DIR) else {
        return false;
    };
    dir.flatten()
        .any(|e| e.path().extension() == Some(OsStr::new("kpm")))
}

pub fn booted_load() -> Result<()> {
    // Stage nothing unless the user actually placed modules; this keeps
    // /data/adb (and /dev) clean before any modules are flashed.
    if !has_modules() {
        return Ok(());
    }

    // Probe the engine once; a kernel without any KPM engine is a supported
    // configuration, so skip quietly instead of failing the whole stage.
    let Ok(ver) = check_version() else {
        drop_kpm_core();
        log::info!("KPM: engine not ready, skip boot load");
        return Ok(());
    };
    log::info!("KPM: boot load engine ready: {ver}");

    if crate::utils::is_safe_mode() {
        log::warn!("KPM: safe-mode, skip");
        drop_kpm_core();
        return Ok(());
    }

    ensure_dir()?;
    load_all_modules()?;
    drop_kpm_core();

    Ok(())
}

fn load_all_modules() -> Result<()> {
    let dir = Path::new(KPM_DIR);

    if !dir.is_dir() {
        return Ok(());
    }

    // Deterministic order so boot behavior is reproducible.
    let mut modules: Vec<std::path::PathBuf> = dir
        .read_dir()?
        .flatten()
        .map(|entry| entry.path())
        .filter(|p| p.extension().is_some_and(|ex| ex == OsStr::new("kpm")))
        .collect();
    modules.sort();

    // One broken .kpm must not block the rest: previously the first failure
    // propagated and every later module silently never loaded after a reboot.
    let mut loaded = 0usize;
    let mut failed = 0usize;
    for path in modules {
        match load_module(&path, None) {
            Ok(()) => loaded += 1,
            Err(e) => {
                failed += 1;
                log::warn!("KPM: load {} failed: {e:#}", path.display());
            }
        }
    }
    log::info!("KPM: boot load finished: {loaded} ok, {failed} failed");

    Ok(())
}
