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

# 用户开关 + 归属判定先做：关掉的内置 TA 什么都不该做（ksud 在开机时已经按开关决定
# 要不要把本模块解密进 tmpfs，这里是运行期/手工调用路径上的第二道闸门）。
MODDIR="$MODPATH"
. "$MODPATH/common/common.sh"
if ! builtin_flag_on ta; then
    _pfd_log "内置 TA 已被用户关闭（$BUILTIN_FLAG 里 ta=0），本次不启动"
    exit 0
fi
if builtin_external_ta_active; then
    _pfd_log "外部 TA_enhanced 模块在位（/data/adb/modules/TA_enhanced）——内置那份让位，避免两个 daemon 同时改 keybox/属性"
    exit 0
fi

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

# 引擎缺失：只记日志，**绝不**删/标记任何模块目录。
#
# 旧上游逻辑在这里做的是"自毁"：`rm -rf /data/adb/modules/TA_enhanced` 然后再建一个
# 带 remove 的目录（Magisk 隐藏模块形态），或者 `touch "$MODPATH/remove"`。两条路都
# 会让 TA_enhanced 从管理器的模块列表里消失——用户 2026-10-15 报的正是这个现象。
# 内置那份跑在 tmpfs 里，写 remove 没有任何意义；用户自己刷的那份要退出，也应该由
# 用户自己决定（管理器里停用/卸载），不该由脚本替他删。
if [ ! -d "$TS" ]; then
    if builtin_tee_present; then
        _pfd_log "TrickyStore 运行目录不在（$TS），但检测到其它 TEE 引擎在位——继续，keybox/target 维护走它"
    else
        _pfd_log "没有可用的 TEE 引擎（内置 TEE 未开启、也没装外部模块）——本次只做回锁属性部分，keybox/target 维护跳过"
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
    # 0/0 有两种截然不同的含义：属性本来就已经正确（无事可做），或者这一轮被
    # ZeroMount 的挂载窗口推迟了（推迟 ≠ 放弃：挂载结束后看护循环会补写并复核）。
    # 后者必须显式写出来，否则这行日志会被读成"一切正常"。
    if [ "$BOOTSTATE_DEFERRED" = "true" ]; then
        _pfd_log "boot-state spoof (early) DEFERRED (ZeroMount mount window): $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed — the watchdog re-writes and verifies these after the window closes"
    else
        _pfd_log "boot-state spoof (early) done: $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed"
    fi
    _pfd_log "early boot-state: device_state=$(getprop ro.boot.vbmeta.device_state) verifiedboot=$(getprop ro.boot.verifiedbootstate) flash.locked=$(getprop ro.boot.flash.locked)"
else
    _pfd_log "common/bootstate.sh missing - early boot-state spoof skipped"
fi

_pfd_log "post-fs-data completed"
