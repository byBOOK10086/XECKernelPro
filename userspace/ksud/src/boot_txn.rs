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

#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
#[cfg(target_os = "android")]
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};

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
    Restored,
}

impl Phase {
    const fn as_str(&self) -> &'static str {
        match self {
            Self::Prepared => "prepared",
            Self::Armed => "armed",
            Self::Observed => "observed",
            Self::RollingBack => "rolling_back",
            Self::Restored => "restored",
        }
    }

    fn parse(value: &str) -> Result<Self> {
        match value {
            "prepared" => Ok(Self::Prepared),
            "armed" => Ok(Self::Armed),
            "observed" => Ok(Self::Observed),
            "rolling_back" => Ok(Self::RollingBack),
            "restored" => Ok(Self::Restored),
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

#[cfg(target_os = "android")]
struct FlashLock(File);

#[cfg(target_os = "android")]
impl FlashLock {
    fn acquire() -> Result<Self> {
        std::fs::create_dir_all(defs::KSU_BACKUP_DIR)?;
        std::fs::set_permissions(defs::KSU_BACKUP_DIR, std::fs::Permissions::from_mode(0o700))?;
        let path = Path::new(defs::KSU_BACKUP_DIR).join("boot_txn.lock");
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&path)
            .with_context(|| format!("open boot transaction lock {}", path.display()))?;
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } != 0 {
            return Err(std::io::Error::last_os_error()).context("lock boot transaction");
        }
        Ok(Self(file))
    }
}

#[cfg(target_os = "android")]
impl Drop for FlashLock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

fn current_source_identity(
    slot: &str,
    partition: &str,
    ota: bool,
    boot: &str,
    resolved: PathBuf,
    device: u64,
    bytes: &[u8],
) -> SourceIdentity {
    SourceIdentity {
        slot: slot.into(),
        partition: partition.into(),
        ota,
        boot: boot.into(),
        resolved,
        device,
        size: bytes.len(),
        digest: sha256::digest(bytes),
    }
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
    // 逐行组好再 join：`push_str(&format!(..))` 这种写法 clippy 判
    // format_push_string（pedantic，本 crate 是 deny），改成 Vec<String> 既过
    // lint 也不用引 std::fmt::Write（会和这里的 std::io::Write 撞名）。
    let mut lines = vec![
        format!("version={STATE_VERSION}"),
        format!("phase={}", state.phase.as_str()),
        format!("slot_suffix={}", state.slot_suffix),
        format!("target_partition={}", state.target_partition),
        format!("target_size={}", state.target_size),
        format!("target_sha256={}", state.target_sha256),
        format!("boot_id={}", state.boot_id),
        format!("ota={}", u8::from(state.ota)),
        format!("record_count={}", state.records.len()),
    ];
    for (index, record) in state.records.iter().enumerate() {
        lines.push(format!("record.{index}.partition={}", record.partition));
        lines.push(format!("record.{index}.suffix={}", record.suffix));
        lines.push(format!("record.{index}.backup={}", record.backup_name));
        lines.push(format!("record.{index}.size={}", record.size));
        lines.push(format!("record.{index}.sha256={}", record.sha256));
    }
    // 保持原来的尾随换行：解析侧按 lines() 读，不依赖行数
    lines.push(String::new());
    lines.join("\n")
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
        ensure!(size > 0 && size <= MAX_IMAGE_SIZE, "invalid backup size");
        ensure!(
            !records
                .iter()
                .any(|record: &BackupRecord| record.partition == partition),
            "duplicate backup partition"
        );
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
    let data = match std::fs::read_to_string(&path) {
        Ok(data) => data,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).with_context(|| format!("read {}", path.display())),
    };
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

fn target_record(state: &BootTxn) -> Result<&BackupRecord> {
    state
        .records
        .iter()
        .find(|record| record.partition == state.target_partition)
        .context("target backup record missing")
}

fn copy_backup(source: &Path, backup_name: &str) -> Result<(u64, String)> {
    let destination = Path::new(defs::KSU_BACKUP_DIR).join(backup_name);
    let temporary = destination.with_extension(format!("tmp.{}", std::process::id()));
    let input =
        File::open(source).with_context(|| format!("open backup source {}", source.display()))?;
    ensure!(
        input.metadata()?.len() <= MAX_IMAGE_SIZE,
        "backup source {} exceeds the supported image size",
        source.display()
    );
    let mut output = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary)?;
    let size = std::io::copy(&mut input.take(MAX_IMAGE_SIZE + 1), &mut output)?;
    ensure!(
        size > 0 && size <= MAX_IMAGE_SIZE,
        "invalid backup image size"
    );
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

/// Back up exactly the target partition before a direct flash.
///
fn prepare_unlocked(slot_suffix: &str, target_partition: &str, ota: bool) -> Result<()> {
    validate_suffix(slot_suffix)?;
    validate_partition(target_partition)?;
    ensure!(
        load_state()?.is_none(),
        "a boot transaction is already pending"
    );
    let boot_id = current_boot_id()?;
    let partition = partition_path(target_partition, slot_suffix);
    ensure!(partition.exists(), "target partition is not available");
    let name = backup_name(target_partition, slot_suffix);
    let (size, sha256) = copy_backup(&partition, &name)?;
    let records = vec![BackupRecord {
        partition: target_partition.to_string(),
        suffix: slot_suffix.to_string(),
        backup_name: name,
        size,
        sha256,
    }];
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

fn arm_unlocked(patched_image: &[u8]) -> Result<()> {
    let mut state = load_state()?.context("boot transaction is not prepared")?;
    ensure!(
        state.phase == Phase::Prepared,
        "boot transaction cannot be armed from {}",
        state.phase.as_str()
    );
    state.target_size = patched_image.len() as u64;
    ensure!(
        state.target_size > 0
            && state.target_size <= MAX_IMAGE_SIZE
            && state.target_size <= target_record(&state)?.size,
        "patched boot image exceeds the supported size"
    );
    state.target_sha256 = sha256::digest(patched_image);
    state.phase = Phase::Armed;
    save_state(&state)
}

/// Read only the target backup into a bounded, verified snapshot before writing.
fn restore_target_image(state: &BootTxn) -> Result<(BackupRecord, Vec<u8>)> {
    let record = target_record(state)?.clone();
    let backup = Path::new(defs::KSU_BACKUP_DIR).join(&record.backup_name);
    ensure!(
        record.size > 0 && record.size <= MAX_IMAGE_SIZE,
        "invalid backup size"
    );
    let input = File::open(&backup)
        .with_context(|| format!("open boot transaction backup {}", backup.display()))?;
    ensure!(input.metadata()?.is_file(), "backup is not a regular file");
    ensure!(
        input.metadata()?.len() == record.size,
        "backup size mismatch"
    );
    let mut bytes = Vec::new();
    input.take(record.size + 1).read_to_end(&mut bytes)?;
    ensure!(bytes.len() as u64 == record.size, "backup size changed");
    ensure!(
        sha256::digest(&bytes) == record.sha256,
        "corrupt boot transaction backup"
    );
    Ok((record, bytes))
}

fn verify_target_restored(state: &BootTxn) -> Result<()> {
    let record = target_record(state)?;
    let partition = partition_path(&record.partition, &record.suffix);
    ensure!(
        read_prefix_hash(&partition, record.size)? == record.sha256,
        "restored target partition hash mismatch: {}",
        record.partition
    );
    Ok(())
}

fn restore_target(state: &BootTxn) -> Result<()> {
    let (record, bytes) = restore_target_image(state)?;
    let mut rolling = state.clone();
    rolling.phase = Phase::RollingBack;
    save_state(&rolling)?;
    let partition = partition_path(&record.partition, &record.suffix);
    boot_patch::flash_partition(partition.to_str().unwrap_or_default(), &bytes)?;
    verify_target_restored(state)?;
    rolling.phase = Phase::Restored;
    rolling.boot_id = current_boot_id()?;
    save_state(&rolling)
}

fn cleanup_verified(state: &BootTxn) -> Result<()> {
    verify_target_restored(state)?;
    clear_state()
}

fn retry_phase_allowed(state: &BootTxn, boot_id: &str) -> bool {
    state.boot_id != boot_id
        && matches!(
            state.phase,
            Phase::Prepared | Phase::Armed | Phase::Restored
        )
}

/// Reconcile only states whose target still equals the verified pre-flash image.
/// Never infer successful booting from sys.boot_completed or erase unknown writes.
#[cfg(target_os = "android")]
fn reconcile_retry() -> Result<()> {
    let Some(state) = load_state()? else {
        return Ok(());
    };
    let boot = current_boot_id()?;
    let slot = crate::utils::getprop("ro.boot.slot_suffix").unwrap_or_default();
    validate_running_slot(&state, &slot)?;
    let can_retry = retry_phase_allowed(&state, &boot);
    ensure!(
        can_retry,
        "boot transaction still requires recovery (phase {}); recovery metadata retained",
        state.phase.as_str()
    );
    // Validate immutable backup and full current target, not just the image prefix.
    let _ = restore_target_image(&state)?;
    verify_target_restored(&state).context(
        "pending boot image differs from its pre-flash backup; reboot to verify/recover before retrying"
    )?;
    clear_state()
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SourceIdentity {
    slot: String,
    partition: String,
    ota: bool,
    boot: String,
    resolved: PathBuf,
    device: u64,
    size: usize,
    digest: String,
}

impl SourceIdentity {
    fn verify(&self, current: &Self) -> Result<()> {
        ensure!(self == current, "source partition changed; retry patching");
        Ok(())
    }
}

/// Owned source bytes and the identity of the live partition they came from.
#[cfg(target_os = "android")]
pub struct SourceSnapshot {
    pub bytes: Vec<u8>,
    identity: SourceIdentity,
}

#[cfg(target_os = "android")]
impl SourceSnapshot {
    pub fn take_bytes(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.bytes)
    }
}

#[cfg(target_os = "android")]
fn read_source(path: &Path) -> Result<(Vec<u8>, PathBuf, u64)> {
    use std::os::unix::fs::MetadataExt;
    let resolved = std::fs::canonicalize(path)?;
    let input = File::open(&resolved)?;
    let device = input.metadata()?.rdev();
    let mut bytes = Vec::new();
    input.take(MAX_IMAGE_SIZE + 1).read_to_end(&mut bytes)?;
    ensure!(
        !bytes.is_empty() && bytes.len() as u64 <= MAX_IMAGE_SIZE,
        "invalid source partition size"
    );
    Ok((bytes, resolved, device))
}

/// Read under the same lock used by flashing and recovery, then patch outside it.
#[cfg(target_os = "android")]
pub fn snapshot_source(slot: &str, partition: &str, ota: bool) -> Result<SourceSnapshot> {
    validate_suffix(slot)?;
    validate_partition(partition)?;
    let _lock = FlashLock::acquire()?;
    reconcile_retry()?;
    let (bytes, resolved, device) = read_source(&partition_path(partition, slot))?;
    let digest = sha256::digest(&bytes);
    Ok(SourceSnapshot {
        identity: SourceIdentity {
            slot: slot.into(),
            partition: partition.into(),
            ota,
            boot: current_boot_id()?,
            resolved,
            device,
            size: bytes.len(),
            digest,
        },
        bytes,
    })
}

#[cfg(target_os = "android")]
fn validate_source(source: &SourceSnapshot, slot: &str, partition: &str, ota: bool) -> Result<()> {
    let (bytes, resolved, device) = read_source(&partition_path(partition, slot))?;
    source.identity.verify(&current_source_identity(
        slot,
        partition,
        ota,
        &current_boot_id()?,
        resolved,
        device,
        &bytes,
    ))?;
    Ok(())
}

/// Serializes the complete prepare -> arm -> flash transaction against other
/// direct flash operations and startup recovery.
#[cfg(target_os = "android")]
pub fn flash_transaction<F>(
    slot_suffix: &str,
    target_partition: &str,
    ota: bool,
    source: &SourceSnapshot,
    bytes: &[u8],
    after_flash: F,
) -> Result<()>
where
    F: FnOnce() -> Result<()>,
{
    let _lock = FlashLock::acquire()?;
    reconcile_retry()?;
    validate_source(source, slot_suffix, target_partition, ota)?;
    prepare_unlocked(slot_suffix, target_partition, ota)?;
    arm_unlocked(bytes)?;
    let target = partition_path(target_partition, slot_suffix);
    let operation = boot_patch::flash_partition(target.to_str().unwrap_or_default(), bytes)
        .and_then(|()| {
            ensure!(
                read_prefix_hash(&target, bytes.len() as u64)? == sha256::digest(bytes),
                "flashed target readback mismatch"
            );
            after_flash()
        });
    if let Err(error) = operation {
        let state = load_state()?.context("flash failed and transaction metadata disappeared")?;
        let rollback = restore_target(&state).and_then(|()| cleanup_verified(&state));
        return match rollback {
            Ok(()) => {
                Err(error).context("flash operation failed; verified target rollback completed")
            }
            Err(rollback_error) => Err(anyhow::anyhow!(
                "flash operation failed: {error:#}; verified rollback failed: {rollback_error:#}; transaction metadata retained"
            )),
        };
    }
    Ok(())
}

#[derive(Debug, PartialEq, Eq)]
enum BootAction {
    Continue,
    CheckPrepared,
    CheckTarget,
    Restore,
    Reboot,
    VerifyRestored,
    VerifyArmed,
}

fn validate_running_slot(state: &BootTxn, running_slot: &str) -> Result<()> {
    validate_suffix(running_slot)?;
    ensure!(
        running_slot == state.slot_suffix,
        "boot transaction target slot {} is not the running slot {running_slot}",
        state.slot_suffix
    );
    if state.ota {
        ensure!(!running_slot.is_empty(), "OTA boot requires an A/B slot");
    }
    Ok(())
}

fn boot_action(state: &BootTxn, boot_id: &str, running_slot: &str) -> Result<BootAction> {
    match state.phase {
        Phase::Prepared if state.boot_id == boot_id => Ok(BootAction::Continue),
        Phase::Prepared => {
            validate_running_slot(state, running_slot)?;
            Ok(BootAction::CheckPrepared)
        }
        Phase::Armed if state.boot_id == boot_id => {
            validate_running_slot(state, running_slot)?;
            Ok(BootAction::VerifyArmed)
        }
        Phase::Armed => {
            validate_running_slot(state, running_slot)?;
            Ok(BootAction::CheckTarget)
        }
        Phase::Observed => {
            validate_running_slot(state, running_slot)?;
            Ok(if state.boot_id == boot_id {
                BootAction::Continue
            } else {
                BootAction::Restore
            })
        }
        Phase::RollingBack => {
            validate_running_slot(state, running_slot)?;
            Ok(BootAction::Restore)
        }
        Phase::Restored => {
            validate_running_slot(state, running_slot)?;
            Ok(if state.boot_id == boot_id {
                BootAction::Reboot
            } else {
                BootAction::VerifyRestored
            })
        }
    }
}

fn should_defer_commit(state: &BootTxn, boot_id: &str) -> bool {
    state.boot_id == boot_id && matches!(state.phase, Phase::Prepared | Phase::Armed)
}

fn validate_commit(state: &BootTxn, boot_id: &str, running_slot: &str) -> Result<()> {
    ensure!(
        state.phase == Phase::Observed,
        "boot transaction was not observed successfully"
    );
    ensure!(
        state.boot_id == boot_id,
        "boot transaction belongs to another boot"
    );
    validate_running_slot(state, running_slot)
}

/// Handle the earliest reachable boot stage. True means verified rollback
/// requires a reboot; callers must stop provisioning and modules, including
/// repeated events in the same boot. Errors must also stop normal boot work.
pub fn on_post_fs_data() -> Result<bool> {
    #[cfg(target_os = "android")]
    let _lock = FlashLock::acquire()?;
    let Some(mut state) = load_state()? else {
        return Ok(false);
    };
    let boot_id = current_boot_id()?;
    if state.phase == Phase::Prepared && state.boot_id == boot_id {
        return Ok(false);
    }
    let running_slot = crate::utils::getprop("ro.boot.slot_suffix").unwrap_or_default();
    match boot_action(&state, &boot_id, &running_slot)? {
        BootAction::Continue => Ok(false),
        BootAction::CheckPrepared => {
            let target = partition_path(&state.target_partition, &state.slot_suffix);
            let backup = target_record(&state)?;
            if read_prefix_hash(&target, backup.size).ok().as_deref()
                == Some(backup.sha256.as_str())
            {
                clear_state()?;
                Ok(false)
            } else {
                restore_target(&state)?;
                Ok(true)
            }
        }
        BootAction::VerifyArmed => {
            let target = partition_path(&state.target_partition, &state.slot_suffix);
            if state.target_size > 0
                && read_prefix_hash(&target, state.target_size).ok().as_deref()
                    == Some(state.target_sha256.as_str())
            {
                Ok(false)
            } else {
                restore_target(&state)?;
                Ok(true)
            }
        }
        BootAction::CheckTarget => {
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
                restore_target(&state)?;
                Ok(true)
            }
        }
        BootAction::Restore => {
            restore_target(&state)?;
            Ok(true)
        }
        BootAction::Reboot => {
            verify_target_restored(&state)?;
            Ok(true)
        }
        BootAction::VerifyRestored => {
            verify_target_restored(&state)?;
            clear_state()?;
            Ok(false)
        }
    }
}

/// Commit a boot only after `sys.boot_completed` has reached the normal hook.
pub fn commit_boot_success() -> Result<bool> {
    #[cfg(target_os = "android")]
    let _lock = FlashLock::acquire()?;
    let Some(state) = load_state()? else {
        return Ok(true);
    };
    let boot_id = current_boot_id()?;
    if should_defer_commit(&state, &boot_id) {
        return Ok(false);
    }
    ensure!(
        state.phase == Phase::Observed,
        "boot transaction is not in an observed success phase"
    );
    let running_slot = crate::utils::getprop("ro.boot.slot_suffix").unwrap_or_default();
    validate_commit(&state, &boot_id, &running_slot)?;
    if state.ota {
        let status = std::process::Command::new(crate::assets::BOOTCTL_PATH)
            .arg("mark-boot-successful")
            .status()
            .context("mark OTA boot successful")?;
        ensure!(status.success(), "bootctl mark-boot-successful failed");
    }
    clear_state()?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state(phase: Phase) -> BootTxn {
        BootTxn {
            phase,
            slot_suffix: "_b".to_string(),
            target_partition: "boot".to_string(),
            target_size: 4,
            target_sha256: sha256::digest(b"boot"),
            boot_id: "original".to_string(),
            ota: true,
            records: vec![BackupRecord {
                partition: "boot".to_string(),
                suffix: "_b".to_string(),
                backup_name: backup_name("boot", "_b"),
                size: 4,
                sha256: sha256::digest(b"old!"),
            }],
        }
    }

    #[test]
    fn source_identity_accepts_unchanged_and_rejects_mutated_target() {
        let original = SourceIdentity {
            slot: "_a".into(),
            partition: "boot".into(),
            ota: false,
            boot: "boot-id".into(),
            resolved: PathBuf::from("/dev/block/boot_a"),
            device: 1,
            size: 4,
            digest: sha256::digest(b"same"),
        };
        let same = original.clone();
        assert!(original.verify(&same).is_ok());
        let mut changed = same;
        changed.digest = sha256::digest(b"new!");
        assert!(original.verify(&changed).is_err());
        changed.digest.clone_from(&original.digest);
        changed.slot = "_b".into();
        assert!(original.verify(&changed).is_err());
    }

    #[test]
    fn ota_never_observes_or_commits_the_old_slot() {
        let armed = state(Phase::Armed);
        assert!(boot_action(&armed, "next", "_a").is_err());
        assert!(boot_action(&armed, "next", "").is_err());
        assert!(boot_action(&armed, "next", "_invalid").is_err());
        assert!(boot_action(&armed, "original", "_a").is_err());
        assert_eq!(
            boot_action(&armed, "original", "_b").unwrap(),
            BootAction::VerifyArmed
        );
        assert_eq!(
            boot_action(&armed, "next", "_b").unwrap(),
            BootAction::CheckTarget
        );
        let mut armed_same_boot = state(Phase::Armed);
        armed_same_boot.target_size = 4;
        assert!(validate_commit(&armed_same_boot, "original", "_b").is_err());
        let observed = state(Phase::Observed);
        assert!(validate_commit(&observed, "original", "_a").is_err());
        assert!(validate_commit(&observed, "next", "_b").is_err());
        assert!(validate_commit(&observed, "original", "_b").is_ok());
        assert!(validate_commit(&armed, "original", "_b").is_err());
    }

    #[test]
    fn observed_boot_retries_restore_on_the_next_boot() {
        let observed = state(Phase::Observed);
        assert_eq!(
            boot_action(&observed, "original", "_b").unwrap(),
            BootAction::Continue
        );
        assert_eq!(
            boot_action(&observed, "next", "_b").unwrap(),
            BootAction::Restore
        );
        assert_eq!(
            boot_action(&state(Phase::RollingBack), "original", "_b").unwrap(),
            BootAction::Restore
        );
        assert_eq!(
            boot_action(&state(Phase::Prepared), "next", "_b").unwrap(),
            BootAction::CheckPrepared
        );
    }

    #[test]
    fn verified_restore_survives_repeated_events_until_reboot() {
        let restored = state(Phase::Restored);
        assert_eq!(
            boot_action(&restored, "original", "_b").unwrap(),
            BootAction::Reboot
        );
        assert_eq!(
            boot_action(&restored, "next", "_b").unwrap(),
            BootAction::VerifyRestored
        );
        assert!(validate_commit(&restored, "original", "_b").is_err());
        assert_eq!(parse_state(&serialize(&restored)).unwrap(), restored);
    }

    #[test]
    fn retries_require_a_different_boot_and_a_recoverable_phase() {
        for phase in [Phase::Prepared, Phase::Armed, Phase::Restored] {
            assert!(!retry_phase_allowed(&state(phase.clone()), "original"));
            assert!(retry_phase_allowed(&state(phase), "next"));
        }
        for phase in [Phase::Observed, Phase::RollingBack] {
            assert!(!retry_phase_allowed(&state(phase), "next"));
        }
    }

    #[test]
    fn same_boot_prepared_or_armed_never_certifies_success() {
        let prepared = state(Phase::Prepared);
        assert!(should_defer_commit(&prepared, "original"));
        assert!(!should_defer_commit(&prepared, "next"));
        let armed = state(Phase::Armed);
        assert!(should_defer_commit(&armed, "original"));
        assert!(!should_defer_commit(&armed, "next"));
        assert!(!should_defer_commit(&state(Phase::Observed), "original"));
    }

    #[test]
    fn legacy_extra_snapshots_do_not_change_restore_scope() {
        let mut legacy = state(Phase::RollingBack);
        legacy.records.push(BackupRecord {
            partition: "init_boot".to_string(),
            suffix: "_b".to_string(),
            backup_name: backup_name("init_boot", "_b"),
            size: 4,
            sha256: sha256::digest(b"init"),
        });
        assert_eq!(target_record(&legacy).unwrap().partition, "boot");
        assert!(validate_commit(&legacy, "original", "_b").is_err());
    }

    #[test]
    fn rejects_oversized_or_ambiguous_restore_records() {
        let mut invalid = state(Phase::RollingBack);
        invalid.records[0].size = MAX_IMAGE_SIZE + 1;
        assert!(parse_state(&serialize(&invalid)).is_err());
        invalid.records[0].size = 0;
        assert!(parse_state(&serialize(&invalid)).is_err());
        invalid.records[0].size = 4;
        invalid.records.push(invalid.records[0].clone());
        assert!(parse_state(&serialize(&invalid)).is_err());
    }
}

/// Whether the pending flash needs the longer OTA startup deadline.
pub fn is_ota_pending() -> Result<bool> {
    Ok(load_state()?.is_some_and(|state| state.ota))
}
