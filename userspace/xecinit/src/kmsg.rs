// SPDX-License-Identifier: GPL-3.0-only

//! 极简 kmsg 日志：ramdisk 早期没有 /dev/log、没有 logd，唯一的出口是 /dev/kmsg。
//! 用 std 直接追加写，失败就吞掉 —— init 里任何一步都不允许因为"日志写不出去"而崩。

use std::io::Write;

pub fn log(level: char, args: std::fmt::Arguments<'_>) {
    let line = format!("xecinit: [{level}] {args}\n");
    for path in ["/dev/kmsg", "/proc/kmsg"] {
        if let Ok(mut file) = std::fs::OpenOptions::new().append(true).open(path)
            && file.write_all(line.as_bytes()).is_ok()
        {
            return;
        }
    }
}

#[macro_export]
macro_rules! klog {
    ($level:expr, $($arg:tt)*) => {
        $crate::kmsg::log($level, format_args!($($arg)*))
    };
}

#[macro_export]
macro_rules! kinfo {
    ($($arg:tt)*) => { $crate::klog!('I', $($arg)*) };
}

#[macro_export]
macro_rules! kwarn {
    ($($arg:tt)*) => { $crate::klog!('W', $($arg)*) };
}

#[macro_export]
macro_rules! kerror {
    ($($arg:tt)*) => { $crate::klog!('E', $($arg)*) };
}
