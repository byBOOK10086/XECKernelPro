#![deny(clippy::all, clippy::pedantic)]
#![warn(clippy::nursery)]
// derive(new) 生成的构造器固定写 `field: field`，全在 sepolicy.rs 的派生目标上，
// 无法在源头修复；也不能往 struct 上加第二个 lint 属性——derive-new 的
// collect_parent_lint_attrs 会把多个 lint 属性用逗号拼进生成代码，产生
// "expected item after attributes" 的非法 token。只能 crate 级放行。
#![allow(
    clippy::module_name_repetitions,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    clippy::doc_markdown,
    clippy::too_many_lines,
    clippy::cast_possible_wrap,
    clippy::large_enum_variant,
    clippy::redundant_field_names
)]

mod apk_sign;
mod assets;
// boot_txn / kpm_patch 从兄弟模块调 boot_patch::flash_partition：crate 根声明的
// 私有模块对整棵 crate 可见，所以这里不需要动可见性；boot_patch 里对应的
// android 子模块与 flash_partition 则必须是 `pub`——clippy::redundant_pub_crate
// （nursery，本 crate 开了 warn(clippy::nursery) 且 CI 用 -Dwarnings）只放过
// `pub`，写 `pub(crate)` 会被判「inside private module」。
mod boot_patch;
#[cfg(target_os = "android")]
mod boot_txn;

// Android-only: the host-side boot-image patcher has no property area and no
// sepolicy engine, so the module is gated exactly like its call sites.
#[cfg(target_os = "android")]
mod bootstate;
#[cfg(target_os = "android")]
mod cli;
#[cfg(not(target_os = "android"))]
mod cli_non_android;
#[cfg(target_os = "android")]
mod debug;
mod defs;
#[cfg(target_os = "android")]
mod feature;
// Upstream's ksu_config support: `feature.rs` imports `crate::feature_id`, and
// `cli.rs` reaches `crate::soft_reboot`, so both must be declared here even
// though this file is otherwise our side of the merge.
//
// feature_id 不能跟着 feature 一起 gate 在 android：boot_patch.rs（host 上也要
// 编，见 module 头注释 "which also runs on the host"）无条件 import 它，gate 住
// 就会让 aarch64-unknown-linux-musl / apple-darwin / windows-gnu 三条 host 构建
// 全部 E0432（clippy 那次 CI 的 build-ksud-extra 就是死在这）。
mod feature_id;
#[cfg(target_os = "android")]
mod init_event;
// KPM / SuSFS are Android-only: they reach into `crate::utils`,
// `crate::defs::WORKING_DIR` and the libc-backed susfs helpers, none of which
// exist for the host-side (macOS / musl) boot-image patcher builds. Keep both
// conditions so `cargo build` for `aarch64-apple-darwin` stays green.
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
mod kpm;
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
mod kpm_patch;
#[cfg(target_os = "android")]
mod ksucalls;
#[cfg(target_os = "android")]
mod late_load;
mod lkm_image;
mod lkm_image_btf;
mod magic_mount;
#[cfg(target_os = "android")]
mod magica;
mod ramdisk_patch;
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
#[cfg(target_os = "android")]
mod su_daemon;
// Magisk 兼容层的 CLI（`magisk` 这个调用名）：脚本里 `magisk --path`/`-V`/
// `--install-module` 等全靠它，缺了会让 `MAGISTMP=$(magisk --path)` 变空串。
#[cfg(target_os = "android")]
mod magisk_compat;
#[cfg(target_os = "android")]
mod metamodule;
#[cfg(target_os = "android")]
mod module;
#[cfg(target_os = "android")]
mod module_config;
#[cfg(target_os = "android")]
mod profile;
#[cfg(target_os = "android")]
mod resetprop;
#[cfg(target_os = "android")]
mod restorecon;
// root 形态判定（内核态 vs 挂载态）的单一闸门：所有会改变 root 形态的操作都要过它
#[cfg(target_os = "android")]
mod root_mode;
#[cfg(target_os = "android")]
mod sepolicy;
#[cfg(target_os = "android")]
mod soft_reboot;
#[cfg(target_os = "android")]
mod su;
#[cfg(target_os = "android")]
mod sulog;
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
mod susfs;
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
mod susfs_config;
#[cfg(all(target_arch = "aarch64", target_os = "android"))]
mod susfs_module;
#[cfg(target_os = "android")]
mod unload;
#[cfg(target_os = "android")]
mod utils;

#[cfg(target_os = "android")]
#[allow(nonstandard_style, unused, unsafe_op_in_unsafe_fn)]
mod ksu_uapi;

fn main() -> anyhow::Result<()> {
    #[cfg(target_os = "android")]
    {
        cli::run()
    }
    #[cfg(not(target_os = "android"))]
    {
        cli_non_android::run()
    }
}
