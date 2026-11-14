#!/bin/bash
# build.sh - the single entry point for every build scenario of whereq-dosbox.
#
# Every step below delegates to the script that owns it, so there is exactly
# one implementation of each build step:
#
#   bootstrap  -> third_party/fetch-sdl2.sh, toolchain/bootstrap-sysroot.sh
#   native     -> native/build-native.sh
#   android    -> android/build-android.sh
#   apk        -> android/gradlew (canonical); android/build-apk.sh (--legacy-apk)
#   icons      -> android/make-icons.py
#
# Usage:
#   bin/build.sh <command> [options]
#
# Commands:
#   doctor      report the state of the toolchain and the build tree
#   bootstrap   fetch the SDL2 sources and build the rootless toolchain
#   native      build the desktop Linux baseline  -> build/install/bin/dosbox
#   android     build the Android native libraries -> build/android/<abi>/libdosbox.so
#   apk         package the APK                  -> build/apk/*.apk
#   all         bootstrap + native + android + apk
#   icons       regenerate the launcher icons from resources/
#   clean       remove the build outputs (keeps the toolchain and SDL2)
#   distclean   remove everything that was generated, including the toolchain
#   help        this text
#
# Options:
#   --release               build the release APK (needs the signing password)
#   --abis "a b c"          the ABIs to build (default: arm64-v8a armeabi-v7a x86_64)
#   --jobs N                parallel jobs (default: nproc)
#   --keystore PATH         keystore for the release APK
#   --ks-pass PASS          keystore password
#   --key-pass PASS         key password
#   --alias NAME            key alias
#   --version-code N        android:versionCode for the APK
#   --version-name NAME     android:versionName for the APK
#   --output PATH           write the APK here
#   --legacy-apk            package with the old no-Gradle javac path (cannot
#                           compile Kotlin; native-only / emergency use)
#   --no-bootstrap          skip the bootstrap in "all"
#
# Examples:
#   bin/build.sh doctor
#   bin/build.sh bootstrap
#   bin/build.sh all
#   bin/build.sh android --abis "arm64-v8a armeabi-v7a"
#   bin/build.sh apk --release
#   WHEREQ_RELEASE_STORE_PASS=... bin/build.sh apk --release
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

SDL2_VERSION=2.30.0
ABIS="arm64-v8a armeabi-v7a x86_64"
MODE=debug
JOBS="$(nproc 2>/dev/null || echo 4)"
DO_BOOTSTRAP=1
USE_LEGACY_APK=0
# APK options, translated into Gradle properties / signing env by do_apk.
VERSION_CODE=""
VERSION_NAME=""
KEYSTORE=""
KS_PASS=""
KEY_PASS=""
KEY_ALIAS=""
OUTPUT=""
LEGACY_APK_ARGS=()

# ------------------------------------------------------------------ output --
if [ -t 1 ]; then
    BOLD="$(printf '\033[1m')"; DIM="$(printf '\033[2m')"
    GREEN="$(printf '\033[32m')"; RED="$(printf '\033[31m')"
    YELLOW="$(printf '\033[33m')"; OFF="$(printf '\033[0m')"
else
    BOLD=""; DIM=""; GREEN=""; RED=""; YELLOW=""; OFF=""
fi

step() { printf '\n%s==> %s%s\n' "$BOLD" "$*" "$OFF"; }
info() { printf '    %s%s%s\n' "$DIM" "$*" "$OFF"; }
ok()   { printf '    %s%s%s\n' "$GREEN" "$*" "$OFF"; }
warn() { printf '    %s%s%s\n' "$YELLOW" "$*" "$OFF"; }
die()  { printf '%serror:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

usage() {
    # print the header comment block, without the leading "# "
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

# -------------------------------------------------------------- arguments ---
CMD=""
while [ $# -gt 0 ]; do
    case "$1" in
        doctor|bootstrap|native|android|apk|all|icons|clean|distclean|help)
            CMD="$1"; shift ;;
        --release) MODE=release; shift ;;
        --debug)   MODE=debug; shift ;;
        --abis)    ABIS="$2"; shift 2 ;;
        --jobs)    JOBS="$2"; shift 2 ;;
        --keystore)   KEYSTORE="$2"; shift 2 ;;
        --ks-pass)    KS_PASS="$2"; shift 2 ;;
        --key-pass)   KEY_PASS="$2"; shift 2 ;;
        --alias)      KEY_ALIAS="$2"; shift 2 ;;
        --version-code) VERSION_CODE="$2"; shift 2 ;;
        --version-name) VERSION_NAME="$2"; shift 2 ;;
        --output)     OUTPUT="$2"; shift 2 ;;
        --legacy-apk) USE_LEGACY_APK=1; shift ;;
        --no-bootstrap) DO_BOOTSTRAP=0; shift ;;
        -h|--help)  usage; exit 0 ;;
        *) die "unknown option: $1 (try: bin/build.sh help)" ;;
    esac
done
[ -n "$CMD" ] || { usage; exit 1; }

# The toolchain is not on the PATH until env.sh is sourced. Every step needs
# it, and it must be sourced - not executed - so the exports land here.
# shellcheck source=/dev/null
. "$ROOT/toolchain/env.sh"

# ------------------------------------------------------------------ steps ---
do_bootstrap() {
    step "Bootstrap: SDL2 sources + rootless toolchain"
    info "SDL2 sources -> third_party/SDL2-$SDL2_VERSION"
    "$ROOT/third_party/fetch-sdl2.sh" "$SDL2_VERSION"
    info "toolchain -> toolchain/sysroot/"
    "$ROOT/toolchain/bootstrap-sysroot.sh"
    ok "bootstrap done"
}

do_native() {
    step "Native desktop baseline (Linux)"
    "$ROOT/native/build-native.sh"
    ok "binary: build/install/bin/dosbox"
}

do_android() {
    step "Android native libraries: $ABIS"
    # shellcheck disable=SC2086
    "$ROOT/android/build-android.sh" $ABIS
    ok "libraries: build/android/<abi>/libdosbox.so"
}

do_apk() {
    # The legacy escape hatch: the old no-Gradle, javac-only packager. It
    # cannot compile the Kotlin app layer, so it is kept only for emergencies
    # / native-only CI. The canonical path below drives Gradle.
    if [ "$USE_LEGACY_APK" = 1 ]; then
        step "APK ($MODE) via the legacy no-Gradle packager"
        local args=()
        if [ "$MODE" = release ]; then args+=(--release); fi
        [ -n "$VERSION_CODE" ] && args+=(--version-code "$VERSION_CODE")
        [ -n "$VERSION_NAME" ] && args+=(--version-name "$VERSION_NAME")
        [ -n "$KEYSTORE" ] && args+=(--keystore "$KEYSTORE")
        [ -n "$KS_PASS" ] && args+=(--ks-pass "$KS_PASS")
        [ -n "$KEY_PASS" ] && args+=(--key-pass "$KEY_PASS")
        [ -n "$KEY_ALIAS" ] && args+=(--alias "$KEY_ALIAS")
        [ -n "$OUTPUT" ] && args+=(--output "$OUTPUT")
        "$ROOT/android/build-apk.sh" "${args[@]}" "${LEGACY_APK_ARGS[@]}"
        ok "apk: build/apk/"
        return
    fi

    step "APK ($MODE) via Gradle"
    for abi in $ABIS; do
        [ -f "$ROOT/build/android/$abi/libdosbox.so" ] || \
            die "native library for $abi is missing - run: bin/build.sh android --abis \"$ABIS\""
    done

    local task="assembleDebug"
    [ "$MODE" = release ] && task="assembleRelease"

    # Pass the version only when overridden; otherwise Gradle reads the VERSION
    # file. Build the flag list conditionally so no empty args reach Gradle.
    local gargs=()
    [ -n "$VERSION_CODE" ] && gargs+=(-PappVersionCode="$VERSION_CODE")
    [ -n "$VERSION_NAME" ] && gargs+=(-PappVersionName="$VERSION_NAME")

    # Release signing is read from the environment by app/build.gradle; map the
    # CLI flags onto it without printing the secrets.
    [ -n "$KEYSTORE" ] && export WHEREQ_RELEASE_KEYSTORE="$KEYSTORE"
    [ -n "$KS_PASS" ] && export WHEREQ_RELEASE_STORE_PASS="$KS_PASS"
    [ -n "$KEY_PASS" ] && export WHEREQ_RELEASE_KEY_PASS="$KEY_PASS"
    [ -n "$KEY_ALIAS" ] && export WHEREQ_RELEASE_ALIAS="$KEY_ALIAS"

    ( cd "$ROOT/android" && ./gradlew ":app:$task" --console=plain "${gargs[@]}" )

    local out="$ROOT/android/app/build/outputs/apk/$MODE/app-$MODE.apk"
    if [ -n "$OUTPUT" ] && [ -f "$out" ]; then
        cp "$out" "$OUTPUT"
        ok "apk: $OUTPUT"
    else
        ok "apk: ${out#"$ROOT"/}"
    fi
}

do_icons() {
    step "Launcher icons"
    have python3 || die "python3 is required for the icon generator"
    python3 "$ROOT/android/make-icons.py" \
        || die "the icon generator needs Pillow: python3 -m pip install --user pillow"
    ok "icons written to android/app/src/main/res/mipmap-*"
}

do_clean() {
    step "Clean: removing the build outputs"
    for d in "$ROOT/build/apk" "$ROOT/build/apk-work" "$ROOT/build/android" \
             "$ROOT/build/install" "$ROOT/build/native" "$ROOT/build/sdl2" \
             "$ROOT/build/bench"; do
        if [ -e "$d" ]; then rm -rf "$d"; info "removed ${d#"$ROOT"/}"; fi
    done
    ok "clean done (the toolchain and the SDL2 sources are still there)"
}

do_distclean() {
    step "Distclean: removing everything that was generated"
    do_clean
    for d in "$ROOT/toolchain/sysroot" "$ROOT/third_party/SDL2-$SDL2_VERSION"; do
        if [ -e "$d" ]; then rm -rf "$d"; info "removed ${d#"$ROOT"/}"; fi
    done
    ok "distclean done - run 'bin/build.sh bootstrap' to start over"
}

do_doctor() {
    step "Toolchain"
    for t in git make gcc g++ cmake ninja javac java python3 zip keytool; do
        if have "$t"; then
            ok "$(printf '%-10s %s' "$t" "$(command -v "$t")")"
        else
            warn "$(printf '%-10s %s' "$t" "not found")"
        fi
    done

    step "Android SDK"
    if [ -n "${WQ_ANDROID_SDK:-}" ] && [ -d "$WQ_ANDROID_SDK" ]; then
        ok "SDK      $WQ_ANDROID_SDK"
        ndk="$(ls -d "$WQ_ANDROID_SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
        [ -n "$ndk" ] && ok "NDK      $ndk" || warn "NDK      not installed"
        bt="$(ls -d "$WQ_ANDROID_SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
        [ -n "$bt" ] && ok "build-tools $bt" || warn "build-tools not installed"
        pl="$(ls -d "$WQ_ANDROID_SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
        [ -n "$pl" ] && ok "platform $pl" || warn "no platform installed"
    else
        warn "no Android SDK at ${WQ_ANDROID_SDK:-<unset>}"
    fi

    step "Build tree"
    if [ -d "$ROOT/third_party/SDL2-$SDL2_VERSION" ]; then
        ok "SDL2 sources  third_party/SDL2-$SDL2_VERSION"
    else
        warn "SDL2 sources missing - run 'bin/build.sh bootstrap'"
    fi
    if [ -d "$ROOT/toolchain/sysroot" ]; then
        ok "sysroot       toolchain/sysroot"
    else
        warn "sysroot missing - run 'bin/build.sh bootstrap'"
    fi
    for abi in $ABIS; do
        if [ -f "$ROOT/build/android/$abi/libdosbox.so" ]; then
            ok "$(printf '%-13s %s' "$abi" "$(stat -c%s "$ROOT/build/android/$abi/libdosbox.so") bytes")"
        else
            info "$(printf '%-13s not built' "$abi")"
        fi
    done
    for apk in "$ROOT"/build/apk/*.apk; do
        if [ -f "$apk" ]; then
            ok "$(printf '%-13s %s' "apk" "${apk#"$ROOT"/}")"
        fi
    done

    step "Signing"
    if [ -f "$ROOT/build/release.keystore" ]; then
        ok "release keystore build/release.keystore"
        if [ -n "${WHEREQ_RELEASE_STORE_PASS:-}" ]; then
            ok "WHEREQ_RELEASE_STORE_PASS is set"
        else
            info "WHEREQ_RELEASE_STORE_PASS is not set - a release build will ask for it"
        fi
    else
        info "no release keystore yet - it is created on the first release build"
    fi
}

case "$CMD" in
    doctor)    do_doctor ;;
    bootstrap) do_bootstrap ;;
    native)    do_native ;;
    android)   do_android ;;
    apk)       do_apk ;;
    icons)     do_icons ;;
    clean)     do_clean ;;
    distclean) do_distclean ;;
    all)
        if [ "$DO_BOOTSTRAP" = 1 ]; then do_bootstrap; fi
        do_native
        do_android
        do_apk
        printf '\n%s==> everything built%s\n' "$GREEN" "$OFF"
        ;;
    help) usage ;;
esac
