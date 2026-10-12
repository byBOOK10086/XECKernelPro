// SPDX-License-Identifier: GPL-3.0-only

//! xecinit —— XECKernelPro 挂载态 root（<5.10）的 ramdisk init 载荷。
//!
//! 它是被注入到 boot 镜像 ramdisk 里的 `/init`：内核把它当 **PID 1** 拉起来，
//! 它做完早期准备后把控制权交回真正的 init。之所以这条路能拿到 root，
//! 是因为它是 PID 1 —— 不需要任何内核补丁，也就不存在"信任锚"问题。
//!
//! 设计约束：
//! * 零依赖（只 libc）+ 静态链接：ramdisk 阶段没有动态链接器、没有 /system；
//! * **绝不能返回**：PID 1 退出会让内核 panic；任何失败都只是记日志，
//!   然后尽力把真 init 拉起来；
//! * charger/recovery 下什么都不做，原样交回。

mod init;
#[macro_use]
mod kmsg;

fn main() {
    if unsafe { libc::getpid() } != 1 {
        eprintln!("xecinit 必须作为 PID 1 运行");
        std::process::exit(1);
    }

    kinfo!("xecinit {} 启动（pid=1）", env!("CARGO_PKG_VERSION"));

    let argv: Vec<std::ffi::CString> = std::env::args_os()
        .map(|arg| std::ffi::CString::new(arg.as_encoded_bytes()).unwrap_or_default())
        .collect();

    if let Err(e) = init::run(&argv) {
        kerror!("xecinit 运行出错：{e}");
        kerror!("接下来尝试直接交回 /system/bin/init");
        // 最后一道兜底：哪怕上面每一步都炸了，也要把控制权交出去。
        let _ = init::force_hand_off_to_system_init();
    }

    kerror!("xecinit：无法交回真 init —— 停在原地（不会 panic 循环刷屏），请重刷原厂 boot 镜像");
    loop {
        // PID 1 不能退出；安静挂起，把 kmsg 留给用户排查。
        unsafe {
            libc::pause();
        }
    }
}
