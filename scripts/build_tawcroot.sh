#!/bin/bash
# Builds Wayondroid's tawcroot (MIT) for arm64 with Nestbox's patch and stages it as
# app/src/main/jniLibs/arm64-v8a/libtawcroot.so. Same flags as Wayondroid's tawcroot/build.sh.
#
# Usage: scripts/build_tawcroot.sh <wayondroid-source-dir> <ndk-dir>
#   source: https://codeload.github.com/yoro1836/wayondroid/tar.gz/a45f80b (the bundled build)
#   patch:  scripts/patch_tawcroot.py is applied to tawcroot/src/syscalls_fd.c first
#           scripts/patch_tawcroot_selinux.py (Android 10 SELinux userspace) after it
set -euo pipefail

SRC="$1/tawcroot"
NDK="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$HERE/../build/tawcroot-aarch64"
JNI="$HERE/../app/src/main/jniLibs/arm64-v8a"

BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$BIN" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC=("$BIN/clang" --target=aarch64-linux-android29)

PY="$(command -v python3 || command -v python)"
grep -q nb_drop_dead_fds "$SRC/src/syscalls_fd.c" || "$PY" -I "$HERE/patch_tawcroot.py" "$SRC/src/syscalls_fd.c"
grep -q SELINUX_DENY_UNKNOWN "$SRC/include/proc_shadow.h" || "$PY" -I "$HERE/patch_tawcroot_selinux.py" "$SRC"

SOURCES=(sysv_sem sysv_shm proc_tcp namespace cgroup mounts uts netemu blocking ports main io strings
  filter filter_build handler dispatch rescue path path_scratch path_fold path_orchestrate path_resolve
  pathcache fdpath linkobjs fastpath stats proc_rewrite proc_shadow identity syscalls_fs syscalls_fd
  gamepad binder owners linkstore dirent_filter syscalls_control chroot shm signal_shadow syscalls_socket
  usercopy loader_elf loader_map loader_stack loader_io_prod loader_exec proctitle exec_state
  exec_handler syscalls_exec supervisor)
ASM=(aarch64_stub aarch64_loader_jump aarch64_fastpath)

CFLAGS=(-O2 -fno-strict-aliasing -fno-stack-protector -fvisibility=hidden -fno-exceptions
  -fno-asynchronous-unwind-tables -ffreestanding -Wall -Wextra -Werror -Wframe-larger-than=1024
  -mno-outline-atomics -I"$SRC/include")
LDFLAGS=(-static -nostdlib -nostartfiles -Wl,--build-id=none -Wl,-z,noexecstack -Wl,-z,relro
  -Wl,--image-base=0x2000000000 -Wl,-soname,libtawcroot.so)

mkdir -p "$OUT_DIR/obj"
objs=()
for s in "${SOURCES[@]}"; do
  "${CC[@]}" "${CFLAGS[@]}" -c "$SRC/src/$s.c" -o "$OUT_DIR/obj/$s.o"
  objs+=("$OUT_DIR/obj/$s.o")
done
for s in "${ASM[@]}"; do
  "${CC[@]}" "${CFLAGS[@]}" -c "$SRC/src/arch/$s.S" -o "$OUT_DIR/obj/$s.o"
  objs+=("$OUT_DIR/obj/$s.o")
done
"${CC[@]}" "${LDFLAGS[@]}" "${objs[@]}" -o "$OUT_DIR/libtawcroot.so"
"$BIN/llvm-strip" "$OUT_DIR/libtawcroot.so"

# Same sanity checks as Wayondroid's build: static EXEC, no interpreter, entry near the base.
"$BIN/llvm-readelf" -h "$OUT_DIR/libtawcroot.so" | grep -q "Type:.*EXEC" || { echo "not EXEC" >&2; exit 1; }
if "$BIN/llvm-readelf" -l "$OUT_DIR/libtawcroot.so" | grep -q INTERP; then echo "has PT_INTERP" >&2; exit 1; fi
cp -f "$OUT_DIR/libtawcroot.so" "$JNI/libtawcroot.so"
echo "staged $(stat -c %s "$JNI/libtawcroot.so") bytes at jniLibs/arm64-v8a/libtawcroot.so"
