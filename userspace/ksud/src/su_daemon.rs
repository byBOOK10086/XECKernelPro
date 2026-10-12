// SPDX-License-Identifier: GPL-3.0-only

//! 挂载态 root（<5.10）的用户态 daemon：`su` 通道。
//!
//! 由 init 以 root 拉起（注入的 `overlay.d/*.rc` 里写
//! `service xecpro_daemon /data/adb/xecpro/xudc daemon`），监听 UNIX socket。
//! `xecsu` 垫片把 argv + cwd + 自己的 stdio 三个 fd 送过来，这里查允许列表、
//! fork/exec 出 root shell、把退出码回传。
//!
//! **授权只看 `SO_PEERCRED` 给出的对端 uid + `/data/adb/xecpro/allow.txt`**：
//! 客户端传什么都不作数；不在列表里一律 `-EPERM` 并记日志（不做"先放行再问"，
//! 也不做定时弹窗 —— 授权动作由管理器显式调用 `su-grant`）。
//!
//! 允许列表格式：一行一个 uid，`#` 起头是注释，空行忽略。

use std::ffi::CString;
use std::fs;
use std::io::Write;
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;

use anyhow::{Context, Result, bail};
use log::{info, warn};

use crate::defs;

/// 协议魔数（必须与 `userspace/xecsu` 里的一致）。
const MAGIC: u32 = 0x5845_4353; // "XECS"
const DENIED: i32 = -1;
/// 一次请求最多接受几个参数/多长，防止对端拿畸形报文把 daemon 拖死。
const MAX_ARGS: usize = 64;
const MAX_ARG_LEN: usize = 4096;
const MAX_CWD_LEN: usize = 1024;

fn allow_path() -> String {
    format!("{}/allow.txt", defs::XECPRO_DIR)
}

fn socket_path() -> String {
    format!("{}/su.sock", defs::XECPRO_DIR)
}

/// 确保工作目录与允许列表存在（权限 0700：里面是 root 名单，不给普通应用读）。
fn ensure_dirs() -> Result<()> {
    let dir = Path::new(defs::XECPRO_DIR);
    if !dir.is_dir() {
        fs::create_dir_all(dir).with_context(|| format!("创建 {}", dir.display()))?;
    }
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(dir, fs::Permissions::from_mode(0o700))
        .with_context(|| format!("chmod {}", dir.display()))?;
    let allow_path_value = allow_path();
    let allow = Path::new(&allow_path_value);
    if !allow.is_file() {
        fs::write(
            allow,
            "# xecpro 挂载态 root 的允许列表：一行一个 uid\n".as_bytes(),
        )
        .with_context(|| format!("初始化 {}", allow.display()))?;
        fs::set_permissions(allow, fs::Permissions::from_mode(0o600)).ok();
    }
    Ok(())
}

/// 允许列表（读失败按"空名单"处理：不给任何 uid 放行，安全侧默认拒绝）。
fn read_allow() -> Vec<u32> {
    let Ok(text) = fs::read_to_string(allow_path()) else {
        return Vec::new();
    };
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter_map(|line| line.parse::<u32>().ok())
        .collect()
}

fn is_allowed(uid: u32) -> bool {
    read_allow().contains(&uid)
}

pub fn su_list() -> Result<()> {
    let uids = read_allow();
    println!("已授权 root 的 uid（{} 个）：", uids.len());
    for uid in uids {
        println!("  {uid}");
    }
    println!("（列表文件：{}）", allow_path());
    Ok(())
}

pub fn su_grant(uid: u32) -> Result<()> {
    ensure_dirs()?;
    let mut uids = read_allow();
    if uids.contains(&uid) {
        println!("uid {uid} 已经在列表里了");
        return Ok(());
    }
    uids.push(uid);
    write_allow(&uids)?;
    println!("已授权 uid {uid}（共 {} 个）", uids.len());
    Ok(())
}

pub fn su_revoke(uid: u32) -> Result<()> {
    ensure_dirs()?;
    let mut uids = read_allow();
    let before = uids.len();
    uids.retain(|item| *item != uid);
    if uids.len() == before {
        println!("uid {uid} 本来就不在列表里");
        return Ok(());
    }
    write_allow(&uids)?;
    println!("已撤销 uid {uid}（剩 {} 个）", uids.len());
    Ok(())
}

fn write_allow(uids: &[u32]) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut text = String::from("# xecpro 挂载态 root 的允许列表：一行一个 uid\n");
    for uid in uids {
        text.push_str(&format!("{uid}\n"));
    }
    let path = allow_path();
    fs::write(&path, text).with_context(|| format!("写 {path}"))?;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).ok();
    Ok(())
}

/// 安装挂载态 root 的用户态部分（管理器调用一次即可，之后重启生效）：
///
/// * `/data/adb/xecpro/xudc` —— 当前这份 xudc 的拷贝，init 会用它拉起 daemon；
/// * `/data/adb/xecpro/bin/xecsu` —— su 垫片（从内置载荷解出来）；
/// * `/data/adb/modules/xecpro-su/` —— 一个最小模块，靠 magic mount 把
///   `system/bin/su` 挂成我们的垫片（这样 /system 只读也能用上 su）。
///
/// # Errors
///
/// 目录/文件写不进去（没有 root 或 /data 不可写）时返回错误。
pub fn install() -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    ensure_dirs()?;

    let exe = std::env::current_exe().context("拿不到当前可执行文件路径")?;
    let daemon_path = format!("{}/xudc", defs::XECPRO_DIR);
    fs::copy(&exe, &daemon_path).with_context(|| format!("拷贝到 {daemon_path}"))?;
    fs::set_permissions(&daemon_path, fs::Permissions::from_mode(0o755)).ok();
    println!("- daemon: {daemon_path}");

    let bin_dir = format!("{}/bin", defs::XECPRO_DIR);
    fs::create_dir_all(&bin_dir).with_context(|| format!("创建 {bin_dir}"))?;
    let su_path = format!("{bin_dir}/su");
    let su_payload = crate::assets::get_asset("xecsu")
        .context("内置载荷里没有 xecsu —— 这次构建没带上 su 垫片")?;
    fs::write(&su_path, su_payload.as_ref().as_ref()).with_context(|| format!("写 {su_path}"))?;
    fs::set_permissions(&su_path, fs::Permissions::from_mode(0o755)).ok();
    println!("- su 垫片: {su_path}");

    // 最小模块：magic mount 会把 system/bin/su 挂到 /system/bin/su
    let module_dir = format!("{}/xecpro-su", defs::MODULE_DIR);
    let module_bin = format!("{module_dir}/system/bin");
    fs::create_dir_all(&module_bin).with_context(|| format!("创建 {module_bin}"))?;
    let prop = format!(
        "id=xecpro-su\nname=XECPro su 垫片\nversion=1.0\nversionCode=1\n\
         author=XECKernelPro\ndescription=挂载态 root（<5.10）的 su 通道：把 /system/bin/su 指向 xecpro 的垫片。\n"
    );
    fs::write(format!("{module_dir}/module.prop"), prop)
        .with_context(|| format!("写 {module_dir}/module.prop"))?;
    let link = format!("{module_bin}/su");
    let _ = fs::remove_file(&link);
    std::os::unix::fs::symlink(format!("{bin_dir}/su"), &link)
        .with_context(|| format!("建软链 {link}"))?;
    println!("- 模块: {module_dir}（/system/bin/su -> {bin_dir}/su）");

    println!("\n下一步：");
    println!("  1) 注入 ramdisk：xudc ramdisk-patch --flash（或 --boot/--output 出镜像手动刷）");
    println!("  2) 重启；然后 xudc su-grant <uid> 授权，应用里的 su 就能用了");
    Ok(())
}

/// daemon 主循环。`once=true` 时只处理一个连接（自检用）。
pub fn run_daemon(once: bool) -> Result<()> {
    ensure_dirs()?;
    let path = socket_path();
    let _ = fs::remove_file(&path);
    let listener = UnixListener::bind(&path).with_context(|| format!("bind {path}"))?;
    // socket 本身不设访问限制：授权判定靠 SO_PEERCRED + 允许列表（只看 socket 权限
    // 会把普通应用挡在门外，反而做不了"按 uid 授权"）。
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(&path, fs::Permissions::from_mode(0o666)).ok();
    info!("xecpro daemon: 监听 {path}");

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                if let Err(e) = handle_client(stream) {
                    warn!("xecpro daemon: 处理请求失败：{e}");
                }
            }
            Err(e) => warn!("xecpro daemon: accept 失败：{e}"),
        }
        if once {
            break;
        }
    }
    Ok(())
}

fn peer_uid(stream: &UnixStream) -> Result<u32> {
    let mut cred: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            std::ptr::from_mut(&mut cred).cast::<libc::c_void>(),
            &mut len,
        )
    };
    if rc != 0 {
        bail!(
            "getsockopt(SO_PEERCRED) 失败：{}",
            std::io::Error::last_os_error()
        );
    }
    Ok(cred.uid)
}

fn handle_client(mut stream: UnixStream) -> Result<()> {
    let uid = peer_uid(&stream)?;
    let (payload, fds) = recv_request(&stream)?;
    let request = parse_request(&payload)?;

    if !is_allowed(uid) {
        warn!("xecpro daemon: 拒绝 uid {uid}（不在允许列表里）");
        return reply(&mut stream, DENIED);
    }

    info!(
        "xecpro daemon: uid {uid} 请求 root：{:?}（cwd={:?}）",
        request.args, request.cwd
    );
    let status = spawn_root(&request, &fds)?;
    reply(&mut stream, status)
}

fn reply(stream: &mut UnixStream, status: i32) -> Result<()> {
    let mut buf = Vec::with_capacity(8);
    buf.extend_from_slice(&MAGIC.to_le_bytes());
    buf.extend_from_slice(&status.to_le_bytes());
    stream.write_all(&buf).context("写应答")?;
    Ok(())
}

struct Request {
    args: Vec<String>,
    cwd: String,
}

/// 收一次带 SCM_RIGHTS 的请求：返回负载与三个 fd（stdin/stdout/stderr）。
fn recv_request(stream: &UnixStream) -> Result<(Vec<u8>, [RawFd; 3])> {
    let mut buf = vec![0u8; 64 * 1024];
    let mut cmsg_buf = [0u8; 128];
    let mut iov = libc::iovec {
        iov_base: buf.as_mut_ptr().cast::<libc::c_void>(),
        iov_len: buf.len(),
    };
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast::<libc::c_void>();
    msg.msg_controllen = cmsg_buf.len();

    let received = unsafe { libc::recvmsg(stream.as_raw_fd(), &mut msg, 0) };
    if received < 0 {
        bail!("recvmsg 失败：{}", std::io::Error::last_os_error());
    }
    buf.truncate(received as usize);

    let mut fds = [-1 as RawFd; 3];
    let mut count = 0usize;
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let data = libc::CMSG_DATA(cmsg).cast::<RawFd>();
                let bytes = (*cmsg).cmsg_len - libc::CMSG_LEN(0) as usize;
                let n = (bytes / std::mem::size_of::<RawFd>()).min(3 - count);
                for i in 0..n {
                    fds[count + i] = *data.add(i);
                }
                count += n;
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    if count != 3 {
        // 缺 fd 就没法把 stdio 接过去：关掉已收到的，明确报错
        for fd in fds.iter().filter(|fd| **fd >= 0) {
            unsafe { libc::close(*fd) };
        }
        bail!("请求里没有带齐 3 个 stdio fd（收到 {count} 个）");
    }
    Ok((buf, fds))
}

fn parse_request(payload: &[u8]) -> Result<Request> {
    let mut cursor = 0usize;
    let magic = take_u32(payload, &mut cursor)?;
    if magic != MAGIC {
        bail!("魔数不对：0x{magic:08x}");
    }
    let arg_count = take_u32(payload, &mut cursor)? as usize;
    if arg_count > MAX_ARGS {
        bail!("argc 太大：{arg_count}");
    }
    let mut request_args = Vec::with_capacity(arg_count);
    for _ in 0..arg_count {
        let len = take_u32(payload, &mut cursor)? as usize;
        if len > MAX_ARG_LEN || cursor + len > payload.len() {
            bail!("参数长度不合法：{len}");
        }
        request_args.push(String::from_utf8_lossy(&payload[cursor..cursor + len]).into_owned());
        cursor += len;
    }
    let cwd_len = take_u32(payload, &mut cursor)? as usize;
    if cwd_len > MAX_CWD_LEN || cursor + cwd_len > payload.len() {
        bail!("cwd 长度不合法：{cwd_len}");
    }
    let cwd = String::from_utf8_lossy(&payload[cursor..cursor + cwd_len]).into_owned();
    Ok(Request {
        args: request_args,
        cwd,
    })
}

fn take_u32(payload: &[u8], cursor: &mut usize) -> Result<u32> {
    if *cursor + 4 > payload.len() {
        bail!("请求被截断");
    }
    let value = u32::from_le_bytes([
        payload[*cursor],
        payload[*cursor + 1],
        payload[*cursor + 2],
        payload[*cursor + 3],
    ]);
    *cursor += 4;
    Ok(value)
}

/// 把 `su` 的参数翻译成"要执行什么"。认 `-c CMD` / `-s SHELL` / `-`（登录 shell），
/// 其余未知开关跳过并记日志（不静默当成命令）。
fn resolve_command(args: &[String]) -> (String, Vec<String>) {
    let mut shell = "/system/bin/sh".to_string();
    let mut command: Option<String> = None;
    let mut rest: Vec<String> = Vec::new();
    let mut index = 0usize;
    while index < args.len() {
        match args[index].as_str() {
            "-c" | "--command" => {
                index += 1;
                command = args.get(index).cloned();
            }
            "-s" | "--shell" => {
                index += 1;
                if let Some(value) = args.get(index) {
                    shell = value.clone();
                }
            }
            "-" | "-l" | "--login" => {}
            other if other.starts_with('-') => {
                warn!("xecpro daemon: 忽略不认识的 su 开关 {other}");
            }
            other => {
                rest.push(other.to_string());
            }
        }
        index += 1;
    }

    if let Some(cmd) = command {
        (shell, vec!["-c".to_string(), cmd])
    } else if rest.is_empty() {
        (shell, Vec::new())
    } else {
        // `su cmd arg...`：直接执行这个命令
        let (head, tail) = rest.split_first().expect("rest 非空");
        (head.clone(), tail.to_vec())
    }
}

fn spawn_root(request: &Request, fds: &[RawFd; 3]) -> Result<i32> {
    let (program, args) = resolve_command(&request.args);

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        bail!("fork 失败：{}", std::io::Error::last_os_error());
    }
    if pid == 0 {
        // 子进程：接上调用方的 stdio、换工作目录，然后 exec。
        unsafe {
            for (index, fd) in fds.iter().enumerate() {
                libc::dup2(*fd, index as RawFd);
                libc::close(*fd);
            }
            if !request.cwd.is_empty() {
                if let Ok(cwd) = CString::new(request.cwd.clone()) {
                    libc::chdir(cwd.as_ptr());
                }
            }
            let env = build_env();
            let program_c = CString::new(program.clone()).unwrap_or_default();
            let mut exec_argv: Vec<CString> = Vec::with_capacity(args.len() + 2);
            exec_argv.push(CString::new(program.clone()).unwrap_or_default());
            for arg in &args {
                exec_argv.push(CString::new(arg.as_str()).unwrap_or_default());
            }
            let mut argv_ptrs: Vec<*const libc::c_char> =
                exec_argv.iter().map(|a| a.as_ptr()).collect();
            argv_ptrs.push(std::ptr::null());
            let env_ptrs: Vec<*const libc::c_char> = env.iter().map(|e| e.as_ptr()).collect();
            libc::execve(program_c.as_ptr(), argv_ptrs.as_ptr(), env_ptrs.as_ptr());
            // exec 失败：用 _exit 而不是 exit，避免跑 daemon 的 atexit
            libc::_exit(127);
        }
    }

    // 父进程：等它结束，把退出码/信号翻译成 shell 语义的返回值
    let mut status: libc::c_int = 0;
    let waited = unsafe { libc::waitpid(pid, &mut status, 0) };
    if waited < 0 {
        bail!("waitpid 失败：{}", std::io::Error::last_os_error());
    }
    if libc::WIFEXITED(status) {
        Ok(libc::WEXITSTATUS(status))
    } else if libc::WIFSIGNALED(status) {
        Ok(128 + libc::WTERMSIG(status))
    } else {
        Ok(1)
    }
}

fn build_env() -> Vec<CString> {
    // 只给最小必需环境：PATH 保证能跑系统命令，HOME 指向我们的目录。
    let pairs = [
        (
            "PATH",
            "/data/adb/xecpro/bin:/system/bin:/system/xbin:/vendor/bin",
        ),
        ("HOME", defs::XECPRO_DIR),
        ("USER", "root"),
        ("LOGNAME", "root"),
        ("SHELL", "/system/bin/sh"),
        ("TERM", "xterm-256color"),
    ];
    pairs
        .iter()
        .filter_map(|(key, value)| CString::new(format!("{key}={value}")).ok())
        .collect()
}

/// 自检：daemon 能不能起来、socket 能不能连、拒绝路径对不对（不需要真的 su）。
pub fn self_test() -> Result<()> {
    ensure_dirs()?;
    println!("工作目录：{}", defs::XECPRO_DIR);
    println!(
        "允许列表：{}（{} 个 uid）",
        allow_path(),
        read_allow().len()
    );
    println!("socket：{}", socket_path());
    let uid = unsafe { libc::getuid() };
    println!("当前 uid {uid} 是否被授权：{}", is_allowed(uid));
    Ok(())
}
