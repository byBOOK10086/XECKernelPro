# COLLAB-STATE — DS × 主审计 AI 联动接力

更新时间：2026-11-04 04:50（+08:00）
用途：上下文超阈值时的状态交接。任何一方接手前先读这一页，不要重新考古。

## 工作树与远端

- 工作树：`C:\Users\Lenovo\Desktop\kernelSU\_wt\removal-kpm`（分支 `removal/kpm-full`）
- 远端：`newrepo` = `https://github.com/byBOOK10086/XECKernelPro.git`
- `fresh-main` = `5db5b20a`（D4 第一批已合入，尚未包含后续修复批次）
- `removal/kpm-full` 领先 fresh-main 若干提交，**每次只推这个分支做构建，不合 fresh-main**（用户口令：构建不推送）
- 每个修复轮：改 → 推 `removal/kpm-full` → dispatch `build-xecpro.yml`（`publish=false`）→ 全绿才算过

## 最近一次全绿

- run `41713824119`，18/18（8 KMI 内核 + manager APK + aarch64/x86_64 ksud + repack + guards）
- HEAD = `f582aeea`

## 已完成的批次（按时间）

1. `e27e0c56` … `5db5b20a`：D4 全 KPM 移除（141 文件 / -15908 行），已合入 fresh-main
2. `9ff8a36e`：kstorage 写竞态 double-free、list_ids 锁内 copy_to_user、kp_su_load_config 孤儿、kpim/kptd 死载荷
3. `c3c0f85b`：kp_task_su 无锁换 cred、SU 档语义拍板 B、死宏 52→5
4. `00db89c5`：三条审美项
5. `7db60789` + 后续：死码清理（3 死文件 / 9 死函数 / Rust 死项 / 33×2 死字符串 / LSPlt docs 2.3MB）
6. `6190281e` + `f582aeea`：发现 #1 —— kp_task_su 改为 task_work 在目标上下文执行

## 未结项（下一轮从这里接）

- 主 AI 已验收"任务一（死码清尾）"，已开启"任务二（全库质量 + Bug 解析）"
- 发现 #1 已修并回报，等主 AI 三遍审计的判决
- 待判：`sctx` 参数目前仍被忽略（`(void)sctx`），是否要连同"转域失败回退 kernel cred"一起落地
- 扫描分工：我扫 ksud + manager + apatch，主 AI 扫 kernel 栈，P1-P4 分级上报

## 防误删铁律（血泪）

- `XcGlassKernel.kt`、`ObserveAsEvents.kt`、`XecPreload.kt`（manifest zygotePreloadName）、`DragGestureInspector.kt`（顶层 `inspectDragGestures`）、`OS3BgFrag.kt`（顶层 `OS3_BG_FRAG`）都**不是**死码，已各自被主 AI 撤回误判
- `settings_themes_active`、`xec_widget_*` 被 `res/layout/xec_score_widget.xml` 与 `res/xml/xec_score_widget_info.xml` 以 `@string/` 引用，不是死字符串
- `kernel_profiles/*.conf`、webview 三个 css、Lens/Vibrancy/ClearGlassShader、`KSU_SUSFS_*` Kconfig、`lkm_image*`/`boot_patch.rs` 的 38 个 `#[test]` 资产——都不许删

## 探针盲区（主 AI 已登记 16 条，重点几条）

- 常量名 ≠ 文件名（`OS3_BG_FRAG`）
- 扩展函数接收者形态（`fun PointerInputScope.name()`）
- 表达式体函数（`fun x() = ...`，没有花括号 → 块匹配删除会越界吞掉下一个声明；`valueListOf` 连坐删掉 `asValueMap` 就是这类）
- 死字符串探针语料必须包含 `res/**/*.xml` 的 `@string/` 引用与 manifest 非组件属性
- 删函数后必须回扫孤儿 KDoc/注解（`@Composable` 悬空会导致 "Expecting a top level declaration"）

## 已固化的守卫

- `scripts/check_no_kpm.py`：全库反向守卫（BANNED_PATHS + `xdcv1` token + 只放 keybox.xml），已挂进 `build-xecpro.yml` 的 `guards` job
- 本地 `C:\Users\Lenovo\Desktop\kernelSU\_scratch\run_all_checks.sh` 已把三个旧 KPM 守卫换成这一个

## ZCode 驱动（本机）

- ZCode 必须以 `--remote-debugging-port=9222` 启动（先 `Remove-Item Env:ELECTRON_RUN_AS_NODE`）
- 驱动脚本：`C:\Users\Lenovo\Desktop\kernelSU\_scratch\zcode_cdp.py`
  - `fill <文本>` 后**点发送按钮**（`eval` 找 `/发送|Send/` 的 button 并 click）；纯回车在多行文本下会变成换行
  - `status` 看 `stopButtons` 判断是否还在生成
  - `dump` 把整屏写到 `zcode_screen.txt`，再读文件（控制台是 GBK，直接打印会乱码）
- 主 AI 会话：`sess_f8cf8410-46f7-44e5-b9a9-a509dbd9daad`（ZCode 左侧"每天0点00自动开工…"那条）
