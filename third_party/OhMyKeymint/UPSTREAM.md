# Oh My Keymint（上游组件记录）

本目录是我们内置 TEE 引擎的上游来源。它**不是**我们的代码，改动它之前先读本文。

## 上游坐标

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/qwq233/OhMyKeymint |
| 版本 | `1.2.6-9069c6c`（2026-11-06 发布） |
| 提交 | `9069c6c` |
| 作者 | James Clef `<qwq233@qwq2333.top>` |
| 发行资产 | `OhMyKeymint-release-arm64-v8a-1.2.6-9069c6c.zip` |
| 资产大小 | 4,930,351 B |
| 资产 sha256 | `045d72dd0e5804001e3f227611e74df1eb0ac466747ff2aa0e8af8948f2e0d07` |

## 目录内容

```
module/     # 上游成品模块，原样解出（36 个文件：18 个文件 + 同目录 .sha256）
  daemon            # keymint 看护脚本（shell，循环拉起 libs/*/keymint）
  daemon-injector   # 注入器看护脚本（循环把 libs/*/inject 注入 keystore2）
  libs/arm64-v8a/keymint   # 软件 KeyMint 本体（Rust，7,752,352 B）
  libs/arm64-v8a/inject    # keystore2 注入载荷（Rust，2,343,200 B）
  post-fs-data.sh service.sh sepolicy.rule injector.toml keybox.xml
  module.prop customize.sh verify.sh META-INF/
  LICENSE.1  LICENSE.2  AOSP.Apache-license-2.0.txt  README.md
src/        # 上游完整源码树（494 个文件，对应同一提交），供审计与再构建
```

`module/` 内的文件与发行 zip **逐字节一致**（可用 zip 内自带的 `.sha256` 逐个校验）。
本仓库不修改这些文件；我们对 OMY 的所有适配都写在自己的模块脚本里。

## 许可证（两个文件共同生效，缺一不可）

- `module/LICENSE.1` — General License Agreement v4（James Clef，2026-10-14）
- `module/LICENSE.2` — Oh My Keymint License（命名限制 + 中国大陆法院管辖）

要点（以原文为准）：

1. **仅限非商业使用**（GLA §3）。本项目免费发行，符合；任何商业化前必须取得作者书面许可。
2. 分发时必须**完整附带协议全文**、保留作者署名与原发布地址，且不得暗示作者为我们背书（GLA §4.1 / §4.4）。
3. **修改** OMY 源码后必须以同一协议发布该修改的完整对应源码（GLA §4.2）。我们不修改它，因此没有这条义务；一旦有人在本仓库里改了 `module/` 或 `src/`，就必须补上源码与修改声明。
4. 衍生物**不得沿用原名**、不得让人误以为出自原作者（LICENSE.2 §1）。我们的外壳模块因此叫「系统密钥组件」而不是 "Oh My Keymint"。
5. 汇编作品里「其他作品」不受本协议约束（GLA §4.2.2）：OMY 与我们 GPL-3.0 的用户态代码**分进程、分目录聚合**，不构成混合代码；但**不得**把 OMY 源码并进 GPL 树再整体声称 GPL-3.0。

## 与本项目的关系

- 运行期引擎 = OMY（`libs/arm64-v8a/keymint` + `inject` + 两个看护脚本）。
- 引擎载体 = `userspace/ksud/builtin/tricky_store`（我们的模块身份、生命周期、作用域与 keybox 管理、管理器状态）。
- 落点文件：`/data/misc/keystore/omk/{keybox.xml,injector.toml,config.toml}`（属主 `1017:1017`），状态目录 `/data/adb/omk`。
- `config.toml` 由 OMY 首次启动时自行生成（含 `[crypto]` 随机密钥、`[device]`、`[trust]`）；我们不代写，避免两处真源。

## 升级方式

1. 取上游新版本 release zip，记录资产 sha256；
2. 解出到 `module/`（整体替换，不合并），同步 `src/`；
3. 更新本文件的坐标表；
4. 复核 `module/service.sh`、`module/daemon*`、`config.toml` 字段是否有变（见 `src/docs/CONFIGURATION.md`），有变就同步我们的 `engineconf.sh` / `service.sh`；
5. 跑 `bash -n` 与模块目录对账，再进 CI。
