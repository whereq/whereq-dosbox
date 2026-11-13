#!/bin/sh
# ===========================================================================
#  Build the STARFALL example game bundle.
# ===========================================================================
#
#  The game is 16-bit DOS assembly, so it is built with the GNU assembler
#  and linker that come with the repository toolchain:
#
#      as --32 -o starfall.o starfall.asm
#      ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start -o starfall.com
#
#  The image is linked at 100h because that is where DOS loads a .COM file
#  and enters it, so every label in the source is also its offset inside the
#  loaded segment.
#
#  The result is packed into the bundle zip that WhereQ-DOSBox installs:
#
#      PLAY.BAT       the start-up script the launcher looks for
#      STARFALL.COM   the game
#      README.TXT     what the bundle is
#
#  Usage:  ./build.sh
# ===========================================================================

set -e

here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

toolchain="../../toolchain/env.sh"
if [ ! -f "$toolchain" ]; then
        echo "build.sh: the repository toolchain is missing ($toolchain)" >&2
        exit 1
fi

# the toolchain has to be sourced, not run
. "$toolchain"

mkdir -p build dist

echo "assembling starfall.asm"
as --32 -I src -o build/starfall.o src/starfall.asm

echo "linking starfall.com"
ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start \
        -o build/STARFALL.COM build/starfall.o

echo "packing the bundle"
rm -f dist/whereq-demo-bundle.zip
cp build/STARFALL.COM bundle/STARFALL.COM
( cd bundle && zip -q -X ../dist/whereq-demo-bundle.zip PLAY.BAT STARFALL.COM README.TXT )
rm -f bundle/STARFALL.COM

echo
echo "built:"
ls -l build/STARFALL.COM dist/whereq-demo-bundle.zip
