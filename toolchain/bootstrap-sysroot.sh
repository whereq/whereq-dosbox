#!/bin/bash
# bootstrap-sysroot.sh - rebuild the rootless dev toolchain.
#
# The host has no compiler packages installed system-wide and we do not want
# to run anything as root, so the few tools the *desktop* build needs are
# unpacked from Ubuntu .deb files into a private sysroot:
#
#   autoconf/automake/m4   for autogen.sh / configure
#   pkg-config             to find SDL2
#   nasm                   x86 assembly in DOSBox / SDL2
#   libsdl2-dev            SDL2 headers + sdl2-config for the desktop build
#   libncurses*            (the Android build uses --without-curses, but the
#                           stock configure wants the headers to be present)
#   libglu1-mesa-dev       pulled in by libsdl2-dev
#   strace                 handy when chasing the emulator
#
#   ./toolchain/bootstrap-sysroot.sh
#
# Everything lands in toolchain/pkgs (the .deb files) and toolchain/sysroot
# (the unpacked tree). Both are gitignored - this script makes them
# reproducible instead.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKGS="$HERE/pkgs"
SYSROOT="$HERE/sysroot"

PACKAGES=(
  autoconf
  automake
  m4
  pkgconf            # Ubuntu 24.04: "pkg-config" is only a transitional
                     # package that depends on pkgconf
  pkgconf-bin        # ...which in turn only ships the "pkg-config" symlink;
                     # the real binary lives in pkgconf-bin
  libpkgconf3        # ...and the shared library it links against
  pkg-config
  nasm
  libsdl2-dev
  libsdl2-2.0-0
  libncurses-dev
  libncurses6
  libglu1-mesa-dev
  strace
)

if [ "$(id -u)" = "0" ]; then
  echo "note: running as root - the whole point is to avoid that, but it still works"
fi

mkdir -p "$PKGS" "$SYSROOT"

# --- 1. fetch ----------------------------------------------------------------
# "apt-get download" is unprivileged; it writes the .deb into the current
# directory. It needs the apt package lists, which a normal desktop image has.
cd "$PKGS"
for p in "${PACKAGES[@]}"; do
  if ls "$p"_*.deb >/dev/null 2>&1; then
    echo "have $p"
    continue
  fi
  echo "downloading $p"
  if ! apt-get download "$p"; then
    echo "FAILED to download $p" >&2
    echo "If the apt lists are missing, run 'sudo apt-get update' once," >&2
    echo "or drop the .deb files into $PKGS by hand." >&2
    exit 1
  fi
done

# --- 2. unpack ---------------------------------------------------------------
cd "$SYSROOT"
for d in "$PKGS"/*.deb; do
  echo "unpacking $(basename "$d")"
  dpkg-deb -x "$d" "$SYSROOT"
done

# --- 3. rewrite the paths baked into the .deb files --------------------------
# The Debian scripts hardcode the absolute paths they were built for
# (/usr/share/automake-1.16, /usr/bin/m4, ...). Inside the sysroot the files
# live under $SYSROOT, so point the scripts at the sysroot - otherwise
# aclocal scans a /usr/share/aclocal-1.16 that does not exist and dies with
# "couldn't open directory".
rewrite() {
  local f="$1"
  shift
  [ -f "$f" ] || return 0
  # Follow symlinks: "sed -i" would otherwise replace the link itself with
  # a regular file and break the alternatives set up in step 4.
  f="$(readlink -f "$f")"
  for p in "$@"; do
    [ -e "$SYSROOT$p" ] || continue
    # The exact form, and the prefix form - aclocal builds
    # '/usr/share/aclocal-' . $VERSION at runtime, so the literal in the
    # source has no version number in it.
    sed -i -e "s|'$p'|'$SYSROOT$p'|g" \
           -e "s|'$p|'$SYSROOT$p|g" "$f"
  done
}

# The .deb files ship the *versioned* names (aclocal-1.16, automake-1.16);
# the unversioned ones only appear later, in step 4, as symlinks. So the
# versioned files have to be rewritten here as well - otherwise aclocal
# keeps its baked-in /usr/share/aclocal- path and dies with
# "couldn't open directory '/usr/share/aclocal-1.16'".
for s in aclocal aclocal-1.16 automake automake-1.16 \
         autom4te autoreconf autoheader autoconf \
         ifnames autoscan autoupdate; do
  rewrite "$SYSROOT/usr/bin/$s" \
    /usr/share/automake-1.16 \
    /usr/share/aclocal-1.16 \
    /usr/share/autoconf \
    /usr/share/aclocal \
    /usr/bin/m4 \
    /usr/bin/autoconf \
    /usr/bin/autoheader \
    /usr/bin/autom4te \
    /usr/bin/aclocal \
    /usr/bin/automake \
    /usr/bin/ifnames
done
echo "rewrote the baked-in paths in the sysroot scripts"

# autom4te.cfg and a few .m4 files point at /usr/share/autoconf for the
# macro directories - same treatment, otherwise autom4te cannot find
# m4sugar/m4sugar.m4.
for f in $(grep -rl "/usr/share/autoconf" "$SYSROOT/usr/share/autoconf" 2>/dev/null); do
  sed -i "s|'/usr/share/autoconf'|'$SYSROOT/usr/share/autoconf'|g" "$f"
  sed -i "s|'/usr/share/autoconf/|'$SYSROOT/usr/share/autoconf/|g" "$f"
done
echo "rewrote the baked-in paths in the autoconf macro files"

# --- 4. alternatives ---------------------------------------------------------
# The .deb files ship versioned names (automake-1.16) and rely on Debian's
# update-alternatives machinery for the unversioned ones. There is no
# alternatives system inside the sysroot, so create the links by hand.
link() {
  if [ ! -e "$SYSROOT/usr/bin/$1" ] && [ -e "$SYSROOT/usr/bin/$2" ]; then
    ln -sf "$2" "$SYSROOT/usr/bin/$1"
    echo "linked $1 -> $2"
  fi
}
link aclocal    aclocal-1.16
link automake   automake-1.16
link pkg-config pkgconf

# DOSBox's configure looks for a "python"; the interpreter itself is not
# packaged here, so point at the system's if there is one.
if [ ! -e "$SYSROOT/usr/bin/python" ] && [ -x /usr/bin/python3 ]; then
  ln -sf /usr/bin/python3 "$SYSROOT/usr/bin/python"
  echo "linked python -> /usr/bin/python3"
fi

# --- 5. verify ---------------------------------------------------------------
missing=0
for exe in autoconf automake aclocal autoreconf m4 pkg-config nasm sdl2-config; do
  if [ ! -x "$SYSROOT/usr/bin/$exe" ]; then
    echo "MISSING: $SYSROOT/usr/bin/$exe" >&2
    missing=1
  fi
done
if [ "$missing" = "1" ]; then
  echo "sysroot is incomplete" >&2
  exit 1
fi

echo "=== sysroot ready: $SYSROOT ==="
echo "now:  . toolchain/env.sh"
