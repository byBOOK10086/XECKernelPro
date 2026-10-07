#!/system/bin/sh
# 新增文件（GPL-3.0）：本文件由 XECKernel Pro 编写，随 TA_enhanced 模块按 GPL-3.0 分发。
# 新增日期 2026-10-08；说明见同目录 NOTICE.md「本地修改」。
#
# post-mount.sh —— late-load（免重启加载 kernelsu.ko）路径下的回锁态补位
# ============================================================================
# 为什么需要它：late_load.rs 走的阶段是 late-load -> post-mount -> service ->
# boot-completed，**不经过 post-fs-data**，所以内置模块的 post-fs-data.sh 在
# "刷入后不重启、直接加载内核模块"这条路径上根本不会执行。锁态属性因此会晚到
# service 阶段才写。这里补一个 post-mount 钩子，把同一份实现提前到该路径能到达的
# 最早阶段（正常开机时它也会跑一次，同样是幂等的，没有副作用）。
MODPATH=${0%/*}
MODDIR="$MODPATH"
LOG_BASE_DIR="/data/adb/.xudc_secure/ta-enhanced/logs"

mkdir -p "$LOG_BASE_DIR" 2>/dev/null

. "$MODPATH/common/common.sh"
. "$MODPATH/common/bootstate.sh"

_PROP_SPOOF_COUNT=0
_PROP_FAIL_COUNT=0

_log "INFO" "post-mount: boot-state spoof (late-load path) starting"
bootstate_spoof_lock
_log "INFO" "post-mount: boot-state spoof done: $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed; verifiedboot=$(getprop ro.boot.verifiedbootstate) flash.locked=$(getprop ro.boot.flash.locked)"
