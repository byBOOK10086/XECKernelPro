//! Atomic feature configuration storage; shared by production and host tests.
use std::collections::HashMap;
#[cfg(unix)]
use std::fs::File;
use std::fs::OpenOptions;
use std::io::{self, Read, Write};
#[cfg(unix)]
use std::os::unix::{fs::OpenOptionsExt, io::AsRawFd};
use std::path::Path;

const MAGIC: u32 = 0x7f4b_5355;
const VERSION: u32 = 1;

pub fn load(path: &Path) -> io::Result<HashMap<u32, u64>> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    let file = match options.open(path) {
        Ok(file) => file,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(HashMap::new()),
        Err(err) => return Err(err),
    };
    let mut bytes = Vec::new();
    file.take(65_537).read_to_end(&mut bytes)?;
    if bytes.len() > 65_536 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "feature config too large",
        ));
    }
    if bytes.len() < 12 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "truncated feature header",
        ));
    }
    let word = |offset| {
        u32::from_le_bytes(
            bytes[offset..offset + 4]
                .try_into()
                .expect("checked header"),
        )
    };
    let count = word(8) as usize;
    if word(0) != MAGIC
        || word(4) != VERSION
        || count > (bytes.len() - 12) / 12
        || bytes.len() - 12 != count * 12
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid feature configuration",
        ));
    }
    let mut values = HashMap::new();
    for chunk in bytes[12..].as_chunks::<12>().0 {
        let id = u32::from_le_bytes(chunk[..4].try_into().expect("fixed chunk"));
        let value = u64::from_le_bytes(chunk[4..].try_into().expect("fixed chunk"));
        if values.insert(id, value).is_some() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "duplicate feature id",
            ));
        }
    }
    Ok(values)
}

/// Hold the same process lock across read, merge, fsync and atomic publication.
pub fn update<F>(path: &Path, change: F) -> io::Result<()>
where
    F: FnOnce(&mut HashMap<u32, u64>) -> io::Result<()>,
{
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::other("missing config parent"))?;
    std::fs::create_dir_all(parent)?;
    let lock_path = path.with_extension("lock");
    let mut options = OpenOptions::new();
    options.create(true).read(true).write(true);
    #[cfg(unix)]
    options
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    let lock = options.open(lock_path)?;
    // Android/Linux use the existing libc dependency rather than raising the
    // project's Rust MSRV for File::lock. Windows host tests use std locking.
    #[cfg(unix)]
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX) } != 0 {
        return Err(io::Error::last_os_error());
    }
    #[cfg(windows)]
    lock.lock()?;
    let mut values = load(path)?; // Corrupt configuration must never be overwritten.
    change(&mut values)?;
    // Keep writes within the same bound enforced by load().
    if values.len() > (65_536 - 12) / 12 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "too many feature entries",
        ));
    }
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(io::Error::other)?
        .as_nanos();
    let temp = path.with_extension(format!("tmp.{}.{nonce}", std::process::id()));
    let mut created = false;
    let result = (|| {
        let mut options = OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        let mut file = options.open(&temp)?;
        created = true;
        file.write_all(&MAGIC.to_le_bytes())?;
        file.write_all(&VERSION.to_le_bytes())?;
        let count =
            u32::try_from(values.len()).map_err(|_| io::Error::other("too many features"))?;
        file.write_all(&count.to_le_bytes())?;
        for (id, value) in values {
            file.write_all(&id.to_le_bytes())?;
            file.write_all(&value.to_le_bytes())?;
        }
        file.sync_all()?;
        drop(file);
        std::fs::rename(&temp, path)?;
        #[cfg(unix)]
        File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() && created {
        let _ = std::fs::remove_file(&temp);
    }
    drop(lock);
    result
}
