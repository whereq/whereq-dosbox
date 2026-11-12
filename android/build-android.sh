#!/bin/bash
# build-android.sh - build libdosbox.so for Android (all ABIs).
#
#   ./android/build-android.sh [abi ...]     (default: arm64-v8a armeabi-v7a)
#
# Steps per ABI:
#   1. SDL2 as a static PIC archive (skipped if already present)
#   2. DOSBox -> libdosbox.so
#   3. strip + copy into the Gradle jniLibs tree (when the Gradle module exists)
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

  # Stage the library for the Gradle build. The jniLibs tree is build output
  # (it is gitignored), so it is created here rather than expected to exist:
  # check for the Gradle *module*, not for the directory we are about to make.
  JNI_ROOT="$ROOT/android/app/src/main/jniLibs"
  if [ -f "$ROOT/android/app/build.gradle" ]; then
    mkdir -p "$JNI_ROOT/$ABI"
    cp "$BUILD/libdosbox.so" "$JNI_ROOT/$ABI/"
    echo "    -> copied to $JNI_ROOT/$ABI"
  fi
done

echo "=== done ==="
