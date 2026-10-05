#!/bin/sh
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
    _log "INFO" "ZeroMount active — deferring overlapping props"
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
