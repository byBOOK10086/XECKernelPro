# Upstream provenance（上游来源与构建说明）

- **项目**：TEESimulator-RS（内置模块 id `tricky_store` 的引擎源码）
- **上游仓库**：https://github.com/Enginex0/TEESimulator-RS
- **作者**：Enginex0、JingMatrix
- **固定修订**：tag `v6.0.0-162`，commit `5267c9dd0092b69dee4a34eb0c8af8e617b2bc5d`
- **许可证**：GPL-3.0（本目录 `LICENSE`；与本仓库根 LICENSE 同许可证）
- **技术渊源**：源自 5ec1cff/TrickyStore（GPL-3.0）https://github.com/5ec1cff/TrickyStore

## 内含的第三方源码

- `app/src/main/cpp/external/LSPlt/` — LSPlt，JingMatrix/LSPlt
  （https://github.com/JingMatrix/LSPlt ，**LGPL-3.0**），
  固定 commit `3e29437f037cb7d2b9fbb459dcf162f6b8d1d926`（上游 `.gitmodules`
  在该修订所钉的子模块 commit；此处按内容原样 vendored，构建时无需 git submodule）。
- `app/src/main/cpp/external/AOSP/` — AOSP libbinder/libutils 头文件子集
  （Apache-2.0，https://android.googlesource.com/platform/frameworks/native/）。
- `app/src/main/cpp/external/linux-kernel/` — Linux 内核 UAPI 头文件子集
  （GPL-2.0，https://git.kernel.org/）。

## 本仓库如何使用这份源码

CI（`.github/workflows/ksud.yml` → `.github/scripts/build-builtin-modules.sh`）
从本目录源码构建以下产物，并放入 `userspace/ksud/builtin/tricky_store/`：

| 产物 | 源码位置 |
|---|---|
| `classes.dex` | Gradle `zipRelease`（`app/` Kotlin/Java，R8 minify） |
| `libTEESimulator.so` | `app/src/main/cpp/binder_interceptor.cpp`（CMake/NDK） |
| `inject`（zip 内 `lib/arm64-v8a/libinject.so`） | `app/src/main/cpp/inject/` |
| `supervisor`（zip 内 `lib/arm64-v8a/libsupervisor.so`） | `app/src/main/cpp/supervisor.cpp` |
| `libcertgen.so` | `native-certgen/`（cargo-ndk，arm64-v8a） |

`module/` 内的 daemon/service.sh 等脚本仅供上游参考；内置模块运行时使用
`userspace/ksud/builtin/tricky_store/` 下为本仓库 builtin 具体化机制重写的脚本。

除本文件外，本目录内容未做任何修改（上游 `v6.0.0-162` 原样快照）。
