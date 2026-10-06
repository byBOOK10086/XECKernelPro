//! Fail-closed transaction tracking for direct Android boot image flashing.
//!
//! This covers the part of a boot failure that userspace can observe: the
//! kernel and init must reach `ksud` with `/data` mounted. A bootloader or
//! pre-init failure still requires fastboot/recovery rescue.

use anyhow::{Context, Result, bail, ensure};
use std::collections::BTreeMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use crate::{boot_patch, defs};

const STATE_VERSION: &str = "1";
const MAX_RECORDS: usize = 3;
/// Boot, init_boot and vendor_boot images sit far below this on every shipping
/// device. The state file is untrusted input: a bogus size field must abort
/// the transaction instead of driving a huge allocation.
const MAX_IMAGE_SIZE: u64 = 256 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
struct BackupRecord {
    partition: String,
    suffix: String,
    backup_name: String,
    size: u64,
    sha256: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Phase {
    Prepared,
    Armed,
    Observed,
    RollingBack,
}

impl Phase {
    fn as_str(&self) -> &'static str {
        match self {
            Self::Prepared => "prepared",
            Self::Armed => "armed",
            Self::Observed => "observed",
            Self::RollingBack => "rolling_back",
        }
    }

    fn parse(value: &str) -> Result<Self> {
        match value {
            "prepared" => Ok(Self::Prepared),
            "armed" => Ok(Self::Armed),
            "observed" => Ok(Self::Observed),
            "rolling_back" => Ok(Self::RollingBack),
            _ => bail!("unknown boot transaction phase: {value}"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct BootTxn {
    phase: Phase,
    slot_suffix: String,
    target_partition: String,
    target_size: u64,
    target_sha256: String,
    boot_id: String,
    ota: bool,
    records: Vec<BackupRecord>,
}

fn state_path() -> PathBuf {
    Path::new(defs::KSU_BACKUP_DIR).join(defs::BOOT_TXN_STATE)
}

fn state_tmp_path() -> PathBuf {
    Path::new(defs::KSU_BACKUP_DIR).join(defs::BOOT_TXN_STATE_TMP)
}

fn partition_path(partition: &str, suffix: &str) -> PathBuf {
    PathBuf::from(format!("/dev/block/by-name/{partition}{suffix}"))
}

fn validate_partition(partition: &str) -> Result<()> {
    ensure!(
        matches!(partition, "boot" | "init_boot" | "vendor_boot"),
        "invalid boot transaction partition: {partition}"
    );
    Ok(())
}

fn validate_suffix(suffix: &str) -> Result<()> {
    ensure!(
        suffix.is_empty() || suffix == "_a" || suffix == "_b",
        "invalid boot transaction slot suffix: {suffix}"
    );
    Ok(())
}

fn validate_sha256(value: &str) -> Result<()> {
    ensure!(
        value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit()),
        "invalid SHA-256"
    );
    Ok(())
}

fn current_boot_id() -> Result<String> {
    let value = std::fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .context("read kernel boot id")?;
    let value = value.trim();
    ensure!(
        !value.is_empty() && !value.contains(['\n', '\r', '=']),
        "invalid kernel boot id"
    );
    Ok(value.to_string())
}

fn sync_parent(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        File::open(parent)
            .with_context(|| format!("open parent directory {}", parent.display()))?
            .sync_all()
            .with_context(|| format!("sync parent directory {}", parent.display()))?;
    }
    Ok(())
}

fn serialize(state: &BootTxn) -> String {
    let mut out = String::new();
    out.push_str("version=1\n");
    out.push_str(&format!("phase={}\n", state.phase.as_str()));
    out.push_str(&format!("slot_suffix={}\n", state.slot_suffix));
    out.push_str(&format!("target_partition={}\n", state.target_partition));
    out.push_str(&format!("target_size={}\n", state.target_size));
    out.push_str(&format!("target_sha256={}\n", state.target_sha256));
    out.push_str(&format!("boot_id={}\n", state.boot_id));
    out.push_str(&format!("ota={}\n", if state.ota { 1 } else { 0 }));
    out.push_str(&format!("record_count={}\n", state.records.len()));
    for (index, record) in state.records.iter().enumerate() {
        out.push_str(&format!("record.{index}.partition={}\n", record.partition));
        out.push_str(&format!("record.{index}.suffix={}\n", record.suffix));
        out.push_str(&format!("record.{index}.backup={}\n", record.backup_name));
        out.push_str(&format!("record.{index}.size={}\n", record.size));
        out.push_str(&format!("record.{index}.sha256={}\n", record.sha256));
    }
    out
}

fn parse_state(data: &str) -> Result<BootTxn> {
    let mut fields = BTreeMap::new();
    for (line_number, line) in data.lines().enumerate() {
        let (key, value) = line.split_once('=').ok_or_else(|| {
            anyhow::anyhow!("malformed boot transaction line {}", line_number + 1)
        })?;
        ensure!(
            !key.is_empty() && !value.contains('\0'),
            "malformed boot transaction field"
        );
        ensure!(
            fields.insert(key.to_string(), value.to_string()).is_none(),
            "duplicate boot transaction field: {key}"
        );
    }

    let version = fields
        .remove("version")
        .context("boot transaction version missing")?;
    ensure!(
        version == STATE_VERSION,
        "unsupported boot transaction version: {version}"
    );
    let phase = Phase::parse(
        &fields
            .remove("phase")
            .context("boot transaction phase missing")?,
    )?;
    let slot_suffix = fields
        .remove("slot_suffix")
        .context("boot transaction slot missing")?;
    validate_suffix(&slot_suffix)?;
    let target_partition = fields
        .remove("target_partition")
        .context("boot transaction target missing")?;
    validate_partition(&target_partition)?;
    let target_size = fields
        .remove("target_size")
        .context("boot transaction target size missing")?
        .parse::<u64>()?;
    let target_sha256 = fields
        .remove("target_sha256")
        .context("boot transaction target hash missing")?;
    if !target_sha256.is_empty() {
        validate_sha256(&target_sha256)?;
    }
    let boot_id = fields
        .remove("boot_id")
        .context("boot transaction boot id missing")?;
    ensure!(
        !boot_id.is_empty() && !boot_id.contains(['\n', '\r', '=']),
        "invalid boot transaction boot id"
    );
    let ota = match fields
        .remove("ota")
        .context("boot transaction ota flag missing")?
        .as_str()
    {
        "0" => false,
        "1" => true,
        value => bail!("invalid boot transaction ota flag: {value}"),
    };
    let record_count = fields
        .remove("record_count")
        .context("boot transaction record count missing")?
        .parse::<usize>()?;
    ensure!(
        record_count > 0 && record_count <= MAX_RECORDS,
        "invalid boot transaction record count"
    );

    let mut records = Vec::with_capacity(record_count);
    for index in 0..record_count {
        let prefix = format!("record.{index}.");
        let partition = fields
            .remove(&format!("{prefix}partition"))
            .with_context(|| format!("missing {prefix}partition"))?;
        validate_partition(&partition)?;
        let suffix = fields
            .remove(&format!("{prefix}suffix"))
            .with_context(|| format!("missing {prefix}suffix"))?;
        validate_suffix(&suffix)?;
        ensure!(
            suffix == slot_suffix,
            "boot transaction record slot mismatch"
        );
        let backup_name = fields
            .remove(&format!("{prefix}backup"))
            .with_context(|| format!("missing {prefix}backup"))?;
        ensure!(
            backup_name.starts_with(defs::BOOT_TXN_BACKUP_PREFIX)
                && !backup_name.contains('/')
                && !backup_name.contains('\\'),
            "invalid boot transaction backup name"
        );
        let size = fields
            .remove(&format!("{prefix}size"))
            .with_context(|| format!("missing {prefix}size"))?
            .parse::<u64>()?;
        let sha256 = fields
            .remove(&format!("{prefix}sha256"))
            .with_context(|| format!("missing {prefix}sha256"))?;
        validate_sha256(&sha256)?;
        records.push(BackupRecord {
            partition,
            suffix,
            backup_name,
            size,
            sha256,
        });
    }
    ensure!(
        fields.is_empty(),
        "unknown boot transaction fields: {:?}",
        fields.keys().collect::<Vec<_>>()
    );
    ensure!(
        records
            .iter()
            .any(|record| record.partition == target_partition),
        "target backup record missing"
    );

    Ok(BootTxn {
        phase,
        slot_suffix,
        target_partition,
        target_size,
        target_sha256,
        boot_id,
        ota,
        records,
    })
}

fn load_state() -> Result<Option<BootTxn>> {
    let path = state_path();
    if !path.exists() {
        return Ok(None);
    }
    let data =
        std::fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    Ok(Some(parse_state(&data)?))
}

fn save_state(state: &BootTxn) -> Result<()> {
    std::fs::create_dir_all(defs::KSU_BACKUP_DIR)?;
    let tmp = state_tmp_path();
    let path = state_path();
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&tmp)?;
    file.write_all(serialize(state).as_bytes())?;
    file.sync_all()?;
    std::fs::rename(&tmp, &path)?;
    sync_parent(&path)
}

fn clear_state() -> Result<()> {
    let path = state_path();
    if path.exists() {
        std::fs::remove_file(&path)?;
        sync_parent(&path)?;
    }
    let tmp = state_tmp_path();
    let _ = std::fs::remove_file(tmp);
    Ok(())
}

fn copy_backup(source: &Path, backup_name: &str) -> Result<(u64, String)> {
    let destination = Path::new(defs::KSU_BACKUP_DIR).join(backup_name);
    let temporary = destination.with_extension(format!("tmp.{}", std::process::id()));
    let mut input =
        File::open(source).with_context(|| format!("open backup source {}", source.display()))?;
    ensure!(
        input.metadata()?.len() <= MAX_IMAGE_SIZE,
        "backup source {} exceeds the supported image size",
        source.display()
    );
    let mut output = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temporary)?;
    let size = std::io::copy(&mut input, &mut output)?;
    output.sync_all()?;
    std::fs::rename(&temporary, &destination)?;
    sync_parent(&destination)?;
    let actual_size = std::fs::metadata(&destination)?.len();
    ensure!(
        actual_size == size,
        "backup size changed while writing {backup_name}"
    );
    let hash = sha256::try_digest(&destination).context("hash boot transaction backup")?;
    Ok((size, hash))
}

fn read_prefix_hash(path: &Path, size: u64) -> Result<String> {
    ensure!(
        size <= MAX_IMAGE_SIZE,
        "boot image size {size} exceeds the supported limit"
    );
    let size_usize = usize::try_from(size).context("boot image is too large for verification")?;
    let input = File::open(path).with_context(|| format!("open partition {}", path.display()))?;
    let mut bytes = Vec::with_capacity(size_usize);
    input.take(size).read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() == size_usize,
        "partition {} is shorter than expected",
        path.display()
    );
    Ok(sha256::digest(&bytes))
}

fn backup_name(partition: &str, suffix: &str) -> String {
    let slot = if suffix.is_empty() {
        "current"
    } else {
        &suffix[1..]
    };
    format!("{}{slot}_{partition}", defs::BOOT_TXN_BACKUP_PREFIX)
}

/// Back up the selected slot's boot and init_boot (plus vendor_boot when it is
/// the target) byte-for-byte before a direct flash.
///
/// The rollback source is always the pre-flash partition content: the device
/// is booted from it right now, so it is by definition the last known-good
/// state, and undoing a flash restores exactly what was there before. This
/// deliberately makes no claim about the image being factory-stock — targets
/// patched by an older release carry no stock marker, and requiring one would
/// block the standard upgrade path.
pub fn prepare(slot_suffix: &str, target_partition: &str, ota: bool) -> Result<()> {
    validate_suffix(slot_suffix)?;
    validate_partition(target_partition)?;
    ensure!(
        load_state()?.is_none(),
        "a boot transaction is already pending"
    );
    let boot_id = current_boot_id()?;
    let mut partitions = vec!["boot", "init_boot"];
    if target_partition == "vendor_boot" {
        partitions.push("vendor_boot");
    }
    let mut records = Vec::new();
    for partition in partitions {
        let path = partition_path(partition, slot_suffix);
        if !path.exists() {
            continue;
        }
        let name = backup_name(partition, slot_suffix);
        let (size, sha256) = copy_backup(&path, &name)?;
        records.push(BackupRecord {
            partition: partition.to_string(),
            suffix: slot_suffix.to_string(),
            backup_name: name,
            size,
            sha256,
        });
    }
    ensure!(
        records
            .iter()
            .any(|record| record.partition == target_partition),
        "target partition is not available"
    );
    save_state(&BootTxn {
        phase: Phase::Prepared,
        slot_suffix: slot_suffix.to_string(),
        target_partition: target_partition.to_string(),
        target_size: 0,
        target_sha256: String::new(),
        boot_id,
        ota,
        records,
    })
}

/// Arm the transaction immediately before the flash syscall.
pub fn arm(patched_image: &[u8]) -> Result<()> {
    let mut state = load_state()?.context("boot transaction is not prepared")?;
    ensure!(
        state.phase == Phase::Prepared,
        "boot transaction cannot be armed from {}",
        state.phase.as_str()
    );
    state.target_size = patched_image.len() as u64;
    ensure!(
        state.target_size <= MAX_IMAGE_SIZE,
        "patched boot image exceeds the supported size"
    );
    state.target_sha256 = sha256::digest(patched_image);
    state.phase = Phase::Armed;
    save_state(&state)
}

fn restore(state: &BootTxn) -> Result<()> {
    let mut rolling = state.clone();
    rolling.phase = Phase::RollingBack;
    save_state(&rolling)?;
    for record in &state.records {
        let backup = Path::new(defs::KSU_BACKUP_DIR).join(&record.backup_name);
        ensure!(
            backup.is_file(),
            "missing boot transaction backup {}",
            backup.display()
        );
        ensure!(
            record.size <= MAX_IMAGE_SIZE,
            "boot transaction record size exceeds the supported limit"
        );
        let backup_size = std::fs::metadata(&backup)
            .with_context(|| format!("stat boot transaction backup {}", backup.display()))?
            .len();
        ensure!(
            backup_size == record.size,
            "boot transaction backup {} has an unexpected size",
            backup.display()
        );
        let hash = sha256::try_digest(&backup)?;
        ensure!(
            hash == record.sha256,
            "corrupt boot transaction backup {}",
            backup.display()
        );
        let partition = partition_path(&record.partition, &record.suffix);
        let bytes = std::fs::read(&backup)?;
        boot_patch::flash_partition(partition.to_str().unwrap_or_default(), &bytes)?;
        let restored = read_prefix_hash(&partition, record.size)?;
        ensure!(
            restored == record.sha256,
            "restored partition hash mismatch: {}",
            record.partition
        );
    }
    clear_state()
}

/// Handle the earliest reachable boot stage. Returns true when a rollback was
/// performed; callers must then stop before provisioning or running modules.
pub fn on_post_fs_data() -> Result<bool> {
    let Some(mut state) = load_state()? else {
        return Ok(false);
    };
    let boot_id = current_boot_id()?;
    match state.phase {
        Phase::Prepared => {
            // No flash was armed; discard an interrupted preflight transaction.
            clear_state()?;
            Ok(false)
        }
        Phase::Armed => {
            if boot_id == state.boot_id {
                return Ok(false);
            }
            let target = partition_path(&state.target_partition, &state.slot_suffix);
            if state.target_size > 0
                && read_prefix_hash(&target, state.target_size).ok().as_deref()
                    == Some(state.target_sha256.as_str())
            {
                state.phase = Phase::Observed;
                state.boot_id = boot_id;
                save_state(&state)?;
                Ok(false)
            } else {
                restore(&state)?;
                Ok(true)
            }
        }
        Phase::Observed => {
            if boot_id == state.boot_id {
                Ok(false)
            } else {
                restore(&state)?;
                Ok(true)
            }
        }
        Phase::RollingBack => {
            restore(&state)?;
            Ok(true)
        }
    }
}

/// Commit a boot only after `sys.boot_completed` has reached the normal hook.
pub fn commit_boot_success() -> Result<()> {
    let Some(state) = load_state()? else {
        return Ok(());
    };
    let boot_id = current_boot_id()?;
    ensure!(
        state.phase == Phase::Observed,
        "boot transaction was not observed successfully"
    );
    ensure!(
        state.boot_id == boot_id,
        "boot transaction belongs to another boot"
    );
    if state.ota {
        let status = std::process::Command::new(crate::assets::BOOTCTL_PATH)
            .arg("mark-boot-successful")
            .status()
            .context("mark OTA boot successful")?;
        ensure!(status.success(), "bootctl mark-boot-successful failed");
    }
    clear_state()
}
