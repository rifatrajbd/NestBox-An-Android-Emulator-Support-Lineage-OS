<div align="center">

<img src="docs/logo.svg" alt="NestBox logo" width="112" />

# NestBox

### A second Android inside your phone — no root, no PC

[![Latest release](https://img.shields.io/github/v/release/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS?include_prereleases&label=release&color=4C8DFF)](https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS/releases)
[![Downloads](https://img.shields.io/github/downloads/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS/total?color=4C8DFF)](https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS/releases)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#-device-compatibility)
[![Guest](https://img.shields.io/badge/guest-LineageOS%2020%20%C2%B7%20Android%208.1-1f2f4d)](#-two-engines)
[![License: GPL v3](https://img.shields.io/badge/License-GPL_v3-blue.svg)](LICENSE)
[![Stars](https://img.shields.io/github/stars/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS?style=flat&logo=github&color=ffb300)](https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS/stargazers)

</div>

NestBox runs full virtual Android machines inside a normal app, in the style of VMOS. It boots
**LineageOS 20 (Android 13) with Google apps** on the phone's own GPU, or a light **Android 8.1**,
and is tuned to survive Xiaomi's HyperOS, which closes background apps aggressively.

> [!WARNING]
> **Early alpha.** Tested on one phone so far (Redmi Note 14 Pro+, HyperOS 3). Expect bugs, and
> do not keep anything important only inside a machine yet.

## 📑 Contents

- [Features](#-features)
- [Screenshots](#-screenshots)
- [Two engines](#-two-engines)
- [Device compatibility](#-device-compatibility)
- [Download and install](#-download-and-install)
- [First-time setup](#-first-time-setup)
- [Using NestBox](#-using-nestbox)
- [Build from source](#-build-from-source)
- [FAQ and troubleshooting](#-faq-and-troubleshooting)
- [Roadmap](#-roadmap)
- [Credits and license](#-credits-and-license)

## ✨ Features

- **No root needed** — machines run as normal app processes on the phone's own kernel
- **Android 13 with Google Play**, or **Android 8.1** for light use, chosen per machine
- **GPU acceleration** through gfxstream on the phone's Vulkan driver (Adreno tested)
- **Full-screen machines, VMOS-style** — no controls on top; tap with **three fingers** to minimize
- **Machine cards** keep the last screen with **Enter** and **Power off**
- **Several machines at once** (Modern mode), each in its own process with a Stop notification
- **Survives HyperOS** — kill detection with **Resume**, plus a guided Xiaomi setup
- **Lifts Android's 32-process limit by itself** through in-app Wireless debugging pairing, no PC
- **Low memory mode** — Android Go settings, smaller app heaps and fewer cached apps
- **Presets** instead of sliders: Battery (pauses when minimized), Balanced, Performance
- **Boot logs saved automatically** to `/sdcard/Nestbox/logs` when a machine fails to start
- **Download or import** systems, and delete machines to free their storage
- **Root inside the machine** (the phone stays unrooted): `su` for apps and shells
- **Transfer** — copy installed phone apps (split APKs too) or any APK into a machine, and a
  shared folder: phone `/sdcard/Nestbox` ↔ guest `/sdcard/Host`
- **Spoof** — region (time zone, language, carrier), device model, and location (Android 12+)

## 📸 Screenshots

<p align="center">
  <img src="docs/screenshots/home.png" width="23%" alt="Machines" />
  <img src="docs/screenshots/new-machine.png" width="23%" alt="New machine: Modern or Classic" />
  <img src="docs/screenshots/machine.png" width="23%" alt="Minimized machine with Enter and Power off" />
  <img src="docs/screenshots/setup.png" width="23%" alt="Xiaomi setup" />
</p>
<p align="center">
  <img src="docs/screenshots/boot.png" width="23%" alt="Boot console" />
  <img src="docs/screenshots/settings.png" width="23%" alt="Settings" />
</p>

<p align="center"><sub>Machines · New machine · Minimized machine · Xiaomi setup · Boot console · Settings (UI shown on an emulator)</sub></p>

## 🧩 Two engines

Pick the mode when you create a machine.

| | **Modern** | **Classic** |
|---|---|---|
| Engine | [Wayondroid](https://github.com/yoro1836/wayondroid): tawcroot runtime, userspace binder, gfxstream | [Twoyi](https://github.com/twoyi/twoyi) 0.5.4, improved |
| Guest | LineageOS 20 · Android 13 (+ Google apps), or **Pixel Experience Plus 10** (port) | Android 8.1 (root built in) |
| Download | 1.2 GB (Android 13) · 0.9 GB (Pixel Experience) | 0.2 GB |
| Storage per machine | ~6 GB | ~1.5 GB |
| Machines at once | several | one |
| Best for | modern apps, Play Store | light apps, older devices' apps |

## 📱 Device compatibility

| Phone | Status |
|---|---|
| Redmi Note 14 Pro+ · Snapdragon 7s Gen 3 · Adreno 810 · HyperOS 3 (Android 16) | ✅ Modern boots and runs |
| Other Snapdragon (Adreno) phones | ❔ untested, expected to work |
| MediaTek / Exynos (Mali) phones | ❔ untested; GPU path not tried |
| x86_64 emulators | 🧪 demo engine only (UI testing) |

**Minimum:** arm64 phone, Android 10, 6 GB RAM, 8 GB free storage.
**Recommended:** 12 GB RAM, Adreno GPU, Wi-Fi for the one-time setup.

> [!NOTE]
> Android 4.4 and other 32-bit systems cannot run: recent phones like the Snapdragon 7s Gen 3
> have no 32-bit CPU mode.

## ⬇️ Download and install

1. Download `NestBox-<version>-debug.apk` from [Releases](https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS/releases).
2. Open it on the phone and allow installing from your browser or file manager.
   On HyperOS, accept the security scan prompt.
3. Open NestBox and follow the Xiaomi setup below.

The app id is `app.nbox`. Debug builds are signed with a debug key, so updates install over each other.

## 🛠 First-time setup

The **Xiaomi setup** screen opens on first launch. Tap **Fix** on each row:

| Row | What to do |
|---|---|
| Autostart | Turn it on in the screen that opens |
| Battery: No restrictions | Choose *No restrictions* |
| Lock in Recents | Open Recents, long-press the NestBox card, tap the lock |
| Floating windows | Allow *Display pop-up windows* |
| Background process limit | See below |

**Background process limit.** Android stops apps that run more than 32 processes, and a guest runs
60 or more. NestBox turns the limit off through Wireless debugging on the phone itself:

1. Connect to Wi-Fi and tap **Open Wireless debugging**.
2. Turn Wireless debugging on and tap **Pair device with pairing code**.
3. Type the 6-digit code into the **NestBox notification**.

> [!TIP]
> If HyperOS refuses the change, turn on **USB debugging (Security settings)** in Developer options
> and pair again.

Allow notifications and storage when asked; storage is only used for boot logs.

## 🚀 Using NestBox

- **New machine** → **Modern** or **Classic** → system → preset and resolution → features → **Create and start**.
- The first start downloads and unpacks the system. The first Android 13 boot takes several
  minutes while it compiles apps; later boots are much faster.
- **Inside a machine:** the phone's back gesture goes to the guest. **Tap with three fingers** (or
  press Home) to minimize.
- **Machine card:** last screen, live RAM and temperature, **Enter**, **Power off**, and below that
  system switches, performance and maintenance (including **Delete machine**).

**Pixel Experience Plus 10 (port, experimental).** Pick it in the Modern system list. NestBox
builds it on the phone: it downloads the community Pixel Experience Plus 10 generic system image
(GSI) and Waydroid's Android 10 vendor, converts the image, removes apps that do nothing in a VM
(dialer, camera, NFC, face unlock, setup wizard, Google app, Photos, Messages) and tunes it for
speed (quick first-boot compile, no boot animation, half-length animations). Android 10 has no
ANGLE, so it renders in software (SwiftShader). It needs about 8 GB of free storage.

**Transfer** (machine card › Transfer, machine running):
- **Apps:** **Copy** installs a phone app in the machine; **Install APK from storage** takes any APK.
- **Files:** put files in the phone's `/sdcard/Nestbox`; the guest sees them in `/sdcard/Host`.
  Modern machines share the folder live. Classic machines copy new and changed files both ways
  when they boot, when the Files tab opens and when they stop.

**Root:** turn on **Root** on the machine card, then restart the machine. Apps get root with
`su`, inside the machine only. Classic uses Twoyi's built-in Superuser; Modern uses NestBox's `su`.

**Spoof** (machine card › Spoof › Apply to machine): region and location apply right away on a
running machine; the device model and the language apply from its next start. Location needs
Android 12 or later in the guest.

**Using less memory:** choose the **Balanced** preset and **720×1600**, and keep **Low memory mode**
on (Settings › Engine; applies from the machine's second start). The RAM shown is real memory use (PSS).

## 🏗 Build from source

### Requirements

- JDK 17
- Android SDK: platform 35, build-tools 35.0.0
- Android NDK **28.2.13676358** (r28c) and CMake 3.22.1 (SDK packages)
- Python 3 and Bash (Git Bash on Windows)

### Steps

```sh
git clone https://github.com/rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS.git nestbox
cd nestbox
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Engine libraries from the Wayondroid and Twoyi releases (SHA-256 checked)
python3 scripts/fetch_engines.py

# libtawcroot.so from Wayondroid's source, with NestBox's HyperOS fix
mkdir -p build/src
curl -L -o build/wayondroid.tar.gz https://codeload.github.com/yoro1836/wayondroid/tar.gz/a45f80b
tar -xzf build/wayondroid.tar.gz -C build/src
bash scripts/build_tawcroot.sh build/src/wayondroid-a45f80b /path/to/Android/Sdk/ndk/28.2.13676358
# libvine_helper.so with NestBox's Android 10 / overlay patch
# (also builds NestBox's guest su, scripts/nbsu.c, as libnbsu.so)
bash scripts/build_helper.sh build/src/wayondroid-a45f80b /path/to/Android/Sdk

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, write `sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk` and run the commands in Git Bash.
Engine binaries are not stored in the repository; the scripts place them in
`app/src/main/jniLibs/arm64-v8a/`.

<details>
<summary><b>How it works</b></summary>

- `engine/ServiceEngine` — starts one `VmService` process per machine (`:vm1`…`:vm4`) and talks to it over a `Messenger`; a heartbeat file detects HyperOS kills
- `engine/modern` — drives Wayondroid's rootless helper (`libvine_helper.so --rootless`) over its line protocol; the display comes from `libvine_runtime.so`
- `engine/classic` — Twoyi's `libtwoyi.so` and renderer. Twoyi has `/data/data/io.twoyi` built in; NestBox rewrites it byte for byte to `/data/data/app.nbox` (same length), which is why the app id is `app.nbox` and `targetSdk` is 28
- `adb/` — Wireless debugging pairing with [libadb-android](https://github.com/MuntashirAkon/libadb-android) to lift the process limit
- `scripts/patch_helper.py` — Android 10 GSI support (packed and suffixed APEXes, root-level zygote rc, 64-bit-only props), the per-boot overlay (`nestbox.prop`, `nestbox.remove`, `nestbox.files/`) and the shared folder mount
- `bridge/` — app installs, spoofing and the shared folder; Classic machines are reached through their adbd on `127.0.0.1:22122`
- `scripts/rom/` — porting and mirroring tools for system images; see [docs/PORTING.md](docs/PORTING.md) and [roms/catalog.json](roms/catalog.json)
- `scripts/patch_tawcroot.py` — the HyperOS fix: entries a process cannot `fstat` are hidden from its own `/proc/self/fd` listing (zygote aborted with "Unable to stat 4"); unpatched, the build is byte-identical to upstream
- `design/` — the original UI design and spec (open the `.dc.html` in a browser)

</details>

## ❓ FAQ and troubleshooting

<details>
<summary><b>A machine fails to start</b></summary>

NestBox saves a folder to `/sdcard/Nestbox/logs/<machine>-<time>/` (or
`Android/data/app.nbox/files/logs` without storage permission) with the guest's logcat, crash
reports, kernel log and engine logs. Open an issue and attach it.
</details>

<details>
<summary><b>The machine was closed in the background</b></summary>

Finish every row of the Xiaomi setup, especially *Battery: No restrictions*, *Lock in Recents* and the
*Background process limit*. A killed machine shows **Stopped by system** with **Resume**.
</details>

<details>
<summary><b>It uses a lot of memory or feels slow</b></summary>

Use **Balanced** and **720×1600**, keep **Low memory mode** and **GPU acceleration** on, and give the
first boot time to finish compiling apps.
</details>

<details>
<summary><b>Banking apps or some games refuse to run</b></summary>

Apps that check Play Integrity or detect virtual environments will not run in any rootless VM.
</details>

**Known gaps:** no camera, no 32-bit guest apps, one Classic machine at a time.

## 🗺 Roadmap

- [x] Machines, wizard, Xiaomi setup, kill detection and notifications
- [x] Modern (Android 13) and Classic (Android 8.1) engines, GPU, boot logs
- [x] Full-screen VMOS-style view, low memory mode, delete machine
- [x] Root switch (su) in both modes
- [ ] Magisk and LSPosed
- [ ] Floating window and floating ball
- [x] APK and shared-folder transfer
- [ ] Clipboard sync
- [x] Region, device and location spoofing
- [ ] Location joystick
- [ ] Snapshots, `.nbx` export and import, repair and factory reset
- [ ] Health monitor and thermal throttling
- [x] Pixel Experience Plus 10 port for Modern mode
- [ ] Android 11 system for Modern mode

## 🙏 Credits and license

NestBox is licensed under the **[GNU GPL v3.0](LICENSE)** because it builds on Wayondroid.

- [Wayondroid](https://github.com/yoro1836/wayondroid) — GPL-3.0; its tawcroot runtime is MIT
- [Twoyi](https://github.com/twoyi/twoyi) — MPL-2.0; `libOpenglRender.so` is Twoyi's closed renderer
- [Waydroid](https://waydro.id) and [LineageOS](https://lineageos.org) — the Android 13 system images and the Android 10 vendor
- [Pixel Experience](https://github.com/PixelExperience) — Pixel Experience Plus 10; the GSI build comes from the community [AndroidGSI](https://sourceforge.net/projects/androidgsi/) project and is downloaded on the phone, not redistributed
- [libadb-android](https://github.com/MuntashirAkon/libadb-android), [Conscrypt](https://github.com/google/conscrypt), [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), [XZ for Java](https://tukaani.org/xz/java.html)
- Fonts: Roboto Flex and JetBrains Mono (SIL Open Font License)

> [!CAUTION]
> NestBox is a personal project, not affiliated with Xiaomi, VMOS, Waydroid, LineageOS or Google.
> Spoofing features may break the terms of some games and services; use them responsibly.
