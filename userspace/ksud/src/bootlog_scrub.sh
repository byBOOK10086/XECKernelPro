#!/system/bin/sh
# Embedded in ksud and started after normal boot or a successful late load.
#
# Detection tools read the kernel ring buffer and the Android log buffers for
# root/AVC traces produced during boot (avc: denials, root-implementation domain
# names and daemon paths). ksud itself opens a ~30 second boot-log capture on
# post-fs-data; clear once as soon as boot-completed fires and once more after
# that capture window closes, so neither the early lines nor the ones produced
# while our collector was attached survive.
#
# Only buffers that actually contain a marker are cleared; unrelated logging is
# left alone.
[ "$(id -u)" = 0 ] || exit 1

clear_boot_logs() {
    result=0
    dmesg -C || result=1
    for buffer in main system radio events crash kernel security; do
        if logcat -b "$buffer" -d -v brief 2>/dev/null |
            grep -Eiq 'avc:|kernelsu|xecpro|selinux_hide|initialize_fake_status|u:r:ksu:s0|u:r:xecpro:s0'; then
            logcat -b "$buffer" -c || result=1
        fi
    done
    return "$result"
}

status=0
clear_boot_logs || status=1
# ksud captures boot logs for 30 seconds. Clear again after capture finishes.
sleep 35 || exit 1
clear_boot_logs || status=1
exit "$status"
