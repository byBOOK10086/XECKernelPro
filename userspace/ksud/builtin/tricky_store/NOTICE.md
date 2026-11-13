# 系统密钥组件（内置 TEE 引擎）— 署名、许可与本地修改

本模块是 XECKernel Pro 的管理器/内核之外自带的一个「密钥证明」载体模块。它由两部分组成，
**许可证不同、边界必须分清**。

## 一、运行期引擎：Oh My Keymint

| 项 | 值 |
|---|---|
| 名称 | Oh My Keymint（OMY / OMK） |
| 作者 | James Clef `<qwq233@qwq2333.top>` |
| 上游 | https://github.com/qwq233/OhMyKeymint |
| 版本 | `1.2.6-9069c6c`（2026-11-06） |
| 发行资产 | `OhMyKeymint-release-arm64-v8a-1.2.6-9069c6c.zip`，sha256 `045d72dd0e5804001e3f227611e74df1eb0ac466747ff2aa0e8af8948f2e0d07` |

载荷（`engine/libs/arm64-v8a/keymint`、`engine/libs/arm64-v8a/inject`、`engine/daemon`、
`engine/daemon-injector`）**逐字节取自上游发行产物**，本仓库不重新编译、也不修改它们。
对应的源码与升级流程见 `third_party/OhMyKeymint/UPSTREAM.md`。

引擎许可证为两个文件共同生效，随载荷一起分发（GLA §4.1 / §4.4 要求）：

- `engine/LICENSE.1` — General License Agreement v4（2026-10-14）
- `engine/LICENSE.2` — Oh My Keymint License
- `engine/AOSP.Apache-license-2.0.txt` — 引擎中源自 AOSP 的部分（Apache-2.0）

约束摘要（以原文为准）：

1. **仅限非商业使用**；商业用途须取得作者书面许可。
2. 分发须完整附带协议全文、保留作者署名与发布地址，**不得暗示作者为本项目背书**。
3. 修改引擎源码者须以同一协议公开该修改的完整对应源码。我们不修改它，因此没有该义务；
   任何人若要改 `third_party/OhMyKeymint/` 下的东西，必须同时补上修改声明与源码。
4. 衍生物不得沿用原名、不得让人误以为出自原作者。

## 二、本模块自有文件：GPL-3.0

以下文件由 XECKernel Pro 编写或改写，按 GPL-3.0 授权：

- `service.sh` — 引擎载体：落盘、keybox 真源与镜像、作用域导出、启停与看护
- `post-fs-data.sh` — 开机前准备 `/data/misc/keystore/omk` 与 keybox
- `engineconf.sh` — target.txt 合并/对账/检测工具路由/`sync_scoop`
- `target_autofill.sh`、`target.baseline.txt`、`target.txt`
- `sepolicy.rule` — 本项目规则 + 上游规则并集
- `keybox.xml` — 数据文件（第三方公开箱子，非本项目的代码作品）
- `module.prop`

**边界声明**：GPL-3.0 只覆盖上面这些自有文件，不覆盖 `engine/` 载荷本身。这两部分
是分进程、分目录的聚合（GLA §4.2.2 允许汇编作品中的其他作品不受该协议约束），
因此本项目**不得**被整体描述为「GPL-3.0 的 Oh My Keymint」，反之亦然。

## 三、本地修改（自 2026-11-13 起）

1. **2026-11-13 引擎更换**：运行期引擎由 TEESimulator-RS（Java `classes.dex` + `supervisor`）
   换成 Oh My Keymint（原生 `keymint` + `inject` + 两个看护脚本）。
   - 删除 `daemon`（旧 Java 启动器）、`supervisor`/`classes.dex`/`libTEESimulator.so`/`libcertgen.so`
     的落盘逻辑，以及 `consistency.sh`（只服务于旧引擎的自检脚本）。
   - 模块目录新增 `engine/`（构建期由 `third_party/OhMyKeymint/module` 拷入，不入库）。
2. **2026-11-13 落点迁移**：keybox 与作用域不再落在引擎目录，改为
   `/data/misc/keystore/omk/{keybox.xml,injector.toml}`（属主 `1017:1017`），
   由 `service.sh` 从 `/data/adb/tricky_store/keybox.xml` 原子镜像过去；
   `target.txt` 仍是唯一真源，`sync_scoop` 每 15s 生成 `injector.toml` 的 `scoop`。
3. **2026-11-13 启动幂等**：`service.sh` 重入时不再拉第二份看护循环（pidfile + cmdline 校验）。
4. **2026-11-13 sepolicy**：并入上游 5 条规则，并补 `adb_data_file` 的
   `execute/map/read` 与目录检索（我们的引擎树不是 `system_file`）。
5. **2026-11-13 module.prop**：`name` 保持中性的「系统密钥组件」（许可证命名条款要求），
   `author` 补上 James Clef；`versionCode` 提到 700 以触发老设备重新落盘。

历史修改（旧引擎时期）见 git 历史中的本文件。
