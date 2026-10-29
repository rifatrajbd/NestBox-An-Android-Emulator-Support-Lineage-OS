# Porting another Android version to NestBox

This is how Pixel Experience Plus 10 was brought to Modern mode. Use it to port another
generic system image (GSI), for example an Android 11 or 12 ROM.

Modern mode boots a Waydroid-style container without root: a **system** image and a
**vendor** image, unpacked into the machine folder by Wayondroid's rootless engine.
A port pairs a GSI (the ROM you want) with Waydroid's vendor for the same Android version.
The phone does the work when the machine is first started: it downloads both, expands the
GSI from its sparse format and applies NestBox's overlay (props, removed apps, su).

## 1. Pick the two images

| Android | API | Waydroid vendor (arm64) |
| --- | --- | --- |
| 10 | 29 | `lineage-17.1-…-MAINLINE-waydroid_arm64-vendor.zip` |
| 11 | 30 | `lineage-18.1-…-MAINLINE-waydroid_arm64-vendor.zip` |
| 13 | 33 | `lineage-20-…-MAINLINE-waydroid_arm64-vendor.zip` |

Vendors: <https://sourceforge.net/projects/waydroid/files/images/vendor/waydroid_arm64/>

The GSI must be **ARM64**, **A/B** (system-as-root) and shipped as `.img.xz` (sparse or raw
ext4). Phones like the Redmi Note 14 Pro+ have no 32-bit CPU mode, so NestBox makes every port
64-bit only; a 32-bit-only (`arm32_binder64`, `a64`) image cannot work.

Below Android 13 the guest has no ANGLE, so NestBox renders it in software (SwiftShader from
the vendor). Expect lower frame rates than the Android 13 system.

## 2. Run the porting tool

```sh
pip install ext4
python scripts/rom/port_gsi.py --id lin18 --name "LineageOS 18.1" --sdk 30 \
  --system <GSI .img.xz URL> --vendor <Waydroid vendor zip URL>
```

It downloads both files into `build/rom-cache`, checks the vendor zip, expands the system
image, looks inside and prints:

- the Android version, API level, ABI list and zygote of the image,
- what needs special handling (packed APEXes, suffixed APEX folders, root-level zygote rc),
- apps that do nothing in a VM (dialer, camera, NFC, setup wizard, face unlock…),
- a ready `Rom(...)` entry with sizes and SHA-256 for `Catalog.kt`.

It also records the ROM in [`roms/catalog.json`](../roms/catalog.json).

## 3. Add it to the app

Paste the entry into the `roms` list in
[`Catalog.kt`](../app/src/main/java/app/nestbox/vm/Catalog.kt), then review it:

- **`sdk`**: the guest API level. Below 33 the engine turns GPU acceleration off.
- **`props`**: `GSI_64BIT + SOFTWARE_GL + SPEED_PROPS` for Android 10–12;
  `GSI_64BIT + SPEED_PROPS` for 13. Add ROM-specific lines if logs ask for them.
- **`remove`**: the tool's suggestions. Keep the launcher, SystemUI, Settings, the keyboard,
  Play Store and Play services. Removing the setup wizard needs `PORT_AFTER_BOOT`, which marks
  the device as set up.
- **`afterBoot`**: `PORT_AFTER_BOOT` (device set up, half-length animations).

Build and install:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Test on the phone

1. New machine › Modern › your ROM › Create and start.
2. The first start downloads, expands and boots. The first boot compiles apps and is slow.
3. If it fails, NestBox saves logs to `/sdcard/Nestbox/logs/<machine>-<time>/`:
   `kmsg.txt` (init), `guest-logcat.txt`, `engine-logs/`, `tombstones/`.

What usually goes wrong, and where to fix it:

| Symptom in the logs | Fix |
| --- | --- |
| `apexd` cannot mount packages | handled for `.apex` files by `nb_unpack_apexes` in `scripts/patch_helper.py` |
| zygote not started, no `init.zygote64.rc` | handled by `nb_prepare_q` |
| `ro.product.cpu.abilist32` crashes, 32-bit zygote | missing `GSI_64BIT` props |
| SurfaceFlinger cannot load EGL | `SOFTWARE_GL` props, or the vendor is not the image's Android version |
| app crash loop at first boot | remove that app in `remove` |
| `Unable to stat 4` (zygote) | needs NestBox's tawcroot (`scripts/build_tawcroot.sh`) |

Engine-level changes go in `scripts/patch_helper.py`; rebuild with `scripts/build_helper.sh`
from a clean Wayondroid source (the patch refuses to run twice).

## 5. Keep a copy on GitHub

```sh
GITHUB_TOKEN=… python scripts/rom/mirror_roms.py lin18
```

This uploads the ROM's files to this repository's `roms` release. The app tries the original
host first and falls back to that copy (same checksum). Add the file name to `MIRRORS` in
`Catalog.kt` and a `"mirror"` field in `roms/catalog.json`.

> [!WARNING]
> ROMs with Google apps (Play Store, Play services) contain software Google does not license
> for redistribution. Mirroring those publicly is your own decision, and GitHub may remove them.
> ROMs without Google apps are usually fine under their own licences (check each one).
