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
//! 支持两类布局：
//!
//! * **legacy rootfs**（ramdisk 根有 `init.rc`，Android 9 及更早）：真 init 启动时
//!   就读这份，xecinit 直接追加阶段脚本即可；
//! * **2SI**（ramdisk 根没有 `init.rc`，Android 10+ 的 4.19/5.4 内核）：真 init.rc 在
//!   `/system/etc/init/hw/init.rc`（只读），所以走**第二段注入** —— 把原 init 里
//!   `/system/bin/init` 这个字符串换成 ramdisk 根的 `/xecinit`，第一段 init 挂完
//!   `/system` 后就会 exec 到我们，那时再去改真正的 init.rc。
//!
//! **不带 ramdisk 的 legacy SAR（A-only）暂不支持**：那条路要先自己 mount
//! `/system` 再 `switch_root`（Magisk 的 `mount_system_root()`），做不到就会卡开机，
//! 所以宁可明确报错也不给一个起不来的镜像。

use std::fs;
use std::io::Cursor;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use android_bootimg::cpio::CpioEntry;
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
/// 2SI 第二段载荷在 ramdisk 根的名字（cpio 里不带前导斜杠）。
const STAGE2_NAME: &str = "xecinit";
/// 第二段载荷的绝对路径。**必须 ≤ 16 字节**：它要被写进原 init 里
/// `/system/bin/init` 那个字符串的位置（见 [`TWO_STAGE_INIT_STR`]）。
const STAGE2_PAYLOAD: &str = "/xecinit";
/// 第一段 init 用来 exec 第二段的字符串（AOSP `first_stage_init.cpp`）。
const TWO_STAGE_INIT_STR: &[u8] = b"/system/bin/init";
/// 首启自举脚本在 ramdisk 根的名字：第一次开机把 ramdisk 里的 xudc 铺到 `/data`。
const BOOTSTRAP_SH: &str = "/xecpro-boot.sh";
/// legacy SAR 的内核 hexpatch：`skip_initramfs\0` → `want_initramfs\0`，与 Magisk
/// `scripts/boot_patch.sh:234-239` 逐字节一致（cmdline 带 `skip_initramfs` 时内核会
/// **跳过 ramdisk**，我们既然合成了 ramdisk 就必须改掉它）。
const SKIP_INITRAMFS: &[u8] = b"skip_initramfs\0";
const WANT_INITRAMFS: &[u8] = b"want_initramfs\0";

/// 注入布局。
#[derive(Clone, Copy, PartialEq, Eq)]
enum Layout {
    /// ramdisk 根有 `init.rc`，真 init 启动时就读它（Android 9 及更早）。
    LegacyRootfs,
    /// ramdisk 根没有 `init.rc`，真 init.rc 在 `/system`（Android 10+）。靠第二段注入。
    TwoStage,
    /// boot 里根本没有 ramdisk（A-only legacy SAR）：合成一个，运行时把 system 挂成根。
    LegacySar,
}

impl Layout {
    /// 启动早期，我们的载荷在哪个目录下（rc 与自举脚本里的路径按它拼）。
    ///
    /// SAR 在 `switch_root` 之前会把整个 ramdisk 重新挂到 `/mnt/xecpro`（旧根被换掉
    /// 之后只剩这一条路还能读到它们）；另外两类就在根上，目录为空串。
    const fn payload_dir(self) -> &'static str {
        match self {
            Self::LegacySar => "/mnt/xecpro",
            Self::LegacyRootfs | Self::TwoStage => "",
        }
    }
}

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

    /// legacy SAR（A-only、boot 里没有 ramdisk 的机型）：**实验性**。
    ///
    /// 打开后会往镜像里合成一个 ramdisk，并把内核里的 `skip_initramfs` 改成
    /// `want_initramfs`（与 Magisk 同一套做法），运行时 xecinit 先 mount system
    /// 再 switch_root。写错的表现是**卡开机**，所以默认关闭；打开前务必先备份
    /// 原厂 boot 镜像。
    #[arg(long, default_value = "false")]
    pub legacy_sar: bool,
}

/// 纯内存核心：给 boot 镜像的 ramdisk 注入 xecinit，返回重打包后的镜像。
///
/// `legacy_sar` 为真时允许对"没有 ramdisk"的镜像合成一个（见 [`Layout`]）。
pub fn patch_ramdisk_bytes(boot_data: &[u8], legacy_sar: bool) -> Result<Vec<u8>> {
    let boot_image = BootImage::parse(boot_data).context("无法解析 boot 镜像")?;

    let (mut cpio, vendor_ramdisk_idx, layout) =
        if let Some(ramdisk) = boot_image.get_blocks().get_ramdisk() {
            let (cpio, idx) = crate::boot_patch::extract_ramdisk(ramdisk)?;
            let layout = if cpio.exists("init.rc") {
                Layout::LegacyRootfs
            } else {
                Layout::TwoStage
            };
            (cpio, idx, layout)
        } else if legacy_sar {
            println!("- 布局：legacy SAR（boot 里没有 ramdisk，按 --legacy-sar 合成一个）");
            println!("! 实验路线：失败的表现是**卡开机**，请确认手里有原厂 boot 镜像可以刷回");
            (android_bootimg::cpio::Cpio::new(), None, Layout::LegacySar)
        } else {
            bail!(
                "这个镜像里没有 ramdisk。两种可能：\
                 ① 不带 ramdisk 的 legacy SAR（A-only）机型 —— 要合成 ramdisk 得显式加 \
                 `--legacy-sar`（实验性，先备份原厂 boot 再试）；\
                 ② ramdisk 在 init_boot / vendor_boot 单独分区的机型 —— 请改选 init_boot.img"
            )
        };

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

    // 布局相关处理（见 [`Layout`]）：SAR 的 ramdisk 是我们刚合成的，没有原 init
    // 可备份；2SI 要把原 init 里"exec 第二段"那个字符串换掉。
    if layout == Layout::TwoStage {
        println!(
            "- 布局：2SI（ramdisk 根没有 init.rc）。阶段脚本会在第二段注入 —— \
             原 init 里的 `{}` 会被换成 `{STAGE2_PAYLOAD}`。",
            String::from_utf8_lossy(TWO_STAGE_INIT_STR)
        );
    }

    let mut orig_init_size = 0usize;
    if layout == Layout::LegacySar {
        println!("- 合成 ramdisk：没有原 init 可备份（xecinit 自己 mount system 再交棒）");
    } else {
        let orig_init = cpio
            .entry_by_name("init")
            .and_then(|entry| entry.data())
            .map(<[u8]>::to_vec)
            .context("ramdisk 里没有 /init，不是可注入的启动 ramdisk")?;
        orig_init_size = orig_init.len();
        println!("- 备份原 init -> {BACKUP_INIT}（{orig_init_size} 字节）");
        cpio.mv("init", BACKUP_INIT)?;
        if layout == Layout::TwoStage {
            let patched = hexpatch_two_stage(&orig_init)?;
            cpio.rm(BACKUP_INIT, false);
            cpio.add(BACKUP_INIT, CpioEntry::regular(0o750, Box::new(patched)))?;
        }
    }

    // 注入的 rc 要干三件事：把 daemon 拉起来（su 通道）、把三个阶段交回 xudc
    // （magic mount 与模块脚本在 post-fs-data 跑，service/boot-completed 也照
    // KernelSU 的约定跑，见 kernel/runtime/ksud_integration.c 里内核态用的那组
    // trigger —— 挂载态没有内核 hook，只能自己写进 init.rc）。
    //
    // seclabel 一律写 `u:r:su:s0`；部分 user 版固件的策略里没有 su 域，那种机型
    // 由 xecinit 在追加进真 init.rc 之前自动去掉这个标签（服务退化成以 init 的
    // 上下文运行），不需要重新打一次 boot。
    // 我们自己的载荷在启动早期能被读到哪：普通布局在根上（空串），SAR 在 /mnt/xecpro
    // （xecinit 在 switch_root 之前会把 ramdisk 重新挂到那儿，见 [`Layout::payload_dir`]）。
    let payload_dir = layout.payload_dir();

    let service_rc = format!(
        r"# xecpro: 挂载态 root 的启动阶段与 su 通道
# 本文件由 `xudc ramdisk-patch` 注入，启动时由 xecinit 追加到真 init.rc。
# 若本机 SELinux 策略里没有 su 域，xecinit 会自动改写下面的 seclabel。
service xecpro_daemon {dir}/xudc daemon
    class late_start
    user root
    group root
    seclabel u:r:su:s0

on post-fs-data
    mkdir {dir} 0700 root root
    start logd
    exec -- /system/bin/sh {payload_dir}{BOOTSTRAP_SH}
    start xecpro_daemon
    exec u:r:su:s0 root -- {dir}/xudc post-fs-data

on nonencrypted
    exec u:r:su:s0 root -- {dir}/xudc services

on property:vold.decrypt=trigger_restart_framework
    exec u:r:su:s0 root -- {dir}/xudc services

on property:sys.boot_completed=1
    exec u:r:su:s0 root -- {dir}/xudc boot-completed
",
        dir = crate::defs::XECPRO_DIR
    );

    // 首启自举：第一次开机时 /data 是空的（用户当时还没有 root，没法自己铺用户态），
    // 所以 ramdisk 里必须带一份 xudc，由这个脚本把它落到 `{dir}`：`install-mount-state`
    // 会把 daemon、su 垫片、busybox 与最小模块一次铺好。之后每次开机只比一次内容。
    let boot_script = format!(
        r#"#!/system/bin/sh
# xecpro 首启自举（由 xudc ramdisk-patch 注入，见 userspace/ksud/src/ramdisk_patch.rs）。
DIR={dir}
if [ ! -x "$DIR/xudc" ] || ! cmp -s {payload_dir}/xudc "$DIR/xudc"; then
    {payload_dir}/xudc install-mount-state > /dev/kmsg 2>&1
fi
"#,
        dir = crate::defs::XECPRO_DIR
    );

    // 目录条目必须显式加：Linux 的 initramfs 解包器只对 S_ISDIR 的成员建目录，
    // 父目录不存在时 `overlay.d/xecpro.rc` 会被**静默丢弃**（Magisk 同样显式
    // `mkdir 0750 overlay.d`）。少了这一行，注入会"成功"，但启动后 xecinit 只会
    // 打印"没有 /overlay.d，跳过 overlay 合并"，daemon 永远起不来。
    cpio.add("overlay.d", CpioEntry::dir(0o750))?;
    cpio.add(
        "overlay.d/xecpro.rc",
        CpioEntry::regular(0o644, Box::new(service_rc.into_bytes())),
    )?;
    cpio.add(
        BOOTSTRAP_SH.trim_start_matches('/'),
        CpioEntry::regular(0o755, Box::new(boot_script.into_bytes())),
    )?;

    // 把当前这份 xudc（正在跑的就是它）也塞进 ramdisk：首启自举靠它。
    let self_exe = std::env::current_exe().context("拿不到当前可执行文件路径")?;
    let self_bytes = fs::read(&self_exe).with_context(|| format!("读 {}", self_exe.display()))?;
    let self_size = self_bytes.len();
    cpio.add("xudc", CpioEntry::regular(0o755, Box::new(self_bytes)))?;
    println!("- 写入 {BOOTSTRAP_SH} 与 ramdisk 内的 xudc（{self_size} 字节，首启自举用）");

    println!("- 写入 /init（xecinit）与 {CONFIG}");
    let payload = assets::get_asset("xecinit")
        .context("内置载荷里没有 xecinit —— 这次构建没带上 ramdisk init，无法注入")?;
    cpio.add("init", CpioEntry::regular(0o750, payload))?;
    if layout == Layout::TwoStage {
        let stage2 = assets::get_asset("xecinit")
            .context("内置载荷里没有 xecinit —— 这次构建没带上 ramdisk init，无法注入")?;
        cpio.add(STAGE2_NAME, CpioEntry::regular(0o750, stage2))?;
        println!("- 2SI 第二段载荷：{STAGE2_PAYLOAD}（ramdisk 根，{STAGE2_NAME}）");
    }

    let config = format!(
        "XECPRO_RAMDISK=1\nPAYLOAD=xecinit\nVERSION={}\nORIG_INIT_SIZE={orig_init_size}\nLAYOUT={}\n",
        env!("CARGO_PKG_VERSION"),
        match layout {
            Layout::LegacyRootfs => "legacy-rootfs",
            Layout::TwoStage => "2si",
            Layout::LegacySar => "legacy-sar",
        }
    );
    cpio.add(
        CONFIG,
        CpioEntry::regular(0o644, Box::new(config.into_bytes())),
    )?;

    let mut new_cpio = Vec::<u8>::new();
    cpio.dump(&mut new_cpio)?;

    let mut patcher = BootImagePatchOption::new(&boot_image);
    if let Some(idx) = vendor_ramdisk_idx {
        patcher.replace_vendor_ramdisk(idx, Box::new(Cursor::new(new_cpio)), false);
    } else if layout == Layout::LegacySar {
        // 源镜像没有 ramdisk：此时 `compressed=false` 会被 patcher 强制成 LZ4，
        // 传 true 才是"原样写入未压缩的 newc"（目标内核不一定带 CONFIG_RD_LZ4）。
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), true);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
    }

    if layout == Layout::LegacySar {
        // 合成 ramdisk 还必须让内核真的去用它：cmdline 里的 skip_initramfs
        // 会让内核跳过 ramdisk，改成 want_initramfs 才加载（Magisk 同款 hexpatch）。
        let kernel = hexpatch_skip_initramfs(&boot_image)?;
        patcher.replace_kernel(Box::new(Cursor::new(kernel)), false);
    }

    let mut repacked = Cursor::new(Vec::with_capacity(boot_data.len()));
    patcher
        .patch(&mut repacked)
        .context("重打包 boot 镜像失败")?;
    let out = repacked.into_inner();
    println!(
        "- 镜像大小：{} -> {} 字节（+{}；ramdisk 里带了 xecinit + xudc + 自举脚本）",
        boot_data.len(),
        out.len(),
        out.len().saturating_sub(boot_data.len())
    );
    Ok(out)
}

/// 字节子串查找（`windows` 版本，够用且不引依赖）。
fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|win| win == needle)
}

/// 把 `from` 全部替换成 `to`（两者**必须等长**），返回替换处数。
fn replace_all(data: &mut [u8], from: &[u8], to: &[u8]) -> usize {
    debug_assert_eq!(from.len(), to.len());
    let mut hits = 0usize;
    let mut at = 0usize;
    while let Some(offset) = find_bytes(&data[at..], from) {
        let start = at + offset;
        data[start..start + to.len()].copy_from_slice(to);
        hits += 1;
        at = start + from.len();
    }
    hits
}

/// 把原 init 里 `/system/bin/init` 换成我们的第二段载荷路径（2SI 专用）。
///
/// 2SI 布局下第一段 init 挂完 `/system` 之后，会 exec 这个字符串指向的二进制当
/// 第二段；换成 `/xecinit`（ramdisk 根，此时 `/` 就是 ramdisk）之后第二段就落到
/// 我们手里，才有机会改真正的 `/system/etc/init/hw/init.rc`。做法与 Magisk 的
/// `init/twostage.rs` 一致（它换成 `/data/magiskinit`）。
///
/// 替换后用 NUL 补齐到原串长度：原位置是 C 字符串字面量，后面紧跟的字节不能动。
///
/// 找不到这个字符串就直接报错：那说明这个布局我们不认识，与其交出一个"能开机但
/// 没有 root"的镜像，不如让用户先别刷。
fn hexpatch_two_stage(init: &[u8]) -> Result<Vec<u8>> {
    ensure!(
        STAGE2_PAYLOAD.len() <= TWO_STAGE_INIT_STR.len(),
        "第二段载荷路径太长：{STAGE2_PAYLOAD}"
    );

    let mut out = init.to_vec();
    let mut replacement = vec![0u8; TWO_STAGE_INIT_STR.len()];
    replacement[..STAGE2_PAYLOAD.len()].copy_from_slice(STAGE2_PAYLOAD.as_bytes());
    let hits = replace_all(&mut out, TWO_STAGE_INIT_STR, &replacement);

    ensure!(
        hits > 0,
        "这个 2SI 镜像的原 init 里找不到 `{}` —— 布局不认识，先别刷（刷回原厂 boot 即可）",
        String::from_utf8_lossy(TWO_STAGE_INIT_STR)
    );
    println!(
        "- 2SI：原 init 里的 `{}` 已换成 `{STAGE2_PAYLOAD}`（{hits} 处）",
        String::from_utf8_lossy(TWO_STAGE_INIT_STR)
    );
    Ok(out)
}

/// legacy SAR：把内核里的 `skip_initramfs` 改成 `want_initramfs`。
///
/// 内核先解压（`dump(.., false)`）再找字符串 —— 压缩过的内核里直接搜是搜不到的；
/// 改完交回 patcher 时传 `compressed=false`，让它按原格式重新压回去。
fn hexpatch_skip_initramfs(boot_image: &BootImage) -> Result<Vec<u8>> {
    let kernel = boot_image
        .get_blocks()
        .get_kernel()
        .context("这个镜像里没有 kernel 段，改不动 skip_initramfs")?;
    let mut raw = Vec::new();
    kernel.dump(&mut raw, false).context("解出内核失败")?;
    let hits = replace_all(&mut raw, SKIP_INITRAMFS, WANT_INITRAMFS);
    ensure!(
        hits > 0,
        "内核里找不到 `skip_initramfs` —— 这台设备可能不是 legacy SAR，不要用 --legacy-sar"
    );
    println!("- legacy SAR：内核里 skip_initramfs -> want_initramfs（{hits} 处）");
    Ok(raw)
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

/// 块设备容量（字节）。`/sys/class/block/<name>/size` 是 512 字节扇区数。
#[cfg(target_os = "android")]
fn block_device_size(partition: &str) -> Option<u64> {
    let real = fs::canonicalize(partition).ok()?;
    let name = real.file_name()?.to_string_lossy().to_string();
    let sectors: u64 = fs::read_to_string(format!("/sys/class/block/{name}/size"))
        .ok()?
        .trim()
        .parse()
        .ok()?;
    Some(sectors * 512)
}

/// 一键注入：读当前 boot 分区 → 注入 → 写回。只允许在挂载态区间（<5.10）使用，
/// 因为这条路线会顶掉 `/init`，在 GKI 设备上和内核态 root 互斥。
///
/// 分区按内核态那边同一套逻辑挑（`choose_boot_partition`）：A/B 槽位、
/// 以及 ramdisk 在 `init_boot` 的机型都不会挑错。
#[cfg(target_os = "android")]
fn patch_ramdisk_flash(legacy_sar: bool) -> Result<()> {
    crate::root_mode::ensure_mount_state("ramdisk 注入（xecinit 挂载态 root）")?;
    let slot = crate::boot_patch::get_slot_suffix(false);
    let kmi = crate::boot_patch::get_current_kmi()?;
    let name = crate::boot_patch::choose_boot_partition(&kmi, false, &None::<String>);
    let partition = format!("/dev/block/by-name/{name}{slot}");
    println!("- 读取当前 {name} 分区（kmi={kmi}）：{partition}");
    let boot_data = read_block_device(&partition)?;
    let repacked = patch_ramdisk_bytes(&boot_data, legacy_sar)?;

    // 注入要往 ramdisk 里多塞一份 xudc（13MB 上下），分区放不下就绝不能写：
    // 写坏 boot 分区 = 变砖。容量读不到时只警告（用 --output 手动刷的那条路也走这里）。
    match block_device_size(&partition) {
        Some(size) if repacked.len() as u64 > size => bail!(
            "注入后的镜像 {} 字节超过 {partition} 的容量 {size} 字节 —— 拒绝写入。\
             请刷回原厂 boot（这台机型的 boot 分区放不下挂载态载荷）",
            repacked.len()
        ),
        Some(size) => println!(
            "- 分区容量 {size} 字节，注入后 {} 字节，放得下",
            repacked.len()
        ),
        None => println!("! 读不到分区容量，无法预检大小；刷之前请自行确认能放下"),
    }

    println!("- 写回 {partition}");
    crate::boot_txn::prepare(&slot, &name, false)?;
    crate::boot_txn::arm(&repacked)?;
    crate::boot_patch::flash_partition(&partition, &repacked)?;
    println!("- 注入完成：重启后由 xecinit 接管 /init，再把控制权交回原 init。");
    Ok(())
}

pub fn patch_ramdisk(args: &RamdiskPatchArgs) -> Result<()> {
    #[cfg(target_os = "android")]
    if args.flash {
        return patch_ramdisk_flash(args.legacy_sar);
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
    let repacked = patch_ramdisk_bytes(&boot_data, args.legacy_sar)?;

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
