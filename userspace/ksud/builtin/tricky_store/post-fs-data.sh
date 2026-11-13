#!/system/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro 新增，上游 Oh My Keymint 没有对应
# 的可用形态（它的 post-fs-data.sh 只处理自己的模块目录）。改动清单见 NOTICE.md。
#
# 为什么需要这一阶段：
#   OMY 的 keybox/config 落在 /data/misc/keystore/omk，属主必须是 1017 (keystore)。
#   keystore2 在开机很早就会启动并读取该目录；如果等到 service 阶段才建，第一次开机
#   的前若干次证明请求会拿到真实 TEE 的结论（未锁/未知根）。所以这里的活必须在
#   keystore2 起来之前干完——这就是 builtin.rs 里 tricky_store 也要跑 post-fs-data 的原因。
MODPATH=${0%/*}
RUNTIME=/data/adb/tricky_store
LOG_FILE="$RUNTIME/.engine.log"
OMK=/data/misc/keystore/omk
OMK_STATE=/data/adb/omk

mkdir -p "$RUNTIME" 2>/dev/null
chmod 755 "$RUNTIME" 2>/dev/null
log_line() {
    printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG_FILE" 2>/dev/null
}

# 外部同名模块存在时让路（与 service.sh 的判定保持一致）。
external_engine_active() {
    local dir self
    self="${MODPATH%/}"
    for dir in /data/adb/modules/oh_my_keymint \
               /data/adb/modules/ohmykeymint \
               /data/adb/modules/tricky_store \
               /data/adb/modules/.tricky_store; do
        [ "$dir" = "$self" ] && continue
        [ -f "$dir/module.prop" ] || continue
        [ -f "$dir/disable" ] && continue
        [ -f "$dir/remove" ] && continue
        return 0
    done
    return 1
}
if external_engine_active; then
    log_line "INFO post-fs-data: external engine module present, stand down"
    exit 0
fi

# 1) omk 工作目录：keystore2 可以进入，其他人不行。
mkdir -p "$OMK/logs" 2>/dev/null
chmod 0770 "$OMK" "$OMK/logs" 2>/dev/null
if ! chown 1017:1017 "$OMK" "$OMK/logs" 2>/dev/null; then
    log_line "WARN post-fs-data: chown 1017:1017 on $OMK failed (engine may not read its config)"
fi

# 2) 状态目录：清掉上一次运行残留的 pidfile 与重启标记。
mkdir -p "$OMK_STATE" 2>/dev/null
rm -f "$OMK_STATE/keymint-daemon.pid" "$OMK_STATE/injector-daemon.pid" 2>/dev/null
rm -f "$OMK_STATE/restart.keymint" "$OMK_STATE/restart.injector" "$OMK_STATE/restart.all" 2>/dev/null

# 3) keybox 播种：只补缺失，不覆盖用户/引擎自己拉来的副本。
#    实时真源是 $RUNTIME/keybox.xml（管理器、TA 自检都读它），这里只是把它镜像给 OMY。
seed_keybox() {
    local src="$RUNTIME/keybox.xml" dst="$OMK/keybox.xml" tmp="$OMK/.keybox.xml.tmp.$$"
    [ -f "$src" ] || src="$MODPATH/keybox.xml"
    [ -f "$src" ] || return 0
    if [ -f "$dst" ]; then
        cmp -s "$src" "$dst" 2>/dev/null && return 0
    fi
    cp -f "$src" "$tmp" 2>/dev/null || return 1
    chown 1017:1017 "$tmp" 2>/dev/null
    chmod 0600 "$tmp" 2>/dev/null
    mv -f "$tmp" "$dst" 2>/dev/null || { rm -f "$tmp"; return 1; }
    log_line "INFO post-fs-data: keybox mirrored to $dst"
    return 0
}
seed_keybox

# 4) injector.toml：service.sh 里由 engineconf.sh 的 sync_scoop 全量生成；
#    这里只在完全缺失时给一份空作用域的保底文件，避免 OMY 自己生成默认值后
#    被我们覆盖时触发一次 keymint 重启。
if [ ! -f "$OMK/injector.toml" ]; then
    tmp="$OMK/.injector.toml.tmp.$$"
    cat > "$tmp" <<'EOF'
version = 1

scoop = []

[main]
enabled = true
log_level = "debug"

[filter]
enabled = true
deny_packages = []
block_android_package = true
allow_unknown_package = false

[intercept]
get_security_level = true
get_key_entry = true
update_subcomponent = true
list_entries = true
delete_key = true
grant = true
ungrant = true
get_number_of_entries = true
list_entries_batched = true
get_supplementary_attestation_info = true
EOF
    if [ -s "$tmp" ]; then
        chown 1017:1017 "$tmp" 2>/dev/null
        chmod 0600 "$tmp" 2>/dev/null
        mv -f "$tmp" "$OMK/injector.toml" 2>/dev/null && \
            log_line "INFO post-fs-data: seeded empty injector.toml"
    fi
    rm -f "$tmp" 2>/dev/null
fi

# 5) config.toml：故意不生成。OMY 首启会自己写（含 [crypto] 随机密钥与 [device]
#    自动探测）；我们代写就会变成两份真源，换机/换名都对不上。
exit 0
