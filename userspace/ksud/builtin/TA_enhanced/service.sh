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

# bootloader 回锁态看护（30s 一次）：sys.oem_unlock_allowed 这类非 ro 属性会被
# 系统改回去，ro.boot.verifiedbootstate 也可能被别的模块覆盖；漂移要补写并留痕。
# 放在这里而不是 prop.sh 里：prop.sh 会被 daemon 周期调用，放里面会反复起进程。
if [ -f "$MODPATH/common/bootstate.sh" ]; then
    . "$MODPATH/common/bootstate.sh"
    bootstate_detect_mode
    bootstate_watchdog_loop &
    _log "INFO" "boot-state watchdog started"
fi

# Keybox 自动获取（面向中国大陆）：TEERS（tricky_store）在位即视为两个 TEE 模块
# 均已激活，开机直接下发配置，用户无需进 WebUI 手动开/手动填源。
#
# 为什么源要换：vendor 自己的四路源（yurikey/upstream/integritybox）除了
# integritybox 的镜像之外全是 raw.githubusercontent.com，国内直连不可达；
# 而 vendor 的 is_online() 又是 ping api.github.com——国内必假，daemon 的首轮
# 拉取直接被跳过。设备端因此永远拿不到新箱子，只能用模块里的出厂副本直到吊销。
# 这里把 vendor 的 custom 源指向**本项目 keybox 分支的国内代理**（内容是解码好的
# 明文 XML，vendor 的 validate 能过；yurikey 原始源是 base64，不能给 vendor 用），
# 真正的多镜像轮换与可信核验由 common/keybox.sh 负责。
#
# interval/enabled 每次开机强制对齐；source/custom_url 只在用户没设过时补默认值
# （WebUI 里选过的源属于用户状态，不能被开机脚本覆盖）。
if [ -d "$TS" ] || [ -d "/data/adb/modules/.tricky_store" ] || [ -d "/data/adb/modules/tricky_store" ]; then
    "$BIN" config set keybox.enabled true >/dev/null 2>&1 || _log "WARN" "keybox.enabled set failed"
    "$BIN" config set keybox.interval 180 >/dev/null 2>&1 || _log "WARN" "keybox.interval set failed"

    # daemon 日志目录统一到 shell/WebUI 都在看的那一个路径，否则 fetch 失败原因
    # 只写在另一个目录里，排查时永远看不到。
    if [ "$(read_config logging.log_dir "")" != "$LOG_BASE_DIR" ]; then
        if "$BIN" config set logging.log_dir "$LOG_BASE_DIR" >/dev/null 2>&1; then
            _log "INFO" "daemon log_dir -> $LOG_BASE_DIR"
        else
            _log "WARN" "daemon log_dir set failed"
        fi
    fi

    keybox_source=$(read_config keybox.source "")
    if [ -z "$keybox_source" ]; then
        "$BIN" config set keybox.source custom >/dev/null 2>&1 || _log "WARN" "keybox.source set failed"
        keybox_source=custom
    fi
    # 给 vendor 用的源必须是"解码后的明文 XML"；镜像清单里第一条本项目分支的条目
    # 在国内代理上（ghfast.top），拿不到就退回 jsDelivr 上的同一份。
    keybox_primary_url=$(grep -m1 'XECKernelPro/keybox/keybox.xml' "$MODPATH/common/keybox_mirrors.txt" 2>/dev/null)
    [ -n "$keybox_primary_url" ] || keybox_primary_url="https://fastly.jsdelivr.net/gh/byBOOK10086/XECKernelPro@keybox/keybox.xml"
    keybox_url=$(read_config keybox.custom_url "")
    if [ "$keybox_source" = "custom" ] && [ -z "$keybox_url" ]; then
        "$BIN" config set keybox.custom_url "$keybox_primary_url" >/dev/null 2>&1 || _log "WARN" "keybox.custom_url set failed"
        keybox_url="$keybox_primary_url"
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
# Keybox 守护（中国大陆可用）：权限 / 可信核验 / 多镜像刷新 / 防退化
# ---------------------------------------------------------------------------
# 实现全部在 common/keybox.sh（本仓库新增文件，文件头逐条写了改动理由与代码证据），
# 这里只做接线：
#
#   1) kb_prepare             —— 建状态目录；把可信证书指纹表去注释后缓存
#                                （grep -f 遇到空行会匹配一切，必须先清干净）
#   2) kb_ensure_from_module  —— 开机即时可用：模块内置副本（由 CI 在上游拉取后
#                                打进构建产物）比实时副本新就换掉它。这是
#                                "刷写即拿到可信密钥"的**离线**路径，不需要设备
#                                能连上 GitHub；只有内置副本就是当前实时副本时
#                                才什么都不做。
#   3) kb_guardian_loop       —— 常驻看护：
#        每 1s  把实时 keybox 权限钉回 644（vendor 每次成功拉取都会写成
#               0600 root，而读它的 KeyBoxManager 跑在 keystore2 进程里，
#               uid=keystore 打不开；不修就是"密钥传不过来"）
#        每 15s 复核可信度（Google 链指纹）、防退化（无效/低级别副本一律用
#               上次可信副本顶回去）、双向对账 TA 兼容副本，并按需扫镜像换新
. "$MODPATH/common/keybox.sh"
kb_prepare
kb_ensure_from_module "$TS/keybox.xml"
kb_guardian_loop &
_log "INFO" "Keybox guardian started (state=$KB_STATE live级别=$(kb_trust_level "$KB_LIVE") DeviceID=$(kb_device_id "$KB_LIVE"))"

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

    # 开机完成后的最终取证（用户排查"密钥/bootloader 状态对不对"看的就这一行）：
    #   live级别 0 = 证书链挂到 Google Hardware Attestation Root（可信）
    #            1 = 结构合法但未命中指纹（多为中间证书轮换，仍可用）
    #            2 = 无效（此时守护已经尝试用副本恢复，见上面 WARN）
    if [ -f "$MODPATH/common/keybox.sh" ]; then
        bootstate_leak_report
        # 除 keybox / 锁态之外，再加三项"能不能生效"的前置事实——它们决定了
        # 引擎到底有没有替检测方伪造证明：
        #   zeromount  生效时整组锁态伪装被豁免（属性类检测方读到真实解锁态）
        #   拦截条目   /data/adb/tricky_store/target.txt 的条目数（引擎是穷举白名单，
        #              不在表里的包**完全不被拦截**，原样拿到真实 TEE 证明）
        #   验机工具覆盖 内置验机工具（wu.keyChain.test）是否在表内
        #   engine     引擎进程是否活着（不在 = keystore 完全没被接管）
        _zm=no
        [ -d /data/adb/modules/meta-zeromount ] && \
            [ ! -f /data/adb/modules/meta-zeromount/disable ] && \
            [ ! -f /data/adb/modules/meta-zeromount/remove ] && _zm=yes
        _tgt=/data/adb/tricky_store/target.txt
        _tgt_n=$(grep -cvE '^[[:space:]]*(#|$)' "$_tgt" 2>/dev/null)
        _tgt_ck=no
        grep -qxF -e 'wu.keyChain.test' "$_tgt" 2>/dev/null && _tgt_ck=yes
        _log "INFO" "boot 后总览：keybox live级别=$(kb_trust_level "$KB_LIVE") DeviceID=$(kb_device_id "$KB_LIVE") 权限=$(stat -c %a "$KB_LIVE" 2>/dev/null) sha=$(kb_short "$(kb_sha "$KB_LIVE")") | 锁态 verifiedboot=$(getprop ro.boot.verifiedbootstate) flash.locked=$(getprop ro.boot.flash.locked) device_state=$(getprop ro.boot.vbmeta.device_state) | zeromount=$_zm | 拦截 target=${_tgt_n:-0} 验机工具覆盖=$_tgt_ck engine=$(pidof TEESimulator 2>/dev/null || echo none) | mode=$BOOTSTATE_MODE"
    fi
) &
