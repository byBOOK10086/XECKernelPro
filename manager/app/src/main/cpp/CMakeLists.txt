
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.18.1)

# 2026-11-08：`kernelsu` → `xcnative`。
# 输出的 .so 名字是 APK 里可见的元数据：任何检测器只要看"这个应用带
# libkernelsu.so"，就等于直接认出了 KernelSU 管理器（比包名黑名单便宜得多）。
# 名字换掉不影响 JNI 符号（符号在 jni.cc 里按 Java_ 前缀导出），只影响产物文件名，
# 所以 Natives.kt 里 loadLibrary 的名字必须同步改，否则启动即 UnsatisfiedLinkError。
project("xcnative")

find_package(cxx REQUIRED CONFIG)
link_libraries(cxx::cxx)

add_library(xcnative
        SHARED
        jni.cc
        ksu.cc
        )

target_include_directories(xcnative PRIVATE .)

find_library(log-lib log)

target_link_libraries(xcnative ${log-lib})

add_library(adbroot SHARED adbroot.cc)
target_link_libraries(adbroot ${log-lib})
