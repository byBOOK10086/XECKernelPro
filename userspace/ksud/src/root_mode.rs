//! root 形态：内核态 vs 挂载态 —— 互斥规则的**单一口径**。
//!
//! 产品规则（2026-10-12 定）：
//!   * 5.10 及以上（GKI）→ **内核态 root**：原版 KernelSU 的做法，内核里带 KSU
//!     （内置 `CONFIG_KSU=y` 或 `kernelsu.ko` 的 LKM），模块用 overlayfs/metamodule 挂载；
//!   * 5.10 以下（非 GKI）→ **挂载态 root**：KernelPatch/kpimg 注入 boot 镜像提供
//!     root 通道，模块用挂载（magic mount / system 挂载）生效。
//!
//! 两者**互不干扰、不会共存**：任何会改变 root 形态的操作都必须先过这里的闸门。
//! 以前这道规则散落在 `boot_patch.rs::ensure_gki_kernel()` 和
//! `kpm_patch.rs::ensure_non_gki_kernel()` 两处各写一遍，现在统一到这里，
//! `xudc root-mode` 也能把当前判定与理由打出来，便于核对。
#![cfg(target_os = "android")]

use anyhow::{Result, ensure};

/// 与 kernel/ 里那套判定保持同一个式子：5.10+ 或 6.x 视为 GKI。
pub fn kernel_version() -> Result<(i32, i32, i32)> {
    crate::boot_patch::android::get_kernel_version()
}

/// 与 kernel/ 和 manager（`Kernels.kt::isGKI`）严格同一个式子：**只看 major.minor**。
/// 5.10+ 或 6.x 视为 GKI，其余（含 4.x 与 5.0~5.9）都归挂载态区间。
///
/// 这里曾经多带一个 `|| patch > 5`（补丁号 > 5）：`4.19.200` 这类**所有** 4 系内核都会
/// 被误判成 GKI，于是"5.1 以下内核一键嵌入 KPatch"被自己的闸门拒掉 —— 4 系内核拿不到
/// root 的根因就是这一句。补丁号和 GKI 没有任何关系，判定绝不能带上它。
pub fn is_gki_kernel() -> bool {
    match kernel_version() {
        Ok((major, minor, _patch)) => (major == 5 && minor >= 10) || major > 5,
        // 版本读不出来时按"内核态"处理：宁可不给挂载态开闸（挂载态是破坏性写 boot）
        Err(_) => true,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RootMode {
    /// 内核态：内置 KSU / LKM，5.10+
    KernelState,
    /// 挂载态：ramdisk/KPatch 注入 + magic mount 挂模块，<5.10
    MountState,
}

impl RootMode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::KernelState => "kernel",
            Self::MountState => "mount",
        }
    }

    pub const fn describe(self) -> &'static str {
        match self {
            Self::KernelState => "内核态 root（原版 KernelSU：内置 KSU / kernelsu.ko，5.10+）",
            Self::MountState => "挂载态 root（KernelPatch 注入 + 挂载，5.10 以下）",
        }
    }
}

pub fn current() -> RootMode {
    if is_gki_kernel() {
        RootMode::KernelState
    } else {
        RootMode::MountState
    }
}

/// 内核态专用操作（GKI 内核安装、LKM 修补、AnyKernel3 直刷、init_boot 修补…）。
pub fn ensure_kernel_state(op: &str) -> Result<()> {
    let (major, minor, patch) = kernel_version()?;
    ensure!(
        is_gki_kernel(),
        "{op} 需要内核态 root（5.10 及以上）；当前内核 {major}.{minor}.{patch} 属于挂载态 root 区间，\
         请改用内核补丁（KernelPatch）路线，两者不会共存"
    );
    Ok(())
}

/// 挂载态专用操作（kpimg 一键嵌入、magic mount 挂载引擎）。
pub fn ensure_mount_state(op: &str) -> Result<()> {
    let (major, minor, patch) = kernel_version()?;
    ensure!(
        !is_gki_kernel(),
        "{op} 只支持 5.10 以下的非 GKI 内核（当前 {major}.{minor}.{patch}）；\
         5.10+ 设备用内核态 root（内置 KSU / LKM），两者不会共存"
    );
    Ok(())
}
