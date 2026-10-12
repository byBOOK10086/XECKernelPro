// SPDX-License-Identifier: GPL-3.0-only

//! ramdisk 注入器：把 `xecinit` 装成 boot 镜像 ramdisk 里的 `/init`。
//!
//! 这是 `<5.10` 挂载态 root 的"第一半"：内核把 `/init` 当 PID 1 拉起来，
//! 我们的 xecinit 做完早期准备再把控制权交回真 init（原 init 备份在 ramdisk 根
//! `.xecpro_init`）。和 Magisk 的 `boot_patch.sh` 是同一套思路，但我们自己实现，
//! 且**刻意拒绝**在已有其它方案补丁的 ramdisk 上动手（覆盖别人的 init 会让设备变砖）。
//!
//! 与 `kpm_patch.rs` 同构：读镜像 → 改 → 重打包；`--flash` 走 `boot_txn` 回滚事务。
//!
//! v1 范围：**legacy rootfs / legacy SAR** 两类 ramdisk 布局（4.x/5.0 主流机型）。
//! 需要内核 hexpatch 的机型（`skip_initramfs` → `want_initramfs`）暂不支持，
//! 检测到就明确报错而不是"打了但起不来"。

use std::fs;
use std::io::Cursor;
use std::path::{Path, PathBuf};

use android_bootimg::cpio::{Cpio, CpioEntry};
use android_bootimg::parser::BootImage;
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::{Context, Result, anyhow, bail, ensure};

use crate::assets;

/// 原 init 的备份名（放在 ramdisk 根，避免依赖 cpio 的建目录语义）。
const BACKUP_INIT: &str = ".xecpro_init";
/// 我们自己的注入标记 + 配置（有它 = 这个 ramdisk 是我们打的）。
const CONFIG: &str = ".xecpro_config";
/// 别人的补丁标记：发现任何一个就拒绝动手。
const FOREIGN_MARKERS: [&str; 4] = [".backup/.magisk", "magisk.xz", "kernelsu.ko", "init.real"];

#[derive(clap::Args, Debug)]
pub struct RamdiskPatchArgs {
    /// 源 boot 镜像（要注入的镜像）。配合 --flash 时可省略。
    #[arg(short, long)]
    pub boot: Option<PathBuf>,

    /// 注入后的镜像输出路径。配合 --flash 时可省略。
    #[arg(short, long)]
    pub output: Option<PathBuf>,

    /// 覆盖已存在的输出文件
    #[arg(long, default_value = "false")]
    pub force: bool,

    /// 直接写回当前 boot 分区（一键，无需 fastboot）
    #[cfg(target_os = "android")]
    #[arg(long, default_value = "false")]
    pub flash: bool,
}

/// 纯内存核心：给 boot 镜像的 ramdisk 注入 xecinit，返回重打包后的镜像。
pub fn patch_ramdisk_bytes(boot_data: &[u8]) -> Result<Vec<u8>> {
    let boot_image = BootImage::parse(boot_data).context("无法解析 boot 镜像")?;
    let ramdisk = boot_image.get_blocks().get_ramdisk().ok_or_else(|| {
        anyhow!("这个镜像里没有 ramdisk（init_boot / vendor_boot 单独分区的布局 v1 暂不支持）")
    })?;

    let (mut cpio, vendor_ramdisk_idx) = crate::boot_patch::android::extract_ramdisk(ramdisk)?;

    for marker in FOREIGN_MARKERS {
        if cpio.exists(marker) {
            bail!(
                "ramdisk 里已有其它方案的补丁（{marker}），拒绝覆盖 —— 请先刷回原厂 boot 镜像再注入"
            );
        }
    }

    // 幂等：已经是我们打的，先把上次的原 init 还原再重打一遍
    if cpio.exists(CONFIG) {
        println!("- 检测到本工具此前的注入，先还原原厂 init");
        cpio.rm(CONFIG, false);
        if cpio.exists(BACKUP_INIT) {
            if cpio.exists("init") {
                cpio.rm("init", false);
            }
            cpio.mv(BACKUP_INIT, "init")?;
        }
    }

    ensure!(
        cpio.exists("init"),
        "ramdisk 里没有 /init，不是可注入的启动 ramdisk"
    );

    // legacy SAR / 2SI 之类的布局靠 ramdisk 里有没有 init.rc 区分；v1 只承诺
    // legacy rootfs/legacy SAR，其它布局明确告知。
    let has_init_rc = cpio.exists("init.rc");
    if !has_init_rc {
        println!(
            "- 注意：ramdisk 根没有 init.rc（可能是 2SI/init_boot 布局）；\
             v1 只验证过 legacy rootfs / legacy SAR，注入后请先确认能开机"
        );
    }

    let orig_init_size = cpio
        .entry_by_name("init")
        .and_then(|entry| entry.data())
        .map_or(0usize, |data| data.len());

    println!("- 备份原 init -> {BACKUP_INIT}（{orig_init_size} 字节）");
    cpio.mv("init", BACKUP_INIT)?;

    let payload = assets::get_asset("xecinit")
        .context("内置载荷里没有 xecinit —— 这次构建没带上 ramdisk init，无法注入")?;
    let config = format!(
        "XECPRO_RAMDISK=1\nPAYLOAD=xecinit\nVERSION={}\nORIG_INIT_SIZE={orig_init_size}\n",
        env!("CARGO_PKG_VERSION")
    );
    println!("- 写入 /init（xecinit）与 {CONFIG}");
    cpio.add("init", CpioEntry::regular(0o750, payload))?;
    cpio.add(
        CONFIG,
        CpioEntry::regular(0o644, Box::new(config.into_bytes())),
    )?;

    let mut new_cpio = Vec::<u8>::new();
    cpio.dump(&mut new_cpio)?;

    let mut patcher = BootImagePatchOption::new(&boot_image);
    if let Some(idx) = vendor_ramdisk_idx {
        patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(new_cpio)), false);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
    }
    let mut repacked = Cursor::new(Vec::with_capacity(boot_data.len()));
    patcher
        .patch(&mut repacked)
        .context("重打包 boot 镜像失败")?;
    Ok(repacked.into_inner())
}

#[cfg(target_os = "android")]
fn read_block_device(path: &str) -> Result<Vec<u8>> {
    use std::io::{Read, Seek, SeekFrom};
    let mut file = std::fs::File::open(path).with_context(|| format!("open {path}"))?;
    let len = file
        .seek(SeekFrom::End(0))
        .with_context(|| format!("seek end of {path}"))? as usize;
    file.seek(SeekFrom::Start(0))
        .with_context(|| format!("seek start of {path}"))?;
    let mut buf = vec![0u8; len];
    file.read_exact(&mut buf)
        .with_context(|| format!("read {path}"))?;
    Ok(buf)
}

/// 一键注入：读当前 boot 分区 → 注入 → 写回。只允许在挂载态区间（<5.10）使用，
/// 因为这条路线会顶掉 `/init`，在 GKI 设备上和内核态 root 互斥。
#[cfg(target_os = "android")]
fn patch_ramdisk_flash() -> Result<()> {
    crate::root_mode::ensure_mount_state("ramdisk 注入（xecinit 挂载态 root）")?;
    let slot = crate::boot_patch::get_slot_suffix(false);
    let partition = format!("/dev/block/by-name/boot{slot}");
    println!("- 读取当前 boot 分区：{partition}");
    let boot_data = read_block_device(&partition)?;
    let repacked = patch_ramdisk_bytes(&boot_data)?;
    println!("- 写回 {partition}");
    crate::boot_txn::prepare(&slot, "boot", false)?;
    crate::boot_txn::arm(&repacked)?;
    crate::boot_patch::flash_partition(&partition, &repacked)?;
    println!("- 注入完成：重启后由 xecinit 接管 /init，再把控制权交回原 init。");
    Ok(())
}

pub fn patch_ramdisk(args: &RamdiskPatchArgs) -> Result<()> {
    #[cfg(target_os = "android")]
    if args.flash {
        return patch_ramdisk_flash();
    }

    let boot = args
        .boot
        .as_ref()
        .context("--boot 是必需的（除非用 --flash）")?;
    let output = args
        .output
        .as_ref()
        .context("--output 是必需的（除非用 --flash）")?;
    ensure!(boot.is_file(), "boot 镜像不存在：{}", boot.display());
    if output.exists() {
        ensure!(
            args.force,
            "输出已存在：{}；要覆盖请加 --force",
            output.display()
        );
    }

    println!("- 读取 {}", boot.display());
    let boot_data = fs::read(boot).with_context(|| format!("读不了 {}", boot.display()))?;
    let repacked = patch_ramdisk_bytes(&boot_data)?;

    let parent = output
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent).with_context(|| format!("建目录失败 {}", parent.display()))?;
    let mut temporary = tempfile::NamedTempFile::new_in(parent).context("创建临时文件失败")?;
    std::io::Write::write_all(&mut temporary, &repacked).context("写临时文件失败")?;
    std::io::Write::flush(&mut temporary).context("flush 失败")?;
    let mode = fs::metadata(boot)
        .with_context(|| format!("stat {}", boot.display()))?
        .permissions()
        .mode();
    use std::os::unix::fs::PermissionsExt;
    temporary
        .as_file()
        .set_permissions(fs::Permissions::from_mode(mode))
        .context("复制权限失败")?;
    temporary
        .persist(output)
        .map_err(|error| anyhow!("保存 {} 失败：{}", output.display(), error.error))?;

    println!("- 输出：{}", output.display());
    println!("- 刷入 boot 分区后重启即可；失败时用原厂镜像恢复。");
    Ok(())
}
