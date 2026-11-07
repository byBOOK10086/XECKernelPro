# susfs4ksu-lkm（第三方，vendored）

SUSFS 的可加载内核模块（LKM）移植版。我们把它的**内核源码**收进来编译成
`susfs_guard_lkm-<KMI>.ko`，随 `xudc` 一起发布，开机由我们的 late-load 路径
在 `kernelsu.ko` 之后加载。这样 LKM 形态的设备也能用上 SUSFS —— 在此之前
SUSFS 只存在于自编译的 GKI 内置内核（见 `kernel/Kconfig` 的说明）。

## 来源

- 仓库：<https://github.com/inforcqb/susfs4ksu-for-lkm>
- 收录 commit：`269766c8fcd52bb41c1f3b11841a93fc52ba68b7`（2026-11-07）
- 许可证：**GPL-2.0**（`LICENSE`），与我们的内核态代码同族，可 vendor。
- 它自己声明的上游：susfs4ksu（功能与 hook 点）+ SukiSU-Ultra（`patch_memory` /
  `lsm_hook` / `symbol_resolver`）。

## 收录了什么 / 没收录什么

收录：`kernel/`（模块全部源码）、`LICENSE`、`README.md`（原作者说明）、
`PROC_INTERFACES.md`（`/proc` 控制节点文档）。

**没有**收录：它的 `.github/`（我们用自己的流水线）、`module/`（那是给模块管理器
刷的 zip 加载器，我们不经过模块体系）、`tools/`（它自己的测试/探针工具）。

## 我们改了什么

暂无。保持上游原样，便于对账与升级；所有接线都在我们自己的树里做
（`userspace/ksud/src/late_load.rs`、`.github/workflows/`）。

## 为什么不直接用它自己的加载器

它用 `susfs_insmod` 从内存装载。我们已经有同类的 in-tree 装载器
（`ksuinit::load_module`），`late_load.rs` 加载 `kernelsu.ko` 走的就是它，
所以复用同一条路径，不额外引一个二进制。

## 已知代价（集成前已评估，写在这里防止后人重复踩）

- 它实现隐藏靠 `security_hook_heads` 头部插入 + 28 个 `sys_call_table` 表项替换
  + 20 多个 kprobe。syscall 表那部分会让基于 syscall 时序/veneer 的判据重新变差。
- `sus_mount` 只隐藏输出行、不改挂载对象，路径级 `statfs`/`mnt_id` 会比真卸载更不自然。
- 所有 `sus_*` 可见性最终落在 `ksu_uid_should_umount(uid)` 一个闸门上：**把探测器
  加进 allowlist 会让它所有隐藏同时失效**。
- `susfs_fp_remove` 在发现表项「已不是自己」时拒绝恢复 → `rmmod` 后有 UAF。
- <5.11 内核模块会引用全局 `__stack_chk_guard`，而 `ksud insmod` 的 kallsyms 遍历
  解析不到它 → 上游 Makefile 在 5.10 上自动加 `-fno-stack-protector`，**这条不能删**。
