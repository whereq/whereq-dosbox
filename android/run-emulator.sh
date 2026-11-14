#!/bin/bash
# run-emulator.sh - drive the app on an Android emulator from WSL.
#
# This host is WSL2 on Windows, and the only Android SDK/emulator available is
# the Windows one under /mnt/c. Two consequences shape this script:
#
#   * the emulator and adb must run as *Windows* processes, so every adb call
#     goes through cmd.exe;
#   * WSL cannot reach the Windows loopback (127.0.0.1:5037 / :5555 is not
#     forwarded), so the Linux adb server is useless here - the Windows adb
#     server has to own the emulator.
#
# Usage:
#   ./android/run-emulator.sh install     build + install + start the app
#   ./android/run-emulator.sh start       start the app only
#   ./android/run-emulator.sh shot FILE   save a screenshot
#   ./android/run-emulator.sh log         dump logcat
#   ./android/run-emulator.sh shell CMD   run a shell command
#   ./android/run-emulator.sh boot        boot the emulator and wait for it
#   ./android/run-emulator.sh push LOCAL REMOTE
#                                          copy a file onto the device
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

WIN_SDK="/mnt/c/Users/googo/AppData/Local/Android/Sdk"
WIN_HOME="/mnt/c/Users/googo"
AVD="${AVD:-Medium_Phone_API_37.0}"
# Which build to install. Override with APK_MODE=release to install the
# production build (it is signed with a different key, so the debug build has
# to be uninstalled first). The APK comes from the canonical Gradle output;
# the legacy no-Gradle path (build/apk/) is used as a fallback.
APK_MODE="${APK_MODE:-debug}"
APK_NAME="app-$APK_MODE.apk"
APK_SRC="$ROOT/android/app/build/outputs/apk/$APK_MODE/$APK_NAME"
if [ ! -f "$APK_SRC" ]; then
  APK_SRC="$ROOT/build/apk/whereq-dosbox-$APK_MODE.apk"   # legacy --legacy-apk output
  APK_NAME="$(basename "$APK_SRC")"
fi
APK_WIN="C:\\Users\\googo\\$APK_NAME"
PKG="com.whereq.dosbox"
ACT="$PKG/.WhereqDosboxActivity"

# Run the Windows adb with a Windows cwd (a UNC cwd makes cmd.exe complain and
# breaks relative paths).
#
# The arguments are deliberately NOT quoted: under WSL interop cmd.exe does not
# strip the quotes, so adb would receive them literally ("unknown command
# \"devices\""). None of our paths contain spaces, and cmd.exe rejoins the
# argument vector with spaces, so `shell "getprop x"` still works.
#
# The WSL interop is flaky: spawning a Windows process fails every now and then
# with "UtilAcceptVsock: accept4 failed 110", which is not an adb error and
# says nothing about the device. So a failed call is retried a few times before
# it is reported - without that, one hiccup kills the script.
WADB_RETRIES="${WADB_RETRIES:-5}"
wadb() {
  # Run cmd.exe from a Windows directory: a WSL cwd is a UNC path and cmd.exe
  # then prints a multi-line warning that would end up in the parsed output.
  # stderr is deliberately left out of the pipe so that only adb's stdout is
  # parsed.
  local attempt out=""
  for attempt in $(seq 1 "$WADB_RETRIES"); do
    out="$( ( cd "$WIN_HOME" && \
      cmd.exe /c "cd /d C:\\ && C:\\Users\\googo\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe $*" ) \
      | tr -d '\r' )" && { printf '%s' "$out"; return 0; }
    [ "$attempt" -lt "$WADB_RETRIES" ] && sleep 3
  done
  printf '%s' "$out"
  return 1
}

emu_state() {
  wadb devices 2>/dev/null | grep -E "^emulator-5554" | awk '{print $2}' || true
}

do_boot() {
  if [ -z "$(emu_state)" ]; then
    echo "== starting emulator (AVD: $AVD) =="
    # -gpu host uses the real GPU through the emulator's OpenGL ES translator.
    # The SwiftShader renderer (swiftshader_indirect) draws the screen in
    # scattered tiles, which looks like an app bug but is not one - the same
    # APK is clean on the host GPU.
    ( cd "$WIN_SDK/emulator" && \
      setsid nohup ./emulator.exe -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot -gpu host -port 5554 > /tmp/emu.log 2>&1 < /dev/null & )
    sleep 10
  fi

  # The adb server must be running *before* the emulator registers, otherwise
  # the device shows up as "unauthorized" and never recovers.
  wadb start-server >/dev/null || true
  echo "== waiting for device =="
  for i in $(seq 1 60); do
    state="$(emu_state)"
    if [ "$state" = "device" ]; then
      echo "device ready after ${i}0s"
      break
    fi
    sleep 10
  done
  [ "$(emu_state)" = "device" ] || { echo "emulator did not come up" >&2; exit 1; }

  echo "== waiting for boot_completed =="
  for i in $(seq 1 30); do
    b="$(wadb shell getprop sys.boot_completed 2>/dev/null | tr -d ' \r\n' || true)"
    [ "$b" = "1" ] && { echo "boot completed"; return 0; }
    sleep 10
  done
  echo "boot did not complete" >&2
  exit 1
}

do_install() {
  [ -f "$APK_SRC" ] || { echo "missing $APK_SRC - run 'bin/build.sh android && bin/build.sh apk' first" >&2; exit 1; }
  cp "$APK_SRC" "/mnt/c/Users/googo/$APK_NAME"
  # Verify the copy really landed: a stale file here silently installs the
  # previous APK, which is very confusing to debug.
  local a b
  a="$(md5sum "$APK_SRC" | cut -d' ' -f1)"
  b="$(md5sum "/mnt/c/Users/googo/$APK_NAME" | cut -d' ' -f1)"
  [ "$a" = "$b" ] || { echo "APK copy mismatch ($a != $b)" >&2; exit 1; }
  echo "== installing =="
  local out
  out="$(wadb install -r "$APK_WIN")"
  echo "$out"
  echo "$out" | grep -q "Success" || { echo "install failed" >&2; exit 1; }
}

do_clear() {
  echo "== clearing app data =="
  wadb shell pm clear "$PKG"
  # "pm clear" kills the app; give the system a moment to finish tearing the
  # process down before we start it again.
  sleep 3
}

do_start() {
  echo "== starting $ACT =="
  wadb shell am force-stop "$PKG"
  wadb shell am start -n "$ACT"
}

do_push() {
  local src="$1" dst="$2"
  [ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
  # adb reads the local path on the Windows side, so the file has to be
  # reachable from C:. Everything under the WSL home is copied over first.
  local base win
  base="$(basename "$src")"
  win="C:\\Users\\googo\\$base"
  cp "$src" "/mnt/c/Users/googo/$base"
  # adb runs on the Windows side, so it needs a Windows path.
  wadb push "$win" "$dst" >/dev/null
  echo "pushed $base -> $dst"
}

do_shot() {
  out="${1:-$ROOT/build/apk/screen.png}"
  wadb shell screencap -p /sdcard/_shot.png
  wadb pull /sdcard/_shot.png "C:\\Users\\googo\\_shot.png" >/dev/null
  cp /mnt/c/Users/googo/_shot.png "$out"
  echo "saved $out"
}

case "${1:-install}" in
  boot)    do_boot ;;
  install) do_boot; do_install; do_start ;;
  reinstall) do_boot; do_clear; do_install; do_start ;;
  clear)   do_clear ;;
  start)   do_start ;;
  shot)    shift; do_shot "$@" ;;
  push)    shift; do_push "$@" ;;
  log)     wadb logcat -d ;;
  shell)   shift; wadb shell "$@" ;;
  *)       echo "usage: $0 {boot|install|reinstall|clear|start|shot|push|log|shell}" >&2; exit 2 ;;
esac
