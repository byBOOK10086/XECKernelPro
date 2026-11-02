//! Durable startup policy; the pure policy and tests also compile with rustc --test.
#![allow(clippy::missing_const_for_fn)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Decision {
    Wait,
    Success,
    Timeout,
}

fn decision(elapsed: u64, stable: Option<u64>, limit: u64) -> Decision {
    // A fully observed success wins the boundary race; an initial property does not.
    if stable.is_some_and(|seconds| seconds >= 60) {
        Decision::Success
    } else if elapsed >= limit {
        Decision::Timeout
    } else {
        Decision::Wait
    }
}

fn failures_after_start(failures: u64, pending: bool, same_boot: bool) -> u64 {
    failures.saturating_add(u64::from(pending && !same_boot))
}

fn rescue_required(failures: u64, corrupt: bool) -> bool {
    corrupt || failures >= 2
}
fn may_reboot(retries: u64) -> bool {
    retries < 2
}

#[cfg(target_os = "android")]
mod runtime {
    use super::{Decision, decision, failures_after_start, may_reboot, rescue_required};
    use anyhow::{Context, Result, ensure};
    use serde_json::{Value, json};
    use std::fs::{self, File, OpenOptions};
    use std::io::{ErrorKind, Write};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::unix::process::CommandExt;
    use std::path::Path;
    use std::process::{Command, Stdio};
    use std::time::Duration;

    const DIR: &str = "/data/adb/ksu/boot_guard";
    const STATE: &str = "/data/adb/ksu/boot_guard/state.json";
    const SKIPPED: &[&str] = &[
        "kpm",
        "common",
        "metamodule",
        "modules",
        "builtin",
        "properties",
        "mount",
    ];

    #[derive(Debug, Default)]
    #[allow(clippy::struct_excessive_bools)]
    struct State {
        boot: String,
        pending: bool,
        rescue: bool,
        failures: u64,
        stage: String,
        reason: String,
        last_recovery: String,
        retries: u64,
        started: u64,
        ready: bool,
        ota: bool,
    }
    impl State {
        fn value(&self) -> Value {
            json!({"version": 1, "enabled": true, "rescue": self.rescue,
                "failures": self.failures, "stage": self.stage, "reason": self.reason,
                "skipped": if self.rescue { SKIPPED } else { &[] as &[&str] },
                "last_recovery": self.last_recovery, "boot": self.boot,
                "pending": self.pending, "retries": self.retries, "started": self.started,
                "ready": self.ready, "ota": self.ota})
        }
        fn parse(v: &Value) -> Result<Self> {
            ensure!(
                v["version"].as_u64() == Some(1) && v["enabled"].as_bool() == Some(true),
                "unsupported state"
            );
            let text = |k: &str| {
                v[k].as_str()
                    .map(str::to_owned)
                    .with_context(|| format!("invalid {k}"))
            };
            let flag = |k: &str| v[k].as_bool().with_context(|| format!("invalid {k}"));
            let number = |k: &str| v[k].as_u64().with_context(|| format!("invalid {k}"));
            let state = Self {
                boot: text("boot")?,
                pending: flag("pending")?,
                rescue: flag("rescue")?,
                failures: number("failures")?,
                stage: text("stage")?,
                reason: text("reason")?,
                last_recovery: text("last_recovery")?,
                retries: number("retries")?,
                started: number("started")?,
                ready: flag("ready")?,
                ota: flag("ota")?,
            };
            ensure!(
                state.retries <= 2 && state.failures <= u64::from(u32::MAX / 2)
                    && (!state.pending || !state.boot.is_empty()),
                "inconsistent state"
            );
            ensure!(
                !rescue_required(state.failures, false) || state.rescue,
                "missing rescue state"
            );
            Ok(state)
        }
    }

    struct Lock(File);
    impl Lock {
        fn acquire(name: &str, nonblock: bool) -> Result<Option<Self>> {
            fs::create_dir_all(DIR)?;
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(DIR, fs::Permissions::from_mode(0o700))?;
            let file = OpenOptions::new()
                .create(true)
                .truncate(false)
                .read(true)
                .write(true)
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
                .open(Path::new(DIR).join(name))?;
            let flags = libc::LOCK_EX | if nonblock { libc::LOCK_NB } else { 0 };
            if unsafe { libc::flock(file.as_raw_fd(), flags) } != 0 {
                let error = std::io::Error::last_os_error();
                if nonblock && error.kind() == ErrorKind::WouldBlock {
                    return Ok(None);
                }
                return Err(error.into());
            }
            Ok(Some(Self(file)))
        }
    }
    impl Drop for Lock {
        fn drop(&mut self) {
            unsafe {
                libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
            }
        }
    }
    fn locked() -> Result<Lock> {
        Lock::acquire("state.lock", false)?.context("state lock unavailable")
    }
    fn root() -> Result<()> {
        ensure!(unsafe { libc::geteuid() } == 0, "boot-guard requires root");
        Ok(())
    }
    fn boot_id() -> Result<String> {
        let id = fs::read_to_string("/proc/sys/kernel/random/boot_id")?;
        ensure!(!id.trim().is_empty(), "empty boot id");
        Ok(id.trim().into())
    }
    fn uptime() -> Result<u64> {
        let mut ts = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        ensure!(
            unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &raw mut ts) } == 0,
            "boot clock unavailable"
        );
        u64::try_from(ts.tv_sec).context("invalid boot clock")
    }
    fn corrupt(reason: String) -> State {
        State {
            rescue: true,
            stage: "state-error".into(),
            reason,
            last_recovery: "state corruption: explicit resume required".into(),
            ..State::default()
        }
    }
    fn load() -> Result<State> {
        match fs::read(STATE) {
            Ok(bytes) => Ok(serde_json::from_slice::<Value>(&bytes)
                .map_err(anyhow::Error::from)
                .and_then(|v| State::parse(&v))
                .unwrap_or_else(|e| corrupt(format!("corrupt boot state: {e:#}")))),
            Err(e) if e.kind() == ErrorKind::NotFound => Ok(State::default()),
            Err(e) => Err(e).context("read boot guard state"),
        }
    }
    fn save(state: &State) -> Result<()> {
        let tmp = Path::new(DIR).join("state.new");
        let mut out = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&tmp)?;
        out.write_all(&serde_json::to_vec(&state.value())?)?;
        out.sync_all()?;
        fs::rename(tmp, STATE)?;
        File::open(DIR)?.sync_all()?;
        Ok(())
    }
    fn migrate(state: &mut State) -> Result<()> {
        for base in [crate::defs::MODULE_DIR, crate::defs::MODULE_UPDATE_DIR] {
            let entries = match fs::read_dir(base) {
                Ok(entries) => entries,
                Err(e) if e.kind() == ErrorKind::NotFound => continue,
                Err(e) => return Err(e.into()),
            };
            for entry in entries {
                let path = entry?.path();
                if !path.is_dir() {
                    continue;
                }
                let prop = fs::read_to_string(path.join("module.prop")).unwrap_or_default();
                let legacy = path
                    .file_name()
                    .is_some_and(|n| n == "Automatic_brick_rescue")
                    || prop
                        .lines()
                        .any(|line| line.trim() == "id=Automatic_brick_rescue");
                if legacy {
                    let marker = path.join("disable");
                    OpenOptions::new()
                        .create(true)
                        .truncate(false)
                        .write(true)
                        .mode(0o600)
                        .custom_flags(libc::O_NOFOLLOW)
                        .open(marker)?
                        .sync_all()?;
                    File::open(&path)?.sync_all()?;
                    state.last_recovery =
                        "Automatic_brick_rescue disabled; native guard owns recovery".into();
                }
            }
        }
        let mounts = fs::read_to_string("/proc/self/mountinfo")?;
        if Path::new("/data/adb/lite_modules").exists()
            || Path::new("/data/adb/modules/lite_modules").exists()
            || mounts.lines().any(|l| l.contains("lite_modules"))
        {
            state.rescue = true;
            state.reason = "legacy lite_modules directory or bind mount conflict; inspect and resolve before resume".into();
            state.last_recovery.clone_from(&state.reason);
        }
        Ok(())
    }
    fn spawn_watch() -> Result<()> {
        let mut command = Command::new(std::env::current_exe()?);
        command
            .args(["boot-guard", "watch"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        unsafe {
            command.pre_exec(|| {
                if libc::setsid() < 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
        command.spawn().context("spawn boot guard watcher")?;
        Ok(())
    }

    /// Call before KPM, common scripts, built-ins, properties, mounts or policy.
    pub fn begin(stage: &str) -> Result<bool> {
        root()?;
        let guard = locked()?;
        let id = boot_id()?;
        let mut state = load()?;
        if state.boot != id {
            state.failures = failures_after_start(state.failures, state.pending, false);
            state.rescue |= rescue_required(state.failures, false);
            if state.failures >= 2 && state.reason.is_empty() {
                state.reason = "two uncommitted startups".into();
            }
            state.boot.clone_from(&id);
            state.pending = true;
            state.started = uptime()?;
            state.ready = false;
            match crate::boot_txn::is_ota_pending() {
                Ok(ota) => state.ota = ota,
                Err(error) => {
                    state.rescue = true;
                    state.reason = format!("invalid boot transaction metadata: {error:#}");
                }
            }
        }
        if !state.pending {
            return Ok(false);
        }
        state.stage = stage.into();
        migrate(&mut state)?;
        save(&state)?;
        let rescue = state.rescue;
        drop(guard);
        // Even rescue boots need a watcher; choices remain unchanged until resume.
        spawn_watch()?;
        Ok(!rescue)
    }
    pub fn allow_stage(stage: &str) -> bool {
        let result = (|| -> Result<bool> {
            let _guard = locked()?;
            let mut state = load()?;
            ensure!(
                state.boot == boot_id()? && (state.pending || state.stage == "success"),
                "boot guard not initialized"
            );
            if state.rescue {
                return Ok(false);
            }
            if state.pending {
                state.stage = stage.into();
            }
            save(&state)?;
            Ok(true)
        })();
        match result {
            Ok(allowed) => allowed,
            Err(e) => {
                log::error!("boot guard gates {stage}: {e:#}");
                false
            }
        }
    }
    /// Signals pipeline readiness, never commits success directly.
    pub fn startup_ready() -> Result<()> {
        let _guard = locked()?;
        let mut state = load()?;
        ensure!(state.boot == boot_id()?, "boot guard boot mismatch");
        state.ready = true;
        save(&state)
    }
    fn reboot(state: &mut State) -> Result<()> {
        if !may_reboot(state.retries) {
            return save(state);
        }
        state.retries += 1;
        state.last_recovery = format!("{}; automatic reboot {}/2", state.reason, state.retries);
        save(state)?;
        let status = Command::new("/system/bin/reboot").status()?;
        ensure!(status.success(), "reboot command failed");
        Ok(())
    }
    pub fn recovery_failed(reason: &str) -> Result<()> {
        let _guard = locked()?;
        let mut state = load()?;
        state.rescue = true;
        state.pending = false;
        state.stage = "transaction-error".into();
        state.reason = reason.into();
        save(&state)
    }

    pub fn rollback_applied() -> Result<()> {
        let _guard = locked()?;
        let mut state = load()?;
        state.boot = boot_id()?;
        state.rescue = true;
        state.pending = false;
        state.stage = "rollback".into();
        state.reason = "boot transaction rollback applied; reboot required".into();
        reboot(&mut state)
    }
    pub fn status() -> Result<()> {
        root()?;
        let _guard = locked()?;
        match load() {
            Ok(state) => println!("{}", state.value()),
            Err(e) => println!("{}", corrupt(format!("state unavailable: {e:#}")).value()),
        }
        Ok(())
    }
    pub fn resume() -> Result<()> {
        root()?;
        let _guard = locked()?;
        let mut state = load()?;
        state.rescue = false;
        state.failures = 0;
        state.retries = 0;
        state.pending = false;
        state.reason.clear();
        state.stage = "resume-pending-reboot".into();
        state.last_recovery = "user resumed; reboot required".into();
        save(&state)?;
        println!(
            "Boot guard resumed. Reboot required; skipped startup stages cannot be replayed safely."
        );
        Ok(())
    }
    pub fn watch() -> Result<()> {
        root()?;
        let Some(_watch_lock) = Lock::acquire("watch.lock", true)? else {
            return Ok(());
        };
        let id = boot_id()?;
        let mut stable_since = None;
        loop {
            let guard = locked()?;
            let mut state = load()?;
            if state.boot != id || !state.pending {
                return Ok(());
            }
            let now = uptime()?;
            if state.ready && crate::utils::getprop("sys.boot_completed").as_deref() == Some("1") {
                stable_since.get_or_insert(now);
            } else {
                stable_since = None;
            }
            let limit = if state.ota { 1800 } else { 300 };
            match decision(
                now.saturating_sub(state.started),
                stable_since.map(|s| now.saturating_sub(s)),
                limit,
            ) {
                Decision::Success => {
                    // State lock serializes success against timeout and explicit resume.
                    if let Err(error) = crate::boot_txn::commit_boot_success() {
                        state.rescue = true;
                        state.pending = false;
                        state.stage = "transaction-error".into();
                        state.reason = format!("boot success transaction failed: {error:#}");
                        state.last_recovery.clone_from(&state.reason);
                        save(&state)?;
                        return Err(error);
                    }
                    state.pending = false;
                    state.stage = "success".into();
                    if !state.rescue {
                        state.failures = 0;
                        state.retries = 0;
                        state.reason.clear();
                    }
                    save(&state)?;
                    return Ok(());
                }
                Decision::Timeout => {
                    state.pending = false; // This boot is counted here, not again next startup.
                    state.failures = state.failures.saturating_add(1);
                    state.rescue |= rescue_required(state.failures, false);
                    state.stage = "timeout".into();
                    state.reason =
                        format!("startup exceeded {limit} seconds without stable completion");
                    reboot(&mut state)?;
                    return Ok(());
                }
                Decision::Wait => {}
            }
            drop(guard);
            std::thread::sleep(Duration::from_secs(2));
        }
    }
}
#[cfg(target_os = "android")]
pub use runtime::{allow_stage, begin, recovery_failed, resume, rollback_applied, startup_ready, status, watch};

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn distinct_boots_count_uncommitted_once() {
        assert_eq!(failures_after_start(0, true, true), 0);
        assert_eq!(failures_after_start(0, true, false), 1);
        assert_eq!(failures_after_start(1, false, false), 1);
        assert!(rescue_required(failures_after_start(1, true, false), false));
        assert_eq!(failures_after_start(u64::MAX, true, false), u64::MAX);
    }
    #[test]
    fn corruption_requires_rescue_even_without_failures() {
        assert!(rescue_required(0, true));
        assert!(!rescue_required(1, false));
    }
    #[test]
    fn success_timeout_race_has_one_boundary_winner() {
        assert_eq!(decision(300, Some(60), 300), Decision::Success);
        assert_eq!(decision(300, Some(59), 300), Decision::Timeout);
        assert_eq!(decision(299, None, 300), Decision::Wait);
        assert_eq!(decision(301, None, 1800), Decision::Wait);
        assert_eq!(decision(1800, None, 1800), Decision::Timeout);
    }
    #[test]
    fn automatic_reboot_budget_is_finite() {
        assert!(may_reboot(0));
        assert!(may_reboot(1));
        assert!(!may_reboot(2));
        assert!(!may_reboot(u64::MAX));
    }
}
