# Rootless backend — engineering baseline

Target: stock Android 16, Samsung Galaxy S24 Ultra (arm64-only), no root, optional Shizuku; retain 32-bit ARM application execution.

## Current status

**Not implemented.** This branch tracks rootless engineering; creating it does not make a guest bootable.

The existing native `Container::start()` performs privileged operations *before* `launch_init()`: `LOOP_CTL_GET_FREE`, `LOOP_SET_FD`, `mount(rootfs)`, filesystem mounts, device-node creation, and `binfmt_misc` registration. Merely skipping the `unshare()` failure would leave these operations broken.

`launch_init()` also requires PID/mount/UTS/IPC namespaces, `MS_PRIVATE`, and `pivot_root` or `chroot`. A Shizuku shell process (UID 2000) does not imply `CAP_SYS_ADMIN`; the reported `unshare errno=1` is consistent with that limitation.

## Architectural decision

Do **not** silently fall back to a non-isolated Android guest or pretend that PRoot is a complete Android VM. PRoot can translate paths and emulate some root filesystem behavior, but cannot manufacture the Android Binder driver, guest ServiceManager isolation, virtual display, kernel device nodes, or unrestricted Android init capabilities.

Evaluate two explicit alternatives:

1. **QEMU system emulation** with an appropriate Android guest kernel, guest Binder support, virtual storage, framebuffer and input. This is the most defensible non-root full-guest design, but substantially larger and slower than the current namespace backend. QEMU *user-mode* used for ARM32 translation is not a substitute for QEMU *system-mode*.
2. **PRoot experimental backend** only if an Android-specific guest bootstrap and Binder/device compatibility are demonstrated. Keep it labeled experimental and never advertise full isolation before testing.

An AVF backend is conditional on whether the device exposes the necessary virtualization APIs and permits this app to use them; do not assume availability on stock One UI.

## Implementation gates

- [ ] Add explicit backend interface and selection, preserving the existing privileged backend.
- [ ] Build a non-privileged runtime capability report (kernel, device nodes, available executables, ABI, SELinux context) without requiring Shizuku.
- [ ] Implement a real guest execution backend; no fake-success start return values.
- [ ] Verify boot to Android home screen, touch input, graphics, and audio on physical Android 16 hardware.
- [ ] Verify installation and execution of a 32-bit-only ARM APK; check that QEMU translation is actually invoked.
- [ ] Package reproducible APK CI artifacts; keep rootless builds clearly marked experimental.

## Reproduction / acceptance

On stock Android 16 with Shizuku in ADB-shell mode, Settings → Shizuku → Test namespace access currently reports `unshare_errno=1`. The rootless backend must not invoke privileged namespace or loop-device setup on this path. Passing the probe is **not** the acceptance criterion: a usable guest plus successful ARM32 app launch is.

## Relevant source

- `app/src/main/cpp/container/namespace_manager.cpp`
- `app/src/main/cpp/vine_runtime.cpp`
- `app/src/main/cpp/diagnostics/namespace_probe.cpp`
- `docs/NOROOT_RESEARCH.md`
