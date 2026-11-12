#!/bin/bash
# fetch-sdl2.sh - download the SDL2 source tarball used for the NDK build.
#
# The extracted sources are NOT committed (100+ MB of upstream code); this
# script makes the dependency reproducible instead.
#
#   ./third_party/fetch-sdl2.sh [version]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VER="${1:-2.30.0}"
TARBALL="SDL2-$VER.tar.gz"
URL="https://github.com/libsdl-org/SDL/releases/download/release-$VER/$TARBALL"

cd "$HERE"
if [ ! -d "SDL2-$VER" ]; then
  if [ ! -f "$TARBALL" ]; then
    echo "downloading $URL"
    curl -fL -o "$TARBALL" "$URL"
  fi
  tar xzf "$TARBALL"
fi
echo "SDL2 sources ready: $HERE/SDL2-$VER"
