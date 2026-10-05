# 内置模块目录（Built-in Modules）

把需要内置的隐藏模块放在本目录下，每个模块一个子目录，目录名即模块 id。

## 目录结构（与普通模块一致）

```
builtin/
  <module_id>/          # 目录名即模块 id（脚本以 KSU_MODULE=<目录名> 环境执行）
    module.prop          # 可选：仅用于自描述 id / name / version / description
    post-fs-data.sh      # 可选：post-fs-data 阶段执行
    service.sh           # 可选：service 阶段执行
    boot-completed.sh    # 可选：boot-completed 阶段执行
    post-mount.sh        # 可选：post-mount 阶段执行
    sepolicy.rule        # 可选：SELinux 规则
    system.prop          # 可选：系统属性
    initrc/*.rc          # 可选：init.rc 片段
```

## 说明

- 这些模块会被打包进 ksud（RustEmbed），首次开机自动解包到 `/data/adb/ksu/builtin/`。
- 内置模块只执行、不枚举：不会出现在管理器「模块」面板，也不会出现在 `/data/adb/modules/` 下，ADB `ls /data/adb/modules/` 看不到。
- 更新方式：直接替换 `/data/adb/ksu/builtin/<module_id>/` 下的文件即可，无需重新刷入 boot。