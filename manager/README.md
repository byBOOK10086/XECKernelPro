# XECKernelPro Manager

Jetpack Compose + Miuix manager for XECKernelPro. The UI ships the Liquid
Glass design, module/repo/theming surfaces, TEE/TA diagnostics and — new in
this branch — the Liquid Home visual layer.

## Build

Requirements:

- JDK 21
- Android SDK 37.0 + Build-Tools 37.0.0
- NDK `29.0.14206865`
- CMake 3.22.1
- A `ksud`/`xudc` binary at
  `app/src/main/jniLibs/arm64-v8a/libxeccli.so` (built from
  `userspace/ksud`; CI does this before packaging)
- `local.properties` pointing at the SDK (never commit it)

```bash
cd manager
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/XECKernelPro_<version>_<code>-debug.apk
```

For a local UI-only compile, `:app:compileDebugKotlin` does not need the daemon
binary; a runtime APK does, because the manager's privileged paths call it.

## Liquid Home (this branch)

- **XEC Score**: local 9-item health score (Root, SELinux, SUSFS, TEE
  injection, daemon, LKM, UAPI, stability, mode), S/A/B/C/D rank.
- **Liquid Hero**: breathing aura, scanline, animated score ring and guard
  days on Home.
- **Status-card appearance**: Palette button opens the image picker plus
  blur/dim sliders; the image is stored through `ModuleBackgrounds` and never
  leaves the app.
- **Share card**: local Canvas PNG (1080×1440), FileProvider + system share.
- **Widget and Quick Settings tile**: `XEC Score` widget and `XEC Hide` tile;
  the one-tap action carries the same intent token as the module deep link and
  opens the existing OneTapHide confirmation flow.
- **Boot reveal**: a short in-app logo ring after the Android 12 splash, then
  Home cards stagger in.

## Layout

```
app/src/main/java/me/weishu/kernelsu/
├── ui/screen/home/          Home, XecHeroCard, XecScore, appearance, share card
├── widget/                  XecScoreWidgetProvider, XecQuickTileService, store
├── ui/design/               XEC Fluid Glass tokens and effects
└── ...
app/src/main/res/xml/        widget provider info, FileProvider paths
app/src/main/res/layout/     remote widget layout
```
