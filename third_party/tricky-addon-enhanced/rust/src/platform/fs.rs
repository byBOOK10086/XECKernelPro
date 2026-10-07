use std::os::unix::io::AsRawFd;
use std::path::Path;

pub fn atomic_write(path: &Path, data: &[u8]) -> anyhow::Result<()> {
    let tmp = path.with_extension("tmp");
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&tmp, data)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

pub fn ensure_dir(path: &Path) -> anyhow::Result<()> {
    if !path.is_dir() {
        std::fs::create_dir_all(path)?;
    }
    Ok(())
}

pub fn acquire_instance_lock(path: &Path) -> anyhow::Result<Option<std::fs::File>> {
    ensure_dir(path.parent().unwrap_or(Path::new("/tmp")))?;
    let file = std::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(false)
        .open(path)?;
    let ret = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
    if ret == 0 {
        Ok(Some(file))
    } else {
        let err = std::io::Error::last_os_error();
        if err.kind() == std::io::ErrorKind::WouldBlock {
            Ok(None)
        } else {
            Err(err.into())
        }
    }
}
