use crate::defs;
use anyhow::Result;
use jwalk::{Parallelism::Serial, WalkDir};
use std::path::Path;

use anyhow::{Context, Ok};
use extattr::{Flags as XattrFlags, lsetxattr};

pub const SYSTEM_CON: &str = "u:object_r:system_file:s0";
/// 与 kernel/selinux/selinux.h 的 `KSU_FILE_CONTEXT` 必须逐字一致。
///
/// 内核侧的类型名已经跟着整体改名走（`KERNEL_SU_FILE = "xecpro_file"`，见 selinux/rules.c
/// 的 `ksu_type(db, KERNEL_SU_FILE, "file_type")`），设备上的策略里并不存在 `ksu_file`
/// 这个类型；继续按旧名打标签，`lsetxattr` 会以 EINVAL 失败，`restorecon()` 直接
/// 在第一步返回错误，后面 `restore_syscon_if_unlabeled(MODULE_DIR)` 也就永远不执行。
pub const KSU_CON: &str = "u:object_r:xecpro_file:s0";
pub const UNLABEL_CON: &str = "u:object_r:unlabeled:s0";

const SELINUX_XATTR: &str = "security.selinux";

pub fn lsetfilecon<P: AsRef<Path>>(path: P, con: &str) -> Result<()> {
    lsetxattr(&path, SELINUX_XATTR, con, XattrFlags::empty()).with_context(|| {
        format!(
            "Failed to change SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    Ok(())
}

pub fn lgetfilecon<P: AsRef<Path>>(path: P) -> Result<String> {
    let con = extattr::lgetxattr(&path, SELINUX_XATTR).with_context(|| {
        format!(
            "Failed to get SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    let con = String::from_utf8_lossy(&con);
    Ok(con.to_string())
}

pub fn setsyscon<P: AsRef<Path>>(path: P) -> Result<()> {
    lsetfilecon(path, SYSTEM_CON)
}

pub fn restore_syscon<P: AsRef<Path>>(dir: P) -> Result<()> {
    for dir_entry in WalkDir::new(dir).parallelism(Serial) {
        if let Some(path) = dir_entry.ok().map(|dir_entry| dir_entry.path()) {
            setsyscon(&path)?;
        }
    }
    Ok(())
}

fn restore_syscon_if_unlabeled<P: AsRef<Path>>(dir: P) -> Result<()> {
    for dir_entry in WalkDir::new(dir).parallelism(Serial) {
        if let Some(path) = dir_entry.ok().map(|dir_entry| dir_entry.path())
            && let anyhow::Result::Ok(con) = lgetfilecon(&path)
            && (con == UNLABEL_CON || con.is_empty())
        {
            lsetfilecon(&path, SYSTEM_CON)?;
        }
    }
    Ok(())
}

pub fn restorecon() -> Result<()> {
    lsetfilecon(defs::DAEMON_PATH, KSU_CON)?;
    restore_syscon_if_unlabeled(defs::MODULE_DIR)?;
    Ok(())
}
