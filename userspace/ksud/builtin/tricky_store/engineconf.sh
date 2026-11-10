#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 新增；改动清单与日期见同目录
# NOTICE.md「本地修改」。上游：Enginex0/TEESimulator-RS（GPL-3.0）。
#
# target.txt 覆盖合并 + 配置与"管理器副本"对账，由 service.sh 在启动引擎前 source。
#
# 为什么单独成文件：这段状态机决定"引擎到底拦不拦某个包"，是本次"刷入后依然
# 报密钥不可信 / bootloader 解锁"的直接修复点，必须在设备之外也能验证。抽成纯
# 函数（只依赖 $RUNTIME / $DATA / $MODPATH / log_line 四个外部量）后，可以在任意
# 机器上用 _scratch/test_target_sync.sh 跑全部分支。
#
# 背景（详见 target.baseline.txt 顶部）：引擎的白名单是**穷举**的，不在
# /data/adb/tricky_store/target.txt 里的 UID 会被完全跳过、原样拿到真实 TEE 证明；
# 而管理器 / WebUI 写的却是另一个路径（$DATA）。两边从未对账，于是"刷了新版本、
# 验机工具依旧报未知认证根证书 / 无效的信任根状态"。

# 短 sha（16 位十六进制）；没有 sha256sum 时退化为长度指纹。
_conf_sha() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | cut -c1-16
    else
        printf 'size-%s' "$(wc -c < "$1" 2>/dev/null | tr -d ' ')"
    fi
}

# 只增不删地把 baseline 里的包追加进列表；已存在（含带 !/? 后缀的写法）不重复追加。
# 追加前显式写 [keybox.xml] 分组头：这些条目要用默认箱子，而不是用户最后一个
# [xxx.xml] 分组里的那份。
merge_baseline() {
    local file="$1" baseline="$2" pending="${2}.pending.$$" added=0 line
    [ -f "$file" ] || return 0
    [ -f "$baseline" ] || return 0
    rm -f "$pending"
    while IFS= read -r line; do
        case "$line" in ''|'#'*) continue ;; esac
        if grep -qxF -e "$line" "$file" 2>/dev/null; then continue; fi
        if grep -qxF -e "${line}!" "$file" 2>/dev/null; then continue; fi
        if grep -qxF -e "${line}?" "$file" 2>/dev/null; then continue; fi
        printf '%s\n' "$line" >> "$pending"
        added=$((added + 1))
    done < "$baseline"
    if [ "$added" -gt 0 ]; then
        {
            printf '\n# --- XEC baseline coverage (auto-added) ---\n'
            printf '[keybox.xml]\n'
            cat "$pending"
        } >> "$file" 2>/dev/null
        chmod 644 "$file" 2>/dev/null
        log_line "INFO target.txt: baseline added $added entr(y/ies)"
    fi
    rm -f "$pending"
    return 0
}

# 双向对账。stamp 记录"上一次我们推给引擎的内容"，用来区分"用户改了"与"引擎侧
# 被别人改了"这两种同名不同源的情况。写入一律走 tmp + mv：引擎的 ConfigObserver
# 只对 CLOSE_WRITE / MOVED_TO 可靠（原地写常丢 IN_CLOSE_WRITE），mv 一定触发重载。
sync_conf() {
    local name="$1" live="$RUNTIME/$1" data="$DATA/$1" stamp="$DATA/.engine_${1}.sha"
    local live_sha data_sha stamp_sha tmp
    [ -e "$live" ] || [ -e "$data" ] || return 0
    live_sha=$([ -f "$live" ] && _conf_sha "$live")
    data_sha=$([ -f "$data" ] && _conf_sha "$data")
    stamp_sha=$(cat "$stamp" 2>/dev/null)

    if [ -z "$live_sha" ] && [ -n "$data_sha" ]; then
        if cp -f "$data" "$live" 2>/dev/null; then
            chmod 644 "$live" 2>/dev/null
            printf '%s\n' "$data_sha" > "$stamp" 2>/dev/null
            log_line "INFO $name: engine copy was missing, seeded from manager copy"
        fi
        return 0
    fi
    if [ -n "$live_sha" ] && [ -z "$data_sha" ]; then
        if cp -f "$live" "$data" 2>/dev/null; then
            chmod 644 "$data" 2>/dev/null
            printf '%s\n' "$live_sha" > "$stamp" 2>/dev/null
            log_line "INFO $name: manager copy was missing, seeded from engine copy"
        fi
        return 0
    fi
    if [ "$live_sha" = "$data_sha" ]; then
        printf '%s\n' "$data_sha" > "$stamp" 2>/dev/null
        return 0
    fi

    if [ "$live_sha" = "$stamp_sha" ]; then
        # 引擎侧没动过 -> 是用户在管理器/WebUI 改了，推给引擎立即生效。
        tmp="${live}.tmp.$$"
        if cp -f "$data" "$tmp" 2>/dev/null && chmod 644 "$tmp" 2>/dev/null && mv -f "$tmp" "$live" 2>/dev/null; then
            printf '%s\n' "$data_sha" > "$stamp" 2>/dev/null
            log_line "INFO $name: pushed manager copy to engine (user edit applied live)"
        else
            rm -f "$tmp"
            log_line "ERROR $name: failed to push manager copy to engine"
        fi
    elif [ "$data_sha" = "$stamp_sha" ]; then
        # 用户在管理器侧没动过 -> 是引擎侧被（本模块/守护）改了，回读给管理器。
        if cp -f "$live" "$data" 2>/dev/null; then
            chmod 644 "$data" 2>/dev/null
            printf '%s\n' "$live_sha" > "$stamp" 2>/dev/null
            log_line "INFO $name: mirrored engine copy back to manager"
        fi
    else
        # 两边都动过：以管理器侧（用户意图）为准，并留下 WARN 供排查。
        tmp="${live}.tmp.$$"
        if cp -f "$data" "$tmp" 2>/dev/null && chmod 644 "$tmp" 2>/dev/null && mv -f "$tmp" "$live" 2>/dev/null; then
            printf '%s\n' "$data_sha" > "$stamp" 2>/dev/null
            log_line "WARN $name: both copies changed; manager copy wins and was pushed to the engine"
        else
            rm -f "$tmp"
            log_line "ERROR $name: both copies changed and the push failed"
        fi
    fi

    # target.txt 特例：任何一次"管理器侧 -> 引擎侧"的推送之后，重新保证基线覆盖。
    # WebUI 保存时会整份重写 target.txt（它并不知道本模块的基线），推完不管的话，
    # 用户在 WebUI 里改一次拦截列表就会把内置验机工具等条目挤掉——那正是本次要修
    # 的故障形态。这里多跑一次 merge，代价是下一轮对账会把带基线的版本回读给管理器
    # （两轮内收敛，且 WebUI 里也能看到这些条目，属于期望行为）。
    if [ "$name" = "target.txt" ] && [ -f "$MODPATH/target.baseline.txt" ]; then
        merge_baseline "$live" "$MODPATH/target.baseline.txt"
    fi
    return 0
}

# 有效条目数（去掉注释与空行）。
_target_count() {
    grep -cvE '^[[:space:]]*(#|$)' "$RUNTIME/target.txt" 2>/dev/null
}

# 覆盖取证：条目数 + 常见验机工具是否在表内。这两项是"引擎到底会不会拦"的直接答案。
# 匹配要容忍模式后缀（`包名!` / `包名?`）与行尾空白：名单里写着 `wu.keyChain.test!`
# 时，逐字比较会误报"未覆盖"，而它其实已经被拦住了。
_target_covers() {
    grep -qE "^[[:space:]]*$1[!?]?[[:space:]]*$" "$RUNTIME/target.txt" 2>/dev/null
}

log_target_coverage() {
    local covered=no vvb=no route=unknown
    _target_covers 'wu\.keyChain\.test' && covered=yes
    _target_covers 'io\.github\.vvb2060\.keyattestation' && vvb=yes
    [ -f "$RUNTIME/.detector_route" ] && route=$(cat "$RUNTIME/.detector_route" 2>/dev/null)
    log_line "INFO target.txt: $(_target_count) entr(y/ies), bundled checker covered=$covered, vvb2060 keyattestation covered=$vvb, verifier route=${route:-unknown}"
}

# ---------------------------------------------------------------------------
# 验机工具路由（2026-11-10）
#
# 背景：本轮把 OhMyKeymint 1.2.6 的源码读完之后，可以确定它让春秋/鸭子闭嘴的机制
# 不是"更强的伪造"，而是把验机工具的 keystore 调用整体改投到一套能自证的实现上；
# 它自己的 FAQ 也写明——keybox 过期/被吊销时，工具看到的是"证书已过期"，这比让
# 工具看到"bootloader 已解锁"更糟。
#
# 旧策略（2026-11-08）是一刀切把检测工具从注入名单里摘掉，理由是真机实测：链不可信
# 时注入会递出"被篡改的 attestation key + 未知根证书"。那份证据是对的，但结论过宽：
# 真正决定成败的是**链能不能自证**。所以这里改成按 keybox 健康度决定：
#   * keybox 通过完整校验（链签名、全链有效期、私钥匹配、吊销列表）
#   * 根是 Google / Google-RKP（硬件形态；AOSP 软件根意味着 Software 安全级，
#     与引擎声称的 TrustedEnvironment 自相矛盾，本身就是检测点）
#   * 叶子剩余 >= 7 天，给上游轮换和自动拉取留出窗口
# 满足时条目带 `!` 写入（强制 GENERATE，才会用 keybox 现签一条 Google 根的链）；
# 不满足时全部剥掉，让它们走真实 TEE。手动关闭：
#   touch /data/adb/.xudc_secure/verifier_forge_off
DETECTOR_PKGS='com.eltavine.duckdetector
com.chunqiunativecheck
io.github.vvb2060.keyattestation
io.github.vvb2060.mahoshojo
io.github.qwq233.keyattestation
com.reveny.nativecheck
com.zhenxi.hunter
io.liankong.riskdetector
luna.safe.luna
icu.nullptr.nativetest
com.android.nativetest'

_detector_re() {
    printf '%s\n' $DETECTOR_PKGS | tr '\n' '|' | sed -e 's/|$//' -e 's/\./\\./g'
}

# 打印 `ok days_left root_type`；找不到 TA 引擎就当"没有健康度信息"。
# 用 TA_ENHANCED_BIN 可覆盖（离线单测 / 非标准安装路径都用它）。
_keybox_health_line() {
    local _bin
    for _bin in "${TA_ENHANCED_BIN:-}" \
        /data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced \
        /data/adb/ksu/builtin/TA_enhanced/bin/ta-enhanced \
        /data/adb/modules/TA_enhanced/bin/ta-enhanced; do
        [ -n "$_bin" ] || continue
        [ -x "$_bin" ] || continue
        "$_bin" keybox health 2>/dev/null | head -n 1
        return 0
    done
    return 1
}

_route_decision() {
    local _line _ok _days _root
    if ! _line=$(_keybox_health_line) || [ -z "$_line" ]; then
        printf 'off no-engine\n'
        return 0
    fi
    set -- $_line
    _ok=${1:-0}
    _days=${2:--1}
    _root=${3:-unknown}
    if [ "$_ok" != "1" ]; then
        printf 'off keybox-invalid\n'
        return 0
    fi
    case "$_root" in
        google|google_rkp) : ;;
        *)
            printf 'off root=%s\n' "$_root"
            return 0
            ;;
    esac
    case "$_days" in
        ''|*[!0-9-]*)
            printf 'off days=unknown\n'
            return 0
            ;;
    esac
    if [ "$_days" -lt 7 ]; then
        printf 'off days=%s\n' "$_days"
        return 0
    fi
    printf 'on days=%s root=%s\n' "$_days" "$_root"
}

apply_detector_routing() {
    local _want _reason _tgt _tmp _pkg _prev _re _rc
    if [ -f "$DATA/verifier_forge_off" ]; then
        _want=off
        _reason=opt-out
    else
        set -- $(_route_decision)
        _want=${1:-off}
        shift 2>/dev/null || true
        _reason="${*:-unknown}"
    fi
    _re=$(_detector_re)
    for _tgt in "$RUNTIME/target.txt" "$DATA/target.txt"; do
        [ -f "$_tgt" ] || continue
        _tmp="${_tgt}.route.$$"
        # 先用同一份正则把检测工具的三种写法（裸名=AUTO / `!` / `?`）全部剥掉：
        # 旧版 baseline 用的是裸名，AUTO 在 TEE 可用时等于 PATCH——检测工具拿到的是
        # 厂商根签的真实链，界面照样报"未知认证根证书"。剥完再按需重加，逻辑幂等。
        grep -v -E "^(${_re})([!?])?$" "$_tgt" > "$_tmp" 2>/dev/null
        _rc=$?
        if [ "$_rc" -gt 1 ]; then
            rm -f "$_tmp"
            continue
        fi
        if [ "$_want" = "on" ]; then
            # 必须带 `!`：强制 GENERATE，才会用 keybox 现签一条 Google 根的链；
            # 裸名（AUTO）在 TEE 可用时走 PATCH，链还是厂商根，检测工具不认。
            for _pkg in $DETECTOR_PKGS; do
                printf '%s!\n' "$_pkg" >> "$_tmp"
            done
        fi
        if ! cmp -s "$_tmp" "$_tgt" 2>/dev/null; then
            mv -f "$_tmp" "$_tgt" 2>/dev/null || rm -f "$_tmp"
        else
            rm -f "$_tmp"
        fi
        chmod 644 "$_tgt" 2>/dev/null
    done
    _prev=$(cat "$RUNTIME/.detector_route" 2>/dev/null)
    if [ "$_want $_reason" != "$_prev" ]; then
        printf '%s %s\n' "$_want" "$_reason" > "$RUNTIME/.detector_route" 2>/dev/null
        log_line "INFO target.txt: verifier routing $_want ($_reason)"
    fi
}
