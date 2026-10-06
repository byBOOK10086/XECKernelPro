#!/system/bin/sh
# Built-in TEESimulator-RS engine init.
#
# The bundled classes.dex is kept byte-for-byte compatible with the upstream
# engine. Its native cert generator path is satisfied through a compatibility
# directory because rewriting DEX string_ids without rebuilding the DEX is not
# safe. The engine itself runs from /data/adb/tricky_store.
MODPATH=${0%/*}
RUNTIME=/data/adb/tricky_store
COMPAT_DIR=/data/adb/modules/tricky_store
LOG_FILE="$RUNTIME/.engine.log"

log_line() {
    printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG_FILE" 2>/dev/null
}

stage_file() {
    local source="$1" target="$2" mode="$3" tmp="${target}.tmp.$$"
    local parent="${target%/*}"
    mkdir -p "$parent" 2>/dev/null || return 1
    rm -f "$tmp"
    cp -f "$source" "$tmp" 2>/dev/null || {
        log_line "ERROR failed to copy $source"
        rm -f "$tmp"
        return 1
    }
    chmod "$mode" "$tmp" 2>/dev/null || {
        log_line "ERROR failed to chmod $tmp"
        rm -f "$tmp"
        return 1
    }
    mv -f "$tmp" "$target" 2>/dev/null || {
        log_line "ERROR failed to install $target"
        rm -f "$tmp"
        return 1
    }
}

label_data_path() {
    local path="$1"
    if command -v chcon >/dev/null 2>&1 && chcon --reference=/data/adb "$path" 2>/dev/null; then
        return 0
    fi
    if command -v busybox >/dev/null 2>&1 && busybox chcon --reference=/data/adb "$path" 2>/dev/null; then
        return 0
    fi
    log_line "WARN failed to inherit SELinux label for $path"
    return 0
}

external_engine_active() {
    local dir
    for dir in /data/adb/modules/tricky_store /data/adb/modules/.tricky_store; do
        [ -f "$dir/module.prop" ] || continue
        [ -f "$dir/disable" ] && continue
        [ -f "$dir/remove" ] && continue
        return 0
    done
    return 1
}

mkdir -p "$RUNTIME" || exit 1
touch "$LOG_FILE" 2>/dev/null || exit 1
label_data_path "$RUNTIME"

# Any active external module owns the same injection surface. Do not start a
# second engine or overwrite its files.
if [ -f /data/adb/modules/tricky_store/module.prop ] && \
   [ ! -f /data/adb/modules/tricky_store/disable ] && \
   [ ! -f /data/adb/modules/tricky_store/remove ] || \
   [ -f /data/adb/modules/.tricky_store/module.prop ] && \
   [ ! -f /data/adb/modules/.tricky_store/disable ] && \
   [ ! -f /data/adb/modules/.tricky_store/remove ]; then
    log_line "INFO builtin TEERS stand down: external module directory is present"
    exit 0
fi

VER=$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop" 2>/dev/null)
[ -n "$VER" ] || VER=0
CUR=$(cat "$RUNTIME/.engine_version" 2>/dev/null)
if [ "$VER" != "$CUR" ] || [ ! -f "$RUNTIME/daemon" ] || \
   [ ! -f "$RUNTIME/classes.dex" ] || [ ! -x "$RUNTIME/inject" ]; then
    STAGE_DIR="$RUNTIME/.engine.$$.tmp"
    rm -rf "$STAGE_DIR"
    mkdir -p "$STAGE_DIR" || exit 1
    stage_file "$MODPATH/daemon" "$STAGE_DIR/daemon" 755 || { rm -rf "$STAGE_DIR"; exit 1; }
    stage_file "$MODPATH/inject" "$STAGE_DIR/inject" 755 || { rm -rf "$STAGE_DIR"; exit 1; }
    stage_file "$MODPATH/supervisor" "$STAGE_DIR/supervisor" 755 || { rm -rf "$STAGE_DIR"; exit 1; }
    stage_file "$MODPATH/classes.dex" "$STAGE_DIR/classes.dex" 644 || { rm -rf "$STAGE_DIR"; exit 1; }
    stage_file "$MODPATH/libTEESimulator.so" "$STAGE_DIR/libTEESimulator.so" 644 || { rm -rf "$STAGE_DIR"; exit 1; }
    stage_file "$MODPATH/libcertgen.so" "$STAGE_DIR/libcertgen.so" 644 || { rm -rf "$STAGE_DIR"; exit 1; }
    stage_file "$MODPATH/module.prop" "$STAGE_DIR/module.prop" 644 || { rm -rf "$STAGE_DIR"; exit 1; }

    # Replace each asset atomically. The version marker is written last, so a
    # partial update is retried on the next boot instead of being accepted.
    for f in daemon inject supervisor classes.dex libTEESimulator.so libcertgen.so module.prop; do
        mv -f "$STAGE_DIR/$f" "$RUNTIME/$f" || {
            log_line "ERROR failed to commit staged engine asset: $f"
            rm -rf "$STAGE_DIR"
            exit 1
        }
    done
    printf '%s\n' "$VER" > "$STAGE_DIR/.engine_version" || {
        rm -rf "$STAGE_DIR"
        exit 1
    }
    mv -f "$STAGE_DIR/.engine_version" "$RUNTIME/.engine_version" || {
        rm -rf "$STAGE_DIR"
        exit 1
    }
    rm -rf "$STAGE_DIR"
    sync
fi

# The bundled DEX still names this absolute upstream path. This directory has
# no module.prop, so it is not enumerated as an installed module.
mkdir -p "$COMPAT_DIR" || exit 1
printf 'xecpro compatibility directory\n' > "$COMPAT_DIR/.xecpro_compat" || exit 1
chmod 600 "$COMPAT_DIR/.xecpro_compat" 2>/dev/null
stage_file "$RUNTIME/libcertgen.so" "$COMPAT_DIR/libcertgen.so" 644 || exit 1
label_data_path "$COMPAT_DIR"
label_data_path "$COMPAT_DIR/libcertgen.so"

# Seed configuration only when absent; user edits survive reboot and updates.
if [ ! -f "$RUNTIME/keybox.xml" ]; then
    stage_file "$MODPATH/keybox.xml" "$RUNTIME/keybox.xml" 644 || exit 1
fi
if [ ! -f "$RUNTIME/target.txt" ]; then
    stage_file "$MODPATH/target.txt" "$RUNTIME/target.txt" 644 || exit 1
fi
if [ ! -f "$RUNTIME/security_patch.txt" ]; then
    printf 'system=prop\n' > "$RUNTIME/security_patch.txt" || exit 1
fi
if [ ! -f "$RUNTIME/hbk" ]; then
    dd if=/dev/random of="$RUNTIME/hbk" bs=32 count=1 2>/dev/null || exit 1
fi
chmod 644 "$RUNTIME/keybox.xml" "$RUNTIME/target.txt" "$RUNTIME/security_patch.txt" "$RUNTIME/hbk" 2>/dev/null

# TA_enhanced still reads this compatibility directory for its status/config UI.
DATA=/data/adb/.xudc_secure
mkdir -p "$DATA" || exit 1
chmod 755 "$DATA" 2>/dev/null
label_data_path "$DATA"
if [ ! -f "$DATA/keybox.xml" ]; then
    stage_file "$MODPATH/keybox.xml" "$DATA/keybox.xml" 644 || exit 1
fi
if [ ! -f "$DATA/target.txt" ]; then
    stage_file "$MODPATH/target.txt" "$DATA/target.txt" 644 || exit 1
fi
if [ ! -f "$DATA/security_patch.txt" ]; then
    printf 'system=prop\n' > "$DATA/security_patch.txt" || exit 1
fi
if [ ! -f "$DATA/hbk" ]; then
    dd if=/dev/random of="$DATA/hbk" bs=32 count=1 2>/dev/null || exit 1
fi
chmod 644 "$DATA/keybox.xml" "$DATA/target.txt" "$DATA/security_patch.txt" "$DATA/hbk" 2>/dev/null

cd "$RUNTIME" || exit 1
./supervisor ./daemon "$RUNTIME" >> "$LOG_FILE" 2>&1 &
log_line "INFO TEESimulator supervisor started"
