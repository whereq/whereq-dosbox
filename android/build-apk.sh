#!/bin/bash
# build-apk.sh - LEGACY: assemble the Android APK *without Gradle*.
#
# DEPRECATED. The app layer is now Kotlin, which this javac-only path cannot
# compile. The canonical build is Gradle (android/app/build.gradle, driven by
# `bin/build.sh apk`). This script is kept only for emergencies and native-only
# CI; reach it with `bin/build.sh apk --legacy-apk`. It drives the SDK
# build-tools directly, for a Java-only source tree:
#
#   aapt2 compile/link  ->  resources + AndroidManifest + R.java
#   javac              ->  .class files   (JAVA ONLY - no Kotlin)
#   d8                 ->  classes.dex
#   zip                ->  add dex + jniLibs into the apk
#   zipalign/apksigner ->  aligned, signed apk
#
# Usage:
#   ./android/build-apk.sh [options] [abi ...]
#
#   (default: debug build, all ABIs found in build/android)
#
# Options:
#   --release              build the production APK
#   --debug                build the debug APK (default)
#   --keystore PATH        keystore to sign with
#                           (debug:   build/debug.keystore
#                            release: $WHEREQ_RELEASE_KEYSTORE or
#                                     build/release.keystore)
#   --ks-pass PASS         keystore password
#                           (release default: $WHEREQ_RELEASE_STORE_PASS)
#   --key-pass PASS        key password
#                           (release default: $WHEREQ_RELEASE_KEY_PASS,
#                            falls back to the store password)
#   --alias NAME           key alias
#                           (debug:   androiddebugkey
#                            release: $WHEREQ_RELEASE_ALIAS or "whereq")
#   --version-code N       override android:versionCode
#   --version-name NAME    override android:versionName
#   --output PATH          write the APK here
#
# The debug build is signed with the auto-generated debug keystore and is
# android:debuggable="true", so it can be attached to with "run-as".
#
# The release build is signed with a *release* key and is not debuggable.
# Losing that key means the app can no longer be updated in place, so keep
# a backup of the .keystore file AND of the password.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

. "$ROOT/toolchain/env.sh"

SDK="${ANDROID_SDK_ROOT_WQ:-$WQ_ANDROID_SDK}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BT="${BT%/}"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"

if [ ! -x "$BT/aapt2" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "SDK build-tools or platform not found under $SDK" >&2
  exit 1
fi

APP="$HERE/app"
OUT="$ROOT/build/apk"
WORK="$ROOT/build/apk-work"

MIN_SDK=21
TARGET_SDK=34

# ------------------------------------------------------------- arguments ---
MODE=debug
KEYSTORE=""
KS_PASS=""
KEY_PASS=""
ALIAS=""
VERSION_CODE=""
VERSION_NAME=""
# The tag is the single source of truth for the version: bin/release.sh
# mirrors it into the VERSION file at the repo root. Fall back to the manifest
# defaults when the file is missing.
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
    if [ -f "$ROOT/VERSION" ]; then
        V="$(tr -d '[:space:]' < "$ROOT/VERSION")"
        if [ -z "$VERSION_NAME" ]; then
            VERSION_NAME="$V"
        fi
        if [ -z "$VERSION_CODE" ]; then
            IFS='.' read -r v_major v_minor v_patch v_build <<< "$V"
            VERSION_CODE=$(( v_major * 1000000 + v_minor * 10000 + v_patch * 100 + v_build ))
        fi
    fi
fi
OUTPUT=""
ABIS=()

usage() { sed -n '2,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --release)     MODE=release; shift ;;
    --debug)       MODE=debug; shift ;;
    --keystore)    KEYSTORE="$2"; shift 2 ;;
    --ks-pass)     KS_PASS="$2"; shift 2 ;;
    --key-pass)    KEY_PASS="$2"; shift 2 ;;
    --alias)       ALIAS="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --output)      OUTPUT="$2"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    --)            shift; ABIS+=("$@"); break ;;
    -*)            echo "unknown option: $1" >&2; usage >&2; exit 1 ;;
    *)             ABIS+=("$1"); shift ;;
  esac
done

# ------------------------------------------------------- signing material ---
if [ "$MODE" = release ]; then
  KEYSTORE="${KEYSTORE:-${WHEREQ_RELEASE_KEYSTORE:-$ROOT/build/release.keystore}}"
  KS_PASS="${KS_PASS:-${WHEREQ_RELEASE_STORE_PASS:-}}"
  KEY_PASS="${KEY_PASS:-${WHEREQ_RELEASE_KEY_PASS:-$KS_PASS}}"
  ALIAS="${ALIAS:-${WHEREQ_RELEASE_ALIAS:-whereq}}"
  if [ -z "$KS_PASS" ]; then
    cat >&2 <<EOF
A release build needs the keystore password.

  * set WHEREQ_RELEASE_STORE_PASS (and optionally WHEREQ_RELEASE_KEY_PASS)
  * or pass --ks-pass / --key-pass

It is deliberately not defaulted: a production key with a well-known
password is not a production key.
EOF
    exit 1
  fi
  KEY_DNAME="${WHEREQ_RELEASE_DNAME:-CN=whereq-dosbox, OU=release, O=whereq, L=-, ST=-, C=DE}"
  APK_NAME="whereq-dosbox-release.apk"
else
  KEYSTORE="${KEYSTORE:-$ROOT/build/debug.keystore}"
  KS_PASS="${KS_PASS:-android}"
  KEY_PASS="${KEY_PASS:-android}"
  ALIAS="${ALIAS:-androiddebugkey}"
  KEY_DNAME="CN=whereq-dosbox, OU=dev, O=whereq, L=-, ST=-, C=DE"
  APK_NAME="whereq-dosbox-debug.apk"
fi

OUTPUT="${OUTPUT:-$OUT/$APK_NAME}"

rm -rf "$WORK"
mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$OUT"

# ---------------------------------------------------------------- ABIs -----
if [ ${#ABIS[@]} -eq 0 ]; then
  for d in "$ROOT"/build/android/*/; do
    [ -f "$d/libdosbox.so" ] && ABIS+=("$(basename "$d")")
  done
fi
if [ ${#ABIS[@]} -eq 0 ]; then
  echo "no libdosbox.so found - run android/build-android.sh first" >&2
  exit 1
fi
echo "mode: $MODE"
echo "ABIs: ${ABIS[*]}"

# ------------------------------------------------------------- resources ---
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$WORK/res/compiled.zip"
ASSET_ARGS=()
if [ -d "$APP/src/main/assets" ]; then
  ASSET_ARGS=(-A "$APP/src/main/assets")
fi

# android:debuggable is not in the manifest any more - aapt2's debug mode
# flag inserts it, so the debug build stays debuggable and the release build
# does not.
DEBUG_ARGS=()
[ "$MODE" = debug ] && DEBUG_ARGS=(--debug-mode)

# aapt2's --version-code/--version-name only fill in a value the manifest
# does not have yet, so an override needs a rewritten copy of the manifest.
MANIFEST="$APP/src/main/AndroidManifest.xml"
if [ -n "$VERSION_CODE" ] || [ -n "$VERSION_NAME" ]; then
  MANIFEST="$WORK/AndroidManifest.xml"
  cp "$APP/src/main/AndroidManifest.xml" "$MANIFEST"
  if [ -n "$VERSION_CODE" ]; then
    sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" "$MANIFEST"
  fi
  if [ -n "$VERSION_NAME" ]; then
    sed -i "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" "$MANIFEST"
  fi
  echo "version: $VERSION_NAME ($VERSION_CODE)"
fi

"$BT/aapt2" link \
  -o "$WORK/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  "${ASSET_ARGS[@]}" \
  "$WORK/res/compiled.zip" \
  --java "$WORK/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  "${DEBUG_ARGS[@]}"

# ----------------------------------------------------------------- java ---
echo "javac..."
find "$APP/src/main/java" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
# NOTE: do NOT pipe javac through a filter - a compile error must fail the
# build, otherwise the APK silently ships an incomplete classes.dex.
if ! javac -nowarn -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" \
      -classpath "$ANDROID_JAR" \
      -d "$WORK/classes" \
      @"$WORK/sources.txt" 2>"$WORK/javac.log"; then
  cat "$WORK/javac.log" >&2
  echo "javac FAILED" >&2
  exit 1
fi
grep -vE "bootstrap class path|source value 8|target value 8|deprecat" "$WORK/javac.log" || true

# ------------------------------------------------------------------ dex ---
echo "d8..."
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
# --release drops the debug information from the dex.
DEX_ARGS=()
[ "$MODE" = release ] && DEX_ARGS=(--release)
"$BT/d8" --lib "$ANDROID_JAR" --min-api "$MIN_SDK" \
      "${DEX_ARGS[@]}" \
      --output "$WORK/dex" @"$WORK/classes.txt"

# ------------------------------------------------------------------ apk ---
echo "packaging..."
cp "$WORK/base.apk" "$WORK/unsigned.apk"
( cd "$WORK/dex" && zip -q "$WORK/unsigned.apk" classes*.dex )
# Native libraries MUST live under lib/<abi>/ inside the APK - that is the
# only place the package installer looks for them (it extracts them into
# nativeLibraryDir). Anything else is silently ignored.
for ABI in "${ABIS[@]}"; do
  LIB="$ROOT/build/android/$ABI/libdosbox.so"
  [ -f "$LIB" ] || { echo "missing $LIB" >&2; exit 1; }
  mkdir -p "$WORK/lib/$ABI"
  cp "$LIB" "$WORK/lib/$ABI/"
  ( cd "$WORK" && zip -q "$WORK/unsigned.apk" "lib/$ABI/libdosbox.so" )
done
# assets/ (dosbox.conf template) is read by WhereqDosboxActivity at runtime.
# aapt2 link puts them under assets/ inside the APK for us.

# ----------------------------------------------------------------- sign ---
if [ ! -f "$KEYSTORE" ]; then
  echo "creating keystore $KEYSTORE ..."
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KEY_PASS" \
          -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "$KEY_DNAME" >/dev/null 2>&1
  if [ "$MODE" = release ]; then
    cat <<EOF

*** RELEASE KEY CREATED ***

  $KEYSTORE

Back this file up together with its password - without it the app can no
longer be updated in place (a new key means a new signature and Android
refuses the upgrade).

EOF
  fi
fi

"$BT/zipalign" -p -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$ALIAS" \
  --out "$OUTPUT" "$WORK/aligned.apk"

echo "=== APK ==="
ls -la "$OUTPUT"
"$BT/apksigner" verify --print-certs "$OUTPUT" 2>&1 | head -4
echo "--- manifest ---"
"$BT/aapt2" dump badging "$OUTPUT" 2>/dev/null | grep -E "^package|debuggable" | head -3
echo "--- contents ---"
unzip -l "$OUTPUT" | grep -E "lib/|classes.dex|AndroidManifest|dosbox.conf" | head -20

if [ "$MODE" = release ]; then
  echo
  echo "release APK: $OUTPUT"
  echo "install with: adb install -r $OUTPUT"
fi
