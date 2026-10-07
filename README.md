# XECKernel Pro

基于 [KernelSU](https://github.com/tiann/KernelSU) 修改开发的 Android root 方案与管理器：内核级 su、模块系统、App Profile、内置 TEE 引擎与 SUSFS 支持。

## 许可证与上游归属（License & Attribution）

本项目基于 **tiann/KernelSU** 修改开发，遵守上游原始许可证，并保留上游版权与作者署名：

| 部分 | 许可证 | 说明 |
|---|---|---|
| `kernel/`（内核模块） | GPL-2.0（见 [kernel/LICENSE](kernel/LICENSE)） | 源自 tiann/KernelSU（作者 weishu / tiann 及社区贡献者），上游版权注释（如 `kernel/core/init.c` 的 `MODULE_AUTHOR("weishu")`）予以保留 |
| `manager/`、`userspace/`、`uapi/`、`website/` 等用户态部分 | GPL-3.0（见 [LICENSE](LICENSE)） | 基于 tiann/KernelSU 用户态代码修改开发 |

感谢 KernelSU 及其社区的上游工作。`docs/` 内保留了上游 KernelSU 的原版 README（含 tiann/KernelSU 链接），一并作为归属声明。

### 上游致谢（转引自 KernelSU README「Credits」）

以下条目原样转引自 tiann/KernelSU 的 `docs/README.md`「Credits」节（main 分支，2026-10-06 取）：

- [Kernel-Assisted Superuser](https://git.zx2c4.com/kernel-assisted-superuser/about/): The KernelSU idea.
- [Magisk](https://github.com/topjohnwu/Magisk): The powerful root tool.
- [genuine](https://github.com/brevent/genuine/): APK v2 signature validation.
- [Diamorphine](https://github.com/m0nad/Diamorphine): Some rootkit skills.

### 美术资源

- 应用图标 / Logo：**明风ouo**（依 CC 协议授权使用，在此致谢）

### 源码合编译与 GPL-2.0 单独致谢

- 内置模块（TEESimulator-RS、Tricky Addon Enhanced）为 **GPL-3.0 源码合编译**：
  上游源码原样 vendor 在 [`third_party/`](third_party/)（每目录附 `UPSTREAM.md`
  注明仓库 / 作者 / 固定 commit / 许可证），CI 从源码构建全部引擎二进制，
  仓库与发布渠道不携带任何 GPL 预编译二进制。
- **GPL-2.0 上游**（KernelSU 内核模块、simonpunk/susfs4ksu 内核补丁、AnyKernel3 等）
  在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 的
  「GPL-2.0 上游致谢」一节单独列出致谢。
- **协议合规**：GPL-3.0 §4/§5/§6/§7 各项义务（保留声明、修改声明与日期、整体同许可、
  提供对应源码、不加附加限制）的实际履约方式，以及 LGPL-3.0 / Apache-2.0 / MIT 的
  履约要点，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 的
  「开源协议合规」一节；上游文件头与各模块 `NOTICE.md` 均带修改声明与日期。

第三方组件与内置/分发模块的完整致谢与许可证清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；应用内「关于 → 开发者名单 → 致谢」同样列有上游致谢。

## 构建

- 管理器 APK、内核 LKM、GKI SUSFS 内核均由 GitHub Actions 构建（`.github/workflows/`）。
- 发布签名密钥通过仓库 Actions Secrets（`SIGN_KEYSTORE_B64` / `SIGN_STORE_PASS` / `SIGN_KEY_PASS`）注入 CI，不随仓库分发。

## 相关链接

- 上游项目：https://github.com/tiann/KernelSU
- 问题反馈：本仓库 Issues
