#!/system/bin/sh
# Read-only PID-scoped probe. 0=present, 1=absent, 2=unknown.
# Failure to observe a process must never request an engine restart.
STATE=${STATE:-/data/adb/omk/injector.payload}
PROC_ROOT=${PROC_ROOT:-/proc}
ENGINE_PATH=${ENGINE_PATH:-/data/adb/tricky_store/engine/}
pids=$(pidof keystore2 2>/dev/null)
rc=$?
case "$rc" in 0|1) : ;; *) exit 2 ;; esac
[ -n "$pids" ] || exit 1
unknown=0
absent=0
for pid in $pids; do
    case "$pid" in ''|*[!0-9]*) exit 2 ;; esac
    maps="$PROC_ROOT/$pid/maps"
    contents=$(cat "$maps" 2>/dev/null) || { unknown=1; continue; }
    [ -n "$contents" ] || { unknown=1; continue; }
    if printf '%s\n' "$contents" | grep -qF "$ENGINE_PATH"; then continue; fi
    ident=$(awk -v want_pid="$pid" '$1 == want_pid && $2 != "" { print $2; exit }' "$STATE" 2>/dev/null) || { unknown=1; continue; }
    if [ -n "$ident" ] && printf '%s\n' "$contents" | grep -qF "$ident"; then continue; fi
    [ -d "$PROC_ROOT/$pid" ] || { unknown=1; continue; }
    absent=1
done
[ "$absent" = 0 ] || exit 1
[ "$unknown" = 0 ] || exit 2
exit 0
