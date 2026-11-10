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

# ---------------------------------------------------------------------------
# 自定义属性（上游 v5.53.x 把执行路径删了，只剩 `config props-custom` 能读出来）
# ---------------------------------------------------------------------------
# 用户以前在 WebUI 里存的 `props.custom_props` 仍然被序列化在 config.toml 里，但新版
# 上游只提供"打印"（`name<TAB>value`），没有任何代码去 apply。这里在 shell 侧补回来，
# 并且**限定前缀**：只放行 ro./persist./sys.，避免有人借它写 `service.adb.root` 之类
# 会把机器交出去的东西。
_custom_props=$("$BIN" config props-custom 2>/dev/null)
if [ -n "$_custom_props" ]; then
    _tab=$(printf '\t')
    _custom_ok=0
    _custom_skip=0
    while IFS="$_tab" read -r _cn _cv; do
        [ -n "$_cn" ] || continue
        case "$_cn" in
            ro.*|persist.*|sys.*) ;;
            *)
                _custom_skip=$((_custom_skip + 1))
                _log "WARN" "custom_props: 前缀不在白名单，已忽略：$_cn"
                continue
                ;;
        esac
        [ -n "$_cv" ] || continue
        check_reset_prop "$_cn" "$_cv"
        _custom_ok=$((_custom_ok + 1))
    done <<XEC_CUSTOM_PROPS
$_custom_props
XEC_CUSTOM_PROPS
    _log "INFO" "custom_props: 应用 $_custom_ok 项，忽略 $_custom_skip 项"
fi

# 收尾：两遍校验 + 真实启动状态取证（/proc/cmdline、/proc/bootconfig）
bootstate_verify
bootstate_leak_report
