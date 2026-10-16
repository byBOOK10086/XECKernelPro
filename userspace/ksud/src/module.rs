#[allow(clippy::wildcard_imports)]
use crate::utils::*;
use crate::{
    assets, defs, ksucalls, metamodule,
    restorecon::{restore_syscon, setsyscon},
    sepolicy,
};

use anyhow::{Context, Result, anyhow, bail, ensure};
use const_format::concatcp;
use is_executable::is_executable;
use log::{debug, error, info, warn};
use regex_lite::Regex;

use std::{
    collections::{BTreeMap, HashMap},
    env::var as env_var,
    fs::{File, Permissions, canonicalize, remove_dir_all, set_permissions},
    path::{Path, PathBuf},
    process::Command,
    str::FromStr,
    time::Instant,
};
use std::{
    fs::{copy, rename},
    io::Write,
};
use zip_extensions::inflate::zip_extract::zip_extract_file_to_memory;

use crate::defs::{MODULE_DIR, MODULE_UPDATE_DIR, UPDATE_FILE_NAME};
use crate::module::ModuleType::{Active, All};
use std::os::unix::{prelude::PermissionsExt, process::CommandExt};

const INSTALLER_CONTENT: &str = include_str!("./installer.sh");
const INSTALL_MODULE_SCRIPT: &str = concatcp!(
    INSTALLER_CONTENT,
    "\n",
    "install_module",
    "\n",
    "exit 0",
    "\n"
);

// ---------------------------------------------------------------------------
// Built-in module storage & materialization
//
// Built-in modules execute like ordinary modules but live encrypted at rest
// (under BUILTIN_STORE_DIR) and are decrypted into a tmpfs runtime dir
// (BUILTIN_MODULE_DIR) at boot. The on-disk blobs use innocuous token names so
// a casual `ls`/`cat` reveals neither the module ids nor their contents.
// ---------------------------------------------------------------------------

const BUILTIN_XOR_KEY: &[u8] = b"xdcv1";

// susfs4ksu（上游改 AGPL-3.0）与 SelinuxFix（上游无许可证）已按合规要求移除，
// 内置集合只保留 GPL-3.0 且可从 third_party/ 源码构建的两个模块。
//
// 第三项是这个模块在用户开关里的键（见 defs::BUILTIN_FLAG_PATH）。两个键**互相独立**：
// 开 TEE 不会顺带开 TA，关 TA 也不动 TEE。
const BUILTIN_MODULES: &[(&str, &str, &str)] = &[
    ("tricky_store", "0.cfg", "tee"),
    ("TA_enhanced", "1.cfg", "ta"),
];

const BUILTIN_MAGIC: &[u8; 4] = b"BCFG";

fn xor_builtin(data: &[u8]) -> Vec<u8> {
    data.iter()
        .enumerate()
        .map(|(i, b)| b ^ BUILTIN_XOR_KEY[i % BUILTIN_XOR_KEY.len()])
        .collect()
}

fn builtin_file_mode(rel: &str) -> u32 {
    let is_shell = Path::new(rel)
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("sh"));
    if is_shell || rel.starts_with("bin/") || matches!(rel, "daemon" | "inject" | "supervisor") {
        0o755
    } else {
        0o644
    }
}

fn pack_builtin_module(entries: &[(String, u32, Vec<u8>)]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64 * 1024);
    buf.extend_from_slice(BUILTIN_MAGIC);
    buf.push(1u8);
    buf.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for (path, mode, data) in entries {
        let path = path.as_bytes();
        buf.extend_from_slice(&(path.len() as u16).to_le_bytes());
        buf.extend_from_slice(path);
        buf.extend_from_slice(&mode.to_le_bytes());
        buf.extend_from_slice(&(data.len() as u32).to_le_bytes());
        buf.extend_from_slice(data);
    }
    xor_builtin(&buf)
}

fn unpack_builtin_module(blob: &[u8]) -> Result<Vec<(String, u32, Vec<u8>)>> {
    let raw = xor_builtin(blob);
    ensure!(raw.len() >= 9, "builtin blob too short");
    ensure!(&raw[..4] == BUILTIN_MAGIC, "bad builtin blob magic");
    let count = u32::from_le_bytes(raw[5..9].try_into().unwrap()) as usize;
    let mut off = 9usize;
    let mut entries = Vec::with_capacity(count);
    for _ in 0..count {
        ensure!(off + 2 <= raw.len(), "truncated builtin blob");
        let plen = u16::from_le_bytes(raw[off..off + 2].try_into().unwrap()) as usize;
        off += 2;
        ensure!(off + plen + 8 <= raw.len(), "truncated builtin blob");
        let path = String::from_utf8_lossy(&raw[off..off + plen]).into_owned();
        off += plen;
        let mode = u32::from_le_bytes(raw[off..off + 4].try_into().unwrap());
        off += 4;
        let dlen = u32::from_le_bytes(raw[off..off + 4].try_into().unwrap()) as usize;
        off += 4;
        ensure!(off + dlen <= raw.len(), "truncated builtin blob");
        let data = raw[off..off + dlen].to_vec();
        off += dlen;
        entries.push((path, mode, data));
    }
    Ok(entries)
}

// ---------------------------------------------------------------------------
// 内置模块的用户开关
//
// 用户裁决（2026-10-15）：内置 TA / TEE 从"开机必然运行"改成**用户可选**，两个开关
// 互相独立，位置在管理器的检测页。开关落在 defs::BUILTIN_FLAG_PATH，三处共用同一份：
//   * ksud 开机时决定要不要解密进 tmpfs、要不要执行任何 stage 脚本（本文件）；
//   * 模块脚本自己复查一次（运行期被关掉时看护循环必须停）；
//   * 管理器读同一份文件渲染那两个开关。
//
// 解析不出来的情况一律按"关闭"处理：宁可少拦一次，也不要用户明确关掉之后开机又
// 自己跑起来；而这正是本次用户报的"刷入的模块重启后从列表消失"要防住的方向。
// ---------------------------------------------------------------------------

/// 读出两个开关（缺文件 / 缺键 / 值非法 = 关闭）。
pub fn builtin_flags() -> BTreeMap<&'static str, bool> {
    let mut flags: BTreeMap<&'static str, bool> = BUILTIN_MODULES
        .iter()
        .map(|(_, _, key)| (*key, false))
        .collect();

    let raw = std::fs::read_to_string(defs::BUILTIN_FLAG_PATH).unwrap_or_default();
    for line in raw.lines() {
        let line = line.split('#').next().unwrap_or_default().trim();
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        let key = key.trim().to_ascii_lowercase();
        let on = matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "on" | "yes" | "enable" | "enabled"
        );
        if let Some(slot) = flags.get_mut(key.as_str()) {
            *slot = on;
        }
    }
    flags
}

/// 把模块 id / 用户输入归一成开关键。
pub fn builtin_key(input: &str) -> Option<&'static str> {
    match input.trim().to_ascii_lowercase().as_str() {
        "tee" | "tricky_store" | "tricky-store" | "trickystore" | "teesimulator" => Some("tee"),
        "ta" | "ta_enhanced" | "ta-enhanced" | "taenhanced" => Some("ta"),
        _ => None,
    }
}

/// 这个内置模块现在开着吗？（模块 id 与开关键都接受）
pub fn builtin_enabled(module_id: &str) -> bool {
    builtin_key(module_id).is_some_and(|key| builtin_flags().get(key).copied().unwrap_or(false))
}

fn builtin_entry(key: &str) -> Option<(&'static str, &'static str)> {
    let key = builtin_key(key)?;
    BUILTIN_MODULES
        .iter()
        .find(|(_, _, k)| *k == key)
        .map(|(id, token, _)| (*id, *token))
}

/// 写开关：整份重写 + 原子替换（开机时读到半个文件是最坏的情况）。
pub fn set_builtin_flag(key: &str, on: bool) -> Result<()> {
    let key =
        builtin_key(key).with_context(|| format!("未知的内置模块：{key}（可用：tee / ta）"))?;
    let mut flags = builtin_flags();
    flags.insert(key, on);

    let mut body = String::from(
        "# XEC 内置模块开关（一行一个 key=1|0），管理器检测页维护，也可手改。\n\
         # 关掉的模块既不解密到 /dev/.xudc_hidden，也不执行任何阶段脚本。\n",
    );
    // `push_str(&format!(...))` 会被 clippy 拦（format_push_string），这里用 writeln!。
    {
        use std::fmt::Write as _;
        for (k, v) in &flags {
            let _ = writeln!(body, "{k}={}", u8::from(*v));
        }
    }

    let path = Path::new(defs::BUILTIN_FLAG_PATH);
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)
            .with_context(|| format!("Failed to create {}", dir.display()))?;
    }
    let tmp = path.with_extension("conf.tmp");
    std::fs::write(&tmp, &body).with_context(|| format!("Failed to write {}", tmp.display()))?;
    std::fs::rename(&tmp, path).with_context(|| format!("Failed to rename {}", tmp.display()))?;
    if let Err(e) = restore_syscon(path) {
        warn!("label {} failed: {e}", path.display());
    }
    info!("built-in module flag: {key}={on}");
    Ok(())
}

/// tmpfs 运行目录。
pub fn builtin_runtime_dir(module_id: &str) -> PathBuf {
    Path::new(defs::BUILTIN_MODULE_DIR).join(module_id)
}

/// `/data/adb/modules` 下有没有这个模块的**外部**安装（目录名被改过、但 module.prop
/// 里 id 还是它也算）。有的话内置那份必须让位：两套引擎同时向 keystore2 注入会互相
/// 打架（两套 grant ledger、两套证书缓存），检测方看到的是"密钥行为异常"。
pub fn external_module_present(module_id: &str) -> bool {
    let root = Path::new(defs::MODULE_DIR);
    for name in [module_id.to_string(), format!(".{module_id}")] {
        if root.join(name).join("module.prop").exists() {
            return true;
        }
    }
    let want = format!("id={module_id}");
    let Ok(entries) = std::fs::read_dir(root) else {
        return false;
    };
    for entry in entries.flatten() {
        let Ok(text) = std::fs::read_to_string(entry.path().join("module.prop")) else {
            continue;
        };
        if text
            .lines()
            .any(|line| line.trim().eq_ignore_ascii_case(&want))
        {
            return true;
        }
    }
    false
}

/// 清掉运行目录——关掉的内置模块不该在 /dev/.xudc_hidden 里留下任何东西。
fn remove_builtin_runtime(module_id: &str) {
    let dir = builtin_runtime_dir(module_id);
    if dir.exists()
        && let Err(e) = remove_dir_all(&dir)
    {
        warn!("remove built-in runtime {} failed: {e}", dir.display());
    }
}

/// 把内置模块的明文资产加密进 /data 上的存储（按 ksud 构建刷新内容戳）。
///
/// 关掉的模块也照样刷新：这样用户下次打开开关时不必先等一次开机。
fn store_builtin_blob(module_id: &str, token: &str) -> Result<()> {
    let dest = Path::new(defs::BUILTIN_STORE_DIR).join(token);
    if dest.exists() && builtin_store_is_current(token) {
        return Ok(());
    }

    let prefix = format!("{module_id}/");
    let mut entries = Vec::new();
    for file in assets::list_builtin_assets() {
        if let Some(rel) = file.strip_prefix(&prefix) {
            let data = assets::get_builtin_asset(&file)?;
            let mode = builtin_file_mode(rel);
            entries.push((rel.to_string(), mode, data));
        }
    }
    ensure!(
        !entries.is_empty(),
        "no bundled assets for built-in module {module_id}"
    );

    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("Failed to create {}", parent.display()))?;
    }
    let blob = pack_builtin_module(&entries);
    std::fs::write(&dest, blob).with_context(|| format!("Failed to write {}", dest.display()))?;
    let stamp = builtin_store_stamp_path(token);
    std::fs::write(&stamp, defs::VERSION_CODE)
        .with_context(|| format!("Failed to write {}", stamp.display()))?;
    Ok(())
}

/// 把存储里的 blob 解密进 tmpfs 运行目录（已存在的文件不覆盖）。
fn materialize_builtin_module(module_id: &str, token: &str) -> Result<()> {
    let store = Path::new(defs::BUILTIN_STORE_DIR).join(token);
    let blob =
        std::fs::read(&store).with_context(|| format!("Failed to read {}", store.display()))?;
    let entries = unpack_builtin_module(&blob)?;
    let base = builtin_runtime_dir(module_id);

    for (rel, mode, data) in entries {
        let dest = base.join(&rel);
        if dest.exists() {
            continue;
        }
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("Failed to create {}", parent.display()))?;
        }
        std::fs::write(&dest, &data)
            .with_context(|| format!("Failed to write {}", dest.display()))?;
        #[cfg(unix)]
        std::fs::set_permissions(&dest, Permissions::from_mode(mode))?;
    }

    // /dev 是 tmpfs：新写进去的文件继承的是 tmpfs 的标签，zygote 不允许 map 它
    // ——内置模块的守护进程要把这些库交给 zygote 注入。整个运行目录标成 system_file，
    // 与普通模块目录 restorecon() 的结果一致。
    if let Err(e) = restore_syscon(&base) {
        warn!("label built-in module dir {} failed: {e}", base.display());
    }
    Ok(())
}

/// 运行期打开一个内置模块：脱离进程组后在后台跑它的 service 阶段。
///
/// 只跑 service 阶段：post-fs-data 的前提（挂载、magic mount、属性、模块目录）在开机时
/// 就完成了，运行期重跑它没有意义，只会在已经就绪的系统上再写一遍。
fn start_builtin_runtime(module_id: &str) -> Result<()> {
    let script = builtin_runtime_dir(module_id).join("service.sh");
    ensure!(
        script.exists(),
        "内置模块运行目录缺失：{}",
        script.display()
    );

    let mut command = Command::new(assets::busybox_path());
    unsafe {
        command.pre_exec(|| {
            detach_process_group(true);
            Ok(())
        });
    }
    command
        .current_dir(script.parent().unwrap_or_else(|| Path::new("/")))
        .arg("sh")
        .arg(&script)
        .envs(get_common_script_envs(Some(module_id)))
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .with_context(|| format!("启动内置模块 {module_id} 失败"))?;

    info!("built-in module {module_id}: service stage started on demand");
    Ok(())
}

/// 运行期停掉一个内置模块：交给模块自己的 `service.sh --stop`。
///
/// 进程名、看护循环、注入面的知识只应该有一份，就在模块脚本里；ksud 只负责按开关、
/// 限时（脚本卡住则 SIGKILL），不自己拼 pkill 规则。
fn stop_builtin_runtime(module_id: &str) {
    let script = builtin_runtime_dir(module_id).join("service.sh");
    if !script.exists() {
        return;
    }

    let Ok(sigchld) = SigchldBlock::block() else {
        warn!("built-in module {module_id}: cannot block SIGCHLD, --stop runs unguarded");
        return;
    };
    let original_sigmask = sigchld.previous;

    let mut command = Command::new(assets::busybox_path());
    unsafe {
        command.pre_exec(move || {
            if libc::sigprocmask(
                libc::SIG_SETMASK,
                &raw const original_sigmask,
                std::ptr::null_mut(),
            ) != 0
            {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    command
        .current_dir(script.parent().unwrap_or_else(|| Path::new("/")))
        .arg("sh")
        .arg(&script)
        .arg("--stop")
        .envs(get_common_script_envs(Some(module_id)))
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());

    match command.spawn() {
        Ok(mut child) => {
            let deadline = Instant::now() + std::time::Duration::from_secs(20);
            match sigchld.wait_until(child.id() as libc::pid_t, deadline) {
                Ok(true) => info!("built-in module {module_id}: stopped"),
                Ok(false) => {
                    warn!("built-in module {module_id}: --stop timed out, killing it");
                    let _ = child.kill();
                    let _ = child.wait();
                }
                Err(e) => warn!("built-in module {module_id}: waiting for --stop failed: {e}"),
            }
        }
        Err(e) => warn!("built-in module {module_id}: cannot run --stop: {e}"),
    }
}

fn builtin_status_of(
    module_id: &str,
    token: &str,
    key: &str,
    flags: &BTreeMap<&'static str, bool>,
) -> serde_json::Value {
    serde_json::json!({
        "key": key,
        "id": module_id,
        "enabled": *flags.get(key).unwrap_or(&false),
        "runtime": builtin_runtime_dir(module_id).join("service.sh").exists(),
        "store": Path::new(defs::BUILTIN_STORE_DIR).join(token).exists(),
        "external": external_module_present(module_id),
    })
}

/// 两个内置模块的状态（管理器检测页读它渲染开关）。
pub fn builtin_status() -> serde_json::Value {
    let flags = builtin_flags();
    let mut map = serde_json::Map::new();
    for (module_id, token, key) in BUILTIN_MODULES {
        map.insert(
            (*key).to_string(),
            builtin_status_of(module_id, token, key, &flags),
        );
    }
    serde_json::Value::Object(map)
}

pub fn builtin_print_status() -> Result<()> {
    println!("{}", serde_json::to_string_pretty(&builtin_status())?);
    Ok(())
}

/// `ksud builtin enable|disable`：写开关并**立刻**让设备侧生效（不必等重启）。
pub fn builtin_apply(target: &str, on: bool) -> Result<()> {
    let key = builtin_key(target)
        .with_context(|| format!("未知的内置模块：{target}（可用：tee / ta）"))?;
    let (module_id, token) = builtin_entry(key).context("内置模块表里没有这个键")?;

    // 模块脚本要跑 busybox；运行期这条路径不经过开机的资产提取，得自己补一次。
    if let Err(e) = assets::ensure_binaries(true) {
        warn!("extract bin assets failed: {e}");
    }

    set_builtin_flag(key, on)?;

    if on {
        store_builtin_blob(module_id, token)?;
        materialize_builtin_module(module_id, token)?;
        start_builtin_runtime(module_id)?;
    } else {
        stop_builtin_runtime(module_id);
        remove_builtin_runtime(module_id);
    }

    println!("{}", serde_json::to_string_pretty(&builtin_status())?);
    Ok(())
}

/// Validate module_id format and security
/// Module ID must match: ^[a-zA-Z][a-zA-Z0-9._-]+$
/// - Must start with a letter (a-zA-Z)
/// - Followed by one or more alphanumeric, dot, underscore, or hyphen characters
/// - Minimum length: 2 characters
pub fn validate_module_id(module_id: &str) -> Result<()> {
    let re = Regex::new(r"^[a-zA-Z][a-zA-Z0-9._-]+$")?;
    if re.is_match(module_id) {
        Ok(())
    } else {
        Err(anyhow!(
            "Invalid module ID: '{module_id}'. Must match /^[a-zA-Z][a-zA-Z0-9._-]+$/"
        ))
    }
}

/// Get common environment variables for script execution
pub fn get_common_script_envs(module_id: Option<&str>) -> Vec<(&'static str, String)> {
    let mut envs = vec![
        ("ASH_STANDALONE", "1".to_string()),
        ("KSU", "true".to_string()),
        // The kernel deliberately reports a Zygisk-Next-compatible version code
        // (KSU_COMPAT_REPORTED_VERSION) to every non-manager caller, so the live
        // value must not leak into module scripts: modules would conclude they
        // run on an ancient kernel and silently disable their modern code paths.
        // ksud and the kernel always come from the same build, so the compile
        // time code is the truthful number here.
        ("KSU_KERNEL_VER_CODE", defs::VERSION_CODE.to_string()),
        ("KSU_VER_CODE", defs::VERSION_CODE.to_string()),
        ("KSU_VER", defs::VERSION_NAME.to_string()),
        ("KSU_UAPI_VER", ksucalls::uapi_version().to_string()),
        ("KSU_RUNTIME_MODE", ksucalls::runtime_mode().to_string()),
        (
            "PATH",
            format!(
                "{}:{}",
                env_var("PATH").unwrap_or_default(),
                assets::binary_dir()
            ),
        ),
    ];

    if let Some(id) = module_id {
        if validate_module_id(id).is_ok() {
            envs.push(("KSU_MODULE", id.to_string()));
        } else {
            error!("Invalid module_id provided: {id}");
        }
    }

    if ksucalls::is_late_load() {
        envs.push(("KSU_LATE_LOAD", "1".to_string()));
    }

    envs
}

fn exec_install_script(module_file: &str, is_metamodule: bool, module_id: &str) -> Result<()> {
    let realpath = std::fs::canonicalize(module_file)
        .with_context(|| format!("realpath: {module_file} failed"))?;

    // Get install script from metamodule module
    let install_script =
        metamodule::get_install_script(is_metamodule, INSTALLER_CONTENT, INSTALL_MODULE_SCRIPT)?;

    let result = Command::new(assets::busybox_path())
        .args(["sh", "-c", &install_script])
        .envs(get_common_script_envs(Some(module_id)))
        .env("OUTFD", "1")
        .env("ZIPFILE", realpath)
        .status()?;
    ensure!(result.success(), "Failed to install module script");
    Ok(())
}

// Check if Android boot is completed before installing modules
fn ensure_boot_completed() -> Result<()> {
    // ensure getprop sys.boot_completed == 1
    if getprop("sys.boot_completed").as_deref() != Some("1") {
        bail!("Android is Booting!");
    }
    Ok(())
}

#[derive(PartialEq, Eq)]
pub enum ModuleType {
    All,
    Active,
    Updated,
}

#[allow(clippy::needless_pass_by_value)]
pub fn foreach_module(
    module_type: ModuleType,
    mut f: impl FnMut(&Path) -> Result<()>,
) -> Result<()> {
    let modules_dir = Path::new(match module_type {
        ModuleType::Updated => MODULE_UPDATE_DIR,
        _ => defs::MODULE_DIR,
    });
    let dir = std::fs::read_dir(modules_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            warn!("{} is not a directory, skip", path.display());
            continue;
        }

        if module_type == Active && path.join(defs::DISABLE_FILE_NAME).exists() {
            info!("{} is disabled, skip", path.display());
            continue;
        }
        if module_type == Active && path.join(defs::REMOVE_FILE_NAME).exists() {
            warn!("{} is removed, skip", path.display());
            continue;
        }

        f(&path)?;
    }

    Ok(())
}

fn foreach_active_module(f: impl FnMut(&Path) -> Result<()>) -> Result<()> {
    foreach_module(Active, f)
}

/// Iterate decrypted built-in modules under the tmpfs runtime dir. These
/// execute like normal modules but are never enumerated by the manager panel
/// or `adb ls modules/`, and the encrypted on-disk store is not readable.
///
/// 用户关掉的模块不参与任何一步（sepolicy、system.prop、stage 脚本、rc），
/// 所以除了 ksud 自己按开关决定要不要解密之外，这里再复查一次。
fn foreach_builtin_module(mut f: impl FnMut(&Path) -> Result<()>) -> Result<()> {
    let modules_dir = Path::new(defs::BUILTIN_MODULE_DIR);
    let Ok(dir) = std::fs::read_dir(modules_dir) else {
        return Ok(());
    };
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let id = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default();
        if !builtin_enabled(id) {
            continue;
        }
        f(&path)?;
    }
    Ok(())
}

pub fn load_sepolicy_rule() -> Result<()> {
    foreach_active_module(|path| {
        let rule_file = path.join("sepolicy.rule");
        if !rule_file.exists() {
            return Ok(());
        }
        info!("load policy: {}", rule_file.display());

        if sepolicy::apply_file(&rule_file).is_err() {
            warn!("Failed to load sepolicy.rule for {}", rule_file.display());
        }
        Ok(())
    })?;

    load_builtin_sepolicy_rule()
}

/// Load only the built-in modules' `sepolicy.rule`.
///
/// Used when the regular module pipeline is skipped (external Magisk detected)
/// but the built-in modules still run, so their required policy types stay
/// available.
pub fn load_builtin_sepolicy_rule() -> Result<()> {
    foreach_builtin_module(|path| {
        let rule_file = path.join("sepolicy.rule");
        if !rule_file.exists() {
            return Ok(());
        }
        info!("load built-in policy: {}", rule_file.display());

        if sepolicy::apply_file(&rule_file).is_err() {
            warn!("Failed to load sepolicy.rule for {}", rule_file.display());
        }
        Ok(())
    })
}

/// Extract the module id from a module script path. Returns whether the path
/// is inside a module dir (built-in included) and the id if resolvable.
fn extract_module_id(path: &Path) -> (bool, Option<String>) {
    for base in [defs::MODULE_DIR, defs::BUILTIN_MODULE_DIR] {
        if let Some(id) = path
            .strip_prefix(base)
            .ok()
            .and_then(|p| p.components().next())
            .and_then(|c| c.as_os_str().to_str())
            .map(ToString::to_string)
        {
            return (true, Some(id));
        }
    }
    (false, None)
}

#[derive(Clone, Copy)]
pub enum ScriptWait {
    NoWait,
    Forever,
    Until(Instant),
}

struct SigchldBlock {
    set: libc::sigset_t,
    previous: libc::sigset_t,
}

impl SigchldBlock {
    fn block() -> std::io::Result<Self> {
        // Initialize both sets before passing them to libc.
        let mut set = unsafe { std::mem::zeroed() };
        let mut previous = unsafe { std::mem::zeroed() };
        unsafe {
            libc::sigemptyset(&raw mut set);
            libc::sigaddset(&raw mut set, libc::SIGCHLD);
            if libc::sigprocmask(libc::SIG_BLOCK, &raw const set, &raw mut previous) != 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        Ok(Self { set, previous })
    }

    fn wait_until(&self, pid: libc::pid_t, deadline: Instant) -> std::io::Result<bool> {
        loop {
            // Only reap the script being waited for. Other children can also send SIGCHLD.
            let result = unsafe { libc::waitpid(pid, std::ptr::null_mut(), libc::WNOHANG) };
            if result == pid {
                return Ok(true);
            }
            if result < 0 {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error() == Some(libc::EINTR) {
                    continue;
                }
                return Err(error);
            }

            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Ok(false);
            }
            let timeout = libc::timespec {
                tv_sec: remaining.as_secs() as libc::time_t,
                tv_nsec: remaining.subsec_nanos().into(),
            };
            // SIGCHLD stays blocked between waitpid and sigtimedwait to avoid lost wakeups.
            if unsafe {
                libc::sigtimedwait(
                    &raw const self.set,
                    std::ptr::null_mut(),
                    &raw const timeout,
                )
            } < 0
            {
                let error = std::io::Error::last_os_error();
                match error.raw_os_error() {
                    Some(libc::EAGAIN) => return Ok(false),
                    Some(libc::EINTR) => {}
                    _ => return Err(error),
                }
            }
        }
    }
}

impl Drop for SigchldBlock {
    fn drop(&mut self) {
        // Restore the parent's signal mask on success, timeout, and every error path.
        if unsafe {
            libc::sigprocmask(
                libc::SIG_SETMASK,
                &raw const self.previous,
                std::ptr::null_mut(),
            )
        } != 0
        {
            warn!(
                "Failed to restore signal mask: {}",
                std::io::Error::last_os_error()
            );
        }
    }
}

pub fn exec_script<T: AsRef<Path>>(path: T, wait: ScriptWait) -> Result<()> {
    info!("exec {}", path.as_ref().display());

    let (is_module_script, module_id) = extract_module_id(path.as_ref());

    // Validate and log module_id extraction
    let validated_module_id = module_id
        .as_ref()
        .and_then(|id| match validate_module_id(id) {
            Ok(()) => {
                debug!("Module ID extracted from script path: '{id}'");
                Some(id.as_str())
            }
            Err(e) => {
                warn!(
                    "Invalid module ID '{id}' extracted from script path '{}': {e}",
                    path.as_ref().display(),
                );
                None
            }
        });

    if is_module_script && module_id.is_none() {
        debug!(
            "Failed to extract module_id from script path '{}'. Script will run without KSU_MODULE environment variable.",
            path.as_ref().display()
        );
    }

    let sigchld = SigchldBlock::block().context("Failed to block SIGCHLD")?;
    let original_sigmask = sigchld.previous;
    let mut command = Command::new(assets::busybox_path());
    unsafe {
        command.pre_exec(move || {
            if libc::sigprocmask(
                libc::SIG_SETMASK,
                &raw const original_sigmask,
                std::ptr::null_mut(),
            ) != 0
            {
                return Err(std::io::Error::last_os_error());
            }
            detach_process_group(true);
            // ignore the error?
            switch_cgroups();
            Ok(())
        })
    };
    command
        .current_dir(path.as_ref().parent().unwrap())
        .arg("sh")
        .arg(path.as_ref())
        .envs(get_common_script_envs(validated_module_id));

    let result = match wait {
        ScriptWait::NoWait => command.spawn().map(|_| ()),
        ScriptWait::Forever => command.status().map(|_| ()),
        ScriptWait::Until(deadline) => command.spawn().and_then(|child| {
            // Once the shared deadline has passed, launch later scripts without waiting.
            if deadline > Instant::now()
                && !sigchld.wait_until(child.id() as libc::pid_t, deadline)?
            {
                warn!("Timed out waiting for script: {}", path.as_ref().display());
            }
            Ok(())
        }),
    };
    result.map_err(|e| anyhow!("Failed to exec {}: {e}", path.as_ref().display()))
}

/// Execute `<stage>.sh` of the built-in modules only.
///
/// Built-in modules are shipped and provisioned by ksud itself, so they must
/// keep running even when an external Magisk installation is detected: in that
/// case the generic module pipeline is skipped on purpose, but skipping our own
/// modules as well leaves Zygisk silently disabled.
pub fn exec_builtin_stage_script(stage: &str, wait: ScriptWait) -> Result<()> {
    foreach_builtin_module(|module| {
        let script_path = module.join(format!("{stage}.sh"));
        if !script_path.exists() {
            return Ok(());
        }

        exec_script(&script_path, wait)
    })
}

pub fn exec_stage_script(stage: &str, wait: ScriptWait) -> Result<()> {
    let metamodule_dir = metamodule::get_metamodule_path().and_then(|path| canonicalize(path).ok());

    foreach_active_module(|module| {
        if metamodule_dir.as_ref().is_some_and(|meta_dir| {
            canonicalize(module).is_ok_and(|resolved| resolved == *meta_dir)
        }) {
            return Ok(());
        }

        let script_path = module.join(format!("{stage}.sh"));
        if !script_path.exists() {
            return Ok(());
        }

        exec_script(&script_path, wait)
    })?;

    exec_builtin_stage_script(stage, wait)
}

pub fn exec_common_scripts(dir: &str, wait: ScriptWait) -> Result<()> {
    let script_dir = Path::new(defs::ADB_DIR).join(dir);
    if !script_dir.exists() {
        info!("{} not exists, skip", script_dir.display());
        return Ok(());
    }

    let dir = std::fs::read_dir(&script_dir)?;
    for entry in dir.flatten() {
        let path = entry.path();

        if !is_executable(&path) {
            warn!("{} is not executable, skip", path.display());
            continue;
        }

        exec_script(path, wait)?;
    }

    Ok(())
}

pub fn load_system_prop() -> Result<()> {
    foreach_active_module(|module| {
        let system_prop = module.join("system.prop");
        if !system_prop.exists() {
            return Ok(());
        }
        info!("load {} system.prop", module.display());

        crate::resetprop::load_system_prop_file(&system_prop)?;

        Ok(())
    })?;

    foreach_builtin_module(|module| {
        let system_prop = module.join("system.prop");
        if !system_prop.exists() {
            return Ok(());
        }
        info!("load built-in {} system.prop", module.display());

        crate::resetprop::load_system_prop_file(&system_prop)?;

        Ok(())
    })?;

    Ok(())
}

/// Path of the build stamp that pins a stored blob to the ksud build that
/// wrote it. Kept next to the blob inside [`defs::BUILTIN_STORE_DIR`].
fn builtin_store_stamp_path(token: &str) -> PathBuf {
    Path::new(defs::BUILTIN_STORE_DIR).join(format!("{token}.ver"))
}

/// Whether the stored blob for `token` was written by the running build.
///
/// Without this check a blob written by an older ksud would survive forever
/// (`dest.exists()` used to short-circuit the refresh), so updating ksud could
/// never deliver a fixed built-in module such as `TA_enhanced`.
fn builtin_store_is_current(token: &str) -> bool {
    std::fs::read_to_string(builtin_store_stamp_path(token))
        .is_ok_and(|stamp| stamp.trim() == defs::VERSION_CODE)
}

/// Persistent encrypted store + tmpfs materialization for built-in modules.
///
/// On-disk blobs are rewritten whenever the running ksud build changes, so a
/// stale blob can never outlive an update and keep e.g. an obsolete module
/// in place; the runtime dir is decrypted from those blobs every boot into
/// RAM-only tmpfs.
///
/// 用户关掉的模块只刷新存储、**不解密进 tmpfs**，并把上一次留下的运行目录删掉：
/// 关掉的内置模块在设备上不该有任何痕迹，也不该被任何一步枚举到。
///
/// 返回值保持可失败：开着的模块没能落盘/解密就是这一步真的失败了（调用方会记日志），
/// 但**两个模块各自独立**——一个失败不影响另一个继续尝试，所以循环里不提前返回。
pub fn ensure_builtin_modules() -> Result<()> {
    let mut first_error: Option<anyhow::Error> = None;

    for (module_id, token, key) in BUILTIN_MODULES {
        // 存储始终跟上当前 ksud 构建：下次打开开关时不必先等一次开机。
        if let Err(e) = store_builtin_blob(module_id, token) {
            warn!("store built-in module {module_id} failed: {e}");
            first_error.get_or_insert(e);
        }

        if !builtin_enabled(key) {
            remove_builtin_runtime(module_id);
            continue;
        }

        if let Err(e) = materialize_builtin_module(module_id, token) {
            warn!("materialize built-in module {module_id} failed: {e}");
            first_error.get_or_insert(e);
        }
    }

    // clippy::option_if_let_else：`match Option` 只有"映射 Some / 兜底 None"两种走向时，
    // 应写成 map_or_else。
    first_error.map_or_else(
        || Ok(()),
        |e| Err(e.context("built-in modules could not be provisioned")),
    )
}

pub fn prune_modules() -> Result<()> {
    foreach_module(All, |module| {
        if !module.join(defs::REMOVE_FILE_NAME).exists() {
            return Ok(());
        }

        info!("remove module: {}", module.display());

        // Execute metamodule's metauninstall.sh first
        let module_id = module.file_name().and_then(|n| n.to_str()).unwrap_or("");

        // Check if this is a metamodule
        let is_metamodule =
            read_module_prop(module).is_ok_and(|props| metamodule::is_metamodule(&props));

        if is_metamodule {
            info!("Removing metamodule symlink");
            if let Err(e) = metamodule::remove_symlink() {
                warn!("Failed to remove metamodule symlink: {e}");
            }
        } else if let Err(e) = metamodule::exec_metauninstall_script(module_id) {
            warn!("Failed to exec metamodule uninstall for {module_id}: {e}");
        }

        // Then execute module's own uninstall.sh
        let uninstaller = module.join("uninstall.sh");
        if uninstaller.exists()
            && let Err(e) = exec_script(uninstaller, ScriptWait::Forever)
        {
            warn!("Failed to exec uninstaller: {e}");
        }

        // Clear module configs before removing module directory
        if let Err(e) = crate::module_config::clear_module_configs(module_id) {
            warn!("Failed to clear configs for {module_id}: {e}");
        }

        // Finally remove the module directory
        if let Err(e) = remove_dir_all(module) {
            warn!("Failed to remove {}: {e}", module.display());
        }

        Ok(())
    })?;

    // collect remaining modules, if none, clean up metamodule record
    let remaining_modules: Vec<_> = std::fs::read_dir(defs::MODULE_DIR)?
        .filter_map(std::result::Result::ok)
        .filter(|entry| entry.path().join("module.prop").exists())
        .collect();

    if remaining_modules.is_empty() {
        info!("no remaining modules.");
    }

    Ok(())
}

const METADATA_FILE_CON: &str = "u:object_r:metadata_file:s0";

// Prefer /metadata/watchdog/ when present, else /metadata.
fn preinit_ksu_dir() -> &'static str {
    if Path::new("/metadata/watchdog").is_dir() {
        defs::PREINIT_DIR_WATCHDOG
    } else {
        defs::PREINIT_DIR_DEFAULT
    }
}

fn collect_rc_files<P: AsRef<Path>>(
    dir: P,
    mod_id: Option<&str>,
    out: &mut dyn Write,
) -> Result<()> {
    let dir = dir.as_ref();
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Ok(());
    };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let path = entry.path();
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_file() && path.extension().and_then(|s| s.to_str()) == Some("rc") {
            if let Some(mod_id) = mod_id {
                writeln!(out, "# === from {mod_id}:{} ===", path.display())?;
            } else {
                // Although the rc file itself is not executable, we still use its executable bit as a switch.
                if !is_executable(&path) {
                    continue;
                }
                writeln!(out, "# === from {} ===", path.display())?;
            }
            let content = std::fs::read(&path)
                .with_context(|| format!("Failed to read rc {}", path.display()))?;
            out.write_all(&content)?;
            writeln!(out)?;
        }
    }
    Ok(())
}

/// Rebuild PREINITDIR/modules.rc by concatenating *.rc from every enabled
/// module. The kernel-side read hook splices this file into init.rc on the
/// next boot.
pub fn regenerate_preinit_rc() -> Result<()> {
    let preinit_str = preinit_ksu_dir();
    let preinit_dir = Path::new(preinit_str);
    std::fs::create_dir_all(preinit_dir)
        .with_context(|| format!("Failed to create {}", preinit_dir.display()))?;

    let tmp_path_buf = preinit_dir.join(defs::MODULES_RC_TMP_FILE);
    let out_path_buf = preinit_dir.join(defs::MODULES_RC_FILE);
    let tmp_path = tmp_path_buf.as_path();
    let out_path = out_path_buf.as_path();

    {
        let mut tmp = File::create(tmp_path)
            .with_context(|| format!("Failed to create {}", tmp_path.display()))?;

        // collect modules in alphabetical order, with their effective module path in the next boot
        let mut modules: BTreeMap<String, Option<PathBuf>> = BTreeMap::new();
        // collect common initrc first
        collect_rc_files(Path::new(defs::ADB_DIR).join("initrc.d"), None, &mut tmp)?;
        // modules_update/ first so freshly-installed modules win on id collision.
        for src_dir in [defs::MODULE_UPDATE_DIR, defs::MODULE_DIR] {
            let Ok(entries) = std::fs::read_dir(src_dir) else {
                continue;
            };
            for entry in entries.flatten() {
                let module_path = entry.path();
                if !module_path.is_dir() {
                    continue;
                }
                let Some(id) = module_path.file_name().and_then(|s| s.to_str()) else {
                    continue;
                };
                let id = id.to_string();
                if module_path.join(defs::DISABLE_FILE_NAME).exists()
                    || module_path.join(defs::REMOVE_FILE_NAME).exists()
                {
                    modules.insert(id, None);
                    continue;
                }
                modules.entry(id).or_insert(Some(module_path));
            }
        }
        // Built-in modules always contribute their rc and are never listed in
        // the panel, so they ignore any disable/remove marker —— 但用户关掉的模块
        // 连 rc 都不该贡献（它的运行目录此时也不存在，这里是双保险）。
        if let Ok(entries) = std::fs::read_dir(defs::BUILTIN_MODULE_DIR) {
            for entry in entries.flatten() {
                let module_path = entry.path();
                if !module_path.is_dir() {
                    continue;
                }
                let Some(id) = module_path.file_name().and_then(|s| s.to_str()) else {
                    continue;
                };
                if !builtin_enabled(id) {
                    continue;
                }
                modules.insert(id.to_string(), Some(module_path));
            }
        }
        for (id, path) in modules {
            if let Some(path) = path {
                collect_rc_files(path.join(defs::MODULE_INIT_RC_DIR), Some(&id), &mut tmp)?;
            }
        }
        tmp.sync_all()?;
    }

    std::fs::rename(tmp_path, out_path).with_context(|| {
        format!(
            "Failed to rename {} -> {}",
            tmp_path.display(),
            out_path.display()
        )
    })?;

    // SELinux label so the kernel's filp_open in init context can read it.
    if let Err(e) = crate::restorecon::lsetfilecon(out_path, METADATA_FILE_CON) {
        debug!("set context on {} failed: {e}", out_path.display());
    }

    // Clear stale file at the other candidate path.
    let stale_dir = if preinit_str == defs::PREINIT_DIR_WATCHDOG {
        defs::PREINIT_DIR_DEFAULT
    } else {
        defs::PREINIT_DIR_WATCHDOG
    };
    std::fs::remove_file(Path::new(stale_dir).join(defs::MODULES_RC_FILE)).ok();

    Ok(())
}

pub fn handle_updated_modules() -> Result<()> {
    let modules_root = Path::new(MODULE_DIR);
    foreach_module(ModuleType::Updated, |updated_module| {
        if !updated_module.is_dir() {
            return Ok(());
        }

        if let Some(name) = updated_module.file_name() {
            let module_dir = modules_root.join(name);
            // Preserve the "remove" marker so a pending uninstall still takes
            // effect, but do NOT carry over the "disable" marker: the installer
            // (installer.sh + Rust cleanup) explicitly clears it, and carrying
            // it forward makes freshly-flashed modules appear "disabled by
            // default" — the user's #1 complaint about ZY modules.
            let removed = if module_dir.exists() {
                let r = module_dir.join(defs::REMOVE_FILE_NAME).exists();
                remove_dir_all(&module_dir)?;
                r
            } else {
                false
            };
            rename(updated_module, &module_dir)?;
            if removed {
                let path = module_dir.join(defs::REMOVE_FILE_NAME);
                if let Err(e) = ensure_file_exists(&path) {
                    warn!("Failed to create {}: {e}", path.display());
                }
            }
        }
        Ok(())
    })?;
    Ok(())
}

/// Zygisk Next's own persistent directory (its runtime state, not a module).
const ZYGISK_STATE_DIR: &str = "/data/adb/zygisksu";

/// Remove the failure leftovers a Zygisk Next style module writes when its
/// daemon cannot start.
///
/// `disable` + `.abort_msg` **together** are the signature of an aborted
/// daemon start and get self-healed here. A `disable` marker that stands alone
/// is a *user* decision (manager toggle) and is deliberately respected —
/// recovery from a bad boot must not silently re-enable what the user switched
/// off on purpose. An `.abort_msg` without `disable` is kept as a diagnostic.
pub fn clear_zygisk_failure_markers() {
    // Directory ids that look like Zygisk Next (`zygisksu`, `Zygisk-Next`, ...)
    // are matched on the substring "ygisk", which covers both spellings.
    for root in [
        defs::MODULE_DIR,
        defs::MODULE_UPDATE_DIR,
        defs::BUILTIN_MODULE_DIR,
    ] {
        let Ok(entries) = std::fs::read_dir(root) else {
            continue;
        };
        for entry in entries.flatten() {
            let name = entry.file_name();
            if name.to_string_lossy().contains("ygisk") {
                clear_zygisk_markers_in(&entry.path());
            }
        }
    }

    // Zygisk Next also stores a copy of these markers outside the module tree.
    clear_zygisk_markers_in(Path::new(ZYGISK_STATE_DIR));
}

/// Self-heal the abort signature (`disable` + `.abort_msg`) in one directory.
fn clear_zygisk_markers_in(dir: &Path) {
    let abort_path = dir.join(defs::ABORT_MSG_FILE_NAME);
    let aborted = std::fs::symlink_metadata(&abort_path).is_ok_and(|meta| meta.is_file());
    if !aborted {
        return;
    }
    for marker in [defs::DISABLE_FILE_NAME, defs::ABORT_MSG_FILE_NAME] {
        let path = dir.join(marker);
        // `symlink_metadata` deliberately: a symlink is not a real marker.
        let is_real_file = std::fs::symlink_metadata(&path).is_ok_and(|meta| meta.is_file());
        if !is_real_file {
            continue;
        }
        match std::fs::remove_file(&path) {
            Ok(()) => info!("Cleared stale Zygisk marker: {}", path.display()),
            Err(e) => warn!("Failed to remove {}: {e}", path.display()),
        }
    }
}

fn install_module_to_system(zip: &str) -> Result<()> {
    ensure_boot_completed()?;

    // print banner
    println!(include_str!("banner"));

    assets::ensure_binaries(false).with_context(|| "Failed to extract assets")?;

    // first check if working dir is usable
    ensure_dir_exists(defs::WORKING_DIR).with_context(|| "Failed to create working dir")?;
    ensure_dir_exists(assets::binary_dir()).with_context(|| "Failed to create bin dir")?;

    // read the module_id from zip, if failed it will return early.
    let mut buffer: Vec<u8> = Vec::new();
    let entry_path = PathBuf::from_str("module.prop")?;
    let zip_path = PathBuf::from_str(zip)?;
    let zip_path = zip_path.canonicalize()?;
    zip_extract_file_to_memory(&zip_path, &entry_path, &mut buffer)?;

    let module_prop = parse_module_prop(&buffer);
    info!("module prop: {module_prop:?}");

    let Some(module_id) = module_prop.get("id") else {
        bail!("module id not found in module.prop!");
    };
    let module_id = module_id.trim();

    // Validate module_id format
    validate_module_id(module_id)
        .with_context(|| format!("Invalid module ID in module.prop: '{module_id}'"))?;

    // Check if this module is a metamodule
    let is_metamodule = metamodule::is_metamodule(&module_prop);

    // Check if it's safe to install regular module
    if !is_metamodule && let Err(is_disabled) = metamodule::check_install_safety() {
        println!("\n❌ Installation Blocked");
        println!("┌────────────────────────────────");
        println!("│ A metamodule with custom installer is active");
        println!("│");
        if is_disabled {
            println!("│ Current state: Disabled");
            println!("│ Action required: Re-enable or uninstall it, then reboot");
        } else {
            println!("│ Current state: Pending changes");
            println!("│ Action required: Reboot to apply changes first");
        }
        println!("└─────────────────────────────────\n");
        bail!("Metamodule installation blocked");
    }

    // All modules (including metamodules) are installed to MODULE_UPDATE_DIR
    let updated_dir = Path::new(defs::MODULE_UPDATE_DIR).join(module_id);

    if is_metamodule {
        info!("Installing metamodule: {module_id}");

        // Check if there's already a metamodule installed
        if metamodule::has_metamodule()
            && let Some(existing_path) = metamodule::get_metamodule_path()
        {
            let existing_id = read_module_prop(&existing_path)
                .ok()
                .and_then(|m| m.get("id").cloned())
                .unwrap_or_else(|| "unknown".to_string());

            if existing_id != module_id {
                println!("\n❌ Installation Failed");
                println!("┌────────────────────────────────");
                println!("│ A metamodule is already installed");
                println!("│   Current metamodule: {existing_id}");
                println!("│");
                println!("│ Only one metamodule can be active at a time.");
                println!("│");
                println!("│ To install this metamodule:");
                println!("│   1. Uninstall the current metamodule");
                println!("│   2. Reboot your device");
                println!("│   3. Install the new metamodule");
                println!("└─────────────────────────────────\n");
                bail!("Cannot install multiple metamodules");
            }
        }
    }

    let zip_uncompressed_size = get_zip_uncompressed_size(zip)?;
    info!(
        "zip uncompressed size: {}",
        humansize::format_size(zip_uncompressed_size, humansize::DECIMAL)
    );
    println!(
        "- Module size: {}",
        humansize::format_size(zip_uncompressed_size, humansize::DECIMAL)
    );

    // Ensure module directory exists and set SELinux context
    ensure_dir_exists(defs::MODULE_UPDATE_DIR)?;
    setsyscon(defs::MODULE_UPDATE_DIR)?;

    // Prepare target directory
    println!("- Installing to {}", updated_dir.display());
    ensure_clean_dir(&updated_dir)?;
    info!("target dir: {}", updated_dir.display());

    // Extract zip to target directory
    println!("- Extracting module files");
    let file = File::open(zip)?;
    let mut archive = zip::ZipArchive::new(file)?;
    archive.extract(&updated_dir)?;

    // Set permission and selinux context for $MOD/system
    let module_system_dir = updated_dir.join("system");
    if module_system_dir.exists() {
        set_permissions(&module_system_dir, Permissions::from_mode(0o755))?;
        restore_syscon(&module_system_dir)?;
    }

    // Execute install script
    println!("- Running module installer");
    exec_install_script(zip, is_metamodule, module_id)?;

    let module_dir = Path::new(MODULE_DIR).join(module_id);
    ensure_dir_exists(&module_dir)?;

    // Clear stale control markers from both the freshly extracted payload and
    // the currently active module dir, so that flashing a module always leaves
    // it enabled:
    //   * installer.sh only removes $NVBASE/modules/$MODID/{disable,remove}
    //     (the active dir) and never touches $MODPATH, so a zip shipping a
    //     root level "disable" file would carry it into the active dir when
    //     the payload is activated on the next boot.
    //   * if customize.sh aborted before installer.sh reached that cleanup,
    //     markers left over from a previous install survive here and
    //     handle_updated_modules() then re-creates them for the new version.
    for dir in [module_dir.as_path(), updated_dir.as_path()] {
        for marker in [defs::DISABLE_FILE_NAME, defs::REMOVE_FILE_NAME] {
            let path = dir.join(marker);
            if path.exists() {
                if let Err(e) = std::fs::remove_file(&path) {
                    warn!("Failed to remove {}: {e}", path.display());
                } else {
                    info!("Cleared stale marker: {}", path.display());
                }
            }
        }
    }

    copy(
        updated_dir.join("module.prop"),
        module_dir.join("module.prop"),
    )?;
    ensure_file_exists(module_dir.join(UPDATE_FILE_NAME))?;

    // Create symlink for metamodule
    if is_metamodule {
        println!("- Creating metamodule symlink");
        metamodule::ensure_symlink(&module_dir)?;
    }

    println!("- Module installed successfully!");
    info!("Module {module_id} installed successfully!");

    Ok(())
}

pub fn install_module(zip: &str) -> Result<()> {
    ksucalls::ensure_uapi_version_matched()?;

    let result = install_module_to_system(zip);
    if let Err(ref e) = result {
        println!("- Error: {e}");
    } else if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }
    result
}

pub fn undo_uninstall_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    // Remove the remove mark
    let remove_file = module_path.join(defs::REMOVE_FILE_NAME);
    if remove_file.exists() {
        std::fs::remove_file(&remove_file)
            .with_context(|| format!("Failed to delete remove file for module '{id}'"))?;
        info!("Removed the remove mark for module {id}");
    }

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn uninstall_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    // Mark for removal
    let remove_file = module_path.join(defs::REMOVE_FILE_NAME);
    File::create(remove_file).with_context(|| "Failed to create remove file")?;

    info!("Module {id} marked for removal");

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn run_action(id: &str) -> Result<()> {
    validate_module_id(id)?;
    ksucalls::ensure_uapi_version_matched()?;

    let action_script_path = format!("/data/adb/modules/{id}/action.sh");
    exec_script(&action_script_path, ScriptWait::Forever)
}

pub fn enable_module(id: &str) -> Result<()> {
    validate_module_id(id)?;

    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    let disable_path = module_path.join(defs::DISABLE_FILE_NAME);
    if disable_path.exists() {
        std::fs::remove_file(&disable_path).with_context(|| {
            format!("Failed to remove disable file: {}", disable_path.display())
        })?;
        info!("Module {id} enabled");
    }

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn disable_module(id: &str) -> Result<()> {
    let module_path = Path::new(defs::MODULE_DIR).join(id);
    ensure!(module_path.exists(), "Module {id} not found");

    let disable_path = module_path.join(defs::DISABLE_FILE_NAME);
    ensure_file_exists(disable_path)?;

    // A user-disabled module must survive the Zygisk self-heal at next boot,
    // which re-enables anything carrying the abort signature (`disable` +
    // `.abort_msg`). Dropping a leftover `.abort_msg` here marks this disable
    // as an explicit user decision. The marker is Zygisk-owned; no KernelSU
    // module system uses it.
    let abort_path = module_path.join(defs::ABORT_MSG_FILE_NAME);
    if abort_path.is_file() {
        let _ = std::fs::remove_file(&abort_path);
    }

    info!("Module {id} disabled");

    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }

    Ok(())
}

pub fn disable_all_modules() -> Result<()> {
    mark_all_modules(defs::DISABLE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }
    Ok(())
}

pub fn uninstall_all_modules() -> Result<()> {
    info!("Uninstalling all modules");
    mark_all_modules(defs::REMOVE_FILE_NAME)?;
    if let Err(e) = regenerate_preinit_rc() {
        warn!("regenerate preinit rc failed: {e}");
    }
    Ok(())
}

fn mark_all_modules(flag_file: &str) -> Result<()> {
    // we assume the module dir is already mounted
    let dir = std::fs::read_dir(defs::MODULE_DIR)?;
    for entry in dir.flatten() {
        let path = entry.path();
        let flag = path.join(flag_file);
        if let Err(e) = ensure_file_exists(flag) {
            warn!("Failed to mark module: {}: {e}", path.display());
        }
    }

    Ok(())
}

/// Parse module.prop content into a key-value map.
///
/// Uses plain `key=value` line parsing (split on the first '='), which is
/// more tolerant than java_properties for values containing spaces, parens or
/// commas (e.g. `version=1.3.4 (746-d1b76b3-release)`, `author=5ec1cff, Nullptr`).
fn parse_module_prop(content: &[u8]) -> HashMap<String, String> {
    let text = String::from_utf8_lossy(content);
    let mut prop_map: HashMap<String, String> = HashMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        prop_map.insert(key.trim().to_string(), value.trim().to_string());
    }
    prop_map
}

/// Read module.prop from the given module path and return as a HashMap
pub fn read_module_prop(module_path: &Path) -> Result<HashMap<String, String>> {
    let module_prop = module_path.join("module.prop");
    ensure!(
        module_prop.exists(),
        "module.prop not found in {}",
        module_path.display()
    );

    let content = std::fs::read(&module_prop)
        .with_context(|| format!("Failed to read module.prop: {}", module_prop.display()))?;

    Ok(parse_module_prop(&content))
}

/// Resolve a module icon path to an absolute on-disk path
fn resolve_module_icon_path(
    module_prop_map: &mut HashMap<String, String>,
    key: &str,
    module_path: &Path,
) {
    if let Some(icon_value) = module_prop_map.get(key) {
        let icon_value = icon_value.trim();
        if icon_value.is_empty() {
            return;
        }
        let path = std::path::Path::new(icon_value);
        if path.is_absolute() {
            log::warn!(
                "Rejected {} with absolute path for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                icon_value
            );
            return;
        }
        let has_parent = path
            .components()
            .any(|c| matches!(c, std::path::Component::ParentDir));
        if has_parent {
            log::warn!(
                "Rejected {} with parent traversal for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                icon_value
            );
            return;
        }
        let candidate = module_path.join(path);
        if candidate.exists() && candidate.is_file() {
            if let Some(s) = candidate.to_str() {
                module_prop_map.insert(key.to_owned(), s.to_string());
            }
        } else {
            log::debug!(
                "{} not found for module {}: {}",
                key,
                module_prop_map.get("id").map_or("", String::as_str),
                candidate.display()
            );
        }
    }
}

fn list_module(path: &str) -> Vec<HashMap<String, String>> {
    // Load all module configs once to minimize I/O overhead
    let all_configs = match crate::module_config::get_all_module_configs() {
        Ok(configs) => configs,
        Err(e) => {
            warn!("Failed to load module configs: {e}");
            HashMap::new()
        }
    };

    // first check enabled modules
    let dir = std::fs::read_dir(path);
    let Ok(dir) = dir else {
        return Vec::new();
    };

    let mut modules: Vec<HashMap<String, String>> = Vec::new();

    for entry in dir.flatten() {
        let path = entry.path();
        info!("path: {}", path.display());

        if !path.join("module.prop").exists() {
            continue;
        }

        let mut module_prop_map = match read_module_prop(&path) {
            Ok(prop) => prop,
            Err(e) => {
                warn!("Failed to read module.prop for {}: {e}", path.display());
                continue;
            }
        };

        // If id is missing or empty, use directory name as fallback
        if !module_prop_map.contains_key("id") || module_prop_map["id"].is_empty() {
            if let Some(id) = entry.file_name().to_str() {
                info!("Use dir name as module id: {id}");
                module_prop_map.insert("id".to_owned(), id.to_owned());
            } else {
                info!("Failed to get module id from dir name");
                continue;
            }
        }

        // Always materialize name/author/version/versionCode/description so the
        // manager never renders "Unknown" for a module whose module.prop omits
        // them (or had them rewritten/truncated by customize.sh). The fallback
        // name is computed up-front: calling module_prop_map.get() inside an
        // entry() closure would conflict with the mutable borrow entry() holds.
        let fallback_name = module_prop_map.get("id").cloned().unwrap_or_default();
        module_prop_map
            .entry("name".to_owned())
            .or_insert(fallback_name);
        module_prop_map.entry("author".to_owned()).or_default();
        module_prop_map.entry("version".to_owned()).or_default();
        module_prop_map
            .entry("versionCode".to_owned())
            .or_insert_with(|| "0".to_owned());
        module_prop_map.entry("description".to_owned()).or_default();

        // Add enabled, update, remove, web, action flags
        let enabled = !path.join(defs::DISABLE_FILE_NAME).exists();
        let update = path.join(defs::UPDATE_FILE_NAME).exists();
        let remove = path.join(defs::REMOVE_FILE_NAME).exists();
        let web = path.join(defs::MODULE_WEB_DIR).exists();
        let action = path.join(defs::MODULE_ACTION_SH).exists();
        let need_mount = path.join("system").exists() && !path.join("skip_mount").exists();

        module_prop_map.insert("enabled".to_owned(), enabled.to_string());
        module_prop_map.insert("update".to_owned(), update.to_string());
        module_prop_map.insert("remove".to_owned(), remove.to_string());
        module_prop_map.insert("web".to_owned(), web.to_string());
        module_prop_map.insert("action".to_owned(), action.to_string());
        module_prop_map.insert("mount".to_owned(), need_mount.to_string());

        resolve_module_icon_path(&mut module_prop_map, "actionIcon", &path);
        resolve_module_icon_path(&mut module_prop_map, "webuiIcon", &path);

        // Apply module config overrides and extract managed features
        if let Some(module_id) = module_prop_map.get("id")
            && let Some(config) = all_configs.get(module_id.as_str())
        {
            // Apply override.description
            if let Some(desc) = config.get("override.description") {
                module_prop_map.insert("description".to_owned(), desc.clone());
            }

            // Extract managed features from manage.* config entries
            let managed_features: Vec<String> = config
                .iter()
                .filter_map(|(k, v)| {
                    if k.starts_with("manage.") && crate::module_config::parse_bool_config(v) {
                        k.strip_prefix("manage.")
                            .map(std::string::ToString::to_string)
                    } else {
                        None
                    }
                })
                .collect();

            if !managed_features.is_empty() {
                module_prop_map.insert("managedFeatures".to_owned(), managed_features.join(","));
            }
        }

        modules.push(module_prop_map);
    }

    modules
}

pub fn list_modules() -> Result<()> {
    let modules = list_module(defs::MODULE_DIR);
    println!("{}", serde_json::to_string_pretty(&modules)?);
    Ok(())
}

/// Get all managed features from active modules
/// Modules declare managed features via config system (manage.<feature>=true)
/// Returns: HashMap<ModuleId, Vec<ManagedFeature>>
pub fn get_managed_features() -> Result<HashMap<String, Vec<String>>> {
    let mut managed_features_map: HashMap<String, Vec<String>> = HashMap::new();

    foreach_active_module(|module_path| {
        // Get module ID
        let Some(module_id) = module_path.file_name().and_then(|n| n.to_str()) else {
            warn!(
                "Failed to get module id from path: {}",
                module_path.display()
            );
            return Ok(());
        };

        // Read module config
        let config = match crate::module_config::merge_configs(module_id) {
            Ok(c) => c,
            Err(e) => {
                warn!("Failed to merge configs for module '{module_id}': {e}");
                return Ok(()); // Skip this module
            }
        };

        // Extract manage.* config entries
        let mut feature_list = Vec::new();
        for (key, value) in &config {
            if key.starts_with("manage.") {
                // Parse feature name
                if let Some(feature_name) = key.strip_prefix("manage.")
                    && crate::module_config::parse_bool_config(value)
                {
                    feature_list.push(feature_name.to_string());
                }
            }
        }

        if !feature_list.is_empty() {
            managed_features_map.insert(module_id.to_string(), feature_list);
        }

        Ok(())
    })?;

    Ok(managed_features_map)
}
