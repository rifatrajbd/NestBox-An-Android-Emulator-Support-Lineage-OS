# NestBox

Run a second Android inside an app on your phone — **no root**. NestBox is a virtual-machine
manager in the style of VMOS, tuned for Xiaomi phones on HyperOS, with two guest engines:

| Mode | Engine | Guest system | Machines at once |
|---|---|---|---|
| **Modern** | [Wayondroid](https://github.com/yoro1836/wayondroid) (tawcroot + userspace binder + gfxstream GPU) | LineageOS 20 · Android 13 (+ Google apps) | several |
| **Classic** | [Twoyi](https://github.com/twoyi/twoyi) 0.5.4, improved | Android 8.1 | one |

> **Status: early alpha.** Tested on one phone: Redmi Note 14 Pro+ (Snapdragon 7s Gen 3, Adreno 810,
> 12 GB, Android 16, HyperOS 3). The Modern engine boots to the home screen there. Expect rough edges.

## Features

- Machines in their own background processes with a foreground notification (Stop button), so HyperOS is less likely to kill them
- Detects when HyperOS kills a machine and offers **Resume**
- Full-screen guest, VMOS-style: phone back goes to the guest, **tap with three fingers to minimize**; the machine card keeps the last screen with **Enter** and **Power off**
- New-machine wizard: mode, system (download or import your own file), preset (Battery / Balanced / Performance), resolution, root, LSPosed, Google services
- Xiaomi setup screen: autostart, battery, Lock in Recents, floating windows, and the background process limit (turned off for you over Wireless debugging — no PC)
- GPU acceleration for Modern machines (gfxstream on the phone's Vulkan driver)
- Low memory mode for Modern machines (Android Go settings, smaller app heaps, fewer cached apps)
- Failed boots save the guest's logs to `/sdcard/Nestbox/logs` automatically
- Delete machines and free their storage

Planned, not done yet: floating window and floating ball, APK/file/clipboard bridge, spoof profiles and joystick, snapshots and `.nbx` export, thermal throttling.

## Requirements

**Phone:** arm64 Android 10 or newer (developed on Android 16 / HyperOS 3), Wi-Fi for the one-time
Wireless debugging pairing, and free storage: about **6 GB** per Modern machine, **1.5 GB** per Classic machine.

**Build machine** (Windows or Linux):

- JDK 17
- Android SDK: platform 35, build-tools 35.0.0, platform-tools
- Android NDK **28.2.13676358** (r28c) — needed to build `libtawcroot.so`
- Python 3 and Bash (Git Bash works on Windows)

## Build

```bash
git clone https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS.git
cd NestBox-An-Android-Emulator-Support-Lineage-OS

# 1. Point Gradle at your SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. Engine libraries (downloaded from the Wayondroid and Twoyi releases, checked by SHA-256)
python3 scripts/fetch_engines.py

# 3. libtawcroot.so, built from Wayondroid's source with NestBox's HyperOS fix
curl -L -o wayondroid.tar.gz https://codeload.github.com/yoro1836/wayondroid/tar.gz/a45f80b
mkdir -p build/src && tar -xzf wayondroid.tar.gz -C build/src
bash scripts/build_tawcroot.sh build/src/wayondroid-a45f80b /path/to/Android/Sdk/ndk/28.2.13676358

# 4. App
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The engine binaries are not stored in this repository; steps 2 and 3 put them in
`app/src/main/jniLibs/arm64-v8a/`. Without them the app still builds, but machines cannot start on a
phone. x86_64 emulators always use a built-in demo engine, which is handy for UI work.

## First run

1. **Xiaomi setup** opens first. Tap **Fix** on each row:
   - *Autostart*, *Battery: No restrictions* and *Floating windows* open the matching HyperOS screens.
   - *Lock in Recents*: open Recents, long-press the NestBox card, tap the lock.
   - *Background process limit*: Android stops apps that run more than 32 processes; a guest runs 60+.
     Connect to Wi-Fi, tap **Open Wireless debugging**, turn it on, tap **Pair device with pairing code**,
     and type the 6-digit code into the **NestBox notification**. If HyperOS refuses the change, turn on
     **USB debugging (Security settings)** in Developer options and try again.
2. Allow notifications and storage when asked (storage is used for boot logs).

## Using it

- **New machine** → pick **Modern** or **Classic** → pick a system:
  - Modern: *Android 13 + Google* (1.2 GB download) or *Import ROM* (a Wayondroid `.vrom`)
  - Classic: *Android 8.1 Geek* (0.2 GB download) or *Import rootfs* (`rootfs.7z` from a Twoyi release)
- The first start downloads and unpacks the system, then boots. The first boot of Android 13 takes
  several minutes while it compiles apps; later boots are faster.
- In the machine: **three-finger tap** to minimize. Back from the card returns to the list.
- **Settings**: on-screen buttons, screen fit, GPU acceleration, low memory mode, guest internet, boot logs.

### Using less memory

- Prefer the **Balanced** preset and **720×1600** resolution.
- Keep **Low memory mode** on (Settings › Engine). It applies from the machine's second start.
- The RAM figure in the app is real memory use (PSS) of the machine's processes.

## Troubleshooting

When a machine fails to start, NestBox saves a folder to `/sdcard/Nestbox/logs/<machine>-<time>/`
(or `Android/data/app.nbox/files/logs` without storage permission) with the guest's logcat, crash
reports, kernel log and engine logs. Open an issue with that folder.

Known gaps: no camera, no 32-bit guest apps, only one Classic machine at a time, Android 4.4 and
other 32-bit systems cannot run (the supported phones have no 32-bit CPU mode).

## How it works

- `engine/ServiceEngine` — the UI's engine: starts one `VmService` process per machine (`:vm1`…`:vm4`) and talks to it over a `Messenger`
- `engine/modern` — drives Wayondroid's rootless helper (`libvine_helper.so --rootless`) over its line protocol; the display comes from `libvine_runtime.so`
- `engine/classic` — Twoyi's `libtwoyi.so` + renderer. Twoyi has `/data/data/io.twoyi` built in; NestBox rewrites it byte for byte to `/data/data/app.nbox` (same length), which is why the package name is `app.nbox` and `targetSdk` is 28
- `adb/` — in-app Wireless debugging pairing ([libadb-android](https://github.com/MuntashirAkon/libadb-android)) to lift the background process limit
- `scripts/patch_tawcroot.py` — the HyperOS fix: hides fd entries a process cannot `fstat` from its own `/proc/self/fd` listing (zygote aborted with "Unable to stat 4")
- `design/` — the original UI design and spec (open the `.dc.html` in a browser)

## Credits and licenses

NestBox is licensed under the **GNU GPL v3.0** (see `LICENSE`), because it builds on Wayondroid.

- [Wayondroid](https://github.com/yoro1836/wayondroid) — GPL-3.0; its tawcroot runtime is MIT
- [Twoyi](https://github.com/twoyi/twoyi) — MPL-2.0; `libOpenglRender.so` is a closed binary from Twoyi's release and is downloaded, not redistributed
- [Waydroid](https://waydro.id) / [LineageOS](https://lineageos.org) — the Android 13 system images
- [libadb-android](https://github.com/MuntashirAkon/libadb-android), [Conscrypt](https://github.com/google/conscrypt), [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), [XZ for Java](https://tukaani.org/xz/java.html)
- Fonts: Roboto Flex and JetBrains Mono (SIL Open Font License)

Some apps (banking, Play Integrity) detect virtual environments and will not run. Spoofing features
may break the terms of some games and services; use them responsibly.
