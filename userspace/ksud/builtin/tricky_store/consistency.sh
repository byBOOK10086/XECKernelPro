#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 新增；改动清单与日期见同目录
# NOTICE.md「本地修改」。上游：Enginex0/TEESimulator-RS（GPL-3.0）。
#
# 证书参数自洽化：让「证书里报的值」和「设备上能被 getprop 读到的值」一致。
#
# 为什么需要它：社区验机工具判定「检测到 TrickyStore 或类似模块」的公开口径是
# 「证书链为模块生成（合成链），与真机 TEE 链特征不符」，而其中最常被抓住的两条是：
#   1. 证书里的安全补丁标签与本机系统属性不一致
#      —— 引擎会读 /data/adb/tricky_store/security_patch.txt；一旦这份文件里写的是
#         别的机型/别的公告日期（WebUI 的 tricky-addon 会写 Pixel 公告日期），证书就会
#         报一个本机没有的补丁日期。社区文档给出的处置办法正是「把这个文件删掉」。
#   2. Boot Hash 不匹配
#      —— 检测方拿 `getprop ro.boot.vbmeta.digest` 和证书里的 VerifiedBootHash 对；
#         两者不一致就直接判模块生成。引擎把值持久化在 boot_hash.bin / boot_key.bin，
#         开机脚本负责回写属性，但引擎可能在属性回写之前就用了随机值，所以要复核。
#
# 本文件只做「体检 + 纠正」，不做任何破坏性动作：动过的文件一律先备份成
# *.bak.<时间戳>；纯函数、可离线单测（见 _scratch/test_consistency.sh）。

# 内置验机工具的包名（管理器自带的「密钥认证」）。可在 source 前覆盖。
CS_CHECKER_PKG="${CS_CHECKER_PKG:-wu.keyChain.test}"

# 把文件读成小写十六进制字符串（失败输出空）。
#
# 必须带 `od -v`：GNU / toybox / busybox 的 od 默认会把**重复行折叠成一行 `*`**
# （duplicate line suppression）。32 字节全同的 boot_hash.bin 读出来就是
# "abab…ab*"，长度对不上、正则不匹配，于是"属性回写"整条链路静默失效。
# 这里仍然给 hexdump / xxd 留兜底，避免某些精简环境的 od 不认识 -v。
cz_file_hex() {
    _f="$1"
    [ -f "$_f" ] || return 0
    _h=$(od -v -A n -t x1 "$_f" 2>/dev/null | tr -d ' \n' | tr 'A-F' 'a-f')
    case "$_h" in
        *'*'*) _h="" ;;
    esac
    if [ -z "$_h" ] && command -v hexdump >/dev/null 2>&1; then
        _h=$(hexdump -v -e '1/1 "%02x"' "$_f" 2>/dev/null | tr 'A-F' 'a-f')
    fi
    if [ -z "$_h" ] && command -v xxd >/dev/null 2>&1; then
        _h=$(xxd -p -c 4096 "$_f" 2>/dev/null | tr -d ' \n' | tr 'A-F' 'a-f')
    fi
    printf '%s' "$_h"
}

# 归一到 YYYY-MM：接受 2025-11-05 / 2025-11 / 20251105 / 202511。
# 只比到「月」是有意的：社区实测厂商补丁日期与公告日期在“日”上本来就常有差异，
# 而检测方的比对是月份级的。
cs_ym() {
    printf '%s' "$1" | tr -d ' \t' | sed -n \
        -e 's/^\([0-9]\{4\}\)-\([0-9]\{2\}\)-\?[0-9]*$/\1-\2/p' \
        -e 's/^\([0-9]\{4\}\)\([0-9]\{2\}\)\([0-9]\{2\}\)\?$/\1-\2/p'
}

# 某个组件对应的系统属性名。
cs_component_prop() {
    case "$1" in
        system) printf '%s\n' "ro.build.version.security_patch" ;;
        vendor) printf '%s\n' "ro.vendor.build.security_patch" ;;
        boot) printf '%s\n' "ro.bootimage.build.version.security_patch" ;;
        *) printf '%s\n' "" ;;
    esac
}

# security_patch_audit <file> [log_fn]
# 返回 0=无需改动 / 1=已纠正 / 2=文件缺失
#
# 只看**全局段**（第一个 [ctx] 之前）。逐条对：
#   值=prop（或空）     -> 引擎从真实 TEE / 属性推导，天然自洽，放行
#   值=no               -> 引擎会「不输出该标签」；真机 TEE 一定输出，属可疑，只告警
#   值=显式日期         -> 与该组件属性做月份级比较，不一致即纠正成 system=prop
security_patch_audit() {
    _f="$1"
    _log="${2:-:}"
    [ -f "$_f" ] || return 2

    _bad=""
    _warn=""
    while IFS= read -r _line; do
        case "$_line" in
            \[*\]*) break ;;                 # 进入包级上下文，全局段结束
            ''|'#'*) continue ;;
        esac
        _k=$(printf '%s' "$_line" | cut -d= -f1 | tr -d ' \t' | tr 'A-Z' 'a-z')
        _v=$(printf '%s' "$_line" | cut -d= -f2- | tr -d ' \t')
        case "$_k" in
            system|vendor|boot|all) ;;
            *) continue ;;
        esac
        case "$_v" in
            ''|prop|PROP|Prop) continue ;;
            no|NO|No)
                _warn="${_warn}${_k}=no "
                continue
                ;;
        esac
        _want=$(cs_ym "$_v")
        if [ -z "$_want" ]; then
            _bad="${_bad}${_k}=${_v}(无法解析) "
            continue
        fi
        if [ "$_k" = "all" ]; then
            _comps="system vendor boot"
        else
            _comps="$_k"
        fi
        for _c in $_comps; do
            _p=$(cs_component_prop "$_c")
            [ -n "$_p" ] || continue
            _real=$(cs_ym "$(getprop "$_p" 2>/dev/null)")
            [ -n "$_real" ] || continue
            [ "$_real" = "$_want" ] || _bad="${_bad}${_c}:文件=${_want}/本机=${_real} "
        done
    done < "$_f"

    if [ -n "$_bad" ]; then
        _bak="${_f}.bak.$(date +%Y%m%d%H%M%S 2>/dev/null || echo 0)"
        cp -f "$_f" "$_bak" 2>/dev/null
        printf 'system=prop\n' > "$_f" 2>/dev/null
        chmod 644 "$_f" 2>/dev/null
        "$_log" "WARN security_patch.txt: 证书补丁标签与本机不一致（${_bad}）——已备份到 $(basename "$_bak") 并改回 system=prop（引擎改为读真实 TEE/属性）。这一条正是验机工具报「TrickyStore 或类似模块」的常见来源。"
        return 1
    fi
    [ -n "$_warn" ] && "$_log" "INFO security_patch.txt: 检测到 ${_warn}——该标签不会被输出，而真机 TEE 一定会输出，部分验机工具会因此告警。"
    return 0
}

# boot_hash_audit <runtime_dir> [log_fn]
# 复核 boot_hash.bin / boot_key.bin 与属性是否一致（检测方是拿 getprop 对证书的）。
boot_hash_audit() {
    _dir="$1"
    _log="${2:-:}"
    [ -d "$_dir" ] || return 0

    _audit_one() {
        _bin="$1"
        _prop="$2"
        _label="$3"
        if [ ! -f "$_bin" ]; then
            "$_log" "WARN $_label: $(basename "$_bin") 缺失——引擎会用随机值填进证书，验机工具会报「Boot Hash 不匹配」。先在验机工具里跑一次生成的密钥，引擎落盘后本项会自动恢复。"
            return 0
        fi
        _val=$(cz_file_hex "$_bin")
        if ! printf '%s' "$_val" | grep -qE '^[0-9a-f]{64}$'; then
            "$_log" "WARN $_label: $(basename "$_bin") 内容不是 32 字节十六进制（读到 ${#_val} 字符），已跳过"
            return 0
        fi
        _cur=$(getprop "$_prop" 2>/dev/null | tr -d ' \t' | tr 'A-F' 'a-f')
        [ "$_cur" = "$_val" ] && return 0
        if command -v resetprop >/dev/null 2>&1; then
            if resetprop -n "$_prop" "$_val" 2>/dev/null; then
                "$_log" "INFO $_label: 属性 $_prop 已按证书值回写（原值=${_cur:-空}），检测方 getprop 与证书现在一致"
            else
                "$_log" "ERROR $_label: 回写属性 $_prop 失败"
            fi
        else
            "$_log" "ERROR $_label: 找不到 resetprop，无法把 $_prop 对齐到证书值"
        fi
        return 0
    }

    _audit_one "$_dir/boot_hash.bin" "ro.boot.vbmeta.digest" "boot-hash"
    _audit_one "$_dir/boot_key.bin" "ro.boot.vbmeta.public_key_digest" "boot-key"
    return 0
}

# --- 引擎日志快照 --------------------------------------------------------
# 引擎自己会把两条关键判定写到 logcat（tag=TEESimulator，info 级，release 版也有）：
#   1. 「TEE functionality check successful / failed」
#      → target.txt 里不带后缀的条目走 AUTO，AUTO 的解析结果就是：
#        可用 → PATCH（真机 TEE 签发、只改内容）；不可用 → GENERATE（整条链模块合成）
#   2. 「Attestation patch levels for uid=N: os=.., vendor=.., boot=..」
#      → 该 uid 的证书里到底报了哪几个补丁标签
# 这两条决定了验机工具在「证书内容」这一层能抓到什么，所以固定抓进模块日志，
# 出问题时截图即可，不需要在设备上敲命令。

# 取引擎日志（拿不到就返回 1，调用方静默跳过）。
es_engine_lines() {
    _n="${1:-400}"
    command -v logcat >/dev/null 2>&1 || return 1
    _out=$(logcat -d -v brief -t "$_n" -s TEESimulator:V 2>/dev/null) || _out=""
    if [ -z "$_out" ]; then
        _out=$(logcat -d -v brief -t "$_n" 2>/dev/null | grep -F 'TEESimulator')
    fi
    [ -n "$_out" ] || return 1
    printf '%s\n' "$_out"
}

# 取某个包名的 uid（拿不到输出空）。
es_uid_of() {
    command -v pm >/dev/null 2>&1 || return 0
    pm list packages -U "$1" 2>/dev/null | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -n 1
}

# engine_log_snapshot <runtime_dir> [log_fn]
# 归纳上面两条判定；内容没变就不重复写（digest 存 runtime 目录）。
# 验机工具包名取 CS_CHECKER_PKG。
engine_log_snapshot() {
    _dir="$1"
    _log="${2:-:}"
    _pkg="$CS_CHECKER_PKG"
    [ -d "$_dir" ] || return 0

    _lines=$(es_engine_lines 400) || return 0

    _tee=$(printf '%s\n' "$_lines" | grep -F 'TEE functionality check' | tail -n 1)
    case "$_tee" in
        *successful*) _state="TEE可用→AUTO解析为PATCH（真机TEE签发，仅改写内容层）" ;;
        *failed*)     _state="TEE不可用→AUTO解析为GENERATE（链由模块+内置keybox合成）" ;;
        *)            _state="未见TEE判定（引擎还没被请求过出证）" ;;
    esac

    _uid=$(es_uid_of "$_pkg")
    _patch=""
    if [ -n "$_uid" ]; then
        _patch=$(printf '%s\n' "$_lines" | grep -F "Attestation patch levels for uid=$_uid" | tail -n 1)
    fi

    _sum="$_state|$_uid|$_patch"
    _digest=$(printf '%s' "$_sum" | cksum 2>/dev/null | tr -d ' \t')
    [ -n "$_digest" ] || _digest=$(printf '%s' "$_sum" | wc -c | tr -d ' \t')
    _stamp="$_dir/.engine_snapshot.digest"
    if [ -f "$_stamp" ] && [ "$(cat "$_stamp" 2>/dev/null)" = "$_digest" ]; then
        return 0
    fi
    printf '%s' "$_digest" > "$_stamp" 2>/dev/null

    "$_log" "INFO 引擎状态：$_state"
    if [ -n "$_patch" ]; then
        "$_log" "INFO 验机工具(${_pkg}${_uid:+ uid=$_uid})的证书补丁标签：$(printf '%s' "$_patch" | sed 's/.*Attestation patch levels for uid=[0-9]*: //')"
    elif [ -n "$_uid" ]; then
        "$_log" "INFO 验机工具(${_pkg} uid=$_uid)：引擎日志里还没有它的出证记录——先在验机工具里跑一次完整检测，本行会自动出现。"
    fi
    case "$_state" in
        *GENERATE*)
            "$_log" "WARN 该状态下证书链是模块合成的，社区验机工具报「检测到 TrickyStore 或类似模块」基本不可避免。可选对策：把拦截表里的 ${_pkg} 改成 ${_pkg}? 强制 PATCH（需 TEE 可用）、或整行删掉不再拦截（验机工具改按真机状态报告）。"
            ;;
    esac
    return 0
}

# run_consistency_audit <runtime_dir> <data_dir> <log_fn>
# 两份 security_patch.txt 一起纠正：只改一份会被 sync_conf 用另一份覆盖回去。
run_consistency_audit() {
    _r="$1"
    _d="$2"
    _log="${3:-:}"
    security_patch_audit "$_r/security_patch.txt" "$_log"
    if [ -f "$_d/security_patch.txt" ] && [ "$_d/security_patch.txt" != "$_r/security_patch.txt" ]; then
        security_patch_audit "$_d/security_patch.txt" "$_log"
    fi
    boot_hash_audit "$_r" "$_log"
    engine_log_snapshot "$_r" "$_log"
    return 0
}
