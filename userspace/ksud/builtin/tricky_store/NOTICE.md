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
  版本标记 `.engine_version` 最后落盘（中途失败下次开机重试）、外部模块在位时主动让位。
- **2026-10-15（去指纹 + 自检自愈）**：
  1) **不再创建 `/data/adb/modules/tricky_store` 兼容目录**，并在开机时删除历史遗留的那份。
     它唯一的作用是迁就 DEX 里写死的 `NativeCertGen.initialize("/data/adb/modules/
     tricky_store/libcertgen.so")`；本批把那行改成从运行目录取（`App.kt`，从 vendored
     源码重建 DEX），TA_enhanced 判断"TS 是否安装"用的是它自己的 tmpfs 路径，不依赖它。
     该目录名是能读 `/data/adb` 的扫描器最容易命中的指纹。
  2) 引擎日志从 `$RUNTIME/.engine.log` 挪到 `/data/adb/.xudc_secure/engine.log`
     （带一次性内容迁移），日志里写着 DeviceID 与 keybox 指纹，是最强信号之一。
  3) 新增**开机自检 + 自愈** `tee_selfcheck`：每 2 分钟（以及开机 75s、看护循环、
     管理器触发文件）核对"引擎在不在跑 / keybox 链末是不是有效的 Google 根 / 验机工具
     是否在拦截名单 / 锁态属性有没有漂移"，能修的自己修（每类每次开机限额），结论写进
     `/data/adb/.xudc_secure/tee-selfcheck.txt` 供管理器「TEE 引擎与拦截」直接显示。
- **2026-10-15（用户可选 + 不再删用户的模块）**：
  1) 内置 TEE 从"开机必然运行"改成**用户可选**：开关落在 `/data/adb/.xudc_secure/builtin.conf`
     （`tee=1`），由管理器「检测」页或 `ksud builtin enable|disable tee` 维护，**默认关闭**；
     关闭时 ksud 既不解密本模块进 tmpfs，也不执行任何阶段脚本，运行目录同时被清掉。
     `service.sh` 自己在开跑前复查一次开关（运行期被关掉的情况下退出）。
  2) 新增 `service.sh --stop`：`ksud builtin disable tee` 会调用它——只收本模块自己的
     进程（`keymint_svc` 与可执行文件指向 `$RUNTIME/supervisor|daemon` 的那些），
     再把 keystore2 重开一份丢掉已注入的引擎代码，并把自检结论写成 `verdict=disabled`。
     **绝不再碰 `/data/adb/modules` 下的任何目录**。
  3) **修掉"用户自己刷的模块重启后消失"**：兼容目录清理过去是无条件
     `rm -rf /data/adb/modules/tricky_store`，而用户自己刷的上游 TrickyStore 用的正是
     这个目录名——一次 `--takeover` 之后它就从管理器的模块列表里没了。现在只删**能证明
     是我们那个壳**的目录（没有 `module.prop`、且只剩 `libcertgen.so`），有 `module.prop`
     的一律保持原样。
  4) 让位/接管的存活判据同时认两套进程名（本项目的 `keymint_svc` 与上游的
     `TEESimulator`），并按 `module.prop` 里的 id 与 `classes.dex + libTEESimulator.so`
     识别改名后的外部模块——只认一个名字会把"用户装的上游引擎活得好好的"判成零拦截，
     进而自动起第二个引擎（两套 grant ledger 互相打架）。
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
- **2026-10-08（引擎状态取证：`consistency.sh` 增补 `engine_log_snapshot`）**：
  在 `run_consistency_audit` 末尾加一段只读取证的逻辑——用
  `logcat -d -t 400 -s TEESimulator:V` 抓引擎自己写的两条 info 级判定（release 版也有）：
  「`TEE functionality check successful/failed`」决定 `target.txt` 里不带后缀的条目在
  AUTO 下会被解析成 PATCH（真机 TEE 签发）还是 GENERATE（链由模块 + 内置 keybox 合成）；
  「`Attestation patch levels for uid=N: …`」则是该 uid 的证书里实际报出的补丁标签。
  归纳结果写进模块日志（内容未变时不重复写，digest 存 `$RUNTIME/.engine_snapshot.digest`），
  TEE 不可用时额外给出「`包名?` 强制 PATCH / 整行移除不再拦截」两条可选对策；取不到
  `logcat` 时静默跳过，不做任何写入。

  动机：用户反馈「装了 v31026 后验机工具依旧报『检测到 TS 或类似模块』」。反编译内置
  验机工具（`wu.keyChain.test`）后确认它的判据分两层：证书内容层（补丁标签、boot hash、
  认证序列、根证书类型）与包裹层（`com.wuying.ErrCheck` 直接抓 keystore binder 应答
  包裹做指纹，见 `createDiagnosticServiceProxy` / `wrapSecurityLevelWithTransactCapture` /
  `captureGenerateKeyReply`）。前者我们能改，后者改不动；而"当前是哪种模式在给验机工具
  出证"必须能从设备上直接看到，否则无从判断该修哪一层。这段取证就是为此，且不需要
  用户在设备上敲命令。

- **2026-10-11（引擎本该更新却没有更新：本轮"刷入新版本仍旧解锁"的根因）**：
  `service.sh` 的资产刷新判据从上游 `module.prop` 的 `versionCode` 改为**资产内容戳**
  （对将写入的 `daemon`/`inject`/`supervisor`/`classes.dex`/`libTEESimulator.so`/
  `libcertgen.so` 取 sha256 前 16 位）。原因：`versionCode` 是引擎自己的版本号
  （162），我们改引擎源码不会改它，于是第一次把某一版引擎写进
  `/data/adb/tricky_store` 之后，此后每一次刷入新版都被判成"已是最新"——引擎侧的
  全部修复（grant 链一致性、RootOfTrust 多份伪造、`STALE_*` 覆盖、keystore2 原生
  回复形状）都停在仓库里，设备上跑的一直是第一次那份 `classes.dex`。现在内容一变
  就替换，并在每次开机把内容戳记进 `.engine.log`。
- **2026-10-11（stand-down 判定的 shell 优先级缺陷）**：`service.sh` 改为调用
  `external_engine_active()`。旧写法把六个 `test` 串成
  `A && B && C || D && E && F`，`&&`/`||` 左结合，等价于 `((A&&B&&C) || D) && E && F`
  ——外部 `tricky_store` 存在但**已被用户停用**（`disable` 文件在）时整体为假，
  本模块不 stand down，于是两个引擎同时向 keystore2 注入：两套 grant ledger、两套
  证书缓存互相打架。
- **2026-10-11（检测工具显式入表）**：`target.baseline.txt` 显式加入
  `com.eltavine.duckdetector`（本仓库做环境检测审计时的判定基准）与其它常见完整性
  检测器，不再依赖"桌面应用自动入表"能否正确解析 `cmd package query-activities`。
- **2026-10-11（引擎源码三处修改，见 `third_party/TEESimulator-RS/NOTICE`）**：
  `Keystore2Interceptor.kt` 的 `getKeyEntry` 后置钩子改为按 `shouldSkipUid()` 而非
  `shouldPatch()` 放行（原写法让 GENERATE 模式、以及"TEE 探测失败而降级成 GENERATE"
  的 AUTO 包完全拿不到实时补丁）；实时补丁结果只在**叶证书确实被重写过**时才写入
  缓存（`AttestationPatcher` 在 keybox 缺失/无 attestation 扩展时是 fail-open，
  会把真实链原样返回，缓存它等于永久钉死真实的（解锁态）RootOfTrust）。

- **2026-10-12（"bootloader 已锁定，但密钥认证仍报未知根证书"：播种/保留实时 keybox 时增加链末根核验）**：
  `service.sh` 的 keybox 播种段落新增 `_KB_ROOTS` / `_kb_root_hashes` / `_kb_root_ok` /
  `_kb_write_root_status`：判据与 `TA_enhanced/common/keybox_roots.txt` 同一份 Google 证明根
  指纹清单，比对对象是每个 `<Key>` 块**最后一张**证书（链末 = 根）。
  1. **为什么是本模块也要管**：验机工具只认链末证书的公钥（`identifyRootCertificateType()`
     与硬编码的 Google/AOSP/Knox 根公钥比对），不是 Google 根就报"未知根证书"；而链照样
     签得出来 —— 于是界面同时出现"bootloader 已锁定 + 未知根证书"。能造成它的箱子都不归
     本项目控制：`ta-enhanced keybox generate` 的自签盒（DeviceID="sw"）、TA WebUI 的 AOSP
     按钮（`common/.default`）、vendor 第三方源（KOWX712 `.extra` / MeowDump `ShockWave.tar`）。
  2. **改动**：实时副本"链末非 Google 根"而内置副本是 Google 根时 → **开机即换回内置副本**
     （旧逻辑只在"实时副本 == 上一版内置副本"或"结构不合法"时才动，一份第三方箱子一旦写进
     实时路径就会被永久保留 —— 这正是"刷了新版本仍旧未知根证书"的路径）；"保留拉取来的
     副本"这一分支同样以链末根为前提。判定不了（缺 `sha256sum`）时**不动**实时副本，
     避免在能力不足的设备上误降级。
  3. **可观测**：结论写入 `/data/adb/tricky_store/.keybox_root`
     （`google|other|missing|unknown 命中/总数 DeviceID sha16`），管理器"检测"页的
     TeeStatus 卡片新增「根证书」一行直接展示（红色即"检测方会报未知根证书"）。

许可证不由本项目改变：本模块整体仍按 **GPL-3.0** 分发，对应源码（含上述修改）随本仓库
一同提供；完整对应源码获取方式见根目录 `THIRD_PARTY_NOTICES.md`。

版权归原作者所有；许可证全文见上游仓库与 `third_party/TEESimulator-RS/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。
