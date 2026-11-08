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

# 叶子证书 = 链上第一张。上游 yurikey 每次换代的正是这张**短命**证书
# （Yurikey67 的叶子只活 12 天：2026-10-25 → 2026-11-06），所以"代次更新"
# 不等于"还能用"：67 代就是最最新的一代，且叶子在 2026-11-06 已经过期。
# 判据必须是 notAfter，而不是代次。
keybox_leaf_enddate() {
    local f="$1" pem
    [ -s "$f" ] || return 1
    pem="$WORK/leaf.pem"
    # 三种形态都要吃得下：①缩进在 XML 里、②PEM 被压成一整行（仓库兜底那份就是）、
    # ③CRLF 检出。openssl 3.x 要求 BEGIN/END 各自顶格独占一行，所以这里不是
    # "抽几行"而是**把 base64 抽出来重新按 64 列折行**。
    awk '
        /-----BEGIN CERTIFICATE-----/ { inside = 1; buf = "" }
        inside {
            line = $0
            sub(/.*-----BEGIN CERTIFICATE-----/, "", line)
            ended = ($0 ~ /-----END CERTIFICATE-----/)
            sub(/-----END CERTIFICATE-----.*/, "", line)
            gsub(/[^A-Za-z0-9+\/=]/, "", line)
            buf = buf line
            if (ended) {
                print "-----BEGIN CERTIFICATE-----"
                for (i = 1; i <= length(buf); i += 64) print substr(buf, i, 64)
                print "-----END CERTIFICATE-----"
                exit
            }
        }' "$f" > "$pem" || return 1
    [ -s "$pem" ] || return 1
    openssl x509 -in "$pem" -noout -enddate 2>/dev/null | sed 's/^notAfter=//'
}

keybox_leaf_epoch() {
    local end
    end="$(keybox_leaf_enddate "$1")" || return 1
    [ -n "$end" ] || return 1
    date -u -d "$end" +%s 2>/dev/null
}

fetch() {
    # fetch <url> <out>
    curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 60 \
        -o "$2" "$1" 2>/dev/null && [ -s "$2" ]
}

CANDIDATE="$WORK/keybox.xml"
FOUND=""

# 本地自检模式：`refresh-keybox.sh --check <keybox.xml>`
# 只解析文件、不联网，用来在改脚本/排查"为什么还是过期证书"时直接验证判据。
if [ "${1:-}" = "--check" ]; then
    KB="${2:?usage: refresh-keybox.sh --check <keybox.xml>}"
    END="$(keybox_leaf_enddate "$KB" || true)"
    EPOCH="$(keybox_leaf_epoch "$KB" || true)"
    NOW="$(date -u +%s)"
    printf 'file       : %s\n' "$KB"
    printf 'generation : %s\n' "$(generation "$KB" || true)"
    printf 'leaf end   : %s\n' "${END:-<unreadable>}"
    if [ -z "$EPOCH" ]; then
        echo "verdict    : UNKNOWN (openssl/date 不可用，或不是 keybox)"
        exit 2
    fi
    if [ "$EPOCH" -le "$NOW" ]; then
        echo "verdict    : EXPIRED"
        exit 1
    fi
    echo "verdict    : VALID"
    exit 0
fi

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

# 有效期守卫（2026-11-08 加）：上游 RKP 箱子的叶子证书只有十几天寿命，
# 所以**代次更新 ≠ 还能用**。v31410 就是这样把一份"刚过期两天的最新箱子"
# 嵌进了产物。
#
# 但真机对照实验（K60E，2026-11-08）证明了**不能自动降级**：
#   67 + 有效 → Duck 红在 Bootloader（cert2 expired）
#   58 + 有效 → Duck 红在 TEE（老式子链与当前引擎不一致）
# 两者都恰好 1 个 Danger —— 换箱子只是把红灯从一张卡挪到另一张卡。而 67 是
# 真 RKP 链、根仍是 Google，长相比"用五年前的老箱子"干净；仓库里那 58 只当
# **离线兜底**（网络全断时设备照样有箱子可用），不该因为过期就优先它。
#
# 所以这里的判据是：**过期只告警，不替换**。日志里点名到期时间，让人一眼看到
# "上游还没换代"，而不是悄悄换成老箱子。
LEAF_END="$(keybox_leaf_enddate "$CANDIDATE" 2>/dev/null || true)"
LEAF_EPOCH="$(keybox_leaf_epoch "$CANDIDATE" 2>/dev/null || true)"
NOW_EPOCH="$(date -u +%s)"
if [ -n "$LEAF_EPOCH" ] && [ "$LEAF_EPOCH" -le "$NOW_EPOCH" ]; then
    echo "::warning::fetched keybox leaf already expired ($LEAF_END) — upstream has not rotated yet; shipping it anyway (see refresh-keybox.sh comments for the 2026-11-08 experiment)"
    if [ -f "$TARGET" ]; then
        log "committed leaf end: $(keybox_leaf_enddate "$TARGET" 2>/dev/null || echo '<unreadable>')"
    fi
else
    log "candidate leaf valid until ${LEAF_END:-<unreadable>}"
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

# Windows/CRLF 检出：内容相同、只有换行差异时**不要**当成更新。否则日志会撒谎
# （说"updated"而 git blob 其实没变），而且会把工作区文件从 CRLF 改写成 LF，
# 让下一次 diff 看起来像有改动。（这个坑真的骗到过一次分析：sha256 一个 dc847feb
# 一个 a0a3da9c，看着像上游同代次换箱，实际只是 CRLF vs LF。）
if [ -f "$TARGET" ] && cmp -s <(tr -d '\r' < "$CANDIDATE") <(tr -d '\r' < "$TARGET"); then
    log "bundled keybox content identical (line-ending only difference); leaving file untouched"
    exit 0
fi

install -m 644 "$CANDIDATE" "$TARGET"
log "bundled keybox updated: $TARGET"
log "embedded copy: DeviceID=$(device_id "$TARGET") sha256=$(sha256sum "$TARGET" | cut -c1-16)"
