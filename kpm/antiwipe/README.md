# antiwipe.kpm

XECKernelPro 的「防格」KPM 模块：只是一个开关模块，守卫逻辑在
`kernel/feature/antiwipe.c` 里（编译进内核但**默认不武装**），本模块通过
kallsyms 解析 `ksu_antiwipe_arm/disarm/trust/*` 并在加载/卸载时调用它们。

因此：
* 不加载 = 内核完全没有拦截（也不会因为守卫把机器卡进重启循环）；
* 卸载 = 立刻解除全部 hook；
* 只有 XECKernelPro 内核导出这些符号，别的内核加载会因符号解析失败而拒绝。

## 构建

需要 KernelPatch SDK 头（`kpatch_src/kernel`）与 NDK clang：

``
clang --target=aarch64-linux-gnu -nostdinc -fno-pic -fno-PIC -fno-pie \\
  -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables \\
  -fno-exceptions -fno-common -mgeneral-regs-only -mno-outline-atomics -O2 \\
  -I <KP>/kernel -I <KP>/kernel/include -I <KP>/kernel/patch/include \\
  -I <KP>/kernel/linux/include -I <KP>/kernel/linux/arch/arm64/include \\
  -I <KP>/kernel/linux/tools/arch/arm64/include \\
  -c -o antiwipe.o antiwipe.c
ld.lld -r -o antiwipe.kpm antiwipe.o
``

产物拷到 `manager/app/src/main/assets/kpm/antiwipe.kpm`；
仓库里已带一份构建好的二进制（5.7KB）。

## 注意

模块只 import 内核符号（`printk`/`_copy_to_user`/`ksu_antiwipe_*`），
不使用 KernelPatch 的 `lib_*`/`kf_*`/`compat_*` 运行时（我们的 KPM 引擎
不提供那张表，用了会在装载期报 unknown symbol）。
