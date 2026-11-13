# 许可证矩阵（License Matrix）

本项目 **XECKernel Pro** 不是单一许可证仓库：它由「我们自己的代码」「KernelSU 上游派生物」「Linux 内核模块」「第三方 vendor 代码」「服务器端」五类来源组成，每一类的许可证不同。任何二次分发都必须按本表逐项处理，不能只看根目录的 `LICENSE`。

**项目主许可证（我们自己的原创代码）：AGPL-3.0-or-later** — 见 [`LICENSE`](LICENSE)。

| 目录 / 范围 | 许可证 | 文本 | 说明 |
|---|---|---|---|
| 我们原创的全部代码（manager 中非上游部分、server、scripts、workflows、docs、主题系统等） | **AGPL-3.0-or-later** | [`LICENSE`](LICENSE) | 本项目主许可证。通过网络提供服务时按 §13 向使用者提供对应源码——`server/` 放在本仓就是为满足这一条 |
| `manager/`、`userspace/`、`uapi/`、`website/` 中**源自 tiann/KernelSU 的文件** | GPL-3.0 | [`LICENSE-GPL-3.0`](LICENSE-GPL-3.0) | 上游版权与许可原样保留，未被单方改写成 AGPL。与 AGPL-3.0 的组合分发依据 AGPL-3.0 §13（GPLv3 与 AGPLv3 可链接/组合，组合物按 AGPL 分发，GPLv3 部分仍为 GPLv3） |
| `kernel/`（内核模块） | **GPL-2.0** | [`kernel/LICENSE`](kernel/LICENSE) | Linux 内核派生物，必须保持 GPL-2.0；**不并入**上面的 AGPL 组合，按独立作品分发 |
| `third_party/ghostlock/` | Apache-2.0 | 目录内 `LICENSE` | 上游原样 vendor |
| `third_party/TEESimulator-RS/` | GPL-3.0 | 目录内 `LICENSE` | 上游原样 vendor |
| `third_party/tricky-addon-enhanced/` | GPL-3.0 | 目录内 `LICENSE` | 上游原样 vendor（含 `external/resetprop-rs`） |
| `third_party/susfs4ksu-lkm/` | GPL-2.0 | 目录内 `LICENSE` | 上游原样 vendor |
| `third_party/*/external/AOSP`、`external/LSPlt` | 各自原许可证 | 目录内 `LICENSE` | 未改动 |
| `server/`（官网生成、发布流水线、上传服务、部署脚本） | **AGPL-3.0-or-later** | [`LICENSE`](LICENSE) | 与主体同许可；凭据一律走环境变量，仓库里不落密钥 |
| `third_party/OhMyKeymint/` 与内置引擎独立载荷 | General License Agreement v4 + Oh My Keymint License；AOSP 部分按原许可 | 目录内 `src/LICENSE.md`、`src/LICENSE-2`、`module/LICENSE.1`、`module/LICENSE.2` | 按原许可作为独立进程和文件聚合，不改为 AGPL/GPL。保留原始载荷、全文许可、作者署名、源码与发布地址；仅按适用许可进行非商业分发。维护者报告已取得作者集成许可，记录见 `third_party/OhMyKeymint/AUTHORIZATION.md`；不暗示作者背书 |

## 为什么是 AGPL-3.0 而不是继续 GPL-3.0

我们提供的是**网络服务**（官网、更新检查、keybox 分发）。GPL-3.0 的 §13 只约束"分发二进制"，不约束"通过网络使用"；AGPL-3.0 补上了这一条：用户通过网络与本项目交互时，有权拿到对应源码。这与本仓把服务器端代码一并开源的做法是同一件事。

## 与上游的关系

- KernelSU 上游（tiann/KernelSU）：GPL-3.0 用户态 + GPL-2.0 内核，本仓保持其原有划分与版权声明（见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)）。
- 第三方 vendor 组件：许可证文本与版权声明一律保留在各自目录内，未被本项目改写。
- 若你是权利人不希望某组件被随本项目分发，请提 issue，我们会立即下架。

## 合规要点（分发者须知）

1. 分发任何二进制（APK / zip / 内核镜像）时，必须同时提供对应源码或书面 offer。
2. 若你修改了 `manager/` 中源自 KernelSU 的文件，保留其 GPL-3.0 声明；你新增的文件可以按 AGPL-3.0 发布。
3. `kernel/` 的二进制（`kernelsu.ko`、内置内核）按 GPL-2.0 分发，必须保留 `kernel/LICENSE` 与源码可得性。
4. Oh My Keymint 保持独立载荷和原许可证，随包携带完整许可、署名与来源；不得将它宣称为 AGPL/GPL 组件。商业用途或超出上游许可的用途须另行取得适用授权。
