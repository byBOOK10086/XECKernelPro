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

# TrickyStore Paths
TS="/dev/.xudc_hidden/tricky_store"
TS_DIR="/data/adb/.xudc_secure"

# Unified log directory -- shell and Rust daemon both log here
LOG_BASE_DIR="/data/adb/.xudc_secure/ta-enhanced/logs"

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
    [ -x "$RP" ] || { _log "WARN" "resetprop-rs not found at $RP, skipping hexpatch"; return 1; }
    for search_string in "$@"; do
        getprop | cut -d'[' -f2 | cut -d']' -f1 | grep "$search_string" | while read -r prop_name; do
            if "$RP" --hexpatch-delete "$prop_name" 2>/dev/null; then
                _log "DEBUG" "hexpatch: $prop_name"
            fi
        done
    done
}
