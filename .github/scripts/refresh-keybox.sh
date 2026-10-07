#!/usr/bin/env bash
# Refresh the keybox that gets embedded into the built-in tricky_store module.
# 新增文件（2026-10-08）：本项目自有 CI 辅助脚本。
#
# 为什么在**构建时**拉 keybox：
#   设备端（尤其中国大陆）到 raw.githubusercontent.com / jsdelivr 的连通性都不
#   可靠，光靠设备端拉取会出现"刷完一直拿不到新箱子"的形态。GitHub Actions 的
#   runner 没有这个限制，所以在打包 ksud（RustEmbed 会把 userspace/ksud/builtin/
#   整个目录打进二进制）之前把上游最新的 keybox 写进内置模块目录，
#   一次构建 = 一份新的可信密钥随包分发；设备端只要能刷入模块就拿到了，
#   **完全不需要联网**。设备端再到网络上轮换新箱子是加分项，不是必需条件。
#
# 行为约定：
#   * 拉不到任何源时**不失败**（保留仓库里已有的那份，并打 ::warning::），
#     因为"用旧箱子"总比"构建挂掉/内置一份空文件"好。
#   * 只有解出来确实是 XML 结构（含 <AndroidAttestation>、<Keybox、多张证书）
#     才写回工作区，绝不把错误页面 / base64 残渣打进产物。
#   * 只改工作区文件，不提交：产物随构建走，仓库里的版本仍是一份可用兜底。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="$ROOT/userspace/ksud/builtin/tricky_store/keybox.xml"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 源按"新鲜度"排序，而不是按"可达性"排序：
#   1) yurikey 原源（base64）—— 唯一的一手来源，永远最新；
#   2) yurikey 经镜像（base64）；
#   3) 本项目 keybox 分支（明文，由 update-keybox.yml 每 15 分钟从 1) 镜像过来）。
# 顺序不能反：先试本项目分支的话，一旦定时镜像停摆，构建会**安静地**嵌进旧箱子
# （下面的代次保护只能拦住"代次更低"，拦不住"停在原地"）。
B64_SOURCES=(
    "https://raw.githubusercontent.com/Yurii0307/yurikey/main/key"
    "https://raw.gitmirror.com/Yurii0307/yurikey/main/key"
    "https://fastly.jsdelivr.net/gh/Yurii0307/yurikey@main/key"
)
PLAIN_SOURCES=(
    "https://raw.githubusercontent.com/byBOOK10086/XECKernelPro/keybox/keybox.xml"
    "https://raw.gitmirror.com/byBOOK10086/XECKernelPro/keybox/keybox.xml"
)

log() { printf '==> %s\n' "$*"; }

looks_like_keybox() {
    local f="$1"
    [ -s "$f" ] || return 1
    grep -q '<AndroidAttestation' "$f" || return 1
    grep -q '<Keybox' "$f" || return 1
    [ "$(grep -c 'BEGIN CERTIFICATE' "$f")" -ge 2 ] || return 1
    return 0
}

device_id() {
    sed -n 's/.*DeviceID="\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -n 1
}

generation() {
    sed -n 's/.*Yurikey\([0-9][0-9]*\).*/\1/p' "$1" 2>/dev/null | head -n 1
}

fetch() {
    # fetch <url> <out>
    curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 60 \
        -o "$2" "$1" 2>/dev/null && [ -s "$2" ]
}

CANDIDATE="$WORK/keybox.xml"
FOUND=""

for url in "${B64_SOURCES[@]}"; do
    log "trying base64 source: $url"
    if fetch "$url" "$WORK/key.b64"; then
        if base64 -d "$WORK/key.b64" > "$CANDIDATE" 2>/dev/null && looks_like_keybox "$CANDIDATE"; then
            FOUND="$url (base64)"
            break
        fi
    fi
    rm -f "$CANDIDATE"
done

if [ -z "$FOUND" ]; then
    for url in "${PLAIN_SOURCES[@]}"; do
        log "trying plain source: $url"
        if fetch "$url" "$CANDIDATE" && looks_like_keybox "$CANDIDATE"; then
            FOUND="$url"
            break
        fi
        rm -f "$CANDIDATE"
    done
fi

if [ -z "$FOUND" ]; then
    # 拿不到就保留仓库里那份。这里刻意不 exit 1：网络抖动不该让整个内核/管理器
    # 构建失败，而仓库里的内置副本本身就是设备端的离线兜底。
    echo "::warning::keybox refresh failed from all sources; keeping the committed bundled copy"
    if [ -f "$TARGET" ]; then
        log "committed copy: DeviceID=$(device_id "$TARGET") sha256=$(sha256sum "$TARGET" | cut -c1-16)"
    else
        echo "::error::no bundled keybox at $TARGET either"
        exit 1
    fi
    exit 0
fi

NEW_GEN="$(generation "$CANDIDATE")"
OLD_GEN=""
[ -f "$TARGET" ] && OLD_GEN="$(generation "$TARGET")"

log "fetched from $FOUND"
log "new: DeviceID=$(device_id "$CANDIDATE") generation=${NEW_GEN:-?} sha256=$(sha256sum "$CANDIDATE" | cut -c1-16) bytes=$(wc -c < "$CANDIDATE")"
if [ -f "$TARGET" ]; then
    log "old: DeviceID=$(device_id "$TARGET") generation=${OLD_GEN:-?} sha256=$(sha256sum "$TARGET" | cut -c1-16)"
fi

# 代次保护：只在"新代次不低于旧代次"或"旧文件无法识别代次"时替换，
# 避免某个镜像回源到旧快照时把更新的内置副本降级。
if [ -n "$NEW_GEN" ] && [ -n "$OLD_GEN" ] && [ "$NEW_GEN" -lt "$OLD_GEN" ]; then
    echo "::warning::fetched keybox is older (Yurikey$NEW_GEN < Yurikey$OLD_GEN); keeping committed copy"
    exit 0
fi

if [ -f "$TARGET" ] && cmp -s "$CANDIDATE" "$TARGET"; then
    log "bundled keybox already up to date"
    exit 0
fi

install -m 644 "$CANDIDATE" "$TARGET"
log "bundled keybox updated: $TARGET"
log "embedded copy: DeviceID=$(device_id "$TARGET") sha256=$(sha256sum "$TARGET" | cut -c1-16)"
