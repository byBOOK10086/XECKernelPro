# XECKernel Pro

基于 [KernelSU](https://github.com/tiann/KernelSU) 修改开发的 Android root 方案与管理器：内核级 su、模块系统、App Profile、内置 TEE / SUSFS 工具链。

## 许可证与上游归属（License & Attribution）

本项目基于 **tiann/KernelSU** 修改开发，遵守上游原始许可证，并保留上游版权与作者署名：

| 部分 | 许可证 | 说明 |
|---|---|---|
| `kernel/`（内核模块） | GPL-2.0（见 [kernel/LICENSE](kernel/LICENSE)） | 源自 tiann/KernelSU（作者 weishu / tiann 及社区贡献者），上游版权注释（如 `kernel/core/init.c` 的 `MODULE_AUTHOR("weishu")`）予以保留 |
| `manager/`、`userspace/`、`uapi/`、`website/` 等用户态部分 | GPL-3.0（见 [LICENSE](LICENSE)） | 基于 tiann/KernelSU 用户态代码修改开发 |

感谢 KernelSU 及其社区的上游工作。`docs/` 内保留了上游 KernelSU 的原版 README（含 tiann/KernelSU 链接），一并作为归属声明。

第三方组件与内置/分发模块的完整致谢与许可证清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；应用内「关于 → 开发者名单 → 致谢」同样列有上游致谢。

## 构建

- 管理器 APK、内核 LKM、GKI SUSFS 内核均由 GitHub Actions 构建（`.github/workflows/`）。
- 发布签名密钥通过仓库 Actions Secrets（`SIGN_KEYSTORE_B64` / `SIGN_STORE_PASS` / `SIGN_KEY_PASS`）注入 CI，不随仓库分发。

## 相关链接

- 上游项目：https://github.com/tiann/KernelSU
- 问题反馈：本仓库 Issues
