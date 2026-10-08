#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 新增；改动清单与日期见同目录
# NOTICE.md「本地修改」。上游：Enginex0/TEESimulator-RS（GPL-3.0）。
#
# 桌面应用自动入表：把「桌面上有图标的应用」的包名自动写进 target.txt。
#
# 为什么需要它：引擎的拦截是**穷举白名单**（ConfigurationManager.shouldSkipUid），不在
# target.txt 里的 UID 会被完全跳过、原样拿到真实 TEE 证明——也就是"没列进来"= "完全没
# 生效"。手工维护这份名单不现实：用户装了新应用、换了桌面、装了新的验机/支付/银行类
# 应用，都得自己去 WebUI 里补一行。这里在开机与常驻循环里自动补齐。
#
# 设计约束（和 engineconf.sh 的 merge_baseline 保持一致）：
#   * 只增不删：用户自己的条目、`!` / `?` 模式后缀、`[xxx.xml]` 分组头一律不动；
#   * 不重复：已经有了（含带后缀的写法）就不再加；
#   * 原子写：tmp + mv，引擎的 ConfigObserver 只对 MOVED_TO / CLOSE_WRITE 可靠，
#     原地写会丢重载；
#   * 可离线单测：取包名的命令走 XEC_LAUNCHER_CMD 覆盖，函数只依赖传入的路径。
#
# 配置（$RUNTIME/target_autofill.conf，缺省即默认值）：
#   enabled=1            关闭写 0（也可直接创建 $RUNTIME/.no_target_autofill）
#   mode=launcher        launcher=桌面有图标的应用（含系统应用）
#                        thirdparty=仅第三方应用（pm list packages -3）
#                        launcher3=桌面有图标的第三方应用
#   suffix=              追加到每个条目的模式后缀，可为空 / ! / ?
#
# 语义速查（引擎解析规则，见 target.baseline.txt）：
#   包名=自动（真实 TEE 可用就补丁，不可用就按 keybox 生成）；包名!=强制生成；包名?=只补丁

# --- 取包名 ---------------------------------------------------------------

# 输出 "包名/Activity" 形式的原始行（cmd / dumpsys 两种来源都兼容）。
_af_launcher_raw() {
    if [ -n "$XEC_LAUNCHER_CMD" ]; then
        eval "$XEC_LAUNCHER_CMD" 2>/dev/null
        return 0
    fi
    if command -v cmd >/dev/null 2>&1; then
        cmd package query-activities --brief \
            -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER 2>/dev/null && return 0
    fi
    # 老设备 / cmd 不可用时的兜底：从 dumpsys 里挑 launcher activity 行（较慢）。
    dumpsys package 2>/dev/null | grep -E '^[[:space:]]+[0-9a-f]+ [A-Za-z0-9_.]+/' 2>/dev/null
}

# 规整成纯包名：取每行的所有 "a.b.c/..." token，砍掉 "/" 之后的部分。
_af_normalize() {
    tr ' ' '\n' 2>/dev/null | grep -E '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+/' | cut -d/ -f1
}

# 第三方应用包名（pm 兜底路径）。XEC_THIRDPARTY_CMD 的语义与 `pm list packages -3`
# 完全一致（可带 "package:" 前缀），便于离线单测。
_af_thirdparty() {
    if [ -n "$XEC_THIRDPARTY_CMD" ]; then
        eval "$XEC_THIRDPARTY_CMD" 2>/dev/null | sed 's/^package://'
        return 0
    fi
    pm list packages -3 2>/dev/null | sed 's/^package://'
}

# 最终写入用的包名清单（已排序去重）。
af_package_list() {
    _af_mode="$1"
    case "$_af_mode" in
        thirdparty)
            _af_thirdparty
            ;;
        launcher3)
            # 桌面应用 ∩ 第三方：桌面清单里筛出 pm 认为第三方的那些。
            _af_tmp3=$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/af.$$")
            _af_thirdparty > "$_af_tmp3" 2>/dev/null
            _af_launcher_raw | _af_normalize | while IFS= read -r _p; do
                grep -qxF "$_p" "$_af_tmp3" 2>/dev/null && printf '%s\n' "$_p"
            done
            rm -f "$_af_tmp3"
            ;;
        *)
            _af_launcher_raw | _af_normalize
            ;;
    esac | grep -E '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$' | sort -u
}

# --- 配置 -----------------------------------------------------------------

# 读配置到 AF_* 变量；缺省 enabled=1 / mode=launcher / suffix 空。
af_load_config() {
    AF_ENABLED=1
    AF_MODE=launcher
    AF_SUFFIX=""
    _af_conf="$1"
    [ -f "$_af_conf" ] || return 0
    while IFS= read -r _line; do
        case "$_line" in ''|'#'*) continue ;; esac
        _k=$(printf '%s' "$_line" | cut -d= -f1 | tr -d ' \t')
        _v=$(printf '%s' "$_line" | cut -d= -f2- | tr -d ' \t')
        case "$_k" in
            enabled) AF_ENABLED="$_v" ;;
            mode) AF_MODE="$_v" ;;
            suffix) AF_SUFFIX="$_v" ;;
        esac
    done < "$_af_conf"
    return 0
}

# --- 写表 -----------------------------------------------------------------

# 条目是否已在表内（容忍模式后缀与行尾空白）。
af_has_entry() {
    grep -qE "^[[:space:]]*$1[!?]?[[:space:]]*$" "$2" 2>/dev/null
}

# target_autofill <target.txt> <conf> [log_fn]
# 返回 0；新增条数打印到 stdout（供调用方记录）。
target_autofill() {
    _t="$1"
    _conf="$2"
    _log="${3:-:}"
    [ -n "$_t" ] || return 0
    [ -f "$_t" ] || return 0
    if [ -f "$(dirname "$_t")/.no_target_autofill" ]; then
        return 0
    fi
    af_load_config "$_conf"
    [ "$AF_ENABLED" = "1" ] || return 0

    _tmp="${_t}.af.$$"
    _added=0
    rm -f "$_tmp"
    af_package_list "$AF_MODE" | while IFS= read -r _pkg; do
        [ -n "$_pkg" ] || continue
        af_has_entry "$_pkg" "$_t" && continue
        printf '%s%s\n' "$_pkg" "$AF_SUFFIX" >> "$_tmp"
    done
    if [ -s "$_tmp" ]; then
        _added=$(wc -l < "$_tmp" 2>/dev/null | tr -d ' ')
        {
            printf '\n# --- XEC launcher autofill (auto-added) ---\n'
            printf '[keybox.xml]\n'
            cat "$_tmp"
        } >> "$_t" 2>/dev/null
        chmod 644 "$_t" 2>/dev/null
    fi
    rm -f "$_tmp"
    [ "${_added:-0}" -gt 0 ] 2>/dev/null && printf '%s\n' "$_added"
    return 0
}
