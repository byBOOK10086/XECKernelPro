# NOTICE

本目录为 **Tricky Addon Enhanced**（模块 id `TA_enhanced`），由 XECKernel Pro 管理器核心内嵌分发。

- 上游：https://github.com/Enginex0/tricky-addon-enhanced
- 作者：Enginex0（目标列表相关组件见 https://github.com/KOWX712/Tricky-Addon-Update-Target-List ）
- 许可证：GPL-3.0（与本仓库根 LICENSE 同许可证）

## 源码合编译声明

本目录**不包含任何 GPL 预编译二进制**。以下产物由 CI 从本仓库
`third_party/tricky-addon-enhanced/`（上游 `v5.27.0`，commit `1951ba023d100b427ba7b321074808a89106889e`
的原样快照）源码构建：

- `bin/arm64-v8a/ta-enhanced` —— `third_party/tricky-addon-enhanced/rust/`
- `bin/arm64-v8a/resetprop-rs` —— `third_party/tricky-addon-enhanced/external/resetprop-rs/`
  （resetprop-rs，Enginex0/resetprop-rs，**MIT**，Copyright (c) 2026 Enginex0，
  固定 commit `4646d28a2de49c139025abc5f6d0357cd863320d`）

`bin/arm64-v8a/aapt` 为 AOSP 预编译工具（**Apache-2.0**，
https://android.googlesource.com/platform/frameworks/base/ ），按上游模块原样随包分发。
其余 `*.sh`、`common/`、`more-exclude.json`、`webui/` 为本目录内可读源文件/配置
（`service.sh` 等为适配本仓库内置模块具体化机制的重写版）。

本目录下列文件为**本项目新增**（上游没有对应文件，不含上游代码，随本模块一并按
GPL-3.0 分发；版权归 XECKernel Pro，新增日期 2026-10-08）：

- `common/bootstate.sh` —— bootloader 回锁态伪装（最早生效 + 看护 + 取证）
- `post-mount.sh` —— late-load 路径（不经过 post-fs-data）下的锁态补位

> 2026-11-13：`common/keybox.sh`、`keybox_mirrors.txt`、`keybox_trust.txt`、
> `keybox_roots.txt` 及配套的 keybox 自动获取/镜像刷新已从本模块移除，
> 密钥拉取与可信核验改由 TEE 引擎侧承担（详见本文件末尾的移除记录）。

原随包的 `common/vbhash_extractor.apk`（无源码、无许可证声明、脚本亦无引用）已移除；
vbhash 提取功能由源码构建的 ta-enhanced 守护进程（`rust/src/vbhash/`）承担。

## 本地修改（GPL-3.0 §5(a) 要求的改动声明）

**本目录下的 `*.sh` 与 `common/*.sh` 是修改版，不是上游原样文件。** 上游对应用户态部分为
`rust/`（二进制由 CI 构建，见上），模块脚本原本服务于上游自己的模块目录布局；
本项目把它们改写成"内置模块"（`/dev/.xudc_hidden/TA_enhanced`、数据目录
`/data/adb/.xudc_secure`）形态，并补齐本项目的可靠性修复。改动者：**XECKernel Pro**。

- **2026-10-07 起**：`service.sh` / `prop.sh` / `propclean.sh` / `post-fs-data.sh` /
  `common/common.sh` 全面适配内置模块布局（`MODPATH` 改用内置隐藏目录、`TS_DIR` 指向
  `/data/adb/.xudc_secure`、二进制 staging 到 `/data` 后运行）。
- **2026-10-07**：`service.sh` 增加 keybox 自动拉取配置下发（yurikey、3 分钟周期）、
  开机后维护任务的超时上限；`common/common.sh` 增加 `resetprop` 解析阶梯。
- **2026-10-08**：`common/common.sh` 的 `resetprop` 兜底重写为四级阶梯（绝对路径
  `/data/adb/ksu/bin/resetprop` → PATH → `resetprop-rs` → 只读包装），并把 `-w/--wait`
  改为自实现的 getprop 轮询（此前缺 `-w` 的档位会把 `resetprop -w sys.boot_completed 0`
  退化成一次写操作，把 `sys.boot_completed` 写成 0）。
- **2026-10-08**：`service.sh` 把 60 秒 keybox 镜像循环升级为 **keybox 守护**（每 5 秒）：
  每次 ta-enhanced 守护进程成功拉取 keybox 后，上游 `keybox/mod.rs::install_data` 会把
  `/data/adb/tricky_store/keybox.xml` 落成 **0600 root**，而读它的 `KeyBoxManager` 运行在
  **keystore2 进程（uid keystore）** 内——权限位打不开，engine 只能退回软件级证书链。
  守护把权限钉回 644（与本仓库内置 tricky_store 的播种权限一致）、校验失败时强制重拉、
  按"实时 → 兼容副本"单向镜像。**vendor 源码保持零修改。**
- **2026-10-08**：`prop.sh` 的收尾校验改为两遍（不一致先补写再复查）并记录实际使用的
  `resetprop` 后端；ZeroMount 豁免由 INFO 升为 WARN 并写明"此开机不伪装 bootloader 状态"。
- **2026-10-08（中国大陆可用性 + 回锁态，本文件列入的改动范围最大的一次）**：
  1. `service.sh` 的 keybox 守护换实现（1s 权限看护 + 15s 可信度巡检 + 多镜像刷新）。
     **该实现已于 2026-11-13 整体移除，见文末记录。**
  2. 新增 `common/keybox.sh` + `keybox_mirrors.txt` + `keybox_trust.txt`（国内可达镜像、
     base64 源自动解码、Google 链指纹核验、按代次防降级）。
     **这四个文件已于 2026-11-13 删除。**
  3. 锁态逻辑拆到新增的 `common/bootstate.sh`：`prop.sh` 不再在脚本开头
     `resetprop -w sys.boot_completed 0` 之后才伪装 bootloader 状态，而是由
     `post-fs-data.sh`（正常开机最早时机）与新增的 `post-mount.sh`（late-load 路径）
     提前落笔，`prop.sh` 保留第二次落笔与两遍校验，`service.sh` 再起一个 30s 看护循环
     补写被系统改回的 `sys.oem_unlock_allowed` 等属性；并把 `/proc/cmdline`、
     `/proc/bootconfig` 里仍暴露的真实启动状态写进日志。
  4. `service.sh` 在开机完成后追加一行总览日志（keybox 级别/DeviceID/权限/sha + 锁态
     属性 + 运行形态 LKM/内置/late-load），作为用户自查的单一入口。
- **2026-10-08（总览补三项"是否真的生效"、跳过不再静默）**：
  1. `service.sh` 的开机后总览追加 `zeromount=`、拦截列表条目数与"内置验机工具是否
     被覆盖"、引擎进程 `engine=`。原因是本次用户报的故障（验机工具报"未知认证根证书 /
     无效的信任根状态"）根因在**引擎的穷举白名单没覆盖那个包**，与 keybox / 锁态属性
     无关；总览里没有这两项时，日志读起来一切正常。
  2. `common/bootstate.sh` 新增 `BOOTSTATE_SKIPPED` 标记（`bs_zeromount_skip`），
     ZeroMount 豁免不再与"属性本来就正确"混为一谈；`post-fs-data.sh` 的计数行会显式
     打印 `SKIPPED (ZeroMount deferral active)`。
  3. `common/common.sh` 的 `read_config` 在 `$BIN` 缺失/不可执行时记一次 WARN（此前只
     静默返回默认值，配置项全部落到默认分支且日志无一字）。

- **2026-10-08（ZeroMount 豁免由"整组跳过"改为"窗口后补写"）**：用户报"密钥没问题了、
  引导加载程序依旧已解锁"。属性这条路径上，唯一能让**所有**锁态属性一个都不写的，
  就是 `bs_zeromount_skip` 的整组永久豁免（ZeroMount 生效时 `post-fs-data` / `post-mount`
  与它的挂载期重叠，上一版选择彻底放弃）。本次改为：窗口期内**推迟**并留痕
  （`BOOTSTATE_DEFERRED` / `bs_lock_write_allowed` / `bs_defer_or_proceed`），
  `service.sh` 的 30s 看护循环在挂载窗口结束后（第 2 个周期）一次性补写
  锁态 + vbmeta + 身份三组属性并复核（判定只看"ZeroMount 生效且窗口未打开"，
  不依赖跨进程传递的标记）；`bootstate_verify` 相应改为"窗口未关则推迟"，
  `post-fs-data.sh` 的计数行打印 `DEFERRED (ZeroMount mount window)`。

- **2026-11-10（构建指纹伪装 + 补丁刷新节奏）**：
  1. **新增** `pif.sh`（本仓库原创文件，不是上游文件）：读 `/data/adb/.xudc_secure/pif/`
     下的白名单属性表并在开机早期用 `resetprop` 写回，做 Play Integrity 需要的
     "整机身份自洽"那一半。脚本**不解析 JSON**（设备上没有 jq，sed 解析 JSON 出错会
     静默写坏全局属性），来源选择与 JSON 解析都在管理器里（`PifConfig.kt`，org.json），
     脚本只做白名单校验（只放行身份字段，`security_patch` 一律不碰，交给守护进程
     每 2 小时刷一次，避免两个写入方互相覆盖）。
  2. `post-fs-data.sh` 在锁态写入之后立刻应用一次指纹（早于 GMS 读属性）；
     `service.sh` 在 `sys.boot_completed` 之后补写第二遍（MIUI/HyperOS 会重算 identity），
     并在开机时把 `security_patch.auto_update` / `security_patch.interval=7200` 强制对齐。
  3. `service.sh` 额外把 staged 的 `resetprop-rs` 同步到
     `/data/adb/tricky_store/ta-enhanced/bin/`：v5.53.1 起 daemon 写属性改成 exec 这个
     **固定路径**（`rust/src/platform/props.rs` 的 `RP_PATH`），与我们自己的 staging
     目录不同，不补这份会让 region/补丁属性写入全部失败。
  4. `kb_vendor_verify()` 曾用于安装前用 daemon 的密码学校验挡掉坏候选；
     **随 `common/keybox.sh` 于 2026-11-13 一并移除。**
  5. `prop.sh` 补回自定义属性的执行：上游 v5.53.x 删掉了 `props.custom_props` 的 apply
     路径（只剩 `config props-custom` 打印 `name<TAB>value`），这里在 shell 侧按
     `ro./persist./sys.` 白名单前缀重新应用，其余前缀记 WARN 并忽略。
  6. `service.sh` 把 `module.prop` 同时复制到 `/data/adb/tricky_store/ta-enhanced/`：
     上游的版本显示 / status 上报 / WebUI 元数据读的是这个路径，只放我们自己的备份目录
     会让版本号落到 crate 版本、状态任务拿不到元数据。

- **2026-11-10（远程配置优先开关）**：service.sh 新增 $REMOTE_PRIO_OFF 门控 ——
  文件 /data/adb/.xudc_secure/remote_priority_off **不存在**时（默认），每次开机把
  security_patch.auto_update=true / security_patch.interval=7200 与
  keybox.enabled=true / keybox.interval=180 钉住；用户显式关闭后这些值以 WebUI 配置
  为准（日志写明"已由用户关闭"）。这是把"补丁/指纹/远程密钥逻辑优先于 TEE 引擎配置"
  这条裁定落成可切换的事实，而不是散落在各脚本里的隐式约定。

- **2026-11-10（keybox 健康度 + 防降级；本项目首次改动 vendor Rust 源码）**：
  1. `rust/src/keybox/validate.rs`：`KeyReport` 新增 `days_left`，`ValidationReport` 新增
     `leaf_days_left` / `expires_soon`（叶子剩余天数，14 天内告警）。起因是 2026-11-10 实测：
     内置 Yurikey 箱子的叶子 **2026-11-13 16:32:13 UTC** 到期，而 `fetch()` 过去只验
     "链能不能验过"，临期/过期箱子仍会照常下发——检测工具收到的每一张伪造链都带过期叶子，
     比不伪造更糟。
  2. `rust/src/keybox/mod.rs`：新增 `keybox health` 子命令（输出一行 `ok days_left root_type`，
     供开机脚本做路由判定）；`fetch()` 增加**防降级**：候选箱子的叶子比在用箱子更早到期时
     拒绝安装（在用箱子已不合法时不拦）。
  3. `rust/src/cli/mod.rs`：注册 `keybox health`。
  以上是本项目对 vendor Rust 源码的第一处改动（此前 Rust 侧保持零修改），按 GPL-3.0 §5(a)
  在此声明；`tricky_store` 的 `service.sh` 用它决定验机工具是否走伪造链。

许可证不由本项目改变：本模块整体仍按 **GPL-3.0** 分发，对应源码（含上述修改）随本仓库
一同提供；完整对应源码获取方式见根目录 `THIRD_PARTY_NOTICES.md`。

版权归原作者所有；许可证全文见上游仓库与 `third_party/tricky-addon-enhanced/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。

## 2026-11-13：移除本项目的 keybox 守护与 TEE 自检胶水

为接入 OhMyKeymint（作者已同意集成），本项目自写的 keybox/自检层整体撤下，TEE 引擎本体
（`third_party/TEESimulator-RS`、内置 `tricky_store` 载荷、TA_enhanced 守护）保持不变：

- 删除 `common/keybox.sh`、`keybox_mirrors.txt`、`keybox_roots.txt`、`keybox_trust.txt`。
- `service.sh` 移除 keybox 自动获取配置块与 1s/15s 守护，也不再改写 daemon 的
  `keybox.source`/`custom_url`/`interval`；密钥获取交回引擎自身的配置。
- 移除 `.github/workflows/update-keybox.yml`、`.github/scripts/refresh-keybox.sh`
  以及 `ksud.yml` 里的刷新步骤；模块只保留构建时的出厂副本。
- 移除管理器里的 TEE 自检面板（`TeeStatusSection.kt`）及其在工具箱的入口；
  指纹自检（`PifSection`）保留。
- `bootstate.sh` / `bootstate.rs` 属锁态伪装，不在本次移除范围。