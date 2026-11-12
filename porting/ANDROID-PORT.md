# em-dosbox → Android native: migration notes

Target: turn the em-dosbox tree (DOSBox r4305, SDL2 branch) into a native
Android application (NDK + CMake + Java), keeping the emulator core intact.

This document is the running log: what was verified, what was corrected, and
what is still open.

---

## 0. Project layout

```
whereq-dosbox/                 <- git repo (one branch: main)
├── src/ include/ ...          upstream DOSBox sources (still unmodified)
├── android/                   NEW  CMake/NDK project -> libdosbox.so
│   ├── CMakeLists.txt
│   ├── cmake/config-android.h.in
│   ├── build-sdl2.sh
│   ├── build-android.sh
│   ├── build-apk.sh           NEW  APK without Gradle (SDK build-tools only)
│   ├── run-emulator.sh        NEW  boot/install/screenshot the app on an emulator
│   ├── jni/dosbox_android.cpp NEW  HOME + chdir glue for the app sandbox
│   └── app/src/main/          NEW  AndroidManifest, Java (SDLActivity shell), assets
├── native/                    NEW  desktop Linux baseline harness
│   └── build-native.sh
├── bench/                     NEW  CPU-core throughput probe (16-bit DOS .COM + runner)
├── third_party/SDL2-2.30.0/   NEW  upstream SDL2 tarball (for the NDK build)
├── toolchain/                 NEW  rootless dev toolchain (env.sh + local sysroot)
├── porting/                   NEW  this document + BENCH-NOTES.md + screenshots/
└── build/                     gitignored: native build, SDL2, android build, APK
```

Keeping the upstream tree at the repo root means a future import of a newer
DOSBox release is trivial; everything we add lives in clearly separated
top-level directories.

### Remotes

| remote | points at | purpose |
|---|---|---|
| `origin` | `git@github.com:whereq/whereq-dosbox.git` | this project — the only remote |

The port work lives on `main`. The DOSBox core is **not** carried as upstream
commit history: it is imported as a single commit ("Import DOSBox r4305 (SDL2)
from dreamlayers/em-dosbox"), so this repository has one author and one
linear history.

There is deliberately **no `upstream` remote**. The repository stands on its
own: `origin` is the only remote, and the em-dosbox fork
(`git@github.com:dreamlayers/em-dosbox.git`) is referenced by URL in the
import commit message rather than tracked as a remote.

To pick up a newer DOSBox release later, fetch the fork explicitly and
re-import it as a single commit:

```
git fetch git@github.com:dreamlayers/em-dosbox.git em-dosbox-svn-sdl2
git read-tree --prefix= -u FETCH_HEAD    # or: git checkout FETCH_HEAD -- .
```

Because the import commit is a verbatim copy of upstream's tip
(`e1f72dd1`, "Update to DOSBox r4305"), re-importing a newer tip has no
content conflict — the DOSBox sources change, the whereq directories do not.

### Building from a fresh clone (verified 2026-11-10)

A clone of the GitHub repo builds from scratch with two bootstrap steps;
everything else is in the repo:

```
git clone git@github.com:whereq/whereq-dosbox.git
./third_party/fetch-sdl2.sh        # SDL2 2.30.0 sources
./toolchain/bootstrap-sysroot.sh   # rootless toolchain (no root, no sudo)
. toolchain/env.sh
./android/build-android.sh arm64-v8a   # -> libdosbox.so (4,734,232 B)
./android/build-apk.sh                 # -> build/apk/whereq-dosbox-debug.apk
./native/build-native.sh               # -> build/install/bin/dosbox (16,768,200 B)
```

`toolchain/bootstrap-sysroot.sh` (new in this commit) unpacks the Ubuntu
`.deb` files the desktop build needs into `toolchain/sysroot/` and rewrites
the absolute paths Debian bakes into them. Three traps came out of that:

* **`pkg-config` is a stub.** On Ubuntu 24.04 `pkg-config` is a transitional
  package; the binary is in `pkgconf-bin` and the shared library in
  `libpkgconf3`. Without both, `pkg-config` dies with *"error while loading
  shared libraries: libpkgconf.so.3"*.
* **The .deb scripts hardcode `/usr/share`.** `aclocal` looks for its perl
  modules in `/usr/share/automake-1.16` and scans `/usr/share/aclocal-1.16`
  (assembled at runtime from the literal `'/usr/share/aclocal-'` plus the
  version), and `autom4te.cfg` prepends `/usr/share/autoconf` for the m4
  macros. All of those have to be rewritten to the sysroot, otherwise
  `autogen.sh` dies with *"Can't locate Automake/Config.pm"* or
  *"m4sugar/m4sugar.m4: no such file or directory"*.
* **`config.guess` / `config.sub` are not installed by `automake
  --add-missing`** (they come from the GNU config package). They are in the
  `autoconf` package under `build-aux/`, so `native/build-native.sh` copies
  them into the source root before running `autogen.sh`.

---

## 0b. Releases and the tag scheme

The version lives in one place: the **git tag**. `bin/release.sh` computes the
next version, mirrors it into the `VERSION` file at the repo root, commits,
creates the annotated tag and pushes both.

```
bin/release.sh -m "Fix the rootless toolchain bootstrap"
bin/release.sh --patch -m "Tune the Android audio latency"
bin/release.sh --dry-run -m "Preview the next release"
```

### Tag naming

```
v{major}.{minor}.{patch}.{build}
```

Four numeric segments, `v` prefixed — the same scheme the other whereq
repositories use (`ccbus.cc`, `chroniq.cc`, `caijing.today`,
`flowdesk.top`). The build counter auto-increments; `--major`, `--minor` and
`--patch` bump those segments as well. The first release on this clone is
`v1.0.0.1`.

Only tags matching `^v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$` count when the next
version is computed, so anything else — the upstream DOSBox tags
(`dosbox-0.74`, `r4010-20170505`, `r4010-20221207`) among them — is ignored.

### What the tag drives

| Consumer | How it gets the version |
|---|---|
| `VERSION` (repo root) | written by `bin/release.sh` from the tag |
| `android/build-apk.sh` | reads `VERSION`; `--version-name` / `--version-code` still override |
| `android/app/build.gradle` | reads `VERSION`; `-PappVersionName` / `-PappVersionCode` still override |
| `AndroidManifest.xml` | the fallback values, kept in step with `VERSION` |

`android:versionCode` is the four segments packed into a monotonically
increasing integer:

```
versionCode = major * 1000000 + minor * 10000 + patch * 100 + build
```

so `v1.0.0.1` → `1000001`, and every later release is strictly larger.

### Tag format

The tags are **annotated** (`git tag -a`), unsigned, and carry a release
message — the release note travels with the tag instead of living only in the
commit history. `bin/release.sh` refuses to reuse an existing tag and warns
when `upstream` has moved past the branch base.

---

## 1. Corrections to the initial feasibility report

The first assessment (before any code was run) contained two overstatements.
Both are now corrected by evidence:

| Claim | Correction | Evidence |
|---|---|---|
| "curses is a hard link blocker — `libdebug.a`/`libgui_tk.a` reference curses symbols unconditionally" | **Wrong.** `--with-curses` defaults to *no*, so `C_CURSOUT` is off; `src/gui/curstxt.cpp` only includes curses inside `#ifdef C_CURSOUT`, and `src/debug/debug.cpp` / `debug_gui.cpp` are entirely inside `#if C_DEBUG` (off by default). The native link line is `-lSDL2 -lX11 -lGL` with **no** curses at all. | `build/native/src/Makefile` link line; `configure.ac:342` (`[with_curses=no]`) |
| "`execvp`/`execlp` in `sdlmain.cpp` must be compiled out on Android" | **Overstated.** bionic provides `execvp`/`execlp`, so they compile fine. They are only reached via the `-editconf` / `-opencaptures` command-line flags, which the Android app never passes. They are a *dead code path*, not a build blocker. | `src/gui/sdlmain.cpp:2839,2863,2870,2902`; bionic `<unistd.h>` provides both |

Two further items were confirmed as real and are handled:

* **`C_DYNREC` must be disabled** — `configure.ac:480` enables it whenever
  `c_targetcpu == "arm"`, which includes aarch64 and armv7l. The recompiling
  core needs a GCC plugin (not buildable with NDK clang) and Android 10+
  forbids executing writable memory.
* **`C_DIRECTSERIAL` should be disabled** — `configure.ac:698` defines `LINUX`
  for `*-*-linux*`, which `aarch64-linux-android` matches, switching on the
  host termios/ioctl serial code.

---

## 2. What the desktop baseline proved

* The tree builds **unmodified** for a native Linux target with the
  Android-equivalent feature set (`--disable-dynamic-core --disable-debug
  --without-curses`). No source patch was needed.
* `core_normal` runs **127.7 M instructions/s** on x86_64 (Intel Core Ultra
  155H) — about **42× the 3 M instr/s** that DOSBox needs for 1× real-time DOS
  execution. With the default 3000 cycles/ms budget the emulator kept its
  virtual clock in sync with the host clock while burning only 4.5 % of a core.
* → The "no JIT on Android" risk is **not** a blocker at the interpreter level.
  Real-device ARM64 numbers are still missing (see open items).

Details and the measurement pitfalls are in `porting/BENCH-NOTES.md`.

---

## 3. N1 — CMake/NDK build  ✅ DONE

### Design

`libdosbox.so` contains the entire emulator (CPU cores, DOS kernel, hardware,
shell) **plus** the SDL2 back end (`src/gui/sdlmain.cpp`), with SDL2 linked
**statically**. The APK therefore ships exactly one native library.

The entry point needs no source patch: `SDL_main.h` redirects DOSBox's `main()`
to `SDL_main()` and, because `SDL_main.h` declares it inside `extern "C"`,
the definition inherits C linkage — exactly what SDL2's `SDLActivity` calls
through `SDL.nativeMain()`.

### Result

| ABI | artifact | size (stripped) | `SDL_main` | `nativeRunMain` | `JNI_OnLoad` | undefined `SDL_*` |
|---|---|---|---|---|---|---|
| arm64-v8a | ELF64 aarch64 | 4,753,376 B | exported | present | present | 0 |
| armeabi-v7a | ELF32 ARM EABI5 | 3,470,108 B | exported | present | present | 0 |

`NEEDED` is exactly the Android platform set — `libOpenSLES.so`, `liblog.so`,
`libandroid.so`, `libm.so`, `libdl.so`, `libGLESv1_CM.so`, `libGLESv2.so`,
`libc.so` — with **no SDL2 dependency**, i.e. SDL2 really is statically linked in.

### Files

| File | Purpose |
|---|---|
| `android/CMakeLists.txt` | builds `libdosbox.so`; source lists mirror the upstream `src/*/Makefile.am` |
| `android/cmake/config-android.h.in` | Android replacement for the autoconf `config.h` (base integer types, compiler attributes, feature switches) |
| `android/build-sdl2.sh` | builds SDL2 as a static PIC archive per ABI |
| `android/build-android.sh` | one-shot: SDL2 → libdosbox.so → strip → copy to `jniLibs/` |
| `android/README.md` | build instructions + remaining work |

### Feature switches (Android)

Enabled: `C_SDLGFX`, `C_FPU`, `C_CORE_INLINE`, `C_UNALIGNED_MEMORY`.

Disabled on purpose: `C_OPENGL` (desktop fixed-function GL cannot run on
GLES2 — we use the SDL_Renderer path), `C_DEBUG` / `C_CURSOUT` (curses),
`C_DYNREC` / `C_DYNAMIC_X86` (no JIT), `C_DIRECTSERIAL` / `LINUX` (host serial
code), `HAVE_PWD_H` (bionic's `getpwnam` is a stub).

### Toolchain findings (the four real blockers)

1. **`C_FPU_X86` must be 0 on ARM.** `src/fpu/fpu_instructions_x86.h` contains
   28 x86 inline-asm blocks (`fldl`, `ax`, …) and is selected by
   `#if C_FPU_X86` in `src/fpu/fpu.cpp:44`. With it enabled the ARM build dies
   with *"invalid output constraint '=&am' in asm"*. The portable
   `src/fpu/fpu_instructions.h` has **zero** asm and is used instead — the same
   choice the upstream Visual C config makes for non-x86.
   → This was the only x86-ism left in the compiled sources; a scan of every
   other file we build shows no inline asm at all.
2. **SDL2 2.30.0 + NDK r27**: the Android sensor back end uses
   `ALooper_pollAll`, which NDK r27 marks unavailable → build SDL2 with
   `-DSDL_SENSOR=OFF`.
3. **The NDK toolchain hides external packages.** It sets
   `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY`, so `find_package(SDL2 CONFIG)`
   cannot see our out-of-tree SDL2 prefix → we set the mode to `BOTH` and also
   provide an explicit `-DSDL2_PREFIX=` fallback.
4. **GLES must be linked explicitly.** SDL2's OpenGLES renderers call
   `glViewport`/`glUseProgram`/… directly. Those come from SDL2's
   `INTERFACE_LINK_LIBRARIES`, which is lost when we fall back to a manual
   lookup → we list `GLESv1_CM` and `GLESv2` ourselves. (Static-link order
   matters: SDL2 first.)

Plus one project-specific pitfall: **use the WSL-local SDK**
(`/home/whereq/android-sdk`), not the Windows one on `/mnt/c` — the 9p mount
makes NDK builds crawl. `toolchain/env.sh` now exports `WQ_ANDROID_SDK` /
`WQ_NDK_HOME` for this.

## 4. DOSBox 0.74 behaviours to keep in mind on Android

| Item | Fact | Consequence |
|---|---|---|
| `core` / `cycles` config keys | live in the **`[cpu]`** section, not `[dosbox]` | misplaced keys are silently ignored → the default 3000 cycles/ms throttle stays on |
| `mount c <dir>` | does **not** switch the current drive | DOS file names must carry an explicit drive letter (`C:\OUT.TXT`), otherwise they hit the unmounted A: drive and creation fails silently |
| `int 21h AH=09` | the string must be `$`-terminated | a missing `$` makes DOSBox scan memory until it finds one → looks like a hang with zero CPU usage |
| cycle limiter | `TIMER_AddTick()` gives the CPU `CPU_CycleMax` cycles and advances the virtual clock by 1 emulated ms | emulated time advances **per CPU_CycleMax instructions**; "instructions per BIOS tick" is therefore circular and must not be used as a throughput metric |
| default budget | `CPU_CycleMax = 3000` (`src/cpu/cpu.cpp:53`) | real-time DOS needs **3,000,000 instr/s** |

---

## 5. N2 — Android app shell  ✅ DONE (verified on an emulator)

### What was built

| File | Purpose |
|---|---|
| `android/app/src/main/java/org/libsdl/app/*.java` | the 9 upstream SDL2 Java files, unmodified except `SDLAudioManager` (see below) |
| `android/app/src/main/java/com/whereq/dosbox/WhereqDosboxActivity.java` | `SDLActivity` subclass: `getLibraries()` → `{"dosbox"}`, `getMainSharedObject()` → `<nativeLibraryDir>/libdosbox.so`, `getMainFunction()` → `SDL_main`, `getArguments()` → `{"-conf", "dosbox.conf"}`, immersive fullscreen |
| `android/app/src/main/java/com/whereq/dosbox/DosboxNative.java` | JNI bridge (`nativePrepareEnvironment`, `nativeConfigDir`) |
| `android/jni/dosbox_android.cpp` | sets `HOME` = app files dir and `chdir()`s there, so DOSBox's `~/.dosbox` and the `-conf` path land inside the app sandbox — **no upstream source change needed** |
| `android/app/src/main/assets/dosbox.conf` | `[sdl] output=surface`, `[cpu] core=normal cycles=auto`, `[autoexec] mount c .` + `c:` |
| `android/build-apk.sh` | builds the APK with the SDK build-tools directly (no Gradle in this environment) |
| `android/run-emulator.sh` | drives the app on the emulator from WSL (boot / install / start / screenshot / logcat) |

### Verified on the emulator

Emulator: Android 37.0 x86_64 (WHPX accelerated), `Medium_Phone_API_37.0`.

* `adb install` → **Success**
* app starts, no `AndroidRuntime` crash in logcat; DOSBox's own code is running
  (`PlayerBase::PlayerBase()` from `src/midi/midi.cpp`)
* the screen shows the DOSBox welcome banner and then the **autoexec ran**:
  `Z:\>SET BLASTER=A220 …` → `Z:\>mount c .` → `Drive C is mounted as local
  directory ./` → `Z:\>c:` → **`C:\>`**
* **keyboard input works**: `C:\>dir` lists the mounted directory
  (`1 File(s) 1,030 Bytes`, `2 Dir(s)`) and returns to the prompt

Screenshots: `porting/screenshots/android-dos-prompt.png`,
`porting/screenshots/android-dos-prompt-dir.png`.

### Five real blockers hit while getting there

1. **`javac` cannot compile SDL2's `SDLAudioManager.java` against `android.jar`.**
   The file uses a Java 8 lambda (`Arrays.stream(...).forEach(...)`) and a method
   reference (`AudioDeviceInfo::getId`). `android.jar` has **no**
   `java.lang.invoke.LambdaMetafactory` (Android never shipped it), so javac
   fails with *"cannot find symbol: method metafactory"* and **d8 then aborts
   with *"Unable to find method metafactory"*** — the APK silently ships an
   incomplete `classes.dex`.
   → Rewrote the four stream/lambda call sites as plain loops. Side benefit:
   `java.util.stream` only exists from API 24, and our `minSdk` is 21, so the
   original code would have crashed on API 21–23 anyway.
2. **The build script hid the javac failure.** It piped javac through
   `grep -v … || true`, so a compile error still produced an APK.
   → javac now writes to a log and a non-zero exit aborts the build.
3. **The native library was packaged at the wrong path.** `zip` was run from
   inside `build/apk-work/lib`, which stored the entry as
   `arm64-v8a/libdosbox.so`. Android only extracts libraries from
   **`lib/<abi>/`** — everything else is silently ignored, and
   `System.loadLibrary("dosbox")` would fail at runtime.
   → zip from the work root so the entry is `lib/<abi>/libdosbox.so`.
4. **The assets were missing.** `dosbox.conf` has to live under `assets/` in the
   APK for `getAssets().open("dosbox.conf")` to find it.
   → passed `-A src/main/assets` to `aapt2 link`.
5. **The emulator never registered with adb.** The device stayed `unauthorized`
   because the Windows adb server was in a broken state when the emulator
   booted. → kill/restart the Windows adb server **before** launching the
   emulator; the device then appears as `offline` and turns `device` once the
   guest is up. Note that WSL2 (NAT mode) cannot reach the Windows loopback,
   so the Linux adb server is useless here — every adb call has to go through
   `cmd.exe` (see `android/run-emulator.sh`).

### Known issue left for N3 — the picture is not scaled

*(resolved in N3, see section 6 below)*

The DOSBox picture was drawn 1:1 in the top-left corner of the 2400×1080
surface (640×400). Cause: with `output=surface` DOSBox uses
`SDL_UpdateWindowSurfaceRects()` on a window that is exactly the DOS
resolution, so the 640×400 window surface is blitted into the corner of the
full-screen SurfaceView.

---

## 6. N3 — SDL2 texture output with aspect-correct letterboxing  ✅ DONE

### Goal

The DOS picture must fill the screen proportionally and centred, with black
bars where the screen aspect does not match the DOS picture (letterbox),
instead of being blitted 1:1 into the top-left corner.

### Design: no `SDL_RenderSetLogicalSize`

The N2 note planned to add `SDL_RenderSetLogicalSize(renderer, 640, 480)`.
That turned out to be unnecessary — and wrong. In the `SCREEN_TEXTURE` path
DOSBox already blits through `SDL_RenderCopy(renderer, texture, NULL, &sdl.clip)`,
and `sdl.clip` is computed by DOSBox itself in `GFX_SetupWindowScaled()`:
with `fullscreen=true` + `fullresolution=desktop` it takes the *desktop* size,
fits the DOS picture into it **preserving the aspect ratio**, and centres the
result:

```
ratio_w = fixedWidth  / (draw.width  * draw.scalex)
ratio_h = fixedHeight / (draw.height * draw.scaley)
if (ratio_w < ratio_h) { clip.w = fixedWidth;  clip.h = dos_h * ratio_w; }
else                  { clip.w = dos_w * ratio_h; clip.h = fixedHeight; }
clip.x = (windowWidth  - clip.w) / 2;   // centred
clip.y = (windowHeight - clip.h) / 2;
```

`SDL_RenderSetLogicalSize()` would have fought with that: DOSBox hands its own
`SDL_Rect` to `SDL_RenderCopy()`, and a logical size would additionally
rescale the *renderer output*, i.e. it would scale the already-scaled picture
a second time. So the fix is configuration, not code:

```ini
[sdl]
output=texture          # SCREEN_TEXTURE path -> SDL_RenderCopy scales the texture
fullscreen=true
fullresolution=desktop  # -> DOSBox computes the aspect-correct, centred clip
autolock=false          # touch input must not grab the mouse

[render]
aspect=true             # VGA text mode is 640x400 -> corrected to 4:3
```

`renderer` stays `auto` so SDL picks its **GLES2** renderer. The software
renderer cannot be used here: its `SDL_UpdateWindowSurface()` needs SDL's
*texture framebuffer* on the window, and that framebuffer owns a renderer of
its own — SDL refuses to attach a second renderer to that window.

### Why this works on Android at all

Three upstream behaviours had to be worked around; all patches live in
`src/gui/sdlmain.cpp` and are fenced by `#if defined(__ANDROID__)`, so the
upstream tree stays mergeable.

1. **The window must never be recreated.** On Android the SDL window *is* the
   `SurfaceView`. SDL's GLES2 renderer recreates the window if it does not
   carry `SDL_WINDOW_OPENGL` (`SDL_render_gles2.c`), and every recreation
   tears down the native window and fetches a new one — which can point at a
   surface that is already gone, leaving the emulator running but invisible.
   → `GFX_SetSDLWindowMode()` now asks for `SDL_WINDOW_OPENGL` when the screen
   type is `SCREEN_OPENGL` *or* `SCREEN_TEXTURE`, and the startup code creates
   the window with the screen type that is actually going to be used.

2. **The DOSBox splash screen has to be skipped.** It draws through
   `SDL_GetWindowSurface()` + `SDL_BlitScaled()`, and `SDL_GetWindowSurface()`
   brings up SDL's texture framebuffer → a second renderer → the output
   back end can no longer create its own. The Android system splash covers
   the startup anyway.

3. **The texture must be destroyed before the renderer.** `SDL_DestroyRenderer()`
   frees every texture belonging to the renderer (`SDL_render.c`), so the
   upstream order (renderer first, texture second) hands `SDL_DestroyTexture()`
   an already freed pointer. SDL then reads the freed texture's magic number
   — a use-after-free — and complains `Parameter 'texture' is invalid`.
   → on Android the texture and its pixel format are released first.

Plus two diagnostic log lines (`Android texture output: …`,
`Android first frame presented, sdl error: "…"`) that turned the black
screen into a readable one.

### Verification (emulator, API 37, x86_64)

```
Android texture output: renderer=1080x2274 window=1080x2274 clip=0,732 1080x810 texture=640x400
Android texture output: renderer=2400x1080 window=2400x1080 clip=480,0 1440x1080 texture=640x400
Android first frame presented, sdl error: ""
```

Both screen orientations letterbox correctly:

| display | clip | result |
|---|---|---|
| 2400×1080 (landscape) | `480,0 1440×1080` | 4:3 centred, 480 px black bars left/right |
| 1080×2274 (portrait)  | `0,732 1080×810`  | 4:3 centred, black bars top/bottom |

The scaling was verified against the pixels of the screenshot rather than by
eye. The DOSBox banner in the corner is a 70-column character painting
(`src/shell/shell.cpp:601`), so its width must be exactly 70 characters:

* measured banner width  **1260 px** = 70 × 18 px → 18 px per character
  = 8 source pixels × **2.25** (the horizontal scale, 1440/640)
* measured banner height **561 px** = 13 lines × 43.2 px
  = 16 source pixels × **2.70** (the vertical scale, 1080/400)

The horizontal and vertical scales differ by exactly 1.2 — that is
`aspect=true` doing its job: a VGA text mode is 640×400 pixels displayed on a
4:3 CRT, so the pixels are 1.2× taller than they are wide. The result matches
what DOSBox's software path produces (there the aspect table duplicates 1.2
lines per source line instead of letting the renderer stretch them), so the
character shapes are the same in both output back ends.

Screenshots: `porting/screenshots/android-n3-texture-boot.png` (start-up
banner) and `android-n3-texture-letterbox.png` (after `dir`, keyboard input
verified working).

### Build

All three ABIs rebuilt, stripped and repackaged:

```
build/android/arm64-v8a/libdosbox.so    4,729,176 B
build/android/armeabi-v7a/libdosbox.so  3,445,780 B
build/android/x86_64/libdosbox.so        4,966,784 B
build/apk/whereq-dosbox-debug.apk       5,079,818 B   (3 ABIs)
```

---

## 7. N4 — touch input and on-screen keyboard  ✅ DONE

### The keyboard needed no DOSBox patch at all

DOSBox's SDL2 back end does not decode `SDL_KEYDOWN` itself. `GFX_Events()`
hands the event to the mapper (`src/gui/sdl_mapper.cpp`), which indexes its
bind list by the **scancode** and activates the matching `CKeyEvent`; that
event calls `KEYBOARD_AddKey()`, which fills the BIOS keyboard buffer the
DOS shell reads:

```
VirtualKeyboardView (Java)
  → DosboxNative.nativePushKey(scancode, pressed)      [JNI]
  → SDL_PushEvent(SDL_KEYDOWN / SDL_KEYUP)
  → GFX_Events() → MAPPER_CheckEvent()
  → CKeyBindGroup::CheckEvent()   (uses event.key.keysym.scancode)
  → CKeyEvent::Active() → KEYBOARD_AddKey(KBD_x, pressed)
  → BIOS keyboard buffer → DOS shell
```

So a virtual key press is exactly an SDL key event with the right scancode.
The on-screen keyboard is just another producer of those events - the same
way the hardware keyboard is.

`android/jni/dosbox_android.cpp` carries a `key_name_map[]` that translates
the key names used by the keyboard UI into SDL scancodes. The names
deliberately follow DOSBox's own table (`DefaultKeys[]` in
`src/gui/sdl_mapper.cpp`), so every key of the virtual keyboard has a 1:1
counterpart in the mapper's bind list - which makes it easy to check that
nothing is missing.

### Touch input: why SDL's own conversion is not enough

SDL's Android back end turns touches into mouse events
(`SDLSurface.onTouch` → `SDL_SendTouch` → synthetic `SDL_MOUSEMOTION` /
`SDL_MOUSEBUTTONDOWN`). That conversion has two problems here:

1. **the position is wrong.** The synthesised mouse events report the
   position of the *system* mouse, not of the finger. Measured: a tap at
   `(250, 600)` produced `mouse motion x=0 y=0`.
2. **the mapping is missing.** The DOS picture is letterboxed into
   `sdl.clip`, so the finger position has to be mapped through that
   rectangle before it means anything in DOS coordinates - and `sdl.clip`
   is DOSBox's own state.

So the conversion is done in DOSBox instead (`android_handle_touch()` in
`src/gui/sdlmain.cpp`, fenced by `#if defined(__ANDROID__)`): the finger
position is turned into window pixels, mapped through `sdl.clip`, and handed
to `Mouse_CursorMoved()` / `Mouse_ButtonPressed()`. SDL's synthesis is
switched off with `SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0")`, otherwise
the mouse would be moved twice.

### The relative-mouse trap

DOSBox captures the mouse when it goes fullscreen
(`GFX_CaptureMouse()` → `SDL_SetRelativeMouseMode(SDL_TRUE)`), which sets
`sdl.mouse.locked`. With that flag set, `Mouse_CursorMoved()` ignores the
absolute position and only adds up relative movement - so every tap was
ignored and the mouse stayed in the middle of the screen.

On Android there is no real mouse to capture and relative mode has no
meaning, so `doGFX_CaptureMouse()` and `GFX_UpdateSDLCaptureState()` keep
the mouse unlocked on Android.

Measured before and after the fix:

| | tap (700, 300) | tap (1500, 800) | tap (1000, 500) |
|---|---|---|---|
| before | 320, 100 | 320, 100 | 320, 100 |
| after | 97, 55 | 453, 148 | 231, 92 |

The numbers are exact: `0.153 * 80 * 8 = 97`, i.e. the DOS text screen's
80×25 cells at 8 pixels each.

### The keyboard UI

`android/app/src/main/java/com/whereq/dosbox/VirtualKeyboardView.java` draws
everything in code (no layout XML, so the `aapt2` resource step stays
untouched):

* a slim always-on **toolbar** with the keys a DOS program needs most
  (Esc, Tab, Ctrl, Alt, Shift, Enter, Bksp, Space, arrows, F1–F8) plus a
  `⌨` button that folds the full panel away and a `«` button that toggles
  the symbol row;
* a foldable **QWERTY panel** with the function key row, the number row,
  three letter rows, the cursor keys and a row of shifted symbols
  (`! @ # $ % ^ & * ( ) _ + { } | : " < > ? ~`);
* **sticky modifiers**: tapping Shift/Ctrl/Alt arms the modifier, it stays
  pressed until the next ordinary key is typed. DOSBox treats the modifiers
  as ordinary keys of their own (`KBD_leftshift` and friends), so arming
  them is just injecting that key and releasing it after the next character
  - which also makes combinations like Ctrl+C work.

The keyboard is added to the activity's content view, i.e. it is drawn on
top of the SDL `SurfaceView`. It only occupies the strip at the bottom of
the screen, so taps anywhere else still reach the emulator.

### Verified on the emulator

* `ver` typed on the virtual keyboard → `DOSBox version` printed, and the
  backspace key deleted a character that was typed before it;
* sticky Shift produced an upper-case `V`;
* taps moved the DOS mouse to the computed position (table above), and a
  swipe produced a continuous stream of `SDL_FINGERMOTION` events;
* the letterbox is unaffected - both orientations still report the correct
  clip rectangle, and `Android first frame presented, sdl error: ""`.

Screenshots: `porting/screenshots/android-n4-toolbar.png` (folded),
`android-n4-virtual-keyboard-panel.png` (unfolded) and
`android-n4-virtual-keyboard.png` (with the `ver` output).
The touch log is in `porting/logs/android-n4-touch.log`.

### Known limitations

* The unfolded panel covers a large part of the screen. It is semi
  transparent and can be folded away, but DOSBox is not told about the
  covered area, so the picture underneath is hidden rather than scaled.
* Only the first finger drives the mouse; a second finger is ignored.
* There is no IME / non-ASCII input - the keyboard produces key codes, not
  text.
* The mouse cursor is not drawn on top of the DOS picture. DOSBox draws
  its own cursor inside the emulated screen, which in text mode is the CRTC
  hardware cursor and therefore not part of a screenshot.

### Build

```
build/android/arm64-v8a/libdosbox.so    4,734,296 B
build/android/armeabi-v7a/libdosbox.so  3,448,436 B
build/android/x86_64/libdosbox.so        4,972,000 B
build/apk/whereq-dosbox-debug.apk       5,088,010 B  (3 ABIs)
```

The desktop build was rebuilt as well and is unaffected - every Android
specific change is fenced by `#if defined(__ANDROID__)`.

---

## 8. Release (production) APK

The debug build is signed with an auto-generated key and is
`android:debuggable="true"`, so `run-as` can attach to it — handy while
porting, useless for shipping.

```sh
./android/build-android.sh                     # native libs for all three ABIs
WHEREQ_RELEASE_STORE_PASS='<password>' \
  ./android/build-apk.sh --release --version-code 1 --version-name 0.1.0
# -> build/apk/whereq-dosbox-release.apk
```

Three things make the release build different:

1. **Its own signing key.** `build/release.keystore` (or
   `$WHEREQ_RELEASE_KEYSTORE`). The password has no default — deliberately:
   a production key with a well-known password is not a production key. The
   script creates the keystore if it is missing and prints a warning to back
   it up, because without it the app can no longer be updated in place (a
   different key means a different signature, and Android refuses the
   upgrade).
2. **`android:debuggable` is not set.** It used to be hardcoded in the
   manifest; now the attribute is *absent* from `AndroidManifest.xml` and the
   debug build gets it from aapt2's debug-mode flag instead. That way the
   two modes cannot drift apart.
3. **`d8 --release`**, which drops the debug information from the dex
   (99,320 B → 96,656 B).

Verified on the emulator with the release APK:

```
$ adb shell run-as com.whereq.dosbox ls files
run-as: package not debuggable: com.whereq.dosbox

$ adb shell dumpsys package com.whereq.dosbox | grep flags
flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP KILL_AFTER_RESTORE ]
```

No `DEBUGGABLE` in the flags, and the app runs exactly like the debug build
— the DOSBox picture is letterboxed as before and the on-screen keyboard
types `ver` → `DOSBox version`.

Signing schemes: v1 (JAR), v2 and v3 all verify, and the APK passes
`zipalign -c -v 4`.

### The Gradle path, verified against the script

`android/app/build.gradle` provides the same thing for people with Android
Studio (`assembleDebug` / `assembleRelease`). It was **verified on 2026-11-10**
(Gradle 8.7 + AGP 8.1.4, JDK 21), running the same three ABIs and the same
keystore as `build-apk.sh --release`:

```sh
cd android && ANDROID_HOME=/home/whereq/android-sdk \
  WHEREQ_RELEASE_KEYSTORE=/abs/path/release.keystore \
  WHEREQ_RELEASE_STORE_PASS='…' \
  gradle assembleRelease -PappVersionCode=7 -PappVersionName=0.7.0
```

| | `build-apk.sh --release` | `gradle assembleRelease` |
|---|---|---|
| certificate SHA-256 | `f3e3c14a730d74390720c3330096b6c8a8afb897a82870bb225137028f547e8e` | identical |
| versionCode / versionName | 7 / 0.7.0 | identical |
| signature schemes | v1 + v2 + v3 | identical |
| `zipalign -c -v 4` | passes | passes |
| `libdosbox.so` CRC-32 (arm64 / armv7 / x86_64) | `f9a99e92` / `7f941d12` / `a587b2e5` | identical |
| `classes.dex` class count | 53 | 54 — the extra class is `SDLSurface$$ExternalSyntheticApiModelOutline0`, generated by AGP's API desugaring |
| `android:debuggable` | absent | absent (`run-as` → *package not debuggable*) |
| APK size | 5,088,006 B | 13,215,078 B |

The size gap is **storage, not content**: AGP's `useLegacyPackaging=false`
stores the `.so` files uncompressed and sets `android:extractNativeLibs=false`
(the library is mmap'ed straight out of the APK), while the script deflates
them. The three native libraries are byte-identical in both packages.

The Gradle APK was installed on the emulator and ran: the DOSBox banner
appears, the `C:\>` prompt is reached and the on-screen keyboard is drawn.

Three Groovy/AGP traps hit on the way, all in `android/app/build.gradle`:

* `storeFile file(storeFile)` inside the `signingConfigs.release` closure does
  **not** call the `SigningConfig` setter — the local variable `storeFile`
  shadows it, so Groovy parses it as `String.call()` and the build fails. The
  local variables are now named `keystorePath` / `aliasName` to avoid the
  shadowing.
* A `Copy` task needs `into` (the destination directory); `from(...) { into … }`
  alone leaves `destinationDir` unset and the task fails validation.
* AGP 8 refuses the `android:package` attribute that aapt2 needs, so the
  Gradle build works on a generated copy of the manifest with the attribute
  removed (`generateManifest`), while the canonical manifest keeps it as the
  single source of truth.

AGP 8.1.4 warns that it was tested up to `compileSdk 34` only; the warning is
suppressed through `android.suppressUnsupportedCompileSdk=36` in
`gradle.properties`. The app itself uses nothing newer than API 21.

`android/build-apk.sh` remains the canonical build — it needs no Gradle
installation and no dependency download.

### Still open for a real release

* `android:allowBackup="true"` — the app keeps DOS files in its private
  directory; whether those should be backed up is a product decision.
* No ProGuard/R8 shrinking: `minifyEnabled false`, because the native side
  looks Java methods up by name. `android/app/proguard-rules.pro` has the
  keep rules that would make shrinking safe, but the result has not been
  tested on a device.
* No Play Store AAB, no Play App Signing — `bundletool` is not available
  here.

---

## 9. Open items

1. **Java app polish** — back button, lifecycle handling
   (`onPause`/`onResume` must not corrupt emulator state). The Gradle project
   (`android/app/build.gradle`) exists and is verified equivalent to the
   no-Gradle build (see section 8), but it is still not the canonical path.
2. **Storage** — `chdir` to the app-private files dir is done; SAF-based
   import/export for game bundles is still missing.
3. **Input** — touch (as a DOS mouse) and an on-screen keyboard are done
   (see section 7). Still missing: gamepad mapping (the tree references *no*
   `SDL_CONTROLLER*` symbols at all), an on-screen joystick for the
   direction keys, and IME / non-ASCII text input. (The hardware keyboard
   works too — the emulator's soft keyboard is delivered as normal key
   events.)
4. **Audio latency** — tune `blocksize` / `prebuffer` for Android's 20–40 ms
   round trip (the Emscripten build used 2048/40).
5. **GLES2 scalers** — optional; without them we rely on the CPU scalers via
   SDL's renderer.
6. **On-device benchmark** — run the `bench/` probe on real ARM64 hardware.
7. **Licensing** — the tree is GPLv2, so the app must be GPLv2 and open
   source; "DOSBox" is a trademark, so the app needs its own name.

