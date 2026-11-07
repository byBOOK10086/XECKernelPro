# XECKernelPro Manager

Jetpack Compose + Miuix manager for XECKernelPro. The UI follows the MIUI 8
design logic: a light-grey page under white grouped panels separated by 1px
lines, with square 2–6dp corners and the MIUI orange accent. Module, repo,
theming and TEE/TA surfaces sit on top of that token layer.

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

## Home layout (MIUI 8)

- **Mode card**: the first focus of Home. A 56dp mode badge (LKM / GKI /
  KernelPatch / Magisk), the mode name, one line of state and a running pill;
  collapsing it keeps only that summary, expanding reveals four key/value
  cells (KernelSU version, kernel, UAPI match, daemon). The four device routes
  and the “not installed / unsupported” branches share this one layout.
- **Panels**: system information, security status, overview and quick actions
  are plain white groups whose rows carry title + subtitle + right value.
- **XEC Score** (9 checks: Root, SELinux, SUSFS, TEE injection, daemon, LKM,
  UAPI, stability, mode) is still computed and still drives the home-screen
  widget; its ring no longer sits on Home.
- **Removed with the redesign**: the liquid hero card, the status-card
  appearance editor (image + blur/dim) and the share card. Theme block
  backgrounds (`home.*`) keep working through the token layer.
- **Widget and Quick Settings tile**: `XEC Score` widget and `XEC Hide` tile;
  the one-tap action carries the same intent token as the module deep link and
  opens the existing OneTapHide confirmation flow.
- **Boot reveal**: a short in-app logo ring after the Android 12 splash.

## Layout

```
app/src/main/java/me/weishu/kernelsu/
├── ui/screen/home/          Home, HomeModeCard, XecScore
├── widget/                  XecScoreWidgetProvider, XecQuickTileService, store
├── ui/design/panel/         MIUI 8 panel family (Xc8Panel/Row/SectionLabel…)
├── ui/design/               XEC design tokens, glass effects
└── ...
app/src/main/res/xml/        widget provider info, FileProvider paths
app/src/main/res/layout/     remote widget layout
```
