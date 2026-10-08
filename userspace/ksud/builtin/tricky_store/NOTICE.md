# NOTICE

本目录为 **TEESimulator-RS**（模块 id `tricky_store`），由 XECKernel Pro 管理器核心内嵌分发。

- 上游：https://github.com/Enginex0/TEESimulator-RS
- 作者：JingMatrix、Enginex0
- 许可证：GPL-3.0（与本仓库根 LICENSE 同许可证）
- 技术源自 5ec1cff 的 TrickyStore（GPL-3.0）：https://github.com/5ec1cff/TrickyStore

## 源码合编译声明

本目录**不包含任何预编译二进制**。引擎产物由 CI 从本仓库
`third_party/TEESimulator-RS/`（上游 `v6.0.0-162`，commit `5267c9dd0092b69dee4a34eb0c8af8e617b2bc5d`
的原样快照）源码构建，并在本目录中生成：

- `classes.dex`、`libTEESimulator.so`、`libcertgen.so`、`inject`、`supervisor`
  —— 构建方式与对应源码见 `third_party/TEESimulator-RS/UPSTREAM.md`。

`daemon`、`service.sh`、`module.prop`、`sepolicy.rule`、`keybox.xml`、`target.txt`
为本目录内可读源文件/配置（`service.sh` 为适配本仓库内置模块具体化机制的重写版）。

内含第三方组件（随上游源码一并构建）：LSPlt（JingMatrix/LSPlt，LGPL-3.0）、
AOSP libbinder/libutils 头文件子集（Apache-2.0）、Linux 内核 UAPI 头文件子集（GPL-2.0）。

## 本地修改（GPL-3.0 §5(a) 要求的改动声明）

**本目录下的 `service.sh` 是修改版，不是上游原样文件。** 上游对应用户态部分
（`classes.dex`、`inject`、`supervisor`、`lib*.so`）由 CI 从 vendored 源码构建，
本项目不改其源码；改动集中在"把引擎作为内置模块跑起来"的启动脚本上。
改动者：**XECKernel Pro**。

- **2026-10-07 起**：`service.sh` 重写为内置模块形态——引擎资产从模块目录
  （`/dev/.xudc_hidden/tricky_store`）原子 staging 到运行目录 `/data/adb/tricky_store`，
  版本标记 `.engine_version` 最后落盘（中途失败下次开机重试）、外部模块在位时主动让位、
  DEX 里写死的上游兼容目录 `/data/adb/modules/tricky_store` 只放 `libcertgen.so`。
- **2026-10-08**：`service.sh` 增加启动前密钥取证：记录实时 keybox 的 sha/年龄/权限，
  识别"当前用的仍是模块自带出厂 keybox"，并在权限不是 644 时修正（读它的
  `KeyBoxManager` 运行在 keystore2 进程里，0600 打不开）。
- **2026-10-08（中国大陆可用性）**：`service.sh` 的 keybox 播种改为**自愈升级**——
  记录"上一次内置副本的 sha"（`$RUNTIME/.keybox_shipped`），若实时副本恰好等于上一版
  内置副本，而本版模块内置了不同的（构建时由 `.github/scripts/refresh-keybox.sh`
  从上游刷新打进来的）副本，就换成本版的：**刷写/更新模块本身即一次密钥刷新**，
  不需要设备端能连上 GitHub。反向保护同时具备：内置副本结构不合法、或级别/代次低于
  实时副本时不替换（避免构建产物回退把可信箱子换掉）。另外**取消**了
  `$DATA/keybox.xml`（TA 侧兼容副本）的自动回填：TA 的守护会把"兼容副本被外部改动"
  当作用户意图（WebUI 粘贴自定义 keybox 走这条路），回填旧内置副本会造成降级。
- **2026-10-08（诊断可见性）**：`service.sh` 的启动取证改为记录 `DeviceID`
  （yurikey 代次，如 `Yurikey58`）、结构核验结论与"是否仍为构建时那份"，
  便于把"密钥没传过来"定位到具体是哪一份、多旧。
- **2026-10-08（拦截白名单：本次"刷入后依然不可信/解锁"的直接修复）**：新增
  `engineconf.sh`（可离线单测的纯函数）与 `target.baseline.txt`，`service.sh` 改为
  source 前者并在启动引擎前做四件事：
  1. 缺失才播种（保留用户既有列表）；
  2. 把 baseline 里缺失的包**只增不删**地追加进 `$RUNTIME/target.txt`；
  3. 让 `$DATA/target.txt`、`$DATA/security_patch.txt`（管理器/WebUI 写入的路径）
     与引擎实际读取的 `$RUNTIME/*` **双向对账**，并在启动后每 15s 复检一次；
  4. 记录覆盖取证（条目数 + 内置验机工具是否在表内）。

  根因：引擎的拦截是**穷举白名单**（`config/ConfigurationManager.kt` 的
  `shouldSkipUid`：UID 不在表里直接跳过），而本模块内置的验机工具
  `wu.keyChain.test` 从来不在默认 11 条里，管理器/WebUI 改的又是另一个路径——
  于是验机工具拿到的是**真实 TEE 证明**，界面必然报"未知认证根证书 /
  无效的信任根状态"。另外 `service.sh` 末尾那句无条件的
  "supervisor started" 改为启动后回读（进程是否活着 + 引擎 logcat + 覆盖数），
  不再把"命令没报错"当成"引擎已生效"。
- **2026-10-08（GPL-3.0 §5(a) 文件级声明）**：新增文件 `engineconf.sh`、
  `target.baseline.txt` 由本项目编写，非上游文件；`service.sh` 对应上述改动。
- **2026-10-08（桌面应用自动入表 + 证书参数自洽化）**：
  1. 新增 `target_autofill.sh`（本项目编写，非上游文件）：把「桌面上有图标的应用」
     自动补进 `target.txt`。取包名走 `cmd package query-activities --brief -a android.intent.action.MAIN -c
     android.intent.category.LAUNCHER`，老设备回退 `dumpsys package`；只增不删、
     容忍 `!` / `?` 后缀、原子写（tmp + mv 触发引擎的 ConfigObserver）、
     可离线单测（`XEC_LAUNCHER_CMD` / `XEC_THIRDPARTY_CMD` 注入）。
     配置 `target_autofill.conf`：`enabled` / `mode=launcher|thirdparty|launcher3` /
     `suffix`；`$RUNTIME/.no_target_autofill` 可整体关闭。开机跑一次，之后每 5 分钟复扫。
  2. 新增 `consistency.sh`（本项目编写）：证书参数自洽化体检 ——
     `security_patch_audit` 把 `security_patch.txt` 里与本机安全补丁**月份级不一致**的
     显式日期改回 `system=prop`（改前备份为 `*.bak.<时间戳>`），因为"证书补丁标签与
     系统属性不一致"正是社区验机工具判定「检测到 TrickyStore 或类似模块」的公开口径；
     `boot_hash_audit` 用 `boot_hash.bin` / `boot_key.bin` 回写
     `ro.boot.vbmeta.digest` / `ro.boot.vbmeta.public_key_digest`，让检测方的
     `getprop` 与证书里的 `VerifiedBootHash` / `VerifiedBootKey` 一致
     （`od` 必须带 `-v`，否则重复行会被折叠成 `*` 导致整条回写静默失效）。
  3. `service.sh`：source 上述两个脚本，开机与循环里分别执行；每 60s 复核一次自洽性。

  动机：社区文档（春秋检测项解决方案）对同类检测项的处置口径是「换密钥模块 / 删除
  `security_patch.txt` / 对齐 boot hash」，本项目的引擎已是其推荐模块，于是把剩下
  两条做成默认行为，而不是让用户手工跑命令。

许可证不由本项目改变：本模块整体仍按 **GPL-3.0** 分发，对应源码（含上述修改）随本仓库
一同提供；完整对应源码获取方式见根目录 `THIRD_PARTY_NOTICES.md`。

版权归原作者所有；许可证全文见上游仓库与 `third_party/TEESimulator-RS/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。
