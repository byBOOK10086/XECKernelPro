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

const XOR_KEY: &[u8] = b"xdcv1";

fn decode(data: &[u8]) -> Vec<u8> {
    data.iter()
        .enumerate()
        .map(|(i, b)| b ^ XOR_KEY[i % XOR_KEY.len()])
        .collect()
}

/// Stage a bundled (obfuscated) asset into tmpfs.
fn stage_asset(name: &str, dst: &Path) -> Result<()> {
    let asset = assets::get_asset(name).with_context(|| format!("asset {name} not bundled"))?;
    let decoded = decode(asset.as_ref().as_ref());
    fs::write(dst, decoded).with_context(|| format!("failed to write {}", dst.display()))?;
    fs::set_permissions(dst, fs::Permissions::from_mode(0o755))?;
    Ok(())
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

/// In-memory core: patch a boot image (raw bytes) with the KPatch-Next engine.
/// Returns the repacked boot image bytes. If the image has no kernel block, the
/// input is returned unchanged.
pub fn patch_kpm_bytes(boot_data: &[u8]) -> Result<Vec<u8>> {
    // A private tmpfs directory prevents concurrent patch calls from sharing
    // executable/input/output paths or removing each other's staging files.
    let staging = tempfile::Builder::new()
        .prefix(".xec-kpm-")
        .tempdir_in("/dev")?;
    fs::set_permissions(staging.path(), fs::Permissions::from_mode(0o700))?;
    let patch_tool = staging.path().join("kptd");
    let patch_core = staging.path().join("kpim");
    let kernel_in = staging.path().join("kernel.in");
    let kernel_out = staging.path().join("kernel.out");

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

    stage_asset("kptd", &patch_tool)?;
    stage_asset("kpim", &patch_core)?;
    fs::write(&kernel_in, &raw_kernel).context("cannot stage kernel")?;

    println!("- Patching kernel with KPatch-Next");
    let status = Command::new(&patch_tool)
        .arg("-p")
        .arg("-i")
        .arg(&kernel_in)
        .arg("-k")
        .arg(&patch_core)
        .arg("-o")
        .arg(&kernel_out)
        .output()
        .context("cannot run patch tool")?;
    if !status.status.success() {
        bail!(
            "patch tool failed: {}",
            String::from_utf8_lossy(&status.stderr).trim()
        );
    }

    let patched_kernel = fs::read(&kernel_out).context("cannot read patched kernel")?;

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
    ensure_non_gki_kernel()?;
    let slot = crate::boot_patch::get_slot_suffix(false);
    let partition = format!("/dev/block/by-name/boot{slot}");
    println!("- Patching live boot partition: {partition}");
    let source = crate::boot_txn::snapshot_source(&slot, "boot", false)?;
    let repacked = patch_kpm_bytes(&source.bytes)?;
    println!("- Flashing patched boot back to {partition}");
    // Serialize and checkpoint the write so partial flash failures restore only
    // the boot image that this operation targets.
    crate::boot_txn::flash_transaction(&slot, "boot", false, &source, &repacked, || Ok(()))?;
    println!("- KPatch-Next embedded successfully. Reboot to take effect.");
    Ok(())
}

/// KPatch-Next（kpimg）只服务 5.10 以下的非 GKI 内核 —— 即"挂载态 root"那一侧。
///
/// 在这条路线上直接写回 boot 分区是破坏性操作：GKI 设备上我们已经有两个受支持
/// 的安装方式（内置 KSU 的 GKI 内核、LKM 的 kernelsu.ko），拿 kpimg 覆盖 boot
/// 分区等于顶掉一个可用的安装，两者也绝不能共存。
/// 判定与文案统一走 `root_mode`（单一口径），可用 `xudc root-mode` 核对。
#[cfg(target_os = "android")]
fn ensure_non_gki_kernel() -> Result<()> {
    crate::root_mode::ensure_mount_state("KPatch/KernelPatch 一键嵌入")
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
