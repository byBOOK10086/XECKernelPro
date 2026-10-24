# susfs_lite.kpm（实验性）

原厂（BL 锁定 / 未换内核）设备上最接近 SUSFS 的一层：kretprobe 改写
newfstatat / faccessat 的返回值为 `-ENOENT`，让不在 allow 集里的进程
「看不见」配置前缀（默认 `/data/adb`）下的路径。

## 诚实边界（先读这个）

- **实验性，未真机验证**。CI（build-xecpro.yml 的 build-kpm job）会把它
  编进管理器 APK 的 assets/kpm/，管理器 KPM 页有内置加载入口；装载与
  卸载仍由用户手动决定。出问题 `xudc kpm unload` 即可完整撤掉
  （kretprobe unregister 是干净的可逆操作）。
- 只覆盖 **stat 族 + faccessat**。`getdents64`（列目录）**不隐藏**——
  检测方列 `/data` 仍会看到 `adb` 条目名；只是 stat 它会得到 ENOENT。
- 有真 SUSFS（GKI 内置内核）的设备**不要用这个**，直接用 SUSFS。
- allow 集必须包含管理器 uid（`allow_uids=...`），否则管理器自己也会
  看不见 `/data/adb`。
- **必须对真内核树编译**：kretprobe/pt_regs 的结构体布局来自目标内核头
  文件，freestanding 硬编偏移就是布局赌博（见 CHANGELOG「一个 .ko 通吃
  全部内核」一节）。CI 用 android15-6.6 的头编，别的 kprobes ABI 不在
  承诺范围。取当前 uid 走引擎 compact 表导出的 `get_current_uid`
  （kernel/kpm/compact.c）——ET_REL 模块链接不到 percpu 的 `current`。

## 构建

```sh
make KDIR=/path/to/prepared-arm64-kernel-tree   # 产物 susfs_lite.kpm（ET_REL ELF）
# CI 自动路径：build-xecpro.yml 的 build-kpm job（ddk-min:android15-6.6 容器）
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
