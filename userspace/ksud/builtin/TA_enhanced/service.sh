MODPATH=${0%/*}
MODDIR="$MODPATH"
PATH=$MODPATH/common/bin:/data/adb/ap/bin:/data/adb/ksu/bin:/data/adb/magisk:$PATH
HIDE_DIR="/data/adb/modules/.TA_enhanced"
TSPA="/data/adb/modules/tsupport-advance"

. "$MODPATH/common/common.sh"
detect_manager

# 引擎二进制落盘运行（v30136）：tmpfs 执行是与"内置引擎从未工作"同类的嫌疑
# 变量，与 TEERS 同策略——staging 到 /data 持久目录后从那里跑；每次开机覆盖，
# 保证二进制始终与当前版本一致。
STAGED_BIN="/data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced"
if [ -n "$BIN" ] && [ -f "$BIN" ]; then
    mkdir -p "$(dirname "$STAGED_BIN")" 2>/dev/null
    if cp -f "$BIN" "$STAGED_BIN" 2>/dev/null; then
        chmod 755 "$STAGED_BIN" 2>/dev/null
        BIN="$STAGED_BIN"
    fi
fi

_log "INFO" "Service started (manager=$MANAGER)"

# Denylist merge function (Magisk only)
add_denylist_to_target() {
    local target_file="$TS_DIR/target.txt"
    local tmp_file="${target_file}.tmp"
    local exclamation_target question_target existing denylist

    exclamation_target=$(grep '!' "$target_file" | sed 's/!$//')
    question_target=$(grep '?' "$target_file" | sed 's/?$//')
    existing=$(sed 's/[!?]$//' "$target_file")
    denylist=$(magisk --denylist ls 2>/dev/null | awk -F'|' '{print $1}' | grep -v "isolated")

    if ! printf "%s\n" "$existing" "$denylist" | sort -u > "$tmp_file"; then
        _log "ERROR" "Failed to write target.txt from denylist"
        rm -f "$tmp_file"
        return 1
    fi

    for pkg in $exclamation_target; do
        sed -i "s/^${pkg}$/${pkg}!/" "$tmp_file"
    done
    for pkg in $question_target; do
        sed -i "s/^${pkg}$/${pkg}?/" "$tmp_file"
    done

    mv "$tmp_file" "$target_file"
}

# Security patch is handled by the daemon's SecurityPatchTask (with retries + bulletin fetch).
# Running `set` here would overwrite bulletin-fetched dates with stale device props.

# Property Spoofing (background)
_log "INFO" "Prop spoofing started"
sh "$MODPATH/prop.sh" &

# TSupport-A Interop
if [ -d "$TSPA" ]; then
    touch "/storage/emulated/0/stop-tspa-auto-target" 2>/dev/null || true
elif [ ! -d "$TSPA" ] && [ -f "/storage/emulated/0/stop-tspa-auto-target" ]; then
    rm -f "/storage/emulated/0/stop-tspa-auto-target"
fi

# Magisk Module Hiding
# Dot-prefix hides from Magisk's module list scan (stable since Magisk v24+).
# service.sh re-copies on every boot so the hidden copy is always fresh.
if [ -f "$MODPATH/action.sh" ]; then
    if [ "$MODPATH" != "$HIDE_DIR" ]; then
        _log "INFO" "Module hiding (Magisk)"
        rm -rf "$HIDE_DIR"
        mkdir -p "$HIDE_DIR"
        busybox chcon --reference="$MODPATH" "$HIDE_DIR" 2>/dev/null || true
        if ! cp -af "$MODPATH/." "$HIDE_DIR/"; then
            _log "ERROR" "Module hiding copy failed, using original path"
            rm -rf "$HIDE_DIR"
        else
            MODPATH="$HIDE_DIR"
            MODDIR="$MODPATH"
            BIN="$MODPATH/bin/${ABI}/ta-enhanced"
        fi
    fi

    # Merge Magisk denylist into target.txt (flag-file-gated)
    [ -f "$TS_DIR/target_from_denylist" ] && add_denylist_to_target
else
    # KSU/APatch: clean up any stale hidden dir
    [ -d "$HIDE_DIR" ] && rm -rf "$HIDE_DIR"
fi

# Ensure system_app file exists for WebUI system app display
if [ ! -f "$TS_DIR/system_app" ]; then
    : > "$TS_DIR/system_app"
    for app in com.google.android.gms com.google.android.gsf com.android.vending \
               com.oplus.deepthinker com.heytap.speechassist com.coloros.sceneservice; do
        pm list packages -s 2>/dev/null | grep -q "package:$app" && echo "$app" >> "$TS_DIR/system_app"
    done
fi

mkdir -p "/data/adb/.xudc_secure/ta-enhanced/bin"

cp -f "$MODPATH/bin/${ABI}/resetprop-rs" "/data/adb/.xudc_secure/ta-enhanced/bin/resetprop-rs" 2>/dev/null
chmod 755 "/data/adb/.xudc_secure/ta-enhanced/bin/resetprop-rs" 2>/dev/null

# Preserve module.prop for WebUI version display, then hide from manager UI
cp -f "$MODPATH/module.prop" "/data/adb/.xudc_secure/ta-enhanced/module.prop" 2>/dev/null || true
rm -f "$MODPATH/module.prop"

# Symlink Management
if [ -f "$MODPATH/action.sh" ] && [ ! -e "$TS/action.sh" ]; then
    ln -s "$MODPATH/action.sh" "$TS/action.sh" 2>/dev/null || true
fi
if [ ! -e "$TS/webroot" ]; then
    ln -s "$MODPATH/webui" "$TS/webroot" 2>/dev/null || true
fi
if [ ! -e "$TS/banner.png" ] && [ -f "$MODPATH/banner.png" ]; then
    ln -s "$MODPATH/banner.png" "$TS/banner.png" 2>/dev/null || true
fi
if [ -f "$TS/module.prop" ] && ! grep -q "^banner=" "$TS/module.prop"; then
    sed -i '$ a\banner=banner.png' "$TS/module.prop" 2>/dev/null || true
fi

# Wait for Boot Completion
_log "INFO" "Waiting for boot completion"
# getprop -w blocks until property is set (Android 10+)
# Timeout after 120s to prevent hanging on broken boots
timeout 120 getprop -w sys.boot_completed 2>/dev/null || {
    until [ "$(getprop sys.boot_completed)" = "1" ]; do
        sleep 5
    done
}
_log "INFO" "Boot completed"

_log "INFO" "Running property cleanup"
sh "$MODPATH/propclean.sh" &

pm list packages -s 2>/dev/null | sed 's/^package://' | sort > "/data/adb/.xudc_secure/ta-enhanced/system_packages.txt"

# VBHash Extraction (config-gated)
vbhash_enabled=$(read_config vbhash.enabled true)
if [ "$vbhash_enabled" = "true" ]; then
    _log "INFO" "Running VBHash extraction"
    "$BIN" vbhash extract 2>/dev/null || _log "WARN" "VBHash extraction failed"
else
    _log "INFO" "VBHash extraction disabled"
fi

# Conflict Check
_log "INFO" "Checking for conflicts at boot"
"$BIN" conflict check 2>/dev/null || _log "WARN" "Conflicts detected, check conflict.log"

# Create tmp directory (needed by action.sh for KSU WebUI APK download)
mkdir -p "$MODPATH/common/tmp"

# Xposed Detection (background)
"$BIN" status xposed-scan >> "$LOG_BASE_DIR/main.log" 2>&1 &

# Magisk: clean up unhidden module dir
[ -f "$MODPATH/action.sh" ] && rm -rf "/data/adb/modules/TA_enhanced"

# Keybox auto-fetch（yurikey，3 分钟一次）：TEERS（tricky_store）在位即视为
# 两个 TEE 模块均已激活，开机直接下发配置，用户无需进 WebUI 手动开。
# interval/enabled 每次开机强制对齐；source 仅在未设置时补 yurikey 默认值，
# 不覆盖用户在 WebUI 里自选的源。拉取由 ta-enhanced 守护进程的 keybox 任务
# 周期执行，写入 /data/adb/.xudc_secure/keybox.xml 供 TEERS supervisor 消费。
if [ -d "$TS" ] || [ -d "/data/adb/modules/.tricky_store" ] || [ -d "/data/adb/modules/tricky_store" ]; then
    "$BIN" config set keybox.enabled true >/dev/null 2>&1 || _log "WARN" "keybox.enabled set failed"
    "$BIN" config set keybox.interval 180 >/dev/null 2>&1 || _log "WARN" "keybox.interval set failed"
    # 源固定 custom（jsdelivr 镜像，国内直连可达）：yurikey/upstream 原源都是
    # raw.githubusercontent.com，国内拉不到，首次获取就死在第一步。custom 源
    # 不做 base64 解码（原文直存），所以镜像分支里由 CI 维护"已解码"的明文
    # XML。守护进程的回退链是 preferred 优先 + 其余全源兜底：镜像挂了自动接
    # yurikey/upstream（海外用户直连原源照样通）。
    "$BIN" config set keybox.source custom >/dev/null 2>&1 || _log "WARN" "keybox.source set failed"
    "$BIN" config set keybox.custom_url "https://cdn.jsdelivr.net/gh/byBOOK10086/XECKernelPro@keybox/keybox.xml" >/dev/null 2>&1 || _log "WARN" "keybox.custom_url set failed"
    _log "INFO" "Keybox auto-fetch on: source=$(read_config keybox.source custom) interval=$(read_config keybox.interval 180)s url=$(read_config keybox.custom_url "") (TEERS active)"
else
    _log "INFO" "Keybox auto-fetch skipped: TEERS (tricky_store) not present"
fi

# 密钥镜像：守护进程只写 $TS_DIR/keybox.xml（内置 TEERS 读这里）；资源包形态
# 的 TEERS 走标准 TrickyStore 目录 /data/adb/tricky_store/keybox.xml。后台循环
# 只在源更新且内容确实不同时覆盖（-nt + cmp），避免用旧文件倒灌新目录。
(
    while :; do
        sleep 60
        src="$TS_DIR/keybox.xml"
        dst="/data/adb/tricky_store/keybox.xml"
        [ -f "$src" ] || continue
        [ -d "/data/adb/tricky_store" ] || continue
        if [ ! -f "$dst" ] || [ "$src" -nt "$dst" ]; then
            if ! cmp -s "$src" "$dst" 2>/dev/null; then
                cp -f "$src" "$dst" 2>/dev/null \
                    && chmod 644 "$dst" 2>/dev/null \
                    && _log "INFO" "keybox mirrored to /data/adb/tricky_store/keybox.xml"
            fi
        fi
        # 状态镜像（反向）：引擎把 tee_status.txt 写进自己的配置目录，
        # 管理器状态行读的是 .xudc_secure 下的同名文件。
        if [ -f "/data/adb/tricky_store/tee_status.txt" ] && \
           ! cmp -s "/data/adb/tricky_store/tee_status.txt" "$TS_DIR/tee_status.txt" 2>/dev/null; then
            cp -f "/data/adb/tricky_store/tee_status.txt" "$TS_DIR/tee_status.txt" 2>/dev/null
        fi
    done
) &

# Launch Daemon
_log "INFO" "Starting ta-enhanced daemon"
"$BIN" daemon --manager "$MANAGER" &
_log "INFO" "Daemon launched"
