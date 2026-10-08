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

# 覆盖取证：条目数 + 内置验机工具是否在表内。这两项是"引擎到底会不会拦"的直接答案。
log_target_coverage() {
    local covered=no
    grep -qxF -e 'wu.keyChain.test' "$RUNTIME/target.txt" 2>/dev/null && covered=yes
    log_line "INFO target.txt: $(_target_count) entr(y/ies), bundled checker covered=$covered"
}
