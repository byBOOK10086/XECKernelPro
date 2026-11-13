#!/usr/bin/env bash
# Build the built-in modules from the vendored sources under third_party/ and
# drop the artifacts into userspace/ksud/builtin/.
#
# Must run BEFORE the ksud `cargo build`: RustEmbed packs whatever is inside
# userspace/ksud/builtin/ at compile time, so this script is what turns the
# committed script/config-only module dirs into working modules. The repo
# intentionally carries no prebuilt GPL binaries; if this step fails, the
# guards below abort the build instead of shipping an incomplete engine.
#
# The TEE engine itself is Oh My Keymint (third_party/OhMyKeymint, GLA v4, not
# GPL): its binaries are vendored as-is and copied verbatim, with the license
# files travelling next to them as GLA 4.1/4.4 requires. We do not rebuild it —
# see third_party/OhMyKeymint/UPSTREAM.md for the upgrade procedure.
#
# Prerequisites (provided by the calling workflow):
#   - rustup with stable + cargo on PATH
#   - ANDROID_NDK_HOME pointing at an NDK (r29 works for the Rust crates)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILTIN="$ROOT/userspace/ksud/builtin"
TA_SRC="$ROOT/third_party/tricky-addon-enhanced"
OMK_MOD="$ROOT/third_party/OhMyKeymint/module"

TARGET="aarch64-linux-android"
ABI="arm64-v8a"

log() { printf '\n==> %s\n' "$*"; }

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
    # 上游 v5.53.1 自带 .cargo/config.toml，把 linker 钉成 android26 wrapper；
    # 我们 vendor 时刻意没有收这个文件（见 third_party/tricky-addon-enhanced/UPSTREAM.md），
    # 因为新 NDK 可能不提供那个 wrapper。这里按实际存在的 wrapper 选，并通过
    # CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER 覆盖，保证换 NDK 也能编。
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
    [ -f "$ta_bin" ] || { echo "ta-enhanced binary missing" >&2; exit 1; }
    "$llvm_bin/llvm-strip" "$ta_bin" 2>/dev/null || strip "$ta_bin" || true
    mkdir -p "$BUILTIN/TA_enhanced/bin/$ABI"
    cp -f "$ta_bin" "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced"

    # Standalone CLI: crate binary is named `resetprop`; the module scripts
    # stage and invoke it as `resetprop-rs`, so keep that file name.
    (cd "$TA_SRC/external/resetprop-rs" && cargo build --release -p resetprop-cli --target "$TARGET")
    local rp_bin="$TA_SRC/external/resetprop-rs/target/$TARGET/release/resetprop"
    [ -f "$rp_bin" ] || { echo "resetprop binary missing" >&2; exit 1; }
    "$llvm_bin/llvm-strip" "$rp_bin" 2>/dev/null || strip "$rp_bin" || true
    cp -f "$rp_bin" "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"
}

# ---------------------------------------------------------------------------
# 2) tricky_store: Oh My Keymint engine, copied verbatim
# ---------------------------------------------------------------------------
# 上游 zip 里每个载荷都配了 `<file>.sha256`（只有哈希、没有文件名），逐字节核验
# 之后再拷，防止仓库里那份被改过或解压坏掉。
verify_omk_payload() {
    local f expected actual
    for f in daemon daemon-injector "libs/$ABI/keymint" "libs/$ABI/inject"; do
        [ -f "$OMK_MOD/$f" ] || { echo "OhMyKeymint payload missing: $OMK_MOD/$f" >&2; exit 1; }
        [ -f "$OMK_MOD/$f.sha256" ] || { echo "OhMyKeymint checksum missing: $OMK_MOD/$f.sha256" >&2; exit 1; }
        expected="$(tr -d ' \r\n' < "$OMK_MOD/$f.sha256")"
        actual="$(sha256sum "$OMK_MOD/$f" | cut -d' ' -f1)"
        [ "$actual" = "$expected" ] || {
            echo "OhMyKeymint payload checksum mismatch: $f" >&2
            echo "  expected $expected" >&2
            echo "  actual   $actual" >&2
            exit 1
        }
        echo "    verified $f"
    done
}

build_tricky_store() {
    log "Staging Oh My Keymint engine (verbatim)"
    [ -d "$OMK_MOD" ] || { echo "OhMyKeymint module tree missing at $OMK_MOD" >&2; exit 1; }
    verify_omk_payload

    local dst="$BUILTIN/tricky_store/engine" f
    rm -rf "$dst"
    mkdir -p "$dst/libs/$ABI"
    install -m 755 "$OMK_MOD/daemon" "$dst/daemon"
    install -m 755 "$OMK_MOD/daemon-injector" "$dst/daemon-injector"
    install -m 755 "$OMK_MOD/libs/$ABI/keymint" "$dst/libs/$ABI/keymint"
    install -m 755 "$OMK_MOD/libs/$ABI/inject" "$dst/libs/$ABI/inject"
    # GLA §4.1/§4.4: the agreement, the author attribution and the release URL
    # must travel with every distributed copy of the work.
    for f in LICENSE.1 LICENSE.2 AOSP.Apache-license-2.0.txt README.md; do
        install -m 644 "$OMK_MOD/$f" "$dst/$f"
    done
}

build_ta_enhanced
build_tricky_store

log "Built-in module artifacts:"
ls -l "$BUILTIN/tricky_store/engine/libs/$ABI/keymint" \
      "$BUILTIN/tricky_store/engine/libs/$ABI/inject" \
      "$BUILTIN/TA_enhanced/bin/$ABI/ta-enhanced" \
      "$BUILTIN/TA_enhanced/bin/$ABI/resetprop-rs"
echo "OK"
