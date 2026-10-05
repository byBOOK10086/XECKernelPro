# KPM 占位页签设计

日期：2026-09-09
状态：已确认

## 目标

在 XECKernelPro 管理器的底部导航栏新增「KPM」页签，作为纯展示占位页，为后续内核补丁模块功能预留入口。页签位于「模块」与「设置」之间。

## 范围

- 仅做空态占位页（无真实内核模块功能）。
- 空态内容：居中提示「暂无内核补丁模块」，无可点击按钮。
- 不修改内核侧任何逻辑。

## 决策记录

- 功能方向：纯展示占位页（方案 A）。
- 空态形式：纯空态页，无按钮（方案 A1）。
- 图标：`Memory`（内存芯片）。
- 页签名：`KPM`。

## 改动清单

1. `manager/app/src/main/java/me/weishu/kernelsu/ui/component/bottombar/BottomBarMiuix.kt`
   - `BottomBarDestination` 枚举新增 `Kpm` 项，插在 `Module` 与 `Setting` 之间。
   - 图标：`Icons.Rounded.Memory`。

2. `manager/app/src/main/java/me/weishu/kernelsu/ui/component/bottombar/BottomBarMaterial.kt`
   - 硬编码 `items` 列表插入 KPM 项（`Icons.Filled.Memory` / `Icons.Outlined.Memory`）。

3. `manager/app/src/main/java/me/weishu/kernelsu/ui/component/bottombar/NavigationRailMaterial.kt`
   - 硬编码 `items` 列表插入 KPM 项（同上图标）。

4. `manager/app/src/main/java/me/weishu/kernelsu/ui/viewmodel/MainActivityViewModel.kt`
   - `MainPagerConfig.PAGE_COUNT` 由 4 改为 5。

5. `manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt`
   - `HorizontalPager` 的 `when(page)` 分支插入 KPM 页，设置页索引由 3 改为 4。

6. 新建 `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/kpm/KpmScreen.kt`
   - 包含 Material 与 Miuix 两套空态页 Composable。
   - 顶部标题栏显示「KPM」，正文居中空态文案。

7. 文案资源（`values/strings.xml` 与 `values-zh-rCN/strings.xml`）
   - `kpm` = `KPM`
   - `kpm_empty`：中文「暂无内核补丁模块」，英文「No kernel patch module installed」。

## 导航顺序

主页 → 超级用户 → 模块 → KPM → 设置

## 页面结构

```
KpmScreen
├── Miuix 分支：Scaffold + TopAppBar(标题 KPM) + 居中 Text(kpm_empty)
└── Material 分支：Scaffold + TopAppBar(标题 KPM) + 居中 Text(kpm_empty)
```

## 非目标

- 不实现 `.kpm` / 内核模块的安装、启用、禁用、自启。
- 不修改内核侧、ksud、ksuinit。
- 不新增任何依赖。

## 验证

- 编译通过（GitHub Actions 或本地 Gradle）。
- 真机安装后底部导航显示 5 个页签，KPM 位于模块与设置之间。
- KPM 页显示标题「KPM」与空态文案，无异常。
