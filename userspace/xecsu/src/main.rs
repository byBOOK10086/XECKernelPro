// SPDX-License-Identifier: GPL-3.0-only

//! xecsu —— 挂载态 root（<5.10）的 `su` 垫片。
//!
//! 这条路线没有内核 sucompat，所以 `su` 自己**不提权**：它把 argv、cwd 和自己的
//! stdin/stdout/stderr 三个 fd 通过 UNIX socket 交给 daemon。daemon 由 init 以 root
//! 拉起（`service xecpro_daemon ... user root`），由它 fork/exec 出真正的 root shell，
//! 再把退出码回传。
//!
//! **安全边界在 daemon 一侧**：daemon 只信 `SO_PEERCRED` 给出的对端 uid，再查允许列表；
//! 客户端传来的任何字段（包括 uid）都只是提示信息，不作数。
//!
//! 协议（客户端 → daemon，一次 sendmsg 带 3 个 SCM_RIGHTS fd）：
//! ```text
//! u32 magic = 0x58454353 ("XECS")
//! u32 argc
//! argc × (u32 len + bytes)      # argv[0..]
//! u32 cwd_len + bytes           # 工作目录（空 = 继承 daemon 的 /）
//! ```
//! 应答（daemon → 客户端）：
//! ```text
//! u32 magic                     # 同上
//! i32 result                    # >=0: 子进程退出码；<0: -(errno)，-4096 表示拒绝
//! ```
//! 约定：允许列表外的 uid 一律 `-EPERM`（-1）并记日志，不弹窗、不"先放行再问"。

use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, RawFd};

const MAGIC: u32 = 0x5845_4353; // "XECS"
const SOCKET_PATH: &str = "/data/adb/xecpro/su.sock";
const DENIED: i32 = -1; // -EPERM

fn main() {
    let argv: Vec<String> = std::env::args().collect();
    let args: Vec<String> = if argv.len() > 1 {
        argv[1..].to_vec()
    } else {
        vec!["sh".to_string()]
    };
    std::process::exit(run(&args));
}

fn run(args: &[String]) -> i32 {
    let socket = match std::os::unix::net::UnixStream::connect(SOCKET_PATH) {
        Ok(socket) => socket,
        Err(e) => {
            eprintln!("xecsu: 连不上 {SOCKET_PATH}（{e}）—— 挂载态 root 的 daemon 没在跑？");
            return 1;
        }
    };

    let payload = encode(args);
    let fds: [RawFd; 3] = [
        io::stdin().as_raw_fd(),
        io::stdout().as_raw_fd(),
        io::stderr().as_raw_fd(),
    ];
    if let Err(e) = send_with_fds(&socket, &payload, &fds) {
        eprintln!("xecsu: 发送请求失败：{e}");
        return 1;
    }

    match read_result(&socket) {
        Ok(status) if status >= 0 => status,
        Ok(DENIED) => {
            eprintln!("xecsu: 这个应用没有被授权 root（去管理器里授权后再试）");
            1
        }
        Ok(other) => {
            eprintln!("xecsu: daemon 返回错误：{other}");
            1
        }
        Err(e) => {
            eprintln!("xecsu: 读应答失败：{e}");
            1
        }
    }
}

fn encode(args: &[String]) -> Vec<u8> {
    let mut buf = Vec::new();
    buf.extend_from_slice(&MAGIC.to_le_bytes());
    buf.extend_from_slice(&(args.len() as u32).to_le_bytes());
    for arg in args {
        buf.extend_from_slice(&(arg.len() as u32).to_le_bytes());
        buf.extend_from_slice(arg.as_bytes());
    }
    let cwd = std::env::current_dir()
        .ok()
        .and_then(|p| p.to_str().map(str::to_string))
        .unwrap_or_default();
    buf.extend_from_slice(&(cwd.len() as u32).to_le_bytes());
    buf.extend_from_slice(cwd.as_bytes());
    buf
}

/// 一次 sendmsg，把 3 个 fd 作为 SCM_RIGHTS 附件送出去（stdio 是管道还是 pty 都适用，
/// 不用区分 /dev/pts 路径，也不会有 pts 权限问题）。
fn send_with_fds(
    socket: &std::os::unix::net::UnixStream,
    payload: &[u8],
    fds: &[RawFd; 3],
) -> io::Result<()> {
    let mut iov = libc::iovec {
        iov_base: payload.as_ptr().cast_mut().cast::<libc::c_void>(),
        iov_len: payload.len(),
    };
    // cmsg 空间：头部 + 3 个 int
    let mut cmsg_buf = [0u8; 64];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast::<libc::c_void>();
    msg.msg_controllen =
        unsafe { libc::CMSG_SPACE((3 * std::mem::size_of::<RawFd>()) as u32) } as usize;

    unsafe {
        let cmsg = libc::CMSG_FIRSTHDR(&msg);
        if cmsg.is_null() {
            return Err(io::Error::other("cmsg 空间不足"));
        }
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = libc::CMSG_LEN((3 * std::mem::size_of::<RawFd>()) as u32) as usize;
        let data = libc::CMSG_DATA(cmsg).cast::<RawFd>();
        std::ptr::copy_nonoverlapping(fds.as_ptr(), data, 3);
    }

    let sent = unsafe { libc::sendmsg(socket.as_raw_fd(), &msg, 0) };
    if sent < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn read_result(socket: &std::os::unix::net::UnixStream) -> io::Result<i32> {
    use std::io::Read;
    let mut buf = [0u8; 8];
    let mut stream = socket;
    stream.read_exact(&mut buf)?;
    let magic = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
    if magic != MAGIC {
        return Err(io::Error::other("应答魔数不对"));
    }
    Ok(i32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]))
}

/// 给将来 argv[0] 判断留的口子：`su` 被软链到别的名字时也能认出来。
#[allow(dead_code)]
fn invoked_as_su() -> bool {
    std::env::args()
        .next()
        .map(|arg0| arg0.rsplit('/').next() == Some("su"))
        .unwrap_or(false)
}

#[allow(dead_code)]
fn cstring(value: &str) -> CString {
    CString::new(value).unwrap_or_default()
}
