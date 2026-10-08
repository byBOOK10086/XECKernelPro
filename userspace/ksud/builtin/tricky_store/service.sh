#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/TEESimulator-RS（GPL-3.0）。
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
chmod 755 "$RUNTIME" 2>/dev/null
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

# ---------------------------------------------------------------------------
# keybox 播种 / 自愈升级
# ---------------------------------------------------------------------------
# 三件事，全部离线完成（不依赖设备能不能连上 GitHub）：
#
#  1) 实时副本缺失 -> 用模块内置副本播种。
#  2) **上一版内置副本 -> 本版内置副本** 的自动升级。CI 每次构建都会把上游最新的
#     keybox 打进本模块（.github/scripts/refresh-keybox.sh，在 ksud 构建前跑），
#     所以"刷写 / 更新模块"本身就是一次密钥刷新——这正是中国大陆用户拿不到新
#     箱子的离线出路。
#     判据不是时间（tmpfs 里模块文件的 mtime 就是本次开机时间，没有可比性），
#     而是"实时副本的 sha == 上一次记录的内置副本 sha"：这恰好说明实时副本只是
#     上一版的内置副本，此时换成这一版的；如果实时副本既不是本版也不是上一版
#     内置副本，说明它是**拉取来的**，那就保留（拉取来的通常比构建时打进来的新，
#     而且反过来覆盖就是"出厂副本倒灌"那种故障）。
#  3) 结构核验 + 权限 644 + 取证。DeviceID 里带着 yurikey 代次（如 "Yurikey58"），
#     日志里看到代次就能判断这份箱子有多旧。
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
_kb_live_sha=$(_kb_sha "$_KB_LIVE")
_kb_ship_sha=$(_kb_sha "$_KB_SHIPPED")
_kb_prev_ship=$(cat "$_KB_STAMP" 2>/dev/null)

if [ ! -f "$_KB_LIVE" ]; then
    if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
        log_line "INFO keybox: seeded from bundled copy (DeviceID=$(_kb_device_id "$_KB_LIVE") sha=$(_kb_sha "$_KB_LIVE"))"
        _kb_live_sha=$(_kb_sha "$_KB_LIVE")
    else
        log_line "ERROR keybox: failed to seed from bundled copy"
    fi
elif [ -n "$_kb_ship_sha" ] && [ "$_kb_live_sha" = "$_kb_prev_ship" ] && [ "$_kb_live_sha" != "$_kb_ship_sha" ]; then
    if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
        log_line "INFO keybox: upgraded to this build's bundled copy (sha $_kb_live_sha -> $(_kb_sha "$_KB_LIVE") DeviceID=$(_kb_device_id "$_KB_LIVE"))"
        _kb_live_sha=$(_kb_sha "$_KB_LIVE")
    else
        log_line "ERROR keybox: upgrade to bundled copy failed"
    fi
elif [ -n "$_kb_live_sha" ] && [ "$_kb_live_sha" != "$_kb_ship_sha" ] && [ "$_kb_live_sha" != "$_kb_prev_ship" ]; then
    log_line "INFO keybox: keeping fetched copy (DeviceID=$(_kb_device_id "$_KB_LIVE") sha=$_kb_live_sha); not overwritten by bundled copy sha=$_kb_ship_sha"
fi

if [ -n "$_kb_ship_sha" ]; then
    printf '%s\n' "$_kb_ship_sha" > "$_KB_STAMP" 2>/dev/null
fi

if [ -f "$_KB_LIVE" ] && ! _kb_looks_valid "$_KB_LIVE"; then
    log_line "WARN keybox: live copy failed structural check (sha=$(_kb_sha "$_KB_LIVE")) — engine will fall back to software-level certs until TA fetches a fresh one"
fi

# ---------------------------------------------------------------------------
# target.txt 覆盖合并 + 配置与"管理器副本"对账
# ---------------------------------------------------------------------------
# 详见同目录 target.baseline.txt 顶部：引擎的白名单是**穷举**的，不在
# /data/adb/tricky_store/target.txt 里的 UID 会被完全跳过、原样拿到真实 TEE 证明，
# 而管理器 / WebUI 写的却是另一个路径（$DATA）。两边从未对账，于是"刷了新版本、
# 验机工具依旧报未知认证根证书 / 无效的信任根状态"。
#
# 这里做四件事：缺失才播种（保留用户既有列表）→ 把 baseline 缺失项只增不删地追加
# → $DATA（用户意图）与 $RUNTIME（引擎实际读取）双向对账 → 权限与覆盖取证。
DATA=/data/adb/.xudc_secure
mkdir -p "$DATA" 2>/dev/null
chmod 755 "$DATA" 2>/dev/null

# 白名单合并 / 双向对账 / 覆盖取证都在 engineconf.sh 里（纯函数，可离线单测）。
. "$MODPATH/engineconf.sh"

# Seed configuration only when absent; user edits survive reboot and updates.
if [ ! -f "$RUNTIME/target.txt" ]; then
    stage_file "$MODPATH/target.txt" "$RUNTIME/target.txt" 644 || exit 1
fi
if [ ! -f "$RUNTIME/security_patch.txt" ]; then
    printf 'system=prop\n' > "$RUNTIME/security_patch.txt" || exit 1
fi
if [ ! -f "$RUNTIME/hbk" ]; then
    dd if=/dev/random of="$RUNTIME/hbk" bs=32 count=1 2>/dev/null || exit 1
fi
if [ ! -f "$DATA/target.txt" ]; then
    stage_file "$MODPATH/target.txt" "$DATA/target.txt" 644 || exit 1
fi
if [ ! -f "$DATA/security_patch.txt" ]; then
    printf 'system=prop\n' > "$DATA/security_patch.txt" || exit 1
fi

merge_baseline "$RUNTIME/target.txt" "$MODPATH/target.baseline.txt"
merge_baseline "$DATA/target.txt" "$MODPATH/target.baseline.txt"
sync_conf target.txt
sync_conf security_patch.txt
chmod 644 "$RUNTIME/keybox.xml" "$RUNTIME/target.txt" "$RUNTIME/security_patch.txt" "$RUNTIME/hbk" 2>/dev/null
chmod 644 "$DATA/target.txt" "$DATA/security_patch.txt" 2>/dev/null
log_target_coverage

# TA_enhanced still reads this compatibility directory for its status/config UI.
label_data_path "$DATA"
# 注意：**这里不再播种 $DATA/keybox.xml**。
# 这一份是 TA 侧的"兼容副本"，而 TA 的 keybox.sh 会把"兼容副本被外部改动"
# （用户在 WebUI 里粘贴自定义 keybox 就走这条路径）当成用户意图，装进实时路径。
# 如果这里在缺少兼容副本时用模块内置副本回填，就会出现这样的降级链：
# 实时副本是比较新的拉取结果 -> 兼容副本被删/缺失 -> 这里回填旧的内置副本 ->
# TA 判定"用户改过" -> 把旧箱子装回实时路径。宁可不回填：TA 的守护会在 15s 内
# 把实时副本镜像过来，WebUI 的显示与自定义写入都不受影响。
if [ ! -f "$DATA/hbk" ]; then
    dd if=/dev/random of="$DATA/hbk" bs=32 count=1 2>/dev/null || exit 1
fi
chmod 644 "$DATA/hbk" 2>/dev/null

# 引擎启动前的密钥取证（用上面已定义好的 _kb_sha / _kb_device_id / _kb_looks_valid）。
#
# 这一段存在的理由：一旦实时 keybox 缺失/结构损坏/被写坏，从引擎启动到下一个
# 可用箱子之间，证明请求只能用软件级证书链——检测方看到的就是"bootloader 未锁 /
# 密钥没传过来"。把"当前用的是哪一份、什么权限、结构对不对、第几代"在引擎启动
# 那一刻写进 .engine.log，事后排查不用再猜。
#
# 权限一并钉住：vendor 每次成功写入都会把实时 keybox 落成 0600 root
# （keybox/mod.rs::install_data 的 set_permissions(0o600)），而**读**它的
# KeyBoxManager 跑在 keystore2 进程里（uid=keystore），0600 打不开。
# TA 侧的常驻守护每 1s 会改回 644，这里管的是引擎启动这一瞬间的那一份。
if [ -f "$_KB_LIVE" ]; then
    _kb_now=$(date +%s 2>/dev/null)
    _kb_mtime=$(stat -c %Y "$_KB_LIVE" 2>/dev/null || echo "$_kb_now")
    _kb_age=$(( _kb_now - _kb_mtime ))
    _kb_mode=$(stat -c %a "$_KB_LIVE" 2>/dev/null)
    if [ "$_kb_mode" != "644" ]; then
        if chmod 644 "$_KB_LIVE" 2>/dev/null; then
            log_line "INFO keybox: perms $_kb_mode -> 644 before engine start (reader runs as uid=keystore)"
        else
            log_line "WARN keybox: chmod 644 failed (mode=$_kb_mode); engine may not be able to read it"
        fi
    fi
    _kb_live=$(_kb_sha "$_KB_LIVE")
    if _kb_looks_valid "$_KB_LIVE"; then
        log_line "INFO keybox: present (sha=${_kb_live:-?} age=${_kb_age}s DeviceID=$(_kb_device_id "$_KB_LIVE") bundled=${_kb_ship_sha:-?} perms=644)"
    else
        log_line "WARN keybox: present but structurally invalid (sha=${_kb_live:-?}) — TA guardian will replace it from its mirror list"
    fi
    if [ -n "$_kb_live" ] && [ "$_kb_live" = "$_kb_ship_sha" ]; then
        log_line "WARN keybox: this is the copy bundled at build time — if attestation fails right after a fresh flash, either this build predates the latest yurikey rotation or all mirrors are unreachable"
    fi
else
    log_line "WARN keybox: absent at engine start — attestation will fall back to software-level certs until TA fetches one"
fi

cd "$RUNTIME" || exit 1
./supervisor ./daemon "$RUNTIME" >> "$LOG_FILE" 2>&1 &
log_line "INFO TEESimulator supervisor launched (pid=$!)"

# 引擎状态取证：启动成功与否必须**回读**，不能靠"启动命令没报错"。
#
# 之前的写法是无条件写一行 "supervisor started"，于是引擎注入失败、进程五连败退出
# 时日志里同样写着"已启动"，排查时等于没有信息。这里延迟一段时间后回读三件事：
#   1) 进程是否真的活着；
#   2) 引擎自己的 logcat（keybox 是否解析成功、注入是否成功）；
#   3) 白名单覆盖数（不在列表里的包根本不会被拦截）。
capture_engine_state() {
    local pid dump
    pid=$(pidof TEESimulator 2>/dev/null)
    if [ -n "$pid" ]; then
        log_line "INFO engine: TEESimulator alive (pid=$pid)"
    else
        log_line "WARN engine: TEESimulator NOT running — keystore is not intercepted, attestation stays the real TEE one (untrusted key + unlocked bootloader)"
    fi
    dump=$(/system/bin/logcat -d -s TEESimulator:* 2>/dev/null \
        | grep -E "Finished parsing|Key store file not found|Fatal error parsing|Injection process failed|Interceptors initialized|Backdoor not found|Successfully rebuilt" \
        | tail -n 6)
    if [ -n "$dump" ]; then
        printf 'INFO engine logcat:\n%s\n' "$dump" >> "$LOG_FILE" 2>/dev/null
    else
        log_line "WARN engine: no TEESimulator logcat lines yet — engine has not handled an attestation request (or never started)"
    fi
    log_target_coverage
}

( sleep 45; capture_engine_state ) >/dev/null 2>&1 &

# WebUI / 管理器改 target.txt 或 security_patch.txt 后不必重启：每 15s 对账一次，
# 有差异时用 tmp + mv 写入，引擎的 ConfigObserver 收到 MOVED_TO 会自动重载。
( while :; do sleep 15; sync_conf target.txt; sync_conf security_patch.txt; done ) >/dev/null 2>&1 &
