// SPDX-License-Identifier: GPL-2.0-only

use std::fs;
use std::io::{Cursor, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;

use android_bootimg::parser::BootImage;
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::{Context, Result, anyhow, bail, ensure};

use crate::assets;

// tmpfs staging paths (never under /data/adb).
const PATCH_TOOL_BIN: &str = "/dev/.kptd";
const PATCH_CORE_BIN: &str = "/dev/.kpim";
const KERNEL_IN: &str = "/dev/.kkin";
const KERNEL_OUT: &str = "/dev/.kkot";

const XOR_KEY: &[u8] = b"xdcv1";

fn decode(data: &[u8]) -> Vec<u8> {
    data.iter()
        .enumerate()
        .map(|(i, b)| b ^ XOR_KEY[i % XOR_KEY.len()])
        .collect()
}

/// Stage a bundled (obfuscated) asset into tmpfs.
fn stage_asset(name: &str, dst: &str) -> Result<()> {
    if Path::new(dst).exists() {
        return Ok(());
    }
    let asset = assets::get_asset(name).with_context(|| format!("asset {name} not bundled"))?;
    let decoded = decode(asset.as_ref().as_ref());
    fs::write(dst, decoded).with_context(|| format!("failed to write {dst}"))?;
    fs::set_permissions(dst, fs::Permissions::from_mode(0o755))?;
    Ok(())
}

/// Removes all tmpfs staging files on drop (success or failure).
struct CleanupGuard;
impl Drop for CleanupGuard {
    fn drop(&mut self) {
        for p in [PATCH_TOOL_BIN, PATCH_CORE_BIN, KERNEL_IN, KERNEL_OUT] {
            let _ = fs::remove_file(p);
        }
    }
}

#[derive(clap::Args, Debug)]
pub struct BootPatchKpmArgs {
    /// Source boot image (contains the kernel to patch). Omit with --flash to
    /// patch the live boot partition in place.
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// Patched boot image output. Required unless --flash is set.
    #[arg(short, long)]
    pub output: Option<PathBuf>,

    /// Replace an existing output file
    #[arg(long, default_value = "false")]
    pub force: bool,

    /// Patch and flash the live boot partition in place (one-click embed, no fastboot).
    #[cfg(target_os = "android")]
    #[arg(long, default_value = "false")]
    pub flash: bool,
}

/// Read a block device (e.g. /dev/block/by-name/boot) into memory. Block
/// devices report length 0 via metadata, so use seek-to-end to size the buffer.
#[cfg(target_os = "android")]
fn read_block_device(path: &str) -> Result<Vec<u8>> {
    use std::io::{Read, Seek, SeekFrom};
    let mut f = std::fs::File::open(path).with_context(|| format!("open {path}"))?;
    let len = f
        .seek(SeekFrom::End(0))
        .with_context(|| format!("seek end of {path}"))? as usize;
    f.seek(SeekFrom::Start(0))
        .with_context(|| format!("seek start of {path}"))?;
    let mut buf = vec![0u8; len];
    f.read_exact(&mut buf)
        .with_context(|| format!("read {path}"))?;
    Ok(buf)
}

/// In-memory core: patch a boot image (raw bytes) with the KPatch-Next engine.
/// Returns the repacked boot image bytes. If the image has no kernel block, the
/// input is returned unchanged.
pub fn patch_kpm_bytes(boot_data: &[u8]) -> Result<Vec<u8>> {
    let _guard = CleanupGuard;

    let boot_image = BootImage::parse(boot_data).context("cannot parse boot image")?;
    let Some(kernel) = boot_image.get_blocks().get_kernel() else {
        println!("- KPM: image has no kernel (e.g. init_boot/vendor_boot), skip kernel patch");
        return Ok(boot_data.to_vec());
    };

    println!("- Decompressing kernel");
    let mut raw_kernel = Vec::new();
    kernel
        .dump(&mut raw_kernel, false)
        .context("cannot decompress boot kernel")?;

    stage_asset("kptd", PATCH_TOOL_BIN)?;
    stage_asset("kpim", PATCH_CORE_BIN)?;
    fs::write(KERNEL_IN, &raw_kernel).context("cannot stage kernel")?;

    println!("- Patching kernel with KPatch-Next");
    let status = Command::new(PATCH_TOOL_BIN)
        .args([
            "-p",
            "-i",
            KERNEL_IN,
            "-k",
            PATCH_CORE_BIN,
            "-o",
            KERNEL_OUT,
        ])
        .output()
        .context("cannot run patch tool")?;
    if !status.status.success() {
        bail!(
            "patch tool failed: {}",
            String::from_utf8_lossy(&status.stderr).trim()
        );
    }

    let patched_kernel = fs::read(KERNEL_OUT).context("cannot read patched kernel")?;

    println!("- Repacking boot image");
    let mut patcher = BootImagePatchOption::new(&boot_image);
    patcher.replace_kernel(Box::new(Cursor::new(patched_kernel)), false);
    let mut repacked = Cursor::new(Vec::with_capacity(boot_data.len()));
    patcher
        .patch(&mut repacked)
        .context("cannot repack boot image")?;
    Ok(repacked.into_inner())
}

/// One-click embed: read the live boot partition, patch its kernel with
/// KPatch-Next, and flash the result back in place. No external boot file or
/// fastboot is required — only a reboot afterwards.
#[cfg(target_os = "android")]
fn patch_kpm_flash() -> Result<()> {
    let slot = crate::boot_patch::get_slot_suffix(false);
    let partition = format!("/dev/block/by-name/boot{slot}");
    println!("- Patching live boot partition: {partition}");
    let boot_data = read_block_device(&partition)?;
    let repacked = patch_kpm_bytes(&boot_data)?;
    println!("- Flashing patched boot back to {partition}");
    crate::boot_patch::flash_partition(&partition, &repacked)?;
    println!("- KPatch-Next embedded successfully. Reboot to take effect.");
    Ok(())
}

pub fn patch_kpm(args: &BootPatchKpmArgs) -> Result<()> {
    #[cfg(target_os = "android")]
    if args.flash {
        return patch_kpm_flash();
    }

    let boot = args
        .boot
        .as_ref()
        .context("--boot is required unless --flash is used")?;
    let output = args
        .output
        .as_ref()
        .context("--output is required unless --flash is used")?;

    ensure!(
        boot.is_file(),
        "boot image does not exist: {}",
        boot.display()
    );
    if output.exists() {
        ensure!(
            args.force,
            "output already exists: {}; use --force",
            output.display()
        );
    }

    println!("- Reading boot image");
    let boot_data =
        fs::read(boot).with_context(|| format!("cannot read boot image {}", boot.display()))?;
    let repacked = patch_kpm_bytes(&boot_data)?;

    let output_parent = output
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(output_parent)
        .with_context(|| format!("cannot create output directory {}", output_parent.display()))?;
    let mut temporary = tempfile::NamedTempFile::new_in(output_parent)
        .context("cannot create temporary boot image")?;
    temporary
        .write_all(&repacked)
        .context("cannot write patched boot image")?;
    temporary
        .flush()
        .context("cannot flush patched boot image")?;
    let mode = fs::metadata(boot)
        .with_context(|| format!("cannot stat boot image {}", boot.display()))?
        .permissions()
        .mode();
    temporary
        .as_file()
        .set_permissions(fs::Permissions::from_mode(mode))
        .context("cannot copy boot image permissions")?;
    temporary.persist(output).map_err(|error| {
        anyhow!(
            "cannot persist patched boot image {}: {}",
            output.display(),
            error.error
        )
    })?;

    println!("- Output: {}", output.display());
    Ok(())
}
