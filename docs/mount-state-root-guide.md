# 5.10 以下内核使用指南（挂载态 / Magisk 路线）

> **一句话**：内核 < 5.10 的非 GKI 机型（4.x / 5.0~5.9）上，KernelSU 的 LKM 路线不可用，
> 管理器会用 **Magisk 官方的修补器**帮你产出补丁镜像 —— 刷完之后这台设备就是一套
> **标准 Magisk**（root、模块、Zygisk 都由 Magisk 提供），管理器只是它的一个前端。
>
> 5.10 及以上的 GKI 设备完全不受影响：那条路继续走内核态（KSU 驱动 + LKM），
> 而且**不会出现任何 Magisk 的东西**；反过来这台 5.10 以下的设备上也不会出现 KSU 的痕迹。
> 两条路线互不污染（守卫脚本 `_scratch/check_root_mode_isolation.py` 会拦住越界）。

---

## 一、先对号入座

| 机型 | 特征 | 怎么选 |
|---|---|---|
| 普通机型（Android 6~12 大多数） | boot 镜像里有 ramdisk | 安装页选 **boot.img** |
| **A-only legacy SAR** | boot 里**没有** ramdisk（system-as-root，A-only） | 安装页选 **recovery.img**，并勾上 **Recovery 模式** |
| 内核 5.10 及以上（GKI） | — | 不走这条路，用安装页的 KSU 直刷 |

不确定的话：选 boot.img 试，脚本会明确告诉你镜像里有没有 ramdisk。

---

## 二、准备

1. **解开 Bootloader**（没解 BL 做不了）。
2. **备份原厂镜像**：从同版本固件包里取 `boot.img`（必要时还有 `recovery.img`）。
   刷坏了 `fastboot flash boot boot.img` 就能救回来。
3. 管理器版本要够新（带 Magisk 官方载荷的那一版起）。

---

## 三、操作步骤（全程不需要 root）

1. 管理器 → **安装** 页 → 顶部会写「Magisk 路线（5.10 以下挂载态）」。
2. 安装方式选 **「Magisk 修补（5.10 以下的内核）」**。
3. 点「下一步」→ 选你机型的 **boot.img**（A-only legacy SAR 选 `recovery.img`）。
4. 需要的话展开**高级选项**（默认全关，与 Magisk 官方默认一致）：

   | 选项 | 传给脚本 | 什么时候勾 |
   |---|---|---|
   | 保留 AVB 2.0 / dm-verity | `KEEPVERITY=true` | 想保留校验、不做 AVB 处理时 |
   | 保留强制加密 | `KEEPFORCEENCRYPT=true` | 设备已加密且不想动 fstab 时 |
   | 同时修补 vbmeta flags | `PATCHVBMETAFLAG=true` | 需要改 vbmeta flags 的机型 |
   | Recovery 模式 | `RECOVERYMODE=true` | A-only legacy SAR（boot 里没 ramdisk） |

   `LEGACYSAR`（内核里 `skip_initramfs` → `want_initramfs`）**由管理器自动判定**并传给脚本。
5. 管理器跑官方 `boot_patch.sh`（日志实时显示），完成后弹出保存框 ——
   把产出的镜像（`magisk-patched-*.img`）存到你找得到的地方。
6. 刷入：

   ```sh
   # 普通机型
   fastboot flash boot magisk-patched.img
   # A-only legacy SAR（Recovery 模式）
   fastboot flash recovery magisk-patched.img
   fastboot reboot
   ```

7. 开机后：这台设备就是 Magisk。用 **Magisk 官方 App** 授权 root 与管理模块；
   管理器侧的**超级用户**页也能管 Magisk 的 root（见第六节），模块目录两边一致
   （`/data/adb/modules`）。
8. 第一次打开管理器的超级用户页（或任何需要 root 的功能）时，Magisk 会弹一次授权框 ——
   **批准 XECKernelPro**。批完之后管理器自己的 root 也就记在同一张表里，两边都能看见、
   都能撤销。

---

## 四、开机后怎么自检

```sh
# Magisk 的运行日志
cat /cache/magisk.log

# Magisk 自己报的版本与 root 状态
magisk -V
magisk --path

# 模块目录（两边同布局）
ls /data/adb/modules
```

---

## 五、出问题怎么办

| 现象 | 处理 |
|---|---|
| 卡在第一屏 | 刷回原厂：`fastboot flash boot boot.img`（Recovery 模式则刷 `recovery.img`） |
| 脚本报 `Cannot find BusyBox` | 本 APK 没带全载荷（管理器单独构建的包会这样），换发版 APK |
| 脚本报 `Boot image patched by unsupported programs` | 你选的是已经打过 Magisk（或别的方案）的镜像，刷回原厂再打 |
| 刷完没有 root | 确认刷的是**打过补丁的那份**镜像、且分区选对（boot / recovery） |
| 管理器里点授权没反应 | 第一次的授权框在 Magisk 那边（官方 App 或它自动装的隐藏 stub），去那里点"允许" |
| 想彻底卸载 | 刷回原厂镜像，删掉 `/data/adb/magisk` 与 `/data/adb/modules` |

---

## 六、管理器的「超级用户」页：两边都能管

5.10 以下的设备上，root 是 Magisk 的 root，所以管理器的**超级用户**页显示的就是
Magisk 自己的策略表（`/data/adb/magisk.db` 的 `policies` 表），通过 Magisk 官方 CLI
（`magisk --sqlite` / `magisk --denylist`）读写，**不另建一份名单、不缓存授权**：

| 管理器里能做的 | 对应 Magisk 的语义 |
|---|---|
| 每个应用的权限：**询问 / 允许 / 拒绝 / 受限** | `policies.policy` = 0 / 2 / 1 / 3（3 = 受限，丢弃全部 capability） |
| **通知** 开关 | `policies.notification` |
| **日志** 开关 | `policies.logging` |
| **撤销** | 删掉 `policies` 里那一行（下次调用 su 会重新询问） |
| **Zygisk** 开关 | `settings` 表 `zygisk`（由 magiskd 开机装载，**改完要重启**） |
| **DenyList 强制**开关 + 名单条数 | `magisk --denylist enable/disable/status/ls`（名单本身在 Magisk App 里配） |

**双向**：Magisk App 与管理器读写的是同一张表 ——

- 在管理器里把某个 App 改成"允许"，Magisk App 的超级用户列表立刻一样；
- 在 Magisk 里授权/撤销，回到管理器超级用户页（每次切回本页都会重读）也一样；
- **本管理器自己的 root 也在列表里**（标着「本管理器」）：Magisk App 能看见它、能撤销它，
  管理器这边也能改它 —— Magisk 的授权框永远优先，管理器不会绕过它自己发 root。

一句话：管理器不是"另一套 root 管理器"，它是 Magisk 策略表的一个前端；Magisk App 同理。
两个前端共用一个后端，所以不存在"谁能管谁"的问题。

> 授权框由谁弹？Magisk 只认自己签名的管理器（官方 App 或它的隐藏 stub），所以**授权框
> 一定来自 Magisk 那边**，本管理器不会（也不能）绕过它自己发 root。设备上没有 Magisk App
> 时，Magisk 会按官方机制自动装一个隐藏的 stub 管理器来弹框 —— 第一次用到管理器里需要
> root 的功能时点"允许"，本管理器就会作为发起方被记进这张表。

---

## 七、模块

- **列表**来自 `/data/adb/modules`（两边同布局，管理器和 Magisk App 看到的是同一份）。
- **安装**：管理器里刷入模块时走的是 **Magisk 官方安装器**（`magisk --install-module`），
  模块自己的 `customize.sh` 用的是 Magisk 的 `ui_print` / `set_perm` / `replace` 语义，
  由 Magisk 自己跑才对；内核态设备那条路才用 `ksud module install`，两条路线不混用工具链。
- **启用 / 禁用 / 卸载**：两边共用同一套标记文件（模块目录下的 `disable` / `remove`），
  所以在管理器里操作，Magisk 下次开机同样生效。

---

## 八、已知限制

1. **DenyList 名单本身**（哪些应用进名单）仍在 Magisk 官方 App 里配置；管理器显示强制
   状态与条数。
2. **没有真机验证过**（这套流程的每一步都是官方脚本在做，但我们没有 <5.10 的设备实测过
   管理器这一层）。首次刷机请守着、并留好原厂镜像。
3. **Magisk 是 GPL-3.0**：随 APK 分发的载荷原样取自官方 v30.7（未修改），版本与逐文件
   sha256 在 CI 里钉死，署名与源码见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)；
   策略表结构、取值与 CLI 行为属于**接口事实**，据此自行实现，没有搬运 Magisk 的实现代码。
4. 自研的挂载态实现（`xudc ramdisk-patch` + `xecinit`）仍留在仓库里作实验，**不再从界面提供**。
