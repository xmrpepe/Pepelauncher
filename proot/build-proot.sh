#!/bin/sh
# Builds termux/proot (third_party/proot) and talloc (third_party/talloc) with the Android NDK
# and installs them as "native libraries" so that Android extracts them to nativeLibraryDir,
# the only app-writable location from which execve() is allowed for targetSdk >= 29:
#
#   <out>/<abi>/libproot.so          the proot executable
#   <out>/<abi>/libproot-loader.so   proot's ELF loader (PROOT_LOADER)
#   <out>/<abi>/libproot-loader32.so 32-bit loader for running armv7 guests on arm64 (PROOT_LOADER_32)
#
# Usage: build-proot.sh <ndk-dir> <work-dir> <out-dir> <abi>...
# Requirements on the build host: POSIX sh, GNU make, the NDK. Nothing else (no gawk, no waf).
set -eu

NDK=$1
WORK=$2
OUT=$3
shift 3

HERE=$(cd "$(dirname "$0")" && pwd)
API=24
HOST_TAG=$(ls "$NDK/toolchains/llvm/prebuilt" | head -n 1)
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)

triple_for() {
    case "$1" in
        arm64-v8a) echo aarch64-linux-android ;;
        armeabi-v7a) echo armv7a-linux-androideabi ;;
        x86_64) echo x86_64-linux-android ;;
        x86) echo i686-linux-android ;;
        *) echo "unsupported ABI: $1" >&2; exit 1 ;;
    esac
}

build_abi() {
    abi=$1
    cc="$BIN/clang --target=$(triple_for "$abi")$API"
    dir="$WORK/$abi"
    rm -rf "$dir"
    mkdir -p "$dir/talloc" "$OUT/$abi"

    # talloc: just the allocator, with a hand-written config.h for bionic.
    cp "$HERE/talloc-config.h" "$dir/talloc/config.h"
    $cc -O2 -fPIC -I"$dir/talloc" -I"$HERE/third_party/talloc" \
        -I"$HERE/third_party/talloc/lib/replace" \
        -c "$HERE/third_party/talloc/talloc.c" -o "$dir/talloc/talloc.o"
    "$BIN/llvm-ar" rcs "$dir/talloc/libtalloc.a" "$dir/talloc/talloc.o"
    cp "$HERE/third_party/talloc/talloc.h" "$dir/talloc/"

    # proot: build in a private copy of the source tree (its makefile builds in-tree).
    cp -R "$HERE/third_party/proot/src" "$dir/src"
    mk() {
        env CC="$cc" STRIP="$BIN/llvm-strip" OBJCOPY="$BIN/llvm-objcopy" \
            OBJDUMP="$BIN/llvm-objdump" \
            CFLAGS="-I$dir/talloc -DARG_MAX=131072 ${EXTRA_CFLAGS:-}" \
            LDFLAGS="-L$dir/talloc -Wl,-z,max-page-size=16384" \
            make -C "$dir/src" -j"$JOBS" \
            PROOT_UNBUNDLE_LOADER=/pepelauncher-set-PROOT_LOADER \
            HAS_LOADER_32BIT= "$@" >"$dir/make.log" 2>&1 || {
                tail -n 40 "$dir/make.log" >&2
                exit 1
            }
    }

    mk loader/loader
    # Only some ABIs (arm64) use loader/loader-info.c. Upstream generates it with a gawk-only
    # script; produce the same file with llvm-readelf and shell arithmetic instead.
    syms=$("$BIN/llvm-readelf" -s "$dir/src/loader/loader")
    start=$(printf '%s\n' "$syms" | awk '$NF == "_start" { print $2; exit }')
    poke=$(printf '%s\n' "$syms" | awk '$NF == "pokedata_workaround" { print $2; exit }')
    if [ -n "$poke" ] && [ -n "$start" ]; then
        {
            echo "#include <unistd.h>"
            echo "const ssize_t offset_to_pokedata_workaround=$((0x$poke - 0x$start));"
        } >"$dir/src/loader/loader-info.c"
    fi
    # extension/ashmem_memfd.c relies on <string.h> arriving transitively, which it does not
    # on armv7; force-include it for proot itself (never for the freestanding loader).
    EXTRA_CFLAGS="-include string.h" mk proot

    "$BIN/llvm-strip" -o "$OUT/$abi/libproot.so" "$dir/src/proot"
    "$BIN/llvm-strip" -o "$OUT/$abi/libproot-loader.so" "$dir/src/loader/loader"
}

for abi in "$@"; do
    build_abi "$abi"
done

# A 64-bit ARM proot runs 32-bit ARM guests through the 32-bit ARM loader.
if [ -f "$OUT/arm64-v8a/libproot.so" ] && [ -f "$OUT/armeabi-v7a/libproot-loader.so" ]; then
    cp "$OUT/armeabi-v7a/libproot-loader.so" "$OUT/arm64-v8a/libproot-loader32.so"
fi
