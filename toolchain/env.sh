#!/bin/sh
# Rootless dev toolchain for whereq-dosbox.
#
#   . toolchain/env.sh
#
# Provides:
#   * autoconf/automake/m4/pkg-config/nasm/python from the local sysroot
#     (installed without root via "apt-get download" + "dpkg-deb -x")
#   * cmake/ninja from the Android SDK (not installed system-wide here)
#   * ANDROID_NDK_HOME / ANDROID_SDK_ROOT for the NDK build

# This file has to be SOURCED, not executed: it exports variables into the
# current shell. Run as a program it would export them into a subshell that
# exits immediately, leaving the environment unchanged - doing nothing at all,
# silently. Catch that here instead of letting the build fail later on a
# missing cmake or NDK.
case "$0" in
    */env.sh|env.sh)
        echo "toolchain/env.sh must be sourced, not executed:" >&2
        echo "    . toolchain/env.sh" >&2
        exit 1
        ;;
esac

_TOOLCHAIN="$(cd "$(dirname "${BASH_SOURCE:-$0}")" && pwd)"
export _TOOLCHAIN
SYSROOT="$_TOOLCHAIN/sysroot"

export PATH="$SYSROOT/usr/bin:$PATH"
export CPPFLAGS="-I$SYSROOT/usr/include -I$SYSROOT/usr/include/x86_64-linux-gnu"
export LDFLAGS="-L$SYSROOT/usr/lib/x86_64-linux-gnu"
export LD_LIBRARY_PATH="$SYSROOT/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export PKG_CONFIG_PATH="$SYSROOT/usr/lib/x86_64-linux-gnu/pkgconfig:$SYSROOT/usr/share/pkgconfig"
export ACLOCAL_PATH="$SYSROOT/usr/share/aclocal"
export AUTOMAKE_LIBDIR="$SYSROOT/usr/share/automake-1.16"
export M4="$SYSROOT/usr/bin/m4"

# aclocal/automake are perl scripts that hardcode their module directory as
# /usr/share/automake-1.16 (the path the .deb was built for). Inside the
# sysroot the modules live in $SYSROOT/usr/share/automake-1.16, and neither
# AUTOMAKE_LIBDIR nor AUTOMAKE_LIB is read by aclocal - so the only way to
# make "use Automake::Config" resolve is PERL5LIB.
export PERL5LIB="$SYSROOT/usr/share/automake-1.16${PERL5LIB:+:$PERL5LIB}"

# --- Android SDK / NDK -------------------------------------------------------
# NOTE: the shell already exports ANDROID_HOME / ANDROID_SDK_ROOT pointing at
# the Windows-side SDK (/mnt/c/...). We deliberately use the WSL-local SDK
# instead: it lives on the ext4 filesystem (much faster than the 9p /mnt/c
# mount for the NDK toolchain) and it carries its own cmake + ninja.
WQ_ANDROID_SDK="${WQ_ANDROID_SDK:-/home/whereq/android-sdk}"
export WQ_ANDROID_SDK
WQ_NDK_HOME="${WQ_NDK_HOME:-$(ls -d "$WQ_ANDROID_SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
export WQ_NDK_HOME

for _cm in "$WQ_ANDROID_SDK"/cmake/*/bin; do
    [ -d "$_cm" ] && PATH="$PATH:$_cm"
done
for _pt in "$WQ_ANDROID_SDK"/platform-tools; do
    [ -d "$_pt" ] && PATH="$PATH:$_pt"
done
export PATH

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME:-}" ]; then
    ANDROID_NDK_HOME="$WQ_NDK_HOME"
    export ANDROID_NDK_HOME
fi

