// SPDX-License-Identifier: GPL-3.0
/*
 * 隐身入口守卫（XECKernelPro）：把管理器的入口寄生在系统设置
 * “激活开发者选项”的原生手势上。
 *
 * 设计约束（与用户约定）：
 *  - 完全不碰系统设置的 UI/导航栏：不注入、不 hook Settings 进程
 *    （国产 ROM 的设置深定制，Java 层寄生要 LSPlant 这类 GPL 库且逐 OEM 适配，
 *    不取）。我们只在 root 侧观察。
 *  - 版本号连点 5 下 = 原生开发者选项行为，照常生效，守卫对此无感知也不干扰；
 *    连点到第 10 下（默认）= 拉起管理器。计数在守卫进程里独立进行。
 *  - 触发条件收紧到“前台焦点在系统设置包 + 时间窗内连点 N 次”，
 *    避免游戏等快速点击场景误触发。
 *  - 兜底永远存在：adb / root shell 一句 am start 就能拉起管理器，
 *    守卫挂了也只是少了入口，不会锁死。
 *
 * 实现要点：
 *  - /dev/input/event* 只读解析 input_event（24B/个，64 位 arm64 布局），
 *    只统计 BTN_TOUCH(0x14a) 按下沿；不 ioctl 筛设备（非触摸设备不产该码）。
 *  - 前台校验仅在计数达标时跑一次 dumpsys（点击稀疏，代价可忽略）。
 *  - 配置 /data/adb/ksu/stealth.conf；启动写 pid 到 stealth.pid；
 *    守卫周期性自检配置被删即退出（Stop 免锁死）。
 */
use anyhow::{bail, Context, Result};
use clap::Subcommand;
use log::{error, info, warn};
use std::fs;
use std::os::unix::process::CommandExt;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use rustix::event::{poll, PollFd, PollFlags};
use rustix::fd::OwnedFd;
use rustix::fs::{open, Mode, OFlags, Timespec};
use rustix::io::read;

use crate::defs;

#[derive(Subcommand, Debug)]
pub enum StealthCli {
    /// 启用：写配置并拉起守卫（管理器隐身前由管理器调用）
    Start {
        /// 管理器完整组件 <package>/<activity>
        #[arg(long)]
        component: String,
        /// 触发点击次数（须 >5，5 下属于原生开发者选项）
        #[arg(long, default_value_t = 10)]
        count: u32,
        /// 连点时间窗（毫秒）
        #[arg(long, default_value_t = 1600)]
        window_ms: u64,
        /// 允许触发的前台包列表（逗号分隔）
        #[arg(long, default_value = "com.android.settings,com.xiaomi.misettings")]
        focus_pkgs: String,
    },
    /// 停止守卫并清除配置
    Stop,
    /// 查看状态
    Status,
    /// 内部：守卫循环（由 start/boot 派生，勿手动运行）
    #[command(name = "watcher", hide = true)]
    Watcher,
}

struct StealthConfig {
    component: String,
    count: u32,
    window_ms: u64,
    focus_pkgs: Vec<String>,
}

fn conf_path() -> PathBuf {
    PathBuf::from(defs::WORKING_DIR).join("stealth.conf")
}

fn pid_path() -> PathBuf {
    PathBuf::from(defs::WORKING_DIR).join("stealth.pid")
}

fn read_config() -> Result<StealthConfig> {
    let text = fs::read_to_string(conf_path()).context("stealth.conf missing")?;
    let mut component = String::new();
    let mut count = 10u32;
    let mut window_ms = 1600u64;
    let mut focus_pkgs = vec!["com.android.settings".to_string()];
    for line in text.lines() {
        let line = line.trim();
        if let Some(v) = line.strip_prefix("component=") {
            component = v.trim().to_string();
        } else if let Some(v) = line.strip_prefix("count=") {
            count = v.trim().parse().unwrap_or(10);
        } else if let Some(v) = line.strip_prefix("window_ms=") {
            window_ms = v.trim().parse().unwrap_or(1600);
        } else if let Some(v) = line.strip_prefix("focus_pkgs=") {
            focus_pkgs = v
                .trim()
                .split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
        }
    }
    if component.is_empty() || !component.contains('/') {
        bail!("stealth.conf has no valid component=<pkg>/<activity>");
    }
    if focus_pkgs.is_empty() {
        focus_pkgs.push("com.android.settings".to_string());
    }
    Ok(StealthConfig {
        component,
        count,
        window_ms,
        focus_pkgs,
    })
}

fn write_config(component: &str, count: u32, window_ms: u64, focus_pkgs: &str) -> Result<()> {
    let text = format!(
        "component={component}\ncount={count}\nwindow_ms={window_ms}\nfocus_pkgs={focus_pkgs}\n"
    );
    fs::write(conf_path(), text).context("write stealth.conf")
}

fn watcher_alive() -> Option<u32> {
    let pid: u32 = fs::read_to_string(pid_path()).ok()?.trim().parse().ok()?;
    if fs::metadata(format!("/proc/{pid}")).is_ok() {
        Some(pid)
    } else {
        None
    }
}

fn kill_watcher() {
    if let Some(pid) = watcher_alive() {
        let _ = Command::new("kill").args(["-9", &pid.to_string()]).status();
    }
    let _ = fs::remove_file(pid_path());
}

fn spawn_watcher() -> Result<()> {
    let exe = std::env::current_exe()?;
    Command::new(exe)
        .args(["stealth", "watcher"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .process_group(0)
        .spawn()
        .context("spawn stealth watcher")?;
    Ok(())
}

pub fn handle(cmd: StealthCli) -> Result<()> {
    match cmd {
        StealthCmd::Start {
            component,
            count,
            window_ms,
            focus_pkgs,
        } => {
            // 必须 >5：5 下是原生开发者选项的解锁点，抢它会破坏原生行为。
            if count <= 5 {
                bail!("count must be > 5 (5 taps belongs to developer options)");
            }
            if !component.contains('/') {
                bail!("component must be <package>/<activity>");
            }
            fs::create_dir_all(defs::WORKING_DIR).ok();
            write_config(&component, count, window_ms, &focus_pkgs)?;
            kill_watcher();
            spawn_watcher()?;
            info!("stealth entrance armed: {component} on {count} taps");
            println!("stealth: armed ({component}, {count} taps, window {window_ms}ms)");
        }
        StealthCmd::Stop => {
            let _ = fs::remove_file(conf_path());
            kill_watcher();
            println!("stealth: stopped");
        }
        StealthCmd::Status => {
            let conf = read_config().ok();
            match (watcher_alive(), conf) {
                (Some(pid), Some(c)) => println!(
                    "stealth: running (pid {pid}) -> {} on {} taps [{}]",
                    c.component,
                    c.count,
                    c.focus_pkgs.join(",")
                ),
                (None, Some(c)) => println!("stealth: config present, watcher dead -> {}", c.component),
                _ => println!("stealth: disabled"),
            }
        }
        StealthCmd::Watcher => {
            if let Err(e) = run_watcher() {
                error!("stealth watcher: {e:#}");
            }
        }
    }
    Ok(())
}

/// 开机自启：配置在就拉守护（on_boot_completed 调用，失败不影响启动）。
pub fn on_boot() -> Result<()> {
    if conf_path().is_file() {
        info!("stealth: config present, starting watcher at boot");
        spawn_watcher()?;
    }
    Ok(())
}

const EV_KEY: u16 = 0x01;
const BTN_TOUCH: u16 = 0x14a;
const INPUT_EVENT_SIZE: usize = 24; // arm64: timeval(16) + type(2) + code(2) + value(4)
const POLL_TICK: Duration = Duration::from_millis(200);

fn run_watcher() -> Result<()> {
    let cfg = read_config().context("watcher: no config")?;
    fs::write(pid_path(), std::process::id().to_string()).ok();

    // 只读 + 非阻塞；权限被 SELinux 拦的设备跳过，全部被拦才报错退出。
    let mut entries: Vec<String> = fs::read_dir("/dev/input")
        .with_context(|| "cannot open /dev/input (SELinux? see manager adb fallback)")?
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|n| n.starts_with("event"))
        .collect();
    entries.sort();

    let mut devices: Vec<(OwnedFd, String)> = Vec::new();
    for name in &entries {
        let path = format!("/dev/input/{name}");
        match open(
            path.as_str(),
            OFlags::RDONLY | OFlags::NONBLOCK | OFlags::CLOEXEC,
            Mode::empty(),
        ) {
            Ok(fd) => devices.push((fd, path)),
            Err(e) => warn!("stealth watcher: skip {path}: {e}"),
        }
    }
    if devices.is_empty() {
        bail!("no readable /dev/input/event* devices");
    }
    info!(
        "stealth watcher: {} device(s), trigger = {} taps in {}ms while focused on [{}]",
        devices.len(),
        cfg.count,
        cfg.window_ms,
        cfg.focus_pkgs.join(",")
    );

    let window = Duration::from_millis(cfg.window_ms.max(300));
    let cooldown = Duration::from_secs(3);
    let mut count: u32 = 0;
    let mut last_tap: Option<Instant> = None;
    let mut last_trigger = Instant::now()
        .checked_sub(cooldown)
        .unwrap_or_else(Instant::now);
    let mut conf_seen = Instant::now();
    let mut buf = [0u8; INPUT_EVENT_SIZE * 32];
    let tick = Timespec::try_from(POLL_TICK)?;

    loop {
        // 每轮重建：PollFd 借用设备 fd（软重启模块同款用法）。
        let mut polls: Vec<PollFd<'_>> = devices
            .iter()
            .map(|(fd, _)| PollFd::new(fd, PollFlags::IN))
            .collect();
        match poll(&mut polls, Some(&tick)) {
            Ok(_) | Err(rustix::io::Errno::INTR) => {}
            Err(e) => {
                warn!("stealth watcher: poll failed: {e}");
                std::thread::sleep(POLL_TICK);
            }
        }

        // 配置被删即退（Stop 不依赖 kill 成败）。
        if conf_path().is_file() {
            conf_seen = Instant::now();
        } else if conf_seen.elapsed() > Duration::from_secs(5) {
            info!("stealth watcher: config removed, exiting");
            let _ = fs::remove_file(pid_path());
            return Ok(());
        }

        let now = Instant::now();
        for (idx, p) in polls.iter().enumerate() {
            let ready = match p.revents() {
                Some(ev) => ev.contains(PollFlags::IN),
                None => false,
            };
            if !ready {
                continue;
            }
            loop {
                let n = match read(&devices[idx].0, &mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                for ev in buf[..n].chunks_exact(INPUT_EVENT_SIZE) {
                    let typ = u16::from_ne_bytes([ev[16], ev[17]]);
                    let code = u16::from_ne_bytes([ev[18], ev[19]]);
                    let value = i32::from_ne_bytes([ev[20], ev[21], ev[22], ev[23]]);
                    if typ != EV_KEY || code != BTN_TOUCH || value != 1 {
                        continue;
                    }
                    if let Some(t) = last_tap {
                        if now.duration_since(t) > window {
                            count = 0;
                        }
                    }
                    count += 1;
                    last_tap = Some(now);
                    if count >= cfg.count && now.duration_since(last_trigger) > cooldown {
                        count = 0;
                        last_trigger = now;
                        if focus_matches(&cfg.focus_pkgs) {
                            launch_manager(&cfg.component);
                        } else {
                            // 计数达标但前台不在设置：静默重置，不打扰。
                            info!("stealth: taps reached but focus not in settings; reset");
                        }
                    }
                }
                if n < buf.len() {
                    break;
                }
            }
        }
    }
}

/// 计数达标时才做一次前台校验：mCurrentFocus（Android 10-15）+
/// topResumedActivity（新版本 window 服务拆分后的兜底）。
fn focus_matches(pkgs: &[String]) -> bool {
    let out = Command::new("sh")
        .arg("-c")
        .arg(
            "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus; \
             dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity",
        )
        .output();
    let text = match out {
        Ok(o) => format!(
            "{}\n{}",
            String::from_utf8_lossy(&o.stdout),
            String::from_utf8_lossy(&o.stderr)
        ),
        Err(e) => {
            warn!("stealth: dumpsys failed: {e}");
            return false;
        }
    };
    pkgs.iter().any(|p| text.contains(p.as_str()))
}

fn launch_manager(component: &str) {
    info!("stealth: launching {component}");
    match Command::new("am")
        .args(["start", "-n", component])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(_) => {}
        Err(e) => error!("stealth: am start failed: {e}"),
    }
}
