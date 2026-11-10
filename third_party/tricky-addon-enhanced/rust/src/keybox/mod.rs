pub mod sources;
pub mod validate;
pub mod generate;

use std::fmt;
use std::path::{Path, PathBuf};
use std::str::FromStr;

use anyhow::{Context, Result, bail};
use serde::{Serialize, Deserialize};
use tracing::{info, warn, error};

use crate::cli::KeyboxAction;
use crate::config::Config;
use crate::platform::fs::atomic_write;

const TARGET_KEYBOX: &str = "/data/adb/tricky_store/keybox.xml";
const BACKUP_KEYBOX: &str = "/data/adb/tricky_store/keybox.xml.bak";

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum KeyboxSource {
    #[default]
    Yurikey,
    Upstream,
    Custom,
}

impl fmt::Display for KeyboxSource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Yurikey => write!(f, "yurikey"),
            Self::Upstream => write!(f, "upstream"),
            Self::Custom => write!(f, "custom"),
        }
    }
}

impl FromStr for KeyboxSource {
    type Err = anyhow::Error;
    fn from_str(s: &str) -> Result<Self> {
        match s.to_ascii_lowercase().as_str() {
            "yurikey" => Ok(Self::Yurikey),
            "upstream" => Ok(Self::Upstream),
            "custom" => Ok(Self::Custom),
            _ => bail!("unknown keybox source: {s}"),
        }
    }
}

#[derive(Debug, Serialize)]
pub struct SourceInfo {
    pub name: String,
    pub active: bool,
}

#[derive(Debug)]
pub struct FetchResult {
    pub source: &'static str,
}

/// 当前生效 keybox 的健康度。
///
/// 存在的理由：`fetch()` 过去只看"链能不能验过"，于是出现两类退化——
/// ① 上游换了一代更早到期的箱子，我们照装，等于提前自杀；
/// ② 箱子临近到期时，检测工具拿到的每一张伪造链都带过期叶子，比"不伪造"更糟
///    （工具会明确报"证书已过期/已吊销"）。
/// 验机工具的路由策略必须以这份健康度为准，而不是"文件存在"。
#[derive(Debug, Clone, Serialize)]
pub struct KeyboxHealth {
    pub ok: bool,
    pub days_left: i64,
    pub root: String,
    pub expires_soon: bool,
}

pub fn current_health() -> KeyboxHealth {
    health_of(Path::new(TARGET_KEYBOX))
}

fn health_of(path: &Path) -> KeyboxHealth {
    let missing = KeyboxHealth {
        ok: false,
        days_left: -1,
        root: "missing".to_string(),
        expires_soon: true,
    };
    // 健康检查走离线校验：断网时 load_revocation() 会等 12 秒，而这条路径在开机
    // 和每 5 分钟的巡检里都会跑。内置吊销表足以判断"过期/根类型/私钥匹配"。
    let report = match validate::validate_file_full_offline(path) {
        Ok(report) => report,
        Err(_) => return missing,
    };
    let root = report
        .keys
        .iter()
        .filter(|k| k.ok)
        .map(|k| k.root_type.as_snake_case())
        .next()
        .unwrap_or("unknown")
        .to_string();
    KeyboxHealth {
        ok: report.ok,
        days_left: report.leaf_days_left.unwrap_or(-1),
        root,
        expires_soon: report.expires_soon,
    }
}

pub fn handle_keybox(action: KeyboxAction, cfg: &Config) -> Result<()> {
    match action {
        KeyboxAction::Health => {
            let health = current_health();
            // 一行三列，给 init/service 脚本直接 `set -- $line` 用：
            //   ok(1/0) days_left root_type
            println!(
                "{} {} {}",
                if health.ok { 1 } else { 0 },
                health.days_left,
                health.root
            );
            Ok(())
        }
        KeyboxAction::Fetch => {
            let result = fetch(cfg)?;
            println!("keybox fetched from {}", result.source);
            Ok(())
        }
        KeyboxAction::Validate { path } => {
            use std::io::Write;
            let target = path.as_deref().unwrap_or(TARGET_KEYBOX);
            let report = validate::validate_file_full(Path::new(target))?;
            let mut stdout = std::io::stdout().lock();
            writeln!(stdout, "{}", serde_json::to_string_pretty(&report)?)?;
            stdout.flush()?;
            if report.ok {
                Ok(())
            } else {
                bail!("keybox validation failed: {target}")
            }
        }
        KeyboxAction::SetCustom { path } => {
            set_custom(Path::new(&path))?;
            println!("custom keybox installed from {path}");
            Ok(())
        }
        KeyboxAction::Sources => {
            let sources = get_sources(cfg);
            let json = serde_json::to_string_pretty(&sources)?;
            println!("{json}");
            Ok(())
        }
        KeyboxAction::Generate => {
            generate::generate_and_install()?;
            println!("device keybox generated");
            Ok(())
        }
        KeyboxAction::Backup => {
            backup()?;
            println!("keybox backed up");
            Ok(())
        }
    }
}

pub fn fetch(config: &Config) -> Result<FetchResult> {
    let preferred = KeyboxSource::from_str(&config.keybox.source).unwrap_or_else(|e| {
        warn!("keybox source {:?} invalid ({e}); defaulting to yurikey", config.keybox.source);
        KeyboxSource::default()
    });
    let custom_url = &config.keybox.custom_url;

    let order = build_source_order(preferred);
    let existing_hash = current_keybox_hash();

    for source in &order {
        let result = match source {
            KeyboxSource::Yurikey => sources::fetch_yurikey(),
            KeyboxSource::Upstream => sources::fetch_upstream(),
            KeyboxSource::Custom => {
                if custom_url.is_empty() {
                    continue;
                }
                sources::fetch_custom_url(custom_url)
            }
        };

        match result {
            Ok(data) => {
                let report = match validate::validate_full(&data) {
                    Ok(r) => r,
                    Err(e) => {
                        warn!("keybox from {} parse failed: {e}", source);
                        continue;
                    }
                };
                if !report.ok {
                    let summary = report
                        .keys
                        .iter()
                        .filter(|k| !k.ok)
                        .map(|k| format!("{}={}", k.algorithm, k.errors.join(",")))
                        .collect::<Vec<_>>()
                        .join("; ");
                    warn!("keybox from {} rejected: {summary}", source);
                    continue;
                }
                let root_type = report
                    .keys
                    .first()
                    .map(|k| k.root_type.as_snake_case())
                    .unwrap_or("unknown");
                // 防降级：候选箱子的叶子如果比在用的更早到期，装上去只会让"过期证书"
                // 这条证据提前出现。唯一例外是在用的箱子已经不合法（那时任何合法箱子都更好）。
                let candidate_days = report.leaf_days_left.unwrap_or(-1);
                let installed = health_of(Path::new(TARGET_KEYBOX));
                if installed.ok
                    && candidate_days >= 0
                    && candidate_days < installed.days_left
                {
                    warn!(
                        "keybox from {} expires in {}d, sooner than the installed one ({}d); keeping the installed keybox",
                        source, candidate_days, installed.days_left
                    );
                    continue;
                }
                let new_hash = sources::compute_sha256(&data);
                if !new_hash.is_empty() && Some(&new_hash) == existing_hash.as_ref() {
                    info!("keybox from {} (root={root_type}) identical to installed, skipping", source);
                    return Ok(FetchResult { source: source_label(source) });
                }
                install_data(&data)?;
                info!("keybox installed from {} (root={root_type})", source);
                return Ok(FetchResult { source: source_label(source) });
            }
            Err(e) => {
                warn!("keybox source {} failed: {e}", source);
            }
        }
    }

    if has_valid_existing_keybox() {
        warn!("all remote sources failed, preserving existing keybox");
        return Ok(FetchResult { source: "existing" });
    }

    bail!("all keybox sources failed")
}

pub fn backup() -> Result<()> {
    let target = Path::new(TARGET_KEYBOX);
    if !target.exists() {
        bail!("no keybox to backup at {}", TARGET_KEYBOX);
    }
    let bak = Path::new(BACKUP_KEYBOX);
    if bak.exists() {
        let bak1 = PathBuf::from(format!("{}.1", BACKUP_KEYBOX));
        std::fs::rename(bak, &bak1)
            .context("failed to rotate backup")?;
    }
    std::fs::copy(target, bak)
        .context("failed to create keybox backup")?;
    info!("keybox backed up to {}", BACKUP_KEYBOX);
    Ok(())
}

pub fn set_custom(path: &Path) -> Result<()> {
    if !path.exists() {
        bail!("custom keybox not found: {}", path.display());
    }
    let data = std::fs::read(path)?;
    let report = validate::validate_full(&data)
        .with_context(|| format!("validating {}", path.display()))?;
    if !report.ok {
        let summary = report
            .keys
            .iter()
            .filter(|k| !k.ok)
            .map(|k| format!("Keybox#{}/Key#{} ({}): {}", k.keybox_index, k.key_index, k.algorithm, k.errors.join("; ")))
            .collect::<Vec<_>>()
            .join(" | ");
        bail!("custom keybox failed validation: {summary}");
    }
    install_data(&data)?;
    let root_type = report
        .keys
        .first()
        .map(|k| k.root_type.as_snake_case())
        .unwrap_or("unknown");
    info!("custom keybox installed from {} (root={root_type})", path.display());
    Ok(())
}

pub fn get_sources(config: &Config) -> Vec<SourceInfo> {
    let active = KeyboxSource::from_str(&config.keybox.source).unwrap_or_else(|e| {
        warn!("keybox source {:?} invalid ({e}); defaulting to yurikey", config.keybox.source);
        KeyboxSource::default()
    });
    vec![
        SourceInfo { name: "yurikey".into(), active: active == KeyboxSource::Yurikey },
        SourceInfo { name: "upstream".into(), active: active == KeyboxSource::Upstream },
        SourceInfo { name: "custom".into(), active: active == KeyboxSource::Custom },
    ]
}

fn build_source_order(preferred: KeyboxSource) -> Vec<KeyboxSource> {
    let all = [
        KeyboxSource::Yurikey,
        KeyboxSource::Upstream,
        KeyboxSource::Custom,
    ];
    let mut order = vec![preferred];
    for s in all {
        if s != preferred {
            order.push(s);
        }
    }
    order
}

fn install_data(data: &[u8]) -> Result<()> {
    if Path::new(TARGET_KEYBOX).exists() {
        if let Err(e) = backup() {
            error!("backup failed before install: {e}");
            bail!("aborting keybox install: backup failed");
        }
    }
    atomic_write(Path::new(TARGET_KEYBOX), data)
        .context("failed to write keybox")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(
            TARGET_KEYBOX,
            std::fs::Permissions::from_mode(0o600),
        );
    }
    Ok(())
}

fn current_keybox_hash() -> Option<String> {
    let data = std::fs::read(TARGET_KEYBOX).ok()?;
    let hash = sources::compute_sha256(&data);
    if hash.is_empty() { None } else { Some(hash) }
}

fn has_valid_existing_keybox() -> bool {
    Path::new(TARGET_KEYBOX).exists()
        && validate::validate_file(Path::new(TARGET_KEYBOX)).is_ok()
}

fn source_label(s: &KeyboxSource) -> &'static str {
    match s {
        KeyboxSource::Yurikey => "yurikey",
        KeyboxSource::Upstream => "upstream",
        KeyboxSource::Custom => "custom",
    }
}
