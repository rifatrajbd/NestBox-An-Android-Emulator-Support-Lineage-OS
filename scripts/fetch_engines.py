#!/usr/bin/env python3
"""Fetches the two guest engines' native libraries into app/src/main/jniLibs/arm64-v8a.

Modern mode  = Wayondroid (github.com/yoro1836/wayondroid, GPL-3.0; tawcroot is MIT).
Classic mode = Twoyi 0.5.4 (github.com/twoyi/twoyi, MPL-2.0; libOpenglRender.so is a closed blob).

Twoyi's libraries have "/data/data/io.twoyi" built in. They are rewritten byte for byte to
"/data/data/app.nbox" (Nestbox's applicationId), which is exactly the same length.

libtawcroot.so and libvine_helper.so are NOT taken from the APK: NestBox builds them from
Wayondroid's source with its own patches (scripts/build_tawcroot.sh, scripts/build_helper.sh).

Usage:  python -I scripts/fetch_engines.py [download-dir]
        bash scripts/build_tawcroot.sh <wayondroid-a45f80b-source> <ndk-28.2.13676358>
        bash scripts/build_helper.sh <wayondroid-a45f80b-source> <android-sdk>
"""
import hashlib
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"

WAYONDROID = (
    "https://github.com/yoro1836/wayondroid/releases/download/dev/wayondroid-debug.apk",
    "785ad82a1b135e7b85419c17b5098337a1f339847d8a837749d03474bda7207e",
)
TWOYI = (
    "https://github.com/twoyi/twoyi/releases/download/0.5.4/twoyi_0.5.4-03211927-release.apk",
    "ca5e25b3d1852c3b9df8f776f5798269de3c3c40fbf4b92c3768f483ee1a71b5",
)

# Wayondroid libraries Nestbox needs (its AndroidX helpers come from Nestbox's own dependencies).
WAYONDROID_LIBS = [
    "libbinderd.so", "libc++_shared.so", "libgfxstream_backend.so",
    "libvine_gralloc.so", "libvine_hal_service.so", "libvine_kumquat.so",
    "libvine_lmkd.so", "libvine_mediascan.so", "libvine_pulse.so", "libvine_runtime.so",
    "libvine_vk_gfxstream.so", "libvine_vold_shim.so", "libvine_wl.so",
]
TWOYI_LIBS = ["libtwoyi.so", "libOpenglRender.so", "libloader.so"]

OLD = b"/data/data/io.twoyi"
NEW = b"/data/data/app.nbox"
assert len(OLD) == len(NEW)


def fetch(url: str, sha: str, dest: Path) -> Path:
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == sha:
        return dest
    print(f"downloading {url}")
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
    got = hashlib.sha256(tmp.read_bytes()).hexdigest()
    if got != sha:
        raise SystemExit(f"checksum mismatch for {url}: {got}")
    tmp.replace(dest)
    return dest


def extract(apk: Path, names, patch: bool):
    with zipfile.ZipFile(apk) as z:
        for name in names:
            data = z.read(f"lib/arm64-v8a/{name}")
            if patch:
                n = data.count(OLD)
                data = data.replace(OLD, NEW)
                print(f"  {name}: {n} path(s) rewritten")
            (OUT / name).write_bytes(data)


def main():
    dl = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build" / "engines"
    dl.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    w = fetch(*WAYONDROID, dl / "wayondroid-debug.apk")
    t = fetch(*TWOYI, dl / "twoyi-0.5.4.apk")
    print("Wayondroid:")
    extract(w, WAYONDROID_LIBS, patch=False)
    print("Twoyi:")
    extract(t, TWOYI_LIBS, patch=True)
    print(f"wrote {len(WAYONDROID_LIBS) + len(TWOYI_LIBS)} libraries to {OUT}")


if __name__ == "__main__":
    main()
