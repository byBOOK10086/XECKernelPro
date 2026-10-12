// SPDX-License-Identifier: GPL-3.0-only

//! magic mount —— 把 `/data/adb/modules/*/system` 等模块目录挂到真实根文件系统上。
//!
//! 算法照 Magisk（`native/src/core/module.rs`）自己实现（只借算法与边界条件，代码独立写）：
//!
//! 1. 先把所有启用模块的文件收成一棵节点树（目录 / 文件 / 符号链接 / whiteout）；
//! 2. 目录在下面任一情况下**必须换成 tmpfs**：
//!    - 目标路径不存在（bind 挂载必须有落点）；
//!    - 源或目标是符号链接（符号链接不能 bind）；
//!    - 出现 `.replace`（整目录替换，不做镜像）；
//!    - 子节点是 whiteout（模块要删掉真实文件）；
//! 3. tmpfs 分支：把真实目录的内容逐级"镜像"进 worker 目录（目录递归、符号链接重建、
//!    普通文件 bind），再把模块文件放进 worker，最后把整个 worker 目录 bind 到真实路径；
//! 4. 非 tmpfs 分支：直接把模块文件 bind 到真实路径；
//! 5. 模块文件的属主/权限/扩展属性（含 `security.selinux`）在收集阶段从**真实目标**克隆过来，
//!    否则 `SELinux` 会拒绝访问（这一步漏了，症状是"文件挂上了但打不开"）。
//!
//! worker 镜像目录必须落在 tmpfs 上（默认 `/dev/.xecpro_magic`），不能放 `/data`：
//! `/data` 还没准备好、而且会被 init 的 mount 追踪污染。

use std::path::PathBuf;

use anyhow::Result;

#[cfg(target_os = "android")]
mod imp {
    use std::collections::BTreeMap;
    use std::ffi::{CStr, CString, OsStr, OsString};
    use std::fs;
    use std::io;
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    use std::path::{Path, PathBuf};

    use anyhow::{Context, Result, anyhow};
    use log::{info, warn};

    /// 模块里这些顶层目录要单独当根分区处理（Magisk 同款：`system/vendor` → `/vendor`）。
    const SECONDARY_PARTITIONS: [&str; 4] = ["vendor", "product", "system_ext", "odm"];

    /// 模块不允许顶掉的系统文件（我们自己的 root 通道，防模块劫持）。
    const PROTECTED: [&str; 3] = ["su", "ksud", "xudc"];

    /// 模块文件的挂载源。`real` = 真实路径（`/` 开头），`worker` = tmpfs 镜像路径。
    #[derive(Clone)]
    struct Paths {
        real: PathBuf,
        worker: PathBuf,
    }

    impl Paths {
        fn join(&self, name: &OsStr) -> Self {
            Self {
                real: self.real.join(name),
                worker: self.worker.join(name),
            }
        }
    }

    enum FsNode {
        Directory { children: BTreeMap<OsString, Self> },
        File { src: PathBuf },
        Symlink { target: PathBuf },
        Whiteout,
    }

    impl FsNode {
        const fn new_dir() -> Self {
            Self::Directory {
                children: BTreeMap::new(),
            }
        }

        /// 把一个模块目录收进节点树。`src` 是模块内该层目录，`rel` 是它对应的真实路径（`/` 起）。
        fn collect(&mut self, src: &Path, rel: &Path) -> Result<()> {
            let Self::Directory { children } = self else {
                return Ok(());
            };
            let Ok(dir) = fs::read_dir(src) else {
                return Ok(());
            };
            for entry in dir.flatten() {
                let name = entry.file_name();
                let child_src = entry.path();
                let child_rel = rel.join(&name);
                let Ok(meta) = fs::symlink_metadata(&child_src) else {
                    continue;
                };
                let file_type = meta.file_type();
                if file_type.is_dir() {
                    children
                        .entry(name)
                        .or_insert_with(Self::new_dir)
                        .collect(&child_src, &child_rel)?;
                } else if file_type.is_symlink() {
                    let target = fs::read_link(&child_src)?;
                    if let Some(slot) = children.get_mut(&name)
                        && let Self::Directory { .. } = slot
                    {
                        continue; // 目录不能被后面的模块顶成链接
                    }
                    children.insert(name, Self::Symlink { target });
                } else if is_whiteout(&meta) {
                    children.insert(name, Self::Whiteout);
                } else {
                    let real_path = root_join(&child_rel);
                    if real_path.exists() {
                        // 克隆真实目标的属主/权限/标签：sdcardfs/SELinux 认这个
                        let _ = clone_attr(&real_path, &child_src);
                    }
                    children.insert(name, Self::File { src: child_src });
                }
            }
            Ok(())
        }

        /// 这个节点的父目录是否必须换成 tmpfs（Magisk `parent_should_be_tmpfs`）。
        fn parent_should_be_tmpfs(&self, target: &Path) -> bool {
            match self {
                Self::Directory { .. } | Self::File { .. } => {
                    fs::symlink_metadata(target).map_or(true, |meta| meta.file_type().is_symlink())
                }
                _ => true, // 符号链接 / whiteout 都不能直接 bind
            }
        }

        /// 提交一个节点。`is_root_dir` = 顶层分区（模块想在顶层放不可支持的东西时只警告不中止）。
        ///
        /// 每个分支都以 `Ok(())` 结尾是刻意的：这里做的是**副作用**（挂载/建目录），
        /// 把尾表达式提出来反而看不出每个分支各自成功/跳过的语义，故显式放行该 lint。
        #[allow(clippy::branches_sharing_code)]
        fn commit(&mut self, paths: &Paths, is_root_dir: bool) -> Result<()> {
            match self {
                Self::Directory { children } => {
                    let mut need_tmpfs = false;
                    children.retain(|name, node| {
                        if name == OsStr::new(".replace") {
                            if is_root_dir {
                                warn!(
                                    "无法替换 {}（顶层不允许 .replace），已忽略",
                                    paths.real.display()
                                );
                                return false;
                            }
                            need_tmpfs = true;
                            return true;
                        }
                        let child = paths.join(name);
                        if node.parent_should_be_tmpfs(&child.real) {
                            if is_root_dir {
                                warn!("无法添加 {}（顶层不支持），已跳过", child.real.display());
                                return false;
                            }
                            need_tmpfs = true;
                        }
                        true
                    });

                    if need_tmpfs {
                        self.commit_tmpfs(paths)?;
                        // worker 里放好之后，整棵搬（bind --rec）到真实路径
                        bind_mount(&paths.worker, &paths.real, true).with_context(|| {
                            format!(
                                "move {} -> {}",
                                paths.worker.display(),
                                paths.real.display()
                            )
                        })?;
                        info!("magic mount: {} <- tmpfs(worker)", paths.real.display());
                    } else {
                        for (name, node) in children.iter_mut() {
                            node.commit(&paths.join(name), false)?;
                        }
                    }
                    Ok(())
                }
                Self::File { src } => {
                    bind_mount(src, &paths.real, false).with_context(|| {
                        format!("mount {} <- {}", paths.real.display(), src.display())
                    })?;
                    info!("magic mount: {} <- {}", paths.real.display(), src.display());
                    Ok(())
                }
                Self::Symlink { .. } | Self::Whiteout => {
                    // 这两种必须走 tmpfs 分支；能走到这里说明父目录判定漏了
                    warn!("{} 需要 tmpfs 父目录，已跳过", paths.real.display());
                    Ok(())
                }
            }
        }

        /// tmpfs 分支：把真实目录镜像进 worker，再把模块内容放进 worker。
        ///
        /// 同 `commit`：分支尾都是 `Ok(())` 是副作用语义使然，显式放行该 lint。
        #[allow(clippy::branches_sharing_code)]
        fn commit_tmpfs(&mut self, paths: &Paths) -> Result<()> {
            match self {
                Self::Directory { children } => {
                    fs::create_dir_all(&paths.worker)
                        .with_context(|| format!("mkdir {}", paths.worker.display()))?;
                    if paths.real.exists() {
                        let _ = clone_attr(&paths.real, &paths.worker);
                    } else if let Some(parent) = paths.worker.parent() {
                        let _ = clone_attr(parent, &paths.worker);
                    }

                    // `.replace`：只放模块自己的内容，**不做镜像**
                    if let Some(Self::File { src }) = children.remove(OsStr::new(".replace"))
                        && let Some(module_dir) = src.parent()
                    {
                        for (name, node) in children.iter_mut() {
                            let child = paths.join(name);
                            match node {
                                Self::Directory { .. } => {
                                    let from = module_dir.join(name);
                                    mount_dummy("replace", &from, &child.worker, true)?;
                                }
                                _ => node.commit_tmpfs(&child)?,
                            }
                        }
                        info!("magic mount: {} <- replace", paths.real.display());
                        return Ok(());
                    }

                    // 镜像真实目录里"模块没提供"的项
                    if let Ok(dir) = fs::read_dir(&paths.real) {
                        for entry in dir.flatten() {
                            let name = entry.file_name();
                            if children.contains_key(&name) {
                                continue;
                            }
                            let child = paths.join(&name);
                            let Ok(meta) = fs::symlink_metadata(entry.path()) else {
                                continue;
                            };
                            if meta.file_type().is_dir() {
                                // 不能直接把真实目录 bind 成镜像：init 会追踪这些 mount
                                // 导致卸载/重挂异常，只能递归镜像。
                                Self::new_dir().commit_tmpfs(&child)?;
                            } else if meta.file_type().is_symlink() {
                                let target = fs::read_link(entry.path())?;
                                Self::Symlink { target }.commit_tmpfs(&child)?;
                            } else {
                                mount_dummy("mirror", &entry.path(), &child.worker, false)?;
                            }
                        }
                    }

                    for (name, node) in children.iter_mut() {
                        node.commit_tmpfs(&paths.join(name))?;
                    }
                    Ok(())
                }
                Self::File { src } => {
                    mount_dummy("mount", src, &paths.worker, false)?;
                    Ok(())
                }
                Self::Symlink { target } => {
                    if paths.worker.exists() {
                        let _ = fs::remove_file(&paths.worker);
                    }
                    let link_target = target.clone();
                    std::os::unix::fs::symlink(&link_target, &paths.worker).with_context(|| {
                        format!(
                            "symlink {} -> {}",
                            paths.worker.display(),
                            link_target.display()
                        )
                    })?;
                    if paths.real.exists() {
                        let _ = clone_attr(&paths.real, &paths.worker);
                    }
                    info!(
                        "magic mount: {} <- link {}",
                        paths.real.display(),
                        link_target.display()
                    );
                    Ok(())
                }
                Self::Whiteout => {
                    // 不往 worker 里放东西 = 真实文件在这个目录里"消失"（这就是删除语义）
                    info!("magic mount: {} <- (whiteout/删除)", paths.real.display());
                    Ok(())
                }
            }
        }
    }

    /// `/` + 相对路径。
    fn root_join(rel: &Path) -> PathBuf {
        let stripped = rel.strip_prefix("/").unwrap_or(rel);
        Path::new("/").join(stripped)
    }

    fn is_whiteout(meta: &fs::Metadata) -> bool {
        (meta.mode() & libc::S_IFMT) == libc::S_IFCHR && meta.rdev() == 0
    }

    fn cpath(path: &Path) -> Result<CString> {
        CString::new(path.as_os_str().as_bytes())
            .map_err(|_| anyhow!("路径里有 NUL：{}", path.display()))
    }

    fn bind_mount(src: &Path, dst: &Path, recursive: bool) -> Result<()> {
        let flags = libc::MS_BIND | if recursive { libc::MS_REC } else { 0 };
        let src_c = cpath(src)?;
        let dst_c = cpath(dst)?;
        let rc = unsafe {
            libc::mount(
                src_c.as_ptr(),
                dst_c.as_ptr(),
                std::ptr::null(),
                flags,
                std::ptr::null(),
            )
        };
        if rc != 0 {
            return Err(anyhow!(
                "bind mount {} -> {} 失败: {}",
                src.display(),
                dst.display(),
                io::Error::last_os_error()
            ));
        }
        Ok(())
    }

    /// 先在 worker 里造出落点（目录/空文件），再 bind。
    fn mount_dummy(reason: &str, src: &Path, dst: &Path, dir: bool) -> Result<()> {
        if dir {
            fs::create_dir_all(dst).with_context(|| format!("mkdir {}", dst.display()))?;
        } else if !dst.exists() {
            fs::File::create(dst).with_context(|| format!("create {}", dst.display()))?;
        }
        bind_mount(src, dst, false)
            .with_context(|| format!("{reason} {} <- {}", dst.display(), src.display()))
    }

    /// 克隆权限位 + 属主 + 全部扩展属性（含 `security.selinux`）。
    fn clone_attr(src: &Path, dst: &Path) -> Result<()> {
        let meta = fs::symlink_metadata(src)?;
        let _ = fs::set_permissions(dst, fs::Permissions::from_mode(meta.mode() & 0o7777));
        if let Ok(dst_c) = cpath(dst) {
            unsafe {
                libc::lchown(dst_c.as_ptr(), meta.uid(), meta.gid());
            }
        }
        for name in list_xattrs(src).unwrap_or_default() {
            if let Ok(Some(value)) = get_xattr(src, &name) {
                let _ = set_xattr(dst, &name, &value);
            }
        }
        Ok(())
    }

    fn list_xattrs(path: &Path) -> Result<Vec<CString>> {
        let c = cpath(path)?;
        let size = unsafe { libc::llistxattr(c.as_ptr(), std::ptr::null_mut(), 0) };
        if size <= 0 {
            return Ok(Vec::new());
        }
        let mut buf = vec![0u8; size.cast_unsigned()];
        let n = unsafe {
            libc::llistxattr(
                c.as_ptr(),
                buf.as_mut_ptr().cast::<libc::c_char>(),
                buf.len(),
            )
        };
        if n <= 0 {
            return Ok(Vec::new());
        }
        buf.truncate(n.cast_unsigned());
        Ok(buf
            .split(|b| *b == 0)
            .filter(|s| !s.is_empty())
            .filter_map(|s| CString::new(s).ok())
            .collect())
    }

    fn get_xattr(path: &Path, name: &CStr) -> Result<Option<Vec<u8>>> {
        let c = cpath(path)?;
        let size = unsafe { libc::lgetxattr(c.as_ptr(), name.as_ptr(), std::ptr::null_mut(), 0) };
        if size < 0 {
            return Ok(None);
        }
        let mut buf = vec![0u8; size.cast_unsigned()];
        let n = unsafe {
            libc::lgetxattr(
                c.as_ptr(),
                name.as_ptr(),
                buf.as_mut_ptr().cast::<libc::c_void>(),
                buf.len(),
            )
        };
        if n < 0 {
            return Ok(None);
        }
        buf.truncate(n.cast_unsigned());
        Ok(Some(buf))
    }

    fn set_xattr(path: &Path, name: &CStr, value: &[u8]) -> bool {
        let Ok(c) = cpath(path) else {
            return false;
        };
        unsafe {
            libc::lsetxattr(
                c.as_ptr(),
                name.as_ptr(),
                value.as_ptr().cast::<libc::c_void>(),
                value.len(),
                0,
            ) == 0
        }
    }

    /// 挂载所有启用模块。返回参与挂载的模块名（按处理顺序）。
    ///
    /// `work_root` 必须在 tmpfs 上（默认 `/dev/.xecpro_magic`）。
    ///
    /// # Errors
    ///
    /// 模块目录读不了、worker 目录建不出来、或任一次 bind/mount 系统调用失败时返回错误；
    /// 单个模块内部的问题（读不到的条目等）只跳过并记日志，不影响其它模块。
    pub fn mount_modules(work_root: &Path) -> Result<Vec<String>> {
        let mut modules = Vec::new();
        let mut root = FsNode::new_dir();

        crate::module::foreach_module(crate::module::ModuleType::Active, |module_dir| {
            let Some(name) = module_dir.file_name().and_then(|n| n.to_str()) else {
                return Ok(());
            };
            let system = module_dir.join("system");
            if !system.is_dir() {
                return Ok(());
            }
            info!("magic mount: 收集模块 {name}");
            modules.push(name.to_string());
            root.collect(&system, Path::new("/system"))?;
            Ok(())
        })?;

        if modules.is_empty() {
            info!("magic mount: 没有启用中的模块");
            return Ok(modules);
        }

        // 模块把文件放在 system/vendor/... 时，若 /vendor 是独立分区要挪到根上。
        // 这里先把整个 system 子树摘出来再动：直接 get_mut 的话，往 root 里 insert
        // 会和 sys 的可变借用打架。
        if let FsNode::Directory { children } = &mut root
            && let Some(mut system_node) = children.remove(OsStr::new("system"))
            && let FsNode::Directory { children: sys } = &mut system_node
        {
            let mut hoisted = Vec::new();
            for part in SECONDARY_PARTITIONS {
                if let Some(node) = sys.remove(OsStr::new(part)) {
                    if Path::new("/").join(part).is_dir() {
                        hoisted.push((part.to_string(), node));
                    } else {
                        sys.insert(OsString::from(part), node);
                    }
                }
            }
            // 防模块劫持：模块提供的 su/ksud/xudc 一律丢掉
            if let Some(FsNode::Directory { children: bin }) = sys.get_mut(OsStr::new("bin")) {
                for name in PROTECTED {
                    if bin.remove(OsStr::new(name)).is_some() {
                        warn!("magic mount: 忽略模块提供的 /system/bin/{name}（受保护）");
                    }
                }
            }
            children.insert(OsString::from("system"), system_node);
            for (part, node) in hoisted {
                info!("magic mount: system/{part} -> /{part}（独立分区）");
                children.insert(OsString::from(part), node);
            }
        }

        fs::create_dir_all(work_root).with_context(|| format!("mkdir {}", work_root.display()))?;
        let paths = Paths {
            real: PathBuf::from("/"),
            worker: work_root.to_path_buf(),
        };
        root.commit(&paths, true)?;
        Ok(modules)
    }
}

/// 调试/内部入口：`xudc magic-mount [--work DIR] [--dry-run]`。
///
/// # Errors
///
/// 枚举模块目录失败、或挂载失败时返回错误；非 Android 目标直接返回错误（不静默成功）。
pub fn cli_magic_mount(work: Option<PathBuf>, dry_run: bool) -> Result<()> {
    #[cfg(target_os = "android")]
    {
        let work_root = work.unwrap_or_else(|| PathBuf::from(crate::defs::MAGIC_WORK_DIR));
        if dry_run {
            let mut names = Vec::new();
            crate::module::foreach_module(crate::module::ModuleType::Active, |dir| {
                if dir.join("system").is_dir()
                    && let Some(name) = dir.file_name().and_then(|n| n.to_str())
                {
                    names.push(name.to_string());
                }
                Ok(())
            })?;
            println!("会参与 magic mount 的模块（{} 个）：", names.len());
            for name in names {
                println!("  - {name}");
            }
            println!("worker: {}", work_root.display());
            return Ok(());
        }
        let mounted = imp::mount_modules(&work_root)?;
        println!(
            "magic mount 完成：{} 个模块，worker={}",
            mounted.len(),
            work_root.display()
        );
        for name in mounted {
            println!("  - {name}");
        }
        Ok(())
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (work, dry_run);
        anyhow::bail!("magic mount 只能在 Android 上运行")
    }
}
