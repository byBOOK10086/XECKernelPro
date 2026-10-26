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
 *    固定滚动窗（默认 3s，窗口内满 N 下才触发），窗口外的历史计数一律
 *    丢弃。此前「每次按下都计数 + 链式续窗」会在浏览设置时被滑动/点按
 *    持续攒满，造成"点一下关于手机就跳转"。
 *  - 触发条件收紧到"前台焦点在系统设置包 + 固定窗内 N 下点按"。
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
use anyhow::{Context, Result, bail};
use clap::Subcommand;
use log::{error, info, warn};
use std::collections::VecDeque;
use std::fs;
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
        /// 触发点击次数（须 >5，5 下属于原生开发者选项）
        #[arg(long, default_value_t = 10)]
        count: u32,
        /// 连点时间窗（毫秒）：窗内攒满 count 下才触发，窗外计数作废
        #[arg(long, default_value_t = 3000)]
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
    /// 内部：开机守护自检（配置在且守卫不在时重新拉起）
    #[command(name = "ensure", hide = true)]
    Ensure,
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
    let mut window_ms = 3000u64;
    let mut focus_pkgs = vec!["com.android.settings".to_string()];
    for line in text.lines() {
        let line = line.trim();
        if let Some(v) = line.strip_prefix("component=") {
            component = v.trim().to_string();
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
        window_ms = 3000;
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
            && (args[0].ends_with("ksud") || args[0].ends_with("xudc"))
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
        StealthCli::Start {
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
            if let Err(e) = run_watcher() {
                error!("stealth watcher: {e:#}");
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

/// 开机自启：配置在就拉守护（on_boot_completed 调用，失败不影响启动）。
pub fn on_boot() -> Result<()> {
    if conf_path().is_file() {
        info!("stealth: config present, starting watcher at boot");
        // 先清残留（核心升级后旧进程可能还活着，双实例会各自计数）。
        kill_watcher();
        spawn_watcher()?;
        // boot-completed 阶段进程环境并不稳定（/dev/input 未就绪、init 清理
        // 子进程、LMK 都可能让第一只守卫秒退）。留一个 60s 的 detached
        // supervisor：每 5s 调一次 `stealth ensure`，配置在就补拉；不会与
        // 已存活的守卫重复起实例。
        spawn_boot_supervisor();
    }
    Ok(())
}

fn spawn_boot_supervisor() {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            warn!("stealth: cannot resolve current_exe for supervisor: {e}");
            return;
        }
    };
    let loop_cmd = format!(
        "n=0; while [ $n -lt 12 ]; do sleep 5; '{}' stealth ensure >/dev/null 2>&1; n=$((n+1)); done",
        exe.display()
    );
    match Command::new("sh")
        .arg("-c")
        .arg(loop_cmd)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .process_group(0)
        .spawn()
    {
        Ok(_) => info!("stealth: boot supervisor armed (60s)"),
        Err(e) => warn!("stealth: boot supervisor spawn failed: {e}"),
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
const MIN_TAP_INTERVAL: Duration = Duration::from_millis(120);
/// 按下期间的位移阈值（原始坐标单位）：超过即视为滑动而非点按（v4 教训：
/// 把滑动计入连点会让浏览设置本身攒满计数，导致"点一下就跳转"）。
const MOVE_THRESHOLD: i32 = 96;
/// 按下时长上限：超过视为长按/拖拽，不算点按。
const TAP_MAX_DOWN: Duration = Duration::from_millis(400);

/// 单个输入节点的触摸轨迹态：用于区分「点按」与「滑动/长按」（v4 语义）。
struct DownState {
    at: Instant,
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
            (true, Some(x0), _) if (x0 - v).abs() > MOVE_THRESHOLD => d.moved = true,
            (false, _, Some(y0)) if (y0 - v).abs() > MOVE_THRESHOLD => d.moved = true,
            _ => {}
        }
    }

    /// BTN_TOUCH 边沿：抬起时判定是否为有效点按，返回其时间戳。
    fn on_btn(&mut self, value: i32, now: Instant) -> Option<Instant> {
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
            if d.moved || now.duration_since(d.at) >= TAP_MAX_DOWN {
                return None;
            }
            Some(now)
        } else {
            None
        }
    }
}

fn run_watcher() -> Result<()> {
    let cfg = read_config().context("watcher: no config")?;
    fs::write(pid_path(), std::process::id().to_string()).ok();

    // 只读 + 非阻塞；权限被 SELinux 拦的设备跳过。boot-completed 阶段可能
    // 早于 /dev/input 就绪，这里最多等 30s（15 × 2s）再认输，否则开机第一次
    // 拉起会因「暂时没有可读 node」直接退出（真机复验踩过）。
    let mut devices: Vec<(OwnedFd, String)> = Vec::new();
    for attempt in 0..15 {
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
                Ok(fd) => devices.push((fd, path)),
                Err(e) => warn!("stealth watcher: skip {path}: {e}"),
            }
        }
        if !devices.is_empty() {
            break;
        }
        if attempt == 14 {
            bail!("no readable /dev/input/event* devices after 30s");
        }
        warn!(
            "stealth watcher: no readable input devices yet (attempt {}/15), retrying",
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

    // v4：固定滚动窗内的有效点按时间戳。窗口是硬边界——新点按到来时把
    // 窗口外的时间戳整批作废，绝不链式续窗（v3 的教训）。
    let window = Duration::from_millis(cfg.window_ms.max(2000));
    let cooldown = Duration::from_secs(3);
    let mut tap_times: VecDeque<Instant> = VecDeque::new();
    let mut last_tap: Option<Instant> = None;
    let mut last_trigger = Instant::now()
        .checked_sub(cooldown)
        .unwrap_or_else(Instant::now);
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

        for (idx, p) in polls.iter().enumerate() {
            // revents() 在本 rustix 版本直接返回 PollFlags（非 Option）。
            if !p.revents().contains(PollFlags::IN) {
                continue;
            }
            loop {
                let n = match read(&devices[idx].0, &mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                for ev in buf[..n].as_chunks::<INPUT_EVENT_SIZE>().0 {
                    let typ = u16::from_ne_bytes([ev[16], ev[17]]);
                    let code = u16::from_ne_bytes([ev[18], ev[19]]);
                    let value = i32::from_ne_bytes([ev[20], ev[21], ev[22], ev[23]]);
                    let now = Instant::now();
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
                            // 上报时，120ms 内只认第一下。
                            if last_tap.is_some_and(|t| tap_at.duration_since(t) < MIN_TAP_INTERVAL) {
                                continue;
                            }
                            last_tap = Some(tap_at);
                            // 固定滚动窗：窗口之外的历史计数一律作废。
                            while let Some(&front) = tap_times.front() {
                                if tap_at.duration_since(front) > window {
                                    tap_times.pop_front();
                                } else {
                                    break;
                                }
                            }
                            tap_times.push_back(tap_at);
                            if tap_times.len() as u32 >= cfg.count
                                && tap_at.duration_since(last_trigger) > cooldown
                            {
                                last_trigger = tap_at;
                                tap_times.clear();
                                if focus_matches(&cfg.focus_pkgs) {
                                    launch_manager(&cfg.component);
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
