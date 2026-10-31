# antiwipe_full.kpm — 满血防格 KPM 模块

与旧版 `kpm/antiwipe`（薄开关：守卫逻辑在内核 `feature/antiwipe.c`，模块只调
`ksu_antiwipe_arm()`）不同，本模块把**全部拦截逻辑装进模块自身**：

- 内核侧零改动、零依赖：不 import 任何 `ksu_antiwipe_*`（旧守卫只在装载时
  被运行期 kallsyms 解析做"单一Owner 接管 disarm"，符号不存在也能装载）；
- hook 基座 = 双引擎 KPM core（`kernel/kpmcore`）的 `hook_wrap`（LSM 分发
  函数链式 hook）+ `hook_syscalln`（sys_reboot）；
- 结构体成员偏移**装载时从 `/sys/kernel/btf/vmlinux` 自举**（`aw_btf.c`），
  不做任何跨 KMI 的编译期布局假设；缺 BTF → 拒绝装载（失败关闭）；
- 卸载（或不装载）= 内核完全没有拦截，不存在重启循环风险。

## 预设危险指令矩阵（每条都有唯一 THREAT 编号与拦截点，受守卫脚本对账）

| 编号 | 危险指令 / 动作 | 拦截点 |
|---|---|---|
| THREAT-01 | `rm -rf /data`、`/data/<顶层>`、`/data/adb/<顶层>` | inode_unlink / inode_rmdir |
| THREAT-02 | `rm -rf /sdcard`、`/storage/emulated/0` 等 | 同上 |
| THREAT-03 | `rm -rf /system` `/vendor` `/persist` `/efs`…（含挂载根本体） | 同上 |
| THREAT-04 | `mv` 移动/覆盖受保护树 | inode_rename |
| THREAT-05 | `dd … of=/dev/block/by-name/{boot,misc,userdata,…}` | file_open（by-name dev 表） |
| THREAT-06 | `dd … of=/dev/block/mmcblk0`（整盘） | file_open（MKDEV(major,0)） |
| THREAT-07 | `/dev/block/*` 兜底网 + `/dev/mtd*` `/dev/ubi*` 裸闪存写 | file_open |
| THREAT-08 | （豁免项）loop/zram 镜像设备放行 | file_open |
| THREAT-09 | 原地覆写密钥/状态文件（`/data/system`、`/data/misc`、`/data/unencrypted`、`/metadata`…） | file_open（写保护树） |
| THREAT-10 | `echo --wipe_data > /cache/recovery/command` | file_open |
| THREAT-11 | `truncate -s 0` 受保护文件 | inode_setattr + file_truncate |
| THREAT-12 | `chmod 000` / `chown` 破坏受保护文件 | inode_setattr |
| THREAT-13 | 通过已打开 fd `ftruncate` | file_truncate（6.6+，kallsyms 可选解析） |
| THREAT-14 | 执行 `fastboot` `flash_image` `flash_erase` `flash_eraseall` `nandwrite` | bprm_check（6.8 前后两个分发名都解析） |
| THREAT-15 | `FS_IOC_REMOVE_ENCRYPTION_KEY(_ALL_USERS)` 内核密钥圈销毁（加密格机） | file_ioctl |
| THREAT-16 | `reboot bootloader/recovery/edl/dload` + kexec 逃逸 | sys_reboot（RESTART2 目标串 + KEXEC；**本钩子不做 comm 豁免**，只认信任窗口） |
| THREAT-17 | 模块武装前已打开的 fd 直接 write（长寿命 fd） | file_permission（MAY_WRITE 复查） |
| THREAT-18 | comm 豁免伪造（`prctl(PR_SET_NAME,"vold")`） | 豁免 = comm **且** 可执行路径在 /system|/apex|/vendor 下 |

**诚实边界**（与旧守卫一致，软件守卫的物理极限）：
- 拦不住"知道这台内核并会动手"的交互式 root——它可以直接 `kpm unload antiwipe`；
- recovery 模式里 ksud 不运行 → 模块不装载 → recovery 内的抹除不在保护内
  （但普通系统里 `reboot recovery` 本身被 THREAT-16 拦住）；
- init 代理的 `setprop sys.powerctl` 重启链在 THREAT-16 面前同样被拦（这是
  特性不是缺陷：要进 bootloader 请用管理器流程，信任窗口 600s 内放行）。

## 信任窗口与 ctl0 协议（管理器契约兼容）

管理器 `KsuCli.kt` 契约保持不变：模块名 `antiwipe`、`kpm control antiwipe
trust 600`（刷写前自动放行 / finally `trust 0` 收回）、`kpm unload antiwipe`、
`kpm list` 前缀匹配。ctl0 完整命令：

```
（空）        状态:  armed=1 blocked=3 trust=0 hooks=8 devs=62 pins=58
on / off      武装 / 解除（off 后可 on 重新武装，模块不必卸载）
trust <秒>    信任窗口（≤3600s，窗口内放行全部 watched 操作）
last          最近一次拦截: last=<kind> pid=<p> comm=<c> target=<t>
selftest      干跑自检: SELFTEST PASS offsets=1 armed=1 hooks=8 devs=62 pins=58 …
```

`KPM_INIT` 参数支持 `armed=0`（装载但不武装）。

## 构建

任意带 aarch64 后端的 clang + lld（ubuntu: `apt install clang lld`）：

```sh
cd kpm/antiwipe_full && ./build.sh      # 产物 antiwipe.kpm
```

产物拷贝到 `manager/app/src/main/assets/kpm/antiwipe.kpm`（覆盖旧薄模块二进制）。
CI：`.github/workflows/build-antiwipe-kpm.yml` 每次 push 自动构建并上传 artifact。

## 引擎要求

需要双 KPM 引擎内核（CONFIG_KSU_KPM_AP=y，2026-10-31 起的全部构建默认开启）：
`hook_wrap`/`hook_syscalln`/`kallsyms_lookup_name`/`kf_*`/`task_struct_offset`
来自 `kp_kpm_symbols[]`，内核函数经 kallsyms 回退解析。旧内核（无引擎）装载
会因符号解析失败被拒绝——失败关闭。

模块只 import 内核符号与引擎符号；BTF 需要 `CONFIG_DEBUG_INFO_BTF=y`
（GKI 全系默认开启）。
