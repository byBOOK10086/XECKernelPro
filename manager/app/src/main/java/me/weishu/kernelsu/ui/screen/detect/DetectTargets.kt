package me.weishu.kernelsu.ui.screen.detect

/**
 * 引擎拦截白名单（target.txt）的共享工具。
 *
 * 存在的理由：引擎的拦截是**穷举白名单**（TEESimulator 的
 * ConfigurationManager.shouldSkipUid：UID 不在表里直接跳过，原样拿到真实 TEE 证明），
 * 而这份名单有三条互不相认的写入路径：
 *   1) 内置模块 service.sh 每次开机把 target.baseline.txt **只增不删**地并进
 *      /data/adb/tricky_store/target.txt（引擎真正读的那一份）；
 *   2) 管理器 / WebUI 写的是 /data/adb/.xudc_secure/target.txt；
 *   3) 桌面应用自动入表（target_autofill.sh）。
 * 于是"装完验机工具直接打开"这种最常见的路径上，包**还没进表**（要等下次开机才被
 * 并入），界面上就是"密钥认证报未知根证书 / bootloader 报解锁"。这里把"补进表 +
 * 两个路径对账"做成一份脚本：检测页的按钮用它，安装内置验机工具之后也立刻用它，
 * 引擎侧的 ConfigObserver（CLOSE_WRITE/MOVED_TO）会即时重载，不需要重启。
 */
internal object DetectTargets {

    const val TRICKY_RUNTIME = "/data/adb/tricky_store"
    const val TRICKY_DATA = "/data/adb/.xudc_secure"

    /** 管理器内置的验机工具（密钥链有效性 / 启动状态 / 环境异常）。 */
    const val BUNDLED_CHECKER = "wu.keyChain.test"

    /**
     * 把内置验机工具补进两个路径的 target.txt 并回显 added=yes/no。
     * 追加时按引擎的解析规则另起一个 `[keybox.xml]` 分组头，避免继承用户自定义的
     * 其它 keybox 分组（`[xxx.xml]` 会切换后续条目使用的箱子）。
     */
    val ADD_CHECKER_SCRIPT: String = listOf(
        "TK=$TRICKY_RUNTIME",
        "T=\$TK/target.txt",
        "mkdir -p \$TK " + TRICKY_DATA,
        "[ -f \$T ] || : > \$T",
        "if ! grep -qE '^[[:space:]]*$BUNDLED_CHECKER[!?]?[[:space:]]*\$' \$T; then",
        "  cp -f \$T \$T.new.\$\$ && printf '\\n[keybox.xml]\\n$BUNDLED_CHECKER\\n' >> \$T.new.\$\$ && " +
            "chmod 644 \$T.new.\$\$ && mv -f \$T.new.\$\$ \$T",
        "fi",
        "cp -f \$T " + TRICKY_DATA + "/target.txt 2>/dev/null",
        "chmod 644 \$T " + TRICKY_DATA + "/target.txt 2>/dev/null",
        "grep -qE '^[[:space:]]*$BUNDLED_CHECKER[!?]?[[:space:]]*\$' \$T && echo added=yes || echo added=no",
    ).joinToString("\n")
}
