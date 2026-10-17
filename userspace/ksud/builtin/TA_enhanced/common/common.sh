# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 上级目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
# common.sh - Shared utilities for TA_enhanced module scripts
# Sourced by: service.sh, action.sh, uninstall.sh, prop.sh

# ABI Detection
# KSU/APatch set $ARCH during install; at runtime fall back to uname
if [ -n "$ARCH" ]; then
    case "$ARCH" in
        arm64) ABI=arm64-v8a ;;
        arm)   ABI=armeabi-v7a ;;
        *)     ABI="" ;;
    esac
else
    case "$(uname -m)" in
        aarch64)       ABI=arm64-v8a ;;
        armv7*|armv8l) ABI=armeabi-v7a ;;
        *)             ABI="" ;;
    esac
fi

# $MODDIR must be set by caller: MODDIR="${0%/*}" (standard KSU/Magisk convention)

# Binary Path
if [ -n "$MODDIR" ] && [ -n "$ABI" ]; then
    BIN="$MODDIR/bin/${ABI}/ta-enhanced"
fi
RP="/data/adb/.xudc_secure/ta-enhanced/bin/resetprop-rs"

# resetprop 解析阶梯（从上到下即优先级，装进 RESETPROP_BIN）：
#
#   1. `/data/adb/ksu/bin/resetprop` —— 本项目 ksud 自带的 Magisk 兼容实现
#      （`assets.rs` 把这条符号链接指向 `/data/adb/xudc`，argv[0] 分派进
#      `resetprop_main`）。**用绝对路径调用**：模块脚本的 PATH 只保证包含该
#      目录，而符号链接由守护进程在启动时创建，脚本可能更早跑到（首刷后第一次
#      开机）。绝对路径 + 存在性检查把这段时序彻底消掉。
#   2. PATH 里的任何 resetprop（Magisk / APatch / 其它管理器形态的本模块）。
#   3. 随模块一起构建的 resetprop-rs（$RP，service.sh 已 staging 到 /data）。
#   4. 一个都不在：退回 getprop 只读包装 —— 读照常，写一律返回失败，让
#      check_reset_prop / ensure_prop 把"写不进去"记成 ERROR，而不是因为读到
#      空值就静默跳过（那正是"属性一个都没伪装、bootloader 仍显示解锁"的形态）。
#
# ⚠️ 等待语义（`-w/--wait NAME VALUE`）由本包装自己实现，**绝不转发**：
# resetprop-rs 第 3 档没有 -w，转发会把 `resetprop -w sys.boot_completed 0`
# 退化成一次写操作，把 sys.boot_completed 直接写成 0（整个系统等这个属性，
# 后果是开机流程被按回未完成态）。这里统一用 getprop 轮询 + 上限超时，
# 语义与 Magisk 的 `-w` 一致（等到值不再是 VALUE），且任何一档都不会挂死。
RESETPROP_BIN=""
if [ -x /data/adb/ksu/bin/resetprop ]; then
    RESETPROP_BIN=/data/adb/ksu/bin/resetprop
elif command -v resetprop >/dev/null 2>&1; then
    RESETPROP_BIN=$(command -v resetprop)
elif [ -x "$RP" ]; then
    RESETPROP_BIN="$RP"
fi

#: `-w` 的上限秒数；等待超时返回 1（调用方自己决定记 WARN 还是继续）。
RESETPROP_WAIT_MAX=${RESETPROP_WAIT_MAX:-180}

resetprop() {
    case "$1" in
        -w|--wait)
            shift
            _rp_name=$1
            _rp_from=${2-}
            _rp_left=$RESETPROP_WAIT_MAX
            while [ "$(getprop "$_rp_name")" = "$_rp_from" ]; do
                [ "$_rp_left" -le 0 ] && return 1
                sleep 1
                _rp_left=$((_rp_left - 1))
            done
            return 0
            ;;
    esac

    if [ -z "$RESETPROP_BIN" ]; then
        case "$1" in
            -*) return 1 ;;
            *)  if [ $# -ge 2 ]; then return 1; else getprop "$1"; fi ;;
        esac
    fi
    "$RESETPROP_BIN" "$@"
}

# TrickyStore Paths
TS="/dev/.xudc_hidden/tricky_store"
TS_DIR="/data/adb/.xudc_secure"

# Unified log directory -- shell and Rust daemon both log here
LOG_BASE_DIR="/data/adb/.xudc_secure/ta-enhanced/logs"

# ---------------------------------------------------------------------------
# 用户开关（管理器「检测」页 / `ksud builtin enable|disable ta`）
# ---------------------------------------------------------------------------
# 2026-10-15 用户裁决：内置 TA 与内置 TEE 一样，从"开机必然运行"改成**用户可选**；
# 2026-10-16 追加裁决：v31121 那版最正常 ⇒ 默认值改回"开"（缺文件 = 开），只有显式关闭
# 才关。两个开关仍然互相独立，ksud 在开机时按开关决定要不要把本模块解密进 tmpfs；
# 这里复查一次是为了运行期被关掉的情况：常驻看护循环必须随之停下，
# 否则用户关了它、下一轮它又把自己拉起来。
BUILTIN_FLAG="/data/adb/.xudc_secure/builtin.conf"
builtin_flag_on() {
    # 外部模块形态（用户自己刷进来的 zip，MODPATH 在 /data/adb/modules 下）
    # 不受"内置开关"约束：刷了就是要它跑。
    case "${MODPATH:-}" in /data/adb/modules/*) return 0 ;; esac
    # 内置形态：**缺文件 / 缺键 = 开启**（v31121 的行为），只有明确的 0/false/off/no 才算关闭。
    [ -f "$BUILTIN_FLAG" ] || return 0
    grep -qiE "^[[:space:]]*$1[[:space:]]*=[[:space:]]*(0|false|off|no|disable|disabled)[[:space:]]*$" \
        "$BUILTIN_FLAG" 2>/dev/null && return 1
    return 0
}

# 用户自己刷入的 TA_enhanced 模块在不在位？
#
# 两个 daemon 会同时改 keybox / target.txt / 回锁属性，表现为互相覆盖、日志各写一半。
# 判据：/data/adb/modules 下有同名模块目录，而且**当前脚本不是从那里执行的**
#（外部那份自己当然要继续跑）。
builtin_external_ta_active() {
    local _d _self="${MODPATH:-${MODDIR:-}}"
    for _d in /data/adb/modules/TA_enhanced /data/adb/modules/.TA_enhanced; do
        [ -f "$_d/module.prop" ] || continue
        [ -f "$_d/disable" ] && continue
        [ -f "$_d/remove" ] && continue
        [ -n "$_self" ] && [ "$_self" = "$_d" ] && return 1
        return 0
    done
    return 1
}

# TEE 引擎在不在位（本项目的 tmpfs 运行目录，或用户自己装的上游模块）。
# TA 的 keybox / target 维护是给引擎用的；一个引擎都没有时启动它只会空转，
# 还会在 /data/adb/tricky_store 下写出没人读的文件。
builtin_tee_present() {
    [ -d "$TS" ] && return 0
    local _d
    for _d in /data/adb/modules/tricky_store /data/adb/modules/.tricky_store \
              /data/adb/modules/TEESimulator; do
        [ -f "$_d/module.prop" ] && [ ! -f "$_d/disable" ] && [ ! -f "$_d/remove" ] && return 0
    done
    [ -d /data/adb/tricky_store ] && [ -f /data/adb/tricky_store/keybox.xml ] && return 0
    return 1
}

# Simple Logger
# Writes to log file + logcat tag "TA_enhanced"
_log() {
    local level="$1" msg="$2"
    local ts
    ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "unknown")
    local line="[$ts] [$level] $msg"
    if [ -d "$LOG_BASE_DIR" ] && [ -w "$LOG_BASE_DIR" ]; then
        echo "$line" >> "$LOG_BASE_DIR/main.log" 2>/dev/null
    fi
    log -t "TA_enhanced" -p "${level%${level#?}}" "$msg" 2>/dev/null || true
}

# Root Manager Detection
# Sets MANAGER variable: "KSU", "APATCH", or "MAGISK"
detect_manager() {
    if [ "$KSU" = "true" ]; then
        MANAGER="KSU"
    elif [ "$APATCH" = "true" ]; then
        MANAGER="APATCH"
    else
        MANAGER="MAGISK"
    fi
}

# Config Reader (delegates to Rust binary)
read_config() {
    local key="$1" default="${2:-}"
    local val
    # $BIN 缺失/不可执行时必须留下痕迹。以前这里只会返回默认值，于是
    # keybox.enabled / keybox.source / keybox.custom_url 的判定会静默落到默认分支，
    # 表现是"配置改了却毫无反应"，而日志里一个字都没有。
    # read_config 基本都在 $( ) 里调用（子 shell），所以用哨兵文件而不是变量去重。
    if [ -z "$BIN" ] || [ ! -x "$BIN" ]; then
        if [ ! -f "$TS_DIR/.read_config_warned" ]; then
            : > "$TS_DIR/.read_config_warned" 2>/dev/null
            _log "WARN" "read_config: BIN='$BIN' is missing or not executable — every config read falls back to its default value"
        fi
        printf '%s' "$default"
        return 0
    fi
    val=$("$BIN" config get "$key" 2>/dev/null)
    printf '%s' "${val:-$default}"
}

# Language Detection
# Read system locale, map to one of 23 supported locale codes
detect_language() {
    local device_lang lang_code

    device_lang=$(getprop ro.system.locale 2>/dev/null)
    [ -z "$device_lang" ] && device_lang=$(getprop persist.sys.locale 2>/dev/null)
    [ -z "$device_lang" ] && device_lang=$(getprop ro.product.locale 2>/dev/null)

    lang_code=$(printf '%s' "$device_lang" | sed 's/_/-/g')
    case "$lang_code" in
        zh-Hans*|zh-CN*) lang_code="zh-CN" ;;
        zh-Hant*|zh-TW*) lang_code="zh-TW" ;;
        pt-BR*) lang_code="pt-BR" ;;
        pt*) lang_code="pt-BR" ;;
        es-ES*|es*) lang_code="es-ES" ;;
        *-*) lang_code="${lang_code%%-*}" ;;
    esac
    case "$lang_code" in
        ar|az|bn|de|el|en|es-ES|fa|fr|id|it|ja|ko|pl|pt-BR|ru|th|tl|tr|uk|vi|zh-CN|zh-TW) ;;
        *) lang_code="en" ;;
    esac

    TA_LANG="$lang_code"
    export TA_LANG
}

# Property spoofing primitives (shared by prop.sh and propclean.sh)
# Callers set _PROP_SPOOF_COUNT and _PROP_FAIL_COUNT before use

check_reset_prop() {
    local name="$1" expected="$2"
    local val
    val=$(resetprop "$name")
    [ -z "$val" ] && return 0
    [ "$val" = "$expected" ] && return 0
    if resetprop -n "$name" "$expected" 2>/dev/null; then
        _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
    else
        _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
        _log "ERROR" "Failed to spoof: $name"
    fi
}

contains_reset_prop() {
    local name="$1" contains="$2" newval="$3"
    case "$(resetprop "$name")" in
        *"$contains"*)
            if resetprop -n "$name" "$newval" 2>/dev/null; then
                _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
            else
                _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
                _log "ERROR" "Failed to spoof (contains): $name"
            fi
            ;;
    esac
}

replace_value_prop() {
    local name="$1" search="$2" replace="$3"
    local val new_val
    val=$(resetprop "$name")
    [ -z "$val" ] && return
    new_val=$(printf '%s' "$val" | sed "s|${search}|${replace}|g")
    [ "$val" = "$new_val" ] && return
    if resetprop -n "$name" "$new_val" 2>/dev/null; then
        _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
    else
        _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
        _log "ERROR" "Failed to replace in: $name"
    fi
}

hexpatch_deleteprop() {
    # 内核属性区里的"删除"只有 resetprop-rs 的 --hexpatch-delete 能做
    # （ksud 自带 resetprop 走 property_service / 直接 mmap 写，没有 hexpatch
    # 模式）。缺它时指纹类属性只能留在属性表里被读到，所以这条 WARN 必须
    # 说清后果，而不是一句"skipping hexpatch"。
    [ -x "$RP" ] || {
        _log "WARN" "resetprop-rs missing at $RP — hexpatch-delete unavailable; fingerprint/init.svc props stay readable this boot"
        return 1
    }
    for search_string in "$@"; do
        getprop | cut -d'[' -f2 | cut -d']' -f1 | grep "$search_string" | while read -r prop_name; do
            if "$RP" --hexpatch-delete "$prop_name" 2>/dev/null; then
                _log "DEBUG" "hexpatch: $prop_name"
            fi
        done
    done
}
