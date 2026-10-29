#!/bin/bash
# Builds Wayondroid's rootless helper (GPL-3.0) for arm64 with NestBox's patch and stages it as
# app/src/main/jniLibs/arm64-v8a/libvine_helper.so. Same CMake settings as Wayondroid's debug build.
#
# Usage: scripts/build_helper.sh <wayondroid-a45f80b-source> <android-sdk-dir>
#   needs the SDK's cmake;3.22.1 and ndk;28.2.13676358 packages
#   patch: scripts/patch_helper.py (Android 10 GSIs, overlay hooks, shared folder)
#   also builds scripts/nbsu.c (the guest su) as jniLibs/arm64-v8a/libnbsu.so
set -euo pipefail

SRC="$1/app/src/main/cpp"
SDK="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="$SDK/ndk/28.2.13676358"
OUT="$HERE/../build/helper"
JNI="$HERE/../app/src/main/jniLibs/arm64-v8a"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
[ -x "$CMAKE" ] || CMAKE="$SDK/cmake/3.22.1/bin/cmake.exe"
NINJA="$SDK/cmake/3.22.1/bin/ninja"
[ -x "$NINJA" ] || NINJA="$SDK/cmake/3.22.1/bin/ninja.exe"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$BIN" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
PY="$(command -v python3 || command -v python)"

"$PY" -I "$HERE/patch_helper.py" "$SRC/rootless"
"$CMAKE" -S "$SRC" -B "$OUT" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Debug "-DCMAKE_CXX_FLAGS=-std=c++17 -Wall -Wextra -Wpedantic" >/dev/null
"$CMAKE" --build "$OUT" --target vine_helper
"$BIN/llvm-strip" -o "$JNI/libvine_helper.so" "$OUT/libvine_helper.so"
echo "staged $(stat -c %s "$JNI/libvine_helper.so") bytes at jniLibs/arm64-v8a/libvine_helper.so"
"$BIN/clang" --target=aarch64-linux-android29 -O2 -fPIE -pie -Wall -o "$OUT/nbsu" "$HERE/nbsu.c"
"$BIN/llvm-strip" -o "$JNI/libnbsu.so" "$OUT/nbsu"
echo "staged $(stat -c %s "$JNI/libnbsu.so") bytes at jniLibs/arm64-v8a/libnbsu.so"
