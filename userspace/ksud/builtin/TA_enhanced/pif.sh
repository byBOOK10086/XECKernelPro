#!/bin/sh
# 修改版（GPL-3.0 §5(a)）：本文件由 XECKernel Pro **新增**（2026-11-10），不是上游文件；
# 改动清单与日期见同目录 NOTICE.md「本地修改」。上游：Enginex0/tricky-addon-enhanced（GPL-3.0）。
#
# pif.sh —— 构建指纹伪装（Play Integrity 的 DEVICE/STRONG 判定看的正是这套字段：
# 指纹里的 brand/device/release/id/incremental 必须与机型、安全补丁自洽，否则
# 拿到的一定是 BASIC 而不是 DEVICE）。
#
# ## 数据来源：**只信管理器写好的白名单表**
#
#   /data/adb/.xudc_secure/pif/enabled     存在=启用（内容忽略）
#   /data/adb/.xudc_secure/pif/props.txt   每行 `ro.xxx=值`
#
# 为什么不在脚本里解析 JSON：设备上没有 jq，用 sed 解析 JSON 遇到转义/嵌套就会
# 静默出错，而这里写的是**全局只读属性**，错一个字段就可能开不了机。管理器用
# org.json 解析后写成"一行一个属性"的朴素格式，脚本只做白名单校验 + resetprop。
#
# ## 为什么不写 security_patch
#
# 补丁级别由守护进程按"每 2 小时"的节奏自己刷新（`security_patch.interval`），
# 两个写入方会互相覆盖、还会让指纹里的日期和补丁对不上。这里显式不碰
# `ro.*security_patch`，只伪装身份字段。

MODPATH="${0%/*}"
MODDIR="$MODPATH"
PIF_DIR="${PIF_DIR:-/data/adb/.xudc_secure/pif}"
PIF_PROPS="$PIF_DIR/props.txt"
PIF_ENABLED="$PIF_DIR/enabled"

if [ -f "$MODPATH/common/common.sh" ]; then
    . "$MODPATH/common/common.sh"
fi
if ! command -v _log >/dev/null 2>&1; then
    _log() { echo "[pif] $2" >&2; }
fi

# 白名单：写坏的属性是"开不了机"级别的事故，所以只放身份字段。
# 注意 `ro.product.*.brand/model/device/name` 这类分区级字段也要放行 ——
# Android 12+ 的应用读的是分区级属性，只改 ro.product.model 没用。
is_allowed_prop() {
    case "$1" in
        ro.build.fingerprint|ro.bootimage.build.fingerprint|ro.system.build.fingerprint|\
        ro.vendor.build.fingerprint|ro.odm.build.fingerprint|ro.product.build.fingerprint|\
        ro.system_ext.build.fingerprint|ro.build.product|ro.build.device|ro.build.id|\
        ro.build.display.id|ro.build.version.incremental|ro.build.version.release|\
        ro.build.date|ro.build.date.utc|ro.build.tags|ro.build.type|ro.build.flavor|\
        ro.build.description|ro.build.host|ro.build.user|\
        ro.product.brand|ro.product.model|ro.product.device|ro.product.name|\
        ro.product.manufacturer|ro.product.first_api_level|\
        ro.product.system.brand|ro.product.system.model|ro.product.system.device|\
        ro.product.system.name|ro.product.system.manufacturer|\
        ro.product.vendor.brand|ro.product.vendor.model|ro.product.vendor.device|\
        ro.product.vendor.name|ro.product.vendor.manufacturer|\
        ro.product.odm.brand|ro.product.odm.model|ro.product.odm.device|ro.product.odm.name|\
        ro.product.product.brand|ro.product.product.model|ro.product.product.device|\
        ro.product.product.name|ro.product.system_ext.brand|ro.product.system_ext.model|\
        ro.product.system_ext.device|ro.product.system_ext.name|\
        ro.vendor.product.brand|ro.vendor.product.model|ro.vendor.product.device|\
        ro.vendor.product.name|ro.vendor.product.manufacturer)
            return 0 ;;
    esac
    return 1
}

# 值里不能有换行/控制字符（会破坏属性表），也不能为空。
is_sane_value() {
    [ -n "$1" ] || return 1
    case "$1" in
        *"$(printf '\n')"*) return 1 ;;
    esac
    return 0
}

pif_apply() {
    if [ ! -f "$PIF_ENABLED" ]; then
        _log "INFO" "pif: 未启用（没有 $PIF_ENABLED）"
        return 0
    fi
    if [ ! -f "$PIF_PROPS" ]; then
        _log "WARN" "pif: 已启用但 $PIF_PROPS 不存在，跳过"
        return 0
    fi
    local ok=0 bad=0 skipped=0 name val line
    while IFS= read -r line; do
        case "$line" in
            ''|'#'*) continue ;;
        esac
        name=${line%%=*}
        val=${line#*=}
        if [ "$name" = "$line" ]; then
            skipped=$((skipped + 1))
            continue
        fi
        if ! is_allowed_prop "$name"; then
            _log "WARN" "pif: 非白名单属性已忽略：$name"
            skipped=$((skipped + 1))
            continue
        fi
        if ! is_sane_value "$val"; then
            _log "WARN" "pif: 值不合法已忽略：$name"
            skipped=$((skipped + 1))
            continue
        fi
        if resetprop -n "$name" "$val" 2>/dev/null; then
            ok=$((ok + 1))
        else
            bad=$((bad + 1))
            _log "WARN" "pif: 写入失败 $name"
        fi
    done < "$PIF_PROPS"
    _log "INFO" "pif: 写入 $ok 项，失败 $bad 项，跳过 $skipped 项；fingerprint=$(getprop ro.build.fingerprint)"
}

pif_disable() {
    _log "INFO" "pif: 停用（删除 $PIF_ENABLED）"
    rm -f "$PIF_ENABLED" 2>/dev/null
}

case "${1:-apply}" in
    apply) pif_apply ;;
    disable) pif_disable ;;
    verify) getprop ro.build.fingerprint ;;
    *) pif_apply ;;
esac
