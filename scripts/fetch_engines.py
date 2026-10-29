#!/usr/bin/env python3
"""Fetches the guest engine's native libraries into app/src/main/jniLibs/arm64-v8a.

Engine = Wayondroid (github.com/yoro1836/wayondroid, GPL-3.0; tawcroot is MIT).

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

# Wayondroid libraries Nestbox needs (its AndroidX helpers come from Nestbox's own dependencies).
WAYONDROID_LIBS = [
    "libbinderd.so", "libc++_shared.so", "libgfxstream_backend.so",
    "libvine_gralloc.so", "libvine_hal_service.so", "libvine_kumquat.so",
    "libvine_lmkd.so", "libvine_mediascan.so", "libvine_pulse.so", "libvine_runtime.so",
    "libvine_vk_gfxstream.so", "libvine_vold_shim.so", "libvine_wl.so",
]


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


def extract(apk: Path, names):
    with zipfile.ZipFile(apk) as z:
        for name in names:
            (OUT / name).write_bytes(z.read(f"lib/arm64-v8a/{name}"))


def main():
    dl = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build" / "engines"
    dl.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    w = fetch(*WAYONDROID, dl / "wayondroid-debug.apk")
    extract(w, WAYONDROID_LIBS)
    print(f"wrote {len(WAYONDROID_LIBS)} libraries to {OUT}")


if __name__ == "__main__":
    main()
