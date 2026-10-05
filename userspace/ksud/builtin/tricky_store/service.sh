#!/system/bin/sh
# Built-in TEESimulator-RS engine init.
#
# v30136 起与上游/资源包版完全同构（资源包版在真机上已被证实可用，旧内置方案
# ——补丁 dex 读 .xudc_secure + tmpfs 运行——从未工作过，废弃）：
#   1. dex 即上游未修改 classes.dex，配置目录 = /data/adb/tricky_store（上游常量）；
#   2. 引擎文件 staging 到该持久目录并从那里运行（消除 tmpfs 执行变量）；
#   3. 资源包模块在场且启用时内置让位——同引擎双注入会互杀，资源包优先。
MODPATH=${0%/*}
RUNTIME=/data/adb/tricky_store

# --- 仲裁：资源包模块在场且启用 → 内置引擎整体让位 ---
if [ -d /data/adb/modules/tricky_store ] && \
   [ ! -f /data/adb/modules/tricky_store/disable ] && \
   [ ! -f /data/adb/modules/tricky_store/remove ]; then
    mkdir -p "$RUNTIME" 2>/dev/null
    echo "$(date '+%F %T') builtin TEERS stand down: pack module active" >> "$RUNTIME/.engine.log" 2>/dev/null
    exit 0
fi

# --- 引擎 staging：缺失或版本变化才覆盖（配置文件不受影响） ---
VER=$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop" 2>/dev/null)
[ -n "$VER" ] || VER=0
CUR=$(cat "$RUNTIME/.engine_version" 2>/dev/null)
if [ "$VER" != "$CUR" ] || [ ! -f "$RUNTIME/daemon" ] || [ ! -f "$RUNTIME/classes.dex" ] || [ ! -x "$RUNTIME/inject" ]; then
    mkdir -p "$RUNTIME" 2>/dev/null
    for f in daemon inject supervisor classes.dex libTEESimulator.so libcertgen.so module.prop; do
        [ -f "$MODPATH/$f" ] && cp -f "$MODPATH/$f" "$RUNTIME/$f" 2>/dev/null
    done
    chmod 755 "$RUNTIME/daemon" "$RUNTIME/inject" "$RUNTIME/supervisor" 2>/dev/null
    chmod 644 "$RUNTIME/classes.dex" "$RUNTIME/libTEESimulator.so" "$RUNTIME/libcertgen.so" "$RUNTIME/module.prop" 2>/dev/null
    echo "$VER" > "$RUNTIME/.engine_version" 2>/dev/null
fi

# --- 配置播种（文件已存在则保留用户改动） ---
[ -f "$RUNTIME/keybox.xml" ] || cp -f "$MODPATH/keybox.xml" "$RUNTIME/keybox.xml" 2>/dev/null
[ -f "$RUNTIME/target.txt" ] || cp -f "$MODPATH/target.txt" "$RUNTIME/target.txt" 2>/dev/null
[ -f "$RUNTIME/security_patch.txt" ] || printf 'system=prop\n' > "$RUNTIME/security_patch.txt" 2>/dev/null
[ -f "$RUNTIME/hbk" ] || dd if=/dev/random of="$RUNTIME/hbk" bs=32 count=1 2>/dev/null
chmod 644 "$RUNTIME/keybox.xml" "$RUNTIME/target.txt" "$RUNTIME/security_patch.txt" "$RUNTIME/hbk" 2>/dev/null
rm -f "$RUNTIME/tee_status.txt" 2>/dev/null

# --- 兼容播种：TA_enhanced 守护进程与管理器状态行仍读 .xudc_secure ---
DATA=/data/adb/.xudc_secure
mkdir -p "$DATA" 2>/dev/null
chmod 755 "$DATA" 2>/dev/null
[ -f "$DATA/keybox.xml" ] || cp -f "$MODPATH/keybox.xml" "$DATA/keybox.xml" 2>/dev/null
[ -f "$DATA/target.txt" ] || cp -f "$MODPATH/target.txt" "$DATA/target.txt" 2>/dev/null
[ -f "$DATA/security_patch.txt" ] || printf 'system=prop\n' > "$DATA/security_patch.txt" 2>/dev/null
[ -f "$DATA/hbk" ] || dd if=/dev/random of="$DATA/hbk" bs=32 count=1 2>/dev/null
chmod 644 "$DATA/keybox.xml" "$DATA/target.txt" "$DATA/security_patch.txt" "$DATA/hbk" 2>/dev/null
rm -f "$DATA/tee_status.txt" 2>/dev/null

# --- 从持久目录启动（与资源包版同一运行方式：cwd 即引擎目录） ---
cd "$RUNTIME" || exit
./supervisor ./daemon "$RUNTIME" >/dev/null 2>&1 &
