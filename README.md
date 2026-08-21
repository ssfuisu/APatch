<div align="center">
<a href="https://github.com/ssfuisu/APatch/releases/latest"><img src="https://images.weserv.nl/?url=https://raw.githubusercontent.com/ssfuisu/APatch/main/app/src/main/ic_launcher-playstore.png&mask=circle" style="width: 128px;" alt="logo"></a>

<h1 align="center">APatch (Custom Enhanced Fork)</h1>

[![Latest Release](https://img.shields.io/github/v/release/ssfuisu/APatch?label=Release&logo=github)](https://github.com/ssfuisu/APatch/releases/latest)
[![GitHub License](https://img.shields.io/github/license/ssfuisu/APatch?logo=gnu)](/LICENSE)

</div>

The patching of Android kernel and Android system with enhanced security and privacy features.

- Next-generation kernel-based root solution for Android devices.
- APM: Full support for modules similar to Magisk and KernelSU.
- KPM: Support for KernelPatch Modules allowing kernel-space function inline hooks and syscall table hooks.
- App Cloaking: Seamless on-device manager repackaging and concealment under randomized package names.
- Play Integrity & Bootloader Spoofing: Built-in kernel and property level verified boot state spoofing (green / locked).
- SELinux Mode Control & Spoofing: Live runtime toggling between Enforcing and Permissive, with deep spoofing protection.
- Root & System File Cloaking: Automatic isolation of /data/adb and su binaries from unauthorized applications.
- SuperKey Authentication: Secure kernel supervisor elevation without hardcoded locks.

## Supported Versions

- Architecture: ARM64 (aarch64)
- Android Kernel Versions: Linux 3.18 through 6.12+

## Requirements

Kernel configurations:
- CONFIG_KALLSYMS=y and CONFIG_KALLSYMS_ALL=y
- CONFIG_KALLSYMS=y and CONFIG_KALLSYMS_ALL=n (Supported)

## Installation & Releases

Download the latest signed release APK directly from the [Releases Section](https://github.com/ssfuisu/APatch/releases/latest).

## Credits

- KernelPatch (bmax121): Core kernel engine
- Magisk (topjohnwu): Policy utilities and libsu
- KernelSU (tiann): Base architecture and module support

## License

APatch is licensed under the GNU General Public License v3 (GPL-3).
