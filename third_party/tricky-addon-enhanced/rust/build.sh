#!/usr/bin/env bash
# Cross-compile ta-enhanced for Android targets
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RUST_DIR="$SCRIPT_DIR"
BIN_DIR="$PROJECT_ROOT/bin"

declare -A ABI_TARGET=(
    [arm64-v8a]=aarch64-linux-android
    [armeabi-v7a]=armv7-linux-androideabi
    [x86_64]=x86_64-linux-android
    [x86]=i686-linux-android
)

find_ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        echo "$ANDROID_NDK_HOME"
        return 0
    fi

    # $ANDROID_HOME/ndk/ contains versioned subdirs
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        local latest
        latest=$(ls -1 "$ANDROID_HOME/ndk" 2>/dev/null | sort -V | tail -1)
        if [ -n "$latest" ] && [ -d "$ANDROID_HOME/ndk/$latest/toolchains" ]; then
            echo "$ANDROID_HOME/ndk/$latest"
            return 0
        fi
    fi

    if [ -n "${NDK_HOME:-}" ] && [ -d "$NDK_HOME" ]; then
        echo "$NDK_HOME"
        return 0
    fi

    # Common SDK locations
    local sdk_dirs=(
        "$HOME/Android/Sdk/ndk"
        "$HOME/Library/Android/sdk/ndk"
    )
    for sdk in "${sdk_dirs[@]}"; do
        if [ -d "$sdk" ]; then
            local latest
            latest=$(ls -1 "$sdk" 2>/dev/null | sort -V | tail -1)
            if [ -n "$latest" ] && [ -d "$sdk/$latest/toolchains" ]; then
                echo "$sdk/$latest"
                return 0
            fi
        fi
    done

    # Standalone NDK installs
    local standalone=(
        "/opt/android-ndk"
        "/opt/android-ndk-r26d"
        "/opt/android-ndk-r25b"
    )
    for path in "${standalone[@]}"; do
        if [ -d "$path/toolchains" ]; then
            echo "$path"
            return 0
        fi
    done

    return 1
}

NDK_HOME=$(find_ndk) || {
    echo "FATAL: Android NDK not found." >&2
    echo "Set ANDROID_NDK_HOME, ANDROID_HOME/ndk/, or NDK_HOME." >&2
    exit 1
}

NDK_BIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$NDK_BIN" ]; then
    echo "FATAL: NDK toolchain not found at $NDK_BIN" >&2
    exit 1
fi

export PATH="$NDK_BIN:$PATH"
echo "NDK: $NDK_HOME"

# cc-rs env vars so ring/other C deps find the right compiler
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android26-clang"
export CC_armv7_linux_androideabi="$NDK_BIN/armv7a-linux-androideabi26-clang"
export CC_x86_64_linux_android="$NDK_BIN/x86_64-linux-android26-clang"
export CC_i686_linux_android="$NDK_BIN/i686-linux-android26-clang"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
export AR_armv7_linux_androideabi="$NDK_BIN/llvm-ar"
export AR_x86_64_linux_android="$NDK_BIN/llvm-ar"
export AR_i686_linux_android="$NDK_BIN/llvm-ar"

if ! command -v cargo >/dev/null 2>&1; then
    echo "FATAL: cargo not found. Install Rust toolchain." >&2
    exit 1
fi

for abi in "${!ABI_TARGET[@]}"; do
    target="${ABI_TARGET[$abi]}"
    if ! rustup target list --installed | grep -q "$target"; then
        echo "Installing Rust target: $target"
        rustup target add "$target"
    fi
done

PROFILE="${1:-release}"

# cd into rust dir so .cargo/config.toml linker settings are found
cd "$RUST_DIR"
CARGO_FLAGS=()

if [ "$PROFILE" = "release" ]; then
    CARGO_FLAGS+=(--release)
fi

STRIP="$NDK_BIN/llvm-strip"
if [ ! -x "$STRIP" ]; then
    STRIP=$(command -v llvm-strip 2>/dev/null || command -v strip 2>/dev/null || true)
fi

built=0
failed=0

for abi in "${!ABI_TARGET[@]}"; do
    target="${ABI_TARGET[$abi]}"
    echo ""
    echo "=== Building $abi ($target) [$PROFILE] ==="

    if cargo build "${CARGO_FLAGS[@]}" --target "$target"; then
        src="target/$target/$PROFILE/ta-enhanced"
        dst_dir="$BIN_DIR/$abi"
        mkdir -p "$dst_dir"
        cp "$src" "$dst_dir/ta-enhanced"

        if [ "$PROFILE" = "release" ] && [ -n "$STRIP" ] && [ -x "$STRIP" ]; then
            "$STRIP" "$dst_dir/ta-enhanced"
        fi

        built=$((built + 1))
    else
        echo "FAILED: $abi" >&2
        failed=$((failed + 1))
    fi
done

echo ""
echo "=== Build Summary ==="
for abi in "${!ABI_TARGET[@]}"; do
    bin="$BIN_DIR/$abi/ta-enhanced"
    if [ -f "$bin" ]; then
        echo "  $abi: $(du -h "$bin" | cut -f1)"
    fi
done
echo "$built succeeded, $failed failed"

if [ "$failed" -gt 0 ]; then
    exit 1
fi
