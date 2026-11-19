# KMI module payloads

The Android xudc build can embed two independent upstream module release ZIPs.
Run `python3 .github/scripts/stage-kmi-modules.py` at the repository root to
populate this directory before compiling xudc. The device does not fetch
anything: it stages ReZygisk and then HMA-OSS via the regular module installer
after a successful normal LKM boot on Android 10 or newer. A subsequent
reboot activates the staged modules. Existing installs are preserved, and
removing a provisioned module does not cause it to be reinstalled on boot.
Only the XECKernel userspace carries these payloads; a standalone kernel or
`kernelsu.ko` does not.

| Module | Release | Corresponding source |
| --- | --- | --- |
| ReZygisk | [v1.0.0](https://github.com/PerformanC/ReZygisk/releases/tag/v1.0.0) | [333d423cde1a959958d9fce380bd017cf6c1cf64](https://github.com/PerformanC/ReZygisk/tree/333d423cde1a959958d9fce380bd017cf6c1cf64), including the submodules pinned by that tree |
| HMA-OSS | [oss-175](https://github.com/frknkrc44/HMA-OSS/releases/tag/oss-175) | [fe7a36799f040a79c666fb05b8479e69854656df](https://github.com/frknkrc44/HMA-OSS/tree/fe7a36799f040a79c666fb05b8479e69854656df), including the submodules pinned by that tree |

The archives are byte-for-byte upstream releases. The manager APK carries
the [upstream notices and license texts](../../../manager/app/src/main/assets/kmi-modules/NOTICE.md).
The repository's build workflows verify both ZIPs in the repacked APK.
