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

_pfd_log "post-fs-data completed"
