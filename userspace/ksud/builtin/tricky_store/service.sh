#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 修改，非上游原样；改动清单与日期见
# 同目录 NOTICE.md「本地修改」。上游：Enginex0/TEESimulator-RS（GPL-3.0）。
# Built-in TEESimulator-RS engine init.
#
# The bundled classes.dex is compiled from this repository's vendored engine source
# (third_party/TEESimulator-RS). The native cert generator is loaded from the
# engine's own runtime directory; there is no upstream compatibility directory
# any more (see the compat note further down).
# The engine runs from /data/adb/tricky_store.
MODPATH=${0%/*}
RUNTIME=/data/adb/tricky_store
# 管理器副本 / 自检 / 日志都放这里（.xudc_secure）。**日志从 RUNTIME 挪出来**是
# 2026-10-15 这批去指纹的一部分：`.engine.log` 是整个模块信号最强的文件（里面写着
# DeviceID、keybox 指纹与失败原因），而 `/data/adb/tricky_store/` 这个名字本身就是
# 检测方（能读 /data/adb 的那些 root 级扫描器）第一个看的地方。
DATA=/data/adb/.xudc_secure
mkdir -p "$DATA" 2>/dev/null
chmod 755 "$DATA" 2>/dev/null
LOG_FILE="$DATA/engine.log"
# 一次性迁移：老版本把日志写在 RUNTIME 下，升级后把内容并过来再删掉旧文件，
# 否则"历史日志没了"会让下一次排查从零开始，而旧文件留在原路径又等于没改。
if [ -f "$RUNTIME/.engine.log" ]; then
    cat "$RUNTIME/.engine.log" >> "$LOG_FILE" 2>/dev/null
    rm -f "$RUNTIME/.engine.log" 2>/dev/null
fi

# 外部 tricky_store 模块占位时的存活宽限期（秒）：宽限期内看到 TEESimulator 进程就让位，
# 看不到就用 --takeover 接管。可用环境变量覆盖，便于本地/真机调参。
TAKEOVER_DELAY=${TAKEOVER_DELAY:-90}

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
#
# 注意：这里**必须**用 external_engine_active()。旧实现把六个 test 直接串成
# `A && B && C || D && E && F`，shell 里 `&&`/`||` 左结合，等价于
# `((A&&B&&C) || D) && E && F`——当 /data/adb/modules/tricky_store 存在并**已被
# 用户停用**（disable 文件在）时整体为假，本模块不但不 stand down，还会和那个外部
# 模块同时向 keystore2 注入两个引擎：两套 grant ledger、两套证书缓存互相打架，
# 检测方看到的就是"密钥行为异常"。
if external_engine_active; then
    if [ "$1" = "--takeover" ]; then
        log_line "WARN builtin TEERS: taking over — an external tricky_store module is present but no TEESimulator process is alive"
    else
        # 「占位」不等于「真的在拦」。旧实现只看到外部模块目录存在就让位并 exit，
        # 于是外部引擎一旦起不来（旧版引擎、Android 16 上注入失败、被系统杀掉而它自己
        # 没有看护、模块还在但运行时文件被清掉……），整台设备就变成
        # **完全没有拦截**：检测方永远拿到真实 TEE 证明 —— 表现就是用户报的
        # "bootloader 解锁 + 信任根异常"，而模块列表里一切正常、没有任何报错。
        # 所以让位之后补一次存活核对：宽限期结束还看不到 TEESimulator 进程就用
        # --takeover 重新进来，由本模块的引擎接管。外部引擎活着时这一步不会发生，
        # 不会出现双引擎互杀（那正是当初要 stand down 的原因）。
        log_line "INFO builtin TEERS stand down: external module directory is present (liveness check in ${TAKEOVER_DELAY}s)"
        (
            sleep "$TAKEOVER_DELAY"
            if [ -n "$(pidof keymint_svc 2>/dev/null)" ]; then
                echo "$(date '+%F %T') INFO builtin TEERS: external engine is alive, staying down" >> "$LOG_FILE" 2>/dev/null
                exit 0
            fi
            sh "$0" --takeover >> "$LOG_FILE" 2>&1
        ) >/dev/null 2>&1 &
        exit 0
    fi
fi

VER=$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop" 2>/dev/null)
[ -n "$VER" ] || VER=0

# ---------------------------------------------------------------------------
# 刷新判据 = 资产**内容戳**，不是上游的 versionCode
# ---------------------------------------------------------------------------
# 上游 module.prop 的 versionCode（162）描述的是引擎自己的版本；我们修引擎源码
# （grant 链一致性、RootOfTrust 多份伪造、STALE_* 覆盖、keystore2 原生回复形状…）
# 并不会改它。旧实现把"要不要把 $MODPATH 下的引擎资产同步进 $RUNTIME"挂在
# "$VER != $CUR" 上，于是第一次开机把某一版的 classes.dex/libTEESimulator.so
# 写进 /data/adb/tricky_store 之后，versionCode 永远还是 162，之后每一次刷入
# 新版本都被判成"已是最新"——设备上永远跑着第一次那一份引擎，引擎侧的修复
# 根本到不了设备。表现就是：刷了新版本，验机工具依旧报"引导加载程序已解锁 /
# 密钥行为异常"。改成对"即将写入的资产内容"取戳：内容一变就替换。
_engine_sources() {
    printf '%s\n' \
        "$MODPATH/daemon" \
        "$MODPATH/inject" \
        "$MODPATH/supervisor" \
        "$MODPATH/classes.dex" \
        "$MODPATH/libTEESimulator.so" \
        "$MODPATH/libcertgen.so"
}

_asset_stamp() {
    if command -v sha256sum >/dev/null 2>&1; then
        _engine_sources | while IFS= read -r _f; do cat "$_f" 2>/dev/null; done | sha256sum | cut -c1-16
    elif command -v md5sum >/dev/null 2>&1; then
        _engine_sources | while IFS= read -r _f; do cat "$_f" 2>/dev/null; done | md5sum | cut -c1-16
    else
        printf 'size-%s' "$(_engine_sources | while IFS= read -r _f; do cat "$_f" 2>/dev/null; done | wc -c | tr -d ' ')"
    fi
}

STAMP=$(_asset_stamp)
[ -n "$STAMP" ] || STAMP="ver-$VER"
CUR=$(cat "$RUNTIME/.engine_version" 2>/dev/null)
if [ "$STAMP" != "$CUR" ] || [ ! -f "$RUNTIME/daemon" ] || \
   [ ! -f "$RUNTIME/classes.dex" ] || [ ! -x "$RUNTIME/inject" ]; then
    log_line "INFO engine: staging assets (stamp ${CUR:-none} -> $STAMP, module versionCode $VER)"
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

    # Replace each asset atomically. The content stamp is written last, so a
    # partial update is retried on the next boot instead of being accepted.
    for f in daemon inject supervisor classes.dex libTEESimulator.so libcertgen.so module.prop; do
        mv -f "$STAGE_DIR/$f" "$RUNTIME/$f" || {
            log_line "ERROR failed to commit staged engine asset: $f"
            rm -rf "$STAGE_DIR"
            exit 1
        }
    done
    printf '%s\n' "$STAMP" > "$STAGE_DIR/.engine_version" || {
        rm -rf "$STAGE_DIR"
        exit 1
    }
    mv -f "$STAGE_DIR/.engine_version" "$RUNTIME/.engine_version" || {
        rm -rf "$STAGE_DIR"
        exit 1
    }
    rm -rf "$STAGE_DIR"
    log_line "INFO engine: assets committed (stamp=$STAMP)"
    sync
else
    log_line "INFO engine: assets already current (stamp=$STAMP, module versionCode $VER)"
fi

# 上游兼容目录（`/data/adb/modules/tricky_store`）**不再创建**。
#
# 它以前存在的唯一理由是 DEX 里写死了 `NativeCertGen.initialize("/data/adb/modules/
# tricky_store/libcertgen.so")`；本批已经把这行改成从运行时目录取（
# `third_party/TEESimulator-RS/.../App.kt`），TA_enhanced 判断"TS 是否安装"用的也是
# 它自己的 tmpfs 路径（common/common.sh 里的 `TS=/dev/.xudc_hidden/tricky_store`），
# 不依赖这个目录。
#
# 为什么必须去掉：`/data/adb/modules/tricky_store/` 这个名字是**能读 /data/adb 的
# root 级扫描器**最容易命中的指纹（模块列表里就是一个名叫 tricky_store 的目录），
# 而它对我们没有任何功能作用。
if [ -d /data/adb/modules/tricky_store ]; then
    rm -rf /data/adb/modules/tricky_store 2>/dev/null && \
        log_line "INFO compat: removed the legacy /data/adb/modules/tricky_store directory (upstream path no longer needed)"
fi

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

# ---------------------------------------------------------------------------
# 链末（根）证书核验
# ---------------------------------------------------------------------------
# 与 builtin/TA_enhanced/common/keybox_roots.txt 同一份 Google 证明根指纹清单。
# 为什么这里也要判：检测方（内置验机工具 wu.keyChain.test 的
# identifyRootCertificateType）只看**链末证书的公钥**，不是 Google/AOSP/Knox 已知根
# 就判 UNKNOWN_ROOT（界面"未知根证书 —— 根证书不是已知可信的证书颁发机构"）。
# 而链本身照样签得出来 —— 于是设备上会出现"bootloader 已锁定（因为 RootOfTrust 被
# 伪造了）+ 未知根证书"这种半红半绿的状态。能造成它的箱子有三种，都不归本项目控制：
#   * ta-enhanced `keybox generate`（自签 CN=Android Keybox，DeviceID="sw"）；
#   * TA_enhanced WebUI 的 AOSP 按钮（AOSP 软件认证根，私钥众所周知）；
#   * vendor 自己的第三方源（KOWX712 .extra / MeowDump ShockWave.tar）。
# 这里的职责只有一件：**开机时别让实时副本停在"链末不是 Google 根"的状态**。
# TA_enhanced 的看护循环 15s 内还会再修一次（common/keybox.sh），两条路互为兜底。
_KB_ROOTS="5be402bf9aa353b635a748a9e017de182c02e030a2e274fe3100435ee031c5ff 49f3601ef04c09e4e008db8ca140233820fedec82dba1ea45714f7e54dc3bb54 afce17777720cb8316b8bcc2268dfd299b0a69dbac46e3444734c1929d0bffcf"

# 每个 <Key> 块最后一张证书（链末 = 根）的规范化 sha256：去掉全部空白与 BEGIN/END
# 标记后对 base64 体取 sha256。逐行状态机实现，不用多字符 RS（POSIX awk 只规定 RS
# 是单字符，toybox/busybox awk 上多字符 RS 会把整份输入当成一条记录）。
_kb_root_hashes() {
    awk '
        /^[ \t]*<Key[ \t>]/ { if (have) print cur; cur = ""; have = 0; inbody = 0; next }
        /-----BEGIN CERTIFICATE-----/ { inbody = 1; body = ""; next }
        /-----END CERTIFICATE-----/   { inbody = 0; cur = body; have = 1; body = ""; next }
        inbody {
            line = $0
            gsub(/[^A-Za-z0-9+\/=]/, "", line)
            body = body line
        }
        END { if (have) print cur }
    ' "$1" 2>/dev/null | while IFS= read -r _body; do
        [ -n "$_body" ] || continue
        printf '%s' "$_body" | sha256sum 2>/dev/null | awk '{print $1}'
    done
}

# 允许的 Google 根 → 到期日（YYYYMMDD，UTC）。设备侧不解析证书（Android 上没有 openssl），
# 只做日期字符串比较（字典序即时间序），日期取不到时按"未过期"放行、绝不误杀。
#
# 为什么光比对指纹不够：2026-05 那次 Google **第一代**硬件根到期（2016-05-27 ~ 2026-05-25）
# 就是活教材 —— 链末指纹"确实是 Google 的根"，可证书已经过期，检测方照样报
# 「证书链不受信任 / 信任根异常」。所以在表里的根也得分"有效/过期"两种。
_kb_root_expiry() {
    case "$1" in
        5be402bf9aa353b635a748a9e017de182c02e030a2e274fe3100435ee031c5ff) printf '20341118' ;;
        49f3601ef04c09e4e008db8ca140233820fedec82dba1ea45714f7e54dc3bb54) printf '20420315' ;;
        afce17777720cb8316b8bcc2268dfd299b0a69dbac46e3444734c1929d0bffcf) printf '20350715' ;;
        *) printf '' ;;
    esac
}

# 一份 keybox 的链末状态（唯一实现，_kb_root_ok / _kb_write_root_status / 自检都用它）：
#   google  = 每条链末都是表里的 Google 根，且都还没到期
#   expired = 链末确实是 Google 根，但**已经过期**（检测方会报证书链不受信任）
#   other   = 结构能用但链末不是表里的根（自签根/软件根/第三方箱子）
#   unknown = 没法判定（缺 sha256sum）
#   missing = 文件不存在或为空
_kb_root_state() {
    local _f="$1" _h _n=0 _exp _expired=0 _today
    [ -s "$_f" ] || { printf 'missing'; return 0; }
    command -v sha256sum >/dev/null 2>&1 || { printf 'unknown'; return 0; }
    _today=$(date -u +%Y%m%d 2>/dev/null)
    for _h in $(_kb_root_hashes "$_f"); do
        _n=$((_n + 1))
        case " $_KB_ROOTS " in
            *" $_h "*) ;;
            *) printf 'other'; return 0 ;;
        esac
        _exp=$(_kb_root_expiry "$_h")
        if [ -n "$_exp" ] && [ -n "$_today" ] && [ "$_today" \> "$_exp" ]; then
            _expired=1
        fi
    done
    [ "$_n" -gt 0 ] || { printf 'other'; return 0; }
    [ "$_expired" = "1" ] && { printf 'expired'; return 0; }
    printf 'google'
}

# 0 = 每条带证书的链，链末都在已知 Google 根表里**且未过期**
# 1 = 结构可用但链末不是已知 Google 根（自签根/软件根/第三方箱子）或已过期
# 2 = 无法判定（缺 sha256sum / 文件不可读）—— 此时**不动**实时副本
_kb_root_ok() {
    local _state
    _state=$(_kb_root_state "$1")
    case "$_state" in
        google) return 0 ;;
        unknown) return 2 ;;
        missing) return 2 ;;
        *) return 1 ;;
    esac
}

# 结论写给管理器（TeeStatus「根证书」一行直接 cat 它，避免在 root shell 里重算哈希）。
_kb_write_root_status() {
    local _hit=0 _total=0 _h _state
    _state=$(_kb_root_state "$_KB_LIVE")
    if [ "$_state" = "missing" ] || [ "$_state" = "unknown" ]; then
        printf '%s 0/0 %s\n' "$_state" "$(_kb_device_id "$_KB_LIVE")" \
            > "$RUNTIME/.keybox_root" 2>/dev/null
        chmod 644 "$RUNTIME/.keybox_root" 2>/dev/null
        return 0
    fi
    for _h in $(_kb_root_hashes "$_KB_LIVE"); do
        _total=$((_total + 1))
        case " $_KB_ROOTS " in
            *" $_h "*) _hit=$((_hit + 1)) ;;
        esac
    done
    printf '%s %s/%s %s\n' "$_state" "$_hit" "$_total" "$(_kb_device_id "$_KB_LIVE")" \
        > "$RUNTIME/.keybox_root" 2>/dev/null
    chmod 644 "$RUNTIME/.keybox_root" 2>/dev/null
    return 0
}

_KB_LIVE="$RUNTIME/keybox.xml"
_KB_SHIPPED="$MODPATH/keybox.xml"
_KB_STAMP="$RUNTIME/.keybox_shipped"
_kb_live_sha=$(_kb_sha "$_KB_LIVE")
_kb_ship_sha=$(_kb_sha "$_KB_SHIPPED")
_kb_prev_ship=$(cat "$_KB_STAMP" 2>/dev/null)
_kb_live_root=2
[ -f "$_KB_LIVE" ] && { _kb_root_ok "$_KB_LIVE"; _kb_live_root=$?; }
_kb_ship_root=2
_kb_root_ok "$_KB_SHIPPED"; _kb_ship_root=$?

if [ ! -f "$_KB_LIVE" ]; then
    if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
        log_line "INFO keybox: seeded from bundled copy (DeviceID=$(_kb_device_id "$_KB_LIVE") sha=$(_kb_sha "$_KB_LIVE"))"
        _kb_live_sha=$(_kb_sha "$_KB_LIVE")
    else
        log_line "ERROR keybox: failed to seed from bundled copy"
    fi
elif [ "$_kb_live_root" = "1" ] && [ "$_kb_ship_root" = "0" ]; then
    # 实时副本链末不是已知 Google 根，而本模块内置的那份是：直接换掉。
    # 不看"实时副本是不是拉取来的/代次高不高"——vendor 的第三方源可以把 DeviceID
    # 里的代次随便写高，而链末的根是骗不了检测方的。
    _kb_old_id=$(_kb_device_id "$_KB_LIVE")
    if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
        log_line "WARN keybox: live copy chain root is not a known Google root (was DeviceID=${_kb_old_id:-?}) — re-seeded from the bundled copy (DeviceID=$(_kb_device_id "$_KB_LIVE") sha=$(_kb_sha "$_KB_LIVE"))"
        _kb_live_sha=$(_kb_sha "$_KB_LIVE")
    else
        log_line "ERROR keybox: failed to replace the non-Google-rooted live copy"
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

_kb_write_root_status

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
# 桌面应用自动入表（只增不删）与证书参数自洽化，同样做成纯函数以便离线验证。
. "$MODPATH/target_autofill.sh"
. "$MODPATH/consistency.sh"

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

# 桌面应用自动入表：把桌面上有图标的应用补进白名单（只增不删、原子写）。
# 两份副本都补：管理器/WebUI 读的是 $DATA，引擎读的是 $RUNTIME，任何一边落后都会
# 让"用户明明装了应用却拿到真实 TEE 证明"。
_af_conf="$RUNTIME/target_autofill.conf"
_af_added=$(target_autofill "$RUNTIME/target.txt" "$_af_conf" log_line)
[ -n "$_af_added" ] && log_line "INFO target.txt: 桌面应用自动入表补入 $_af_added 个（引擎副本）"
_af_added=$(target_autofill "$DATA/target.txt" "$_af_conf" log_line)
[ -n "$_af_added" ] && log_line "INFO target.txt: 桌面应用自动入表补入 $_af_added 个（管理器副本）"

# 证书参数自洽化：补丁标签与 Boot Hash 必须与设备上 getprop 读到的值一致，否则
# 验机工具会把"证书链由模块生成"直接报出来（社区口径见 consistency.sh 顶部）。
run_consistency_audit "$RUNTIME" "$DATA" log_line

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
    pid=$(pidof keymint_svc 2>/dev/null)
    if [ -n "$pid" ]; then
        log_line "INFO engine: TEESimulator alive (pid=$pid)"
    else
        log_line "WARN engine: TEESimulator NOT running — keystore is not intercepted, attestation stays the real TEE one (untrusted key + unlocked bootloader)"
    fi
    dump=$(/system/bin/logcat -d -s keymint_svc:* 2>/dev/null \
        | grep -E "Finished parsing|Key store file not found|Fatal error parsing|Injection process failed|Interceptors initialized|Backdoor not found|Successfully rebuilt|Served RootOfTrust|Failed to patch|No keybox found|Rewrote the software-enforced" \
        | tail -n 8)
    if [ -n "$dump" ]; then
        printf 'INFO engine logcat:\n%s\n' "$dump" >> "$LOG_FILE" 2>/dev/null
    else
        log_line "WARN engine: no TEESimulator logcat lines yet — engine has not handled an attestation request (or never started)"
    fi
    log_target_coverage
}

# ---------------------------------------------------------------------------
# 开机自检 + 自愈（管理器「TEE 引擎与拦截」直接读这份结论）
# ---------------------------------------------------------------------------
# 存在的理由（2026-10-15 用户反馈那批验机截图）：用户能看到的只有验机工具的红字，
# 而从"引擎没起来 / 箱子链末过期 / 名单没覆盖 / 锁态没伪装"到那句红字之间，**没有任何
# 界面给出因果**。日志里其实全都有，但没人会去读 logcat —— 于是同一台设备四张截图四种
# 状态，连用户自己都说不清哪张是"根本没拦到"。
#
# 这里每轮做三件事：读事实 → 算结论 → **能修的自己修**（每类每次开机限额，避免刷日志），
# 结论写进 $DATA/tee-selfcheck.txt（KEY=VALUE，管理器一行 sed 就能读）。
# 写在 $DATA 而不是 $RUNTIME：引擎目录会被 vendor 的更新流程整目录重写。
SELFCHECK_FILE="$DATA/tee-selfcheck.txt"
REPAIR_COUNT_FILE="$DATA/.tee-repair-count"

# 每类修复的配额：$1=类别 $2=上限。0 = 还能修（已计数），1 = 配额用完。
_bump_repair() {
    local _k="$1" _cap="$2" _n=0
    [ -f "$REPAIR_COUNT_FILE" ] && \
        _n=$(sed -n "s/^$_k=//p" "$REPAIR_COUNT_FILE" 2>/dev/null | head -n 1)
    case "$_n" in ''|*[!0-9]*) _n=0 ;; esac
    [ "$_n" -ge "$_cap" ] && return 1
    _n=$((_n + 1))
    {
        grep -v "^$_k=" "$REPAIR_COUNT_FILE" 2>/dev/null
        printf '%s=%s\n' "$_k" "$_n"
    } > "$REPAIR_COUNT_FILE.tmp" 2>/dev/null && \
        mv -f "$REPAIR_COUNT_FILE.tmp" "$REPAIR_COUNT_FILE" 2>/dev/null
    return 0
}

# 锁态属性回写：优先 ksud（内核态路线），退回 Magisk 的 resetprop。
# 逐条只在"当前值不对"时才写，避免每次自检都刷一遍属性区。
_selfcheck_set_prop() {
    local _cur
    _cur=$(getprop "$1" 2>/dev/null)
    [ "$_cur" = "$2" ] && return 0
    local _c
    for _c in /data/adb/ksu/bin/ksud /data/adb/xudc resetprop; do
        if [ -x "$_c" ] || command -v "$_c" >/dev/null 2>&1; then
            "$_c" resetprop -n "$1" "$2" >/dev/null 2>&1 && return 0
        fi
    done
    return 1
}

tee_selfcheck() {
    local _pid _files _kb_state _target _checker _vvb _lc _served _perr
    local _vbs _locked _dev _verity _verdict _repair=""

    _pid=$(pidof keymint_svc 2>/dev/null)
    _files=missing
    if [ -f "$RUNTIME/daemon" ] && [ -f "$RUNTIME/classes.dex" ] && \
       [ -f "$RUNTIME/libTEESimulator.so" ] && [ -x "$RUNTIME/inject" ]; then
        _files=ok
    fi
    _kb_state=$(_kb_root_state "$_KB_LIVE")
    _target=$(grep -cvE '^[[:space:]]*(#|$)' "$RUNTIME/target.txt" 2>/dev/null)
    case "$_target" in ''|*[!0-9]*) _target=0 ;; esac
    # 覆盖检查容忍 `包名!` / `包名?` 与行尾空白（引擎把模式后缀当同一目标）。
    _checker=$(grep -qE '^[[:space:]]*wu\.keyChain\.test[!?]?[[:space:]]*$' \
        "$RUNTIME/target.txt" 2>/dev/null && echo yes || echo no)
    _vvb=$(grep -qE '^[[:space:]]*io\.github\.vvb2060\.keyattestation[!?]?[[:space:]]*$' \
        "$RUNTIME/target.txt" 2>/dev/null && echo yes || echo no)
    # 一次 logcat，两处复用（两次 -d 在低端机上要几百毫秒）。
    _lc=$(/system/bin/logcat -d -s keymint_svc:* 2>/dev/null | tail -n 800)
    _served=$(printf '%s\n' "$_lc" | grep -c 'Served RootOfTrust')
    _perr=$(printf '%s\n' "$_lc" | grep -c -E 'Failed to patch|No keybox found|Failed to modify hardware')
    _vbs=$(getprop ro.boot.verifiedbootstate 2>/dev/null)
    _locked=$(getprop ro.boot.flash.locked 2>/dev/null)
    _dev=$(getprop ro.boot.vbmeta.device_state 2>/dev/null)
    _verity=$(getprop ro.boot.veritymode 2>/dev/null)

    # 1) 引擎没跑（资产在位却起不来 = 注入失败/被杀），自己拉起来。
    if [ -z "$_pid" ] && [ "$_files" = "ok" ]; then
        if _bump_repair engine 3; then
            log_line "WARN selfcheck: 引擎未运行 —— 自动重启 supervisor"
            ( cd "$RUNTIME" && ./supervisor ./daemon "$RUNTIME" >> "$LOG_FILE" 2>&1 & )
            _repair="${_repair}engine,"
            sleep 10
            _pid=$(pidof keymint_svc 2>/dev/null)
            [ -n "$_pid" ] && log_line "INFO selfcheck: 引擎已恢复（pid=$_pid）" || \
                log_line "ERROR selfcheck: 重启后仍未看到 TEESimulator（注入失败）"
        fi
    fi

    # 2) 箱子链末不是有效 Google 根（含"是 Google 根但已过期"）：换回内置的那份。
    #    只有在**内置副本本身合格**时才换，否则等于拿一个坏箱子换另一个坏箱子。
    if [ "$_kb_state" != "google" ] && _kb_root_ok "$_KB_SHIPPED"; then
        if _bump_repair keybox 2; then
            if stage_file "$_KB_SHIPPED" "$_KB_LIVE" 644; then
                _kb_write_root_status
                _kb_state=$(_kb_root_state "$_KB_LIVE")
                log_line "WARN selfcheck: keybox 链末状态不是有效 Google 根 —— 已换回内置副本（现在 $_kb_state）"
                _repair="${_repair}keybox,"
            else
                log_line "ERROR selfcheck: 换回内置 keybox 失败"
            fi
        fi
    fi

    # 3) 验机工具不在白名单（引擎是穷举白名单，不在表里的包原样拿真实证明）。
    if [ "$_checker" != "yes" ] || [ "$_vvb" != "yes" ]; then
        if _bump_repair coverage 3; then
            merge_baseline "$RUNTIME/target.txt" "$MODPATH/target.baseline.txt"
            sync_conf target.txt
            log_line "WARN selfcheck: 验机工具未覆盖（checker=$_checker vvb=$_vvb）—— 已合并 baseline 并对账"
            _repair="${_repair}coverage,"
            _checker=yes
            _vvb=yes
        fi
    fi

    # 4) 锁态属性漂移（别的模块/系统把它改回去了）。
    if [ "$_vbs" != "green" ] || [ "$_locked" != "1" ] || \
       [ "$_dev" != "locked" ] || [ "$_verity" != "enforcing" ]; then
        if _bump_repair props 6; then
            _selfcheck_set_prop ro.boot.verifiedbootstate green
            _selfcheck_set_prop ro.boot.flash.locked 1
            _selfcheck_set_prop ro.boot.vbmeta.device_state locked
            _selfcheck_set_prop ro.boot.veritymode enforcing
            log_line "WARN selfcheck: 锁态属性漂移（vbs=$_vbs locked=$_locked dev=$_dev verity=$_verity）—— 已回写"
            _repair="${_repair}props,"
            _vbs=$(getprop ro.boot.verifiedbootstate 2>/dev/null)
            _locked=$(getprop ro.boot.flash.locked 2>/dev/null)
            _dev=$(getprop ro.boot.vbmeta.device_state 2>/dev/null)
            _verity=$(getprop ro.boot.veritymode 2>/dev/null)
        fi
    fi

    # 结论文案给管理器用；这里只算"最坏的那一环"，顺序就是用户该关心的顺序。
    if [ -z "$_pid" ]; then
        _verdict=engine_down
    elif [ "$_kb_state" != "google" ]; then
        _verdict="keybox_${_kb_state}"
    elif [ "$_checker" != "yes" ] || [ "$_vvb" != "yes" ]; then
        _verdict=coverage_missing
    elif [ "$_vbs" != "green" ] || [ "$_locked" != "1" ]; then
        _verdict=locked_props_missing
    else
        _verdict=ok
    fi

    {
        printf 'at=%s\n' "$(date +%s 2>/dev/null || echo 0)"
        printf 'engine=%s\n' "${_pid:-none}"
        printf 'enginefiles=%s\n' "$_files"
        printf 'kbroot=%s\n' "$_kb_state"
        printf 'target=%s\n' "$_target"
        printf 'checker=%s\n' "$_checker"
        printf 'vvb=%s\n' "$_vvb"
        printf 'served=%s\n' "$_served"
        printf 'patcherr=%s\n' "$_perr"
        printf 'vbs=%s\n' "$_vbs"
        printf 'locked=%s\n' "$_locked"
        printf 'devstate=%s\n' "$_dev"
        printf 'verity=%s\n' "$_verity"
        printf 'verdict=%s\n' "$_verdict"
        printf 'repair=%s\n' "${_repair:-none}"
    } > "$SELFCHECK_FILE.tmp" 2>/dev/null && \
        mv -f "$SELFCHECK_FILE.tmp" "$SELFCHECK_FILE" 2>/dev/null
    chmod 644 "$SELFCHECK_FILE" 2>/dev/null

    # 只在结论变化时写日志：15s/2min 一轮的循环里每次都写会把日志冲爆。
    if [ "$_verdict" != "$(cat "$DATA/.tee-selfcheck-last" 2>/dev/null)" ]; then
        printf '%s\n' "$_verdict" > "$DATA/.tee-selfcheck-last" 2>/dev/null
        log_line "INFO selfcheck: verdict=$_verdict engine=${_pid:-none} kbroot=$_kb_state target=$_target checker=$_checker vvb=$_vvb served=$_served patcherr=$_perr vbs=$_vbs locked=$_locked repair=${_repair:-none}"
    fi
}

( sleep 45; capture_engine_state ) >/dev/null 2>&1 &
# 75s（引擎状态回读之后）先跑一轮自检：开机那一下的失败要在用户打开验机工具**之前**修掉。
( sleep 75; tee_selfcheck ) >/dev/null 2>&1 &

# 引擎看护：45s 那次回读只回答"开机时起没起来"。注入链一旦在运行期断掉（进程被杀、
# 崩溃、被别的模块顶掉），keystore 就重新变回"没被接管"，检测方又拿到真实 TEE 证明；
# 而本机原厂证书链的根是 Google 的，于是表现成**"密钥没问题、引导加载程序却是解锁的"**
# ——最容易被误判成"keybox 好了但锁态没伪装"，实际上整条引擎都不在了，且没有任何界面提示。
# 这里每 5 分钟确认一次，必要时重启 supervisor，连续失败则退避到每小时一次，
# 全程写进 .engine.log（管理器的 TEE 卡片读的就是这份日志）。
engine_watchdog() {
    local _pid _now _attempts=0 _last_try=0
    while :; do
        sleep 300
        _pid=$(pidof keymint_svc 2>/dev/null)
        if [ -n "$_pid" ]; then
            _attempts=0
            # 引擎活着也要每轮自检一次：箱子过期、名单落后、属性被改回去都发生在这里。
            tee_selfcheck
            continue
        fi
        _now=$(date +%s 2>/dev/null || echo 0)
        # 连续 3 次重启失败后：一小时才再试一次，避免刷爆日志。
        if [ "$_attempts" -ge 3 ] && [ "$_last_try" != "0" ] && \
           [ $(( _now - _last_try )) -lt 3600 ]; then
            continue
        fi
        _attempts=$((_attempts + 1))
        _last_try=$_now
        log_line "ERROR engine: TEESimulator 未运行 —— keystore 未被接管，检测方会看到真实 TEE 证明（密钥可信但引导加载程序解锁）；第 $_attempts 次尝试重启 supervisor"
        log_target_coverage
        ( cd "$RUNTIME" && ./supervisor ./daemon "$RUNTIME" >> "$LOG_FILE" 2>&1 & )
        sleep 30
        if [ -n "$(pidof keymint_svc 2>/dev/null)" ]; then
            log_line "INFO engine: supervisor 重启成功，引擎已恢复接管"
            _attempts=0
        else
            log_line "ERROR engine: 重启后仍未看到 TEESimulator 进程（注入失败）"
        fi
    done
}
( sleep 90; engine_watchdog ) >/dev/null 2>&1 &

# WebUI / 管理器改 target.txt 或 security_patch.txt 后不必重启：每 15s 对账一次，
# 有差异时用 tmp + mv 写入，引擎的 ConfigObserver 收到 MOVED_TO 会自动重载。
# 同一循环里做两件慢事（节流）：每 60s 复核一次补丁标签/Boot Hash 的自洽性（几次
# getprop + od，代价可忽略）；每 5min 重扫一次桌面应用（cmd package 约百毫秒级）。
( _tick=0
  while :; do
    sleep 15
    sync_conf target.txt
    sync_conf security_patch.txt
    _tick=$((_tick + 1))
    if [ $((_tick % 4)) -eq 0 ]; then
        run_consistency_audit "$RUNTIME" "$DATA" log_line
    fi
    # 每 2 分钟一轮自检（含自愈）：引擎被杀、箱子被换、属性被改回去都在这个粒度内发现。
    if [ $((_tick % 8)) -eq 0 ]; then
        tee_selfcheck
    fi
    # 管理器「一键自检并修复」按的是一个触发文件：写它比让管理器自己重算一遍
    # （root shell 里拼 awk/sha256sum，出错是静默的）可靠得多，也保证"设备侧只有
    # 一份自检实现"。
    if [ -f "$DATA/.tee-selfcheck-now" ]; then
        rm -f "$DATA/.tee-selfcheck-now" 2>/dev/null
        tee_selfcheck
    fi
    if [ $((_tick % 20)) -eq 0 ]; then
        _af_added=$(target_autofill "$RUNTIME/target.txt" "$_af_conf" log_line)
        [ -n "$_af_added" ] && log_line "INFO target.txt: 桌面应用自动入表补入 $_af_added 个（引擎副本）"
        _af_added=$(target_autofill "$DATA/target.txt" "$_af_conf" log_line)
        [ -n "$_af_added" ] && log_line "INFO target.txt: 桌面应用自动入表补入 $_af_added 个（管理器副本）"
    fi
  done ) >/dev/null 2>&1 &
