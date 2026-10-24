# susfs_lite.kpm（实验性）

原厂（BL 锁定 / 未换内核）设备上最接近 SUSFS 的一层：kretprobe 改写
newfstatat / faccessat 的返回值为 `-ENOENT`，让不在 allow 集里的进程
「看不见」配置前缀（默认 `/data/adb`）下的路径。

## 诚实边界（先读这个）

- **实验性，未真机验证**，不在 CI 里构建。出问题 `xudc kpm unload` 即可
  完整撤掉（kretprobe unregister 是干净的可逆操作）。
- 只覆盖 **stat 族 + faccessat**。`getdents64`（列目录）**不隐藏**——
  检测方列 `/data` 仍会看到 `adb` 条目名；只是 stat 它会得到 ENOENT。
- 有真 SUSFS（GKI 内置内核）的设备**不要用这个**，直接用 SUSFS。
- allow 集必须包含管理器 uid（`allow_uids=...`），否则管理器自己也会
  看不见 `/data/adb`。

## 构建

```sh
make NDK=/path/to/android-ndk        # 产物 susfs_lite.kpm（ET_REL ELF）
```

## 装载

```sh
# manager uid 可从管理器首页看到；多个 uid/前缀用逗号分隔
xudc kpm load /data/local/tmp/susfs_lite.kpm \
    "allow_uids=10236;prefix=/data/adb,/debug_ramdisk"
# 运行时改参数（同 init 参数格式）
xudc kpm control susfs_lite "reload allow_uids=10236;prefix=/data/adb"
# 卸载（恢复全部 syscall 行为）
xudc kpm unload susfs_lite
```

## 为什么长这样

本仓库的 KPM 引擎（`kernel/kpm/`）是「可重定位 ELF 装载器」：做节解析
与 kallsyms 符号重定位，然后调 `.kpm.init/.kpm.ctl0/.kpm.exit`，不给
模块导出 inline-hook 框架。因此样例用内核标准 `register_kretprobe()`
（所有目标内核都有），entry 阶段把 filename 拷进 per-instance 数据、
return 阶段改写 `regs->regs[0]`。
