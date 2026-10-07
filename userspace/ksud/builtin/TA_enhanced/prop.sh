#!/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
# prop.sh - Early boot property spoofing (Tricky Addon Enhanced)
# Runs pre-boot via resetprop. Post-boot cleanup in propclean.sh (hexpatch + normalization).

MODPATH="${0%/*}"
MODDIR="$MODPATH"
. "$MODPATH/common/common.sh"

_PROP_SPOOF_COUNT=0
_PROP_FAIL_COUNT=0

_ZEROMOUNT_ACTIVE=false
_zm_dir="/data/adb/modules/meta-zeromount"
if [ -d "$_zm_dir" ] && [ ! -f "$_zm_dir/disable" ] && [ ! -f "$_zm_dir/remove" ]; then
    _ZEROMOUNT_ACTIVE=true
    # 豁免期间 bootloader 状态属性完全不伪装（检测应用会看到 unlocked/orange）。
    # 这里升为 WARN 并写明后果：这条曾以 INFO 静默通过，"bootloader 还是解锁
    # 状态"的排查里它就是头号嫌疑之一。
    _log "WARN" "ZeroMount active — deferring overlapping props (bootloader-state props NOT spoofed this boot; detectors will see the real state)"
fi

ensure_prop() {
    NAME=$1
    NEWVAL=$2
    VALUE=$(getprop "$NAME")
    if [ -z "$VALUE" ]; then
        if resetprop -n "$NAME" "$NEWVAL" 2>/dev/null; then
            _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
        else
            _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
            _log "ERROR" "Failed to spoof (ensure): $NAME"
        fi
    fi
}

_log "INFO" "Property spoofing starting"
if ! resetprop -w sys.boot_completed 0 2>/dev/null; then
    _log "WARN" "resetprop -w sys.boot_completed timeout or failure"
fi

if [ "$_ZEROMOUNT_ACTIVE" != "true" ]; then
    check_reset_prop "ro.boot.vbmeta.device_state" "locked"
    check_reset_prop "ro.boot.verifiedbootstate" "green"
    check_reset_prop "ro.boot.flash.locked" "1"
    check_reset_prop "ro.boot.veritymode" "enforcing"
    check_reset_prop "ro.boot.warranty_bit" "0"
    check_reset_prop "ro.warranty_bit" "0"
    check_reset_prop "ro.debuggable" "0"
    check_reset_prop "ro.force.debuggable" "0"
    check_reset_prop "ro.secure" "1"
    check_reset_prop "ro.adb.secure" "1"
    check_reset_prop "ro.build.type" "user"
    check_reset_prop "ro.build.tags" "release-keys"
    check_reset_prop "ro.vendor.boot.warranty_bit" "0"
    check_reset_prop "ro.vendor.warranty_bit" "0"
    check_reset_prop "vendor.boot.vbmeta.device_state" "locked"
    check_reset_prop "vendor.boot.verifiedbootstate" "green"
    check_reset_prop "sys.oem_unlock_allowed" "0"

    check_reset_prop "ro.secureboot.lockstate" "locked"

    check_reset_prop "ro.boot.realmebootstate" "green"
    check_reset_prop "ro.boot.realme.lockstate" "1"

    check_reset_prop "ro.crypto.state" "encrypted"
    check_reset_prop "ro.is_ever_orange" "0"

    check_reset_prop "ro.oem_unlock_supported" "0"
    check_reset_prop "ro.secureboot.devicelock" "1"
fi

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

if [ "$_ZEROMOUNT_ACTIVE" != "true" ]; then
    # Delete qemu property entirely -- some detectors check existence, not value
    if [ -n "$(resetprop ro.kernel.qemu)" ]; then
        if resetprop --delete ro.kernel.qemu 2>/dev/null; then
            _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
        else
            resetprop -n ro.kernel.qemu "" 2>/dev/null
            _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
            _log "WARN" "Could not delete ro.kernel.qemu, blanked instead"
        fi
    fi
fi

# Hide recovery boot mode
contains_reset_prop "ro.bootmode" "recovery" "unknown"
contains_reset_prop "ro.boot.bootmode" "recovery" "unknown"
contains_reset_prop "ro.boot.mode" "recovery" "unknown"
contains_reset_prop "vendor.bootmode" "recovery" "unknown"
contains_reset_prop "vendor.boot.bootmode" "recovery" "unknown"
contains_reset_prop "vendor.boot.mode" "recovery" "unknown"

if [ "$_ZEROMOUNT_ACTIVE" != "true" ]; then
    _hash_src=""
    hash_value=""

    # only defer to boot_hash.bin when our TEESimulator variant is active
    _ts_mod="/dev/.xudc_hidden/tricky_store"
    _ts_mp=$(cat "$_ts_mod/module.prop" 2>/dev/null)
    _teesim_ok=false
    case "$_ts_mp" in
        *TEESimulator-RS*)
            [ -d "$_ts_mod" ] && [ ! -f "$_ts_mod/disable" ] && [ ! -f "$_ts_mod/remove" ] && _teesim_ok=true ;;
    esac

    if [ "$_teesim_ok" = "true" ] && [ -f "$TS_DIR/boot_hash.bin" ]; then
        hash_value=$(od -A n -t x1 "$TS_DIR/boot_hash.bin" 2>/dev/null | tr -d ' \n')
        if echo "$hash_value" | grep -qE '^[a-f0-9]{64}$'; then
            _hash_src="teesim"
        else
            hash_value=""
        fi
    fi

    if [ -z "$hash_value" ] && [ -f "/data/adb/boot_hash" ]; then
        hash_value=$(grep -v '^#' "/data/adb/boot_hash" 2>/dev/null | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
        [ -n "$hash_value" ] && _hash_src="boot_hash"
    fi

    if echo "$hash_value" | grep -qE '^[a-f0-9]{64}$'; then
        if resetprop -n ro.boot.vbmeta.digest "$hash_value" 2>/dev/null; then
            _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
            _log "INFO" "VBMeta digest set from $_hash_src: $(printf '%.16s' "$hash_value")..."
        else
            _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
            _log "ERROR" "Failed to set vbmeta.digest from $_hash_src"
        fi
    elif [ -n "$hash_value" ]; then
        _log "WARN" "boot_hash invalid from $_hash_src (not 64-char hex)"
    fi

    ensure_prop "ro.boot.vbmeta.device_state" "locked"
    ensure_prop "ro.boot.vbmeta.invalidate_on_error" "yes"
    ensure_prop "ro.boot.vbmeta.avb_version" "1.0"
    ensure_prop "ro.boot.vbmeta.hash_alg" "sha256"

    slot_suffix=$(getprop ro.boot.slot_suffix 2>/dev/null)
    VBMETA_SIZE=""
    for candidate in \
        "/dev/block/by-name/vbmeta${slot_suffix}" \
        "/dev/block/by-name/vbmeta" \
        "/dev/block/by-name/vbmeta_a" \
        "/dev/block/by-name/vbmeta_b"; do
        if [ -b "$candidate" ]; then
            VBMETA_SIZE=$(blockdev --getsize64 "$candidate" 2>/dev/null)
            [ -n "$VBMETA_SIZE" ] && [ "$VBMETA_SIZE" -gt 0 ] 2>/dev/null && break
            VBMETA_SIZE=""
        fi
    done
    ensure_prop "ro.boot.vbmeta.size" "${VBMETA_SIZE:-4096}"
fi

_log "INFO" "Property spoofing complete: $_PROP_SPOOF_COUNT spoofed, $_PROP_FAIL_COUNT failed"

# 收尾校验（两遍）：三条被查得最多的开机状态属性逐一取证，不一致的先补写一次
# 再复查——service 阶段属性区可能还在被 init 收尾，第一遍偶发不一致并不代表
# 伪装链断了，直接判死会产生假警报。两遍都过不去才记 ERROR，此时证据是确凿的：
# 伪装链的哪一环断了（resetprop 全部缺失、ZeroMount 豁免、属性被拒写）都能从
# 上下文立刻定位，"bootloader 还是解锁"不再需要盲猜。
#
# 同时把 RESETPROP_BIN 记进日志：TA 与 TEERS 两个模块读的是同一个属性区，
# 排查时第一件要确认的事就是"到底用的哪个 resetprop"。
if [ "$_ZEROMOUNT_ACTIVE" != "true" ]; then
    _bs_failed=""
    for _p in "ro.boot.vbmeta.device_state=locked" \
              "ro.boot.verifiedbootstate=green" \
              "ro.boot.flash.locked=1"; do
        _name=${_p%%=*}; _want=${_p#*=}
        if [ "$(getprop "$_name")" != "$_want" ]; then
            # 属性不存在（读回空）与值不对是两件事：前者不是伪装失败，本设备没有
            # 这条属性，补写反而会凭空造出一条检测方会当异常看的属性。
            if [ -n "$(getprop "$_name")" ]; then
                resetprop -n "$_name" "$_want" 2>/dev/null
            fi
            sleep 2
            if [ "$(getprop "$_name")" != "$_want" ]; then
                _bs_failed="$_bs_failed $_name='$(getprop "$_name")'(want '$_want')"
            fi
        fi
    done
    _log "INFO" "resetprop backend: ${RESETPROP_BIN:-none (read-only fallback)}"
    if [ -n "$_bs_failed" ]; then
        _log "ERROR" "boot-state verify failed after retry:$_bs_failed"
    fi
    _log "INFO" "final boot-state: device_state=$(getprop ro.boot.vbmeta.device_state) verifiedboot=$(getprop ro.boot.verifiedbootstate) flash.locked=$(getprop ro.boot.flash.locked) veritymode=$(getprop ro.boot.veritymode)"
else
    _log "WARN" "final boot-state check skipped (ZeroMount deferral active) — detectors will read the real unlocked/orange state this boot"
fi
