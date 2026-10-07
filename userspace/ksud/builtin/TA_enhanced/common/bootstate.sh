#!/system/bin/sh
# 新增文件（GPL-3.0）：本文件由 XECKernel Pro 编写，非上游文件、不含上游代码，随 TA_enhanced
# 模块一起按 GPL-3.0 分发。新增日期 2026-10-08；说明见 ../NOTICE.md「本地修改」。
#
# bootstate.sh —— bootloader「回锁态」伪装：最早生效 + 常驻看护 + 证据留痕
# ============================================================================
# 为什么单独拆出来：
#
# 1) **时机太晚。** 原 prop.sh 一开头就 `resetprop -w sys.boot_completed 0`
#    （等到开机完成才动手），于是从内核启动到 boot_completed 之间，任何检测方
#    读到的都是真实值：ro.boot.verifiedbootstate=orange / ro.boot.flash.locked=0。
#    LKM 模式尤其明显——LKM 不修补 boot 镜像，这些属性就是 bootloader 原样报的。
#    现在把"锁态属性"拆成 bootstate_spoof_lock()，在 post-fs-data 阶段
#    （第一个能写属性的时机）就写下去。
#
# 2) **构建身份类属性故意留在原时机。** ro.debuggable / ro.adb.secure /
#    ro.build.type / ro.build.tags / ro.secure / ro.crypto.state 属于"指纹伪装"，
#    提前写有改变 init/adbd 早期分支的风险，因此仍由 prop.sh 在 boot 完成后处理
#    （bootstate_spoof_identity）。只有"锁态"这组提前——它才是检测方判断
#    "bootloader 是否解锁"的直接依据。
#
# 3) **写完不复核就等于没写。** 上一轮已经加了两遍校验；这里加上常驻看护
#    （bootstate_watchdog_loop，30s 一次）：属性被系统改回去就补写并记 WARN，
#    免得"开机前几秒是对的、几分钟后又变回解锁"这种最难查的形态。
#
# 4) **/proc/cmdline 与 /proc/bootconfig 是另一条独立的泄露路径。**
#    resetprop 改的是属性区，改不了内核命令行。本文件每次校验都会把这两个文件里
#    的 verifiedbootstate / flash.locked 真实取值写进日志——检测方如果去读它们，
#    日志里就能直接看到，不用再猜（SUSFS 的 cmdline/bootconfig 伪装是本仓库
#    内核侧可选项，Kconfig 里有开关但当前用户态没有调用入口，见交接文档）。
# ============================================================================

# 该模块需要的日志/属性助手来自 common.sh（_log / check_reset_prop / resetprop）

# "属性不存在才补写"的助手（原来定义在 prop.sh 里；post-fs-data 阶段也要用，
# 因此移到本文件，prop.sh 直接复用同一份实现，避免两处语义漂移）。
ensure_prop() {
    local NAME="$1" NEWVAL="$2" VALUE
    VALUE=$(getprop "$NAME")
    if [ -z "$VALUE" ]; then
        if resetprop -n "$NAME" "$NEWVAL" 2>/dev/null; then
            _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
        else
            _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
            _log "ERROR" "Failed to spoof (ensure): $NAME"
        fi
    fi
}

BOOTSTATE_ZEROMOUNT_ACTIVE=false
_bs_zm_dir="/data/adb/modules/meta-zeromount"
if [ -d "$_bs_zm_dir" ] && [ ! -f "$_bs_zm_dir/disable" ] && [ ! -f "$_bs_zm_dir/remove" ]; then
    BOOTSTATE_ZEROMOUNT_ACTIVE=true
    _log "WARN" "ZeroMount active — deferring overlapping props (bootloader-state props NOT spoofed this boot; detectors will see the real state)"
fi

# 运行形态取证：LKM / 内置 / late-load。
# 权威来源是 ksud 自己（`ksud debug info` 直接打印内核上报的 flags），拿不到时
# 退回 sysfs：LKM 模式下内核模块是 insmod 进来的，/sys/module/kernelsu 存在；
# 内置模式（obj-y 编进内核）不会有这个目录。
bootstate_detect_mode() {
    BOOTSTATE_MODE="unknown"
    BOOTSTATE_AUTHORITATIVE="no"
    local _info _ksud=""
    if [ -x /data/adb/ksu/bin/ksud ]; then
        _ksud=/data/adb/ksu/bin/ksud
    elif command -v ksud >/dev/null 2>&1; then
        _ksud=$(command -v ksud)
    elif [ -x /data/adb/xudc ]; then
        _ksud=/data/adb/xudc
    fi
    if [ -n "$_ksud" ]; then
        _info=$(timeout 10 "$_ksud" debug info 2>/dev/null)
        case "$_info" in
            *"lkm: true"*)  BOOTSTATE_MODE="lkm" ;;
            *"lkm: false"*) BOOTSTATE_MODE="builtin" ;;
        esac
        case "$_info" in
            *"lkm: "*) BOOTSTATE_AUTHORITATIVE="yes" ;;
        esac
        case "$_info" in
            *"late_load: true"*) BOOTSTATE_LATE_LOAD=1 ;;
            *"late_load: false"*) BOOTSTATE_LATE_LOAD=0 ;;
        esac
    fi
    if [ "$BOOTSTATE_MODE" = "unknown" ]; then
        if [ -d /sys/module/kernelsu ] || [ -d /sys/module/xudc ]; then
            BOOTSTATE_MODE="lkm"
        elif [ "$KSU" = "true" ]; then
            BOOTSTATE_MODE="builtin"
        fi
    fi
    case "${KSU_LATE_LOAD:-}" in
        1) BOOTSTATE_LATE_LOAD=1 ;;
    esac
    _log "INFO" "运行形态：mode=$BOOTSTATE_MODE（ksud 权威=$BOOTSTATE_AUTHORITATIVE）late_load=${BOOTSTATE_LATE_LOAD:-0} KSU=$KSU APATCH=$APATCH MANAGER=${MANAGER:-?}"
}

# ---------------------------------------------------------------------------
# 锁态属性（最早生效的那组）
# ---------------------------------------------------------------------------
bootstate_spoof_lock() {
    [ "$BOOTSTATE_ZEROMOUNT_ACTIVE" = "true" ] && return 0
    check_reset_prop "ro.boot.vbmeta.device_state" "locked"
    check_reset_prop "ro.boot.verifiedbootstate" "green"
    check_reset_prop "ro.boot.flash.locked" "1"
    check_reset_prop "ro.boot.veritymode" "enforcing"
    check_reset_prop "ro.boot.warranty_bit" "0"
    check_reset_prop "ro.warranty_bit" "0"
    check_reset_prop "ro.vendor.boot.warranty_bit" "0"
    check_reset_prop "ro.vendor.warranty_bit" "0"
    check_reset_prop "vendor.boot.vbmeta.device_state" "locked"
    check_reset_prop "vendor.boot.verifiedbootstate" "green"
    check_reset_prop "ro.secureboot.lockstate" "locked"
    check_reset_prop "ro.secureboot.devicelock" "1"
    check_reset_prop "ro.boot.realmebootstate" "green"
    check_reset_prop "ro.boot.realme.lockstate" "1"
    check_reset_prop "ro.is_ever_orange" "0"
    # sys.oem_unlock_allowed 不是 ro，系统后续可能改回去——看护循环会补写。
    check_reset_prop "sys.oem_unlock_allowed" "0"
}

# ---------------------------------------------------------------------------
# 构建身份类属性（保持原时机：prop.sh 在 boot 完成后调用）
# ---------------------------------------------------------------------------
bootstate_spoof_identity() {
    [ "$BOOTSTATE_ZEROMOUNT_ACTIVE" = "true" ] && return 0
    check_reset_prop "ro.debuggable" "0"
    check_reset_prop "ro.force.debuggable" "0"
    check_reset_prop "ro.secure" "1"
    check_reset_prop "ro.adb.secure" "1"
    check_reset_prop "ro.build.type" "user"
    check_reset_prop "ro.build.tags" "release-keys"
    check_reset_prop "ro.crypto.state" "encrypted"
    check_reset_prop "ro.oem_unlock_supported" "0"

    # qemu 属性：有些检测方只看"存在"而不看值，所以整条删掉而不是清空。
    if [ -n "$(resetprop ro.kernel.qemu)" ]; then
        if resetprop --delete ro.kernel.qemu 2>/dev/null; then
            _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
        else
            resetprop -n ro.kernel.qemu "" 2>/dev/null
            _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
            _log "WARN" "Could not delete ro.kernel.qemu, blanked instead"
        fi
    fi

    # recovery 启动模式
    contains_reset_prop "ro.bootmode" "recovery" "unknown"
    contains_reset_prop "ro.boot.bootmode" "recovery" "unknown"
    contains_reset_prop "ro.boot.mode" "recovery" "unknown"
    contains_reset_prop "vendor.bootmode" "recovery" "unknown"
    contains_reset_prop "vendor.boot.bootmode" "recovery" "unknown"
    contains_reset_prop "vendor.boot.mode" "recovery" "unknown"
}

# ---------------------------------------------------------------------------
# vbmeta 相关属性（digest/size/avb 版本）——保持原时机
# ---------------------------------------------------------------------------
bootstate_spoof_vbmeta() {
    [ "$BOOTSTATE_ZEROMOUNT_ACTIVE" = "true" ] && return 0
    local _hash_src="" hash_value="" _ts_mod _ts_mp _teesim_ok=false slot_suffix candidate VBMETA_SIZE

    # 只有本项目的 TEESimulator 变体在位时才用它的 boot_hash.bin
    _ts_mod="/dev/.xudc_hidden/tricky_store"
    _ts_mp=$(cat "$_ts_mod/module.prop" 2>/dev/null)
    case "$_ts_mp" in
        *TEESimulator-RS*)
            [ -d "$_ts_mod" ] && [ ! -f "$_ts_mod/disable" ] && [ ! -f "$_ts_mod/remove" ] && _teesim_ok=true ;;
    esac

    if [ "$_teesim_ok" = "true" ] && [ -f "$TS_DIR/boot_hash.bin" ]; then
        hash_value=$(od -A n -t x1 "$TS_DIR/boot_hash.bin" 2>/dev/null | tr -d ' \n')
        if echo "$hash_value" | grep -qE '^[a-f0-9]{64}$'; then
            _hash_src="teesim"
        else
            hash_value=""
        fi
    fi

    if [ -z "$hash_value" ] && [ -f "/data/adb/boot_hash" ]; then
        hash_value=$(grep -v '^#' "/data/adb/boot_hash" 2>/dev/null | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
        [ -n "$hash_value" ] && _hash_src="boot_hash"
    fi

    if echo "$hash_value" | grep -qE '^[a-f0-9]{64}$'; then
        if resetprop -n ro.boot.vbmeta.digest "$hash_value" 2>/dev/null; then
            _PROP_SPOOF_COUNT=$((_PROP_SPOOF_COUNT + 1))
            _log "INFO" "VBMeta digest set from $_hash_src: $(printf '%.16s' "$hash_value")..."
        else
            _PROP_FAIL_COUNT=$((_PROP_FAIL_COUNT + 1))
            _log "ERROR" "Failed to set vbmeta.digest from $_hash_src"
        fi
    elif [ -n "$hash_value" ]; then
        _log "WARN" "boot_hash invalid from $_hash_src (not 64-char hex)"
    fi

    ensure_prop "ro.boot.vbmeta.device_state" "locked"
    ensure_prop "ro.boot.vbmeta.invalidate_on_error" "yes"
    ensure_prop "ro.boot.vbmeta.avb_version" "1.0"
    ensure_prop "ro.boot.vbmeta.hash_alg" "sha256"

    slot_suffix=$(getprop ro.boot.slot_suffix 2>/dev/null)
    VBMETA_SIZE=""
    for candidate in \
        "/dev/block/by-name/vbmeta${slot_suffix}" \
        "/dev/block/by-name/vbmeta" \
        "/dev/block/by-name/vbmeta_a" \
        "/dev/block/by-name/vbmeta_b"; do
        if [ -b "$candidate" ]; then
            VBMETA_SIZE=$(blockdev --getsize64 "$candidate" 2>/dev/null)
            [ -n "$VBMETA_SIZE" ] && [ "$VBMETA_SIZE" -gt 0 ] 2>/dev/null && break
            VBMETA_SIZE=""
        fi
    done
    ensure_prop "ro.boot.vbmeta.size" "${VBMETA_SIZE:-4096}"
}

# ---------------------------------------------------------------------------
# 证据：属性区之外还有两个内核接口能泄露真实启动状态
# ---------------------------------------------------------------------------
bootstate_leak_report() {
    local _cmdline _bootconfig
    _cmdline=$(tr ' ' '\n' < /proc/cmdline 2>/dev/null | grep -E 'verifiedbootstate|flash\.locked|vbmeta\.device_state' | tr '\n' ' ')
    if [ -n "$_cmdline" ]; then
        _log "WARN" "/proc/cmdline 仍带真实启动状态（resetprop 改不到内核命令行）: $_cmdline"
    fi
    if [ -r /proc/bootconfig ]; then
        _bootconfig=$(grep -E 'verifiedbootstate|flash\.locked|vbmeta\.device_state' /proc/bootconfig 2>/dev/null | tr -d ' ' | tr '\n' ' ')
        if [ -n "$_bootconfig" ]; then
            _log "WARN" "/proc/bootconfig 仍带真实启动状态: $_bootconfig"
        else
            _log "INFO" "/proc/bootconfig 未暴露 verifiedbootstate/flash.locked"
        fi
    fi
}

# ---------------------------------------------------------------------------
# 两遍校验 + 收尾取证（上一轮加的语义保留）
# ---------------------------------------------------------------------------
# 为什么要两遍：service 阶段属性区可能还在被 init 收尾，第一遍偶发不一致并不代表
# 伪装链断了；两遍都过不去才记 ERROR，此时证据是确凿的（resetprop 全缺、
# ZeroMount 豁免、属性被拒写都能从上下文立刻定位）。
bootstate_verify() {
    if [ "$BOOTSTATE_ZEROMOUNT_ACTIVE" = "true" ]; then
        _log "WARN" "final boot-state check skipped (ZeroMount deferral active) — detectors will read the real unlocked/orange state this boot"
        return 0
    fi
    local _bs_failed="" _p _name _want
    for _p in "ro.boot.vbmeta.device_state=locked" \
              "ro.boot.verifiedbootstate=green" \
              "ro.boot.flash.locked=1"; do
        _name=${_p%%=*}; _want=${_p#*=}
        if [ "$(getprop "$_name")" != "$_want" ]; then
            # 属性不存在（读回空）与值不对是两件事：前者不是伪装失败，本设备没有
            # 这条属性，补写反而会凭空造出一条检测方会当异常看的属性。
            if [ -n "$(getprop "$_name")" ]; then
                resetprop -n "$_name" "$_want" 2>/dev/null
            fi
            sleep 2
            if [ "$(getprop "$_name")" != "$_want" ]; then
                _bs_failed="$_bs_failed $_name='$(getprop "$_name")'(want '$_want')"
            fi
        fi
    done
    _log "INFO" "resetprop backend: ${RESETPROP_BIN:-none (read-only fallback)}"
    if [ -n "$_bs_failed" ]; then
        _log "ERROR" "boot-state verify failed after retry:$_bs_failed"
    fi
    _log "INFO" "final boot-state: device_state=$(getprop ro.boot.vbmeta.device_state) verifiedboot=$(getprop ro.boot.verifiedbootstate) flash.locked=$(getprop ro.boot.flash.locked) veritymode=$(getprop ro.boot.veritymode) oem_unlock_allowed=$(getprop sys.oem_unlock_allowed)"
}

# ---------------------------------------------------------------------------
# 常驻看护：sys.* 属性会被系统改回去；篡改留下的漏洞不补，等于没做。
# ---------------------------------------------------------------------------
bootstate_watchdog_loop() {
    local _warned_oem=0 _tick=0
    while :; do
        sleep 30
        _tick=$((_tick + 1))
        [ "$BOOTSTATE_ZEROMOUNT_ACTIVE" = "true" ] && continue
        # ro.* 属性是写一次就定型的，这里只读回来核对（不产生写放大）
        if [ "$_tick" -le 20 ]; then
            if [ "$(getprop sys.oem_unlock_allowed)" = "1" ]; then
                if resetprop -n sys.oem_unlock_allowed "0" 2>/dev/null; then
                    if [ "$_warned_oem" = "0" ]; then
                        _warned_oem=1
                        _log "WARN" "sys.oem_unlock_allowed 被系统改回 1，已补写为 0（看护每 30s 一次，最多核对 10 分钟）"
                    fi
                fi
            fi
        fi
        if [ "$_tick" -le 4 ]; then
            local _vbs
            _vbs=$(getprop ro.boot.verifiedbootstate)
            if [ -n "$_vbs" ] && [ "$_vbs" != "green" ]; then
                resetprop -n ro.boot.verifiedbootstate "green" 2>/dev/null
                _log "WARN" "ro.boot.verifiedbootstate 漂移为 '$_vbs'，已回写 green"
            fi
        fi
    done
}
