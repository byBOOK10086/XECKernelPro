# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
MODPATH=${0%/*}
TS="/dev/.xudc_hidden/tricky_store"
LOG_BASE_DIR="/data/adb/.xudc_secure/ta-enhanced/logs"
BOOT_LOG="$LOG_BASE_DIR/boot.log"

# Defensive logger -- /data may not be fully decrypted
_pfd_log() {
    local ts msg
    ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "unknown")
    msg="[$ts] [POST-FS-DATA] $1"
    if [ -d "$LOG_BASE_DIR" ] && [ -w "$LOG_BASE_DIR" ]; then
        echo "$msg" >> "$BOOT_LOG" 2>/dev/null && return 0
    fi
    echo "$msg" >&2
}

_pfd_log "post-fs-data started"

# Wait for the built-in TrickyStore engine dir. Built-in modules are
# materialized under /dev/.xudc_hidden BEFORE this stage runs, so this
# normally returns immediately; short cap just in case.
_wait_count=0
while [ ! -d "$TS" ]; do
    _wait_count=$((_wait_count + 1))
    [ "$_wait_count" -ge 10 ] && break
    sleep 0.2
done
_pfd_log "TrickyStore engine dir ready (waited ${_wait_count} iterations)"

# Self-removal if TrickyStore missing
if [ ! -d "$TS" ] || [ -f "$TS/remove" ]; then
    _pfd_log "TrickyStore missing or removing - marking self for removal"
    if [ -f "$MODPATH/action.sh" ]; then
        # Magisk hidden module: recreate stub at real ID
        rm -rf "/data/adb/modules/TA_enhanced" 2>/dev/null
        mkdir -p "/data/adb/modules/TA_enhanced"
        touch "/data/adb/modules/TA_enhanced/remove"
    else
        touch "$MODPATH/remove"
    fi
fi

# Clean stale symlinks
[ -L "$TS/webroot" ] && rm -f "$TS/webroot"
[ -L "$TS/action.sh" ] && rm -f "$TS/action.sh"
[ -L "$TS/banner.png" ] && rm -f "$TS/banner.png"

# Root Manager Detection
if [ -n "$APATCH" ]; then
    MANAGER="APATCH"
elif [ -n "$KSU" ]; then
    MANAGER="KSU"
else
    MANAGER="MAGISK"
fi

# Persist manager for service.sh
echo "MANAGER=$MANAGER" > "$MODPATH/common/manager.sh"
chmod 755 "$MODPATH/common/manager.sh"
_pfd_log "Root manager detected: $MANAGER"

# ---------------------------------------------------------------------------
# bootloader 回锁态：在**第一个能写属性的时机**就落笔
# ---------------------------------------------------------------------------
# 这是本阶段存在的唯一理由：LKM 模式下这些属性是 bootloader 原样上报的
# （verifiedbootstate=orange / flash.locked=0），而检测方在开机后任意时刻都可能
# 读它们。原实现放在 service 阶段、并且要先等 sys.boot_completed，等于把整个
# 开机过程都暴露成"已解锁"。这里提前写一次，service 阶段（prop.sh）再写一次，
# 之后由 service.sh 启动的看护循环持续兜底。
if [ -f "$MODPATH/common/bootstate.sh" ]; then
    MODDIR="$MODPATH"
    mkdir -p "$LOG_BASE_DIR" 2>/dev/null
    . "$MODPATH/common/common.sh"
    . "$MODPATH/common/bootstate.sh"
    _PROP_SPOOF_COUNT=0
    _PROP_FAIL_COUNT=0
    _pfd_log "boot-state spoof (early) starting"
    bootstate_spoof_lock
    # 0/0 有两种截然不同的含义：属性本来就已经正确（无事可做），或者整组被
    # ZeroMount 豁免跳过了。后者必须显式写出来，否则这行日志会被读成"一切正常"。
    if [ "$BOOTSTATE_SKIPPED" = "true" ]; then
        _pfd_log "boot-state spoof (early) SKIPPED (ZeroMount deferral active): $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed"
    else
        _pfd_log "boot-state spoof (early) done: $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed"
    fi
    _pfd_log "early boot-state: device_state=$(getprop ro.boot.vbmeta.device_state) verifiedboot=$(getprop ro.boot.verifiedbootstate) flash.locked=$(getprop ro.boot.flash.locked)"
else
    _pfd_log "common/bootstate.sh missing - early boot-state spoof skipped"
fi

_pfd_log "post-fs-data completed"
