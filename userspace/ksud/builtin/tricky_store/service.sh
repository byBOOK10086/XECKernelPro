#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 重写，改动清单与日期见同目录
# NOTICE.md「本地修改」。运行期引擎为 Oh My Keymint 1.2.6-9069c6c
# （third_party/OhMyKeymint/UPSTREAM.md，GLA v4，非 GPL）。
#
# 本模块是引擎“载体”：模块身份、生命周期、keybox 真源、作用域（target.txt）与
# 管理器状态都归我们；软件 KeyMint 本体与 keystore2 注入载荷是 OMY 的二进制，
# 由它自己的 daemon / daemon-injector 看护脚本拉起。
#
# 与旧版（TEESimulator）最大的三点不同：
#   1) 没有 supervisor / classes.dex：引擎是原生二进制，进程名 keymint；
#   2) keybox 与作用域必须落到 /data/misc/keystore/omk（属主 1017），不是引擎目录；
#   3) 注入器会按需 kill 并重启 keystore2，所以“重启引擎”不能顺带重启别的服务。
MODPATH=${0%/*}
RUNTIME=/data/adb/tricky_store
ENGINE="$RUNTIME/engine"
COMPAT_DIR=/data/adb/ksu/builtin/tricky_store
LOG_FILE="$RUNTIME/.engine.log"
OMK=/data/misc/keystore/omk
OMK_STATE=/data/adb/omk
ENGINE_ID=omk-1.2.6-9069c6c

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
    return 0
}

# 模块自述归一化：老设备上 /data/adb/tricky_store/module.prop 可能还是把上游引擎名
# 写进 name 的旧副本，检测器能按名字直接命中。只在确实带旧名时改写。
_normalize_module_prop() {
    local mp="$RUNTIME/module.prop"
    [ -f "$mp" ] || return 0
    grep -qE 'TEESimulator|Oh My Keymint' "$mp" 2>/dev/null || return 0
    sed -i 's/^name=.*/name=系统密钥组件/' "$mp" 2>/dev/null
    sed -i '/^updateJson=/d' "$mp" 2>/dev/null
    log_line "INFO module.prop normalized (no upstream engine name in the UI)"
}

# 2026-11-05 本地修改：内置引擎现在自己就装在 /data/adb/ksu/builtin/tricky_store，
# 不再是隐藏目录里的那份。所以「外部模块占位」的判定必须排除 $MODPATH，否则它会把
# 自己当成外部 TrickyStore、直接 stand down，引擎永远不启动（原来的隐藏布局下这个
# 判定成立，因为内置引擎跑在 /dev/.xudc_hidden 里）。
SELF_DIR="${MODPATH%/}"
BUILTIN_DIR=/data/adb/ksu/builtin/tricky_store

# 2026-11-11 产品裁定（用户）：**管理器内置引擎优先**，外部一律让位、始终不运行；
# 只有用户关掉 TEE 引擎（`xudc builtin tee off`）时才轮到外部。于是这里取代 11-05
# 那版"谁看见谁都退"的互让——真机上内置树和模块版互相 stand down，结果 keystore2
# 谁都没接管，bootloader 一直显示未锁定。
#
# 2026-11-13：引擎换成 Oh My Keymint 后，用户仍可能另装上游 `oh_my_keymint` 模块；
# 两套注入会同时抢 keystore2，所以内置树对**真正的上游引擎模块**让位，但对自己那份
# `/data/adb/modules/tricky_store` 副本仍然不让（那条是 11-11 裁定要压制的对象）。
active_module_dir() {
    local dir="$1"
    [ "$dir" = "$SELF_DIR" ] && return 1
    [ -f "$dir/module.prop" ] || return 1
    [ -f "$dir/disable" ] && return 1
    [ -f "$dir/remove" ] && return 1
    return 0
}

external_engine_active() {
    local dir
    if [ "$SELF_DIR" = "$BUILTIN_DIR" ]; then
        for dir in /data/adb/modules/oh_my_keymint /data/adb/modules/ohmykeymint; do
            active_module_dir "$dir" && return 0
        done
        return 1
    fi
    [ -f "$BUILTIN_DIR/module.prop" ] && return 0
    for dir in /data/adb/modules/tricky_store \
               /data/adb/modules/.tricky_store \
               /data/adb/modules/oh_my_keymint \
               /data/adb/modules/ohmykeymint; do
        active_module_dir "$dir" && return 0
    done
    return 1
}

mkdir -p "$RUNTIME" || exit 1
chmod 755 "$RUNTIME" 2>/dev/null
touch "$LOG_FILE" 2>/dev/null || exit 1
label_data_path "$RUNTIME"
_normalize_module_prop

if external_engine_active; then
    log_line "INFO builtin engine stand down: external engine module is present"
    exit 0
fi

# ---------------------------------------------------------------------------
# 引擎落盘（整目录原子替换）
# ---------------------------------------------------------------------------
# 引擎文件 10MB（keymint 7.75MB + inject 2.34MB），逐文件 stage 会留下半套状态；
# 这里先铺到 .engine.<pid>.tmp，再整目录换。版本戳最后写，中断即下次重试。
VER=$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop" 2>/dev/null)
[ -n "$VER" ] || VER=0
CUR=$(cat "$RUNTIME/.engine_version" 2>/dev/null)
need_stage=0
[ "$CUR" = "$VER $ENGINE_ID" ] || need_stage=1
for f in daemon daemon-injector libs/arm64-v8a/keymint libs/arm64-v8a/inject LICENSE.1 LICENSE.2 AOSP.Apache-license-2.0.txt README.md; do
    [ -f "$ENGINE/$f" ] || { need_stage=1; break; }
    if command -v cmp >/dev/null 2>&1; then
        cmp -s "$MODPATH/engine/$f" "$ENGINE/$f" || { need_stage=1; break; }
    else
        [ "$(wc -c < "$MODPATH/engine/$f")" = "$(wc -c < "$ENGINE/$f")" ] || { need_stage=1; break; }
    fi
done

if [ "$need_stage" = 1 ]; then
    log_line "INFO engine: staging $ENGINE_ID (versionCode=$VER)"
    STAGE_DIR="$RUNTIME/.engine.$$.tmp"
    rm -rf "$STAGE_DIR"
    mkdir -p "$STAGE_DIR/libs/arm64-v8a" || exit 1
    for f in daemon daemon-injector; do
        stage_file "$MODPATH/engine/$f" "$STAGE_DIR/$f" 755 || { rm -rf "$STAGE_DIR"; exit 1; }
    done
    for f in keymint inject; do
        stage_file "$MODPATH/engine/libs/arm64-v8a/$f" "$STAGE_DIR/libs/arm64-v8a/$f" 755 || {
            rm -rf "$STAGE_DIR"; exit 1
        }
    done
    for f in LICENSE.1 LICENSE.2 AOSP.Apache-license-2.0.txt README.md; do
        stage_file "$MODPATH/engine/$f" "$STAGE_DIR/$f" 644 || { rm -rf "$STAGE_DIR"; exit 1; }
    done
    rm -rf "$ENGINE"
    mv -f "$STAGE_DIR" "$ENGINE" || { rm -rf "$STAGE_DIR"; exit 1; }
    printf '%s\n' "$VER $ENGINE_ID" > "$RUNTIME/.engine_version.probe" 2>/dev/null
    mv -f "$RUNTIME/.engine_version.probe" "$RUNTIME/.engine_version" 2>/dev/null
    label_data_path "$ENGINE"
    label_data_path "$ENGINE/libs/arm64-v8a/keymint"
    label_data_path "$ENGINE/libs/arm64-v8a/inject"
    sync
fi

# Compatibility directory：TA_enhanced 的旧脚本仍会找 /data/adb/ksu/builtin/tricky_store。
mkdir -p "$COMPAT_DIR" || exit 1
printf 'xecpro compatibility directory\n' > "$COMPAT_DIR/.xecpro_compat" 2>/dev/null
chmod 600 "$COMPAT_DIR/.xecpro_compat" 2>/dev/null
label_data_path "$COMPAT_DIR"

# ---------------------------------------------------------------------------
# omk 工作目录（post-fs-data 已建；这里兜底，属主错就纠正）
# ---------------------------------------------------------------------------
mkdir -p "$OMK/logs" "$OMK_STATE" 2>/dev/null
chmod 0770 "$OMK" "$OMK/logs" 2>/dev/null
_omk_uid=$(stat -c %u "$OMK" 2>/dev/null)
if [ "$_omk_uid" != "1017" ]; then
    chown 1017:1017 "$OMK" "$OMK/logs" 2>/dev/null || \
        log_line "WARN omk: chown 1017 failed (owner=$_omk_uid); engine may not read keybox"
fi

# Upstream wrappers prefer these global override files. Preserve them before
# selecting our pinned payload; never silently execute a stale external build.
for f in keymint inject; do
    if [ -e "$OMK_STATE/$f" ] || [ -L "$OMK_STATE/$f" ]; then
        backup="$OMK_STATE/$f.pre-xec"
        # 上游 OMY 可能在我们运行期间再次写入热更新二进制（用户重装模块、手动替换）。
        # 只保留一份备份还不够：直接报错退出会让引擎整套不启动。这里轮转备份
        # （pre-xec -> pre-xec.old，新文件 -> pre-xec），始终保留最新一份供关闭内置
        # 引擎时归还，也保证本次启动继续用我们锁定的载荷。
        if [ -e "$backup" ] || [ -L "$backup" ]; then
            rm -f "$OMK_STATE/$f.pre-xec.old"
            mv "$backup" "$OMK_STATE/$f.pre-xec.old" || exit 1
            log_line "INFO rotated previous override backup to $f.pre-xec.old"
        fi
        mv "$OMK_STATE/$f" "$backup" || exit 1
        log_line "INFO preserved upstream binary override at $backup"
    fi
done

# ---------------------------------------------------------------------------
# keybox：真源 $RUNTIME/keybox.xml，镜像到 omk（OMY 读的那份）
# ---------------------------------------------------------------------------
_kb_sha() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | cut -c1-16
    elif command -v md5sum >/dev/null 2>&1; then
        md5sum "$1" 2>/dev/null | cut -c1-16
    else
        printf 'size-%s' "$(wc -c < "$1" 2>/dev/null | tr -d ' ')"
    fi
}

_kb_device_id() {
    sed -n 's/.*DeviceID="\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -n 1
}

_kb_looks_valid() {
    grep -q '<AndroidAttestation' "$1" 2>/dev/null || return 1
    grep -q '<Keybox' "$1" 2>/dev/null || return 1
    [ "$(grep -c 'BEGIN CERTIFICATE' "$1" 2>/dev/null)" -ge 2 ] || return 1
    return 0
}

_KB_LIVE="$RUNTIME/keybox.xml"
_KB_SHIPPED="$MODPATH/keybox.xml"
_KB_STAMP="$RUNTIME/.keybox_shipped"
_KB_OMK="$OMK/keybox.xml"

if [ ! -f "$_KB_LIVE" ]; then
    if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
        log_line "INFO keybox: seeded from bundled copy (DeviceID=$(_kb_device_id "$_KB_LIVE") sha=$(_kb_sha "$_KB_LIVE"))"
    else
        log_line "ERROR keybox: failed to seed from bundled copy"
    fi
fi
_kb_ship_sha=$(_kb_sha "$_KB_SHIPPED")
_kb_prev_ship=$(cat "$_KB_STAMP" 2>/dev/null)
_kb_live_sha=$(_kb_sha "$_KB_LIVE")
# 上一版内置副本 -> 本版内置副本的自动升级（与旧逻辑一致：只升级“出厂副本”，
# 用户或引擎自己拉来的新箱子不动）。
if [ -n "$_kb_ship_sha" ] && [ "$_kb_live_sha" = "$_kb_prev_ship" ] && [ "$_kb_live_sha" != "$_kb_ship_sha" ]; then
    if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
        log_line "INFO keybox: upgraded to this build's bundled copy (sha $_kb_live_sha -> $(_kb_sha "$_KB_LIVE"))"
        _kb_live_sha=$(_kb_sha "$_KB_LIVE")
    else
        log_line "ERROR keybox: upgrade to bundled copy failed"
    fi
fi
if [ -n "$_kb_ship_sha" ]; then
    printf '%s\n' "$_kb_ship_sha" > "$_KB_STAMP" 2>/dev/null
fi
chmod 644 "$_KB_LIVE" 2>/dev/null

# 镜像到 omk：OMY 在 keystore2 进程里以 uid=keystore 读它，所以 1017:1017 0600。
# 原子写（tmp + mv）：OMY 的 ConfigObserver 认 MOVED_TO / CLOSE_WRITE。
mirror_keybox() {
    local tmp="$OMK/.keybox.xml.tmp.$$" live="$RUNTIME/keybox.xml"
    [ -f "$live" ] || return 0
    if [ -f "$_KB_OMK" ] && cmp -s "$live" "$_KB_OMK" 2>/dev/null && \
       [ "$(stat -c %u "$_KB_OMK" 2>/dev/null)" = "1017" ] && \
       [ "$(stat -c %a "$_KB_OMK" 2>/dev/null)" = "600" ]; then
        return 0
    fi
    cp -f "$live" "$tmp" 2>/dev/null || return 1
    chown 1017:1017 "$tmp" 2>/dev/null
    chmod 0600 "$tmp" 2>/dev/null
    mv -f "$tmp" "$_KB_OMK" 2>/dev/null || { rm -f "$tmp"; return 1; }
    log_line "INFO keybox: mirrored to $OMK (DeviceID=$(_kb_device_id "$_KB_OMK") sha=$(_kb_sha "$_KB_OMK"))"
    return 0
}
mirror_keybox

if [ -f "$_KB_LIVE" ] && ! _kb_looks_valid "$_KB_LIVE"; then
    log_line "WARN keybox: live copy failed structural check (sha=$(_kb_sha "$_KB_LIVE")) — engine will report an expired/unknown root until a fresh keybox lands"
fi

# ---------------------------------------------------------------------------
# 作用域：target.txt（我们） -> injector.toml 的 scoop（OMY）
# ---------------------------------------------------------------------------
DATA=/data/adb/.xudc_secure
mkdir -p "$DATA" 2>/dev/null
chmod 755 "$DATA" 2>/dev/null

. "$MODPATH/engineconf.sh"

if [ ! -f "$RUNTIME/target.txt" ]; then
    stage_file "$MODPATH/target.txt" "$RUNTIME/target.txt" 644 || exit 1
fi
if [ ! -f "$RUNTIME/security_patch.txt" ]; then
    printf 'system=prop\n' > "$RUNTIME/security_patch.txt" || exit 1
fi
if [ ! -f "$DATA/target.txt" ]; then
    stage_file "$MODPATH/target.txt" "$DATA/target.txt" 644 || exit 1
fi
if [ ! -f "$DATA/security_patch.txt" ]; then
    printf 'system=prop\n' > "$DATA/security_patch.txt" || exit 1
fi

merge_baseline "$RUNTIME/target.txt" "$MODPATH/target.baseline.txt"
merge_baseline "$DATA/target.txt" "$MODPATH/target.baseline.txt"
apply_detector_routing
sync_conf target.txt
sync_conf security_patch.txt
chmod 644 "$RUNTIME/target.txt" "$RUNTIME/security_patch.txt" 2>/dev/null
chmod 644 "$DATA/target.txt" "$DATA/security_patch.txt" 2>/dev/null
log_target_coverage
sync_scoop

label_data_path "$DATA"

# ---------------------------------------------------------------------------
# 启动两个守护进程（OMY 自己的看护脚本，内部会自动重启子进程）
# ---------------------------------------------------------------------------
# 幂等启动：管理器/TA 都可能再跑一次 service.sh，重复拉起会得到两份看护循环，
# 每份都在自己的循环里重启 keymint，最后 keystore2 被反复注入。
_daemon_alive() {
    local pidfile="$1" marker="$2" pid
    pid=$(cat "$pidfile" 2>/dev/null)
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qF "$marker" || return 1
    return 0
}

_start_engine() {
    local which="$1"
    if [ "$which" = "injector" ]; then
        if _daemon_alive "$OMK_STATE/injector-daemon.pid" "/engine/daemon-injector "; then
            log_line "INFO engine: injector daemon already running, not starting a second one"
            return 0
        fi
        cd "$ENGINE" || return 1
        sh "$ENGINE/daemon-injector" >> "$LOG_FILE" 2>&1 &
        printf '%s\n' "$!" > "$OMK_STATE/injector-daemon.pid" 2>/dev/null
    else
        if _daemon_alive "$OMK_STATE/keymint-daemon.pid" "/engine/daemon "; then
            log_line "INFO engine: keymint daemon already running, not starting a second one"
            return 0
        fi
        cd "$ENGINE" || return 1
        sh "$ENGINE/daemon" >> "$LOG_FILE" 2>&1 &
        printf '%s\n' "$!" > "$OMK_STATE/keymint-daemon.pid" 2>/dev/null
    fi
    return 0
}

_engine_pid() {
    pidof keymint 2>/dev/null | awk '{print $1}'
}

_keystore_pid() {
    pidof keystore2 2>/dev/null | awk '{print $1}'
}

# 注入是否生效：keystore2 的 maps 里有没有我们的 inject 载荷。
_engine_injected() {
    sh "$MODPATH/injection-status.sh"
}

_start_engine keymint
_start_engine injector
log_line "INFO engine $ENGINE_ID launched (keymint pid=$(_engine_pid), injector armed)"

capture_engine_state() {
    local pid injected logtail
    pid=$(_engine_pid)
    if [ -n "$pid" ]; then
        log_line "INFO engine: keymint alive (pid=$pid)"
    else
        log_line "WARN engine: keymint NOT running — keystore2 is not intercepted, attestation stays the real TEE one"
    fi
    if _engine_injected; then
        log_line "INFO engine: keystore2 injected (pid=$(_keystore_pid))"
    else
        log_line "WARN engine: keystore2 NOT injected yet — injector may still be waiting for keystore2"
    fi
    logtail=$(tail -n 6 "$OMK/logs/keymint.log" 2>/dev/null)
    [ -z "$logtail" ] && logtail=$(tail -n 6 "$OMK/logs/injector.log" 2>/dev/null)
    if [ -n "$logtail" ]; then
        printf 'INFO engine omk log:\n%s\n' "$logtail" >> "$LOG_FILE" 2>/dev/null
    fi
    log_target_coverage
}

( sleep 45; capture_engine_state ) >/dev/null 2>&1 &

# 看护：keymint 或 injector 的看护进程都没了才重拉；子进程崩了由 OMY 自己循环重启。
engine_watchdog() {
    local _pid _attempts=0 _now _last_try=0
    while :; do
        sleep 300
        _pid=$(_engine_pid)
        if [ -n "$_pid" ] && _engine_injected; then
            _attempts=0
            continue
        fi
        _now=$(date +%s 2>/dev/null || echo 0)
        if [ "$_attempts" -ge 3 ] && [ "$_last_try" != "0" ] && \
           [ $(( _now - _last_try )) -lt 3600 ]; then
            continue
        fi
        _attempts=$((_attempts + 1))
        _last_try=$_now
        log_line "ERROR engine: keymint=$(_engine_pid) injected=$(_engine_injected && echo yes || echo no) — restarting engine (attempt $_attempts)"
        log_target_coverage
        _start_engine keymint
        _start_engine injector
        sleep 30
        if [ -n "$(_engine_pid)" ]; then
            log_line "INFO engine: restarted (pid=$(_engine_pid))"
        else
            log_line "ERROR engine: restart failed, keymint still not running"
        fi
    done
}
( sleep 90; engine_watchdog ) >/dev/null 2>&1 &

# 15s 对账：target/security_patch 双向同步 + scoop 重算 + keybox 镜像。
# scoop 变化由 OMY 的 watcher 热加载，不需要重启 keymint。
( _n=0; while :; do
    sleep 15
    sync_conf target.txt
    sync_conf security_patch.txt
    sync_scoop
    mirror_keybox
    _n=$((_n + 1))
    [ $((_n % 20)) -eq 0 ] && apply_detector_routing
  done ) >/dev/null 2>&1 &

exit 0
