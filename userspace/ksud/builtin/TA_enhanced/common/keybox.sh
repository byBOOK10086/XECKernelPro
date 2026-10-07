#!/system/bin/sh
# 新增文件（GPL-3.0）：本文件由 XECKernel Pro 编写，非上游文件、不含上游代码，随 TA_enhanced
# 模块一起按 GPL-3.0 分发。新增日期 2026-10-08；说明见 ../NOTICE.md「本地修改」。
#
# keybox.sh —— 面向中国大陆的 keybox 自动获取 / 可信核验 / 防退化自愈
# ============================================================================
# 为什么需要这个文件（每一条都是代码级证据，不是猜测）：
#
# 1) **vendor 的"在线判断"在中国大陆永远为假。**
#    third_party/tricky-addon-enhanced/rust/src/platform/network.rs::is_online()
#    用 `ping -c 1 -w 5 api.github.com` 判断网络。api.github.com 在国内被污染/
#    不可达，于是 wait_for_network() 七次退避（1+2+4+8+16+16+16≈63s）后返回 false，
#    daemon 的 KeyboxTask 启动分支直接 boot_done=true 放弃首轮拉取
#    （daemon/tasks.rs:184-193）。本文件自带 kb_online()，探测 223.5.5.5（阿里
#    DNS，国内可 ping）+ 默认路由兜底，**绝不复用 api.github.com**。
#
# 2) **vendor 的四路源在国内基本全灭。**
#    rust/src/keybox/sources.rs 里 yurikey / upstream 都是
#    raw.githubusercontent.com；integritybox 主源同域（镜像才是 raw.gitmirror.com）。
#    ta-enhanced 的 custom 源又只是"一个 URL"，没有镜像轮换。设备端因此**永远
#    拿不到新箱子**：模块自带的出厂副本会一直用到吊销为止——检测方看到的就是
#    "密钥传不过来 / bootloader 还是解锁状态"。本文件按"国内优先"的顺序扫镜像，
#    并把最近一次成功的镜像记下来，下轮从它开始。
#
# 3) **vendor 每次成功写入都会把实时 keybox 落成 0600 root，而读它的进程是
#    uid=keystore**（keybox/mod.rs::install_data 的 set_permissions(0o600)；
#    KeyBoxManager.parseKeyStoreFile 跑在 keystore2 进程内）。本文件每 tick 把
#    权限钉回 644——窗口从 5s 缩到 1s。
#
# 4) **需要"可信"而不只是"能解析"。** vendor 的 keybox validate 只查标签存在性
#    （rust/src/keybox/validate.rs 的 CHECKS 全是 contains）。本文件另外做
#    **证书链指纹核验**：把每个 PEM 证书体规范化后取 sha256，与
#    common/keybox_trust.txt 里登记的 Google Hardware Attestation 根/中间证书
#    指纹比对，链里包含这些证书才算"可信"（级别 0）。
#    （shell 层不做密码学验证；"私钥↔链首证书公钥一致"这一条已对出厂盒子单独
#    复核，脚本与结论见交接文档。）
#
# 5) **防止被写坏/被降级。** vendor 的四路源里 integritybox 走第三方镜像
#    （raw.gitmirror.com），内容不归本项目控制；一旦它写进来一个结构不合法、
#    或级别更低的箱子，本文件在 15s 内用"上次可信副本"恢复，并把过程写进日志。
#
# 使用方式（由 service.sh 调用）：
#   . "$MODPATH/common/keybox.sh"
#   kb_prepare                     # 建状态目录、缓存指纹表
#   kb_ensure_from_module          # 开机即时可用：模块内置副本比实时副本新则换掉
#   kb_guardian_loop &             # 常驻看护（权限/可信度/刷新）
# ============================================================================

# 路径全部支持环境覆盖（默认即设备上的真实路径）：唯一目的是让这些逻辑可以在
# 开发机上用一份假目录完整跑一遍（见交接文档里的离线自测脚本），设备端不受影响。
KB_DIR=${KB_DIR:-/data/adb/tricky_store}
KB_LIVE=${KB_LIVE:-$KB_DIR/keybox.xml}
KB_COMPAT_DIR=${KB_COMPAT_DIR:-${TS_DIR:-/data/adb/.xudc_secure}}
KB_COMPAT=${KB_COMPAT:-$KB_COMPAT_DIR/keybox.xml}
KB_STATE=${KB_STATE:-$KB_COMPAT_DIR/keybox}
KB_GOOD=${KB_GOOD:-$KB_STATE/good.xml}       # 最后一次"可信级（0 级）"副本
KB_LAST=${KB_LAST:-$KB_STATE/last.xml}       # 最后一次"结构合法（1 级及以上）"副本
KB_TRUST_CUR=${KB_TRUST_CUR:-$KB_STATE/trust.cur}     # 指纹表（去注释后的纯 hash 行）
KB_MIRRORS_CUR=${KB_MIRRORS_CUR:-$KB_STATE/mirrors.cur}
KB_WORKING=${KB_WORKING:-$KB_STATE/working_mirror}
KB_SHIPPED=${KB_SHIPPED:-$KB_STATE/shipped.sha}
KB_COMPAT_SYNCED=${KB_COMPAT_SYNCED:-$KB_STATE/compat.synced}
KB_NEXT_AT=${KB_NEXT_AT:-$KB_STATE/next_refresh_at}

KB_TRUST_FILE=${KB_TRUST_FILE:-${MODDIR:-$MODPATH}/common/keybox_trust.txt}
KB_MIRRORS_FILE=${KB_MIRRORS_FILE:-${MODDIR:-$MODPATH}/common/keybox_mirrors.txt}
KB_MIRRORS_USER=${KB_MIRRORS_USER:-$KB_STATE/mirrors.txt}

# 可调参数（都可在环境里覆盖，方便用户按自己网络调）
KB_URL_TIMEOUT=${KB_URL_TIMEOUT:-20}        # 单个镜像下载上限（秒）
KB_VENDOR_TIMEOUT=${KB_VENDOR_TIMEOUT:-60}  # 交给 vendor CLI 拉取时的上限（秒）
KB_SWEEP_BUDGET=${KB_SWEEP_BUDGET:-240}     # 一轮镜像扫描总预算（秒）
KB_VENDOR_MAX=${KB_VENDOR_MAX:-3}           # 一轮里最多交给 vendor CLI 试几个 URL
KB_REFRESH_INTERVAL=${KB_REFRESH_INTERVAL:-21600}  # 正常情况下的刷新间隔（6h）
KB_RETRY_OFFLINE=${KB_RETRY_OFFLINE:-60}    # 离线时的重试间隔（秒）
KB_MAX_AGE=${KB_MAX_AGE:-93600}             # 超过 26h 未更新则强制刷新
KB_TICK=${KB_TICK:-1}                       # 权限看护周期（秒）
KB_CHECK_EVERY=${KB_CHECK_EVERY:-15}        # 可信度/刷新评估周期（tick 数）

kb_now() { date +%s 2>/dev/null || echo 0; }

# 内容指纹（用于"变没变"的比较）。缺 hash 工具时退回长度指纹：仍能察觉变化，
# 只是无法察觉等长替换，此时日志里会带 size- 前缀，一眼能看出是降级模式。
kb_sha() {
    [ -f "$1" ] || { printf ''; return 1; }
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | awk '{print $1}'
    elif command -v md5sum >/dev/null 2>&1; then
        md5sum "$1" 2>/dev/null | awk '{print $1}'
    else
        printf 'size-%s' "$(wc -c < "$1" 2>/dev/null | tr -d ' ')"
    fi
}

kb_short() { printf '%.16s' "$1"; }

# yurikey 的 DeviceID 里带着代次（当前实测形如 "Yurikey58. Valid keybox. ..."）。
# 这是**唯一**能从盒子本身判断新旧的信号：代次更高才算更新，避免被镜像里的旧
# 副本倒灌（那正是"密钥一直传不过来"的另一种形态）。
kb_generation() {
    sed -n 's/.*Yurikey\([0-9][0-9]*\).*/\1/p' "$1" 2>/dev/null | head -n 1
}

kb_device_id() {
    sed -n 's/.*DeviceID="\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -n 1
}

# 把每个 PEM 证书体规范化（去掉所有空白）后逐个算 sha256。
# 只取 <Certificate> 段：私钥段的标记是 "-----BEGIN PRIVATE KEY-----"，去掉空白后
# 成 "-----BEGINPRIVATEKEY-----"，与证书标记不同，awk 不会误取。
kb_cert_hashes() {
    command -v sha256sum >/dev/null 2>&1 || return 1
    tr -d '[:space:]' < "$1" 2>/dev/null | awk -v RS='-----ENDCERTIFICATE-----' \
        -v B='-----BEGINCERTIFICATE-----' '
        {
            i = index($0, B)
            if (i > 0) {
                body = substr($0, i + length(B))
                gsub(/[^A-Za-z0-9+\/=]/, "", body)
                if (length(body) > 0) print body
            }
        }' | while IFS= read -r _body; do
        printf '%s' "$_body" | sha256sum 2>/dev/null | awk '{print $1}'
    done
}

# 可信级别：0=链里含 Google Hardware Attestation 根/中间证书（指纹命中），
#           1=结构合法但未命中指纹，2=无效。
# 说明（别把这一层当成密码学验证）：shell 层只做"这张证书在不在链里"的指纹比对
# 与结构核验；链上的签名关系由引擎/检测方运行时验证。出厂那份 yurikey 盒子已经
# 用私钥↔证书公钥一致性单独复核过（EC 与 RSA 两把私钥的公钥部分都等于链首
# 证书的 SPKI，见交接文档里的复核脚本与结论），所以"指纹命中 + 私钥一致性"这两件
# 事合起来才构成"可信密钥"。
kb_trust_level() {
    local _f="$1" _hashes
    [ -s "$_f" ] || { printf '2'; return; }
    # 超过 1MB 的一律当无效：正常 keybox 只有几 KB，防止把某个大文件/归档写进实时路径
    [ "$(wc -c < "$_f" 2>/dev/null | tr -d ' ')" -le 1048576 ] 2>/dev/null || { printf '2'; return; }
    grep -q '<AndroidAttestation' "$_f" 2>/dev/null || { printf '2'; return; }
    grep -q '<Keybox' "$_f" 2>/dev/null || { printf '2'; return; }
    grep -q 'BEGIN CERTIFICATE' "$_f" 2>/dev/null || { printf '2'; return; }
    grep -q 'BEGIN.*PRIVATE KEY' "$_f" 2>/dev/null || { printf '2'; return; }
    # 至少要有一条可用链；yurikey 正常形态是 ecdsa+rsa 两把钥匙、每把 3 张证书。
    [ "$(grep -c 'BEGIN CERTIFICATE' "$_f" 2>/dev/null)" -ge 2 ] || { printf '2'; return; }
    [ -s "$KB_TRUST_CUR" ] || { printf '1'; return; }
    _hashes=$(kb_cert_hashes "$_f")
    [ -n "$_hashes" ] || { printf '1'; return; }
    if printf '%s\n' "$_hashes" | grep -qFxf "$KB_TRUST_CUR" 2>/dev/null; then
        printf '0'
    else
        printf '1'
    fi
}

kb_fix_perms() {
    if [ -d "$KB_DIR" ]; then
        local _dm
        _dm=$(stat -c %a "$KB_DIR" 2>/dev/null)
        case "$_dm" in
            7[0-9][0-9]|75[0-9]) ;;
            *) chmod 755 "$KB_DIR" 2>/dev/null ;;
        esac
    fi
    [ -f "$KB_LIVE" ] || return 0
    local _m
    _m=$(stat -c %a "$KB_LIVE" 2>/dev/null)
    if [ "$_m" != "644" ]; then
        if chmod 644 "$KB_LIVE" 2>/dev/null; then
            _log "INFO" "keybox 权限 $_m -> 644（读它的 KeyBoxManager 在 keystore2 进程里，uid=keystore 打不开 0600）"
        else
            _log "WARN" "keybox chmod 644 失败（mode=$_m）；引擎可能读不到这份盒子"
        fi
    fi
}

kb_mirror_compat() {
    [ -f "$KB_LIVE" ] || return 0
    mkdir -p "$KB_COMPAT_DIR" 2>/dev/null
    local _tmp="$KB_COMPAT.mirror.$$"
    if cp -f "$KB_LIVE" "$_tmp" 2>/dev/null && chmod 644 "$_tmp" 2>/dev/null && \
       sync && mv -f "$_tmp" "$KB_COMPAT" 2>/dev/null; then
        kb_sha "$KB_LIVE" > "$KB_COMPAT_SYNCED" 2>/dev/null
        return 0
    fi
    rm -f "$_tmp" 2>/dev/null
    _log "WARN" "keybox 兼容副本写入失败：$KB_COMPAT"
    return 1
}

# 实时副本 <-> 兼容副本 的双向对账。
# 方向为什么不能永远单向：TA 的 WebUI「自定义 keybox」把内容写到
# $TS_DIR/keybox.xml（兼容副本），而引擎只读 /data/adb/tricky_store/keybox.xml。
# 纯单向镜像会在 1s 内把用户刚粘贴的盒子覆盖掉。这里的规则是：
#   - 兼容副本与实时一致            -> 记录同步点，什么都不做
#   - 兼容副本 != 实时 且 == 上次同步点 -> 兼容副本过期，实时 -> 兼容
#   - 兼容副本 != 实时 且 != 上次同步点 -> 有人（用户/WebUI）改过它：结构合法就
#                                        装进实时路径（让用户的选择真正生效）
kb_sync_compat() {
    [ -f "$KB_COMPAT" ] || { kb_mirror_compat; return 0; }
    local _l _c _s
    _l=$(kb_sha "$KB_LIVE")
    _c=$(kb_sha "$KB_COMPAT")
    _s=$(cat "$KB_COMPAT_SYNCED" 2>/dev/null)
    if [ -n "$_l" ] && [ "$_l" = "$_c" ]; then
        [ "$_s" = "$_l" ] || kb_sha "$KB_LIVE" > "$KB_COMPAT_SYNCED" 2>/dev/null
        return 0
    fi
    if [ "$_c" = "$_s" ]; then
        kb_mirror_compat
        return 0
    fi
    local _lvl
    _lvl=$(kb_trust_level "$KB_COMPAT")
    if [ "$_lvl" = "2" ]; then
        _log "WARN" "兼容副本 $KB_COMPAT 内容不合法且与实时不一致——忽略它，用实时副本覆盖"
        kb_mirror_compat
        return 0
    fi
    _log "INFO" "兼容副本被外部修改（WebUI/用户）且结构合法：按用户意图装入实时路径（级别=$_lvl）"
    if ! kb_try_candidate "$KB_COMPAT" "compat-adopt" "$KB_COMPAT" force; then
        _log "WARN" "兼容副本被拒绝（可能本身不合法），用实时副本覆盖它"
        kb_mirror_compat
    fi
}

kb_remember_working() {
    mkdir -p "$KB_STATE" 2>/dev/null
    printf '%s\n' "$1" > "$KB_WORKING" 2>/dev/null
}

# 安装候选：原子替换 + 644 + 备份 + 维护可信/最后可用副本 + 同步兼容副本。
kb_install() {
    local _src="$1" _label="$2" _tmp _lvl
    [ -s "$_src" ] || return 1
    mkdir -p "$KB_DIR" "$KB_STATE" 2>/dev/null
    _tmp="$KB_DIR/.keybox.tmp.$$"
    rm -f "$_tmp"
    cp -f "$_src" "$_tmp" 2>/dev/null || { rm -f "$_tmp"; return 1; }
    chmod 644 "$_tmp" 2>/dev/null
    chown 0:0 "$_tmp" 2>/dev/null
    sync
    [ -f "$KB_LIVE" ] && cp -f "$KB_LIVE" "$KB_LIVE.bak" 2>/dev/null
    mv -f "$_tmp" "$KB_LIVE" 2>/dev/null || { rm -f "$_tmp"; return 1; }
    chmod 644 "$KB_LIVE" 2>/dev/null
    _lvl=$(kb_trust_level "$KB_LIVE")
    cp -f "$KB_LIVE" "$KB_LAST" 2>/dev/null
    chmod 644 "$KB_LAST" 2>/dev/null
    if [ "$_lvl" = "0" ]; then
        cp -f "$KB_LIVE" "$KB_GOOD" 2>/dev/null
        chmod 644 "$KB_GOOD" 2>/dev/null
    fi
    kb_mirror_compat
    _log "INFO" "keybox 已安装：来源=$_label 级别=$_lvl DeviceID=$(kb_device_id "$KB_LIVE") sha=$(kb_short "$(kb_sha "$KB_LIVE")") bytes=$(wc -c < "$KB_LIVE" 2>/dev/null | tr -d ' ') key数=$(grep -c '<Key algorithm=' "$KB_LIVE" 2>/dev/null)"
    return 0
}

# 候选替换策略（防止降级/无意义抖动）：
#   - 级别 2（结构不合法）一律丢弃
#   - 与实时内容相同            -> 视为成功（内容本来就对）
#   - 实时无效（级别 2/缺失）    -> 接受
#   - 候选级别比实时差           -> 拒绝
#   - 级别相同                   -> 只有代次（YurikeyNN）更高才替换
# 第 4 个参数为 force 时跳过"级别/代次"这两道闸：只用于**用户显式动作**
# （WebUI 里粘贴自定义 keybox），此时用户意图优先，但仍要求结构合法。
kb_try_candidate() {
    local _src="$1" _label="$2" _url="${3:-}" _force="${4:-}" _lvl _live_lvl _cand_gen _live_gen _cand_sha _live_sha
    _lvl=$(kb_trust_level "$_src")
    if [ "$_lvl" = "2" ]; then
        _log "WARN" "keybox: $_label 内容无效（结构核验未通过）——丢弃"
        return 1
    fi
    _live_lvl=$(kb_trust_level "$KB_LIVE")
    _cand_sha=$(kb_sha "$_src")
    _live_sha=$(kb_sha "$KB_LIVE")
    if [ -n "$_live_sha" ] && [ "$_cand_sha" = "$_live_sha" ]; then
        _log "INFO" "keybox: $_label 与实时副本一致（sha=$(kb_short "$_cand_sha")），无需替换"
        [ -n "$_url" ] && kb_remember_working "$_url"
        return 0
    fi
    if [ "$_force" != "force" ] && [ "$_live_lvl" != "2" ]; then
        if [ "$_lvl" -gt "$_live_lvl" ]; then
            _log "INFO" "keybox: $_label 级别 $_lvl 低于当前 $_live_lvl，拒绝降级"
            return 1
        fi
        if [ "$_lvl" = "$_live_lvl" ]; then
            _cand_gen=$(kb_generation "$_src")
            _live_gen=$(kb_generation "$KB_LIVE")
            if [ -n "$_cand_gen" ] && [ -n "$_live_gen" ] && [ "$_cand_gen" -gt "$_live_gen" ]; then
                _log "INFO" "keybox: $_label 代次更新 Yurikey$_live_gen -> Yurikey$_cand_gen"
            else
                _log "INFO" "keybox: $_label 与当前同级别且代次不更新（候选=${_cand_gen:-?} 当前=${_live_gen:-?}），跳过"
                return 1
            fi
        fi
    fi
    kb_install "$_src" "$_label"
}

# ---------------------------------------------------------------------------
# 下载：curl -> wget -> busybox/toybox wget -> vendor CLI（自带 ureq/TLS）
# vendor CLI 这一档是**关键兜底**：KernelSU 不随包提供 busybox，很多设备上
# curl/wget 都不存在，而 ta-enhanced 二进制自带 HTTPS 能力。
# ---------------------------------------------------------------------------
kb_http_get() {
    local _u="$1" _o="$2" _bb
    rm -f "$_o"
    if command -v curl >/dev/null 2>&1; then
        timeout "$KB_URL_TIMEOUT" curl -fsSL -o "$_o" "$_u" >/dev/null 2>&1
        [ -s "$_o" ] && return 0
        rm -f "$_o"
    fi
    if command -v wget >/dev/null 2>&1; then
        timeout "$KB_URL_TIMEOUT" wget -q -O "$_o" "$_u" >/dev/null 2>&1
        [ -s "$_o" ] && return 0
        rm -f "$_o"
    fi
    for _bb in busybox toybox; do
        command -v "$_bb" >/dev/null 2>&1 || continue
        timeout "$KB_URL_TIMEOUT" "$_bb" wget -q -O "$_o" "$_u" >/dev/null 2>&1
        [ -s "$_o" ] && return 0
        rm -f "$_o"
    done
    return 1
}

kb_b64_decode() {
    local _i="$1" _o="$2"
    if command -v base64 >/dev/null 2>&1; then
        base64 -d < "$_i" > "$_o" 2>/dev/null && [ -s "$_o" ] && return 0
    fi
    if command -v busybox >/dev/null 2>&1; then
        busybox base64 -d < "$_i" > "$_o" 2>/dev/null && [ -s "$_o" ] && return 0
    fi
    if command -v toybox >/dev/null 2>&1; then
        toybox base64 -d < "$_i" > "$_o" 2>/dev/null && [ -s "$_o" ] && return 0
    fi
    if command -v openssl >/dev/null 2>&1; then
        openssl base64 -d < "$_i" > "$_o" 2>/dev/null && [ -s "$_o" ] && return 0
    fi
    return 1
}

# yurikey 的原始源（.../yurikey/main/key）是 base64 编码的 XML，先解码再用。
kb_maybe_decode() {
    local _f="$1" _t="$1.b64"
    grep -q '<AndroidAttestation' "$_f" 2>/dev/null && return 0
    if kb_b64_decode "$_f" "$_t" && grep -q '<AndroidAttestation' "$_t" 2>/dev/null; then
        mv -f "$_t" "$_f"
        return 0
    fi
    rm -f "$_t" 2>/dev/null
    return 1
}

# 交给 vendor CLI 拉一个 URL。注意：vendor 会先试 custom（我们给的 URL），失败后
# 还会继续试它自己的三路源（国内不通、每个最多 30s），所以这里必须套 timeout。
# 另外 vendor 的 install_data 会把文件落成 0600，kb_check_live 会在 15s 内改回 644。
kb_vendor_fetch() {
    local _u="$1" _old_src _old_url _out _src _rc
    [ -n "$BIN" ] && [ -x "$BIN" ] || return 1
    _old_src=$(read_config keybox.source "")
    _old_url=$(read_config keybox.custom_url "")
    "$BIN" config set keybox.source custom >/dev/null 2>&1 || return 1
    "$BIN" config set keybox.custom_url "$_u" >/dev/null 2>&1 || return 1
    _out=$(timeout "$KB_VENDOR_TIMEOUT" "$BIN" keybox fetch 2>&1)
    _rc=$?
    _src=$(printf '%s\n' "$_out" | sed -n 's/^keybox fetched from //p' | head -n 1)
    [ -n "$_src" ] && _log "INFO" "keybox: vendor CLI 拉取完成 source=$_src rc=$_rc"
    # 还原用户配置：WebUI 里选过的源/URL 属于用户状态，不能被我们改掉。
    if [ "$_old_url" != "$_u" ]; then
        "$BIN" config set keybox.custom_url "$_old_url" >/dev/null 2>&1
    fi
    if [ -n "$_old_src" ] && [ "$_old_src" != "custom" ]; then
        "$BIN" config set keybox.source "$_old_src" >/dev/null 2>&1
    fi
    [ "$_rc" = "0" ]
}

# ---------------------------------------------------------------------------
# 镜像列表：国内优先，最近一次成功的排在最前面
# ---------------------------------------------------------------------------
kb_mirrors() {
    mkdir -p "$KB_STATE" 2>/dev/null
    : > "$KB_MIRRORS_CUR"
    local _w
    _w=$(cat "$KB_WORKING" 2>/dev/null)
    if [ -n "$_w" ]; then
        case "$_w" in
            *://*) printf '%s\n' "$_w" >> "$KB_MIRRORS_CUR" ;;
        esac
    fi
    {
        [ -f "$KB_MIRRORS_FILE" ] && sed -e 's/[[:space:]]*$//' -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$KB_MIRRORS_FILE"
        [ -f "$KB_MIRRORS_USER" ] && sed -e 's/[[:space:]]*$//' -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$KB_MIRRORS_USER"
    } >> "$KB_MIRRORS_CUR" 2>/dev/null
    # 去重（保留首次出现顺序）
    awk '!seen[$0]++' "$KB_MIRRORS_CUR" > "$KB_MIRRORS_CUR.uniq" 2>/dev/null && \
        mv -f "$KB_MIRRORS_CUR.uniq" "$KB_MIRRORS_CUR"
}

kb_online() {
    # 中国大陆可达性优先：223.5.5.5 是阿里公共 DNS，国内直连稳定；1.1.1.1 兜底
    # 海外/VPN 场景。**不用 api.github.com**（见文件头第 1 条）。
    if command -v ping >/dev/null 2>&1; then
        ping -c 1 -W 2 223.5.5.5 >/dev/null 2>&1 && return 0
        ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1 && return 0
    fi
    if command -v ip >/dev/null 2>&1; then
        ip route 2>/dev/null | grep -q '^default' && return 0
    fi
    if [ -r /proc/net/route ]; then
        grep -q '00000000' /proc/net/route 2>/dev/null && return 0
    fi
    return 1
}

kb_need_refresh() {
    [ -f "$KB_LIVE" ] || return 0
    local _lvl _age
    _lvl=$(kb_trust_level "$KB_LIVE")
    [ "$_lvl" = "2" ] && return 0
    # 只有"结构合法但没挂到 Google 根"的盒子、并且手上没有可信副本时，继续找。
    [ "$_lvl" = "1" ] && [ ! -s "$KB_GOOD" ] && return 0
    _age=$(( $(kb_now) - $(stat -c %Y "$KB_LIVE" 2>/dev/null || echo 0) ))
    [ "$_age" -gt "$KB_MAX_AGE" ] && return 0
    return 1
}

# 一轮镜像扫描：国内直连 -> vendor CLI 兜底。命中"可信级"立即收工。
kb_sweep() {
    local _deadline _url _host _out _vendor_try _got_any _pre _pre_lvl _new_lvl _pg _ng
    kb_mirrors
    _deadline=$(( $(kb_now) + KB_SWEEP_BUDGET ))
    _vendor_try=0
    _got_any=0
    _log "INFO" "keybox: 开始镜像扫描（预算 ${KB_SWEEP_BUDGET}s / 单源 ${KB_URL_TIMEOUT}s / 共 $(wc -l < "$KB_MIRRORS_CUR" 2>/dev/null | tr -d ' ') 个源）"
    while IFS= read -r _url; do
        case "$_url" in ''|\#*) continue ;; esac
        [ "$(kb_now)" -ge "$_deadline" ] && { _log "WARN" "keybox: 扫描预算用尽，本轮停止（下轮继续）"; break; }
        _host=$(printf '%s' "$_url" | sed -e 's|^[A-Za-z][A-Za-z0-9+.-]*://||' -e 's|/.*$||')
        _out="$KB_STATE/.dl.$$"
        if kb_http_get "$_url" "$_out"; then
            kb_maybe_decode "$_out"
            if kb_try_candidate "$_out" "mirror:$_host" "$_url"; then
                kb_remember_working "$_url"
                _got_any=1
                if [ "$(kb_trust_level "$KB_LIVE")" = "0" ]; then
                    rm -f "$_out"
                    return 0
                fi
            fi
        else
            _log "WARN" "keybox: 镜像 $_host 下载失败/超时（${KB_URL_TIMEOUT}s）——试下一个"
        fi
        rm -f "$_out" 2>/dev/null
    done < "$KB_MIRRORS_CUR"

    [ "$_got_any" = "1" ] && _log "INFO" "keybox: 直连镜像只拿到非可信级副本，转 vendor CLI 继续找可信链"

    while IFS= read -r _url; do
        case "$_url" in ''|\#*) continue ;; esac
        [ "$_vendor_try" -ge "$KB_VENDOR_MAX" ] && break
        [ "$(kb_now)" -ge "$_deadline" ] && break
        _host=$(printf '%s' "$_url" | sed -e 's|^[A-Za-z][A-Za-z0-9+.-]*://||' -e 's|/.*$||')
        _vendor_try=$((_vendor_try + 1))
        # vendor 的 fetch 会**直接改写实时 keybox**（install_data 写 TARGET_KEYBOX），
        # 而且它在我们的 URL 失败后还会继续试自己的三路源——其中 integritybox 走
        # 第三方镜像。所以先留一份快照：万一它把一份"没挂 Google 根且代次不更新"
        # 的箱子写进来，就把快照装回去，避免被第三方源降级。
        _pre="$KB_STATE/.pre_vendor.$$"
        _pre_lvl="2"
        if [ -f "$KB_LIVE" ]; then
            cp -f "$KB_LIVE" "$_pre" 2>/dev/null
            _pre_lvl=$(kb_trust_level "$_pre")
        fi
        if kb_vendor_fetch "$_url"; then
            kb_maybe_decode "$KB_LIVE"
            _new_lvl=$(kb_trust_level "$KB_LIVE")
            if [ "$_pre_lvl" = "0" ] && [ "$_new_lvl" = "1" ]; then
                _pg=$(kb_generation "$_pre")
                _ng=$(kb_generation "$KB_LIVE")
                if [ -z "$_ng" ] || [ -z "$_pg" ] || [ "$_ng" -le "$_pg" ]; then
                    _log "WARN" "keybox: vendor 拉回的箱子未挂 Google 根且代次不更新（候选=${_ng:-?} 现有=${_pg:-?}）——保留原可信副本"
                    kb_install "$_pre" "keep-trusted"
                    rm -f "$_pre" 2>/dev/null
                    continue
                fi
                _log "INFO" "keybox: vendor 拉回代次更高的箱子（Yurikey$_pg -> Yurikey$_ng），采用"
                kb_install "$KB_LIVE" "vendor:$_host"
                kb_remember_working "$_url"
                rm -f "$_pre" 2>/dev/null
                return 0
            fi
            if kb_try_candidate "$KB_LIVE" "vendor:$_host" "$_url"; then
                kb_remember_working "$_url"
                if [ "$(kb_trust_level "$KB_LIVE")" = "0" ]; then
                    rm -f "$_pre" 2>/dev/null
                    return 0
                fi
            fi
        else
            _log "WARN" "keybox: vendor CLI 在 $_host 上失败/超时（${KB_VENDOR_TIMEOUT}s）"
        fi
        rm -f "$_pre" 2>/dev/null
    done < "$KB_MIRRORS_CUR"
    return 1
}

# 15s 一次的可信度/防退化巡检 + 刷新判定
kb_check_live() {
    local _lvl _cur
    _lvl=$(kb_trust_level "$KB_LIVE")
    if [ "$_lvl" = "2" ]; then
        if [ -s "$KB_GOOD" ]; then
            _log "WARN" "keybox 实时副本无效——用上次可信副本恢复"
            kb_install "$KB_GOOD" "restore-good"
            return 0
        fi
        if [ -s "$KB_LAST" ]; then
            _log "WARN" "keybox 实时副本无效——用上次可用副本恢复（未挂 Google 根）"
            kb_install "$KB_LAST" "restore-last"
            return 0
        fi
        _log "WARN" "keybox 实时副本无效且无可恢复副本——等待下一轮刷新"
        return 1
    fi
    if [ "$_lvl" = "0" ]; then
        _cur=$(kb_sha "$KB_LIVE")
        if [ "$_cur" != "$(kb_sha "$KB_GOOD")" ]; then
            mkdir -p "$KB_STATE" 2>/dev/null
            cp -f "$KB_LIVE" "$KB_GOOD" 2>/dev/null
            chmod 644 "$KB_GOOD" 2>/dev/null
            _log "INFO" "keybox: 记录可信副本 DeviceID=$(kb_device_id "$KB_LIVE") sha=$(kb_short "$_cur")"
        fi
    elif [ -s "$KB_GOOD" ]; then
        # 级别 1（结构合法但没挂 Google 根）而手上还有一份级别 0 的副本：
        # 只在当前副本**无效**时才回退（见上面的分支），否则保留当前这份——中间
        # 证书轮换也会表现为"级别 1"，那种情况下把旧箱子装回去反而是倒退。
        # 这里只留一条去重的提示，避免每 15s 刷屏。
        _cur=$(kb_sha "$KB_LIVE")
        if [ "$_cur" != "$(cat "$KB_STATE/level1.logged" 2>/dev/null)" ]; then
            printf '%s\n' "$_cur" > "$KB_STATE/level1.logged" 2>/dev/null
            _log "WARN" "keybox 当前为级别 1（DeviceID=$(kb_device_id "$KB_LIVE") sha=$(kb_short "$_cur")）：链未命中 Google 根指纹；若同时存在可信副本会在其无效时自动回退"
        fi
    fi
    kb_sync_compat
    return 0
}

# 刷新调度：需要刷新就扫，扫不动（离线）就 60s 后再试。
kb_maybe_refresh() {
    local _now _next
    _now=$(kb_now)
    _next=$(cat "$KB_NEXT_AT" 2>/dev/null)
    case "$_next" in ''|*[!0-9]*) _next=0 ;; esac
    [ "$_now" -lt "$_next" ] && return 0
    if ! kb_need_refresh; then
        printf '%s\n' "$(( _now + KB_REFRESH_INTERVAL ))" > "$KB_NEXT_AT" 2>/dev/null
        return 0
    fi
    if ! kb_online; then
        _log "INFO" "keybox: 需要刷新但当前无网络（阿里 DNS/默认路由都不通），${KB_RETRY_OFFLINE}s 后重试"
        printf '%s\n' "$(( _now + KB_RETRY_OFFLINE ))" > "$KB_NEXT_AT" 2>/dev/null
        return 0
    fi
    _log "WARN" "keybox: 触发刷新（缺失/无效/超期 ${KB_MAX_AGE}s）"
    kb_sweep
    _log "INFO" "keybox: 本轮扫描结束 live级别=$(kb_trust_level "$KB_LIVE") DeviceID=$(kb_device_id "$KB_LIVE") sha=$(kb_short "$(kb_sha "$KB_LIVE")")"
    printf '%s\n' "$(( $(kb_now) + KB_REFRESH_INTERVAL ))" > "$KB_NEXT_AT" 2>/dev/null
}

kb_prepare() {
    mkdir -p "$KB_DIR" "$KB_STATE" 2>/dev/null
    chmod 755 "$KB_DIR" 2>/dev/null
    # 指纹表去注释/去空行后缓存：grep -f 遇到空行会匹配一切，必须清掉。
    if [ -f "$KB_TRUST_FILE" ]; then
        sed -e 's/#.*//' -e 's/[[:space:]]//g' -e '/^$/d' "$KB_TRUST_FILE" > "$KB_TRUST_CUR" 2>/dev/null
    else
        _log "WARN" "keybox 指纹表缺失：$KB_TRUST_FILE（可信级别只能判到 1）"
        : > "$KB_TRUST_CUR"
    fi
}

# 开机即时可用：模块内置副本（构建时由 CI 从上游刷新的那份）比实时副本新时换掉它。
# 这是"刷写即拿到可信密钥"的离线路径——完全不依赖设备端能不能连上 GitHub。
#
# 判据不是时间（tmpfs 里模块文件的 mtime 就是本次开机时间，没有可比性），而是
# "实时副本的 sha == 上一次记录的内置副本 sha"：这说明实时副本只是上一版的内置
# 副本，此时本版内置了不同的箱子就该换掉。反向的三道闸同样要有：
#   - 内置副本结构不合法（级别 2）           -> 不换
#   - 内置副本级别低于实时副本               -> 不换（防止构建产物回退把可信箱子换掉）
#   - 两边代次都能识别且内置副本代次更低      -> 不换（防止镜像/构建回源到旧快照）
kb_ensure_from_module() {
    local _ship="$1" _live_sha _ship_sha _prev _live_lvl _ship_lvl _live_gen _ship_gen
    [ -f "$_ship" ] || return 0
    _ship_sha=$(kb_sha "$_ship")
    _live_sha=$(kb_sha "$KB_LIVE")
    _ship_lvl=$(kb_trust_level "$_ship")
    if [ "$_ship_lvl" = "2" ]; then
        _log "WARN" "keybox: 模块内置副本结构不合法，忽略（sha=$(kb_short "$_ship_sha")）"
        return 0
    fi
    _prev=$(cat "$KB_SHIPPED" 2>/dev/null)
    if [ ! -f "$KB_LIVE" ]; then
        _log "INFO" "keybox: 实时副本缺失，用模块内置副本播种（DeviceID=$(kb_device_id "$_ship") 级别=$_ship_lvl）"
        kb_install "$_ship" "module-shipped"
    elif [ "$_live_sha" = "$_prev" ] && [ "$_live_sha" != "$_ship_sha" ]; then
        _live_lvl=$(kb_trust_level "$KB_LIVE")
        _live_gen=$(kb_generation "$KB_LIVE")
        _ship_gen=$(kb_generation "$_ship")
        if [ "$_ship_lvl" -gt "$_live_lvl" ]; then
            _log "WARN" "keybox: 本版内置副本级别 $_ship_lvl 低于实时副本 $_live_lvl，保留实时副本"
        elif [ -n "$_live_gen" ] && [ -n "$_ship_gen" ] && [ "$_ship_gen" -lt "$_live_gen" ]; then
            _log "WARN" "keybox: 本版内置副本代次更旧（Yurikey$_ship_gen < Yurikey$_live_gen），保留实时副本"
        else
            _log "INFO" "keybox: 实时副本仍是上一版内置副本，本版内置了更新的盒子，换掉（$_live_sha -> $_ship_sha）"
            kb_install "$_ship" "module-shipped-upgrade"
        fi
    else
        _log "INFO" "keybox: 实时副本保留（DeviceID=$(kb_device_id "$KB_LIVE") sha=$(kb_short "$_live_sha") 级别=$(kb_trust_level "$KB_LIVE")）"
    fi
    printf '%s\n' "$_ship_sha" > "$KB_SHIPPED" 2>/dev/null
}

# 常驻看护：1s 权限钉住，15s 可信度巡检 + 刷新判定。
kb_guardian_loop() {
    local _tick=0
    kb_prepare
    while :; do
        sleep "$KB_TICK"
        _tick=$((_tick + 1))
        kb_fix_perms
        if [ $((_tick % KB_CHECK_EVERY)) -eq 0 ]; then
            kb_check_live
            kb_maybe_refresh
        fi
    done
}
