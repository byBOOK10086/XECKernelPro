//! Private boot-log creation without following links or truncating prior logs.
use std::ffi::{CStr, CString};
use std::fs::{File, OpenOptions, Permissions};
use std::io;
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path};
use std::sync::atomic::{AtomicU64, Ordering};

const DIR_MODE: u32 = 0o700;
const FILE_MODE: u32 = 0o600;
static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn c_name(bytes: &[u8]) -> io::Result<CString> {
    CString::new(bytes).map_err(|_| invalid("boot-log path contains NUL"))
}

fn open_at(dir: &File, name: &CStr, flags: i32, mode: u32) -> io::Result<File> {
    // SAFETY: dir remains open, name is NUL-terminated, and a successful fd is
    // transferred to exactly one File. O_CLOEXEC is applied at creation.
    let fd = unsafe {
        libc::openat(
            dir.as_raw_fd(),
            name.as_ptr(),
            flags | libc::O_CLOEXEC,
            mode,
        )
    };
    if fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { File::from_raw_fd(fd) })
    }
}

fn private_directory(path: &Path) -> io::Result<File> {
    if !path.is_absolute() || path == Path::new("/") {
        return Err(invalid(
            "boot-log directory must be an absolute non-root path",
        ));
    }
    let mut dir = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open("/")?;
    for component in path.components() {
        let name = match component {
            Component::RootDir | Component::CurDir => continue,
            Component::Normal(name) => c_name(name.as_bytes())?,
            _ => return Err(invalid("boot-log directory contains a parent component")),
        };
        let flags = libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW;
        dir = match open_at(&dir, &name, flags, 0) {
            Ok(child) => child,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                // New directories are private even when the caller uses umask(0).
                let result = unsafe { libc::mkdirat(dir.as_raw_fd(), name.as_ptr(), DIR_MODE) };
                if result != 0 && io::Error::last_os_error().kind() != io::ErrorKind::AlreadyExists
                {
                    return Err(io::Error::last_os_error());
                }
                open_at(&dir, &name, flags, 0)?
            }
            Err(error) => return Err(error),
        };
    }
    if dir.metadata()?.uid() != unsafe { libc::geteuid() } {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "boot-log directory has another owner",
        ));
    }
    dir.set_permissions(Permissions::from_mode(DIR_MODE))?;
    // Serialize cooperating collectors before inspecting or rotating entries.
    if unsafe { libc::flock(dir.as_raw_fd(), libc::LOCK_EX) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(dir)
}

fn existing_log(dir: &File, name: &CStr) -> io::Result<Option<File>> {
    let flags = libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_NONBLOCK;
    let file = match open_at(dir, name, flags, 0) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.nlink() != 1 {
        return Err(invalid(
            "boot-log entry must be a singly-linked regular file",
        ));
    }
    if metadata.uid() != unsafe { libc::geteuid() } {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "boot-log entry has another owner",
        ));
    }
    Ok(Some(file))
}

fn rename_at(dir: &File, from: &CStr, to: &CStr) -> io::Result<()> {
    if unsafe { libc::renameat(dir.as_raw_fd(), from.as_ptr(), dir.as_raw_fd(), to.as_ptr()) } == 0
    {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

struct Temporary<'a> {
    dir: &'a File,
    name: CString,
    file: Option<File>,
    remove_on_drop: bool,
}

impl<'a> Temporary<'a> {
    fn new(dir: &'a File) -> io::Result<Self> {
        for _ in 0..64 {
            let sequence = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
            let name =
                c_name(format!(".bootlog-{}-{sequence}.tmp", std::process::id()).as_bytes())?;
            let flags = libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW;
            match open_at(dir, &name, flags, FILE_MODE) {
                Ok(file) => {
                    return Ok(Self {
                        dir,
                        name,
                        file: Some(file),
                        remove_on_drop: true,
                    });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(error),
            }
        }
        Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "boot-log staging names unavailable",
        ))
    }
}

impl Drop for Temporary<'_> {
    fn drop(&mut self) {
        if self.remove_on_drop {
            // The private directory fd anchors cleanup to our staging entry.
            unsafe { libc::unlinkat(self.dir.as_raw_fd(), self.name.as_ptr(), 0) };
        }
    }
}

fn restore_old(dir: &File, saved: &mut Option<Temporary<'_>>, old: &CStr) -> io::Result<()> {
    if let Some(saved) = saved {
        rename_at(dir, &saved.name, old)?;
        saved.remove_on_drop = true;
    }
    Ok(())
}

fn rotation_error(original: io::Error, rollback: io::Result<()>) -> io::Error {
    match rollback {
        Ok(()) => original,
        Err(error) => io::Error::other(format!(
            "boot-log rotation failed: {original}; rollback failed: {error}; retained private staging logs"
        )),
    }
}

/// Start a collector on a private staged log, then publish its log rotation.
///
/// All path components reject symlinks. A private owner-only directory and an
/// advisory lock protect the sequence from other UIDs and cooperating writers;
/// a hostile process with the same privileged UID is outside this boundary.
/// The callback runs before either existing log is renamed. Its returned value
/// must stop the collector on drop until this function successfully returns it,
/// so a publication error cannot leave a collector writing an unlinked log.
pub fn start_rotated<T>(
    directory: &Path,
    logname: &str,
    start: impl FnOnce(File) -> io::Result<T>,
) -> io::Result<T> {
    if logname.is_empty()
        || !logname
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
    {
        return Err(invalid("invalid boot-log name"));
    }
    let dir = private_directory(directory)?;
    let live = c_name(format!("{logname}.log").as_bytes())?;
    let old = c_name(format!("{logname}.old.log").as_bytes())?;
    // Validate both names before chmod/rename. In particular, a malicious old
    // entry must not cause us to modify or lose an otherwise valid current log.
    let current = existing_log(&dir, &live)?;
    let previous = existing_log(&dir, &old)?;
    for file in current.iter().chain(previous.iter()) {
        file.set_permissions(Permissions::from_mode(FILE_MODE))?;
    }
    let mut fresh = Temporary::new(&dir)?;
    // A failed spawn must not retire even one previous generation. Keep the
    // stage name private and let its guard remove it when the callback fails.
    let started = start(fresh.file.take().expect("fresh boot-log descriptor"))?;
    let mut saved = if current.is_some() && previous.is_some() {
        let mut backup = Temporary::new(&dir)?;
        rename_at(&dir, &old, &backup.name)?;
        // Keep previous content if a later rollback cannot restore its name.
        backup.remove_on_drop = false;
        Some(backup)
    } else {
        None
    };
    if current.is_some()
        && let Err(error) = rename_at(&dir, &live, &old)
    {
        return Err(rotation_error(error, restore_old(&dir, &mut saved, &old)));
    }
    if let Err(error) = rename_at(&dir, &fresh.name, &live) {
        let rollback = if current.is_some() {
            rename_at(&dir, &old, &live)
        } else {
            Ok(())
        }
        .and_then(|()| restore_old(&dir, &mut saved, &old));
        return Err(rotation_error(error, rollback));
    }
    if let Some(saved) = saved.as_mut() {
        // Successful one-generation rotation retires only the older old log.
        saved.remove_on_drop = true;
    }
    Ok(started)
}

#[cfg(test)]
fn open_rotated(directory: &Path, logname: &str) -> io::Result<File> {
    start_rotated(directory, logname, Ok)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;
    use std::os::unix::fs::symlink;

    fn mode(path: &Path) -> u32 {
        fs::metadata(path).unwrap().mode() & 0o777
    }

    #[test]
    fn fresh_files_remain_private_with_umask_zero() {
        const CHILD: &str = "XEC_BOOTLOG_UMASK_TEST_CHILD";
        if std::env::var_os(CHILD).is_none() {
            let result = std::process::Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "private_bootlog::tests::fresh_files_remain_private_with_umask_zero",
                ])
                .env(CHILD, "1")
                .status()
                .unwrap();
            assert!(result.success());
            return;
        }
        // This isolated, single-test child may safely change its process umask.
        unsafe { libc::umask(0) };
        let temp = tempfile::tempdir().unwrap();
        let logs = temp.path().join("parent/log");
        open_rotated(&logs, "dmesg")
            .unwrap()
            .write_all(b"audit preserved")
            .unwrap();
        assert_eq!(mode(&logs), DIR_MODE);
        assert_eq!(mode(&logs.join("dmesg.log")), FILE_MODE);
        assert_eq!(
            fs::read(logs.join("dmesg.log")).unwrap(),
            b"audit preserved"
        );
    }

    #[test]
    fn existing_logs_are_private_and_rotate_without_content_changes() {
        let temp = tempfile::tempdir().unwrap();
        let logs = temp.path().join("logs");
        fs::create_dir(&logs).unwrap();
        let current = logs.join("dmesg.log");
        let old = logs.join("dmesg.old.log");
        fs::write(&current, b"current audit").unwrap();
        fs::write(&old, b"previous audit").unwrap();
        for path in [&logs, &current, &old] {
            fs::set_permissions(path, Permissions::from_mode(0o777)).unwrap();
        }
        let mut output = open_rotated(&logs, "dmesg").unwrap();
        output.write_all(b"new audit").unwrap();
        assert_eq!(fs::read(&old).unwrap(), b"current audit");
        assert_eq!(fs::read(&current).unwrap(), b"new audit");
        assert_eq!(mode(&logs), DIR_MODE);
        assert_eq!(mode(&current), FILE_MODE);
        assert_eq!(mode(&old), FILE_MODE);
        assert_eq!(fs::read_dir(&logs).unwrap().count(), 2);
    }

    #[test]
    fn repeated_start_failures_preserve_both_log_generations() {
        let temp = tempfile::tempdir().unwrap();
        let logs = temp.path().join("logs");
        fs::create_dir(&logs).unwrap();
        let current = logs.join("dmesg.log");
        let old = logs.join("dmesg.old.log");
        fs::write(&current, b"current audit").unwrap();
        fs::write(&old, b"previous audit").unwrap();
        let missing_command = temp.path().join("missing-capture-command");

        for _ in 0..2 {
            let error = start_rotated(&logs, "dmesg", |mut output| {
                // Even a nonempty staging file is discarded on failed start.
                output.write_all(b"unpublished capture")?;
                assert_eq!(fs::read(&current).unwrap(), b"current audit");
                assert_eq!(fs::read(&old).unwrap(), b"previous audit");
                std::process::Command::new(&missing_command)
                    .stdout(output)
                    .spawn()
            })
            .unwrap_err();
            assert_eq!(error.kind(), io::ErrorKind::NotFound);
            assert_eq!(fs::read(&current).unwrap(), b"current audit");
            assert_eq!(fs::read(&old).unwrap(), b"previous audit");
            assert_eq!(mode(&logs), DIR_MODE);
            assert_eq!(mode(&current), FILE_MODE);
            assert_eq!(mode(&old), FILE_MODE);
            assert_eq!(fs::read_dir(&logs).unwrap().count(), 2);
        }
    }

    #[test]
    fn previous_log_without_current_is_preserved_and_secured() {
        let temp = tempfile::tempdir().unwrap();
        let old = temp.path().join("dmesg.old.log");
        fs::write(&old, b"retain me").unwrap();
        fs::set_permissions(&old, Permissions::from_mode(0o666)).unwrap();
        open_rotated(temp.path(), "dmesg").unwrap();
        assert_eq!(fs::read(&old).unwrap(), b"retain me");
        assert_eq!(mode(&old), FILE_MODE);
    }

    #[test]
    fn symlink_hardlink_and_fifo_entries_preserve_existing_files() {
        for entry in ["dmesg.log", "dmesg.old.log"] {
            for kind in ["symlink", "hardlink", "fifo"] {
                let temp = tempfile::tempdir().unwrap();
                let logs = temp.path().join("logs");
                fs::create_dir(&logs).unwrap();
                let outside = temp.path().join("outside");
                fs::write(&outside, b"untouched outside").unwrap();
                fs::set_permissions(&outside, Permissions::from_mode(0o644)).unwrap();
                let other = logs.join(if entry == "dmesg.log" {
                    "dmesg.old.log"
                } else {
                    "dmesg.log"
                });
                fs::write(&other, b"untouched log").unwrap();
                let bad = logs.join(entry);
                match kind {
                    "symlink" => symlink(&outside, &bad).unwrap(),
                    "hardlink" => fs::hard_link(&outside, &bad).unwrap(),
                    _ => {
                        let name = c_name(bad.as_os_str().as_bytes()).unwrap();
                        assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), FILE_MODE) }, 0);
                    }
                }
                assert!(open_rotated(&logs, "dmesg").is_err(), "{kind} {entry}");
                assert_eq!(fs::read(&outside).unwrap(), b"untouched outside");
                assert_eq!(mode(&outside), 0o644);
                assert_eq!(fs::read(&other).unwrap(), b"untouched log");
                assert_eq!(fs::read_dir(&logs).unwrap().count(), 2);
            }
        }
    }

    #[test]
    fn directory_symlink_is_rejected_without_changing_target_permissions() {
        let temp = tempfile::tempdir().unwrap();
        let real = temp.path().join("real");
        fs::create_dir(&real).unwrap();
        fs::set_permissions(&real, Permissions::from_mode(0o755)).unwrap();
        let link = temp.path().join("link");
        symlink(&real, &link).unwrap();
        assert!(open_rotated(&link, "dmesg").is_err());
        assert!(open_rotated(&link.join("child"), "dmesg").is_err());
        assert_eq!(mode(&real), 0o755);
        assert_eq!(fs::read_dir(&real).unwrap().count(), 0);
    }
}
