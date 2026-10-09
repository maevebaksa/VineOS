# Rootless runtime provenance and redistribution notes

The `rootless-backend` branch builds a development APK by combining VineOS with a
QEMU system-emulation runtime, UEFI firmware, and a separately downloaded Android
guest.  These pieces have different licenses.  Do not treat the VineOS license as
relicensing third-party binaries.

## QEMU runtime bundle

The build script `scripts/fetch_rootless_qemu.py` currently pins the Android/arm64
runtime bundle from:

- Repository: `AI2TH/Pockr`
- Commit: `f6d43483cc519987b4048962ea96303d99f3030d`
- Directory: `android/app/src/main/jniLibs/arm64-v8a`

The Pockr application source is MIT licensed and its license is fetched into
`third_party/rootless-qemu/POCKR_LICENSE.txt` at build time.  The bundle contains
QEMU and multiple shared libraries whose licenses are independent of Pockr's MIT
license.  QEMU itself is GPL-licensed software; dependency libraries have their own
licenses.  A public binary release must preserve the applicable notices and satisfy
source-code obligations for the exact binaries being shipped.

For that reason this branch's automated build is explicitly an **experimental
development build**.  Before publishing it as a formal VineOS release, replace or
supplement the prebuilt runtime with a reproducible-from-source QEMU build and ship
a complete third-party notice/source bundle.

## Android guest

The first rootless start downloads the guest image instead of embedding it in the
APK.  The pinned artifact is:

- Project: `jqssun/android-lineage-qemu`
- Release: `v2026.07.08`
- Artifact: `UTM-VM-lineage-23.2-20260708-jqssun-virtio_arm64only.zip`
- SHA-256: `ec1ee6d630248060d531b13b3f3e607288d1a93a21f103522ef6bd342c878394`

That Android/LineageOS image contains software under the Android Open Source Project,
LineageOS, Linux kernel, and other upstream licenses.  VineOS verifies the pinned
archive checksum before extracting its VM disk images.

The selected LineageOS ARM64 virtual-device configuration is multiarch: its primary
ABI is `arm64-v8a`, while its secondary ABI is `armeabi-v7a`/`armeabi`.  QEMU
system-emulates an ARM CPU capable of AArch32 execution, which is the mechanism this
branch uses to support 32-bit ARM Android applications on an arm64-only physical
phone.

## UEFI firmware

The CI workflow installs Ubuntu's `qemu-efi-aarch64` package and copies its AAVMF
code image into the APK as `assets/rootless/efi_code.fd`.  The package's own
copyright/license files remain the authoritative license record for that firmware.

## Network exposure

The QEMU display and ADB forwarding endpoints are bound to `127.0.0.1` only.  The
VNC endpoint intentionally has no password because it is not exposed off-device.
Do not change the bind address to `0.0.0.0` without adding authentication and an
explicit user-facing security warning.
