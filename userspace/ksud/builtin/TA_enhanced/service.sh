# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
MODPATH=${0%/*}
MODDIR="$MODPATH"
PATH=$MODPATH/common/bin:/data/adb/ap/bin:/data/adb/ksu/bin:/data/adb/magisk:$PATH
HIDE_DIR="/data/adb/modules/.TA_enhanced"
TSPA="/data/adb/modules/tsupport-advance"

. "$MODPATH/common/common.sh"
detect_manager

# 日志目录必须在这里就建出来：_log 只在目录存在时才写文件，而 daemon 自己的默认
# 日志目录（/data/adb/tricky_store/ta-enhanced/logs，见 vendor config/mod.rs 的
# LoggingConfig::default）**不是**这个路径——不建的话本模块 shell 层的全部证据
# （权限修复、keybox 来源/级别、锁态校验、真实启动状态）都只进 logcat，
# 事后排查等于没有日志。daemon 的 logging.log_dir 在下面会统一到这里。
mkdir -p "$LOG_BASE_DIR" 2>/dev/null
chmod 755 "$LOG_BASE_DIR" 2>/dev/null

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

# A previous build shipped this tree as a Magisk module and hid its copy under a
# dot-prefixed directory. That shape no longer exists - ksud materialises the tree
# into /data/adb/ksu/builtin - so the only thing left to do is clear the old copy
# if one is still lying around from that era.
if [ -d "$HIDE_DIR" ]; then
    _log "INFO" "Removing the leftover module-era copy at $HIDE_DIR"
    rm -rf "$HIDE_DIR"
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

# v5.53.1 起 Rust 侧写属性不再走进程内实现，而是**固定路径** exec 一个 resetprop-rs
# （third_party/.../rust/src/platform/props.rs 的 RP_PATH =
#  /data/adb/tricky_store/ta-enhanced/bin/resetprop-rs）。它读的不是我们的 staging
#  目录，不补这一份的话：region 写入直接失败、security_patch 走属性分支时只告警却返回成功
#  —— 表现就是"补丁/区域设置改了但设备毫无变化"。
RP_VENDOR="/data/adb/tricky_store/ta-enhanced/bin/resetprop-rs"
if [ -x "$RP_STAGE" ]; then
    mkdir -p "${RP_VENDOR%/*}" 2>/dev/null
    if cp -f "$RP_STAGE" "${RP_VENDOR}.tmp.$$" 2>/dev/null && \
       chmod 755 "${RP_VENDOR}.tmp.$$" 2>/dev/null && \
       mv -f "${RP_VENDOR}.tmp.$$" "$RP_VENDOR" 2>/dev/null; then
        command -v chcon >/dev/null 2>&1 && \
            chcon --reference=/data/adb "$RP_VENDOR" 2>/dev/null || true
    else
        rm -f "${RP_VENDOR}.tmp.$$" 2>/dev/null
        _log "WARN" "resetprop-rs 未同步到 vendor 固定路径 $RP_VENDOR（daemon 的属性写入会失败）"
    fi
fi

# Preserve module.prop for WebUI version display. Hiding it from the manager
# list is only done where it cannot invalidate the module: our ksud executes
# module scripts without requiring module.prop, but Magisk treats a module
# dir without one as invalid and would stop running this script entirely.
cp -f "$MODPATH/module.prop" "/data/adb/.xudc_secure/ta-enhanced/module.prop" 2>/dev/null || true
# 上游有多处（版本显示 / status 上报 / WebUI 元数据）读的是
# `/data/adb/tricky_store/ta-enhanced/module.prop`，和我们的备份目录不是同一个路径；
# 不补这一份，界面上版本号只能落到 crate 版本，状态任务也拿不到元数据。
mkdir -p "/data/adb/tricky_store/ta-enhanced" 2>/dev/null || true
cp -f "$MODPATH/module.prop" "/data/adb/tricky_store/ta-enhanced/module.prop" 2>/dev/null || true
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

# bootloader 回锁态看护（30s 一次）：sys.oem_unlock_allowed 这类非 ro 属性会被
# 系统改回去，ro.boot.verifiedbootstate 也可能被别的模块覆盖；漂移要补写并留痕。
# 放在这里而不是 prop.sh 里：prop.sh 会被 daemon 周期调用，放里面会反复起进程。
if [ -f "$MODPATH/common/bootstate.sh" ]; then
    . "$MODPATH/common/bootstate.sh"
    bootstate_detect_mode
    bootstate_watchdog_loop &
    _log "INFO" "boot-state watchdog started"
fi

# keybox 拉取/镜像/可信核验曾在本仓库实现，改为交给 TEE 引擎与 OTA 提供方；
# 本脚本不再改写引擎配置，也不常驻守护，避免两处同时写同一份 keybox。

# Launch Daemon BEFORE the boot wait: it owns the periodic tasks (security
# patch, keybox fetch) and does not depend on sys.boot_completed. Waiting for
# boot first used to delay the engine by up to two minutes on slow boots.
_log "INFO" "Starting ta-enhanced daemon"
"$BIN" daemon --manager "$MANAGER" &
_log "INFO" "Daemon launched"

# ---------------------------------------------------------------------------
# 安全补丁：**每 2 小时**自动刷新（用户硬性要求）
# ---------------------------------------------------------------------------
# 每次开机强制对齐：config 里 interval 的合法下限是 3600 秒
# （rust/src/config/mod.rs 的 clamp），7200 合法；以前默认 86400，等于一天才动一次，
# Play Integrity 的 DEVICE 判定会因为补丁太旧掉到 BASIC。
# 白名单里留下 60/3600 等其他取值范围，是为了不让"用户在 WebUI 里选的值"被
# 无条件覆盖 —— 这里只在开机时写一次默认策略，运行期用户可以改。
if [ "$REMOTE_PRIORITY" = "true" ]; then
    "$BIN" config set security_patch.auto_update true >/dev/null 2>&1 \
        || _log "WARN" "security_patch.auto_update set failed"
    "$BIN" config set security_patch.interval 7200 >/dev/null 2>&1 \
        || _log "WARN" "security_patch.interval set failed"
fi
_log "INFO" "安全补丁自动更新：enabled=$(read_config security_patch.auto_update true) interval=$(read_config security_patch.interval 7200)s"

# ---------------------------------------------------------------------------
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

    # 指纹第二遍：boot_completed 之后系统/厂商框架可能重算过 identity 属性
    # （MIUI/HyperOS 会），再写一次并核对。失败只告警，不影响开机。
    if [ -f "$MODPATH/pif.sh" ]; then
        sh "$MODPATH/pif.sh" apply
    fi

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

) &
