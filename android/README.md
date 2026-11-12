# whereq-dosbox — Android native port

Native Android build of the em-dosbox (DOSBox r4305 / SDL2) tree.

## Architecture

```
Java app (SDLActivity subclass)  ──►  SDL2 Android (SDLActivity)  ──►  libdosbox.so
   ├─ org.libsdl.app.*      (copied from third_party/SDL2)   (this repo)
   ├─ WhereqDosboxActivity  (immersive mode, config bootstrap)
   └─ libdosbox.so          (DOSBox emulator + SDL2 back end, statically linked SDL2)
```

`libdosbox.so` contains the *whole* emulator — CPU cores, DOS kernel, hardware,
shell — plus the SDL2 back end (`src/gui/sdlmain.cpp`). SDL2 itself is linked
**statically** into it, so the APK ships exactly one native library.

No source patch is needed for the entry point: `SDL_main.h` redirects DOSBox's
`main()` to `SDL_main()` and gives it C linkage, which is exactly what SDL2's
`SDLActivity` calls through `SDL.nativeMain()`.

The app also needs no upstream patch for its *environment*: `android/jni/
dosbox_android.cpp` sets `HOME` to the app-private files directory and
`chdir()`s there, so DOSBox's `~/.dosbox` and the `-conf dosbox.conf` path
resolve inside the app sandbox.

## App icon

The launcher icon comes from `resources/whereq-dosbox-3.png` at the repo root.
It is pre-rendered into the Android resource tree:

| Asset | What it is |
|---|---|
| `res/mipmap-*/ic_launcher.png` | legacy launcher icon, 48/72/96/144/192 px |
| `res/mipmap-*/ic_launcher_round.png` | the same art with a circular mask |
| `res/mipmap-*/ic_launcher_foreground.png` | adaptive-icon foreground: the art inside the 72dp safe zone of a 108dp canvas |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon (API 26+): black background + the foreground above |
| `res/values/colors.xml` | `ic_launcher_background`, the black of the artwork |

The manifest points at them with `android:icon="@mipmap/ic_launcher"` and
`android:roundIcon="@mipmap/ic_launcher_round"`.

To change the icon, replace `resources/whereq-dosbox-1.png` and re-render the
mipmaps (Pillow is enough):

```sh
python3 - <<'EOF'
from PIL import Image
im = Image.open('resources/whereq-dosbox-1.png').convert('RGB')
...  # crop to the artwork, square it, resize into the mipmap densities
EOF
```

## Prerequisites

| Tool | Where | Notes |
|---|---|---|
| NDK r27 | `/home/whereq/android-sdk/ndk/27.1.12297006` | `ANDROID_NDK_HOME` |
| cmake ≥ 3.22 | `/home/whereq/android-sdk/cmake/3.22.1/bin` | added to PATH by `toolchain/env.sh` |
| SDL2 sources | `third_party/SDL2-2.30.0` | upstream tarball, unmodified |

`source toolchain/env.sh` once to get all of the above on `PATH`.

## Building from a fresh clone

Nothing that is needed to build is committed (SDL2 is 100+ MB of upstream
code, the toolchain is a private sysroot), so a fresh clone starts with two
bootstrap steps:

```sh
git clone git@github.com:whereq/whereq-dosbox.git
cd whereq-dosbox

./third_party/fetch-sdl2.sh          # SDL2 2.30.0 sources -> third_party/
./toolchain/bootstrap-sysroot.sh     # rootless toolchain  -> toolchain/sysroot/
. toolchain/env.sh                   # cmake / nasm / m4 / pkg-config on PATH

./android/build-android.sh           # libdosbox.so for all three ABIs
./android/build-apk.sh               # build/apk/whereq-dosbox-debug.apk
```

`bootstrap-sysroot.sh` unpacks the Ubuntu `.deb` files the *desktop* build
needs (autoconf/automake/m4, pkg-config, nasm, SDL2 headers, ncurses,
strace) into `toolchain/sysroot/` **without root** and rewrites the absolute
paths that Debian bakes into those scripts, so that aclocal/autom4te find
their m4 macros inside the sysroot instead of in `/usr/share`. It needs the
apt package lists to be present (`sudo apt-get update` once).

The desktop baseline harness builds the same way:

```sh
./native/build-native.sh             # -> build/install/bin/dosbox
```

## Build

```sh
# everything (SDL2 -> libdosbox.so -> strip) for both ABIs
./android/build-android.sh

# or step by step:
./android/build-sdl2.sh arm64-v8a        # SDL2 -> build/sdl2/<abi>/lib/libSDL2.a
cmake -S android -B build/android/arm64-v8a \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-24 \
      -DCMAKE_BUILD_TYPE=Release \
      -DSDL2_PREFIX="$PWD/build/sdl2/arm64-v8a" \
      -DCMAKE_PREFIX_PATH="$PWD/build/sdl2/arm64-v8a"
cmake --build build/android/arm64-v8a -j"$(nproc)"
```

`build-android.sh` also strips the library and copies it into
`android/app/src/main/jniLibs/<abi>/` once the Gradle side exists.

### Verified output

| ABI | artifact | size (stripped) |
|---|---|---|
| arm64-v8a | ELF64 aarch64 | 4,753,376 B |
| armeabi-v7a | ELF32 ARM EABI5 | 3,470,108 B |
| x86_64 | ELF64 x86-64 | 4,991,632 B (emulator only) |

All export `SDL_main` (the entry point SDL2's `SDLActivity` calls through
`SDL.nativeMain()`) and `JNI_OnLoad`, and link against nothing but the
Android platform libraries — SDL2 is statically linked in.

## APK

Gradle is not installed in this environment, so `build-apk.sh` drives the SDK
build-tools directly:

```sh
./android/build-apk.sh                       # debug, all ABIs found in build/android
./android/build-apk.sh x86_64 arm64-v8a      # debug, a subset
```

`aapt2 compile/link` → `javac` → `d8` → `zip` → `zipalign` → `apksigner`,
producing `build/apk/whereq-dosbox-debug.apk` (signed with a generated debug
keystore in `build/debug.keystore`).

The APK layout matters and is easy to get wrong:

```
lib/<abi>/libdosbox.so   <- native libraries; Android only extracts THIS path
assets/dosbox.conf       <- read by WhereqDosboxActivity at startup
classes.dex
```

## Building the production APK

```sh
# 1. build the native libraries for all three ABIs (stripped)
./android/build-android.sh

# 2. build the release APK
WHEREQ_RELEASE_STORE_PASS='<your password>' \
  ./android/build-apk.sh --release --version-code 1 --version-name 0.1.0
```

The result is `build/apk/whereq-dosbox-release.apk`.

What the release build does differently:

| | debug | release |
|---|---|---|
| signing key | generated `build/debug.keystore` | `build/release.keystore` (or `$WHEREQ_RELEASE_KEYSTORE`) |
| `android:debuggable` | `true` | absent → `false` |
| `d8` | with debug info | `--release` (no debug info, smaller dex) |
| `run-as` | works | `package not debuggable` |

The password has **no default** on purpose — a production key with a
well-known password is not a production key. It is read from
`WHEREQ_RELEASE_STORE_PASS` (and optionally `WHEREQ_RELEASE_KEY_PASS`), or
you can pass `--ks-pass` / `--key-pass` / `--keystore` / `--alias`.

If the keystore does not exist yet, the script creates it and tells you to
back it up:

> **Back up `build/release.keystore` together with its password.** Without
> it the app can no longer be updated in place — a new key means a new
> signature and Android refuses the upgrade.

Other options:

```sh
./android/build-apk.sh --release --version-code 7 --version-name 0.7.0
./android/build-apk.sh --release --output /tmp/wq.apk
./android/build-apk.sh --help
```

`android:debuggable` is **not** in `AndroidManifest.xml` any more: the debug
build gets it from aapt2's debug-mode flag, the release build must not have
it.

### Installing it on the emulator

The release APK is signed with a different key than the debug one, so the
debug build has to be uninstalled first:

```sh
./android/run-emulator.sh shell pm uninstall com.whereq.dosbox
APK_NAME=whereq-dosbox-release.apk ./android/run-emulator.sh install
```

### With Gradle / Android Studio

`android/app/build.gradle` is the same project for people who have a normal
Android Studio setup:

```sh
cd android && ./gradlew assembleDebug     # build/outputs/apk/debug/app-debug.apk
cd android && ./gradlew assembleRelease   # build/outputs/apk/release/app-release.apk
```

The release key is taken from `WHEREQ_RELEASE_KEYSTORE` /
`WHEREQ_RELEASE_STORE_PASS` / `WHEREQ_RELEASE_ALIAS`, or from a
`android/keystore.properties` file:

```properties
storeFile=/abs/path/to/release.keystore
storePassword=...
keyAlias=whereq
keyPassword=...
```

Both paths are never committed (`.gitignore` covers `*.keystore` and
`keystore.properties`).

> The Gradle files are **not exercised by this repository's own build** —
> the canonical build is `android/build-apk.sh`, which drives the SDK
> build-tools directly. The Gradle build additionally generates a copy of the
> manifest without the `android:package` attribute, which AGP 8 rejects but
> aapt2 needs.

#### Gradle vs. the script: verified equivalent

The Gradle path was verified on 2026-11-10 with Gradle 8.7 + AGP 8.1.4 on
JDK 21, against the same three ABIs and the same keystore as
`build-apk.sh --release`:

| | `build-apk.sh --release` | `gradle assembleRelease` |
|---|---|---|
| certificate SHA-256 | `f3e3c14a…547e8e` | **identical** |
| versionCode / versionName | 7 / 0.7.0 | **identical** |
| signature schemes | v1 + v2 + v3 | **identical** |
| `zipalign -c 4` | passes | passes |
| `libdosbox.so` per ABI | CRC-32 `f9a99e92` / `7f941d12` / `a587b2e5` | **identical** |
| `classes.dex` classes | 53 | 54 (the extra one is `SDLSurface$$ExternalSyntheticApiModelOutline0`, generated by AGP's API desugaring) |
| `android:debuggable` | absent | absent (`run-as` → *package not debuggable*) |
| APK size | 5,088,006 B | 13,215,078 B |

The size difference is **storage, not content**: AGP's `useLegacyPackaging=false`
stores the `.so` files uncompressed and sets `android:extractNativeLibs=false`
(so the library is mmap'ed straight out of the APK), while the script deflates
them. The three native libraries are byte-identical in both packages.

Both APKs were installed on the emulator and ran: the DOSBox banner appears,
the `C:\>` prompt is reached and the on-screen keyboard is shown
(`porting/screenshots/n5-gradle-release.png`).

The Gradle build prints `Unable to strip the following libraries, packaging
them as they are: libdosbox.so.` — that is expected and harmless: the
libraries are already stripped by `build-android.sh` (verified: no `.symtab`
section in any of the three), and AGP only strips libraries it built itself.

Versions are passed identically to both builds:

```sh
./android/build-apk.sh --release --version-code 7 --version-name 0.7.0
cd android && ./gradlew assembleRelease -PappVersionCode=7 -PappVersionName=0.7.0
```

### Gotchas hit while building this

* SDL2 2.30.0's Android sensor back end uses `ALooper_pollAll`, unavailable in
  NDK r27 → build SDL2 with `-DSDL_SENSOR=OFF` (already in `build-sdl2.sh`).
* The NDK toolchain sets `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY`, which hides
  the out-of-tree SDL2 prefix → the CMakeLists sets it to `BOTH` and offers an
  explicit `-DSDL2_PREFIX=` fallback.
* Link `GLESv1_CM` and `GLESv2` explicitly: SDL2's OpenGLES renderers call
  `glViewport`/`glUseProgram` directly, and that normally arrives through
  SDL2's `INTERFACE_LINK_LIBRARIES`, which is lost on the fallback path.
* `SDL_STATIC_PIC=ON` is required — a static archive linked into a shared
  object must be position independent.
* **`android.jar` has no `java.lang.invoke.LambdaMetafactory`**, so SDL2's
  `SDLAudioManager.java` (which uses a lambda and a method reference) cannot be
  compiled against it — javac fails and d8 then aborts with *"Unable to find
  method metafactory"*, leaving an incomplete `classes.dex`. The four stream
  call sites were rewritten as plain loops (which also removes the dependency
  on `java.util.stream`, i.e. API 24+).
* A native library stored anywhere but `lib/<abi>/` inside the APK is silently
  ignored by the installer → `System.loadLibrary("dosbox")` would fail.

## Running it on the emulator

```sh
./android/run-emulator.sh install    # boot emulator, install APK, start app
./android/run-emulator.sh shot out.png
./android/run-emulator.sh log
```

This host is WSL2 on Windows and the only Android SDK available is the Windows
one under `/mnt/c`, so the emulator and adb run as *Windows* processes and
every adb call goes through `cmd.exe`. WSL2 (NAT mode) cannot reach the
Windows loopback, so the Linux adb server is useless here.

One trap: the Windows **adb server must be running before the emulator
starts**, otherwise the device stays `unauthorized` forever. `run-emulator.sh`
handles the ordering.

## Display output

The DOS picture is drawn through the SDL2 **texture** path
(`output=texture` → `SCREEN_TEXTURE` → `SDL_RenderCopy`) and scaled into
`sdl.clip`, which DOSBox computes itself from `fullscreen=true` +
`fullresolution=desktop`: the picture is fitted into the display
**preserving the aspect ratio** and centred, so the leftover is black
(letterbox). With `aspect=true` the 640×400 VGA text mode is corrected to
4:3, exactly like the software path does.

| display | clip | result |
|---|---|---|
| 2400×1080 (landscape) | `480,0 1440×1080` | 4:3 centred, 480 px bars left/right |
| 1080×2274 (portrait)  | `0,732 1080×810`  | 4:3 centred, bars top/bottom |

`renderer` stays `auto` so SDL picks its GLES2 renderer; the software
renderer cannot be used because it needs SDL's texture framebuffer on the
window, and that framebuffer owns a renderer that would block the texture
path from creating its own.

Three upstream behaviours needed `#if defined(__ANDROID__)` patches in
`src/gui/sdlmain.cpp` (details in `porting/ANDROID-PORT.md`, section 6):

* the SDL window **must not be recreated** — on Android it *is* the
  `SurfaceView`, so `SCREEN_TEXTURE` windows are created with
  `SDL_WINDOW_OPENGL` and the window is created once, with the final screen
  type;
* the DOSBox splash screen is skipped — it needs `SDL_GetWindowSurface()`,
  which brings up the conflicting texture framebuffer;
* the texture is destroyed **before** the renderer — `SDL_DestroyRenderer()`
  frees the renderer's textures, so the upstream order hands
  `SDL_DestroyTexture()` an already freed pointer (use-after-free).

## Touch input and the on-screen keyboard

**Touch** becomes a DOS mouse. The conversion is done in DOSBox itself
(`android_handle_touch()` in `src/gui/sdlmain.cpp`, `#if defined(__ANDROID__)`),
because a finger has to be mapped through the letterboxed `sdl.clip`
rectangle, and because SDL's own touch-to-mouse synthesis reports the
position of the *system* mouse instead of the finger. SDL's synthesis is
switched off (`SDL_HINT_TOUCH_MOUSE_EVENTS=0`) so the mouse is not moved
twice.

DOSBox captures the mouse when it goes fullscreen, which would put it into
SDL's relative mouse mode - on a touch screen that makes every tap useless,
so the capture is disabled on Android.

**The on-screen keyboard** (`VirtualKeyboardView.java`) is drawn on top of
the SDL surface: a slim toolbar that is always visible, plus a foldable
QWERTY panel with a symbol row. Shift/Ctrl/Alt are sticky - tap them, then
tap a character.

No DOSBox patch was needed for the keyboard. DOSBox's SDL2 back end routes
key events through the mapper (`src/gui/sdl_mapper.cpp`), which indexes its
bind list by the *scancode* and calls `KEYBOARD_AddKey()`. So a virtual key
press is simply an SDL key event with the right scancode, pushed in through
`DosboxNative.nativePushKey()`.

Verified on the emulator: `ver` typed on the virtual keyboard prints
`WhereQ-DOSBox version`, backspace deletes, sticky Shift produces upper case, and
taps move the DOS mouse to the computed cell.

## Feature set (Android equivalent of the native baseline)

Enabled: `C_SDLGFX`, `C_FPU`, `C_CORE_INLINE`, `C_UNALIGNED_MEMORY`.

`C_FPU_X86` is **0** on Android: it selects `src/fpu/fpu_instructions_x86.h`,
which contains 28 x86 inline-asm blocks and cannot compile on ARM. The
portable `src/fpu/fpu_instructions.h` (zero asm) is used instead.

Deliberately disabled (see `android/cmake/config-android.h.in` for the reasons):

| Macro | Why |
|---|---|
| `C_OPENGL` | DOSBox's own GL path is desktop fixed-function GL; GLES2 has no fixed pipeline. We use the SDL_Renderer path instead (`SDL_HINT_RENDER_DRIVER=opengles2`). |
| `C_DEBUG`, `C_CURSOUT` | need curses / ncursesw — not available on Android |
| `C_DYNREC` | needs a GCC plugin (not buildable with NDK clang) and Android 10+ forbids executing writable memory |
| `C_DYNAMIC_X86` | x86/x64 only |
| `C_DIRECTSERIAL`, `LINUX` | no usable RS-232 on Android; `LINUX` also switches on the host serial code |
| `HAVE_PWD_H` | bionic's `getpwnam()` is a stub that always returns NULL |
| `C_FPU_X86` | selects x86 inline asm (`fpu_instructions_x86.h`); ARM needs the portable FPU |

## CPU core on Android

There is no JIT available, so the emulator runs the interpreter cores
(`core=normal`, `core=simple`, `core=prefetch`). Measured on the desktop
baseline (x86_64, Intel Core Ultra 7 155H):

| core | IPS | vs. 3 M instr/s real-time budget |
|---|---|---|
| normal | 127,691,489 | 4256 % |
| simple | 133,366,666 | 4446 % |

3,000,000 instr/s is what DOSBox needs to run DOS software at 1× speed
(`CPU_CycleMax = 3000` cycles/ms, `src/cpu/cpu.cpp:53`). Even a large
downscale for ARM64 leaves ample headroom. **On-device numbers still need to
be measured on real hardware.**

## Remaining work

1. **Java app polish** — back button, lifecycle (`onPause`/`onResume` must not
   lose emulator state), `android/app/build.gradle` for people with a normal
   Android Studio setup.
2. **Storage** — `chdir` into the app-private files dir is done (see the JNI
   glue) and `mount c .` + `c:` gives a `C:\>` prompt; what is missing is
   SAF-based import/export for game bundles.
3. **Input** — touch→mouse and the on-screen keyboard are done. Still missing:
   gamepad mapping (the tree has *zero* `SDL_CONTROLLER*` support and DOSBox
   still uses the legacy `SDL_Joystick` API), an on-screen joystick for games,
   and IME / non-ASCII text input.
4. **Audio** — SDL2's Android audio path works, but latency is 20–40 ms;
   tune `blocksize`/`prebuffer` (the Emscripten build used 2048/40).
5. **GLES2 scalers** — optional: reimplement DOSBox's hardware scalers as
   GLES2 shaders, otherwise rely on the CPU scalers.
6. **On-device benchmark** — run `bench/` on a real device to get the ARM64
   numbers.

