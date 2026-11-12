#!/bin/bash
# build-native.sh - native Linux (WSL2) baseline build for whereq-dosbox.
#
# Feature set mirrors the intended Android/NDK target:
#   - SDL2 (Android will use SDL2 for Android)
#   - no dynamic cores (no x86 dyn, no GCC-plugin dynrec) => interpreter only
#   - no curses text output, no curses-based internal debugger
#     (Android has no ncurses; this is the Android-equivalent config)
#
# Out-of-tree (VPATH) build: the DOSBox tree stays clean at the repo root.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRC="$ROOT"
BUILD="$ROOT/build/native"
PREFIX="$ROOT/build/install"

. "$ROOT/toolchain/env.sh"
export AUTOM4TE="$ROOT/toolchain/sysroot/usr/bin/autom4te"

mkdir -p "$BUILD"

# config.guess / config.sub are required by AC_CANONICAL_HOST, but automake
# --add-missing does not ship them (they come from the GNU config package).
# The autoconf package in the sysroot has them under build-aux/.
SYSROOT="$ROOT/toolchain/sysroot"
for f in config.guess config.sub; do
  if [ ! -f "$SRC/$f" ] && [ -f "$SYSROOT/usr/share/autoconf/build-aux/$f" ]; then
    cp "$SYSROOT/usr/share/autoconf/build-aux/$f" "$SRC/$f"
    chmod +x "$SRC/$f"
    echo "staged $f from the sysroot"
  fi
done

cd "$BUILD"

if [ ! -x "$SRC/configure" ]; then
  ( cd "$SRC" && ./autogen.sh )
fi

if [ ! -f "$BUILD/config.status" ]; then
  "$SRC/configure" \
    --prefix="$PREFIX" \
    --disable-dynamic-core \
    --disable-debug \
    --without-curses \
    "$@"
fi

# The DOSBox core is vendored verbatim from the upstream 0.74 tree, which
# trips over a number of modern compiler checks that have nothing to do with
# this port: DOSBox logs the 64 bit "Bitu" type with %d/%x, takes the
# address of members in packed structs and ignores the result of fread()/
# write(). Same policy as the Android build (see android/CMakeLists.txt):
# silence them here instead of patching the frozen upstream tree.
NATIVE_CXXFLAGS="$(sed -n 's/^CXXFLAGS = //p' "$BUILD/Makefile" | head -1)"
NATIVE_CXXFLAGS="$NATIVE_CXXFLAGS -Wno-format -Wno-address-of-packed-member -Wno-unused-result"

make -j"$(nproc)" CXXFLAGS="$NATIVE_CXXFLAGS"
make install

echo "=== binary ==="
ls -la "$PREFIX/bin/dosbox"
