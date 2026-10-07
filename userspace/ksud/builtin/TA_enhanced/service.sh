# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
MODPATH=${0%/*}
MODDIR="$MODPATH"
PATH=$MODPATH/common/bin:/data/adb/ap/bin:/data/adb/ksu/bin:/data/adb/magisk:$PATH
HIDE_DIR="/data/adb/modules/.TA_enhanced"
TSPA="/data/adb/modules/tsupport-advance"

. "$MODPATH/common/common.sh"
detect_manager

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

# Bounded boot wait: `getprop -w` blocks until the property is set (Android
# 10+), capped at 120s, then a capped poll loop. Everything that waits on this
# runs in a background block below; the daemon is already running by then, so
# a hung boot delays maintenance but never the engine itself.
wait_for_boot_completed() {
    local waited=0
    if timeout 120 getprop -w sys.boot_completed 2>/dev/null; then
        return 0
    fi
    while [ "$waited" -lt 60 ]; do
        [ "$(getprop sys.boot_completed)" = "1" ] && return 0
        sleep 5
        waited=$((waited + 5))
    done
    return 1
}

# TSupport-A Interop
if [ -d "$TSPA" ]; then
    touch "/storage/emulated/0/stop-tspa-auto-target" 2>/dev/null || true
elif [ ! -d "$TSPA" ] && [ -f "/storage/emulated/0/stop-tspa-auto-target" ]; then
    rm -f "/storage/emulated/0/stop-tspa-auto-target"
fi

# Magisk Module Hiding
# Decide the final module path FIRST: the staging below runs from whatever
# path survives this block, so the engine binary is always staged from the
# copy that is actually in charge. (Dot-prefix hides from Magisk's module
# list scan, stable since Magisk v24+; service.sh re-copies on every boot so
# the hidden copy is always fresh.)
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

# Drop the visible Magisk module dir only when hiding actually succeeded.
# If the copy failed above, MODPATH still points at the original and removing
# it would destroy the only live copy of the module.
if [ "$MODPATH" = "$HIDE_DIR" ]; then
    rm -rf "/data/adb/modules/TA_enhanced"
fi

# Ensure system_app file exists for WebUI system app display
if [ ! -f "$TS_DIR/system_app" ]; then
    : > "$TS_DIR/system_app"
    for app in com.google.android.gms com.google.android.gsf com.android.vending \
               com.oplus.deepthinker com.heytap.speechassist com.coloros.sceneservice; do
        pm list packages -s 2>/dev/null | grep -q "package:$app" && echo "$app" >> "$TS_DIR/system_app"
    done
fi

# 引擎二进制落盘运行（v30136）：tmpfs 执行是与"内置引擎从未工作"同类的嫌疑
# 变量，与 TEERS 同策略——staging 到 /data 持久目录后从那里跑；每次开机覆盖，
# 保证二进制始终与当前版本一致。临时文件先完整写入并同步，再原子替换，
# 避免守护进程看到半个 ELF。prop.sh 会经 read_config 走 $BIN、经 hexpatch 走
# $RP，所以两个二进制都必须在 prop.sh 启动前就位。
mkdir -p "/data/adb/.xudc_secure/ta-enhanced/bin"
STAGED_BIN="/data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced"
if [ -n "$BIN" ] && [ -f "$BIN" ]; then
    STAGED_TMP="${STAGED_BIN}.tmp.$$"
    if cp -f "$BIN" "$STAGED_TMP" 2>/dev/null && \
       chmod 755 "$STAGED_TMP" 2>/dev/null && \
       sync && mv -f "$STAGED_TMP" "$STAGED_BIN" 2>/dev/null; then
        if command -v chcon >/dev/null 2>&1; then
            chcon --reference=/data/adb "$STAGED_BIN" 2>/dev/null || _log "WARN failed to label $STAGED_BIN"
        fi
        BIN="$STAGED_BIN"
    else
        rm -f "$STAGED_TMP" 2>/dev/null
        _log "ERROR ta-enhanced staging failed; keeping module binary"
    fi
fi

RP_STAGE="/data/adb/.xudc_secure/ta-enhanced/bin/resetprop-rs"
if [ -f "$MODPATH/bin/${ABI}/resetprop-rs" ]; then
    RP_TMP="${RP_STAGE}.tmp.$$"
    if cp -f "$MODPATH/bin/${ABI}/resetprop-rs" "$RP_TMP" 2>/dev/null && \
       chmod 755 "$RP_TMP" 2>/dev/null && \
       sync && mv -f "$RP_TMP" "$RP_STAGE" 2>/dev/null; then
        if command -v chcon >/dev/null 2>&1; then
            chcon --reference=/data/adb "$RP_STAGE" 2>/dev/null || true
        fi
    else
        rm -f "$RP_TMP" 2>/dev/null
        _log "WARN" "resetprop-rs staging failed; hexpatch will be skipped"
    fi
fi

# Preserve module.prop for WebUI version display. Hiding it from the manager
# list is only done where it cannot invalidate the module: our ksud executes
# module scripts without requiring module.prop, but Magisk treats a module
# dir without one as invalid and would stop running this script entirely.
cp -f "$MODPATH/module.prop" "/data/adb/.xudc_secure/ta-enhanced/module.prop" 2>/dev/null || true
if [ "$MANAGER" != "MAGISK" ]; then
    rm -f "$MODPATH/module.prop"
fi

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

# Create tmp directory (needed by action.sh for KSU WebUI APK download)
mkdir -p "$MODPATH/common/tmp"

# Property Spoofing (background)
_log "INFO" "Prop spoofing started"
sh "$MODPATH/prop.sh" &

# Keybox auto-fetch（yurikey，3 分钟一次）：TEERS（tricky_store）在位即视为
# 两个 TEE 模块均已激活，开机直接下发配置，用户无需进 WebUI 手动开。
# interval/enabled 每次开机强制对齐；source 仅在未设置时补 yurikey 默认值，
# 不覆盖用户在 WebUI 里自选的源。拉取由 ta-enhanced 守护进程的 keybox 任务
# 周期执行，写入 /data/adb/.xudc_secure/keybox.xml 供 TEERS supervisor 消费。
# 这里只写配置、不等 boot：放在 daemon 启动之前，首个周期就能读到。
if [ -d "$TS" ] || [ -d "/data/adb/modules/.tricky_store" ] || [ -d "/data/adb/modules/tricky_store" ]; then
    "$BIN" config set keybox.enabled true >/dev/null 2>&1 || _log "WARN" "keybox.enabled set failed"
    "$BIN" config set keybox.interval 180 >/dev/null 2>&1 || _log "WARN" "keybox.interval set failed"
    # Use the project mirror only when no source has been selected yet. A
    # setting written in the WebUI is user state and must not be overwritten on
    # every boot. The URL is treated the same way for custom sources.
    keybox_source=$(read_config keybox.source "")
    if [ -z "$keybox_source" ]; then
        "$BIN" config set keybox.source custom >/dev/null 2>&1 || _log "WARN" "keybox.source set failed"
        keybox_source=custom
    fi
    keybox_url=$(read_config keybox.custom_url "")
    if [ "$keybox_source" = "custom" ] && [ -z "$keybox_url" ]; then
        "$BIN" config set keybox.custom_url "https://cdn.jsdelivr.net/gh/byBOOK10086/XECKernelPro@keybox/keybox.xml" >/dev/null 2>&1 || _log "WARN" "keybox.custom_url set failed"
        keybox_url="https://cdn.jsdelivr.net/gh/byBOOK10086/XECKernelPro@keybox/keybox.xml"
    fi
    _log "INFO" "Keybox auto-fetch on: source=$keybox_source interval=$(read_config keybox.interval 180)s url=$keybox_url (TEERS active)"
else
    _log "INFO" "Keybox auto-fetch skipped: TEERS (tricky_store) not present"
fi

# Launch Daemon BEFORE the boot wait: it owns the periodic tasks (security
# patch, keybox fetch) and does not depend on sys.boot_completed. Waiting for
# boot first used to delay the engine by up to two minutes on slow boots.
_log "INFO" "Starting ta-enhanced daemon"
"$BIN" daemon --manager "$MANAGER" &
_log "INFO" "Daemon launched"

# ---------------------------------------------------------------------------
# Keybox 守护：从 daemon 启动这一刻就跑，不等 boot 完成
# ---------------------------------------------------------------------------
# 为什么不等 boot：拉取动作由刚启动的 daemon 的第一个周期就发起（KeyboxTask
# 在 boot 未完成时也会先 wait_for_network 再连拉 boot_retries 次），而下面
# 修的权限问题正是在"每次成功拉取之后"出现的——等到 boot 完成再修，中间这段
# 恰好是 attestation 最容易失败的窗口。
#
# 守护的三件事：
#
#  1) **DAC 权限（这条是"密钥有时传不过来"的直接修复）**
#     ta-enhanced 守护进程每次成功拉取都走 vendor 的
#     `keybox/mod.rs::install_data`：atomic_write 之后显式
#     `set_permissions(TARGET_KEYBOX, 0o600)`，即 /data/adb/tricky_store/
#     keybox.xml 变成 **0600 root**。
#     而**读**它的 `KeyBoxManager.parseKeyStoreFile`（vendor app/src/.../pki/
#     KeyBoxManager.kt）运行在 **keystore2 进程内**（TEESimulator 的注入点就在
#     该进程里，uid=keystore），这个 uid 打不开 0600 root 的文件。SELinux 是
#     放行的（本模块 sepolicy.rule 里的 `allow keystore adb_data_file file *`），
#     卡住的是 UNIX 权限位——于是每次拉取成功之后，engine 反而读不到这份更新
#     鲜的 keybox，只能退回软件级证书链，检测方看到的就是"bootloader 未锁"。
#     这里按本项目既有的 644 约定把权限钉回去（内置 tricky_store 的 service.sh
#     播种时也是 644，两处必须一致），并把目录补到 755 保证可穿越。
#     vendor 源码零修改，所以修在模块脚本这一层。
#
#  2) **有效性**：`keybox validate` 失败（截断/坏格式）时强制拉一次（带冷却，
#     不会把四路源打爆），拉完再验一次并把结论写进日志。
#
#  3) **镜像**：把实时 keybox 同步到 TA 的兼容目录 $TS_DIR/keybox.xml（老版本
#     与第三方工具读这一份），只在内容确实不同时覆盖。方向固定为
#     「实时 -> 兼容副本」：反过来的方向会把模块里那份出厂静态 keybox 倒灌
#     到实时路径上，那正是"密钥一直传不过来"的另一种形态。
(
    _KB=/data/adb/tricky_store/keybox.xml
    _KB_DIR=/data/adb/tricky_store
    _last_sha=""
    _validate_fail=0
    _fetch_cooldown=0

    _kb_sha() {
        command -v sha256sum >/dev/null 2>&1 || return 1
        sha256sum "$1" 2>/dev/null | cut -c1-16
    }

    while :; do
        sleep 5

        # --- 1) 权限 ---
        if [ -d "$_KB_DIR" ]; then
            _dirmode=$(stat -c %a "$_KB_DIR" 2>/dev/null)
            case "$_dirmode" in
                7[0-9][0-9]|75[0-9]) ;;
                *) chmod 755 "$_KB_DIR" 2>/dev/null ;;
            esac
        fi
        if [ -f "$_KB" ]; then
            _mode=$(stat -c %a "$_KB" 2>/dev/null)
            if [ "$_mode" != "644" ]; then
                if chmod 644 "$_KB" 2>/dev/null; then
                    _log "INFO" "keybox perms $_mode -> 644 (engine in keystore2 uid must be able to read it)"
                else
                    _log "WARN" "keybox chmod 644 failed (mode=$_mode); engine may fail to read it"
                fi
            fi
        fi

        # --- 2) 内容变化 / 有效性 ---
        _sha=$(_kb_sha "$_KB")
        if [ -n "$_sha" ] && [ "$_sha" != "$_last_sha" ]; then
            if [ -n "$_last_sha" ]; then
                _log "INFO" "keybox changed: $_last_sha -> $_sha (engine FileObserver reloads it on next request)"
            else
                _log "INFO" "keybox watchdog: sha=$_sha"
            fi
            _last_sha="$_sha"
            _validate_fail=0
        fi

        if [ -n "$BIN" ] && [ -x "$BIN" ] && [ -f "$_KB" ]; then
            if ! timeout 20 "$BIN" keybox validate >/dev/null 2>&1; then
                _now=$(date +%s 2>/dev/null || echo 0)
                if [ "$_validate_fail" = "0" ]; then
                    _validate_fail=1
                    _log "WARN" "keybox validate failed — scheduling a forced fetch"
                fi
                if [ "$_now" -ge "$_fetch_cooldown" ]; then
                    _fetch_cooldown=$(( _now + 300 ))
                    _log "WARN" "forcing keybox fetch after validation failure"
                    if "$BIN" keybox fetch >> "$LOG_BASE_DIR/main.log" 2>&1; then
                        _sha=$(_kb_sha "$_KB")
                        _last_sha="$_sha"
                        if timeout 20 "$BIN" keybox validate >/dev/null 2>&1; then
                            _validate_fail=0
                            _log "INFO" "keybox recovered by forced fetch (sha=${_sha:-?})"
                        else
                            _log "ERROR" "keybox still invalid after fetch; attestation will fall back to software certs"
                        fi
                    else
                        _log "ERROR" "forced keybox fetch failed (all sources unreachable?)"
                    fi
                fi
            fi
        elif [ -d "$_KB_DIR" ] && [ ! -f "$_KB" ]; then
            _now=$(date +%s 2>/dev/null || echo 0)
            if [ -n "$BIN" ] && [ -x "$BIN" ] && [ "$_now" -ge "$_fetch_cooldown" ]; then
                _fetch_cooldown=$(( _now + 300 ))
                _log "WARN" "keybox missing at $_KB — forcing one fetch"
                "$BIN" keybox fetch >> "$LOG_BASE_DIR/main.log" 2>&1 \
                    || _log "ERROR" "keybox fetch failed and no local keybox exists"
            fi
        fi

        # 开机很久之后仍没换过（四路源全挂时守护进程只保旧文件不出声）
        if [ "$(getprop sys.boot_completed)" = "1" ] && [ -f "$_KB" ] && [ -n "$BIN" ] && [ -x "$BIN" ]; then
            _age=$(( $(date +%s 2>/dev/null || echo 0) - $(stat -c %Y "$_KB" 2>/dev/null || echo 0) ))
            if [ "$_age" -gt 93600 ]; then
                _now=$(date +%s 2>/dev/null || echo 0)
                if [ "$_now" -ge "$_fetch_cooldown" ]; then
                    _fetch_cooldown=$(( _now + 1800 ))
                    _log "WARN" "keybox stale (${_age}s old) — forcing one fetch"
                    "$BIN" keybox fetch >> "$LOG_BASE_DIR/main.log" 2>&1 \
                        || _log "WARN" "forced keybox fetch failed (all sources unreachable?)"
                fi
            fi
        fi

        # --- 3) 镜像到 TA 兼容目录（单向：实时 -> 副本） ---
        if [ -f "$_KB" ] && [ -d "$TS_DIR" ]; then
            _dst="$TS_DIR/keybox.xml"
            if [ ! -f "$_dst" ] || ! cmp -s "$_KB" "$_dst" 2>/dev/null; then
                _tmp="${_dst}.mirror.$$"
                if cp -f "$_KB" "$_tmp" 2>/dev/null && chmod 644 "$_tmp" 2>/dev/null && \
                   sync && mv -f "$_tmp" "$_dst" 2>/dev/null; then
                    _log "INFO" "keybox mirrored to $_dst"
                else
                    rm -f "$_tmp" 2>/dev/null
                    _log "WARN" "keybox mirror failed"
                fi
            fi
        fi
    done
) &

# Post-boot maintenance, bounded and fully backgrounded: a hung boot skips
# these tasks with a warning instead of stalling this script.
(
    _log "INFO" "Waiting for boot completion"
    if ! wait_for_boot_completed; then
        _log "WARN" "Boot wait timed out; skipping boot-gated maintenance"
        exit 0
    fi
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

    # Xposed Detection (background)
    "$BIN" status xposed-scan >> "$LOG_BASE_DIR/main.log" 2>&1 &

    # Keybox 相关（权限守护 / 有效性 / 镜像）已移到 daemon 启动处独立运行：
    # 那条守护必须早于 boot 完成，才能盖住"拉取成功 -> 权限变 0600 -> engine
    # 读不到"这个窗口。这里不再重复做一遍，避免两个循环同时写兼容副本。
) &
