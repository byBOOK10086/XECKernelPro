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

版权归原作者所有；许可证全文见上游仓库与 `third_party/TEESimulator-RS/LICENSE`。
本项目按 GPL-3.0 分发本模块，未改变其许可条款；如你是权利人且不希望被分发，请提交 issue，我们会立即处理。
