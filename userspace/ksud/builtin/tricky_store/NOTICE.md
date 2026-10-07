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

许可证不由本项目改变：本模块整体仍按 **GPL-3.0** 分发，对应源码（含上述修改）随本仓库
一同提供；完整对应源码获取方式见根目录 `THIRD_PARTY_NOTICES.md`。

版权归原作者所有；许可证全文见上游仓库与 `third_party/TEESimulator-RS/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。
