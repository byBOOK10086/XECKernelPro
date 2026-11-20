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
 *  - v4 计数语义（真机教训 v1-v3）：只数「点按质量」的触摸——按下到抬起
 *    无位移（排除滑动）、时长 <400ms（排除长按/拖拽）才算一下；窗口是
 *    固定滚动窗（默认 5s，窗口内满 N 下才触发），窗口外的历史计数一律
 *    丢弃。此前「每次按下都计数 + 链式续窗」会在浏览设置时被滑动/点按
 *    持续攒满，造成"点一下关于手机就跳转"。
 *  - 触发条件收紧到"前台焦点在系统设置包 + 固定窗内 N 下点按"。
 *  - 兜底永远存在：adb / root shell 一句 am start 就能拉起管理器，
 *    守卫挂了也只是少了入口，不会锁死。
 *
 * 实现要点：
 *  - /dev/input/event* 只读解析 input_event（24B/个，64 位 arm64 布局），
 *    通过 ioctl 筛选 BTN_TOUCH 触摸设备，按事件时间戳统计完整点按。
 *  - 前台校验仅在计数达标时跑一次 dumpsys（点击稀疏，代价可忽略）。
 *  - 配置 /data/adb/ksu/stealth.conf；启动写 pid 到 stealth.pid；
 *    守卫周期性自检配置被删即退出（Stop 免锁死）。
 */
use anyhow::{Context, Result, bail};
use clap::Subcommand;
use log::{error, info, warn};
use std::collections::VecDeque;
use std::fs;
use std::os::fd::AsRawFd;
use std::os::unix::process::CommandExt;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use rustix::event::{PollFd, PollFlags, poll};
use rustix::fd::OwnedFd;
use rustix::fs::{Mode, OFlags, Timespec, open};
use rustix::io::read;

use crate::defs;

#[derive(Subcommand, Debug)]
pub enum StealthCli {
    /// 启用：写配置并拉起守卫（管理器隐身前由管理器调用）
    Start {
        /// 管理器完整组件 <package>/<activity>
        #[arg(long)]
        component: String,
        /// Android user owning the manager installation
        #[arg(long, default_value_t = 0)]
        user: u32,
        /// 触发点击次数（须 >5，5 下属于原生开发者选项）
        #[arg(long, default_value_t = 10)]
        count: u32,
        /// 连点时间窗（毫秒）：窗内攒满 count 下才触发，窗外计数作废
        #[arg(long, default_value_t = 5000)]
        window_ms: u64,
        /// 允许触发的前台包列表（逗号分隔）
        #[arg(
            long,
            default_value = "com.android.settings,com.xiaomi.misettings,com.hihonor.settings,com.coloros.settings,com.oplus.settings,com.vivo.settings,com.bbk.settings,com.samsung.android.settings,com.oneplus.settings"
        )]
        focus_pkgs: String,
    },
    /// 停止守卫并清除配置
    Stop,
    /// 查看状态
    Status,
    /// 内部：守卫循环（由 start/boot 派生，勿手动运行）
    #[command(name = "watcher", hide = true)]
    Watcher,
    /// 内部：开机守护自检（配置在且守卫不在时重新拉起）
    #[command(name = "ensure", hide = true)]
    Ensure,
}

struct StealthConfig {
    component: String,
    user: u32,
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
    let mut user = 0u32;
    let mut count = 10u32;
    let mut window_ms = 5000u64;
    let mut focus_pkgs = vec!["com.android.settings".to_string()];
    for line in text.lines() {
        let line = line.trim();
        if let Some(v) = line.strip_prefix("component=") {
            component = v.trim().to_string();
        } else if let Some(v) = line.strip_prefix("user=") {
            user = v.parse().context("invalid Android user")?;
        } else if let Some(v) = line.strip_prefix("count=") {
            count = v.trim().parse().unwrap_or(10);
        } else if let Some(v) = line.strip_prefix("window_ms=") {
            window_ms = v.trim().parse().unwrap_or(1000);
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
    // 历史配置（v1-v3 写入的 1000/1600ms）配新语义太窄：固定窗内 10 下
    // 人类点不到，守卫会永远不触发。统一抬到 2s 下限（3s 默认）。
    if window_ms < 2000 {
        window_ms = 5000;
    }
    Ok(StealthConfig {
        component,
        user,
        count,
        window_ms,
        focus_pkgs,
    })
}

fn write_config(
    component: &str,
    user: u32,
    count: u32,
    window_ms: u64,
    focus_pkgs: &str,
) -> Result<()> {
    let text = format!(
        "component={component}\nuser={user}\ncount={count}\nwindow_ms={window_ms}\nfocus_pkgs={focus_pkgs}\n"
    );
    fs::write(conf_path(), text).context("write stealth.conf")
}

/// 扫描 /proc 找出所有 stealth watcher 进程（exe 为 ksud/xudc 且 argv 是
/// "stealth watcher"）。比只信 pid 文件可靠：升级核心后旧进程仍在跑、
/// pid 复用、多次 start 叠加，都会产生多份守卫各自计数。
/// exe 有两个合法名：仓库产物叫 ksud，发到设备上的二进制叫 xudc
///（同名坑：只认一个就会把活着的守卫误报成 dead）。
fn watcher_pids() -> Vec<u32> {
    let mut pids = Vec::new();
    let Ok(rd) = fs::read_dir("/proc") else {
        return pids;
    };
    for e in rd.filter_map(std::result::Result::ok) {
        let name = e.file_name().to_string_lossy().to_string();
        if !name.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let Ok(cmd) = fs::read_to_string(format!("/proc/{name}/cmdline")) else {
            continue;
        };
        let args: Vec<&str> = cmd.split('\0').filter(|s| !s.is_empty()).collect();
        if args.len() >= 3
            && is_watcher_executable(args[0])
            && args[1] == "stealth"
            && args[2] == "watcher"
        {
            pids.push(name.parse().unwrap_or(0));
        }
    }
    pids
}

fn watcher_alive() -> Option<u32> {
    watcher_pids().into_iter().next()
}

fn kill_watcher() {
    for pid in watcher_pids() {
        let _ = Command::new("kill").args(["-9", &pid.to_string()]).status();
    }
    for _ in 0..50 {
        if watcher_pids().is_empty() {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let _ = fs::remove_file(pid_path());
    let _ = fs::remove_file(ready_path());
}

fn is_watcher_executable(path: &str) -> bool {
    matches!(
        std::path::Path::new(path)
            .file_name()
            .and_then(|n| n.to_str()),
        Some("ksud" | "xudc" | "libxeccli.so")
    )
}

fn state_lock(name: &str) -> Result<fs::File> {
    fs::create_dir_all(defs::WORKING_DIR)?;
    let file = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(PathBuf::from(defs::WORKING_DIR).join(name))?;
    anyhow::ensure!(
        unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "stealth operation already running"
    );
    Ok(file)
}

fn ready_path() -> PathBuf {
    PathBuf::from(defs::WORKING_DIR).join("stealth.ready")
}

fn spawn_watcher() -> Result<()> {
    let _ = fs::remove_file(ready_path());
    let exe = std::env::current_exe()?;
    let mut child = Command::new(exe)
        .args(["stealth", "watcher"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .process_group(0)
        .spawn()
        .context("spawn stealth watcher")?;
    let expected = child.id().to_string();
    for _ in 0..160 {
        if child.try_wait()?.is_some() {
            bail!("touch watcher exited before readiness");
        }
        if fs::read_to_string(ready_path()).is_ok_and(|pid| pid == expected) {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = child.kill();
    let _ = child.wait();
    bail!("touch watcher readiness timed out; launcher must stay visible")
}

// EVIOCGBIT(EV_KEY): only accept an input node whose driver advertises BTN_TOUCH.
// Drivers without this protocol are rejected before hiding instead of silently locking out users.
fn supports_touch(fd: &OwnedFd) -> bool {
    let mut bits = [0u8; 96];
    let request = (2u32 << 30) | (96 << 16) | (u32::from(b'E') << 8) | 0x21;
    let result = unsafe { libc::ioctl(fd.as_raw_fd(), request as _, bits.as_mut_ptr()) };
    if result < 0 || bits[usize::from(BTN_TOUCH) / 8] & (1 << (BTN_TOUCH % 8)) == 0 {
        return false;
    }
    // EVIOCSCLOCKID: all devices must use the same clock, unaffected by wall-clock updates.
    let clock = libc::CLOCK_MONOTONIC;
    unsafe { libc::ioctl(fd.as_raw_fd(), 0x4004_45a0, std::ptr::addr_of!(clock)) == 0 }
}

pub fn handle(cmd: StealthCli) -> Result<()> {
    let _control = if matches!(cmd, StealthCli::Watcher) {
        None
    } else {
        Some(state_lock("stealth-control.lock")?)
    };
    match cmd {
        StealthCli::Start {
            component,
            user,
            count,
            window_ms,
            focus_pkgs,
        } => {
            // 必须 >5：5 下是原生开发者选项的解锁点，抢它会破坏原生行为。
            if count <= 5 {
                bail!("count must be > 5 (5 taps belongs to developer options)");
            }
            if !component.contains('/')
                || !component
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._/$".contains(&b))
            {
                bail!("component must be <package>/<activity>");
            }
            fs::create_dir_all(defs::WORKING_DIR).ok();
            kill_watcher();
            write_config(&component, user, count, window_ms, &focus_pkgs)?;
            if let Err(error) = spawn_watcher() {
                let _ = fs::remove_file(conf_path());
                return Err(error);
            }
            info!("stealth entrance armed: {component} on {count} taps");
            println!("stealth: ready v2");
        }
        StealthCli::Stop => {
            let _ = fs::remove_file(conf_path());
            kill_watcher();
            println!("stealth: stopped");
        }
        StealthCli::Status => {
            let conf = read_config().ok();
            match (watcher_alive(), conf) {
                (Some(pid), Some(c)) => println!(
                    "stealth: running (pid {pid}) -> {} on {} taps [{}]",
                    c.component,
                    c.count,
                    c.focus_pkgs.join(",")
                ),
                (None, Some(c)) => {
                    println!("stealth: config present, watcher dead -> {}", c.component);
                }
                _ => println!("stealth: disabled"),
            }
        }
        StealthCli::Watcher => {
            let _singleton = state_lock("stealth-watcher.lock")?;
            if let Err(e) = run_watcher() {
                error!("stealth watcher: {e:#}");
                if let Ok(cfg) = read_config() {
                    restore_launcher(&cfg);
                }
                return Err(e);
            }
        }
        StealthCli::Ensure => {
            // 开机守护用：配置在但守卫不在就重新拉起（绝不覆盖已有实例，
            // 否则双实例会各自计数、等于阈值减半）。
            if conf_path().is_file() && watcher_pids().is_empty() {
                info!("stealth: ensure respawns missing watcher");
                spawn_watcher()?;
            }
        }
    }
    Ok(())
}

/// A reboot ends the hidden session and restores the launcher.
pub fn on_boot() -> Result<()> {
    // A reboot ends the hidden session. The app BOOT_COMPLETED receiver also restores
    // the icon without root; this path covers installations with a working daemon.
    let _control = state_lock("stealth-control.lock")?;
    if let Ok(cfg) = read_config() {
        restore_launcher(&cfg);
    }
    let _ = fs::remove_file(conf_path());
    kill_watcher();
    Ok(())
}

fn restore_launcher(cfg: &StealthConfig) {
    let Some((package, _)) = cfg.component.split_once('/') else {
        return;
    };
    let component = format!("{package}/com.xecpro.kernel.XecMainActivity");
    match Command::new("pm")
        .args(["enable", "--user", &cfg.user.to_string(), &component])
        .status()
    {
        Ok(status) if status.success() => {}
        result => warn!("stealth: launcher recovery failed: {result:?}"),
    }
}

const EV_KEY: u16 = 0x01;
const EV_ABS: u16 = 0x03;
const BTN_TOUCH: u16 = 0x14a;
const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_MT_POSITION_X: u16 = 0x35;
const ABS_MT_POSITION_Y: u16 = 0x36;
const INPUT_EVENT_SIZE: usize = 24; // arm64: timeval(16) + type(2) + code(2) + value(4)
const POLL_TICK: Duration = Duration::from_millis(200);
/// 同一物理点按的最小间隔：滤掉副输入节点重复上报与压力抖动（第一版教训）。
const MIN_TAP_INTERVAL: Duration = Duration::from_millis(60);
/// 按下期间的位移阈值（原始坐标单位）：超过即视为滑动而非点按（v4 教训：
/// 把滑动计入连点会让浏览设置本身攒满计数，导致"点一下就跳转"）。
const MOVE_THRESHOLD: i32 = 96;
/// 按下时长上限：超过视为长按/拖拽，不算点按。
const TAP_MAX_DOWN: Duration = Duration::from_millis(400);

/// 单个输入节点的触摸轨迹态：用于区分「点按」与「滑动/长按」（v4 语义）。
struct DownState {
    at: Duration,
    x: Option<i32>,
    y: Option<i32>,
    moved: bool,
}

struct TouchTrack {
    cur_x: Option<i32>,
    cur_y: Option<i32>,
    down: Option<DownState>,
}

impl TouchTrack {
    /// 坐标事件：更新当前坐标；按下期间做锚点与位移判定。
    /// 锚点策略（关键）：不快照按下沿之前的坐标——那可能是上一次触摸的
    /// 残值（部分驱动先报按键后报坐标）。按下后到达的首个 X/Y 各自充当
    /// 锚点：静止点按此后不再有坐标事件（=未移动），滑动必有后续位移。
    #[allow(clippy::missing_const_for_fn)]
    fn on_pos(&mut self, is_x: bool, v: i32) {
        if is_x {
            self.cur_x = Some(v);
        } else {
            self.cur_y = Some(v);
        }
        let Some(d) = self.down.as_mut() else {
            return;
        };
        match (is_x, d.x, d.y) {
            (true, None, _) => d.x = Some(v),
            (false, _, None) => d.y = Some(v),
            (true, Some(x0), _) if x0.abs_diff(v) > MOVE_THRESHOLD as u32 => d.moved = true,
            (false, _, Some(y0)) if y0.abs_diff(v) > MOVE_THRESHOLD as u32 => d.moved = true,
            _ => {}
        }
    }

    /// BTN_TOUCH 边沿：抬起时判定是否为有效点按，返回其时间戳。
    fn on_btn(&mut self, value: i32, now: Duration) -> Option<Duration> {
        if value == 1 {
            if self.down.is_none() {
                self.down = Some(DownState {
                    at: now,
                    x: None,
                    y: None,
                    moved: false,
                });
            }
            None
        } else if value == 0 {
            let d = self.down.take()?;
            if d.moved
                || now
                    .checked_sub(d.at)
                    .is_none_or(|elapsed| elapsed >= TAP_MAX_DOWN)
            {
                return None;
            }
            Some(now)
        } else {
            None
        }
    }
}

// Kernel event time preserves spacing when several taps arrive in a single read.
fn event_time(ev: &[u8; INPUT_EVENT_SIZE]) -> Option<Duration> {
    let seconds = i64::from_ne_bytes(ev[..8].try_into().ok()?);
    let micros = i64::from_ne_bytes(ev[8..16].try_into().ok()?);
    if seconds < 0 || !(0..1_000_000).contains(&micros) {
        return None;
    }
    Some(Duration::new(seconds as u64, micros as u32 * 1000))
}

fn run_watcher() -> Result<()> {
    let cfg = read_config().context("watcher: no config")?;
    fs::write(pid_path(), std::process::id().to_string()).ok();

    // 只读 + 非阻塞；权限被 SELinux 拦的设备跳过。boot-completed 阶段可能
    // 早于 /dev/input 就绪，这里最多等 10s（5 × 2s）再认输，否则开机第一次
    // 拉起会因「暂时没有可读 node」直接退出（真机复验踩过）。
    let mut devices: Vec<(OwnedFd, String)> = Vec::new();
    for attempt in 0..6 {
        devices.clear();
        let mut entries: Vec<String> = match fs::read_dir("/dev/input") {
            Ok(rd) => rd
                .filter_map(std::result::Result::ok)
                .map(|e| e.file_name().to_string_lossy().to_string())
                .filter(|n| n.starts_with("event"))
                .collect(),
            Err(e) => {
                warn!("stealth watcher: cannot open /dev/input: {e}");
                Vec::new()
            }
        };
        entries.sort();

        for name in &entries {
            let path = format!("/dev/input/{name}");
            match open(
                path.as_str(),
                OFlags::RDONLY | OFlags::NONBLOCK | OFlags::CLOEXEC,
                Mode::empty(),
            ) {
                Ok(fd) if supports_touch(&fd) => devices.push((fd, path)),
                Ok(_) => {}
                Err(e) => warn!("stealth watcher: skip {path}: {e}"),
            }
        }
        if !devices.is_empty() {
            break;
        }
        if attempt == 5 {
            bail!("no supported readable touchscreen after 10s");
        }
        warn!(
            "stealth watcher: no readable input devices yet (attempt {}/6), retrying",
            attempt + 1
        );
        std::thread::sleep(Duration::from_secs(2));
    }
    info!(
        "stealth watcher: {} device(s), trigger = {} taps in {}ms while focused on [{}]",
        devices.len(),
        cfg.count,
        cfg.window_ms,
        cfg.focus_pkgs.join(",")
    );

    fs::write(ready_path(), std::process::id().to_string())?;

    // v4：固定滚动窗内的有效点按时间戳。窗口是硬边界——新点按到来时把
    // 窗口外的时间戳整批作废，绝不链式续窗（v3 的教训）。
    let window = Duration::from_millis(cfg.window_ms.max(2000));
    let cooldown = Duration::from_secs(3);
    let mut tap_times: VecDeque<Duration> = VecDeque::new();
    let mut last_tap: Option<Duration> = None;
    let mut last_trigger: Option<Duration> = None;
    let mut tracks: Vec<TouchTrack> = devices
        .iter()
        .map(|_| TouchTrack {
            cur_x: None,
            cur_y: None,
            down: None,
        })
        .collect();
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
                bail!("touchscreen polling failed: {e}");
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

        for (idx, p) in polls.iter().enumerate() {
            if p.revents()
                .intersects(PollFlags::ERR | PollFlags::HUP | PollFlags::NVAL)
            {
                bail!("touchscreen disconnected; restoring launcher");
            }
            // revents() 在本 rustix 版本直接返回 PollFlags（非 Option）。
            if !p.revents().contains(PollFlags::IN) {
                continue;
            }
            loop {
                let n = match read(&devices[idx].0, &mut buf) {
                    Ok(0) => bail!("touchscreen closed"),
                    Err(rustix::io::Errno::AGAIN | rustix::io::Errno::INTR) => break,
                    Err(e) => bail!("touchscreen read failed: {e}"),
                    Ok(n) => n,
                };
                for ev in buf[..n].as_chunks::<INPUT_EVENT_SIZE>().0 {
                    let typ = u16::from_ne_bytes([ev[16], ev[17]]);
                    let code = u16::from_ne_bytes([ev[18], ev[19]]);
                    let value = i32::from_ne_bytes([ev[20], ev[21], ev[22], ev[23]]);
                    let Some(now) = event_time(ev) else {
                        continue;
                    };
                    match typ {
                        EV_ABS => match code {
                            ABS_X | ABS_MT_POSITION_X => tracks[idx].on_pos(true, value),
                            ABS_Y | ABS_MT_POSITION_Y => tracks[idx].on_pos(false, value),
                            _ => {}
                        },
                        EV_KEY if code == BTN_TOUCH => {
                            // 抬起沿才判点按质量（无位移且时长合格）。
                            let Some(tap_at) = tracks[idx].on_btn(value, now) else {
                                continue;
                            };
                            // 全局最小点按间隔：同一物理点按被副输入节点重复
                            // 上报时，60ms 内只认第一下。
                            if last_tap.is_some_and(|t| tap_at.saturating_sub(t) < MIN_TAP_INTERVAL)
                            {
                                continue;
                            }
                            last_tap = Some(tap_at);
                            // 固定滚动窗：窗口之外的历史计数一律作废。
                            while let Some(&front) = tap_times.front() {
                                if tap_at.saturating_sub(front) > window {
                                    tap_times.pop_front();
                                } else {
                                    break;
                                }
                            }
                            tap_times.push_back(tap_at);
                            if tap_times.len() as u32 >= cfg.count
                                && last_trigger
                                    .is_none_or(|last| tap_at.saturating_sub(last) > cooldown)
                            {
                                last_trigger = Some(tap_at);
                                tap_times.clear();
                                if focus_matches(&cfg.focus_pkgs) {
                                    if let Err(error) = launch_manager(&cfg) {
                                        restore_launcher(&cfg);
                                        return Err(error);
                                    }
                                } else {
                                    // 计数达标但前台不在设置：静默重置，不打扰。
                                    info!("stealth: taps reached but focus not in settings; reset");
                                }
                            }
                        }
                        _ => {}
                    }
                }
                if n < buf.len() {
                    break;
                }
            }
        }
    }
}

/// 系统设置包的运行时发现结果（只探测一次）。
static SETTINGS_PKGS: std::sync::OnceLock<Vec<String>> = std::sync::OnceLock::new();

/// 发现本机「系统设置」的包名。
///
/// 内置默认只写了 com.android.settings / com.xiaomi.misettings，而荣耀、OPPO、
/// vivo、三星等 ROM 的设置包是 com.hihonor.settings / com.coloros.settings /
/// com.vivo.settings / com.samsung.android.settings —— 白名单对不上，计数攒满后
/// 前台校验必然失败并静默重置，用户看到的就是「点 OS 版本没用」。
fn detect_settings_packages() -> &'static Vec<String> {
    SETTINGS_PKGS.get_or_init(|| {
        let mut found: Vec<String> = Vec::new();

        // 1) 系统设置入口的解析结果（最权威，随 ROM 走）。
        if let Ok(out) = Command::new("sh")
            .arg("-c")
            .arg(
                "cmd package resolve-activity --brief \
                   -a android.settings.SETTINGS 2>/dev/null \
                 | tail -n 1",
            )
            .output()
        {
            let text = String::from_utf8_lossy(&out.stdout);
            if let Some(pkg) = text.trim().split('/').next() {
                let pkg = pkg.trim();
                if !pkg.is_empty() && pkg.contains('.') {
                    found.push(pkg.to_string());
                }
            }
        }

        // 2) 名字里带 settings 的系统包（覆盖 OEM 自有命名）。
        if let Ok(out) = Command::new("sh")
            .arg("-c")
            .arg("pm list packages -s 2>/dev/null | grep -i settings | head -n 8")
            .output()
        {
            for line in String::from_utf8_lossy(&out.stdout).lines() {
                if let Some(pkg) = line.trim().strip_prefix("package:") {
                    let pkg = pkg.trim();
                    if !pkg.is_empty() && !found.iter().any(|f| f == pkg) {
                        found.push(pkg.to_string());
                    }
                }
            }
        }

        if found.is_empty() {
            warn!("stealth: could not resolve the settings package, relying on name match");
        } else {
            info!("stealth: resolved settings packages: {}", found.join(", "));
        }
        found
    })
}

/// 从 dumpsys 文本里取出前台组件的 "pkg/activity" 片段。
///
/// 只做子串匹配会漏：新版本 window 服务的 dump 里已经没有 mCurrentFocus，
/// 而 topResumedActivity 的格式在各 ROM 上也不统一。
fn focused_components(text: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for line in text.lines() {
        if !(line.contains("mCurrentFocus")
            || line.contains("mFocusedWindow")
            || line.contains("topResumedActivity"))
        {
            continue;
        }
        // "u0 com.android.settings/com.android.settings.Settings}"
        for token in line.split_whitespace() {
            if let Some((pkg, activity)) = token.split_once('/') {
                let pkg = pkg.trim_start_matches("u0").trim();
                if pkg.contains('.') && activity.chars().next().is_some_and(|c| c != '{') {
                    out.push((pkg.to_string(), activity.trim_end_matches('}').to_string()));
                }
            }
        }
    }
    out
}

/// 计数达标时才做一次前台校验。
fn focus_matches(pkgs: &[String]) -> bool {
    let out = Command::new("sh")
        .arg("-c")
        .arg(
            "dumpsys window 2>/dev/null | grep -E -m1 'mCurrentFocus|mFocusedWindow'; \
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

    let detected = detect_settings_packages();
    let is_settings_pkg =
        |pkg: &str| -> bool { pkgs.iter().any(|p| p == pkg) || detected.iter().any(|p| p == pkg) };

    let components = focused_components(&text);
    if components.is_empty() {
        // 解析不出组件时退回宽松匹配：既认配置/探测到的包，也认任何
        // 包名或 Activity 名里带 settings 的前台窗口。
        return pkgs.iter().any(|p| text.contains(p.as_str()))
            || text.to_ascii_lowercase().contains("settings");
    }

    components.iter().any(|(pkg, activity)| {
        is_settings_pkg(pkg)
            || pkg.to_ascii_lowercase().contains("settings")
            || activity.to_ascii_lowercase().contains("settings")
    })
}

fn launch_manager(cfg: &StealthConfig) -> Result<()> {
    let output = Command::new("am")
        .args([
            "start",
            "--user",
            &cfg.user.to_string(),
            "-n",
            &cfg.component,
        ])
        .stdin(Stdio::null())
        .output()
        .context("launch recovery activity")?;
    let message = format!(
        "{} {}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    anyhow::ensure!(
        output.status.success() && !message.contains("Error:") && !message.contains("Exception"),
        "recovery activity launch failed: {message}"
    );
    Ok(())
}
