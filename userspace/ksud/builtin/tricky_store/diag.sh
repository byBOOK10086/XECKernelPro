#!/system/bin/sh
# XECKernel Pro —— TEE / 环境一致性诊断收集（只读，不改任何东西）
#
# 用法（root）：
#   su -c sh /data/adb/ksu/builtin/tricky_store/diag.sh
# 输出同时写到 /data/adb/tricky_store/.diag.log，方便直接发出来。
#
# 为什么要有它：春秋/鸭子/Maple 的 TEE 命中分两类——
#   ① 检测器根本没进引擎作用域（scoop 里没有它）→ 拿到真实 TEE；
#   ② 进了作用域，但引擎生成的链/标签/时序被判定为伪造。
# 这两类的修法完全不同，而界面上只显示"TEE 异常"。这个脚本把判定所需的证据一次收齐：
# 路由结论、箱子健康度、作用域内容、注入状态、证明会写什么 vs 属性实际是什么。
MODDIR=${0%/*}
RUNTIME=${RUNTIME:-/data/adb/tricky_store}
OMK=${OMK:-/data/misc/keystore/omk}
DATA=${DATA:-/data/adb/.xudc_secure}
OUT=${OUT:-$RUNTIME/.diag.log}
: > "$OUT" 2>/dev/null || OUT=/dev/null

say() { printf '%s\n' "$*" | tee -a "$OUT" 2>/dev/null; }
sec() { printf '\n===== %s =====\n' "$*" | tee -a "$OUT" 2>/dev/null; }
kv()  { printf '%-34s %s\n' "$1" "$2" | tee -a "$OUT" 2>/dev/null; }

getp() { getprop "$1" 2>/dev/null; }

sec "device"
kv date "$(date '+%F %T %z' 2>/dev/null)"
kv model "$(getp ro.product.model) / $(getp ro.product.device)"
kv build.fingerprint "$(getp ro.build.fingerprint)"
kv android "$(getp ro.build.version.release) (SDK $(getp ro.build.version.sdk))"
kv security_patch.system "$(getp ro.build.version.security_patch)"
kv security_patch.vendor "$(getp ro.vendor.build.security_patch)"
kv security_patch.boot "$(getp ro.boot.boot_patch_level)"
kv build.date.utc "$(getp ro.build.date.utc)"
kv build.type "$(getp ro.build.type)"
kv kernel "$(uname -r 2>/dev/null)"
kv kernel.version "$(uname -v 2>/dev/null)"

sec "boot state"
kv verifiedbootstate "$(getp ro.boot.verifiedbootstate)"
kv flash.locked "$(getp ro.boot.flash.locked)"
kv vbmeta.device_state "$(getp ro.boot.vbmeta.device_state)"
kv varitymode "$(getp ro.boot.veritymode)"
kv secureboot.lockstate "$(getp ro.secureboot.lockstate)"
kv oem_unlock_allowed "$(getp sys.oem_unlock_allowed)"
msg=$(getp ro.boot.vbmeta.digest)
kv vbmeta.digest "${msg:-<missing>}"
kv vbmeta.avb_version "$(getp ro.boot.vbmeta.avb_version)"
kv bootstate.flag "$(cat /data/adb/ksu/.bootstate 2>/dev/null || echo '<none>')"
kv cmdline.unlock_tokens "$(tr ' ' '\n' < /proc/cmdline 2>/dev/null | grep -E 'verifiedboot|flash.locked|device_state|veritymode' | tr '\n' ' ')"

sec "engine carrier"
kv engine.version "$(cat "$RUNTIME/.engine_version" 2>/dev/null | tr '\n' ' ')"
kv detector.route "$(cat "$RUNTIME/.detector_route" 2>/dev/null || echo '<none>')"
kv target.entries "$(grep -cvE '^[[:space:]]*(#|$)' "$RUNTIME/target.txt" 2>/dev/null)"
kv target.detectors "$(grep -E 'duck|chunqiu|maple|keyattestation|nativetest|riskdetector|luna' "$RUNTIME/target.txt" 2>/dev/null | tr '\n' ' ')"
kv security_patch.txt "$(tr '\n' ' ' < "$RUNTIME/security_patch.txt" 2>/dev/null)"
kv keymint.pid "$(pidof keymint 2>/dev/null)"
kv keystore2.pid "$(pidof keystore2 2>/dev/null)"
inj=no
eng="$RUNTIME/engine"
for pid in $(pidof keystore2 2>/dev/null); do
    [ -r "/proc/$pid/maps" ] || continue
    grep -qF "$eng/" "/proc/$pid/maps" 2>/dev/null && inj=yes
done
kv keystore2.injected "$inj"
kv omk.log.tail "$(tail -n 3 "$OMK/logs/keymint.log" 2>/dev/null | tr '\n' '|' | cut -c1-200)"

sec "scope (OMY scoop)"
kv injector.toml "$(ls -l "$OMK/injector.toml" 2>/dev/null | awk '{print $1, $3, $5}')"
kv scoop.count "$(sed -n '/^scoop[[:space:]]*=[[:space:]]*\[/,/^\]/p' "$OMK/injector.toml" 2>/dev/null | grep -c '"')"
kv scoop.detectors "$(grep -E 'duck|chunqiu|maple' "$OMK/injector.toml" 2>/dev/null | tr -d ' ",' | tr '\n' ' ')"

sec "keybox"
kb="$RUNTIME/keybox.xml"
kv path "$(ls -l "$kb" 2>/dev/null | awk '{print $1, $3, $5}')"
kv device_id "$(sed -n 's/.*DeviceID="\([^"]*\)".*/\1/p' "$kb" 2>/dev/null | head -n 1)"
kv sha256_16 "$(sha256sum "$kb" 2>/dev/null | cut -c1-16)"
TA_BIN=""
for cand in /data/adb/.xudc_secure/ta-enhanced/bin/ta-enhanced \
            /data/adb/ksu/builtin/TA_enhanced/bin/arm64-v8a/ta-enhanced \
            /data/adb/modules/TA_enhanced/bin/arm64-v8a/ta-enhanced \
            /data/adb/tricky_store/ta-enhanced/bin/ta-enhanced; do
    [ -x "$cand" ] && { TA_BIN="$cand"; break; }
done
kv ta_enhanced "${TA_BIN:-<missing>}"
if [ -n "$TA_BIN" ]; then
    kv health "$("$TA_BIN" keybox health 2>/dev/null)"
    kv validate "$("$TA_BIN" keybox validate "$kb" 2>/dev/null | tr '\n' ' ' | cut -c1-400)"
fi
kv feed.auto_update disabled

sec "OMY config [trust]/[device]（证明里会写什么）"
grep -E '^[[:space:]]*(security_patch|os_patchlevel|vendor_patchlevel|boot_patchlevel|vb_key|vb_hash|verified_boot_state|device_locked|os_version|brand|device|product|manufacturer|model|serial)[[:space:]]*=' "$OMK/config.toml" 2>/dev/null | tee -a "$OUT" 2>/dev/null

sec "engine log (tail 60)"
tail -n 60 "$RUNTIME/.engine.log" 2>/dev/null | tee -a "$OUT" 2>/dev/null

sec "section5 residue (read-only)"
for p in /data/local/tmp/mount_mask /data/local/tmp/mount_mark /data/local/tmp/resetprop \
         /data/local/tmp/HyperCeiler /data/local/tmp/simpleHook /data/local/tmp/luckys \
         /data/local/stryker /data/DNA /data/system/AppRetention /data/gpu_freq_table.conf \
         /data/swap_config.conf /system/bin/adb /system/bin/fastboot /storage/emulated/0/MT2; do
    [ -e "$p" ] && kv "exists" "$p"
done
kv tmpdir "$(stat -c '%a %u:%g %y' /data/local/tmp 2>/dev/null)"
kv prop.pihooks "$(getprop 2>/dev/null | grep -cE 'pihook|pixelprops|spoof.gms')"
kv prop.persist_hidden_scheme "$(getp persist.chunqiu.path_hide)$(getp persist.sys.vold_app_data_isolation_enabled)"
kv prop.logd_size "$(getp persist.logd.size) $(getp persist.logd.size.main)"
kv dex2oat_flags "$(getp dalvik.vm.dex2oat-flags)"

sec "selinux"
kv getenforce "$(getenforce 2>/dev/null)"
kv enforce_file "$(cat /sys/fs/selinux/enforce 2>/dev/null)"

say ""
say "收集完成：$OUT"
