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

make -j"$(nproc)"
make install

echo "=== binary ==="
ls -la "$PREFIX/bin/dosbox"
