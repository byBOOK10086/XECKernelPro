#!/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
# prop.sh - 属性伪装（Tricky Addon Enhanced）
#
# 2026-10-08 改动：**锁态属性提前到 service 阶段的第一时间写入，不再等
# sys.boot_completed**（原来是脚本开头就 resetprop -w 等待，导致开机后到
# boot_completed 之间检测方读到的是真实 unlocked/orange）。实现搬进
# common/bootstate.sh，post-fs-data.sh 也会更早调用同一份实现，
# 这里保留 service 阶段的第二次落笔与收尾校验。

MODPATH="${0%/*}"
MODDIR="$MODPATH"
. "$MODPATH/common/common.sh"
. "$MODPATH/common/bootstate.sh"

_PROP_SPOOF_COUNT=0
_PROP_FAIL_COUNT=0

_log "INFO" "Property spoofing starting"

# 运行形态（LKM / 内置 / late-load）取证 + 锁态属性立刻落笔
bootstate_detect_mode
bootstate_spoof_lock

# 其余属性（构建身份、vbmeta、区域）仍按原语义等 boot 完成后再动。
# `-w` 的等待语义由 common.sh 自己实现（绝不转发给没有 -w 的 resetprop-rs，
# 否则会把 sys.boot_completed 直接写成 0）。
if ! resetprop -w sys.boot_completed 0 2>/dev/null; then
    _log "WARN" "resetprop -w sys.boot_completed timeout or failure"
fi

bootstate_spoof_identity
bootstate_spoof_vbmeta

# MIUI region enforcement — restore device-snapshotted values from config
_region_enabled=$(read_config region.enabled true)
if [ "$_region_enabled" = "true" ]; then
    _cfg_hwc=$(read_config region.hwc "")
    _cfg_hwcountry=$(read_config region.hwcountry "")
    _cfg_mod_device=$(read_config region.mod_device "")
    _cfg_hw_sku=$(read_config region.hardware_sku "")
    [ -n "$_cfg_hwc" ] && check_reset_prop "ro.boot.hwc" "$_cfg_hwc"
    [ -n "$_cfg_hwcountry" ] && check_reset_prop "ro.boot.hwcountry" "$_cfg_hwcountry"
    [ -n "$_cfg_mod_device" ] && check_reset_prop "ro.product.mod_device" "$_cfg_mod_device"
    [ -n "$_cfg_hw_sku" ] && check_reset_prop "ro.boot.product.hardware.sku" "$_cfg_hw_sku"
fi

_log "INFO" "Property spoofing complete: $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed"

# 收尾：两遍校验 + 真实启动状态取证（/proc/cmdline、/proc/bootconfig）
bootstate_verify
bootstate_leak_report
