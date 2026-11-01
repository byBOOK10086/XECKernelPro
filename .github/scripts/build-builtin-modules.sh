#!/usr/bin/env bash
# Build the GPL-3.0 built-in modules from the vendored sources under
# third_party/ and drop the artifacts into userspace/ksud/builtin/.
#
# Must run BEFORE the ksud `cargo build`: RustEmbed packs whatever is inside
# userspace/ksud/builtin/ at compile time, so this script is what turns the
# committed script/config-only module dirs into working modules. The repo
# intentionally carries no prebuilt GPL binaries; if this step fails, the
# guards below abort the build instead of shipping an incomplete engine.
#
# Prerequisites (provided by the calling workflow):
#   - rustup with stable + cargo on PATH
#   - ANDROID_NDK_HOME pointing at an NDK (r29 works for the Rust crates)
#   - ANDROID_HOME with platforms;android-36, build-tools;36.0.0,
#     ndk;27.3.13750724, cmake;3.22.1 (TEESimulator-RS Gradle pins NDK 27.3)
#   - JDK 21 as JAVA_HOME (TEESimulator-RS targets JVM 21)
#   - cargo-ndk on PATH (TEESimulator-RS Gradle task buildRustCertgen)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILTIN="$ROOT/userspace/ksud/builtin"
TS_SRC="$ROOT/third_party/TEESimulator-RS"
TA_SRC="$ROOT/third_party/tricky-addon-enhanced"

TARGET="aarch64-linux-android"
ABI="arm64-v8a"

log() { printf '\n==> %s\n' "$*"; }

require_nonempty_file() {
    local path="$1"
    [ -s "$path" ] || {
        echo "required build output missing or empty: $path" >&2
        exit 1
    }
}

# Fail before copying anything when an output is the wrong architecture.  A
# successful Gradle/Cargo invocation is not sufficient: RustEmbed would pack a
# partial or host-architecture tree and the device would only fail at boot.
require_aarch64_elf() {
    local path="$1" readelf_bin
    require_nonempty_file "$path"
    readelf_bin="$(command -v llvm-readelf || command -v readelf || true)"
    [ -n "$readelf_bin" ] || {
        echo "cannot validate ELF output (llvm-readelf/readelf not found): $path" >&2
        exit 1
    }
    "$readelf_bin" -h "$path" 2>/dev/null | grep -qE 'Class:[[:space:]]+ELF64' || {
        echo "output is not ELF64: $path" >&2
        exit 1
    }
    "$readelf_bin" -h "$path" 2>/dev/null | grep -qE 'Machine:[[:space:]]+AArch64' || {
        echo "output is not AArch64: $path" >&2
        exit 1
    }
}

ndk_llvm_bin() {
    local ndk="${ANDROID_NDK_HOME:-}"
    if [ -z "$ndk" ]; then
        echo "ANDROID_NDK_HOME is not set" >&2
        exit 1
    fi
    local bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    [ -d "$bin" ] || { echo "NDK llvm bin not found at $bin" >&2; exit 1; }
    printf '%s' "$bin"
}

# ---------------------------------------------------------------------------
# 1) TA_enhanced: ta-enhanced daemon + resetprop-rs CLI from vendored Rust
# ---------------------------------------------------------------------------
build_ta_enhanced() {
    log "Building ta-enhanced + resetprop-rs ($TARGET)"
    rustup target add "$TARGET"

    local llvm_bin wrap_api="" api
    llvm_bin="$(ndk_llvm_bin)"
    export PATH="$llvm_bin:$PATH"
    # .cargo/config.toml from upstream main pins android26 wrappers, but the
    # vendored v5.27.0 tree has no config.toml and newer NDKs may drop old
    # wrapper APIs — pick the lowest wrapper that exists and override via env.
    for api in 26 24 28 29 21 23; do
        if [ -f "$llvm_bin/aarch64-linux-android${api}-clang" ]; then
            wrap_api="$api"
            break
        fi
    done
    [ -n "$wrap_api" ] || { echo "no aarch64-linux-android wrapper in NDK" >&2; exit 1; }
    echo "    using android${wrap_api} wrapper"

    export CC_aarch64_linux_android="$llvm_bin/aarch64-linux-android${wrap_api}-clang"
    export AR_aarch64_linux_android="$llvm_bin/llvm-ar"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"

    (cd "$TA_SRC/rust" && cargo build --release --target "$TARGET")
    local ta_bin="$TA_SRC/rust/target/$TARGET/release/ta-enhanced"
    require_aarch64_elf "$ta_bin"
    "$llvm_bin/llvm-strip" "$ta_bin" 2>/dev/null || strip "$ta_bin" || true
    require_aarch64_elf "$ta_bin"
    mkdir -p "$BUILTIN/TA_enhanced/bin/$ABI"
    cp -f "$ta_bin" "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced"
    require_aarch64_elf "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced"

    # Standalone CLI: crate binary is named `resetprop`; the module scripts
    # stage and invoke it as `resetprop-rs`, so keep that file name.
    (cd "$TA_SRC/external/resetprop-rs" && cargo build --release -p resetprop-cli --target "$TARGET")
    local rp_bin="$TA_SRC/external/resetprop-rs/target/$TARGET/release/resetprop"
    require_aarch64_elf "$rp_bin"
    "$llvm_bin/llvm-strip" "$rp_bin" 2>/dev/null || strip "$rp_bin" || true
    require_aarch64_elf "$rp_bin"
    cp -f "$rp_bin" "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"
    require_aarch64_elf "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"
}

# ---------------------------------------------------------------------------
# 2) tricky_store: TEESimulator-RS engine via its own Gradle zipRelease
# ---------------------------------------------------------------------------
build_tricky_store() {
    log "Building TEESimulator-RS engine (Gradle zipRelease)"
    command -v cargo-ndk >/dev/null || {
        echo "cargo-ndk not found (the Gradle buildRustCertgen task needs it)" >&2
        exit 1
    }
    rustup target add "$TARGET"
    cd "$TS_SRC"
    ./gradlew zipRelease --no-daemon -q

    local zip stage
    zip="$(ls -t out/TEESimulator-RS-*-Release.zip 2>/dev/null | head -1 || true)"
    [ -n "$zip" ] || { echo "no TEESimulator-RS release zip in out/" >&2; exit 1; }
    stage="$(mktemp -d)"
    unzip -q "$zip" -d "$stage"

    local libdir="$stage/lib/$ABI"
    require_nonempty_file "$stage/classes.dex"
    require_aarch64_elf "$libdir/libTEESimulator.so"
    require_aarch64_elf "$libdir/libcertgen.so"
    require_aarch64_elf "$libdir/libinject.so"
    require_aarch64_elf "$libdir/libsupervisor.so"
    install -m 644 "$stage/classes.dex" "$BUILTIN/tricky_store/classes.dex"
    install -m 644 "$libdir/libTEESimulator.so" "$BUILTIN/tricky_store/libTEESimulator.so"
    install -m 644 "$libdir/libcertgen.so" "$BUILTIN/tricky_store/libcertgen.so"
    # Magisk/KSU module convention ships executables as lib*.so under lib/<abi>;
    # our builtin layout uses the bare names the staging scripts expect.
    install -m 755 "$libdir/libinject.so" "$BUILTIN/tricky_store/inject"
    install -m 755 "$libdir/libsupervisor.so" "$BUILTIN/tricky_store/supervisor"
    rm -rf "$stage"
}

build_ta_enhanced
build_tricky_store

log "Validating final built-in module manifest"
for path in \
    "$BUILTIN/tricky_store/classes.dex" \
    "$BUILTIN/tricky_store/libTEESimulator.so" \
    "$BUILTIN/tricky_store/libcertgen.so" \
    "$BUILTIN/tricky_store/inject" \
    "$BUILTIN/tricky_store/supervisor" \
    "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced" \
    "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"; do
    require_nonempty_file "$path"
done
for path in \
    "$BUILTIN/tricky_store/libTEESimulator.so" \
    "$BUILTIN/tricky_store/libcertgen.so" \
    "$BUILTIN/tricky_store/inject" \
    "$BUILTIN/tricky_store/supervisor" \
    "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced" \
    "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"; do
    require_aarch64_elf "$path"
done
ls -l "$BUILTIN/tricky_store/classes.dex" \
      "$BUILTIN/tricky_store/libTEESimulator.so" \
      "$BUILTIN/tricky_store/libcertgen.so" \
      "$BUILTIN/tricky_store/inject" \
      "$BUILTIN/tricky_store/supervisor" \
      "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced" \
      "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"
echo "OK"
