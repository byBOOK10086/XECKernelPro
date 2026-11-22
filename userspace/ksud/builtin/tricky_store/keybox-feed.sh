#!/system/bin/sh
# Carrier-owned update transaction. Source after engineconf.sh.
# FD 9 is separate from service worker FD 8; closing this subshell releases it.
keybox_lock() {
    exec 9>"$RUNTIME/.keybox-update.lock" || return 1
    if command -v flock >/dev/null 2>&1; then
        flock -n 9
    else
        local bb
        bb=$(_busybox_bin) || return 1
        "$bb" flock -n 9
    fi
}

feed_status() {
    local previous
    previous=$(cut -d' ' -f2- "$RUNTIME/.keybox-feed.status" 2>/dev/null)
    [ "$previous" = "$*" ] && return 0
    printf '%s %s\n' "$(date +%s)" "$*" > "$RUNTIME/.keybox-feed.status.tmp" &&
        mv -f "$RUNTIME/.keybox-feed.status.tmp" "$RUNTIME/.keybox-feed.status"
    log_line "INFO keybox feed: $*"
}

feed_sha() {
    [ -f "$1" ] || return 1
    local hash
    hash=$(sha256sum "$1" 2>/dev/null) || return 1
    hash=${hash%% *}
    [ "${#hash}" = 64 ] || return 1
    case "$hash" in *[!0-9a-f]*) return 1 ;; esac
    printf '%s\n' "$hash"
}

# Never undo a concurrent user edit. Journal remains if recovery cannot finish.
feed_recover() {
    local pending live_sha old_sha
    [ -f "$RUNTIME/.keybox-feed.pending" ] || return 0
    pending=$(cat "$RUNTIME/.keybox-feed.pending")
    live_sha=$(feed_sha "$RUNTIME/keybox.xml") || return 1
    old_sha=$(feed_sha "$RUNTIME/.keybox-feed.backup") || return 1
    if [ "$live_sha" = "$pending" ]; then
        cp -p "$RUNTIME/.keybox-feed.backup" "$RUNTIME/.keybox-feed.restore" &&
            mv -f "$RUNTIME/.keybox-feed.restore" "$RUNTIME/keybox.xml" || return 1
    elif [ "$live_sha" != "$old_sha" ]; then
        feed_status user-edit-preserved
        rm -f "$RUNTIME/.keybox-feed.pending"
        return 0
    fi
    _mirror_keybox_unlocked || { feed_status rollback-mirror-failed; return 1; }
    if [ -f "$RUNTIME/.keybox-feed.owner-backup" ]; then
        cp -f "$RUNTIME/.keybox-feed.owner-backup" "$RUNTIME/.keybox_feed.installed" || return 1
    else
        rm -f "$RUNTIME/.keybox_feed.installed"
    fi
    rm -f "$RUNTIME/.keybox-feed.pending"
    feed_status files-restored-runtime-unconfirmed
}

mirror_keybox() (
    keybox_lock || return 1
    feed_recover || return 1
    _mirror_keybox_unlocked
)

# The validator emits formatted JSON. Require every entry to be valid, private-key
# matched and Google-rooted, not just any matching text in a failed report.
feed_validate() {
    local report="$1.report"
    timeout 30 "$ta" keybox validate "$1" > "$report" 2>/dev/null || return 1
    awk '
        /"ok"[[:space:]]*:/ { oks++; if ($0 !~ /:[[:space:]]*true[[:space:]]*,?[[:space:]]*$/) bad=1 }
        /"root_type"[[:space:]]*:/ { roots++; if ($0 !~ /:[[:space:]]*"google(_rkp)?"[[:space:]]*,?[[:space:]]*$/) bad=1 }
        /"key_match"[[:space:]]*:/ { matches++; if ($0 !~ /:[[:space:]]*"matched"[[:space:]]*,?[[:space:]]*$/) bad=1 }
        /"keybox_index"[[:space:]]*:/ { boxes++; if ($0 !~ /:[[:space:]]*1[[:space:]]*,?[[:space:]]*$/) bad=1 }
        /"leaf_days_left"[[:space:]]*:/ { days++; v=$0; sub(/^.*:[[:space:]]*/,"",v); sub(/,[[:space:]]*$/,"",v); if (v !~ /^[0-9]+$/ || v+0 < 1) bad=1 }
        END { exit !(!bad && days==1 && roots>0 && matches==roots && boxes==roots && oks==roots+1) }
    ' "$report"
}

feed_download() {
    # No cleartext fallback and no HTTPS -> HTTP redirect.
    case "$1" in https://*) : ;; *) return 1 ;; esac
    local bb
    bb=$(_busybox_bin 2>/dev/null)
    if [ -n "$bb" ]; then
        "$bb" wget -q -T 30 -O "$2" "$1" 2>/dev/null && return 0
    fi
    command -v curl >/dev/null 2>&1 || return 1
    curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 10 \
        --max-time 30 --max-filesize 1048576 -o "$2" "$1" 2>/dev/null
}

refresh_keybox_if_stale() (
    keybox_lock || return 1
    feed_recover || return 1
    local live="$RUNTIME/keybox.xml" live_sha ship_sha own_sha health now stamp age ta url
    local raw xml work candidate saw_https=0
    live_sha=$(feed_sha "$live") || { feed_status hash-unavailable; return 1; }
    [ ! -f "$RUNTIME/.keybox-user-managed" ] || { feed_status custom-preserved; return 0; }
    ship_sha=$(feed_sha "$MODPATH/keybox.xml")
    own_sha=$(cat "$RUNTIME/.keybox_feed.installed" 2>/dev/null)
    # Migrate legacy 16-digit ownership stamps only when they match the live hash.
    if [ "${#own_sha}" = 16 ] && [ "${live_sha%????????????????????????????????????????????????}" = "$own_sha" ]; then
        own_sha=$live_sha
    fi
    if [ "$live_sha" != "$ship_sha" ] && [ "$live_sha" != "$own_sha" ]; then
        feed_status custom-preserved
        return 0
    fi
    for url in $XEC_KEYBOX_FEEDS; do case "$url" in https://*) saw_https=1 ;; esac; done
    [ "$saw_https" = 1 ] || { feed_status blocked-no-https-source; return 1; }
    ta=$(_ta_enhanced_bin) || { feed_status validator-unavailable; return 1; }
    command -v timeout >/dev/null 2>&1 || { feed_status timeout-unavailable; return 1; }
    health=$(_keybox_health_line) || { feed_status health-unknown; return 1; }
    set -- $health
    case "${1:-}" in 0|1) : ;; *) feed_status health-unknown; return 1 ;; esac
    case "${2:-}" in ''|*[!0-9-]*) feed_status health-unknown; return 1 ;; esac
    [ "$2" -eq "$2" ] 2>/dev/null || { feed_status health-unknown; return 1; }
    [ "${1:-0}" = 1 ] && [ "$2" -gt 10 ] && return 0
    now=$(date +%s)
    stamp=$(cat "$RUNTIME/.keybox_feed.stamp" 2>/dev/null)
    case "$stamp" in ''|*[!0-9]*) stamp=0 ;; esac
    age=$((now - stamp))
    [ "$age" -lt 0 ] || [ "$age" -ge 3600 ] || return 0
    printf '%s\n' "$now" > "$RUNTIME/.keybox_feed.stamp"
    work=$(mktemp -d "$RUNTIME/.keybox-feed.XXXXXX") || return 1
    trap 'rm -rf "$work"' EXIT
    trap 'exit 1' HUP INT TERM
    raw="$work/raw"; xml="$work/keybox.xml"
    for url in $XEC_KEYBOX_FEEDS; do
        rm -f "$raw" "$xml"
        feed_download "$url" "$raw" || continue
        [ -s "$raw" ] && [ "$(wc -c < "$raw")" -le 1048576 ] || continue
        if grep -q '<AndroidAttestation' "$raw"; then
            cp "$raw" "$xml" || continue
        else
            if [ -n "$bb" ]; then
                "$bb" base64 -d "$raw" > "$xml" 2>/dev/null || continue
            else
                base64 -d "$raw" > "$xml" 2>/dev/null || continue
            fi
        fi
        cmp -s "$xml" "$live" && continue
        feed_validate "$xml" || continue
        candidate=$(feed_sha "$xml") || continue
        [ "$(feed_sha "$live")" = "$live_sha" ] || { feed_status concurrent-edit-preserved; return 1; }
        cp -p "$live" "$RUNTIME/.keybox-feed.backup" || return 1
        rm -f "$RUNTIME/.keybox-feed.owner-backup"
        if [ -f "$RUNTIME/.keybox_feed.installed" ]; then
            cp -p "$RUNTIME/.keybox_feed.installed" "$RUNTIME/.keybox-feed.owner-backup" || return 1
        fi
        printf '%s\n' "$candidate" > "$RUNTIME/.keybox-feed.pending" || return 1
        chmod 600 "$xml" && mv -f "$xml" "$live" || { feed_recover; return 1; }
        if ! _mirror_keybox_unlocked; then feed_recover; return 1; fi
        printf '%s\n' "$candidate" > "$RUNTIME/.keybox_feed.installed" || { feed_recover; return 1; }
        rm -f "$RUNTIME/.keybox-feed.pending"
        printf '%s\n' "$now" > "$RUNTIME/.keybox-feed.last-install"
        # Upstream exposes no identity-bound load acknowledgement. Do not claim ready.
        feed_status installed-runtime-unconfirmed
        return 0
    done
    feed_status no-usable-candidate
    return 1
)
