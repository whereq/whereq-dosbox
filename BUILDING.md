# Building WhereQ-DOSBox

Every build scenario of this project, with a command you can paste. The
shortest possible version:

```sh
git clone git@github.com:whereq/whereq-dosbox.git
cd whereq-dosbox
bin/build.sh all
```

`bin/build.sh all` bootstraps the toolchain, builds the desktop baseline,
builds the Android libraries for all three ABIs and packages a debug APK.
It is the entry point for everything below — each command delegates to the
script that owns that step, so there is only ever one implementation of a
build step.

- [What you need](#what-you-need)
- [The commands](#the-commands)
- [Scenario 1 — first build on a fresh clone](#scenario-1--first-build-on-a-fresh-clone)
- [Scenario 2 — the desktop Linux baseline](#scenario-2--the-desktop-linux-baseline)
- [Scenario 3 — the Android native libraries](#scenario-3--the-android-native-libraries)
- [Scenario 4 — the debug APK](#scenario-4--the-debug-apk)
- [Scenario 5 — the release APK](#scenario-5--the-release-apk)
- [Scenario 6 — install and run it](#scenario-6--install-and-run-it)
- [Scenario 7 — the Gradle build](#scenario-7--the-gradle-build)
- [Scenario 8 — the launcher icons](#scenario-8--the-launcher-icons)
- [Scenario 9 — version bump and release](#scenario-9--version-bump-and-release)
- [Scenario 10 — the benchmark](#scenario-10--the-benchmark)
- [Scenario 11 — clean up](#scenario-11--clean-up)
- [Where the output lands](#where-the-output-lands)
- [Troubleshooting](#troubleshooting)

---

## What you need

| | |
|---|---|
| Host | Linux (the reference host is WSL2 on Ubuntu) — no root, no `sudo` |
| Compiler | GCC for the desktop baseline, the NDK's clang for Android |
| Android SDK | build-tools, one platform, and an NDK — set `WQ_ANDROID_SDK` if it is not in `/home/whereq/android-sdk` |
| JDK | a JDK for `javac`/`d8`/`apksigner` (`keytool` comes with it) |
| Python 3 | only for the icon generator, and it needs Pillow |

Nothing is installed system-wide: the toolchain lives in `toolchain/sysroot/`
and is unpacked from `.deb` files without root.

Check what you have before you start:

```sh
bin/build.sh doctor
```

```
==> Toolchain
    git        /usr/bin/git
    cmake      /home/whereq/android-sdk/cmake/3.22.1/bin/cmake
    javac      /usr/lib/jvm/java-21-openjdk-amd64/bin/javac
    python3    /usr/bin/python3
==> Android SDK
    SDK      /home/whereq/android-sdk
    NDK      /home/whereq/android-sdk/ndk/27.1.12297006
    build-tools /home/whereq/android-sdk/build-tools/36.0.0/
    platform /home/whereq/android-sdk/platforms/android-36
```

---

## The commands

```
bin/build.sh <command> [options]
```

| Command | What it does |
|---|---|
| `doctor` | report the state of the toolchain and the build tree |
| `bootstrap` | fetch the SDL2 sources and build the rootless toolchain |
| `native` | build the desktop Linux baseline |
| `android` | build the Android native libraries (`libdosbox.so`) |
| `apk` | package the APK |
| `all` | `bootstrap` + `native` + `android` + `apk` |
| `icons` | regenerate the launcher icons from `resources/` |
| `clean` | remove the build outputs |
| `distclean` | remove everything that was generated, toolchain included |
| `help` | the built-in help |

| Option | |
|---|---|
| `--release` | build the release APK (needs the signing password) |
| `--abis "a b c"` | the ABIs to build — default `arm64-v8a armeabi-v7a x86_64` |
| `--jobs N` | parallel jobs (default `nproc`) |
| `--keystore PATH` | keystore for the release APK |
| `--ks-pass PASS` | keystore password |
| `--key-pass PASS` | key password |
| `--alias NAME` | key alias |
| `--version-code N` | `android:versionCode` for the APK |
| `--version-name NAME` | `android:versionName` for the APK |
| `--output PATH` | write the APK here |
| `--no-bootstrap` | skip the bootstrap in `all` |

---

## Scenario 1 — first build on a fresh clone

Two bootstrap steps are needed once per clone: the SDL2 sources (they are not
committed — 100+ MB of upstream code) and the rootless toolchain.

```sh
git clone git@github.com:whereq/whereq-dosbox.git
cd whereq-dosbox

bin/build.sh bootstrap
```

```
==> Bootstrap: SDL2 sources + rootless toolchain
    SDL2 sources -> third_party/SDL2-2.30.0
    toolchain -> toolchain/sysroot/
```

or, if you prefer the scripts directly:

```sh
./third_party/fetch-sdl2.sh          # SDL2 2.30.0 -> third_party/SDL2-2.30.0
./toolchain/bootstrap-sysroot.sh     # rootless toolchain -> toolchain/sysroot/
```

`toolchain/env.sh` has to be **sourced**, not executed — it exports
`PATH`, `ANDROID_NDK_HOME` and the autotools environment into your shell:

```sh
. toolchain/env.sh
```

`bin/build.sh` sources it for you, so you only need it by hand when you run
the individual scripts or want `cmake`/`ninja` in your own shell.

---

## Scenario 2 — the desktop Linux baseline

The baseline build is the fastest way to check that the emulator itself still
works. It is an out-of-tree (VPATH) autoconf build, so the source tree stays
clean.

```sh
bin/build.sh native
```

```
==> Native desktop baseline (Linux)
=== binary ===
-rwxr-xr-x 1 whereq whereq 16770496 /home/whereq/git/whereq-dosbox/build/install/bin/dosbox
```

Run it:

```sh
./build/install/bin/dosbox -version
# WhereQ-DOSBox version 1.0.0.1, copyright 2002-2019 DOSBox Team.
```

A headless smoke test, useful in CI:

```sh
cd /tmp && SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  /home/whereq/git/whereq-dosbox/build/install/bin/dosbox -c "exit"
```

The underlying script is `native/build-native.sh` — it configures with
`--disable-dynamic-core --disable-debug --without-curses`, which mirrors the
Android feature set (no x86 JIT, no curses debugger).

---

## Scenario 3 — the Android native libraries

The Android build links SDL2 statically into `libdosbox.so`, so the APK ships
exactly one native library per ABI.

```sh
bin/build.sh android
```

```
==> Android native libraries: arm64-v8a armeabi-v7a x86_64
=== DOSBox for arm64-v8a ===
[100%] Built target dosbox
--- arm64-v8a ---
-rwxr-xr-x 1 whereq whereq 4734600 .../build/android/arm64-v8a/libdosbox.so
```

Just one ABI (the one your phone needs):

```sh
bin/build.sh android --abis "arm64-v8a"
```

A specific subset, e.g. an arm64 phone plus the x86_64 emulator image:

```sh
bin/build.sh android --abis "arm64-v8a x86_64"
```

Start from scratch (after changing a compiler flag, for example):

```sh
rm -rf build/android/arm64-v8a
bin/build.sh android --abis "arm64-v8a"
```

A clean build prints **no warnings**: the port's own code is compiled with
`-Wall` and the vendored DOSBox core has the upstream noise switched off for
the vendored sources only (see `android/CMakeLists.txt`).

The underlying script is `android/build-android.sh`; it also builds SDL2 for
the ABI if that is missing.

---

## Scenario 4 — the debug APK

```sh
bin/build.sh apk
```

```
==> APK (debug)
--- manifest ---
package: name='com.whereq.dosbox' versionCode='1000001' versionName='1.0.0.1'
application-debuggable
--- contents ---
     99824  classes.dex
  4734600  lib/arm64-v8a/libdosbox.so
  3450388  lib/armeabi-v7a/libdosbox.so
  4972336  lib/x86_64/libdosbox.so
```

The result is `android/app/build/outputs/apk/debug/app-debug.apk`. A debug
build is signed with the auto-generated debug key and stays
`android:debuggable="true"`, so `run-as` works on it.

The version comes from the `VERSION` file at the repo root, which
`bin/release.sh` keeps in sync with the git tag. Override it for a test
build:

```sh
bin/build.sh apk --version-name 1.0.0.2 --version-code 1000002
```

`bin/build.sh apk` drives **Gradle** (`android/gradlew`), the canonical build
now that the app layer is Kotlin. The native libraries are built separately by
`bin/build.sh android` and staged into the APK. The old `android/build-apk.sh`
(`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`) is a legacy, Java-only
path reachable with `bin/build.sh apk --legacy-apk`; it cannot compile the
Kotlin sources.

---

## Scenario 5 — the release APK

A release build differs from a debug build in three ways: it is **not**
debuggable, the dex carries no debug information, and it is signed with the
production key.

Set the signing material in the environment:

```sh
export WHEREQ_RELEASE_STORE_PASS='<keystore password>'
export WHEREQ_RELEASE_KEY_PASS='<key password>'    # optional, defaults to the store password
export WHEREQ_RELEASE_ALIAS=whereq                   # optional, default is "whereq"

bin/build.sh apk --release
```

| Variable | Meaning | Default |
|---|---|---|
| `WHEREQ_RELEASE_STORE_PASS` | password that unlocks the keystore file | none — the build refuses to run without it |
| `WHEREQ_RELEASE_KEY_PASS` | password of the private key inside the keystore | the store password |
| `WHEREQ_RELEASE_ALIAS` | name of the key entry | `whereq` |
| `WHEREQ_RELEASE_KEYSTORE` | path to the keystore | `build/release.keystore` |
| `WHEREQ_RELEASE_DNAME` | X.509 name, used only when a new keystore is created | `CN=whereq-dosbox, OU=release, O=whereq, L=-, ST=-, C=DE` |

Keep the password out of the shell history:

```sh
read -rsp "keystore password: " WHEREQ_RELEASE_STORE_PASS; echo
export WHEREQ_RELEASE_STORE_PASS
bin/build.sh apk --release
```

The keystore itself is never committed (`*.keystore` is in `.gitignore`).
If `build/release.keystore` does not exist yet, the script creates it and
reminds you to back it up together with its password — without it the app can
no longer be updated in place.

Verify the result before shipping it:

```sh
BT=$(ls -d /home/whereq/android-sdk/build-tools/*/ | sort -V | tail -1)
APK=android/app/build/outputs/apk/release/app-release.apk

${BT}aapt2 dump badging "$APK" | grep -E "^package|debuggable"   # no debuggable line!
${BT}apksigner verify --verbose "$APK" | grep "Verified using v[123] scheme"
${BT}zipalign -c -v 4 "$APK"
```

The `debuggable` line must be **absent**, and the signature schemes should
all read `true`.

---

## Scenario 6 — install and run it

On a connected device or emulator with `adb` on the `PATH`:

```sh
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.whereq.dosbox/.WhereqDosboxActivity
```

A release APK cannot be installed over a debug-signed one (different
signature). Uninstall first:

```sh
adb uninstall com.whereq.dosbox
adb install android/app/build/outputs/apk/release/app-release.apk
```

`android/run-emulator.sh` drives the Windows-side emulator from WSL2 and
wraps the same commands:

```sh
./android/run-emulator.sh boot      # start the emulator and wait for it
./android/run-emulator.sh install   # build + install + start the app
./android/run-emulator.sh shot /tmp/shot.png
./android/run-emulator.sh log       # dump logcat
./android/run-emulator.sh shell "getprop ro.product.cpu.abi"
```

---

## Scenario 7 — the Gradle build

`android/app/` is a normal Gradle project for anyone with Android Studio. It
produces an equivalent APK, and it reads the same signing variables.

```sh
cd android
./gradlew assembleDebug
./gradlew assembleRelease
```

The signing configuration prefers `android/keystore.properties` and falls
back to the environment variables:

```properties
# android/keystore.properties  (gitignored)
storeFile=/home/whereq/git/whereq-dosbox/build/release.keystore
storePassword=...
keyAlias=whereq
keyPassword=...
```

Without a keystore, `assembleRelease` falls back to the debug key and stays
debuggable — that build is for local testing only, never for shipping.

---

## Scenario 8 — the launcher icons

The launcher assets are generated from the logo in `resources/`:

```sh
bin/build.sh icons
```

```
==> Launcher icons
logo: resources/WD-logo-4K-4096x4096.png
size: 4096x4096
adaptive background: #0D0D1F
mark: 2425x1369
wrote 16 assets + colors.xml
```

It writes the legacy square and round icons, the adaptive foreground (the
`>_` mark inside the 72/108 safe zone), the adaptive background colour and
the logo the README shows. Do not edit the generated PNGs by hand.

Check that the assets are in sync without writing anything (exit code 1 when
they are not):

```sh
python3 android/make-icons.py --check
```

Use a different artwork:

```sh
python3 android/make-icons.py --source resources/other-logo.png
```

Needs Pillow:

```sh
python3 -m pip install --user pillow
```

---

## Scenario 9 — version bump and release

The version lives in the git tag. `bin/release.sh` bumps it, mirrors it into
the `VERSION` file, commits, tags and pushes:

```sh
bin/release.sh --dry-run -m "Fix the Android audio latency"
```

Preview first — the dry run writes nothing:

```
━━━ Step 1: Check the working tree
━━━ Step 2: Write VERSION
  [dry-run] would write VERSION = 1.0.0.2
━━━ Step 4: Tag
  [dry-run] git tag -a v1.0.0.2 -m "Fix the Android audio latency"
═══ Release v1.0.0.2 ═══
  version      : 1.0.0.2
  versionCode  : 1000002
  build it with:
    ./android/build-apk.sh --version-name 1.0.0.2 --version-code 1000002
```

Then do it for real, and build the matching APK:

```sh
bin/release.sh -m "Fix the Android audio latency"

export WHEREQ_RELEASE_STORE_PASS='<keystore password>'
bin/build.sh apk --release --version-name 1.0.0.2 --version-code 1000002
```

Tags are `v{major}.{minor}.{patch}.{build}` — four numeric segments. The
`android:versionCode` is the same four segments packed into a monotonically
increasing integer (`1.0.0.2` → `1000002`).

---

## Scenario 10 — the benchmark

The CPU-core throughput probe runs the emulator flat out and measures
instructions per wall second:

```sh
bin/build.sh native
./bench/run-bench.sh 3 normal simple
```

```
CORE      ROUND   WALL_SEC    CPU_SEC INSTRUCTIONS          IPS vs_REALTIME
normal        1       3.38       2.26    240060000     71023668   2367.45%
normal        2       2.59       2.26    240060000     92687258   3089.57%
   -> BEST normal: 92687258 IPS  (wall 2.59s, cpu 2.26s)
simple        1       2.51       2.08    240060000     95641434   3188.04%
simple        2       2.57       2.23    240060000     93408560   3113.61%
   -> BEST simple: 95641434 IPS  (wall 2.51s, cpu 2.08s)
```

The notes on what counts as a valid metric are in
[`porting/BENCH-NOTES.md`](porting/BENCH-NOTES.md).

---

## Scenario 11 — clean up

Remove the build outputs but keep the toolchain and the SDL2 sources:

```sh
bin/build.sh clean
```

Remove everything that was generated, toolchain and SDL2 sources included:

```sh
bin/build.sh distclean
```

After a `distclean` the next build starts from the bootstrap again:

```sh
bin/build.sh all
```

---

## Where the output lands

```
build/
  apk/whereq-dosbox-debug.apk        debug APK
  apk/whereq-dosbox-release.apk      release APK
  android/<abi>/libdosbox.so         native library per ABI
  android/<abi>/libdosbox.so.unstripped
  install/bin/dosbox                 desktop baseline binary
  native/                            the autoconf build tree
  sdl2/<abi>/lib/libSDL2.a           the static SDL2 per ABI
  release.keystore                   the production key (never committed)
  debug.keystore                     the development key (never committed)
```

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `toolchain/env.sh must be sourced, not executed` | you ran it instead of sourcing it: `. toolchain/env.sh` |
| `cmake: command not found` | the toolchain is not on the `PATH` — source `toolchain/env.sh` |
| `No such file or directory: .../configure` | the tree was cloned without it: run `bin/build.sh bootstrap` |
| `failed to load the jniLibs` in Gradle | build the libraries first: `bin/build.sh android` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | a release APK over a debug install: `adb uninstall com.whereq.dosbox` first |
| `keystore password was incorrect` | wrong password, or the keystore was created with a different one — check with `keytool -list -keystore build/release.keystore` |
| The emulator shows a black screen | the SDL2 static library is stale: `rm -rf build/sdl2 && bin/build.sh android` |

---

## See also

- [`android/README.md`](android/README.md) — the Android side in detail
- [`porting/ANDROID-PORT.md`](porting/ANDROID-PORT.md) — the porting log: what was verified, what was corrected, what is still open
- [`porting/BENCH-NOTES.md`](porting/BENCH-NOTES.md) — the benchmark and what its numbers mean
- [`README.md`](README.md) — the project overview
