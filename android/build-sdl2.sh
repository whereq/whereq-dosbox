#!/bin/bash
# build-sdl2.sh - build SDL2 as a static library for Android with the NDK.
#
# We link SDL2 statically into libdosbox.so, so the APK only has to ship one
# .so. SDL_STATIC_PIC is required because a static archive linked into a
# shared object must be compiled position independent.
#
#   ./android/build-sdl2.sh [abi ...]
#
# With no argument all three ABIs are built, the same set build-android.sh
# builds and the same set the released APK carries.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# toolchain/env.sh puts cmake/ninja on PATH and resolves the WSL-local NDK
. "$ROOT/toolchain/env.sh"

NDK="${ANDROID_NDK_HOME:-${WQ_NDK_HOME:-/home/whereq/android-sdk/ndk/30.0.16248370}}"
SDL_SRC="${SDL_SRC:-$ROOT/third_party/SDL2-2.32.10}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-23}"
OUT_ROOT="$ROOT/build/sdl2"

if ! command -v cmake >/dev/null 2>&1; then
  echo "cmake not found on PATH (source toolchain/env.sh)" >&2
  exit 1
fi

if [ ! -d "$SDL_SRC" ]; then
  echo "SDL2 sources not found at $SDL_SRC" >&2
  echo "Download SDL2-2.32.10 and unpack it there, or set SDL_SRC=..." >&2
  exit 1
fi
if [ ! -d "$NDK" ]; then
  echo "NDK not found at $NDK (set ANDROID_NDK_HOME)" >&2
  exit 1
fi

ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a x86_64)

for ABI in "${ABIS[@]}"; do
  BUILD="$ROOT/build/sdl2-build/$ABI"
  PREFIX="$OUT_ROOT/$ABI"
  echo "=== SDL2 for $ABI ==="
  rm -rf "$BUILD" "$PREFIX"
  cmake -S "$SDL_SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DSDL_SHARED=OFF \
    -DSDL_STATIC=ON \
    -DSDL_STATIC_PIC=ON \
    -DSDL_SENSOR=OFF \
    -DSDL_TEST=OFF \
    -DSDL_EXAMPLES=OFF \
    -DSDL_INSTALL_TESTS=OFF
  cmake --build "$BUILD" -j"$(nproc)"
  cmake --install "$BUILD" >/dev/null
  echo "--- installed ---"
  ls -la "$PREFIX/lib/"*.a 2>/dev/null || ls -la "$PREFIX/lib/"
done

echo "=== done ==="
