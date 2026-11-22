//! Scrub boot-time AVC/root traces from the kernel and Android log buffers.
//!
//! Detection tools read `dmesg` and `logcat` after boot and look for `avc:`
//! denials, root-implementation domain names and daemon paths left behind by
//! the boot sequence. The collector that stores our own boot logs is attached
//! for [`crate::defs::BOOTLOG_TIMEOUT`], so the embedded script clears the
//! marked buffers once when boot-completed fires and once after that window
//! closes. Only buffers containing a marker are cleared.
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

const SCRIPT: &str = include_str!("bootlog_scrub.sh");

/// Start the scrubber as a detached child so boot-completed is not delayed.
pub fn spawn() {
    let started = unsafe {
        Command::new("/system/bin/sh")
            .arg("-c")
            .arg(SCRIPT)
            .process_group(0)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .pre_exec(|| {
                crate::utils::switch_cgroups();
                Ok(())
            })
            .spawn()
    };
    match started {
        Ok(child) => log::info!("boot log scrub scheduled (pid {})", child.id()),
        Err(error) => log::warn!("failed to start boot log scrub: {error}"),
    }
}
