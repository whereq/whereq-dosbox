#!/bin/bash
# build-android.sh - build libdosbox.so for Android (all ABIs).
#
#   ./android/build-android.sh [abi ...]     (default: arm64-v8a armeabi-v7a)
#
# Steps per ABI:
#   1. SDL2 as a static PIC archive (skipped if already present)
#   2. DOSBox -> libdosbox.so
#   3. strip + copy into the Gradle jniLibs tree (when it exists)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

. "$ROOT/toolchain/env.sh"

ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a armeabi-v7a)

for ABI in "${ABIS[@]}"; do
  SDL_PREFIX="$ROOT/build/sdl2/$ABI"
  BUILD="$ROOT/build/android/$ABI"

  if [ ! -f "$SDL_PREFIX/lib/libSDL2.a" ]; then
    echo "=== SDL2 for $ABI ==="
    "$HERE/build-sdl2.sh" "$ABI"
  fi

  echo "=== DOSBox for $ABI ==="
  cmake -S "$HERE" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DSDL2_PREFIX="$SDL_PREFIX" \
    -DCMAKE_PREFIX_PATH="$SDL_PREFIX" >/dev/null
  cmake --build "$BUILD" -j"$(nproc)"

  STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  cp "$BUILD/libdosbox.so" "$BUILD/libdosbox.so.unstripped"
  "$STRIP" --strip-unneeded "$BUILD/libdosbox.so"

  echo "--- $ABI ---"
  ls -la "$BUILD/libdosbox.so" "$BUILD/libdosbox.so.unstripped"

  JNI="$ROOT/android/app/src/main/jniLibs/$ABI"
  if [ -d "$(dirname "$JNI")" ]; then
    mkdir -p "$JNI"
    cp "$BUILD/libdosbox.so" "$JNI/"
    echo "    -> copied to $JNI"
  fi
done

echo "=== done ==="
