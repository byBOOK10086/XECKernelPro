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
mod boot_patch;
#[cfg(target_os = "android")]
mod boot_txn;
#[cfg(target_os = "android")]
mod cli;
#[cfg(not(target_os = "android"))]
mod cli_non_android;
#[cfg(target_os = "android")]
mod debug;
mod defs;
#[cfg(target_os = "android")]
mod feature;
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
#[cfg(target_os = "android")]
mod magica;
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
#[cfg(target_os = "android")]
mod sepolicy;
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
