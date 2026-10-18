use anyhow::Result;
use rust_embed::RustEmbed;

#[cfg(target_os = "android")]
mod android {
    use crate::assets::Asset;
    use crate::defs::BINARY_DIR;
    use crate::utils::ensure_binary;
    use const_format::concatcp;

    pub const RESETPROP_PATH: &str = concatcp!(BINARY_DIR, "resetprop");
    pub const BUSYBOX_PATH: &str = concatcp!(BINARY_DIR, "busybox");
    pub const BOOTCTL_PATH: &str = concatcp!(BINARY_DIR, "bootctl");
    /// 真 Magisk 的目录：挂载态（方案甲）设备上它才是"我们的邻居"。
    const MAGISK_DIR: &str = "/data/adb/magisk";

    /// 模块脚本/安装器该用哪个 bin 目录？
    ///
    /// 内核态（5.10+）用 KernelSU 的既有布局 `/data/adb/ksu/bin`；挂载态（<5.10）
    /// 优先用我们自己的 `/data/adb/xecpro/bin`。
    ///
    /// 但挂载态的**正式路线现在是 Magisk**（方案甲，管理器跑官方 `boot_patch.sh`）：
    /// 那种设备上 `/data/adb/xecpro/bin` 根本不会存在（没人跑 `install-mount-state`），
    /// 而真 Magisk 自带 `/data/adb/magisk`。所以这里做一次**只对挂载态生效**的探测：
    /// 有我们自己的就用它，没有就退到 Magisk 的目录。内核态照旧返回原值，一行不变。
    pub fn binary_dir() -> String {
        if !crate::utils::is_mount_state() {
            return BINARY_DIR.trim_end_matches('/').to_string();
        }
        if std::path::Path::new(crate::defs::XECPRO_BIN_DIR).is_dir() {
            return crate::defs::XECPRO_BIN_DIR.to_string();
        }
        MAGISK_DIR.to_string()
    }

    /// 挂载态下 busybox 的路径（随 [`binary_dir`] 的探测结果走）。
    pub fn busybox_path() -> String {
        if !crate::utils::is_mount_state() {
            return BUSYBOX_PATH.to_string();
        }
        let ours = format!("{}/busybox", crate::defs::XECPRO_BIN_DIR);
        if std::path::Path::new(&ours).is_file() {
            return ours;
        }
        format!("{MAGISK_DIR}/busybox")
    }

    pub fn ensure_binaries(ignore_if_exist: bool) -> anyhow::Result<()> {
        // 挂载态设备不铺 `/data/adb/ksu/bin/*`：那是内核态的布局与指纹。
        // 挂载态自己的工具（xudc / su 垫片 / busybox）由 `xudc install-mount-state`
        // 铺到 `/data/adb/xecpro/bin`，两边井水不犯河水。
        if crate::utils::is_mount_state() {
            return Ok(());
        }

        for file in Asset::iter() {
            if file == "ksuinit"
                || file == "waitsys"
                || file == "kpmd"
                || file == "kptd"
                || file == "kpim"
                || file.ends_with(".ko")
            {
                // don't extract internal executables (ksuinit, waitsys), the
                // on-demand patch assets, and kernel modules
                continue;
            }
            let asset =
                Asset::get(&file).ok_or_else(|| anyhow::anyhow!("asset not found: {file}"))?;
            ensure_binary(format!("{BINARY_DIR}{file}"), &asset.data, ignore_if_exist)?;
        }

        // Create resetprop -> ksud symlink (resetprop is now built into ksud)
        let resetprop_link = RESETPROP_PATH;
        let _ = std::fs::remove_file(resetprop_link);
        std::os::unix::fs::symlink("/data/adb/xudc", resetprop_link)?;

        Ok(())
    }
}

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(all(target_arch = "x86_64", target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin/x86_64"]
struct Asset;

#[cfg(all(target_arch = "aarch64", target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin/aarch64"]
struct Asset;

#[cfg(all(target_arch = "riscv64", target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin/riscv64"]
struct Asset;

// If not Android, ie. macos, linux, windows, include all architectures.
#[cfg(not(target_os = "android"))]
#[derive(RustEmbed)]
#[folder = "bin"]
struct Asset;

#[allow(unused)]
pub fn get_asset_data(name: &str) -> Result<std::borrow::Cow<'static, [u8]>> {
    let asset = Asset::get(name).ok_or_else(|| anyhow::anyhow!("asset not found: {name}"))?;
    Ok(asset.data)
}

pub fn get_asset(name: &str) -> Result<Box<dyn AsRef<[u8]>>> {
    let asset = Asset::get(name).ok_or_else(|| anyhow::anyhow!("asset not found: {name}"))?;
    Ok(Box::new(asset.data))
}

pub fn list_supported_kmi() -> std::vec::Vec<std::string::String> {
    let mut list = Vec::new();
    for file in Asset::iter() {
        // kmi_name = "xxx_kernelsu.ko"
        if let Some(kmi) = file.strip_suffix("_kernelsu.ko") {
            list.push(kmi.to_string());
        }
    }
    list
}
