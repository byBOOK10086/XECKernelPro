#!/system/bin/sh
# XECKernelPro: read-only OMY injection status, scoped to the current process.
# OMY records a randomized mapped library name together with its keystore2 PID.
STATE=/data/adb/omk/injector.payload
for pid in $(pidof keystore2 2>/dev/null); do
    case "$pid" in ''|*[!0-9]*) continue ;; esac
    maps="/proc/$pid/maps"
    [ -r "$maps" ] || continue
    if [ -r "$STATE" ] && awk -v want_pid="$pid" '
        FNR == NR { if ($1 == want_pid && $2 != "") ident = $2; next }
        ident != "" && index($0, ident) { found = 1; exit }
        END { exit found ? 0 : 1 }
    ' "$STATE" "$maps" 2>/dev/null; then
        exit 0
    fi
    grep -qF '/data/adb/tricky_store/engine/' "$maps" 2>/dev/null && exit 0
done
exit 1
