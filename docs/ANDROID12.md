# Android 12 engine (plan)

Goal: an Android 12 machine that boots reliably and feels like VMOS / VM Master, using NestBox's
rootless engine (no root on the phone).

## Why a ROM of our own

VM Master is fast because it runs its own Android builds, made for running inside an app:
container patches, the phone's GPU, and a light system. Nobody publishes such a build for
Android 12 on arm64 (Waydroid made images for Android 10, 11 and 13 only), and generic system
images (GSIs) are phone builds that fail in a container one check at a time (see the removed
Pixel Experience port). So NestBox builds its own: **LineageOS 19.1 (Android 12L), as a
Waydroid-style container ROM**, packed as a `.vrom` the app already boots.

## Phases

1. **Build machine.** WSL 2 with Ubuntu 22.04 on drive C: (needs ~300 GB free, 32 GB RAM, swap).
   `repo` tool, LineageOS build dependencies, ccache.
2. **Source.** LineageOS 19.1 (`repo init -u https://github.com/LineageOS/android.git -b lineage-19.1`,
   about 120 GB to download) plus Waydroid's container trees, ported from their `lineage-18.1`
   branches (Waydroid has no 19.1 branch):
   - `device/waydroid/waydroid`, `vendor/waydroid`, `hardware/waydroid`
   - Waydroid's framework and system patches (`vendor/waydroid/waydroid-patches`), rebased on 19.1
   Target: `lineage_waydroid_arm64_only-userdebug` (64-bit only, like these phones).
3. **First boot, software graphics.** Build `system.img` + `vendor.img`, pack them as a `.vrom`
   (zip with `manifest.json`, the images and their SHA-256), import it in NestBox. Fix boot
   blockers in the ROM itself (init, SELinux, storage), not in the engine.
4. **GPU.** Build ANGLE (`external/angle`) into the system and use Wayondroid's gfxstream Vulkan
   driver and gralloc, the same path that gives Android 13 hardware graphics here.
5. **Speed, VM Master style.** 64-bit only, no bloat, quick first-boot compile; then profile the
   rootless runtime and move the hottest file syscalls into the ROM's own bionic (path
   redirection in the guest's libc instead of a trap per call), and cut the display copy.
6. **Ship.** Add "Android 12" to the system list, mirror the ROM on this repository's `roms`
   release, document the build in this file.

## Risks

- Porting Waydroid's 18.1 trees and patches to 19.1 is weeks of work; each LineageOS build takes
  3-6 hours on a 20-core PC (later builds are faster with ccache).
- The source download is about 120 GB; a slow connection makes phase 2 long.
- Booting is not guaranteed until phase 3 succeeds; the ROM is fixed and rebuilt until it does.
