#!/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
# propclean.sh - Post-boot property cleanup via hexpatch + partition normalization
# Runs after boot completion. Called by service.sh and periodically by Rust daemon.
# Techniques adapted from silvzr/sensitive-props-crontabs.

MODPATH="${0%/*}"
MODDIR="$MODPATH"
. "$MODPATH/common/common.sh"

_PROP_SPOOF_COUNT=0
_PROP_FAIL_COUNT=0

_log "INFO" "Property cleanup starting"

# Snapshot all props once — used for own-ROM detection below
_all_props=$(getprop)

FINGERPRINT_FILE="$MODPATH/common/rom-fingerprints.txt"
if [ -f "$FINGERPRINT_FILE" ]; then
    fingerprints=""
    while IFS= read -r line; do
        case "$line" in \#*|"") continue ;; esac
        # Don't wipe props belonging to the running ROM — breaks version display, OTA, etc.
        if echo "$_all_props" | grep -qi "^\[ro\.${line}"; then
            _log "INFO" "Preserving own ROM props: $line"
            continue
        fi
        fingerprints="$fingerprints $line"
    done < "$FINGERPRINT_FILE"
    if [ -n "$fingerprints" ]; then
        # shellcheck disable=SC2086
        hexpatch_deleteprop $fingerprints
    fi
fi

getprop | grep -E "pihook|pixelprops|eliteprops|spoof.gms" | \
    sed -E 's/^\[(.*)\]:.*/\1/' | while IFS= read -r prop; do
    hexpatch_deleteprop "$prop"
done

replace_value_prop ro.build.flavor "lineage_" ""
replace_value_prop ro.build.flavor "userdebug" "user"
replace_value_prop ro.build.display.id "eng." ""
replace_value_prop ro.build.display.id "lineage_" ""
replace_value_prop ro.build.display.id "userdebug" "user"
replace_value_prop ro.build.display.id "dev-keys" "release-keys"
replace_value_prop vendor.camera.aux.packagelist "lineageos." ""
replace_value_prop ro.build.version.incremental "eng." ""

for prefix in bootimage odm odm_dlkm oem product system system_ext vendor vendor_dlkm; do
    check_reset_prop "ro.${prefix}.build.type" "user"
    check_reset_prop "ro.${prefix}.build.tags" "release-keys"
    replace_value_prop "ro.${prefix}.build.version.incremental" "eng." ""
    for suffix in build.description build.fingerprint; do
        replace_value_prop "ro.${prefix}.${suffix}" "aosp_" ""
    done
    replace_value_prop "ro.product.${prefix}.name" "aosp_" ""
done

getprop | grep "test-keys" | cut -d'[' -f2 | cut -d']' -f1 | while IFS= read -r prop; do
    replace_value_prop "$prop" "test-keys" "release-keys"
done

# init.svc.adbd 以前被强行写成 stopped（想藏 adb 状态）。**2026-11-08 真机实测：
# 这是自伤**——属性说 stopped、/dev/socket/adbd 和 JDWP 却都活着，Duck Detector
# 的 "ADB Runtime" 直接把这种矛盾判成 DANGER（春秋也报同类项）。adbd 是否在跑
# 本来就是用户自己的选择（拿着 USB 调试的人），藏它只会制造矛盾证据。
# 现在改成"与事实一致"：真在跑就别写 stopped；早先的 bootstate.sh 也会在
# post-fs-data 阶段做同样的纠正，两处语义保持一致。
if [ -S /dev/socket/adbd ] && pidof adbd >/dev/null 2>&1; then
    check_reset_prop "init.svc.adbd" "running"
else
    check_reset_prop "init.svc.adbd" "stopped"
fi
# 删除属性会在属性区留下空洞（春秋的 Property Modified 就是扫这个），
# 所以只在该属性**真的存在**时才删。
if [ -n "$(getprop init.svc.adb_root)" ]; then
    hexpatch_deleteprop "init.svc.adb_root"
fi
check_reset_prop "init.svc.flash_recovery" "stopped"

_log "INFO" "Property cleanup complete: $_PROP_SPOOF_COUNT changed, $_PROP_FAIL_COUNT failed"
