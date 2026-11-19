# KMI module payloads

The Android xudc build can embed two independent upstream module release ZIPs.
Run `python3 .github/scripts/stage-kmi-modules.py` at the repository root to
populate this directory before compiling xudc. The device does not fetch
anything. On Android 10 or newer, a normal LKM boot prepares ReZygisk and
HMA-OSS before the module policies and boot scripts run. After Android
finishes booting, PackageManager installs the bundled HMA-OSS manager for
user 0 if it isn't already installed. Existing installs are preserved, and
removing a provisioned module does not cause it to be reinstalled on boot.
Only the XECKernel userspace carries these payloads; a standalone kernel or
`kernelsu.ko` does not.

| Module | Release | Corresponding source |
| --- | --- | --- |
| ReZygisk | [v1.0.0](https://github.com/PerformanC/ReZygisk/releases/tag/v1.0.0) | [333d423cde1a959958d9fce380bd017cf6c1cf64](https://github.com/PerformanC/ReZygisk/tree/333d423cde1a959958d9fce380bd017cf6c1cf64), including the submodules pinned by that tree |
| HMA-OSS | [oss-177](https://github.com/frknkrc44/HMA-OSS/releases/tag/oss-177) | [0f4618be83c034c3a4935f4c33825c948ce171b3](https://github.com/frknkrc44/HMA-OSS/tree/0f4618be83c034c3a4935f4c33825c948ce171b3), including the submodules pinned by that tree |

The archives are byte-for-byte upstream releases. The manager APK carries
the [upstream notices and license texts](../../../manager/app/src/main/assets/kmi-modules/NOTICE.md).
The repository's build workflows verify both ZIPs in the repacked APK.
