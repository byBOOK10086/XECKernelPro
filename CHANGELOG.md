# 更新日志

本文件记录 XECKernelPro 管理器的用户可见变更。历史版本由 CI 的 `generate_release_notes` 自动汇总，本文从全局 UI 重写（XEC Fluid Glass）开始做人工整理。

---

## 未发版 · TEE 注入链修复：运行期开引擎必须重打 keystore 侧 sepolicy + 全链可诊断

> 类型：事故修复（用户实测："开关打不开内置模块，检测页还是老样子"）

### 根因：换了"用户可选"之后，注入链缺了一环

引擎的拦截靠**注入**，不是靠"进程在跑"：守护进程（`app_process` 跑的 Kotlin/JVM 进程）
用 `exec ./inject $(pidof keystore2) libTEESimulator.so entry` ptrace 进 keystore2，让
keystore2 `dlopen` + mmap `/data/adb/tricky_store/libTEESimulator.so`，由那个库在
keystore2 里装 binder hook。`supervisor` 只是个 fork/重启壳子（5 行 C 代码），不碰 keystore2。

这一链有两处前提以前没人管：

1. **keystore2 必须能读/mmap 那个 .so** —— 靠模块 `sepolicy.rule` 里的
   `allow keystore {adb_data_file shell_data_file tmpfs} file *`。这几条以前**只在开机
   post-fs-data 加载一次**；v31128 把内置模块改成"用户可选、默认关闭"之后，用户在检测页
   打开开关走的是 `ksud builtin enable tee`：它解密、启动 `service.sh`，**却没人再补这几条
   规则**。keystore2 打不开 .so → 注入失败 → 守护进程重试 5 次后 `exitProcess(1)` →
   supervisor 500ms 后拉起 → 再失败……设备上就是"内置模块怎么都起不来"，日志里只有一行
   `Failed to find backdoor after 5 retries`。
2. **没有任何界面能看出"注入没成功"** —— 以前只看 `pidof keymint_svc`：采样点撞上就报
   "运行中"，撞不上就报"引擎没跑"，两种都不说明原因，而且"进程在跑但没注入"这种**零拦截**
   状态会被显示成一切正常。

### 修复

- **`ksud builtin enable tee` 现在会重打该模块的 `sepolicy.rule`**（`apply_builtin_sepolicy`），
  并在解密前先清空运行目录 —— 后者修掉"materialize 不覆盖已存在文件"导致的老文件残留。
- 新增 **`ksud builtin repair <tee|ta>`**：重打 sepolicy + 重新解密一份干净的运行目录 +
  收掉正在反复重启的旧引擎 + 以 `service.sh --repair` 干净重启（脚本会核对注入状态，
  必要时**换一个干净的 keystore2 进程**再注入）。检测页与 TEE 看板各有一个「修复引擎」按钮。
- 引擎资产完整性变成**硬错误**：`store_builtin_blob` 先检查 `classes.dex` /
  `libTEESimulator.so` / `libcertgen.so` / `inject` / `supervisor` / `daemon` / `sepolicy.rule`
  等是否真的被打进这一版 ksud（它们不在 git 里，由 CI 的 `build-builtin-modules.sh` 生成；
  任何一次"没跑那个脚本就编译"都会得到一个"能跑但引擎永远起不来"的 ksud），
  `ksud builtin status` 里也会带 `assets=` / `runtimeassets=`。
- **注入状态成为一等事实**：设备侧自检与看板都会检查
  `/proc/$(pidof keystore2)/maps` 里有没有 `libTEESimulator` —— 这是"到底拦不拦得住"的
  唯一硬证据；自检新增 `not_injected` 结论，并在发现"引擎在跑但没注入 / 守护进程反复重启"
  时**自动重启整条注入链**（每次开机限额，必要时换 keystore2）。
- **启动诊断落到文件**：`service.sh` 把"走到哪一步、结果如何"写进
  `/data/adb/.xudc_secure/engine-start.txt`（TA 侧是 `ta-start.txt`），开机 / 手动 / 修复
  三种模式都写；看板与开关卡片直接显示。以前脚本里十几处 `|| exit 1` 一退出就什么都不写。

### 管理器侧：开关不再依赖设备上那份 ksud

- 检测页的开关改走**管理器自带**的 ksud（APK 里的 `libxeccli.so`）。设备侧那份是内核带着
  装的，只更新管理器时它可能还是旧的（不认 `builtin` 子命令），于是"开关点了没反应"。
- 同时探明**内核侧那份** ksud 是否支持开关：不支持就明确写出"本次开关只在当前这次开机内
  有效，重启后会失效，请一并更新内核 / LKM"。
- 挂载态（<5.10 Magisk 路线）本来就跑不了内置模块，卡片直接说明并让用户改用 Magisk 模块。
- 四种失败不再塌成同一句话：命令失败 / 资产缺失 / 运行目录不完整 / 注入失败，
  各自的文案与出路分开；开关动作改为**重读状态核对**（以前 `disable` 对已关掉的模块也返回
  成功，会弹出一句"已关闭"却什么都没发生）。


---

## v31128 · 2026-10-16 · 内置 TA / TEE 改为用户可选，并修掉「刷入的模块重启后从列表里消失」

> 类型：行为变更（内置 TA/TEE **默认关闭**，开关在「检测」页）+ 事故修复

### 事故：自己刷的 TA / TrickyStore 模块，重启后从管理器的模块列表里消失

用户实测报告：刷入 TA 与 TEE 模块、重启之后，那两个模块在管理器的模块列表里没有了。
根因是内置模块脚本里三条**无差别**的删除/标记路径，受害者都是用户自己装的那一份：

- `tricky_store/service.sh` 的兼容目录清理是无条件 `rm -rf /data/adb/modules/tricky_store`。
  那个兼容目录（只放一个 `libcertgen.so`、没有 `module.prop`）是本项目以前造的，但
  **用户自己刷的上游 TrickyStore 用的正是同一个目录名** —— 一次自动接管（外部模块占位
  但引擎没起来时的 `--takeover`）之后，用户的模块目录就被删掉了。
  现在只删**能证明是我们那个壳**的目录（没有 `module.prop`，且除 `libcertgen.so` 外没有
  别的东西）；带 `module.prop` 的一律保持原样并写日志。
- `TA_enhanced/post-fs-data.sh` 在找不到 TrickyStore 时会 `rm -rf /data/adb/modules/
  TA_enhanced` 再建一个带 `remove` 的目录，或者 `touch "$MODPATH/remove"`（上游的
  "自毁"逻辑）。模块被标上 `remove` 之后下一次开机就被 ksud 清掉，列表里也就没了。
  现在只记日志，不删、不标记。
- `TA_enhanced/service.sh` 的「Magisk Module Hiding」只看 `action.sh` 在不在，而
  KernelSU / APatch 的模块同样有 `action.sh`：在内核态设备上它会把自己拷成
  `/data/adb/modules/.TA_enhanced`，然后删掉可见目录 `/data/adb/modules/TA_enhanced`。
  现在隐藏只在 `MANAGER=MAGISK` **且**脚本确实从 `/data/adb/modules/` 下执行时才做。

同时修掉一个会让上面的删除被触发的误判：让位/接管的存活判据原来只认本项目引擎的新
进程名 `keymint_svc`，而**用户装的上游 TEESimulator / TrickyStore 的引擎进程叫
`TEESimulator`** —— 于是"外部引擎活得好好的"被判成"外部模块占位但零拦截"，内置那份
自动 `--takeover` 起第二个引擎（两套 grant ledger、两套证书缓存互相打架）。现在存活
判据同时认两个名字，外部模块识别也扩到"目录里带 `classes.dex` + `libTEESimulator.so`
的任何已启用模块"与 `module.prop` 里的 id，改名/换目录名都能认出来。

### 内置 TA / TEE 改为用户可选（两个互相独立的开关）

- 位置在**检测页**第二张卡「内置模块（用户可选）」，两个开关各自独立：开 TEE 不会顺带
  开 TA，关 TA 也不动 TEE。默认**都是关闭**：内置能力不再默认接管设备。
- 开关落在 `/data/adb/.xudc_secure/builtin.conf`（一行一个 `key=1|0`），三处共用同一份：
  ksud 开机时决定要不要解密/执行、模块脚本运行期复查、管理器渲染开关。
- 新增 `ksud builtin status|enable|disable <tee|ta>`：`status` 输出 JSON（开关、运行目录、
  外部模块），`enable/disable` 写完开关**立刻**生效——enable 现场解密 + 起引擎，
  disable 走模块自己的 `--stop`（收进程、重启一次 keystore2 丢掉已注入的引擎代码、
  自检结论写成 `verdict=disabled`），然后把运行目录删掉。
- 关掉的内置模块在设备上不留痕迹：不解密进 `/dev/.xudc_hidden`、不执行任何阶段脚本、
  不贡献 sepolicy/system.prop/init rc，看护循环（自检、keybox 守护、回锁守护、15s 对账）
  在下一次 tick 自行退出。挂载态（Magisk 路线）没有内置模块，卡片会说明"这一项不适用"。

### TEE 看板：分得清「关掉了」和「没跑」

- 设备侧自检新增 `verdict=disabled`；看板的引擎、根证书、拦截名单、引擎文件、补丁失败
  各行在内置 TEE 关闭时不再标红（那读到的是上一轮留下的状态），并给出「内置 TEE 已被
  你关闭」的归属行与一句说明，而不是继续报"引擎未运行"的故障文案。
- 「接管引擎」按钮的判据改成"没有任何引擎在跑、且用户没关掉内置 TEE"；按钮会先把开关
  打开再 `--takeover` —— 开关文件是唯一权威。

### 中文化

- 把 9 个**只写在英文里**的字符串补上中文并清空 `check_strings_parity.py` 的历史豁免
  白名单（此后任何新 key 只写英文都会直接失败）：`App Profile`（应用配置）、
  `ADB Root`（ADB Root 权限）、SU 日志的四类事件名（Root 执行 / 经典 su / 授予 root /
  守护进程重启）、模块 WebUI 终端的三句（正在启动模块 Web 服务…等）。
- 检测页新增的开关卡片全部走中文资源，`TA_enhanced` / `tricky_store` 这类内部 id 不再
  出现在界面上。

### 守卫

- 新增 `_scratch/check_builtin_modules.py`：钉死"内置模块必须按开关运行、且任何脚本都
  不许无条件删 `/data/adb/modules/<id>`"（含 ksud 侧闸门、CLI 子命令、两个模块的
  `--stop`、看护循环闸门、管理器卡片与中英字符串）。
- `check_tee_contract.py` 放行两类**探测**目标（上游 `TEESimulator`、`keystore2`），
  本模块自己的身份仍然只允许 `keymint_svc` 一个名字。

### 待验证

- 以上都是静态契约与本地守卫的结论：真机仍需确认「关闭后 keystore2 重启能恢复真实
  证明」「开启后引擎能在运行期接管」「两个开关切换后重启仍然生效」。

---

## v31121 · 2026-10-15 · 主题系统与主题商城 + TEE 开机自检/去指纹

> 类型：新增功能（对外可见：设置页多一个「主题」入口，整个界面的配色/圆角/玻璃/背景都能换）

### 新增：整套换肤（配色 + 圆角 + 玻璃 + 背景）

- 设计令牌层多了一个**主题入口**：`XcTheme` 现在可以接收一套 `XcThemeData`，一次换掉颜色、
  形状、玻璃三组令牌 —— 全 App 42 个文件、237 处 `Xc.colors.*` 一行都不用改，切主题时整棵树
  重组一次即可（这正是当初把令牌做成 `CompositionLocal` 的用意）。
- 玻璃第一次变成可调：新增 `XcGlass`（模糊半径/折射/覆色浓度/亮边/高光/暗部/光泽，**全是倍率**，
  默认 1.0 = 今天的样子），玻璃壳层与透明玻璃各自读它。主题只在这个基础上做整体加减，
  不写死绝对值 —— 否则任何组合下都得重新论证文字可读性。
- 模块页那套霓虹板（`XcNeon`）保持它"不外溢"的约定：主题**不写就不动它**，写了才覆盖。
- 背景图沿用现成的壁纸链路（根层壁纸就是全 App 玻璃的折射源）：主题带图时写进
  `WallpaperStore` 并落 `wallpaper_light/dark`；槽位前缀带主题 id，所以**不会删掉用户自己选的背景**，
  「恢复默认外观」也会把用户原来那张还回去。

### 新增：主题商城

- 默认源是自建主题仓库 `byBOOK10086/XECKernelPro-Themes`（jsDelivr → GitHub raw 两级回退，
  与模块仓库同一套口径），索引里的每个包都带 `sha256`，下载后校验、不匹配当场丢弃。
- 卡片直接给出缩略色块（索引里带 `accent`/深浅两档底色）、作者、版本、体积，以及
  「下载 / 应用 / 可更新 / 已安装」四种状态；下载走私有目录 + 进度回调，不落公共 Downloads。
- 主题包走 `RemoteDownloader`（一次自动重试 + zip 头校验），装进 `filesDir/themes/<id>/`。

### 新增：主题编辑器

- 基于**当前外观**改：深/浅两档各 15 个颜色令牌逐项可改（点色块出取色器：预设色 + 手填
  `#RGB/#RRGGBB/#AARRGGBB`）、圆角 7 档、玻璃 7 个倍率、背景图两张（相册选图）、
  模块页霓虹板 25 个槽位（可选）。
- 只把**改过的**令牌写进包：未改的沿用内置默认，于是同一份主题在旧版管理器上也能装
  （未知键忽略、缺失键取默认）。
- 元数据（名称/ID/作者/版本/一句话简介/详细说明）跟着包走；ID 有正则校验，重名会提示覆盖。

### 新增：自己发布，别人下载

- 「导出主题包」走系统文件选择器存 `.xectheme`（清单里没有任何存储权限，写公共目录会直接失败）；
- 「发布到主题商城」把包传到 XEC 网盘（与远程模块助手同一条 OpenList 通道，**不需要 GitHub 账号**
  就能拿到直链），随后弹出提交表单入口 —— 在主题仓库开一个 issue，机器人校验（zip 结构、
  体积/条目上限、禁止可执行条目、路径穿越、id/版本必填）通过后收进索引、重建 `themes.json`
  并回帖，管理器刷新即可看到。
- 主题仓库同时带上 `sources.json`（作者自己托管直链）与示例主题，方便别人照着做。

### 其它

- 守卫新增 `_scratch/check_theme_contract.py`（92 条）：把主题包格式（App 与仓库脚本的上限、
  id 正则、禁止条目）、索引字段、令牌注入点、换肤链路（应用/恢复/不误删用户背景）钉在一起；
  `check_kt_imports.py` 与括号/编码扫描也扩到 `data/theme`、`ui/design`（原先只扫 `ui/screen`）。
- `WallpaperStore` 多两个入口：从本地文件导入背景、按槽位清理（主题专用）。
- `Downloader.kt` 多一个 `sha256Of(file)`（主题包是文件、不是 content Uri）。

### 内部调试后的三条修复（用户实测反馈）

- **商城下载不再吊死在 jsDelivr 单点上**：索引里的包地址原本只有一个域名，CDN 一抽风就彻底装不上。
  现在会在 `cdn/fastly/gcore.jsdelivr.net` 与 `raw.githubusercontent.com` 之间互推候选逐个重试；
  **sha256 不匹配也会换下一个镜像**（不同边缘节点缓存不同步时正是这么救回来的），全部候选都失败才丢弃。
- **自定义主题源不再被偷偷顶替**：原先即使用户自己填了源，失败后仍会回退到官方索引；现在只有用内置源时
  才追加官方 raw 兜底，自定义源只走它自己推导出的镜像。
- **异常退出留下的半包不再冒充主题**：本地主题目录里点开头的 `.staging-*` 一律跳过（只跳过不删，
  避免与正在进行的安装抢目录）。

### 发布路径重做：真·一键上传，不需要 GitHub 账号

- 入口长在了正确的地方：**商城页顶部新增「发布我的主题」**（此前入口只藏在「我的主题」的卡片按钮里，
  用户反馈的原话就是"主题商店无法上传"——因为根本没找到）。点开是「选择要发布的主题」列表。
- 包传到网盘 `/uploads/pending/` 后，主题仓库的定时任务每 10 分钟扫一次，按**内容 sha256 去重**后
  自动收进索引——**不需要打开 github.com、不需要 GitHub 账号**（国内手机上那一步常年打不开，
  这正是"上传了但商城一直不出现"的主因）。开 issue 提交那条路仍然保留（想留言/要回执时用），
  两条路走的是**同一份收货代码**。
- 每类上传（含被拒的）记在 `pending-seen.json` 里，避免每轮重复下载；被拒的包不会进仓库。

### 新增：首页每张卡片可以各设背景

- 主题包新增 `blocks` 段：给首页六张卡片（状态卡 / 更新卡 / 警告卡 / 提权入口 / 支持链接 / 信息卡）
  各自设**颜色或图片 + 不透明度 + 压暗**，还能只对深色或浅色档生效。
- 编辑器新增「区块背景」区，逐张卡一行：色块取色器、选图/清图、透明度与压暗调步、明暗档三选一、重置。
- 认不出的区块 id / 字段一律安静忽略：新版主题在旧版管理器上照样能装，只是少一块背景。

### TEE（密钥证明）开机自检与自愈

- 引擎侧新增 `tee_selfcheck`：开机 75 秒、看护循环、以及每 2 分钟各核对一遍
  **引擎进程 / keybox 链末是不是有效的 Google 根 / 验机工具在不在拦截名单 / 锁态属性有没有漂移**，
  能自己修的当场修（每类每次开机限额，不会反复重启或刷日志），结论写进
  `/data/adb/.xudc_secure/tee-selfcheck.txt`。
- 管理器「检测 → TEE 引擎与拦截」顶部直接显示这句结论（已接管 / 引擎没在跑 / 箱子不可用 /
  名单缺项 / 属性漂移），并新增「自检并修复」按钮（按一个触发文件，让设备侧跑，管理器不自己重算）。
- **"是 Google 根但已过期"成为独立状态**：2026-05-25 那代第一代硬件根到期后，链末指纹仍然"像 Google"、
  证书却已过期，检测方照样报「证书链不受信任」；现在过期的箱子会被内置的有效副本顶掉。

### TEE 去指纹（减少"被认出来"的廉价特征）

- 引擎进程名与 logcat tag 从 `TEESimulator` 换成中性名（唯一真源在 `daemon` 的 `--nice-name`，改名不漏处）；
- **不再创建** `/data/adb/modules/tricky_store` 兼容目录（并清理历史遗留）——它此前只是为了迁就 DEX 里
  写死的证书生成库路径，现已改为从引擎运行目录加载；
- 引擎日志从 `/data/adb/tricky_store/.engine.log` 迁到 `/data/adb/.xudc_secure/engine.log`（含内容迁移）。

### 说明：内置验机工具那条「检测到TrickyStore或类似模块」

- 这行红字来自**管理器内置的验机工具本身**（`wu.keyChain.test`），不是 keybox 的问题：它把自己签的
  一张证书写进系统密钥库再读回来比对，只要引擎覆盖它（我们在名单里保留了它）这条就必然出现。
  它**没有**任何 keybox 黑名单（链末那张根它是认的）。
- 判断伪装是否生效请看「引导加载程序已锁定 / 谷歌硬件根证书」两行 + 管理器 TEE 看板的引擎结论；
  第三方验机工具（vvb2060 密钥认证、Play Integrity）没有这种探针。这条说明已写进「关于这个检测工具」。

---

## v31109–v31111 · 2026-10-15 · 挂载态 Magisk 路线收尾 + 模块仓库官方源搬家 + 远程模块助手补完

> 类型：缺陷修复 + 功能补完（对外可见：挂载态首页/安装页、超级用户页授权与开关、5.10 以下的
> 修补流程、模块仓库源、远程模块助手的分享与接收）
> 提交范围：本地修复，待随下一版发版

### 修复：挂载态（5.10 以下）的设备终于显示成「Magisk 路线」

- 首页原来只用 `ksuVersion != null` 判断"在跑"，挂载态设备（没有 KSU 驱动）因此掉进
  `home_unsupported`，显示"现在只支持 GKI 内核" —— 而它正是 Magisk 路线的目标机型。
  现在路线判据统一成一处（`ui/util/RootRoute.kt` 的 `isMountState` = 没有 KSU 驱动应答
  且内核 <5.10）：首页/灵动岛显示「Magisk 路线（5.10 以下挂载态）」+ 正在跑的 magiskd 版本，
  GhostLock 提权入口（内核态专有）在挂载态不再出现。
- 「<5.10 但有 KSU 驱动（KPatch）」是**内核态**：它现在只看到 KernelPatch 那一项，再也看不到
  任何 Magisk 入口；反过来"内核态 + 另有真 Magisk"才算冲突（模块页照旧提示）。

### 修复：挂载态 Magisk 修补流程的载荷不再拷进 app 私有目录（Android 10+ 的 W^X）

- `cacheDir` 属于 `app_data_file`，Android 10+ 的 `untrusted_app` 对它**没有 execute**，
  拷进去的 `magiskboot`/`busybox` 会被 `Permission denied`。改成官方 App 的做法
  （`MagiskInstaller.kt::extractFiles` 的非 stub 分支）：把 `nativeLibraryDir` 里的载荷
  **软链接**到工作目录，execve 解析到真实 inode，脚本里的 `./magiskboot` 才跑得起来。
- LEGACYSAR 也从"只看 `ro.build.system_root_image`"改成 Magisk 官方判据（`/proc/mounts` 的根
  是 `/dev/root`），并在安装页给出手动覆盖开关（默认显示「自动」，判错可以强行打开/关闭）；
  自动探测挪到 IO 线程，不再压在组合期。

### 修复：挂载态的每一处写入都会回读校验（原来可能静默失败）

- `magisk --sqlite` 的退出码**恒为 0**（官方 v30.7：客户端读到行就 `return Ok(0)`，magiskd
  那侧把 SQL 的返回值丢掉），所以只写不回读等于没有错误处理。现在 `writePolicy`（允许/拒绝/
  询问/受限、通知、日志）、`removePolicy`（撤销）、`setZygisk` 都在**同一条 root shell** 里
  写完立刻读回同一行/同一开关，对不上就抛 `MagiskRoot.Unavailable`；DenyList 强制开关按官方
  判据（`--denylist status` 的退出码）回读。依旧是同一张表、不建影子表、不缓存。

### 修复：挂载态装完模块不再"假装软重启"

- `ksud soft-reboot` 在挂载态必然失败（内核里没有 KSU 驱动，UAPI 校验过不去），原来只记一行
  日志就 `Ok(())`，管理器于是显示"已重启"而设备根本没重启。现在 ksud 真的返回错误（退出码
  非 0），管理器在挂载态直接**退回真重启**；内核态行为不变。

### 修复：busybox 的 applet 真的可用（二次修补不再丢 SHA1）

- 载荷里的 `busybox` 原来只是摆在工作目录里、没人铺 applet：官方 `grep_prop()` 依赖的
  `dos2unix` 因此可能解析不到，`boot_patch.sh` 取 `config.orig` 的 SHA1 失败 → 原厂备份链
  断掉（还原原厂 / OTA 失效）。现在按官方 magiskd 的做法跑 `busybox --install -s <workdir>`
  并让 `<workdir>` 排在 PATH 最前；同时改正了"`TMPDIR` 保护工作目录"的错误注释
  （`util_functions.sh` 会把 `TMPDIR` 重写成 `/dev/tmp`）。

### 修复：超级用户页的首次授权引导（Magisk 被隐藏时不再是一页坏界面）

- `magisk` CLI 探测失败时先显示原始错误，再补一条"CLI 找不到、请确认已安装并已授权"的说明；
  只有真正"表里一行都没有"才显示"还没有应用申请过 root"。Magisk 包名被随机化（隐藏应用）
  时，"打开 Magisk 应用"按钮会消失，现在改为显示"去隐藏图标里批准本管理器"的说明。

### 变更：自研挂载态入口在没有 KSU 驱动且已装官方 Magisk 时拒绝执行

- `ramdisk-patch` / `install-mount-state` / `daemon` / `mount-state-status` / `magic-mount` /
  `su-list|su-grant|su-revoke` 这些入口，在**没有 KSU 驱动应答**且检测到官方 Magisk
  （`/data/adb/magisk.db` 或 Magisk 自己的二进制）时报错退出并指向 Magisk 路线 —— 同一台机器
  上不会再出现第二套 root 实现。判据用驱动是否应答，所以内核态设备（含 <5.10 的 KPatch）
  即使另装了 Magisk，`daemon`/`su-*` 这些正常功能照旧可用。
- `xudc root-mode` 与 `root_mode.rs` 的措辞不再声称挂载态是"KernelPatch 注入 + 挂载"的自研
  实现。口径统一：内核版本读不出来时按"非 GKI"（= 挂载态）处理，与管理器 `Kernels.kt` 一致。

### 修复：模块仓库的「官方源」不再指向已经 404 的旧地址

- 官方索引已搬到 `https://modules.kernelsu.org/api/v1/modules.json`（旧 `modules.json` 现在
  返回 404，且形状从裸数组变成 `{schemaVersion, modules:[…]}`）。管理器现在两个地址、两种形状
  都认，字段也做了映射（`id/name/stable{version,versionCode,publishedAt}/stars/authors[].url`）；
  点「官方源」看到的就是官方仓库，不再"选了官方、看到自家模块"。
- 详情页：官方 v1 的说明是单独一个 HTML 路径（`api/v1/modules/<id>/readme`），资产在
  `releases[].zip` 里。旧的 `module/<id>.json` 已 404，过去任何模块点进"说明"都永远转圈。
  现在两个协议都试，并且"确实没有说明/没有可下载版本"会给出空态，不再无限转圈或白屏。
- 自建仓库的模块详情页不再硬编码两个必然 404 的外链（官方站点详情页、不存在的
  `KernelSU-Modules-Repo` 组织），改用作者链接 + 详情里返回的主页/源码地址。
- 源回退提示改成"`%1$s` 不可用 · 已改用 `%2$s`"，说清真正生效的是哪一个源。

### 新增：模块 zip 的 sha256 端到端校验

- 模块仓库的索引由 `scripts/build_index.py` 在生成 `modules.json` 时写入每个 zip 的
  `latestRelease.sha256`（自建仓库以实际字节复算，作者仓库以实际下载内容复算）；管理器下载完
  校验一次，不匹配的文件当场丢弃、不进入"可安装"状态（哈希在 IO 线程算，不卡界面）。

### 补完：远程模块助手（分享 / 接收）

- **免登录出码**：以前唯一的出码入口要求登录 Hub；现在可以直接把链接编成 XEC2 加密码
  （`RemoteModuleCodec.encodeAll`）复制走，接收端本来就支持。
- **停止**：刷入过程中可以叫停（在模块之间生效；`flashModule()` 是阻塞调用，代码注释里写明）。
- **上传**：多选上传时部分失败不再丢掉已成功的链接；超过 500 MB（接收端硬上限）在选文件时
  就拦下；上传体改成定长流式（进度是真实网络进度，也不再整包进内存）；上传成功但未入库时
  给出"提交到模块仓库索引"的入口与说明。
- **会话**：`/api/me` 改用 POST（服务端只在 POST 上注册，GET 一律 404），进页面校验一次缓存
  的登录态，过期就清掉并提示；退出登录的提示文案也修正了。
- 定位：新增 `_scratch/check_modrepo_contract.py`（71 条断言）把"官方索引地址/双 schema/
  详情页空态/sha256 贯通/App↔Hub 路由/两个端口/新增字符串成对"钉住，并且联网核对
  `modules.kernelsu.org/api/v1/modules.json` 与自建索引里声明的 sha256 是否等于 zip 真实字节。

### 修复：TEE 引擎没跑时看板一定有按钮可点

- 原来「接管引擎」只在"外部 tricky_store 模块占位、但它的引擎没起来"时才出现。另一种同样
  致命的形态是**没有任何外部模块占位、内置引擎自己没起来**（资产没落盘、supervisor 崩了、
  注入失败、被杀掉而看护还没轮到）—— 那时看板只会说"引擎未运行"，却一个可点的按钮都没有。
  现在判据改成"引擎没跑且内置资产在位"，并新增一行说明（"内置引擎没有在跑，也没有外部模块
  占位 —— 点「接管引擎」把它拉起来"），用户不必靠 ADB 才能自救。

### 构建：载荷在打包/重签之后再复核一次

- CI 的 repack job 新增一步，拆开**签发出去的** APK，逐条核对 15 个 Magisk 载荷条目的
  sha256（与下载步骤同一份 pin），掉了或变了就 fail；`THIRD_PARTY_NOTICES.md` 也补齐了
  APK 摘要与 15 个逐文件摘要，并写明哪些来自上游发布件、哪些可复现及复算方法。

---

## v31103–v31107 · 2026-10-13 · 5.10 以下改走 Magisk 官方修补（方案甲）+ 管理器双向管 root

> 类型：路线切换 + 新增功能（对外可见：<5.10 的安装方式、超级用户页、模块安装通道）
> 提交范围：`bc9029e5..41506e67`（发版 v31103 → v31107）

### 变更：<5.10 的 root 改用 Magisk 官方修补（方案甲）

- 安装页在非 GKI（<5.10）分支新增 **「Magisk 修补（5.10 以下的内核）」**：管理器不做任何
  boot 修补算法，只按官方 `boot_patch.sh` 的目录契约摆好载荷（`magiskboot` / `magiskinit` /
  `magisk` / `init-ld` / `busybox` / `stub.apk` / `util_functions.sh`）并以 `BOOTMODE=true`
  跑官方脚本，把产物 `new-boot.img` 导出给用户刷入。
- 高级开关四个，全部对应官方环境变量（默认全关，与 Magisk 默认一致）：
  `KEEPVERITY` / `KEEPFORCEENCRYPT` / `PATCHVBMETAFLAG` / `RECOVERYMODE`；`LEGACYSAR`
  由管理器判定后传入。A-only legacy SAR 的官方答案是 **`RECOVERYMODE=true` 刷 recovery**。
- 载荷版本固定 **v30.7**，CI 按 **APK sha256 + 每个载荷文件 sha256** 校验后解包到
  `assets/magisk/` 与 `jniLibs/<abi>/`（不进源码树）；GPL-3.0 署名、来源与"未修改"声明见
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 新增：挂载态的「超级用户」页 = Magisk 策略表的前端（双向）

- 管理器新增按路线的判定：**驱动是否应答 + 内核是否 GKI**（都不需要 root）。非 GKI 且无驱动
  ⇒ 挂载态：这台设备上的 root 就是 Magisk 的 root。
- **超级用户页**直接读写 Magisk 自己的策略表（`magisk --sqlite` / `magisk --denylist`）：
  权限四态（询问 / 允许 / 拒绝 / 受限）、通知、日志、撤销、Zygisk 开关、DenyList 强制状态
  与条数，并标出「本管理器」自己那一行。**不建影子名单、不缓存授权** ⇒ Magisk App 与本
  管理器两边改一边立刻一致；本管理器的 root 授权也在同一张表里，Magisk 那边看得到、收得回。
- 授权框永远由 Magisk 弹（它只认自己签名的管理器），管理器不会绕过它自己发 root。
- 挂载态的 root shell 改为直接走设备的 `su`（原来先试 `libxeccli.so debug su`，没有内核驱动
  时必然拿不到 root），并且每条命令新开一条 shell —— 授权被撤销或首次被拒之后不会留死壳。

### 变更：模块安装通道

- 挂载态下刷入模块改走 **Magisk 官方安装器**（`magisk --install-module`），模块的
  `customize.sh` 用 Magisk 的 `ui_print` / `set_perm` / `replace` 语义；内核态仍走
  `ksud module install`。启用 / 禁用 / 卸载继续用两边共用的 `disable` / `remove` 标记文件。

### 移除：自研挂载态路线从界面撤下

- `InstallMethod.RamdiskPatch` 及其界面与字符串全部撤下；`xudc ramdisk-patch` 与 `xecinit`
  代码保留为实验，不再对外推荐。
- 「与 Magisk 冲突、模块不可用」不再出现在 <5.10 设备上（那是内核态与真 Magisk 同时存在时
  才有的真冲突）；首页该位置改为「Magisk 路线（5.10 以下挂载态）」。

### 兼容性

- **GKI 2.0 / 内核态（5.10+）行为不变**：Magisk 相关资源与逻辑只出现在非 GKI 分支；
  `_scratch/check_root_mode_isolation.py`（51 条断言）与 CI 全量构建（8 个 LKM、ksud、
  xecinit、管理器 APK）保证这一点。
- <5.10 使用说明见 [docs/mount-state-root-guide.md](docs/mount-state-root-guide.md)。

---

## v31001 · 2026-10-06 · 许可合规整改：XecHide 下架、Zygisk Next 移出分发、上游署名补全、迁移新仓库

> 类型：合规与分发调整（对外可见：Release 资产与资源包内容变化）

- **下架 XecHide**：应用隐藏引擎源码、CI 工作流与 Release 资产整体移除，不再分发。
- **移除 Zygisk Next 分发**：其许可证不允许第三方再分发。Release 与紫罗兰资源包均不再附带该组件；需要 Zygisk 框架的用户请从官方仓库（LSPosed/ZygiskNext）获取——自装 Zygisk 后资源包内 HMA 模块即可正常启用。
- **签名密钥出库**：`xecpro-key.jks` 从仓库与全部 git 历史中移除，改由仓库 Actions Secrets 注入 CI（同一把密钥，签名结果不变，APK 升级链不受影响）。
- **上游署名补全**：新增根 README 与 THIRD_PARTY_NOTICES.md；应用内「开发者名单 → 致谢」补充 KernelSU（weishu/tiann）与 HMA-OSS、TEESimulator-RS、Tricky Addon、susfs4ksu、LSPosed 等上游致谢；README 中明确「内核 GPL-2.0 / 用户态 GPL-3.0」的许可结构与上游归属。
- **内置模块补 NOTICE**：`builtin/` 四个模块目录各附 NOTICE.md 注明上游与许可。

---

## v30056 · 2026-09-14 · XEC Fluid Glass 全局 UI 重写

> 提交范围：`60b825b..9f2fef2`（4 个提交）
> 规模：134 个文件，+3304 / -11701 行（净减约 8400 行）
> 版本号：`versionCode = 30056 = 30000 + 提交总数(56)`，`versionName` 由 CI 打 tag 时的 `git describe` 决定

### 新增：自有的设计语言

管理器的外观此前只是 KernelSU / miuix / Material3 两个上游主题的转发结果，本次改为一套自有、可命名的设计语言 **XEC Fluid Glass**：深墨绿灰底（`#0B0F10`）+ teal 强调色（`#12B886`），设计原则是「底是静的，玻璃是动的，光是水的」。

新增 `ui/design/` 三层结构，上层只依赖下层：

| 层 | 内容 | 职责 |
| --- | --- | --- |
| L1 `design/token/` | `XcColor`、`XcShape`、`XcMotion`、`XcTypography`、`XcElevation`、`XcTheme` | 纯数据令牌，经 `CompositionLocal` 下发 |
| L2 `design/glass/` | `XGlassSurface`、`XGlassCard`、`XGlassBar`、`XGlassDialog` | 玻璃表面，运行时能力三档降级 |
| L3 `design/liquid/` | `WaterDrop`、`XcIndication` | 点击水滴特效 |

全圆角化：`XcShape` 提供 `xs 8dp / sm 12dp / md 16dp / lg 22dp / xl 28dp / bar 32dp / pill 999dp`，**任何矩形表面都不再使用 `RectangleShape`**。

### 新增：全局水滴交互

- `Modifier.xWaterDropClick(onClick, shape)`：波纹裁剪在控件圆角内，并叠加 `pressScale` 按下收缩。扩散直径 2.2× 控件短边、上限 160dp，时长 620ms，起始不透明度 0.42，两层波（第二层滞后 80ms）。
- `XDropletHost`：包住整个应用根节点，用祖先级 `pointerInput`（Initial pass、不 consume）观察任意按下点，在顶层绘制扩散光晕——**没有单独改造过的控件也自动带水滴**。
- 短边 < 48dp 的控件只做收缩、不扩散；内置 `LocalXWaterDropEnabled` 可整体关闭。

### 新增：玻璃对话框体系

- **弃用平台 `WindowDialog`**：切换 window 会让 `LayerBackdrop` 失效（模糊与折射全部丢失），且它没有任何 `Shape` 参数，无法满足圆角要求。改为**同窗口玻璃浮层**，由 `XDialogHost` 统一承载；根组合里建立整窗 backdrop 并通过 `LocalXDialogHost` / `LocalXDialogBackdrop` 下发，返回键语义按平台 `Dialog` 补齐。
- **新增 `XDialog` / `XDialogTitle`**：「内容由调用方自绘」的对话框改为向宿主登记，由根层 `XDialogHost` 绘制，避免 `XGlassDialog` 的 `fillMaxSize()` 全屏浮层在 `LazyColumn` item / `Card` 深子树里被裁切。
- 迁移全仓 **9 处** `OverlayDialog` 调用点：`SendLogDialog`、`ChooseKmiDialogMiuix`、`UninstallDialogMiuix`、`RootProfileConfigMiuix`（Groups / Flags / Caps / SELinux 四个面板）、`SulogMiuix`（`SulogDetailDialog`）、`ModuleMiuix`（`ModuleShortcutDialog`）。全仓 `OverlayDialog` 代码引用归零。
- 对话框补上安全区约束与面板内滚动，长文案不再把按钮顶出屏幕。

### 移除：Material / Miuix 双轨

`UiMode` 与 25 组 `XxxMiuix.kt` + `XxxMaterial.kt` 成对实现是「和 KernelSU 一模一样」的结构性原因，本次收敛为单轨：

- 删除设置页的 **UI 模式开关**、`UiMode.kt`、`LocalUiMode`、`Theme.kt` 里的分发、`SettingsUiState.uiMode` / `onSetUiModeIndex` 以及 `settings_ui_mode*` 字符串资源。
- 删除 **44 个** Material / 双轨实现文件：25 个页面/组件 `*Material.kt`、`material/Expressive*.kt` 全家、`material/SearchBar.kt`、`SendLogBottomSheet.kt`、`SnackBar.kt`、`TonalCard.kt`、`SegmentedList.kt`、`TopBarBackButton.kt`、`profile/dialogs/MultiSelectDialog.kt`、`SingleSelectDialog.kt`、`dialog/DialogMiuix.kt`、`theme/MaterialTheme.kt`、`theme/ThemeExt.kt`、`theme/Type.kt`、`WebUIMaterial.kt` 等。
- 收敛后以 miuix 为渲染后端（保留 Material3 依赖供未及改造处使用）。

### 修复

- **13 条编译阻断**：`MiuixTheme` 里照搬了 Material3 的 9 个 miuix 根本不存在的颜色槽位（`surfaceContainerLow/Lowest/Dim/Bright/Tint`、`onSurfaceVariant`、`outlineVariant`、`inverseSurface`/`onInverseSurface`）。miuix 的 `Colors` 是 MD3 `ColorScheme` 的精简子集，共 53 个槽位，多写一个就报 `No parameter with name ... found`。另修 `RectangleShape` 的包路径（在 `androidx.compose.ui.graphics`，`foundation.shape` 下无此符号）与 `Retained` 的 `value` 缺省值。
- **全局色偏**：miuix 的 `keyColor` 只在 Monet 档生效，默认 System 档下 `primary` 仍是 miuix 蓝，导致按钮、开关等 **20+ 处**强调色与 XEC 主题不一致。改为用 materialkolor 从 `accentSeed` 自派生整套强调色系并逐槽写回。
- **语义色对比度**：新增容器态底色 `dangerTint` / `warningTint` / `successTint` 及前景令牌 `onDangerTint` / `onWarningTint` / `onSuccessTint` / `onSemanticSolid`。语义实色直接当文字时对比度仅约 3:1，故朝 `text` 方向混 55% 拿到 7:1 以上。
- **`BlurredBar` 的折射从未生效**：此前用 `RectangleShape` 作为 `drawBackdrop` 的 shape，而 `lens()` 内部要求 `shape as? CornerBasedShape`，取不到就直接 `return`——所以「液态玻璃」只剩模糊。顺带修掉顶栏/底栏四角是直角的问题。
- 全仓 `isDynamicColor` 硬编码兜底与 `DpSize` 引用归零。

### 兼容性

- `minSdk 31`。玻璃按运行时能力**三档降级**，低版本不崩溃、不黑屏：
  1. 支持 AGSL（API 33+）→ `vibrancy + blur + lens` 真实折射；
  2. 支持 `RenderEffect` → `textureBlur`；
  3. 都不支持 → 半透明纯色 + 描边。
- `DARK_AMOLED` 档位下 `backdrop` 取纯黑，降低玻璃不透明度、改用描边区分层次。
- **保留不动**：`ColorMode`（含 `DARK_AMOLED`）、`keyColor`、`paletteStyle`、`colorSpec` 及其设置项。

### 视觉变化提示

- 对话框标题由「居中 title4 / Medium」统一改为「左对齐 SemiBold」（`XDialogTitle`，可选副标题）。
- `SulogDetailDialog` 的 `weight(1f, fill = false)` 改为 `heightIn(max = 420.dp)`，因为新宿主不再提供可分配 weight 的父容器语义。
- 迁移后各对话框不再自带 `verticalScroll`：宿主面板内层已统一滚动。

### 构建与验证

CI run `34791687327`（sha `9f2fef2`）已跑完，结果如下：

| Job | 结果 |
| --- | --- |
| `build-manager` | success（自 `f6a0f14` 以来 manager 首次编译成功） |
| `repack-manager` | success |
| `build-ksud`（x86_64 / aarch64-linux-android） | success |
| `build-lkm` ×8 | success |
| `generate-key` / `build-ksuinit` | success |
| `build-ksud-extra (aarch64-apple-darwin)` | **failure（既有问题）** |

关于最后一条：`build-ksud-extra (aarch64-apple-darwin)` 在**重构前的基线提交** `60b825b`、`7b37d88` 上同样失败（其余 4 个 `build-ksud-extra` 因矩阵 fail-fast 被 cancelled），是长期既有问题，与本次 Kotlin 改动无关。

静态校验全绿：花括号配平（190 个 `.kt` 文件）、无残留引用已删除符号、调用点参数逐一对齐（13 个声明）。

---

## 附：本版本相关提交

| 提交 | 说明 |
| --- | --- |
| `f6a0f14` | refactor(manager): 全局 UI 重写为 XEC Fluid Glass，接入水滴特效与玻璃对话框 |
| `15b8bd5` | fix(manager): 修复 13 条编译阻断（miuix 槽位 / RectangleShape 包路径 / Retained 缺省值） |
| `3d904e3` | fix(manager): 清除 miuix 动态色兜底造成的全局色偏 |
| `9f2fef2` | fix(manager): 玻璃对话框收敛收尾——全仓 OverlayDialog 调用点迁移到 XDialog |

设计文档：`docs/superpowers/specs/2026-09-14-xecpro-ui-global-refactor-design.md`
