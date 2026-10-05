#!/bin/sh
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

check_reset_prop "init.svc.adbd" "stopped"
hexpatch_deleteprop "init.svc.adb_root"
check_reset_prop "init.svc.flash_recovery" "stopped"

_log "INFO" "Property cleanup complete: $_PROP_SPOOF_COUNT changed, $_PROP_FAIL_COUNT failed"
