# WhereQ-DOSBox — technical reference

**Audience:** senior software engineers who have to read, review or modify this
codebase. It assumes familiarity with emulators, C/C++, the JVM/Android and
build systems; it does not assume familiarity with *this* port.

**Scope:** the technical implementation of WhereQ-DOSBox — the Android native
port of DOSBox, the disk save-state machine, the sound-chip snapshots, the
bundle launcher, the on-screen keyboard, the build pipeline and the release
process.

**Not scope:** the product pitch — that is the top-level
[`README.md`](../README.md) (what the app is and how to use it). This document
covers the rest: *how the thing works*, why it is built that way, what was
measured, what is still open, and what the screenshots show. It supersedes the
migration diary that came before it.

**Companion documents.** [`USER-GUIDE.md`](USER-GUIDE.md) is the guide for the
person holding the phone. [`INTERRUPTS.md`](INTERRUPTS.md) is the interrupt map:
every software interrupt and hardware vector this emulator implements, what
each one is for, and which code answers it.

**Status:** the Android migration phase is **complete** — the milestone tag
`milestone/android-migration-complete` marks it, and it rides on the current
release, `v1.0.0.17` (the phase itself ended with `v1.0.0.10`). From there the
project is in **maintenance**: bug fixes and documentation polish, no new
features. §12 is therefore a list of known gaps, not a roadmap.

**Reading conventions.**

* `path:line` refers to the repository at the current `main`.
* `▸` marks a rule that is easy to break by accident — the kind of thing that
  looks like a detail and turns into a crash.
* Diagrams are plain text in fenced blocks, so they render in a terminal, in a
  diff and on GitHub.
* Numbers quoted in this document were measured, not estimated: the chip
  struct sizes come from a compile-time probe (§2.3, §6.2), the emulator
  numbers from the log of a release APK on an x86_64 AVD (§11).

---

## Table of contents

| § | Topic | What you get out of it |
|---|---|---|
| [1](#1-the-shape-of-the-system) | The shape of the system | The layers, the three threads, what is upstream DOSBox and what is WhereQ |
| [2](#2-the-native-build) | The native build | NDK/CMake/SDL2, the ABI matrix, the integer-size probe, the feature switches |
| [3](#3-the-build-and-release-pipeline) | The build and release pipeline | CI, the tag scheme, the signing chain, the version encoding |
| [4](#4-the-runtime) | The runtime | SDL2 on Android, the renderer, the JNI surface, the frame loop, the configuration |
| [5](#5-the-save-state-machine) | The save-state machine | The whole design: the safe point, the file format, the host pointers, the failure modes |
| [6](#6-the-sound-chips) | The sound chips | The exact OPL snapshot, the pointer re-derivation, the fallback, the PC speaker |
| [7](#7-the-dos-layer-in-the-snapshot) | The DOS layer in the snapshot | Why the open file handles need a whole subsystem |
| [8](#8-the-bundle-launcher) | The bundle launcher | From a `.zip` on the phone to a running DOS program |
| [9](#9-the-on-screen-keyboard) | The on-screen keyboard | The layout model, the modifier model, the stuck-key rule |
| [10](#10-the-game-mode-gamepad) | The Game Mode gamepad | The profiles, the D-pad and its three output modes, the trackball, the remapper, the key path |
| [11](#11-verification) | Verification | How each claim in this document was proven, and the log lines that prove it |
| [12](#12-known-limitations-and-open-items) | Known limitations and open items | What is still not captured, what is still open |
| [13](#13-the-licence-and-the-notices) | The licence and the notices | The GPL screen, the two assets, how it is verified, and the privacy policy |
| [A](#appendix-a--the-opl-register-map-as-implemented) | A: the OPL register map | The address decoding of the chip this build actually uses |
| [B](#appendix-b--the-state-file-block-table) | B: the state file block table | Every block, its order and its size |
| [C](#appendix-c--the-log-line-reference) | C: the log-line reference | Every diagnostic line the port can print |
| [D](#appendix-d--the-upstream-behaviours-that-will-bite-you) | D: the upstream behaviours that will bite you | The silent DOSBox failures that cost real debugging time |
| [E](#appendix-e--the-screenshot-gallery) | E: the screenshot gallery | Every picture the port produced, and what it shows |

---

## 1. The shape of the system

### 1.1 What the app is

WhereQ-DOSBox is **upstream DOSBox r4305 (the SDL2 branch, as maintained by the
em-dosbox fork) plus a thin WhereQ layer**. The port does not rewrite the
emulator; it makes it build for Android, gives it a private writable home, adds
a disk save-state machine that upstream does not have, and puts a phone-shaped
shell around it.

![1.1 What the app is](diagrams/tr01-1-1-what-the-app-is.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
┌───────────────────────────────────────────────────────────────────────────┐
│  Android app layer (Kotlin)                                              │
│                                                                          │
│  WhereqDosboxActivity ── SDLActivity (upstream SDL2 2.30.0, unmodified)  │
│    ├── SurfaceView (SDLSurface)  ←→ SDL2 C library (statically linked)   │
│    ├── VirtualKeyboardView  ── overlay: letters/symbols/functions layers  │
│    ├── GameLauncherView     ── overlay: the "Files" panel, bundles, slots │
│    └── DosboxForegroundService ── keeps the process alive in the background│
│                                                                          │
│  DosboxNative (6 native methods) ─────────────┐                         │
└───────────────────────────────────────────────┼─────────────────────────┘
                                                │ JNI
┌───────────────────────────────────────────────┼─────────────────────────┐
│  WhereQ native layer                          ▼                         │
│                                                                          │
│  android/jni/dosbox_android.cpp                                            │
│    HOME + chdir  → the app's files dir        (sandbox)                  │
│    stdout/stderr → logcat                    (diagnostics)               │
│    6 JNI entry points                                                        
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  DOSBox core (upstream, + WhereQ's save-state hooks)              │    │
│  │                                                                    │    │
│  │   src/dosbox.cpp ──► normal_loop_depth ──► SAVESTATE_RunPending() │    │
│  │   src/gui/savestate.cpp     the machine snapshot                  │    │
│  │   src/dos/dos_state.cpp     the DOS layer of the snapshot         │    │
│  │   src/hardware/{pic,timer,pcspeaker,adlib,dbopl}.cpp              │    │
│  │                             the hardware that has to be snapshotted│    │
│  └────────────────────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────────────┘
```

</details>

### 1.2 The three threads

There are exactly three threads that matter, and the save-state machine is
defined by how they meet.

![1.2 The three threads](diagrams/tr02-1-2-the-three-threads.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
┌─────────────────────────┐
│ Android main thread     │  Activity, overlays, the keyboard
│ (Kotlin)                │  ▸ UI work only; never touches the machine
└──────────┬──────────────┘
           │  JNI call (blocks)
           │  DosboxNative.nativeSaveState(path)
           ▼
┌─────────────────────────┐
│ DOSBox thread           │  SDL_main() → DOSBOX_Init() → DOSBOX_RunMachine()
│ (native, created by     │
│  SDLActivity)           │  ┌───────────────────────────────────────────┐
│                         │  │ while (1) {                               │
│                         │  │   Normal_Loop_Inner()      ← the machine  │
│                         │  │   if (normal_loop_depth == 1)             │
│                         │  │       SAVESTATE_RunPending();  ◄── the    │
│                         │  │   GFX_Events();                           │
│                         │  │ }                                         │
│                         │  └───────────────────────────────────────────┘
│                         │  The snapshot is taken *here*: in guest code,
│                         │  at an instruction boundary, with no host frame
│                         │  holding a copy of the CPU registers.
└──────────┬──────────────┘
           │  SDL_PushEvent()   (thread safe)
           ▼
┌─────────────────────────┐
│ SDL event / audio       │  SDL's own threads
└─────────────────────────┘
```

</details>

The **logcat pump** is a fourth, trivial thread: `android/jni/dosbox_android.cpp`
starts a detached pthread that reads DOSBox's `stdout`/`stderr` out of a pipe
and re-emits them as logcat lines under the tag `DOSBox`.

Two consequences of the thread split drive the whole design:

* A save or a load is a **request**, not a call. The UI thread posts the
  request and blocks on a condition variable; the DOSBox thread performs the
  work when it reaches a safe point (§5.3).
* A key press is an **event**, not a call. The UI thread pushes an
  `SDL_KEYDOWN`/`SDL_KEYUP` into SDL's queue and returns immediately (§4.4).

### 1.3 What is upstream and what is WhereQ

The vendored tree is the em-dosbox fork of DOSBox r4305. Upstream's own logic
is untouched: every change WhereQ made to it is the save-state machine, an
Android fix, or one branding string. But "upstream is untouched" needs one
qualifier, because the port's **first** commit did not stop at the upstream
tip.

The import commit `e119e3e` copied upstream's tip verbatim **and** layered the
Android port on top of it in the same commit. Its own message says so:

```text
Initial commit: WhereQ-DOSBox — a native Android port of DOSBox r4305

The DOSBox emulator core (r4305, SDL2 branch) imported verbatim from the
Em-DOSBox fork, plus a native Android port layered on top of it.
...
Every Android-specific change is fenced behind #if defined(__ANDROID__)
(include/mouse.h, src/gui/sdlmain.cpp, src/ints/mouse.cpp) - the emulator
core itself is untouched.
```

So the boundary between upstream and the port is the `#if defined(__ANDROID__)`
fence, not a commit. The import-time Android layer is:

| File | Import-time Android change |
|---|---|
| `src/gui/sdlmain.cpp` | the texture is destroyed **before** the renderer (SDL frees a renderer's textures with it, so the upstream order hands `SDL_DestroyTexture()` an already freed pointer); the SDL window asks for `SDL_WINDOW_OPENGL` right away, so it is created once and stays bound to the SurfaceView instead of being torn down and re-fetched; the splash screen opens the window with the screen type that will actually be used; `android_handle_touch()` maps a finger to a DOS mouse through the letterbox rectangle instead of using SDL's own touch synthesis (which reports the *system* mouse, so a tap would land at (0, 0)); `SDL_HINT_TOUCH_MOUSE_EVENTS` is switched off so the mouse is not moved twice; `GFX_CaptureMouse()` and `GFX_UpdateSDLCaptureState()` never enter SDL's relative-mouse mode (there is no real mouse, and relative movement would fight the absolute touch mapping); a diagnostic log of the renderer/window/clip/texture geometry |
| `include/mouse.h`, `src/ints/mouse.cpp` | `Mouse_GetPosition()` — an Android-only accessor for the DOS-pixel mouse position, used by the diagnostic log above |

Everything below is the **post-import** work: the complete set of changes to
`src/` and `include/` relative to `e119e3e` — 29 files — so a reviewer can
bound the blast radius.

**New files (added after the import):**

| File | Lines | What it is |
|---|---|---|
| `include/savestate.h` | 71 | the save-state contract: result codes and three C functions |
| `src/gui/savestate.cpp` | 646 | the machine snapshot (§5) |
| `src/dos/dos_state.cpp` | 305 | the DOS layer of the snapshot (§7) |

**WhereQ-only files outside the vendored tree** (present since the import, so
not part of the diff above):

| File | Lines | What it is |
|---|---|---|
| `android/jni/dosbox_android.cpp` | 387 | the JNI bridge and the sandbox setup (§4.3) |
| `android/jni/dosbox_log.h` | 14 | `DOSBOX_LOG` → `__android_log_print(ANDROID_LOG_INFO, "whereq-dosbox", …)` |

**The Kotlin app layer** is a third thing, and it is neither upstream nor part
of the `src/` diff: it is a whole Android application (`android/app/`) that
drives the emulator from the outside. The files that matter for this document:

| File | Lines | What it is |
|---|---|---|
| `WhereqDosboxActivity.kt` | 1400 | the `SDLActivity` subclass: the overlays, the launcher, the per-game preferences (§8, §10.9) |
| `VirtualKeyboardView.kt` | 1262 | the on-screen keyboard (§9) |
| `GameModeView.kt` | 1042 | the touch gamepad (§10) |
| `GameLibrary.kt`, `GameLauncherView.kt` | 816, 603 | the bundle import and the launcher panel (§8) |
| `SaveStore.kt`, `BundleFolder.kt`, `GameBundle.kt` | — | the durable save store and the bundle model (§8.9) |
| `DosboxNative.kt` | — | the Kotlin side of the JNI bridge (§4.3) |
| `BackAction.kt` | — | what a back press does, and in which order (§9.7) |
| `LicensesActivity.kt` | — | the licence screen: the GPL text and the notices (§13) |

Everything in that layer reaches the emulator through the same three JNI calls
— `nativePushKey`, `nativePushMouseButton`, `nativePushMouseMotion` — so the
app adds **no** emulator-core behaviour beyond what §10.7 describes.

**Upstream files with a WhereQ change:**

| File | Change | Why |
|---|---|---|
| `src/dosbox.cpp` | `Normal_Loop` split into `Normal_Loop_Inner` + a depth counter; `SAVESTATE_RunPending()` at depth 1 | a snapshot may only be taken at the outermost loop (§5.3) |
| `src/shell/shell_misc.cpp` | `SAVESTATE_GuestReady()` in `DOS_Shell::InputCommand()` | the shell reached its prompt ⇒ the autoexec (and the game) has run |
| `src/dos/dos_execute.cpp` | `SAVESTATE_GuestReady()` in `DOS_Execute()` | a game started by the autoexec never lets the shell return |
| `src/gui/sdlmain.cpp` | the Android surface lifecycle (`sdl.recreate_window`, `sdl.video_presented`, `GFX_VideoReady()`, `VGA_KillDrawing()` before the texture is recreated, a redraw invalidation after it, `bool` for the alt-state flags) **and** the touch→mouse mapping (`android_touch_cursor()`, the LEFT button held for as long as the finger is down, a second finger as a RIGHT click) | Android's surface lifecycle and the orientation settle (§4.2, §5.3); the touch semantics the games actually read (§4.7) |
| `src/ints/mouse.cpp` | the INT 33h driver gains `MOUSE_StateSize/SaveState/LoadState`: the stable configuration (the AX=0Ch callback, the cursor range, the position, the custom cursor and its hotspot, the sensitivity) is saved, the volatile state (the event queue, the button and click counters, `in_UIR`, the timer) is **reset** on load, and the two cursor-mask pointers are re-derived from the saved cursor type | a restored game has to find its own mouse callback again, and a stale queue or a stuck `in_UIR` wedges the driver (§5.5, §5.6) |
| `include/mouse.h` | the Android-only `Mouse_GetPosition()` accessor is removed | it existed for the touch diagnostic log, which the port replaced (§4.7) |
| `src/hardware/serialport/softmodem.cpp` | one string: `"Modem compiled for DOSBox version "` → `"Modem compiled for WhereQ-DOSBox version "` | the modem announces itself to the guest like everything else (§1.1) |
| `src/cpu/core_dynrec/cache.h` | an `__ANDROID__` branch that `mmap`s the JIT cache RWX from the start | Android enforces W^X: a malloc'd heap may not be made executable (§2.6) |
| `src/hardware/pic.cpp` | `PIC_StateSize/SaveState/LoadState` | the interrupt state is part of the machine (§5.5) |
| `src/hardware/timer.cpp` | `TIMER_StateSize/SaveState/LoadState(f, clock_saved)` | the PIT phase is part of the machine (§5.5) |
| `src/hardware/pcspeaker.cpp` | `SPKR_StateSize/SaveState/LoadState` + a reset on load | the speaker is a real-time device (§6.7) |
| `src/hardware/vga_dac.cpp` | `VGA_DAC_UpdateAllColors()` | the restored palette has to reach the renderer (§5.7) |
| `src/hardware/vga_draw.cpp` | `VGA_RestartFrameTimer()` | the vertical retrace is a PIC event, and the event queue is not in the snapshot (§5.7) |
| `src/cpu/core_dynrec.cpp` | `CPU_Core_Dynrec_Cache_Reset()` | stale translations of replaced memory must never run (§5.7) |
| `src/hardware/adlib.cpp`, `src/hardware/adlib.h` | the OPL block of the snapshot | §6 |
| `src/hardware/dbopl.cpp`, `src/hardware/dbopl.h` | the exact OPL chip snapshot | §6 |
| `include/dos_inc.h`, `include/dos_system.h`, `include/vga.h`, `include/video.h` | the new prototypes | the save-state API |
| `src/gui/Makefile.am`, `include/Makefile.am`, `src/dos/Makefile.am` | the new files | the desktop build stays in sync |
| `src/Makefile.am` | the dos archive is listed a second time, after the gui archive | the save-state code in `gui/` calls into `dos/`, and a linker walks a static archive only once (§2.12) |
| `docs/dosbox.1` | the SEE ALSO section, which pointed at `/usr/share/doc/dosbox`, is removed; the date in the `.TH` line is adjusted, as the page itself asks | that path is a Debian one and this build installs no such directory, so the reference pointed at nothing (§13.5) |

The **autoconf build is untouched**: `configure.ac` and the root `Makefile.am`
have not changed since the import. The Android build is a separate CMake
project that never reuses them (§2.1).

### 1.4 The repository map

![1.4 The repository map](diagrams/tr03-1-4-the-repository-map.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
whereq-dosbox/
├── src/ include/            upstream DOSBox (em-dosbox fork of r4305) + the
│                            WhereQ save-state additions
├── android/
│   ├── CMakeLists.txt       the native project (DOSBox → libdosbox.so)
│   ├── cmake/config-android.h.in   the Android config.h template
│   ├── build-sdl2.sh        SDL2 2.30.0 → build/sdl2/<abi>/lib/libSDL2.a
│   ├── build-android.sh     SDL2 → libdosbox.so → strip → jniLibs
│   ├── build-apk.sh         the legacy javac/d8 APK path (no Gradle)
│   ├── jni/                 the JNI bridge
│   └── app/                 the Kotlin app + the Gradle build
├── third_party/SDL2-2.30.0/ the SDL2 tarball (not committed)
├── toolchain/                env.sh + the rootless sysroot bootstrap
├── native/                   the desktop Linux baseline build
├── bench/                    the CPU-core throughput probe
├── examples/                 game-bundle (STARFALL) and opl-test-bundle
├── docs/                      this document + the screenshot gallery, next to
│                              DOSBox's own man page and porting guide
│   └── diagrams/              the SVG drawings of this document and the
│                              generator that makes them
├── bin/release.sh            the release script (tag → VERSION → APK)
├── downloads/                the link to the published release APK
└── build/                    gitignored: the native build, SDL2, the APK
```

</details>

---

## 2. The native build

`libdosbox.so` contains the **entire emulator** — the CPU cores, the DOS
kernel, the hardware, the shell — plus the SDL2 back end. SDL2 itself is
linked **statically**, so the APK ships exactly one native library per ABI and
has no SDL2 dependency at runtime.

![2. The native build](diagrams/tr04-2-the-native-build.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
   third_party/SDL2-2.30.0/          android/src/… + upstream src/
            │                                     │
            │  cmake -DSDL_SHARED=OFF              │  add_library(dosbox SHARED …)
            │  -DSDL_STATIC=ON                     │  122 translation units
            │  -DSDL_STATIC_PIC=ON                 │  + android/jni/dosbox_android.cpp
            ▼                                     ▼
   build/sdl2/<abi>/lib/libSDL2.a  ────────►  build/android/<abi>/libdosbox.so
            (static PIC archive)                        │
                                                        │ llvm-strip --strip-unneeded
                                                        ▼
                                       android/app/src/main/jniLibs/<abi>/libdosbox.so
                                                        │
                                                        │ Gradle: stageNativeLibs
                                                        ▼
                                             app-release.apk  →  lib/<abi>/libdosbox.so
```

</details>

### 2.1 The project

`android/CMakeLists.txt` is a standalone CMake project — it does not reuse the
autoconf build, which is why `configure.ac` has not been touched since the
import.

| Setting | Value |
|---|---|
| `cmake_minimum_required` | 3.22.1 |
| `project()` | `whereq-dosbox C CXX` |
| target | `add_library(dosbox SHARED …)` → `libdosbox.so` |
| sources | 122 translation units, listed explicitly (§2.4) |
| include dirs | `include/`, `src/`, `android/jni/`, the generated `include/` |
| defines | `_GNU_SOURCE`, `HAVE_STRINGS_H` |
| min platform | `android-24` |

### 2.2 The ABI matrix

| `ANDROID_ABI` | `DOSBOX_C_TARGETCPU` | `sizeof(int*)` | Used for |
|---|---|---|---|
| `arm64-v8a` | `ARMV8LE` | 8 | phones (64-bit) |
| `armeabi-v7a` | `ARMV7LE` | 4 | phones (32-bit) |
| `x86_64` | `X86_64` | 8 | the emulator test rig |
| `x86` | `X86` | 4 | accepted, not shipped |

Anything else is a `FATAL_ERROR`. `x86_64` is not a phone ABI at all — it is
there because the SDK ships x86_64 system images and the verification rig runs
on an emulator.

### 2.3 ▸ The integer-size probe

`long` is 8 bytes on LP64 (`arm64-v8a`, `x86_64`) and 4 bytes on ILP32
(`armeabi-v7a`, `x86`). DOSBox derives its `Bit64u`/`Bit64s` typedefs from
`SIZEOF_UNSIGNED_LONG`, and a wrong answer **silently truncates the 64-bit
x86 arithmetic** in the CPU core (MUL/DIV/IMUL/IDIV, RDTSC).

Hardcoding the answer per ABI would work today and break the day somebody adds
an ABI. The build therefore **asks the compiler**:

```cmake
include(CheckTypeSize)
check_type_size("long"       DOSBOX_SIZEOF_UNSIGNED_LONG)
check_type_size("long long"  DOSBOX_SIZEOF_UNSIGNED_LONG_LONG)
if(NOT DOSBOX_SIZEOF_UNSIGNED_LONG OR NOT DOSBOX_SIZEOF_UNSIGNED_LONG_LONG)
    message(FATAL_ERROR "could not determine the size of long / long long")
endif()
```

`config.h` is then generated from `android/cmake/config-android.h.in` with
`configure_file(… @ONLY)`, substituting the three probed values.

### 2.4 The source list

The list in `android/CMakeLists.txt:128-267` mirrors the `*_SOURCES` lists of
the upstream `src/*/Makefile.am` files. It covers the CPU (all five cores plus
the dynrec cache), the DOS kernel and all six drive back ends, the FPU, the GUI
(including `sdl_mapper.cpp` and the new `savestate.cpp`), 37 hardware files
(including the new `adlib.cpp`, `dbopl.cpp` and `pcspeaker.cpp` changes), the
MAME OPL models, the serial ports, the interrupt handlers, the GUI toolkit and
the shell.

Explicitly **excluded**, with a reason for each:

| Excluded | Why |
|---|---|
| `src/cpu/core_dyn_x86.cpp` | x86-only dynrec; Android uses the portable dynrec |
| `src/debug/debug_gui.cpp`, `debug_win32.cpp` | curses and Win32; `C_DEBUG` is off |
| `src/dos/cdrom_ioctl_{linux,os2,win32}.cpp`, `cdrom_aspi_win32.cpp` | host CD-ROM drivers; Android has no usable CD-ROM |

### 2.5 ▸ Two NDK workarounds in the SDL2 lookup

The NDK toolchain file sets `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY`, so
`find_package(SDL2 CONFIG)` cannot see the out-of-tree SDL2 prefix. The build
works around it in two steps:

```cmake
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)      # let the prefix through
find_package(SDL2 CONFIG QUIET)
if(NOT SDL2_FOUND)
    # a static-only SDL2 exports SDL2::SDL2-static, not SDL2::SDL2
    if(TARGET SDL2::SDL2-static)
        set(SDL2_TARGET SDL2::SDL2-static)
    elseif(TARGET SDL2::SDL2)
        set(SDL2_TARGET SDL2::SDL2)
    else()
        # manual fallback: -DSDL2_PREFIX=<prefix>
    endif()
endif()
```

The manual fallback (`android/CMakeLists.txt:100-121`) does a
`find_library(… NO_CMAKE_FIND_ROOT_PATH)` / `find_path(…)` and synthesises an
`IMPORTED` target. **It loses SDL2's `INTERFACE_LINK_LIBRARIES`**, which is
where the GLES libraries normally come from — so the link line lists
`GLESv1_CM` and `GLESv2` explicitly (§2.8).

### 2.6 The feature switches

`android/cmake/config-android.h.in` is the Android replacement for the
autoconf `config.h`. The switches that matter:

| Define | Value | Why |
|---|---|---|
| `C_SDLGFX` | 1 | the SDL2 back end |
| `C_FPU` | 1 | the x87 FPU is emulated |
| `C_FPU_X86` | **0** | `src/fpu/fpu_instructions_x86.h` has 28 inline-asm blocks and only builds on x86. ARM uses the portable `fpu_instructions.h` — the same choice the upstream Visual C config makes for non-x86. This was the last x86-ism in the compiled set. |
| `C_CORE_INLINE` | 1 | the inline CPU core |
| `C_UNALIGNED_MEMORY` | 1 | bionic permits unaligned access |
| `C_DYNREC` | 1 | the portable recompiling core (§2.7) |
| `C_TARGETCPU` | per ABI | `ARMV8LE` / `ARMV7LE` / `X86_64` / `X86` |
| `C_ATTRIBUTE_ALWAYS_INLINE` | 1 | `INLINE inline __attribute__((always_inline))` |
| `C_HAS_BUILTIN_EXPECT` | 1 | `GCC_UNLIKELY` / `GCC_LIKELY` |

Deliberately **not** defined, and why:

| Not defined | Why |
|---|---|
| `HAVE_PWD_H` | bionic ships `<pwd.h>` but `getpwnam()` is a stub returning NULL — `src/misc/cross.cpp` would take a broken home-directory path. |
| `LINUX` | `src/hardware/serialport/libserial.cpp` keys its termios code off `LINUX`/`MACOSX`/`BSD`; leaving it undefined keeps host serial code out. |
| `C_DEBUG` | needs curses. |
| `C_CURSOUT` | needs ncursesw. |
| `C_OPENGL` | the desktop fixed-function GL cannot run on GLES2 — the `SDL_Renderer` path is used instead. |
| `C_DYNAMIC_X86` | the x86-specific dynrec needs a GCC plugin. |
| `C_DIRECTSERIAL`, `C_MODEM`, `C_IPX` | no usable RS-232, modem or IPX on Android. |

▸ The `PACKAGE_VERSION` / `PACKAGE_STRING` / `VERSION` macros in
`config-android.h.in` are `@VAR@` substitutions, filled in by
`android/CMakeLists.txt` from the `VERSION` file at the repo root (§3.3). They
were **hardcoded literals** (`"1.0.0.1"`) until 1.0.0.12, so the generated
`config.h` reported `1.0.0.1` whatever release was built — and `VERSION` is
read at run time, in the DOS startup banner, in the window title, in the BIOS
machine string and in the name of the mapper file.

### 2.7 ▸ The JIT cache and Android's W^X

`C_DYNREC=1` selects the portable recompiling core. Its code cache is
normally `malloc`'d and then made executable with `mprotect(PROT_EXEC)`.
**Android forbids that**: hardened devices reject making heap memory
executable.

`src/cpu/core_dynrec/cache.h` therefore grew an `__ANDROID__` branch that
allocates the cache as anonymous RWX memory from the start — the JIT-style
allocation an untrusted app is allowed to make:

```c
mmap(NULL, CACHE_TOTAL + CACHE_MAXSIZE + PAGESIZE_TEMP - 1 + PAGESIZE_TEMP,
     PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0)
```

and the `mprotect` block is compiled out on Android (`C_HAVE_MPROTECT &&
!defined(__ANDROID__)`), because the memory is already executable. The cache is
only allocated when `core=dynamic`/`auto` is selected; `core=normal` remains
the fallback if the `mmap` fails.

Note that the *shipped* default is `core=auto`, which prefers `dynamic` — so
on Android the default path is the dynrec core, not the interpreter. The
interpreter is the fallback; the CPU-core throughput numbers are in §2.12.

### 2.8 Compilation and link flags

The code this port maintains (`jni/`, and the Android parts of
`src/gui/sdlmain.cpp`) is compiled with the full warning set:

```
-Wall -Wno-unused-variable -Wno-unused-but-set-variable
-Wno-unused-function -Wno-sign-compare -Wno-format-truncation
-fno-strict-aliasing
```

The **vendored upstream sources** additionally get:

```
-Wno-format -Wno-switch -Wno-unused-private-field
-Wno-address-of-packed-member -Wno-bitwise-instead-of-logical
-Wno-int-in-bool-context -Wno-missing-braces -Wno-non-c-typedef-for-linkage
```

Patching the frozen upstream tree for modern-clang warnings would only add
noise to every future DOSBox update, so they are switched off **for the
vendored sources only** (`android/CMakeLists.txt:335-350`).

Release optimisation:

```
-O3  -flto=thin  -fomit-frame-pointer
```

There is no JIT-independent speed margin to waste, and the cores are large
switch dispatchers that gain from cross-translation-unit inlining. ▸
**`-ffast-math` is deliberately not enabled** — the x87 FPU emulation and the
DOS timing code need IEEE semantics; relaxing them would change what programs
compute, not just how fast they run.

The link line:

```cmake
target_link_libraries(dosbox PRIVATE
    ${DOSBOX_SDL2_TARGET}   # SDL2 first — static link order matters
    OpenSLES log android m dl
    GLESv1_CM GLESv2)
```

The resulting `NEEDED` set is exactly `libOpenSLES.so, liblog.so,
libandroid.so, libm.so, libdl.so, libGLESv1_CM.so, libGLESv2.so, libc.so` —
**no SDL2 dependency**, which is the proof that SDL2 is statically linked.

### 2.9 SDL2

| Setting | Value |
|---|---|
| version | 2.30.0 (fetched by `third_party/fetch-sdl2.sh`, not committed) |
| NDK | `27.1.12297006` (default `/home/whereq/android-sdk/ndk/27.1.12297006`) |
| platform | `android-24` |
| output | `build/sdl2/<abi>` |
| flags | `-DSDL_SHARED=OFF -DSDL_STATIC=ON -DSDL_STATIC_PIC=ON -DSDL_SENSOR=OFF -DSDL_TEST=OFF -DSDL_EXAMPLES=OFF` |

Two of these are load-bearing:

* **`SDL_STATIC_PIC=ON`** — a static archive linked into a shared object must be
  position-independent.
* **`-DSDL_SENSOR=OFF`** — the Android sensor back end uses `ALooper_pollAll`,
  which NDK r27 marks unavailable.

### 2.10 The build script

`android/build-android.sh [abi …]` does, per ABI:

1. **SDL2** — skipped if `build/sdl2/<abi>/lib/libSDL2.a` already exists.
2. **DOSBox** — configure with the NDK toolchain file, then
   `cmake --build … -j$(nproc)`.
3. **strip** — keep `libdosbox.so.unstripped` next to it, then
   `llvm-strip --strip-unneeded`.
4. **stage** — copy into `android/app/src/main/jniLibs/<abi>/`.

▸ The default ABI list is **three** (`arm64-v8a armeabi-v7a x86_64`) — the
same set CI builds and the same set the released APK carries. It used to be
two, so an APK built from a plain `./android/build-android.sh` was missing an
ABI the published one has. `bin/build.sh` always passes the list explicitly,
so this only matters for a direct call of the script.

▸ **The `jniLibs` staging is vestigial for the Gradle path.** The Gradle
module stages the libraries out of `build/android/<abi>/libdosbox.so` into
`build/generated/jniLibs` and points `sourceSets.main.jniLibs.srcDirs` there.
The `src/main/jniLibs` tree is written but read by neither the Gradle path nor
the legacy path (the legacy `build-apk.sh` reads `build/android/<abi>/`
directly).

▸ **A fresh worktree without the staged libraries silently produces a 1.8 MB
APK with no `lib/` directory at all.** This is the single most common way to
build a broken APK here: the Gradle build succeeds, the app installs, and it
crashes on `System.loadLibrary`. Always run `build-android.sh` first, and
check the APK's contents:

```sh
unzip -l app-release.apk | grep 'lib/'
```

### 2.11 The rootless toolchain

`toolchain/env.sh` exists because the host has no compiler packages and no
root. It sets up a private sysroot and the Android SDK paths.

▸ **It must be sourced, not executed.** Running it as a program would export
the variables into a subshell that exits immediately, leaving the environment
unchanged — the build would then fail later on a missing `cmake` or NDK, with
a confusing error. The script refuses to run rather than let that happen:

```sh
case "$0" in
    */env.sh|env.sh)
        echo "toolchain/env.sh must be sourced, not executed:"
        echo "    . toolchain/env.sh"
        exit 1 ;;
esac
```

What it exports:

| Variable | Value | Why |
|---|---|---|
| `PATH` | prefixed with `$SYSROOT/usr/bin` | the unpacked toolchain |
| `CPPFLAGS` | `-I$SYSROOT/usr/include` + the multiarch include | `libsdl2-dev` puts `_real_SDL_config.h` in `…/x86_64-linux-gnu` |
| `LDFLAGS`, `LD_LIBRARY_PATH`, `PKG_CONFIG_PATH` | the sysroot equivalents | |
| `PERL5LIB` | `$SYSROOT/usr/share/automake-1.16` | ▸ load-bearing: `aclocal`/`automake` are perl scripts that hardcode `/usr/share/automake-1.16`, and **neither `AUTOMAKE_LIBDIR` nor `AUTOMAKE_LIB` is read by `aclocal`** — `PERL5LIB` is the only way to make `use Automake::Config` resolve |
| `WQ_ANDROID_SDK` | `/home/whereq/android-sdk` | ▸ the WSL-local SDK, **not** the Windows-side one the shell exports: it lives on ext4 and is far faster than the 9p `/mnt/c` mount for the NDK toolchain, and it carries its own cmake and ninja |
| `WQ_NDK_HOME` | the newest `$WQ_ANDROID_SDK/ndk/*` | |

`toolchain/bootstrap-sysroot.sh` unpacks the Ubuntu `.deb` packages the
desktop build needs (`autoconf`, `automake`, `m4`, `pkgconf`, `nasm`,
`libsdl2-dev`, `libncurses-dev`, …) into `toolchain/sysroot/` without root,
and rewrites the absolute paths baked into them. Two details are worth knowing:

* The `pkgconf` / `pkgconf-bin` / `libpkgconf3` trio is mandatory on Ubuntu
  24.04: `pkg-config` is a transitional package there, and without the shared
  library it dies with *"error while loading shared libraries:
  libpkgconf.so.3"*.
* The path rewrite resolves symlinks first. `sed -i` on a symlink would
  replace the link itself with a regular file and break the alternatives set
  up in the next step.

### 2.12 ▸ The desktop baseline and the throughput benchmark

The Android build is not the only one that matters: the **desktop Linux
baseline** (`native/build-native.sh`) builds the same core with the same
feature set and is what the performance numbers are measured on. It exists so
that a change can be compared on the host before it goes to a device.

```
 ./native/build-native.sh
   → autogen.sh + configure + make + make install
```

| Configure flag | Why |
|---|---|
| `--disable-dynamic-core` | no x86 dyn core and no GCC-plugin dynrec — Android has neither |
| `--disable-debug` | the internal debugger needs curses |
| `--without-curses` | no ncursesw on Android |

The resulting `config.h` has `C_SDLGFX=1`, `C_OPENGL=1`, `C_FPU=1`,
`C_CORE_INLINE=1` and **no** `C_DEBUG` / `C_CURSOUT` / `C_DYNREC` /
`C_DYNAMIC_X86`. The link line is the proof that curses is not a hard blocker:

```
dosbox.o cpu/libcpu.a debug/libdebug.a dos/libdos.a fpu/libfpu.a hardware/libhardware.a
       gui/libgui.a ints/libints.a misc/libmisc.a shell/libshell.a
       hardware/mame/libmame.a hardware/serialport/libserial.a libs/gui_tk/libgui_tk.a
       -lSDL2 -lX11 -lGL
```

▸ **The desktop baseline has to keep linking, and that takes three extra
rules.** The save-state code lives in `gui/` but calls into `dos/` (the open
file handles), while `dos/` has always called back into `gui/`
(`GFX_SetTitle` in `src/dos/dos_execute.cpp`). A linker walks a static archive
once, in order: a dependency on a *later* archive is satisfied, one on an
*earlier* archive is not. So:

* `dos/libdos.a` is listed a **second time**, after `gui/libgui.a`, in
  `dosbox_LDADD` (`src/Makefile.am`) — a linker group (`--start-group`) would
  do the same but is not available on every linker DOSBox builds with;
* the dynrec code cache reset is guarded with `#if C_DYNREC`, because the
  desktop build is configured with `--disable-dynamic-core` and the function
  does not exist there at all;
* `src/dos/dos_state.cpp` includes `<cstdint>` explicitly — it compiled on
  Android only because the NDK headers pull it in transitively.

▸ **The desktop build is the one that catches this.** The Android build has
its own CMake source list and its own `config.h`, so a file that is missing
from `src/dos/Makefile.am` is simply not compiled there — the desktop
autotools build is what links the whole tree together and proves that every
file is where the build expects it.

**The benchmark.** `bench/bench.asm` is a 16-bit DOS `.COM` program
(hand-assembled with nasm) that executes a fixed number of x86 instructions in a
tight loop. It measures elapsed **BIOS ticks** (INT 1Ah AH=00) and writes
`TICKS=<hex> <outer>` to `C:\BENCH.TXT`.

▸ **The metric caveat, which is the whole point of the probe.** The BIOS-tick
interval is *not* a valid throughput metric on its own: DOSBox advances its
virtual clock once per `CPU_CycleMax` instructions, so "instructions per BIOS
tick" just returns `CPU_CycleMax * 1000` — the configured cycle budget, not the
speed of the core. The valid metric is **instructions per wall-clock second**,
measured with a large cycle budget (`cycles=fixed 2000000`) so the emulator is
CPU-bound rather than throttled.

```sh
./bench/run-bench.sh 3 normal simple auto
```

**The results** — host: Intel Core Ultra 7 155H (x86_64), 4 vCPU, WSL2 Ubuntu
24.04, 240 060 000 instructions per run:

| Core | wall (s) | CPU (s) | IPS (best of 3) | vs 3 M real-time |
|---|---|---|---|---|
| normal | 1.88 | 1.82 | **127 691 489** | 4256 % |
| simple | 1.80 | 1.73 | **133 366 666** | 4446 % |
| auto | 2.02 | 1.97 | **118 841 584** | 3961 % |

`CPU ≈ wall` ⇒ the emulator is genuinely CPU-bound, so these are raw core speeds
and not throttled numbers. The synthetic loop is ALU-only; real DOS code with
more memory traffic measures lower (expect 30–60 M IPS), still ~10–20× above the
real-time budget.

**The real-time pacing check** (`cycles=fixed 3000`, the DOSBox default
budget):

```
WALL=82.41s   CPU=3.68s   BIOS ticks=0x5B1=1457 -> emulated 80.05s
```

The emulated time (80.05 s) tracks the wall time (82.41 s) while the process
burns only 3.68 s of CPU — **4.5 % of one core**. `core_normal` therefore
sustains 1× real-time DOS execution at the default budget with ~40× headroom
on this host: the emulator idles between the guest's instructions because it
only needs 3 M instr/s and the core can do >120 M instr/s.

▸ **The conclusion that mattered:** the "no JIT on Android" risk is **not** a
blocker at the interpreter level. Real-device ARM64 numbers are still missing
(§12.3).

---

## 3. The build and release pipeline

### 3.1 The two workflows

![3.1 The two workflows](diagrams/tr05-3-1-the-two-workflows.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
      push to main  /  pull request
                    │
                    ▼
        ┌──────────────────────────┐
        │  ci.yml · job "native"   │   matrix: arm64-v8a, armeabi-v7a, x86_64
        │  (parallel, one per ABI) │   → artifact libdosbox-<abi>
        └───────────┬──────────────┘
                    │  needs: native
                    ▼
        ┌──────────────────────────┐
        │  ci.yml · job "apk"      │   downloads the three .so files,
        │  (debug APK)             │   assembles, VERIFIES the ABIs
        └──────────────────────────┘   → artifact whereq-dosbox-debug-apk

      push tag v1.0.0.9  (or workflow_dispatch with a tag)
                    │
                    ▼
        ┌──────────────────────────┐
        │ release.yml · "native"   │   identical to the CI native job
        └───────────┬──────────────┘
                    │  needs: native
                    ▼
        ┌──────────────────────────────────────────────────────────────┐
        │ release.yml · "publish"                                     │
        │   tag ↔ VERSION check → unpack the key → assembleRelease    │
        │   → copy + sha256 → apksigner verify (fingerprint!)         │
        │   → ABI check → gh release create                           │
        └──────────────────────────────────────────────────────────────┘
```

</details>

The native build is the expensive part — SDL2 plus the whole DOSBox core,
three times — so it runs as a **parallel matrix** and the libraries are handed
to the packaging job as artifacts. The CI workflow reads **no repository
secrets**; only the release workflow does.

### 3.2 The CI details that are easy to get wrong

| Detail | Why it is the way it is |
|---|---|
| `runs-on: ubuntu-24.04` (pinned) | `ubuntu-latest` is a moving target. Pinning keeps the toolchain identical until it is deliberately bumped. |
| `sudo apt-get install -y -qq nasm` | nasm assembles the x86 parts of SDL2. Without it SDL2 falls back to the C versions — that works, but it is not what the local build produces, and the two must stay identical. |
| `android-actions/setup-android@v3` with `packages: 'platform-tools'` | the action's default is `"tools platform-tools"`, and the `tools` package was removed from the SDK repository years ago — asking for it fails the whole job. (Fix commit `f35d3ee`.) |
| `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "cmake;3.22.1" "ndk;27.1.12297006"` | the real packages come in a second step, because the action's own default fails. |
| `./third_party/fetch-sdl2.sh` | the SDL2 sources are not committed (100+ MB); the step makes the dependency reproducible. |
| `export WQ_ANDROID_SDK="$ANDROID_SDK_ROOT"` before sourcing `toolchain/env.sh` | the env script defaults to the WSL-local SDK; on the runner it must point at the runner's SDK. |
| `actions/setup-java@v6`, `temurin`, `java-version: 17` | AGP 8.7 needs JDK 17. (Fix commit `1ee9487` — the workflows were on `setup-java` v5.) |
| `cache: gradle` on the same `setup-java` step | every run used to re-download the whole AGP dependency tree from Maven Central, and a rate-limited answer (HTTP 429) failed the build for reasons that have nothing to do with the code. The cache makes the second and later runs resolve from the local store. `setup-java` rather than `gradle/actions/setup-gradle@v4`: the latter has no `cache-dependency-path` input and validates the wrapper jars by default (`validate-wrappers: true`), which is a failure mode to avoid. `setup-java`'s own defaults already match this repo (`**/*.gradle*`, `**/gradle.properties`, `**/gradle-wrapper.properties`), so the input is deliberately not overridden. |
| `if-no-files-found: error` on every upload | a missing artifact must fail the job, not silently produce an empty download. |
| the **ABI verification** step | the APK is unzipped and grepped for `lib/<abi>/libdosbox.so` for each ABI; a missing one fails with `::error::<abi> is missing from the APK`. This is the guard against the "1.8 MB APK with no `lib/`" failure of §2.10. |
| the released file is named after the **version**, not the tag | `whereq-dosbox-<version>.apk` — the tag's "v" is dropped (`${RELEASE_TAG#v}`), so the release asset and the download link in `downloads/` carry the same name. One file, one name: a release asset called `whereq-dosbox-v1.0.0.11.apk` next to a link to `whereq-dosbox-1.0.0.11.apk` is a support question waiting to happen. |
| the **release prose** comes from `docs/releases/<version>.md` | the body of the GitHub Release is read from the repository, so it is reviewed, versioned and changed in the same commit as the code it describes. `{{TAG}}` and `{{VERSION}}` are substituted before it is published, which is how a screenshot can be pinned to the release it belongs to. A release without such a file still gets published — it just gets the machine facts (file name, SHA-256, certificate) and no prose. See [docs/releases/README.md](releases/README.md). |
| the **native version** comes from the `VERSION` file | `android/CMakeLists.txt` reads the same `VERSION` file the release script writes and the Gradle build reads, and substitutes it into `config-android.h.in`. It used to be a literal `1.0.0.1`, so the emulator said *version 1.0.0.1* in the DOS banner and in the window title whatever release was installed. |

### 3.3 Versioning

| Consumer | Source |
|---|---|
| **`VERSION`** (repo root) | the single source of truth, written by `bin/release.sh` from the git tag |
| `android/app/build.gradle` | reads `VERSION`, falls back to `1.0.0.1` if the file is missing |
| `AndroidManifest.xml` | a fallback literal (`versionCode="1000011"`, `versionName="1.0.0.11"`), kept in step by the release script |
| `android/cmake/config-android.h.in` | reads `VERSION` too — `android/CMakeLists.txt` substitutes it into the template (it was a stale literal, see §12.1) |

The encoding is `versionCode = major*1e6 + minor*1e4 + patch*1e2 + build`,
implemented identically in three places (`bin/release.sh:219-225`,
`android/app/build.gradle:36-45`, `android/build-apk.sh:90`). It makes
`android:versionCode` monotonic:

```
v1.0.0.1 → 1000001
v1.0.0.9 → 1000009
v1.0.1.1 → 1001011
v2.0.0.1 → 2000001
```

Overrides: `-PappVersionCode=N` / `-PappVersionName=NAME` (Gradle) and
`--version-code` / `--version-name` (the legacy script) win over the `VERSION`
file.

### 3.4 The tag scheme

Tags are `v{major}.{minor}.{patch}.{build}` — **four** numeric segments, `v`
prefixed. `bin/release.sh` computes the next version by looking only at tags
matching

```sh
TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
```

so upstream DOSBox tags (`dosbox-0.74`, `r4010-20170505`, …) are ignored. The
build counter auto-increments; `--major`, `--minor` and `--patch` bump the
others and reset the lower segments.

A tag is what drives the release, and the release workflow **verifies the tag
against the `VERSION` file** before it builds:

```
::error::Tag $RELEASE_TAG does not match VERSION $version
```

The tag is the single source of truth for the version — it is mirrored into
`VERSION` and read by the Gradle build. A tag that did not match would ship an
APK of a different version than its tag claims.

### 3.5 Signing

The release key lives in the repository secrets, and the password **never
touches the disk**:

```yaml
env:
  KEYSTORE_B64: ${{ secrets.WHEREQ_RELEASE_KEYSTORE_B64 }}
run: |
  printf '%s' "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.keystore"
  chmod 600 "$RUNNER_TEMP/release.keystore"
  echo "WHEREQ_RELEASE_KEYSTORE=$RUNNER_TMP/release.keystore" >> "$GITHUB_ENV"
```

The store password is passed to Gradle through the environment
(`WHEREQ_RELEASE_STORE_PASS`, `WHEREQ_RELEASE_ALIAS`) and never written to a
file. The local build takes the same two environment variables; the Gradle
signing config falls back to the debug key when they are absent, which is
convenient for testing and must never be used for a release.

▸ **The certificate fingerprint is verified in CI.** The release job runs
`apksigner verify --print-certs` and compares the first 64-hex-digit string
against the hardcoded expected value:

```
b16b480d5a1ff6d35baff0b15fa3fc4b0ee815e500861fbff1b062c9bf7d3933
```

A release-signed APK has to carry the certificate of **every previous
release**, otherwise Android refuses to install it over the old one. A
rotation is a breaking change for the installed base, not a convenience.

▸ **The current key was rotated on 2026-11-11.** The fingerprint above is the
certificate that every release from 1.0.0.6 onwards carries; the APKs before
that were signed with the previous key and cannot be updated in place by a
newer build. The keystore itself is **not** in the repository — it lives in the
CI secret `WHEREQ_RELEASE_KEYSTORE_B64` and, locally, in
`build/release.keystore` (gitignored). The store password is only ever passed
through the environment.

### 3.6 The release script

`bin/release.sh` is the local counterpart of the release workflow. It

1. computes the next version from the tags,
2. writes `VERSION`,
3. rewrites the manifest's `versionCode`/`versionName`,
4. stages the commit,
5. builds the APK,
6. commits the manifest and the `VERSION` file,
7. tags and pushes.

It is the only supported way to cut a release, because it is the only place
that keeps the tag, the `VERSION` file, the manifest and the APK in step.

---

## 4. The runtime

### 4.1 The Android shell

The app layer is **Kotlin only**; there is no `.java` variant of the activity.
The SDL2 shell under `android/app/src/main/java/org/libsdl/app/` is
**byte-identical to upstream SDL2 2.30.0** — the port adds no patches to it.

`WhereqDosboxActivity` (`android/app/src/main/java/com/whereq/dosbox/WhereqDosboxActivity.kt:58`)
extends `SDLActivity` and overrides four hooks:

| Hook | Value | Why |
|---|---|---|
| `getLibraries()` | `arrayOf("dosbox")` | SDL2 is **statically linked** into `libdosbox.so`; asking for `"SDL2"` would fail to load a library that does not exist. |
| `getMainSharedObject()` | `applicationInfo.nativeLibraryDir + "/libdosbox.so"` | the real path on the device |
| `getMainFunction()` | `"SDL_main"` | DOSBox's `main()` is redirected to `SDL_main` by `SDL_main.h` — no source patch needed for the entry point |
| `getArguments()` | `arrayOf("-conf", "dosbox.conf")` | relative to the CWD, which the JNI layer set to the app's files directory (§4.3) |

`DosboxNative` also loads the library itself in its `init` block as a safety
net, swallowing the `UnsatisfiedLinkError` because `SDLActivity` will load it
later anyway.

### 4.2 The output path

![4.2 The output path](diagrams/tr06-4-2-the-output-path.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 SurfaceView (SDLSurface)          ← the Android surface
        │
        │  SDL_CreateWindow(window, ..., SDL_WINDOW_OPENGL)   on Android the
        ▼                                                     SDL window *is*
 SDL_Renderer  (SDL_RENDERER_ACCELERATED, + PRESENTVSYNC)    the SurfaceView
        │                                                     (src/gui/sdlmain.cpp:856-874)
        │  SDL_RenderCopy(texture, NULL, &sdl.clip)
        ▼
 SDL_Texture   (PIXELFORMAT_ARGB8888, TEXTUREACCESS_STREAMING)
        ▲
        │  DOSBox's VGA draw path writes into the texture
        │
 src/gui/sdlmain.cpp  GFX_EndUpdate() → SDL_RenderClear / Copy / Present
```

</details>

Two Android-specific details in `src/gui/sdlmain.cpp` matter:

* **The window is never resized.** On Android the SDL window *is* the
  `SurfaceView`, which the app keeps full-screen. The picture is scaled and
  letterboxed by the **renderer**, through `sdl.clip` — the destination
  rectangle of `SDL_RenderCopy`. `GFX_SetupWindowScaled()` computes it
  (`src/gui/sdlmain.cpp:934-1003`): the aspect-corrected size, then
  `sdl.clip.x = (windowWidth - sdl.clip.w)/2` to centre it. `sdl.clip` is also
  the mouse-mapping rectangle.

  ▸ The app layer deliberately does **not** resize the surface
  (`WhereqDosboxActivity.kt:124-128`): changing the `SurfaceView` size breaks
  DOSBox's Android texture renderer and the screen goes black. The keyboard
  instead derives the same geometry independently in Kotlin
  (`VirtualKeyboardView.kt:161`) and uses only the letterbox bars beside the
  picture.

  ▸ **The rejected alternative: `SDL_RenderSetLogicalSize()`.** The obvious
  way to scale a fixed 640×400 picture is to declare a logical size and let
  SDL scale it. It does not work here: the logical size also defines the
  **mouse coordinate system**, and DOSBox's own aspect correction would then
  fight with it — the picture would be letterboxed twice (once by DOSBox's
  aspect correction, once by the renderer's logical size) and the mouse would
  be mapped through the wrong rectangle. Scaling the *destination* rectangle of
  `SDL_RenderCopy` instead keeps the mouse mapping in DOSBox's hands, where it
  belongs.

  ▸ The renderer is deliberately left at `"auto"`: on Android SDL picks its
  GLES2 renderer, which presents with `eglSwapBuffers()` straight to the
  `SurfaceView`. The software renderer does not work here — its
  `SDL_UpdateWindowSurface()` needs SDL's texture framebuffer on the window,
  and that framebuffer owns a renderer of its own, which conflicts with the
  renderer of the texture path.

* **The surface can be replaced while the app is in the background.** When the
  app returns to the foreground it gets a **new** `SurfaceView` surface, but
  the SDL window still carries the EGL surface of the old one — the renderer
  draws into a buffer nobody looks at. The emulator keeps running (and even
  presents without an error) while the screen shows a stale picture. The fix
  is the `sdl.recreate_window` flag: on `SDL_WINDOWEVENT_SHOWN` it is set, and
  `GFX_SetSDLWindowMode()` then recreates the window and the texture.

### 4.3 The JNI bridge

`android/jni/dosbox_android.cpp` (344 lines) has six entry points. All of them
are declared as `@JvmStatic external` in `DosboxNative.kt`, so the JNI symbols
keep the `Java_com_whereq_dosbox_DosboxNative_*` shape.

| Native method | Purpose |
|---|---|
| `nativePrepareEnvironment(filesDir)` | sets `HOME` and the working directory; redirects stdout/stderr into logcat |
| `nativeConfigDir()` | returns `$HOME` — diagnostics |
| `nativeScancodeFromName(name)` | key name → `SDL_Scancode` |
| `nativePushKey(scancode, pressed)` | injects an `SDL_KEYDOWN`/`SDL_KEYUP` |
| `nativeSaveState(path)` | `SAVESTATE_Request(1, path)` |
| `nativeLoadState(path)` | `SAVESTATE_Request(2, path)` |

**The sandbox.** `nativePrepareEnvironment` does two things that make DOSBox's
desktop assumptions work on Android:

```c
setenv("HOME", dir, 1);   /* ~/.dosbox → <filesDir>/.dosbox      */
chdir(dir);               /* the CWD on Android starts as "/"    */
```

`Cross::ResolveHomedir()` expands `~` from `$HOME`, so the per-user config
lands in `<filesDir>/.dosbox`; the working directory lands in the same place,
so the relative `-conf dosbox.conf` argument resolves to
`<filesDir>/dosbox.conf` — which the app itself writes — and the
`[autoexec] mount c .` line mounts the app's files directory as `C:`.

▸ **`JNI_OnLoad` is deliberately not defined.** SDL2's Android back end
already exports one, and two definitions collide at link time.

**The logcat pipe.** `stdout` and `stderr` are `dup2`'d onto a pipe that a
detached pthread drains into `__android_log_write(ANDROID_LOG_INFO, "DOSBox", …)`.
▸ The C library's buffering must be switched off
(`setvbuf(stdout, NULL, _IONBF, 0)`): with a pipe, `stdout` would buffer in 4 KB
blocks and only the tail of DOSBox's output would ever appear.

If the pump thread cannot be started, stdout/stderr are pointed at the read end
of the pipe (i.e. `/dev/null`) — logging is a nicety, not a requirement.

There is a second, smaller logging channel: `DOSBOX_LOG` in
`android/jni/dosbox_log.h` calls `__android_log_print(ANDROID_LOG_INFO,
"whereq-dosbox", …)`. So the logcat shows two tags: **`DOSBox`** for DOSBox's
own `LOG_MSG` output and **`whereq-dosbox`** for the port's own messages.

**The key injection.** `nativePushKey` builds an SDL keyboard event and pushes
it into SDL's queue:

```c
ev.type           = pressed ? SDL_KEYDOWN : SDL_KEYUP;
ev.key.repeat     = 0;
ev.key.timestamp  = SDL_GetTicks();
ev.key.windowID   = 0;
ev.key.keysym.scancode = (SDL_Scancode)scancode;
ev.key.keysym.sym      = SDL_GetKeyFromScancode((SDL_Scancode)scancode);
ev.key.keysym.mod      = KMOD_NONE;
SDL_PushEvent(&ev);
```

`SDL_PushEvent` is thread-safe, so this may be called from the Java UI thread
while the emulator runs on its own thread.

▸ **Why a scancode is enough — and why DOSBox needs no patch for this.**
DOSBox's SDL2 back end does not decode `SDL_KEYDOWN` itself: `GFX_Events()`
hands the event to the mapper (`src/gui/sdl_mapper.cpp`), which indexes its
bind list **by the scancode** and activates the matching `CKeyEvent`, which
calls `KEYBOARD_AddKey()` and fills the BIOS keyboard buffer. So a virtual key
press is nothing but an `SDL_KEYDOWN` with the right `SDL_Scancode`.

▸ The scancode is refused before SDL is up:

```c
if ((SDL_WasInit(SDL_INIT_VIDEO) & SDL_INIT_VIDEO) == 0) return JNI_FALSE;
```

DOSBox starts SDL from `GUI_StartUp()`; before that a key press is dropped.

The name → scancode table (`key_name_map[]`) deliberately follows DOSBox's own
key table (`DefaultKeys[]` in `src/gui/sdl_mapper.cpp`), so every virtual key
has a 1:1 counterpart in the mapper's bind list. The lookup is
case-insensitive; an unknown name logs a warning and yields
`SDL_SCANCODE_UNKNOWN`.

### 4.4 The frame loop and the cycle limiter

The machine loop is upstream DOSBox's, with one addition:

```c
static int normal_loop_depth = 0;

static Bitu Normal_Loop_Inner(void) {
    ...
    if (normal_loop_depth == 1)
        SAVESTATE_RunPending();
    ...
}

static Bitu Normal_Loop(void) {
    normal_loop_depth++;
    Bitu result = Normal_Loop_Inner();
    normal_loop_depth--;
    return result;
}
```

▸ **The depth matters.** `DOSBOX_RunMachineNoSleep()` re-enters the loop from
inside BIOS callbacks (`CALLBACK_Idle` for the INT 15h/86h wait), the
page-fault handler and the I/O-fault handlers. Each of those frames keeps the
CPU registers on the **host** stack and writes them back when it returns — a
snapshot taken there would be overwritten a moment later. Only the outermost
loop is a safe point, and that is where the save-state request is serviced
(§5.3).

**The cycle limiter** is what keeps the emulation at real time. DOSBox's
`TIMER_AddTick()` gives the CPU `CPU_CycleMax` cycles and advances the virtual
clock by one emulated millisecond; with the default `CPU_CycleMax = 3000`
(`src/cpu/cpu.cpp:53`) that is **3,000,000 instructions per second** of real
time. The measured behaviour on the desktop baseline: 82.4 s of wall time
produced 80.05 s of emulated time while burning 3.68 s of CPU — 4.5 % of one
core. The remaining ~40× headroom is what pays for the interpreter.

▸ The `core` and `cycles` keys live in the **`[cpu]`** section, not in
`[dosbox]`. A misplaced key is silently ignored and the default 3000
cycles/ms throttle stays on, which is a classic source of meaningless
benchmarks.

### 4.5 The configuration

The app writes `dosbox.conf` from an asset template on **every boot**
(`WhereqDosboxActivity.kt:886-919`), so a shipped change always takes effect
and the file cannot drift. The only thing the app overrides is the CPU core
the user picked:

```kotlin
config.replace(Regex("(?m)^core=.*$"), "core=" + core)
```

The shipped template (`android/app/src/main/assets/dosbox.conf`):

| Section | Key | Value |
|---|---|---|
| `[sdl]` | `output` | `texture` |
| `[sdl]` | `fullscreen` | `true` |
| `[sdl]` | `fullresolution` | `desktop` |
| `[sdl]` | `autolock`, `waitonerror` | `false` |
| `[dosbox]` | `memsize` | `16` |
| `[dosbox]` | `machine` | `svga_s3` |
| `[cpu]` | `core` | `auto` (overridable by the user) |
| `[cpu]` | `cycles` | `auto` |
| `[render]` | `scaler` | `none` |
| `[render]` | `aspect` | `true` |
| `[autoexec]` | | `mount c .` + `c:` |

`renderer` is deliberately left at `"auto"`: on Android SDL picks its GLES2
renderer, which presents with `eglSwapBuffers()` straight to the
`SurfaceView`.

▸ There is **no `[sblaster]`, `[adlib]`, `[speaker]` or `[mixer]` section** in
the template. The effective values are DOSBox's built-in defaults registered in
`src/dosbox.cpp`:

| Section | Key | Default | Source |
|---|---|---|---|
| `[sblaster]` | `sbtype` | `sb16` | `src/dosbox.cpp:837-841` |
| `[sblaster]` | `irq` / `dma` / `hdma` | `7` / `1` / `5` | `:848`, `:852`, `:856` |
| `[sblaster]` | `sbmixer` | `true` | `:860` |
| `[sblaster]` | `oplmode` | `auto` | `:863-865` |
| `[sblaster]` | `oplemu` | `default` | `:868-870` |
| `[sblaster]` | `oplrate` | `44100` | `:873` |
| `[speaker]` | `pcspeaker` | `true` | `:905-906` |
| `[speaker]` | `pcrate` | `44100` | `:909` |

Those defaults are what §6 depends on. The chain is:

![4.5 The configuration](diagrams/tr07-4-5-the-configuration.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 sbtype=sb16  ──►  type = SBT_16, but a machine without a second DMA
                    controller falls back to SBT_PRO2
        │
 oplmode=auto ──►  SBT_PRO2  ──►  OPL_opl3  ──►  Init(Adlib::MODE_OPL3)
        │                              (the log shows mode=2)
 oplemu=default ─►  DBOPL::Handler      ("fast" selects the same handler)
```

</details>

So this build runs the **DBOPL** handler in **OPL3** mode — which is exactly
the chip the exact snapshot of §6.3 describes. (`oplemu=compat` and
`oplemu=mame` select the handlers that **cannot** restore themselves; the load
path then falls back to the register replay of §6.5.)

▸ The template's `nosound=false` sits under `[dosbox]`, but DOSBox registers
`nosound` under `[mixer]`. The value in `[dosbox]` is not read; the effective
value is the `[mixer]` default `false` — the same result by a different route.

▸ A bundle that ships its own `dosbox.conf` does **not** get it used. The
launcher only reports *"ships its own dosbox.conf"* as an informational flag;
the app always boots with its own generated config.

### 4.6 The foreground service

`DosboxForegroundService` exists for one reason, stated in its own class
documentation:

> When a game is running and the user switches to another app, Android is free
> to reclaim this process under memory pressure — which would lose the running
> game (DOSBox r4305 has no save-state; the whole machine lives in RAM). While
> this foreground service is running the OS keeps the process, so SDL's paused
> emulator survives and resumes exactly where the player left it.

| Detail | Value |
|---|---|
| channel | `"dosbox_running"`, `IMPORTANCE_LOW`, `setShowBadge(false)` |
| notification | `"WhereQ-DOSBox"` / *"Playing `<game>` — tap to return"*, `setOngoing(true)` |
| type | `foregroundServiceType="specialUse"` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description |
| return value | `START_NOT_STICKY` — the OS must **not** resurrect the service on its own; a game launch restarts the whole process |

The service is started when a game is pending and stopped on relaunch or on
destroy. `POST_NOTIFICATIONS` is requested at runtime on API 33+.

### 4.7 ▸ Touch, the mouse and the relative-mouse trap

A finger has to become a DOS mouse cursor, and SDL's own conversion is not
usable here. SDL's Android back end turns touches into mouse events
(`SDLSurface.onTouch` → `SDL_SendTouch` → synthetic `SDL_MOUSEMOTION` /
`SDL_MOUSEBUTTONDOWN`), which has two problems:

1. **the position is wrong** — the synthesised events report the position of
   the *system* mouse, not of the finger (measured: a tap at `(250, 600)`
   produced `mouse motion x=0 y=0`);
2. **the mapping is missing** — the DOS picture is letterboxed into
   `sdl.clip`, so a finger position only means something after it has been
   mapped through that rectangle, and `sdl.clip` is DOSBox's own state.

So the conversion is done inside DOSBox instead —
`android_handle_touch()` in `src/gui/sdlmain.cpp`, fenced by
`#if defined(__ANDROID__)`:

![4.7 ▸ Touch, the mouse and the relative-mouse trap](diagrams/tr08-4-7-touch-the-mouse-and-the-relative-mouse-trap.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  SDL_FINGERDOWN / SDL_FINGERMOTION / SDL_FINGERUP
        │
        ▼
  android_handle_touch(finger)              src/gui/sdlmain.cpp:2641
        │  the FIRST finger drives the cursor and holds the LEFT button for as
        │  long as it is down; a SECOND finger is a RIGHT click
        ▼
  px = finger.x * windowWidth               SDL normalises touches to 0..1
  py = finger.y * windowHeight
        │
        ▼
  map through sdl.clip — exactly what HandleMouseMotion() does for a real mouse
        │  rx = (px - clip.x) / (clip.w - 1)
        │  ry = (py - clip.y) / (clip.h - 1)
        ▼
  Mouse_CursorMoved(0, 0, rx, ry, sdl.mouse.locked)
```

</details>

▸ **A click is a held button, not a tap.** The first finger presses the LEFT
button on `SDL_FINGERDOWN` and releases it on `SDL_FINGERUP`, so the button is
down for the whole gesture and a drag keeps it down while it moves. This is
what a game that reads the mouse *live* needs — the button state at INT 33h
AX=3 — because an instantaneous click can fall between two polls and be missed
entirely. The RIGHT button is held the same way: a second finger that joins
the first one is a right click (cancel or back in most DOS games), held until
that finger lifts. The left button is released first, so the two clicks never
overlap.

The on-screen **RMB** key sends the same right click at the *current* cursor
position, through `DosboxNative.nativePushMouseButton()`
(`android/jni/dosbox_android.cpp:359` → `Mouse_ButtonPressed()` /
`Mouse_ButtonReleased()`). It is a held button as well: the finger down
presses it, the finger up releases it.

SDL's own synthesis is switched off with
`SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0")` (`sdlmain.cpp:3383`), otherwise
the mouse would be moved twice.

▸ **The relative-mouse trap.** DOSBox captures the mouse when it goes
fullscreen (`GFX_CaptureMouse()` → `SDL_SetRelativeMouseMode(SDL_TRUE)`), which
sets `sdl.mouse.locked`. With that flag set, `Mouse_CursorMoved()` **ignores
the absolute position** and only adds up relative movement — so every tap was
ignored and the mouse stayed in the middle of the screen.

On Android there is no real mouse to capture and relative mode has no meaning,
so `doGFX_CaptureMouse()` and `GFX_UpdateSDLCaptureState()` keep the mouse
unlocked on Android (`sdlmain.cpp:1501` and `:1529`).

Measured before and after the fix:

| | tap (700, 300) | tap (1500, 800) | tap (1000, 500) |
|---|---|---|---|
| before | 320, 100 | 320, 100 | 320, 100 |
| after | 97, 55 | 453, 148 | 231, 92 |

The numbers are exact: `0.153 * 80 * 8 = 97`, i.e. the DOS text screen's 80×25
cells at 8 pixels each.

---

## 5. The save-state machine

### 5.1 What a save state is

A save state is a **snapshot of the whole emulated machine**, written to a file
and read back later — including into a *freshly booted* DOSBox. It is not an
emulator feature: DOSBox has none, and a game that is 40 minutes into Ultima
VIII could not be put back the way it was without one.

The contract is three C functions in `include/savestate.h`:

```c
int  SAVESTATE_Request(int op, const char *path);   /* from any thread, blocks */
void SAVESTATE_GuestReady(void);                     /* the guest is running   */
void SAVESTATE_RunPending(void);                     /* from the DOSBox thread */
```

and six result codes:

| Code | Value | Meaning | What the user sees |
|---|---|---|---|
| `SAVESTATE_OK` | 0 | done | *"Saved to slot 1"* |
| `SAVESTATE_ERR_IO` | 1 | the file could not be written or read | *"Could not save (error 1)"* |
| `SAVESTATE_ERR_FORMAT` | 2 | not a save state, or the wrong version | *"That save file is damaged"* |
| `SAVESTATE_ERR_MISMATCH` | 3 | the state is for a different machine configuration | *"That save is for a different machine setup"* |
| `SAVESTATE_ERR_TIMEOUT` | 4 | the DOSBox thread did not service the request | *"Save timed out — is a game running?"* |
| `SAVESTATE_ERR_BUSY` | 5 | another request is already in flight | *"Busy — try again in a moment"* |

### 5.2 The request lifecycle

The UI thread never touches the machine. It posts a request and blocks.

![5.2 The request lifecycle](diagrams/tr09-5-2-the-request-lifecycle.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 Android UI thread                DOSBox thread                    the file
 ───────────────────               ─────────────                    ────────
 saveState(bundle, slot)
   └ workScope.launch {
       withContext(IO) {
         nativeSaveState(path)
           └► SAVESTATE_Request(1, path)
                 lock(g_mtx)
                 if (g_op != 0) return ERR_BUSY
                 g_path = path; g_op = 1; g_done = false
                 g_cv.wait_for(lk, 5s) ────────┐
                 │                            │  the machine loop keeps
                 │                            │  running; the request is
                 │                            │  polled once per iteration
                 │                            ▼
                 │            ┌──────────────────────────────────────┐
                 │            │ Normal_Loop_Inner()                   │
                 │            │   … run the guest …                    │
                 │            │   if (normal_loop_depth == 1)          │
                 │            │       SAVESTATE_RunPending();  ◄───────┼─┐
                 │            │   GFX_Events();                        │ │
                 │            └──────────────────────────────────────┘ │
                 │              │ gate 1: guest ready?                  │
                 │              │ gate 2: video settled?  (load only)   │
                 │              │ gate 3: not in an interrupt stub?      │
                 │              │   → do_save(path) ─────────────────────┤
                 │              │        FillFlags()                     │
                 │              │        write every block ─────────────┴──►
                 │              │   if (g_gen == gen)                   │
                 │              │     g_result = rc; g_done = true      │
                 │              │   g_cv.notify_all()                   │
                 │◄─────────────┘
       return rc
     setStatus(message)
```

</details>

Two consequences of the split:

* The UI thread **blocks** for up to 5 s (save) or 30 s (load). There is no
  Java-side timeout — the call simply blocks, which is why the app runs it on
  `Dispatchers.IO` rather than the main thread.
* Only **one** request may be in flight. A second one returns `ERR_BUSY`
  immediately rather than queueing.

The timeouts are asymmetric on purpose: a save has to happen *right away* —
the machine is running — so 5 s is a generous ceiling that still fails fast if
the DOSBox thread is wedged. A load may have to wait for the guest to start at
all, so it gets 30 s.

▸ **A timeout retires its generation.** The wait above has a ceiling, and the
requester may give up while the DOSBox thread is still *inside*
`do_save()`/`do_load()` — a large save on a slow phone can outlast the 5 s.
When that happens the requester clears `g_op` and returns
`SAVESTATE_ERR_TIMEOUT`, but the in-flight `do_save()` keeps running and will
eventually publish a result.

Without a guard, that late result is mistaken for the **next** request's. The
next requester sets `g_done = false` and waits; the stale completion then sets
`g_done = true` with the *previous* request's `g_result`. The new request is
answered with an old answer, and the file on disk is the file the old request
wrote.

The guard is a generation counter, bumped when a request **stops waiting**
rather than when it is published:

```c
static uint64_t g_gen = 0;   /* bumped every time a request stops waiting */

int SAVESTATE_Request(int op, const char *path) {
    ...
    bool serviced = g_cv.wait_for(lk, timeout, [] { return g_done; });
    ++g_gen;          /* retire this generation - serviced *or* timed out */
    g_op = 0;
    if (!serviced) return SAVESTATE_ERR_TIMEOUT;
    return g_result;
}

void SAVESTATE_RunPending(void) {
    ...
    gen = g_gen;      /* the generation being serviced */
    ...
    if (g_gen == gen) {   /* still the request being waited on? */
        g_result = rc;
        g_done = true;
    }
    g_cv.notify_all();
}
```

A completion that arrives after a timeout therefore always finds a different
generation and drops its result. `g_cv.notify_all()` still runs — it simply has
nobody to wake up.

### 5.3 ▸ The safe point

A request is only serviced when the machine is in a state where replacing it is
safe. There are **three gates**, and a request that fails any of them simply
waits — it is not failed.

![5.3 ▸ The safe point](diagrams/tr10-5-3-the-safe-point.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
                   ┌──────────────────────────────────────┐
   request ───────►│ gate 1: has the guest started?       │
                   │   g_guest_ready                       │
                   │   set by the shell reaching its      │
                   │   prompt, or by DOS_Execute()        │
                   └───────────────┬──────────────────────┘
                          no ──────┘ (wait, log once)
                                   │ yes
                   ┌───────────────▼──────────────────────┐
                   │ gate 2: has the video settled?        │
                   │   (load only)  GFX_VideoReady()       │
                   │   the device is rotated into place   │
                   │   after the boot; a picture drawn     │
                   │   before that is thrown away by the   │
                   │   resize that follows                 │
                   └───────────────┬──────────────────────┘
                          no ──────┘ (wait; a save skips this)
                                   │ yes
                   ┌───────────────▼──────────────────────┐
                   │ gate 3: is the CPU in an interrupt    │
                   │ stub?  cpu_in_interrupt_stub()        │
                   │   cs == F000 and ip in [1000, 2600]    │
                   └───────────────┬──────────────────────┘
                          yes ─────┘ (wait, log once)
                                   │ no
                   ┌───────────────▼──────────────────────┐
                   │ do_save() / do_load()                 │
                   └──────────────────────────────────────┘
```

</details>

**Why gate 1.** DOSBox paints its start-up banner and the autoexec's output
through the BIOS, and the BIOS runs the machine loop while it does so. The
loop is therefore already running long before the guest program is. Replacing
the machine there would throw the boot away. The module is told from **both**
sides:

* `src/shell/shell_misc.cpp` — the shell reached its prompt, so the autoexec
  (and the game) has run;
* `src/dos/dos_execute.cpp` — a guest program has been loaded and the CPU has
  been pointed at it. A game started by the autoexec never lets the shell
  return (the shell is blocked inside the program), so this is the only
  notification that fires for it.

**Why gate 2.** A restore rebuilds the whole picture: the VGA is re-derived and
the renderer gets a new texture. On Android the device is rotated into place a
moment after the boot, and until that has settled the picture is scaled for
the old orientation — the resize that follows replaces the texture, so a
machine restored in that window was drawn for the wrong size and then thrown
away, which left the screen black.

**Why gate 3.** DOSBox's own interrupt stubs (`src/cpu/callback.cpp`) are the
"extra callback" instruction (`FE 38`) followed by an `IRET`: the host handler
runs *inside* the `FE 38` instruction, and the `IRET` that follows pops the
shadow registers the interrupt pushed. A restore that lands between the two
would replace the stack the `IRET` pops from. The "real interrupt" stubs are
worse: they stop the emulated machine right after the handler returned, so a
restore there would end the program that was just restored.

Both stub areas live in the `F000` segment between `CB_SOFFSET` and the end of
the real-interrupt table. The check is:

```c
static bool cpu_in_interrupt_stub(void) {
    if (SegValue(cs) != CB_SEG) return false;
    const Bitu ip = reg_eip;
    return (ip >= CB_SOFFSET) &&
           (ip < CB_SOFFSET + (Bitu)CB_MAX * CB_SIZE + 0x100 * 6);
}
```

▸ **A guest can starve the request.** Gate 3 is a *polled* condition: the
machine loop asks on every iteration. A normal program is in guest code often
enough for the request to be picked up. A program that polls the keyboard in a
tight loop is **not** — it is inside the BIOS handler every single time the
loop runs, so the request never gets its turn and the save times out.

The port logs the reason once per request, because *"the save timed out"* on
its own does not say why:

```
savestate: waiting for the CPU to leave the interrupt stub (cs:f000 ip:00001234)
```

The fix on the guest side is to pause in guest code between the polls; a delay
loop is enough. The OPL test program in `examples/opl-test-bundle/` does
exactly that.

### 5.4 The file format

![5.4 The file format](diagrams/tr11-5-4-the-file-format.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
┌──────────────────────────────────────────────────────────────────────────┐
│ SSHeader  (24 bytes)                                                     │
│   char     magic[4]      "WQSS"                                          │
│   uint32_t version        8                                               │
│   uint32_t machine        ::machine                                       │
│   uint32_t mem_pages      MEM_TotalPages()                                │
│   uint32_t vmemsize       vga.vmemsize                                    │
│   uint32_t svga_card      ::svgaCard                                      │
├──────────────────────────────────────────────────────────────────────────┤
│ cpu_regs        the CPU registers                                        │
│ cpu             the CPUBlock (GDT/IDT/LDT/TSS, CPL, paging, …)            │
│ Segs            the segment registers                                   │
│ paging.cr3, paging.cr2, paging.base.page, paging.base.addr, paging.enabled
│ paging.firstmb  the host's linear→physical map of the first megabyte     │
│ guest RAM       mem_pages * 4096 bytes                                   │
│ vga             the whole VGA struct                                     │
│ vga.mem.linear  vmemsize bytes of video memory                           │
│ vga.fastmem     vmemsize * 2 bytes (the chain4 mirror)                    │
│ clock           the PIC timestamp of the save                            │
│ PIC             the two interrupt controllers                            │
│ TIMER           the three PIT channels                                   │
│ fpu             the x87 register file and the control word               │
│ SPKR            the PC speaker                                           │
│ OPL             the OPL/AdLib FM chip  (register cache + exact snapshot)  │
│ MOUSE           the INT 33h mouse driver (callback, range, buttons)       │
│ DOS             the DOS layer (handles, drive, code page, current dirs)   │
└──────────────────────────────────────────────────────────────────────────┘
```

</details>

The file is a **fixed-order sequence of raw blocks**. There is no
tag/length framing: the order *is* the format, and the payload size is
computed from the header:

```c
static size_t expected_payload_size(uint32_t mem_pages, uint32_t vmemsize) {
    return sizeof(cpu_regs) + sizeof(cpu) + sizeof(Segs) +
           paging_blob_size() +
           (size_t)mem_pages * 4096u +
           sizeof(vga) +
           (size_t)vmemsize +              /* vga.mem.linear           */
           (size_t)vmemsize * 2u +          /* vga.fastmem (chain4)     */
           sizeof(double) +                 /* the PIC clock           */
           PIC_StateSize() + TIMER_StateSize() +
           sizeof(fpu) +
           SPKR_StateSize() +
           OPL_StateSize() +                 /* the OPL/AdLib FM chip   */
           MOUSE_StateSize() +               /* the INT 33h mouse       */
           DOS_StateSize();                  /* the DOS layer           */
}
```

▸ **The size is validated before any live state is touched.** A truncated file
is rejected whole, never half-restored:

```c
long pos = ftell(f);
fseek(f, 0, SEEK_END);
long end = ftell(f);
fseek(f, pos, SEEK_SET);
if (pos < 0 || end < 0 ||
    (size_t)(end - pos) != expected_payload_size(h.mem_pages, h.vmemsize)) {
    LOG_MSG("savestate: payload size mismatch, refusing load");
    return SAVESTATE_ERR_FORMAT;
}
```

The header doubles as a **configuration guard**: a state can only be loaded
into a machine with the same memory size, the same video memory size and the
same SVGA card. A mismatch is `SAVESTATE_ERR_MISMATCH`, not a crash.

**The version history.**

| `SS_VERSION` | What was added |
|---|---|
| 1-3 | the CPU, the RAM, the VGA |
| 4 | `paging.firstmb` |
| 5 | the open DOS file handles (milestone B2) |
| 6 | the OPL register cache |
| 7 | **the exact OPL chip snapshot** |
| 8 | **the INT 33h mouse driver state** |
| 9 | **the mouse block slimmed to the callback, the cursor range and the position** |
| **10** | **the mouse block grown to the whole driver configuration** — the cursor shape, hotspot, granularity and sensitivity as well — 1.0.0.12 |

A slot written by an older build is refused (*"that save file is damaged"*)
rather than misread. ▸ The 1.0.0.9 slots are version 5, the 1.0.0.10 slots are
version 7 and the 1.0.0.11 slots are version 8 — **none of them can be loaded
by this build**.

### 5.5 The blocks in detail

**CPU.** `cpu_regs`, the whole `CPUBlock` and the segment registers. One field
is explicitly *not* restored:

```c
CPU_Decoder *l_old_decoder = cpu.hlt.old_decoder;   /* keep the live one */
... rd(f, &cpu, sizeof(cpu)) ...
cpu.hlt.old_decoder = l_old_decoder;
```

`cpu.hlt.old_decoder` is a *function* pointer into the CPU core. It is
meaningless in another process.

**Guest RAM.** The whole `MemBase` array, `mem_pages * 4096` bytes, written
and read in one block.

**Paging.** The page tables live in guest RAM; only the registers travel
(`cr3`, `cr2`, `base.page`, `base.addr`, `enabled`). They are read into
*temporaries* and applied **after** the RAM is in place, and the TLB is
cleared afterwards — the map of the first megabyte just changed, so every
cached translation of it is stale.

▸ `paging.firstmb` is host state, but it belongs to the *machine*: while
paging is off DOSBox resolves the first megabyte through this array instead of
the guest's page tables, and the EMS page frame is mapped through it. Leaving
it out makes the EMS page frame point at the wrong physical memory.

**VGA.** The whole `vga` struct plus the video memory and the `fastmem`
mirror. The mirror is not optional: it is what the EGA/LIN4 modes are drawn
from, and without it those modes come back as garbage.

**Clock and timing.** The save writes `PIC_FullIndex()` as a `double`, so the
PIT phase can be continued rather than restarted. `TIMER_LoadState(f,
clock_saved)` shifts the three channels' absolute start times forward by the
elapsed time and re-arms `PIT0_Event` with the **remaining** time of the
current period (`pit[0].delay - phase`), so IRQ0 keeps its place in the tick.

**PIC.** Restored, but the pending/in-service state is deliberately **dropped**:

```c
imrr = ~imr;  irr = 0;  isr = 0;  isrr = ~isr;  active_irq = 8;
```

A restored in-service bit would block all lower-priority IRQs — and the RTC
that drives INT 15h/86h, and thus the sound timing, would stall. A stale
request bit would fire a spurious IRQ. `PIC_Ticks` is not restored either;
the PIT start times are re-based instead.

**FPU.** The x87 register file and the control word.

**PC speaker** and **OPL** are covered in §6.

**MOUSE.** The INT 33h mouse driver keeps its state in one file-static struct in
`src/ints/mouse.cpp` — and none of it lives in guest memory. The guest
configures the mouse once at startup and never re-issues that configuration,
so after a restore the driver cannot rebuild it:

* the **event-callback registration** (`sub_seg`, `sub_ofs`, `sub_mask`),
  installed via INT 33h AX=0Ch. Without it a restored game's mouse clicks stop
  reaching the game: the cursor still moves (DOSBox draws it), but a dialog's
  *confirm* button no longer responds, because the callback that would have
  told the game about the click is gone;
* the **graphics cursor and its hotspot** (AX=09). Without them the cursor
  reverts to the default white block drawn with a zero hotspot, so the
  *visible* cursor no longer lines up with the real click point (POS_X/POS_Y):
  the taps land up-and-left of the target and the selections miss;
* the **cursor range, the position, the visibility** and the **motion
  sensitivity**.

**Only that configuration is saved — the volatile half of the driver is
deliberately left at its clean post-boot values.** The event queue, the button
and click counters, the interrupt re-entrancy flags (`in_UIR`) and the
cursor-draw backing store are *not* restored. Restoring them wedged
mouse-driven games: the stale queue and flags fed garbage into the INT 74
handler and into the guest's own callback, and a game could hang on load or
stop answering clicks once the queue filled up behind a stuck `in_UIR`. This is
what changed in **1.0.0.12**; 1.0.0.11 saved the whole struct.

The block is a struct of its own, so the file, the size and the fields cannot
drift apart:

```c
/* the configuration block, in this order */
sub_seg, sub_ofs, sub_mask,          /* the guest's event callback      */
min_x, max_x, min_y, max_y,          /* the cursor range                */
x, y,                                /* the cursor position             */
hotx, hoty,                          /* the cursor hotspot              */
cursorType, textAndMask, textXorMask,/* the cursor shape                */
gran_x, gran_y,                      /* the movement granularity        */
mode, page, enabled,                 /* the mouse mode                  */
hidden, oldhidden,                   /* the visibility                  */
language, doubleSpeedThreshold,      /* the driver's own settings       */
mickeysPerPixel_x, mickeysPerPixel_y,/* the sensitivity                 */
pixelPerMickey_x, pixelPerMickey_y,
senv_x_val, senv_y_val, dspeed_val,  /* the speed environment           */
senv_x, senv_y,
userScreenMask[CURSORY],             /* the user's own cursor masks     */
userCursorMask[CURSORY],
```

`MOUSE_StateSize()` is `sizeof(MouseStateBlock)` and `MOUSE_SaveState()`
writes exactly that struct, so the block and the payload-size check in §5.4
cannot drift apart.

The loader reads the block back, then clears the volatile state, so the
restored callback starts from a clean, idle driver:

```c
mouse.events = 0;                     /* no stale queued mouse events     */
memset(mouse.event_queue, 0, sizeof(mouse.event_queue));
mouse.buttons = 0;                    /* no button held across the save  */
mouse.times_pressed[i] = mouse.times_released[i] = 0;
mouse.timer_in_progress = false;
mouse.in_UIR = false;                 /* a stuck in_UIR would swallow clicks */
PIC_RemoveEvents(MOUSE_Limit_Events); /* and drop the pending limit event */
```

The two cursor masks are the only host pointers the driver holds, and the
snapshot does not contain them — they are process-local allocations, and the
block carries the *contents* of the user's masks instead. After the load the
pointers are re-derived from the saved cursor type: a user-defined graphics
cursor (type 2) uses the restored masks, anything else uses the built-in
arrow. That is why the mouse is not one of the exceptions to §5.6.

**DOS layer** is covered in §7.

### 5.6 ▸ The host-pointer rule

Three structs in the snapshot hold pointers into the host process (it was four
until 1.0.0.12 slimmed the mouse block, which no longer carries its two cursor
masks):

| Struct | Pointers |
|---|---|
| `vga` | `mem.linear`, `mem.linear_orgptr`, `fastmem`, `fastmem_orgptr`, `draw.linear_base`, `draw.font_tables[0..1]`, `tandy.draw_base`, `tandy.mem_base`, `lfb.handler` |
| `cpu` | `hlt.old_decoder` (a function pointer) |
| `DBOPL::Chip` | `volHandler`, `waveBase`, `synthHandler` (§6.2) |

They are process-local allocations. The rule is: **read the content state from
the file, keep the live pointers.**

```c
Bit8u *l_linear    = vga.mem.linear;
Bit8u *l_fastmem   = vga.fastmem;
PageHandler *l_lfb = vga.lfb.handler;
...
rd(f, &vga, sizeof(vga));
vga.mem.linear  = l_linear;
vga.fastmem     = l_fastmem;
vga.lfb.handler = l_lfb;
```

The same rule is stated in the file's own header comment, and it is the reason
the OPL snapshot needs an explicit *re-derivation* step rather than a plain
`memcpy` (§6.2).

### 5.7 ▸ The derived-state rebuild

The snapshot is the *machine*. Everything that is a **consequence** of the
machine is rebuilt, not restored:

| What | How |
|---|---|
| lazy CPU flags | `FillFlags()` before the save; `lflags.type = t_UNKNOWN` after the load, so `reg_flags` is authoritative |
| paging | the registers are re-applied, then `PAGING_ClearTLB()` |
| the dynrec code cache | `CPU_Core_Dynrec_Cache_Reset()` — the translated blocks belong to the memory that was just replaced |
| the VGA draw pipeline | `vga.draw.resizing = false`, `vga.draw.width = 0`, `vga.draw.height = 0`, then `VGA_DetermineMode()`, `VGA_SetupHandlers()`, `VGA_SetupDrawing(0)` |
| the vertical retrace | `VGA_RestartFrameTimer()` — the retrace is a PIC event, and the PIC event queue is **not** part of the snapshot, so without this nothing asks the VGA for the next line and the picture freezes |
| the palette | `VGA_DAC_UpdateAllColors()` — there is no "set the entire palette" entry point in the stock code |

▸ **Two of those lines are the result of a crash.** `vga.draw.resizing` is
part of the `vga` struct, so it was restored from the file as well — and
`VGA_StartResize()` does nothing at all while it is set, which silently
skipped the whole re-derivation. `VGA_SetupDrawing()` only rebuilds the output
window when the new dimensions *differ* from `vga.draw.width/height/...`, and
those came from the file too, so they matched and the resize was skipped as
well. The renderer kept the old mode, and the pending draw event of the old
mode then fired with the new state and wrote through a stale output pointer:
**SIGSEGV in `Normal1x_8_32_L`**. Clearing the derived values makes
`VGA_SetupDrawing()` take its full path — `VGA_KillDrawing()` drops the stale
draw events and `RENDER_SetSize()` puts the renderer into the restored mode.

### 5.8 The fingerprint

Every save and every load writes one summary line. The two lines have to be
identical when a snapshot is loaded back — that is the proof that the restore
brought the machine back and not merely "something similar":

```
savestate: saved cs:0192/00001920 ip:000001b0 sp:0000fff6 ss:0192/00001920 ...
            gdt:00000000/00000000 idt:00000000/000003ff ldt:0000
            gdtck:811c9dc5 stckck:2143e05f
```

The two checksums are the interesting part:

| Checksum | Covers | Catches |
|---|---|---|
| `gdtck` | the descriptor table | a mangled GDT, even when the base and the limit look right |
| `stckck` | the top of the current stack | a stack that was restored into the wrong place |

Both are FNV-1a over the bytes.

### 5.9 The Resume path

"Resume" is not a save-state feature — it is a launcher feature that happens to
use one.

![5.9 The Resume path](diagrams/tr12-5-9-the-resume-path.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  Files panel
     │  "▶ Resume STARFALL · slot 1 · 5 min ago"
     ▼
  refreshResumeButton()
     ├─ reads KEY_LAST_GAME from SharedPreferences "launch"
     ├─ finds the bundle
     └─ newestSlot(bundle) = the slot with the greatest lastModified()
                                (empty and missing files are skipped)
     ▼
  resumeLastGame() → relaunch(folder, slot)
     ├─ writes the pending launch + slot to SharedPreferences, commit()s
     ├─ stops the foreground service
     ├─ restarts the activity with CLEAR_TASK | NEW_TASK
     └─ Runtime.getRuntime().exit(0)   ← the old process dies
     ▼
  the next boot: onCreate() reads and clears the pending launch/slot
     └─ restoreAfterBoot(folder, slot)
          ├─ the file is gone → log and start the game normally
          └─ nativeLoadState(path)
               ├─ OK      → the game is back
               └─ failure → relaunch(folder, null) so the player is never
                            left staring at a bare DOS prompt
```

</details>

▸ The **process is killed and restarted** for every launch. DOSBox's whole
machine lives in RAM, and there is no way to hand a running emulator over to a
new process; the snapshot is what survives the handover.

▸ The slots live **outside the C: mount**:

```kotlin
private fun stateDir(bundle: GameBundle): File =
    File(File(noBackupFilesDir, "states"), bundle.folder)
```

so they never show up inside DOS and are not swept into the cloud backup —
each one is 16 MB or more.

---

## 6. The sound chips

### 6.1 Why the registers are not the state

The OPL is driven entirely by register writes. A driver programs its
instruments once at the start of a song and then only sends *key on* and *key
off* — everything that happens in between lives **inside the chip**, where no
register records it:

* the **envelope**: which state the operator is in (attack, decay, sustain,
  release) and how far into it,
* the **operator phase**: where the waveform generator is in its cycle,
* the **LFO counters**: the vibrato and tremolo phase,
* the **feedback history**: the previous output of each operator.

So replaying the register cache after a restore rebuilds the instruments but
**re-triggers every note from its attack**. The tone comes back — but it comes
back *wrong*: it starts again instead of continuing where it was.

That is exactly what the fallback path (§6.5) is about, and it is the reason
the chip is snapshotted wholesale.

### 6.2 The chip, as built

The handler that runs by default is `DBOPL::Handler` (`oplemu=default`,
§4.5). Its state is one struct:

![6.2 The chip, as built](diagrams/tr13-6-2-the-chip-as-built.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
struct Chip {
    Channel chan[18];              // 9 channels per bank × 2 banks
    Bit32u  lfoCounter, lfoAdd;    // the LFO
    Bit32u  noiseCounter, noiseAdd, noiseValue;
    Bit32u  freqMul[16];           // the frequency multipliers
    Bit32u  linearRates[76];       // the envelope rates
    Bit32u  attackRates[76];       // the attack rates
    Bit8u   reg104, reg08, reg04, regBD;
    Bit8u   vibratoIndex, tremoloIndex;
    Bit8s   vibratoSign;
    Bit8u   vibratoShift, tremoloValue, vibratoStrength, tremoloStrength;
    Bit8u   waveFormMask;
    Bit8s   opl3Active;            // 0 or -1
};

struct Channel {
    Operator op[2];                // operator 1 (modulator), operator 2 (carrier)
    SynthHandler synthHandler;     // ◄── host pointer
    Bit32u   chanData;
    Bit32s   old[2];               // the feedback history
    Bit8u    feedback, regB0, regC0, fourMask;
    Bit8s    maskLeft, maskRight;
};

struct Operator {
    VolumeHandler volHandler;      // ◄── host pointer
    Bit16s* waveBase;              // ◄── host pointer (into the static WaveTable)
    Bit32u  waveMask, waveStart;
    Bit32u  waveIndex, waveAdd, waveCurrent;
    Bit32u  chanData, freqMul, vibrato;
    Bit32s  sustainLevel, totalLevel;
    Bit32u  currentLevel;
    Bit32s  volume;
    Bit32u  attackAdd, decayAdd, releaseAdd;
    Bit32u  rateIndex;             // the position inside the envelope
    Bit8u   rateZero, keyOn;
    Bit8u   reg20, reg40, reg60, reg80, regE0;
    Bit8u   state;                 // OFF / RELEASE / SUSTAIN / DECAY / ATTACK
    Bit8u   tremoloMask, vibStrength, ksr;
};
```

</details>

Measured sizes on the x86_64 build:

| Struct | Size |
|---|---|
| `DBOPL::Operator` | 104 bytes |
| `DBOPL::Channel` | 248 bytes |
| **`DBOPL::Chip`** | **5 176 bytes** |

▸ **Exactly three host pointers** live in the chip: `Operator::volHandler`
(a member-function pointer to the envelope generator), `Operator::waveBase`
(a pointer into the static `WaveTable`) and `Channel::synthHandler` (a
member-function pointer to the channel generator). Everything else is plain
data. This is what makes the whole-struct snapshot viable — see §6.4 for what
it costs.

### 6.3 The exact snapshot

The interface gains three optional methods, with a default of *not handled*:

```c
class Handler {
    ...
    virtual size_t StateSize( void ) { return 0; }
    virtual bool SaveState( FILE* f ) { (void)f; return true; }
    virtual bool LoadState( FILE* f ) { (void)f; return false; }
    virtual void LogState( const char *tag ) { (void)tag; }
};
```

and the DBOPL handler implements them:

```c
size_t Handler::StateSize( void ) { return sizeof( chip ); }

bool Handler::SaveState( FILE* f ) {
    return fwrite( &chip, sizeof( chip ), 1, f ) == 1;
}

bool Handler::LoadState( FILE* f ) {
    if ( !load_chip_exact( chip, f ) ) return false;
    log_chip_state( "restored", chip );
    return true;
}
```

The chip is copied **verbatim**. The envelope state, the rate index, the wave
phase, the LFO counters and the feedback history all come back exactly as they
were. No note is re-triggered, and no note is left hanging.

### 6.4 ▸ The pointer re-derivation

The copy restores three host pointers that are meaningless in another process.
They are re-derived from the restored register and state fields, **exactly the
way the guest's own writes derive them**:

```c
static bool load_chip_exact( Chip& chip, FILE* f ) {
    if ( fread( &chip, sizeof( chip ), 1, f ) != 1 )
        return false;

    chip.UpdateSynths();               /* the per-channel synth handler */

    for ( int c = 0; c < 18; c++ ) {
        for ( int o = 0; o < 2; o++ ) {
            Operator& op = chip.chan[c].op[o];

            /* the envelope generator of the restored state */
            Bit8u st = op.state;
            if ( st > Operator::ATTACK ) st = Operator::OFF;
            op.volHandler = VolumeHandlerTable[ st ];

            /* the wave pointer, the way Operator::WriteE0() derives it */
            Bit8u waveForm = op.regE0 &
                ( ( 0x3 & chip.waveFormMask ) | ( 0x7 & chip.opl3Active ) );
            op.waveBase  = WaveTable + WaveBaseTable[ waveForm ];
            op.waveStart = WaveStartTable[ waveForm ] << WAVE_SH;
            op.waveMask  = WaveMaskTable[ waveForm ];
        }
    }
    return true;
}
```

![6.4 ▸ The pointer re-derivation](diagrams/tr14-6-4-the-pointer-re-derivation.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
        the file                        the live chip
     ┌──────────────┐                ┌──────────────────────┐
     │  the chip    │   fread        │  the chip            │
     │  verbatim    │ ─────────────► │  verbatim            │
     │              │                │                      │
     │  volHandler  │ ✗ invalid      │  volHandler    ──────┼── re-derived from
     │  waveBase    │ ✗ invalid      │  waveBase      ──────┼── op.regE0 +
     │  synthHandler│ ✗ invalid      │  synthHandler  ──────┼── waveFormMask/opl3
     └──────────────┘                │                      │
                                     │  state, rateIndex,   │
                                     │  waveIndex, volume,  │  ◄── these are the
                                     │  lfoCounter, old[]   │      point of the
                                     └──────────────────────┘      whole exercise
```

</details>

Two details are easy to get wrong and are worth stating explicitly:

* **`op.state` is clamped.** `state` is a `Bit8u` in the file; the enum only
  has five values. A corrupted or truncated file could carry anything, and
  `VolumeHandlerTable[st]` would read out of bounds. The clamp to
  `Operator::OFF` turns that into a silent operator instead of a crash.

* **The waveform is masked exactly as `WriteE0()` masks it.** The mask is
  `(0x3 & waveFormMask) | (0x7 & opl3Active)`: in OPL2 mode `waveFormMask` is
  `0x0`, so only the waveforms 0-3 are selectable; in OPL3 mode `opl3Active`
  opens all eight. Using the same expression means the restored pointer is
  the one the guest's own write would have produced.

▸ `Chip::UpdateSynths()` re-derives the per-channel `synthHandler` by calling
`Channel::UpdateSynth()` on all 18 channels. That is the same call the chip
makes when the guest writes register `0x104` (the 4-op pairing) or `0x105`
(the OPL3 enable), so the restored chip has exactly the synthesis path the
guest had configured.

### 6.5 The fallback: the register replay

A handler that cannot snapshot itself — the MAME OPL2/OPL3 models and the
"compat" OPL2/OPL3 emulators — keeps the old behaviour: the module replays
the register cache.

▸ **The replay needs the right order.** Registers `0x105` (the OPL3 enable)
and `0x104` (the 4-operator channel pairing) decide how *every other*
register is interpreted: which bank it lands in, which channels are 4-op
voices, and — critically — that in OPL2 mode `WriteE0()` clamps the waveform
to 0-3, so an OPL3 voice would come back with the wrong timbre. They must be
applied **before** the operator and channel registers; a flat 0..511 sweep
would not do that.

```c
handler->WriteReg( 0x105, cache[0x105] );
handler->WriteReg( 0x104, cache[0x104] );
for (Bitu r = 0; r < sizeof(cache); r++ ) {
    if (r == 0x104 || r == 0x105) continue;
    handler->WriteReg( (Bit32u)r, cache[r] );
}
```

The cache itself is part of the state file in every case, so the fallback has
the registers it needs.

### 6.6 ▸ The alignment rule

The handler's block is a fixed part of the state file, and **the DOS layer
comes behind it**. So the block has to be consumed in *every* case — even when
the handler cannot restore itself and the registers are replayed instead.

Without that, the file cursor stays in the middle of the block and the open
file handles behind it are read from the wrong place: a restore that *looks*
fine and is not.

```c
bool exact = false;
size_t chip_size = handler ? handler->StateSize() : 0;
if ( handler && chip_size ) {
    long block = ftell( f );
    if ( handler->LoadState(f) ) {
        exact = true;
    } else {
        /* rewind and skip the block, so the rest of the file stays aligned */
        if ( block < 0 || fseek( f, block, SEEK_SET ) != 0 ) return false;
        if ( !skip_bytes( f, chip_size ) ) return false;
    }
}
```

`skip_bytes()` seeks forward and then verifies that the file really contains
those bytes, so a truncated file is still rejected rather than silently
accepted with a short read.

### 6.7 The PC speaker: reset it, do not replay it

The PC speaker is a **real-time output device**. Its queued delay entries and
its PIC-relative timing indices were taken against the *saved* machine's
clock; replaying them against the current clock makes the mixer emit a drone
or a steady beep that the guest never asked for.

The speaker is therefore **reset to silence on load**. The configuration
survives (the rate, the minimum toggle length, the mixer channel), the
run-time state does not:

```c
spkr.mode       = SPKR_OFF;
spkr.used       = 0;
spkr.last_ticks = 0;
spkr.last_index = 0;
spkr.pit_index  = 0;
spkr.pit_last   = 0;
spkr.volwant    = 0;
spkr.volcur     = 0;
if (spkr.chan) spkr.chan->Enable(false);
```

The guest re-drives the speaker on its next tone, so no audible event is lost
in practice.

Note the one pointer in the speaker struct: `spkr.chan` is a live
`MixerChannel*`. It is nulled before the write and restored after it — it is
never persisted.

### 6.8 ▸ The evidence

The port writes one diagnostic line per save and per load, and **the two lines
have to be identical** — that is the proof of an exact restore.

```
savestate: OPL chip saved:    ch0.op0 decay rate=0 phase=d96f49ac vol=00000000
                               ch0.op1 decay rate=0 phase=d96f49ac vol=00000000
                               ... lfo=000b228c opl3=0
savestate: OPL chip restored: ch0.op0 decay rate=0 phase=d96f49ac vol=00000000
                               ch0.op1 decay rate=0 phase=d96f49ac vol=00000000
                               ... lfo=000b228c opl3=0
```

Every field of that line is a field the register cache cannot carry:

| Field | What it shows |
|---|---|
| `ch0.op0 decay` | the envelope state and **which operator** it is |
| `rate=0` | the position inside the envelope |
| `phase=d96f49ac` | the operator's wave phase |
| `vol=00000000` | the current attenuation |
| `lfo=000b228c` | the LFO counter — restored only by the exact snapshot |
| `opl3=0` | the chip mode |

For the contrast, the **same saved state** was loaded by a build with the
exact snapshot disabled, so the registers were replayed instead:

```
savestate: OPL chip replayed: ch0.op0 attack rate=0 phase=80000000 vol=000001ff
                                ch0.op1 attack rate=0 phase=80000000 vol=000001ff
                                ... lfo=00000000
```

| | old register replay | new exact snapshot |
|---|---|---|
| envelope state | `attack` — the note starts again | `decay` — where it was |
| wave phase | `80000000` — reset to the start value | `d96f49ac` — the live phase |
| volume | `1ff` — silent until the attack gets through | `0` — full volume |
| LFO counter | `00000000` — never restored | `0008774c` — the saved value |

The module logs its side of the story too:

```
savestate: OPL module saved:    exact=1 mode=2 regs=62/512 r20=31 r23=31
                                 r40=3f r43=00 r60=f0 r63=f0 r80=77 r83=77
                                 rA0=99 rB0=23
```

`regs=9/512` is the number of non-zero entries in the register cache — it says
whether the guest programmed the chip at all — and the listed registers are
what it programmed.

▸ **The log names every operator that is not idle**, not just the first one of
the chip. The first operator of channel 0 stays in `release` from the chip's
own power-up clearing — `Chip::Setup()` clears the chip by writing `0xff` and
then `0x00` to every register, which keys every channel on and immediately off
again — and a log that only showed that would hide a real note.

---

## 7. The DOS layer in the snapshot

### 7.1 The problem

DOSBox keeps the open files in **one global table**, `Files[]`
(`src/dos/dos_files.cpp`). A handle the guest gets from `INT 21h` is *not* a
pointer: the guest's **Job File Table** — which lives in the PSP, i.e. in the
guest's own memory — stores the **index** of the entry in that table.

![7.1 The problem](diagrams/tr15-7-1-the-problem.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
   the guest's memory (restored with the RAM)
   ┌────────────────────────────────┐
   │ PSP                            │
   │  ┌──────────────────────────┐  │
   │  │ Job File Table           │  │
   │  │  [0] = 3   ──┐           │  │
   │  │  [1] = 7     │ indices   │  │
   │  │  [2] = 12 ───┘           │  │
   │  └──────────────────────────┘  │
   └────────────────────────────────┘
                    │
                    ▼
   DOSBox's global table (NOT in the guest's memory)
   ┌──────────────────────────────────────────────┐
   │ Files[3]  → SOUND\MUSIC.FLX                   │
   │ Files[7]  → SAVE\GAME.DAT                     │
   │ Files[12] → the console device                │
   └──────────────────────────────────────────────┘
```

</details>

The table therefore has to be **rebuilt in exactly the same order** after a
restore. Otherwise the guest reads through a handle that points at nothing
(DOS reports *"invalid handle"*, error 6) or — worse — at somebody else's
file.

This is the "open DOS file handles" milestone (B2), and it is the difference
between a restore that works and one that aborts the game.

### 7.2 What is stored

Per handle:

| Field | Meaning |
|---|---|
| `kind` | 0 = an empty slot, 1 = a device, 2 = a file |
| `flags` | `DOS_File::flags`: the open mode and the inherit flag |
| `time`, `date`, `attr` | the file's timestamps and attributes |
| `refCtr` | the reference count — a duplicated handle shares one entry, and the count is restored as it was saved (§7.3) |
| `pos` | the position in the file |
| `devnum` | the device number (for `kind == 1`) |
| `hdrive` | the drive the handle belongs to |
| `name` | the DOS name of the file (the drive-relative path DOSBox built it from) |

▸ **The record has no padding at all.** Every member is 32-bit and the name is
padded to a multiple of four, so the record has a fixed size and the whole
block can be size-checked before any live state is touched:

```c
struct DOSStateHandle {
    uint32_t kind, flags, time, date, attr, refCtr, pos, devnum, hdrive;
    char     name[DOS_PATHLENGTH];
};
#define DOSSTATE_HANDLE_SIZE (9 * 4 + DOS_PATHLENGTH)

static_assert(sizeof(DOSStateHandle) == DOSSTATE_HANDLE_SIZE,
              "DOSStateHandle must not have any padding");
```

Besides the handles, the DOS layer keeps three things that are not in the
guest's memory either, and they travel with the state as well:

* the **current drive**,
* the **loaded code page** (the BIOS font that goes with it lives in the video
  memory, which is restored from the snapshot as well),
* the **current directory of every mounted drive**.

### 7.3 The restore

```
  read the whole block into a temporary array
      ▸ a truncated state is rejected instead of half-restored
  close and drop everything the loading process has open
      ▸ the guest's own file table (in the restored memory) takes its place
      ▸ the discarded files are closed even when their count is > 1
  restore the current drive, the code page and the current directories
  rebuild the table, in the same order:
      ▸ a device  → a fresh wrapper around the device itself,
        exactly like DOS_OpenFile() does it
      ▸ a file    → re-opened through its drive, then the file pointer
        is put back where the guest left it
```

A file is restored by **re-opening it through its drive** — exactly the way
`DOS_OpenFile()` opened it the first time — and then seeking to the saved
position. A device is restored from the device table.

▸ **The reference count is restored — and it has to be.** A handle duplicated
with INT 21h/45h (DUP) does **not** get a second entry in DOSBox's file table.
It gets a second entry in the *guest's* Job File Table (the JFT), which lives
in the PSP — and the PSP is part of the guest's memory, which the snapshot
captures. Both PSP entries therefore point at the **same** index of the table,
and the object in that slot is shared by both handles.

The count says how many of those references exist, and two rules depend on it:
`DOS_CloseFile()` deletes the table entry when the count reaches zero
(`src/dos/dos_files.cpp`), and `localFile::Close()` closes the host file only
when the count is 1 (`src/dos/drive_local.cpp`). Both assume that the count
which comes back is the count which was saved: with a count of 2, closing the
first handle leaves the entry alive for the second one, and the host file is
closed when the last reference goes away.

▸ **Setting the count to 1 on restore breaks the DUP semantics.** The restore
puts the saved count back (`dev->refCtr = (Bits)h->refCtr;` and
`file->refCtr = (Bits)h->refCtr;` in `src/dos/dos_state.cpp`). Replacing those
two lines with a fixed `= 1` looks tidier — each slot holds one object, so one
reference — but it breaks exactly the sharing DUP creates.

The first close then takes the count to zero, the shared entry is deleted, and
the second handle is left pointing at nothing: the next read fails with
"invalid handle" (DOS error 6), and so does closing it. The handle is not
"a file of its own" after a restore — it is a handle to nothing.

Measured on the emulator, with a program that opens a file, duplicates the
handle (INT 21h/45h), waits while a state is saved and restored, closes the
first handle and then reads through the second:

| the restored count | the read through the duplicate | closing the duplicate |
|---|---|---|
| the saved one (2) | 1 byte, the file's content | succeeds |
| 1 | fails, "invalid handle" | fails — the entry was gone |

The restore therefore keeps the saved count. The sharing that DUP creates is
part of what the snapshot has to preserve, and the reference count is what
preserves it.

▸ **The discarded machine's files are closed, whatever their count.** The
objects of the machine that is being replaced are closed before they are
thrown away, and the count is forced to 1 first: `localFile::Close()` only
closes the host file when the count is 1, and a handle duplicated with
INT 21h/45h (DUP) shares one table object — so the objects that would
otherwise be left open are exactly the shared ones. `localFile` has no
destructor, so a file that is not closed here stays open for the rest of the
session.

Without that forced close, every restore of a state in which a handle was
shared leaked one host file descriptor. Measured on the emulator, with the
guest holding the file open through three save/restore cycles, the number of
descriptors pointing at the guest's file went from 1 to 2, 3 and 4 — one leak
per restore. With the forced close it stays at 1, and it falls back to 0 when
the guest closes its handles.

Every step logs what it did, so a failed restore is diagnosable from the log
alone:

```
savestate: dos handle 3: SOUND\MUSIC.FLX (drive 2, pos 4096, refs 1)
savestate: dos handle 7: device 0 (CON)
savestate: dos state restored: drive 2, code page 437, 4 handle(s)
```

and the failure cases are explicit:

```
savestate: file handle 12 has no name - skipped
savestate: could not reopen SAVE\GAME.DAT on drive 2
savestate: device handle 5 is gone (device 3)
```

▸ **A handle that cannot be restored is skipped, not faked.** A virtual file
has no name of its own, so it cannot be re-opened; the guest gets an invalid
handle for it, exactly as it would without the snapshot.

▸ **A device handle is retired, not deleted.** The machine loop is entered
from the DOS shell's wait for a key, so a read on the console device may still
be in flight while the table is rebuilt. The wrapper object is not touched
again once the call has been dispatched into the device, but keeping it around
until the next save or load costs nothing and removes the question entirely.

▸ **The write buffers are flushed before the save.** The snapshot has to
contain what the guest has written so far, so every open `localFile` is
flushed first.

### 7.4 Why the DOS block comes last

The DOS block is read **after** everything else, on purpose:

> the handles are re-opened through their drive, and the drive only knows the
> current directory of the *saved* machine once the DOS block has been
> applied.

So the order in the file is not arbitrary — it is a dependency order:

![7.4 Why the DOS block comes last](diagrams/tr16-7-4-why-the-dos-block-comes-last.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
   CPU → RAM → paging → VGA → clock → PIC/TIMER → FPU → speaker → OPL → DOS
                                                                    └── last:
                                                                        it re-opens
                                                                        files through
                                                                        the drives
                                                                        that the
                                                                        block itself
                                                                        restores
```

</details>

▸ This is also why the OPL block must be consumed even when the handler
cannot restore itself (§6.6): a block that is skipped shifts everything behind
it.

---

## 8. The bundle launcher

A **bundle** is a `.zip` archive with a DOS game in it. The launcher turns it
into a running DOS program through five steps, each of which exists because of
a specific limitation of DOSBox on Android.

![8. The bundle launcher](diagrams/tr17-8-the-bundle-launcher.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 ① pick              ② unpack             ③ rename            ④ write          ⑤ boot
 ───────             ──────               ───────             ───────          ─────
 ACTION_OPEN_        GAMES/G<n>/          DOS 8.3 names,      GAMES/G<n>/      fresh
 DOCUMENT            (zip bomb,           unique, 8.3        RUN.BAT          DOSBox
  + EXTRA_           path traversal,      truncated,                        process,
    MIME_TYPES       512 MB cap           spaces removed                    [autoexec]
```

</details>

The three files that carry the whole launcher:

| File | Lines | What it owns |
|---|---|---|
| `GameLibrary.kt` | 678 | the manifest, the install, the DOS naming, the entry-point heuristic, `RUN.BAT` |
| `GameLauncherView.kt` | 575 | the overlay panel, the bundle rows, the slot dialog, the launch editor |
| `WhereqDosboxActivity.kt` | 1050 | the picker, the relaunch, the slot files, the `[autoexec]` line |

### 8.1 The on-disk layout

![8.1 The on-disk layout](diagrams/tr18-8-1-the-on-disk-layout.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 /data/data/com.whereq.dosbox/
 │
 ├── files/                          ← mounted as C: by DOSBox
 │   ├── dosbox.conf                 ← regenerated from the asset on every boot
 │   ├── GAMES/
 │   │   ├── games.json              ← the manifest (it sits next to, not in, GAMES)
 │   │   ├── G1/
 │   │   │   ├── RUN.BAT             ← the launcher batch file
 │   │   │   ├── PLAY.BAT            ← the game, as it was in the zip
 │   │   │   └── DATA/…             ← sub-directories keep their shape
 │   │   ├── G2/ …
 │   │   └── G<n>/…
 │   └── …                           ← the DOS game's own save files live here too
 │
 └── no_backup/                     ← NOT mounted; never visible inside DOS
     └── states/
         ├── G1/slot1.sav
         ├── G1/slot2.sav
         ├── G1/slot3.sav
         └── G2/slot1.sav …
```

</details>

▸ **The slot files are outside the C: mount.** They live under `noBackupFilesDir`
(`WhereqDosboxActivity.kt:386`), not under `filesDir`, so they never appear
inside DOS — a `DIR` in the emulator does not list 16 MB of save states — and
they are excluded from the cloud backup, because a slot is a copy of the machine
and the game's own save files are the real data.

▸ **The folder names are `G1`, `G2`, … and the index is never reused.**
`nextIndex()` (`GameLibrary.kt:436`) scans the directory for the highest `G<n>`
and takes the next one. A deleted game therefore never takes the folder of an
older one, and the DOS paths recorded in the manifest stay valid.

▸ **The games directory is upper case (`GAMES`).** DOSBox runs on a case
sensitive file system here, and the app starts a game by typing
`C:\GAMES\G<n>\RUN.BAT` into the emulator. The folder on disk and the path that
is typed have to spell it identically, or DOSBox answers *"Illegal command"*.

### 8.2 Picking the archive

`pickZipFile()` (`WhereqDosboxActivity.kt:550`) opens the system document
picker:

| Detail | Value | Why |
|---|---|---|
| action | `ACTION_OPEN_DOCUMENT` | the persisted-URI picker, not the legacy one |
| `EXTRA_MIME_TYPES` | `application/zip`, `application/x-zip-compressed` | not every provider calls a zip `application/zip` |
| `DocumentsContract.EXTRA_INITIAL_URI` | `primary:Download` | the picker opens where the archives usually are |
| fallback | `ACTION_GET_CONTENT` | for devices without a document provider |
| second path | `ACTION_OPEN_DOCUMENT_TREE` | "install all bundles from a folder" |

`browsePhoneFiles()` walks a picked **tree** and installs every archive it
finds — the short way in for a folder of DOS games.

There is a third path: a bundle opened from a file manager arrives as an
`ACTION_VIEW` intent and is installed immediately (`handleOpenIntent()`), because
the user already asked for it with a tap.

▸ The document picker is called with `CATEGORY_OPENABLE` and the extra MIME
types, so a provider that reports a `.zip` as `application/octet-stream` is not
filtered out by the type list alone.

### 8.3 ▸ Installing: the three guards

`GameLibrary.install()` (`GameLibrary.kt:117`) unpacks the archive with
`ZipInputStream`. Three guards run while it does:

![8.3 ▸ Installing: the three guards](diagrams/tr19-8-3-installing-the-three-guards.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  entry ──► isDirectory? ──► skip
      │
      ▼
  resolveSafely(target, dosPath(name, used))
      │
      ├── absolute path ("/etc/passwd")?   ──► REJECT
      ├── contains ":"? ("C:/windows")?     ──► REJECT
      ├── any path part == ".."?            ──► REJECT
      └── the resolved absolute path does not start with
          the target folder + "/" ?          ──► REJECT
      │
      ▼
  copy(input, out, zipEntry.size)
      │
      ├── declared size > 256 MB?           ──► THROW (the whole install fails)
      ├── bytes actually read > 256 MB?     ──► THROW
      └── total unpacked > 512 MB?          ──► THROW
      │
      ▼
  the folder is deleted again and the install fails cleanly
```

</details>

| Limit | Value | Rationale |
|---|---|---|
| `MAX_UNPACKED_BYTES` | 512 MB | a DOS game is a few megabytes; more is a broken archive or a zip bomb |
| `MAX_ENTRY_BYTES` | 256 MB | one enormous file is a bomb even if the total is under the cap |

The declared size in the zip header is **not** trusted: the copy counts the bytes
it actually reads and aborts on the limit, so a lying header cannot get past it.

▸ A rejected entry is **skipped**, not fatal: `install()` logs
`skipping unsafe entry <name>` and continues with the next one. A single
malicious entry therefore cannot block an otherwise good bundle. Only the size
limits abort the whole install — and then the folder is deleted, so a failed
install leaves nothing behind.

### 8.4 ▸ The DOS naming rule

DOSBox turns every name it is given into upper case, cuts it down to eight
characters plus three and drops the spaces
(`GameLibrary.dosName()`, `GameLibrary.kt:551`). A program that asks for
`RESOURCE.CFG` therefore ends up at `RESOURC.CFG` — so the app writes the files
under exactly that name.

![8.4 ▸ The DOS naming rule](diagrams/tr20-8-4-the-dos-naming-rule.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  zip entry name            stored as            why
  ───────────────────────── ──────────────────── ──────────────────────────────
  "resource.cfg"            RESOURC.CFG          base cut to 8
  "Game Data.dat"           GAMEDATA.DAT         spaces dropped, base cut to 8
  "readme.txt"              README.TXT           extension cut to 3
  "config.bak"              CONFIG.BAK
  "a.b.c"                   A.B.C                 two dots → left alone (see below)
  "LONG1.CFG"               LONG.CFG             the base is cut to 8
  "LONG2.CFG"               LONG~1.CFG           the second one gets a number
```

</details>

Two rules make this safe:

* **A name with two dots is kept as it is** (`text.indexOf('.', dot + 1) >= 0`).
  DOSBox refuses such a name, so the file is simply *out of reach* of the
  emulator — but it stays on disk with its original name, which is better than
  renaming it to something that would collide with another file.
* **A collision gets a number, not an overwrite.** `uniqueName()`
  (`GameLibrary.kt:591`) truncates the base to six characters and appends
  `~1`, `~2`, … up to `~99`. Two files that both want to be `LONG.CFG` both
  survive.

▸ **Why the name has to be right at install time.** DOSBox caches the
directory listing of a drive when it mounts it, and only re-scans floppies on
`FindFirst` (`localDrive.cpp:184`). A bundle installed *after* the boot is
therefore invisible to a program that enumerates the drive — but a file whose
name DOSBox can spell without a listing is found anyway. The 8.3 name is not
cosmetic; it is the reason an installer inside the game finds its own data.

### 8.5 ▸ The entry-point heuristic

`detectEntryPoint()` (`GameLibrary.kt:278`) picks the file that most likely
starts the game. Only the **root** of the bundle is considered first — a start-up
file deeper in the tree is data, not the entry point.

![8.5 ▸ The entry-point heuristic](diagrams/tr21-8-5-the-entry-point-heuristic.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  the root of the bundle
        │
        ├─► a file from PREFERRED_BATCH_FILES ?
        │     play.bat, run.bat, start.bat, game.bat, jogar.bat,
        │     launch.bat, go.bat, dos.bat
        │     → that one wins
        │
        ├─► any .bat ?  ──► the first one (the list is sorted case-insensitively)
        │
        ├─► any .exe / .com ?
        │     │
        │     ├─ drop the installers and the TSR drivers:
        │     │     INSTALL, SETUP, UNINSTALL, UNINSTAL, CONFIG, SETSOUND,
        │     │     SOUND, SETUPSB, INST          (SETUP_NAMES)
        │     │     anything whose base ends in "DRV"   (GRPDRV, SNDDRV, …)
        │     │
        │     └─► the BIGGEST of what is left
        │
        └─► nothing in the root → the whole bundle:
              the first .bat, else the biggest file
```

</details>

▸ **The driver filter is the important part.** A bundle like 三国志英杰传 ships
`INSTALL.EXE`, `GRPDRV.EXE` and `MAIN.EXE` side by side. Auto-running the
installer or the driver drops straight back to the prompt, and auto-loading a
driver TSR that is really a `CONFIG.SYS` device **hangs** instead of exiting
cleanly. The port therefore ships the game alone and lets the user add drivers
deliberately through the "Launch…" editor (§8.10).

The same reasoning is why the app does **not** auto-load driver TSRs at install
time: `writeRunBat(target, folder, entryPoint, null)` writes a `RUN.BAT` with
no custom script.

### 8.6 `RUN.BAT` and the `[autoexec]`

Every bundle gets a `RUN.BAT` (`writeRunBat()`, `GameLibrary.kt:390`):

```
@ECHO OFF
MOUNT A GAMES/G1 >NUL
MOUNT B GAMES/G1 >NUL
C:
CD \GAMES\G1
PLAY.BAT
```

| Line | Why |
|---|---|
| `@ECHO OFF` | the batch file does not echo its own commands |
| `MOUNT A GAMES/G1` | many DOS games of the floppy era read their data with **absolute A:/B: paths** — verified: 三国志英杰传's `MAIN.EXE` opens `A:BAKDATA.R3`, `B:IPPAN0.R3`, … |
| `MOUNT B GAMES/G1` | the second floppy drive, same content |
| `C:` | select the C: drive (the app's files directory) |
| `CD \GAMES\G1` | switch to the game folder so it finds its data files |
| `PLAY.BAT` | the entry point — **quoted** when the name contains a space |

▸ The `MOUNT` paths are **relative to DOSBox's working directory**, which is the
app's files directory (the JNI layer `chdir`s there — §4.3). `GAMES/G1` therefore
resolves to the app's files directory. Mounting A: and B: is harmless for a game
that never touches them.

▸ `RUN.BAT` is written in **ISO-8859-1 with CRLF line endings**
(`GameLibrary.kt:432`) — DOS line endings, and the code page DOSBox boots with.
A UTF-8 file would be read by DOS as the code page's characters, and the LF-only
endings confuse the DOS command interpreter.

The batch file is not *typed* into the emulator. The app boots DOSBox with a
generated configuration whose `[autoexec]` ends with `mount c .` and `c:`, and
the auto-start line is **appended** to it:

```kotlin
// WhereqDosboxActivity.kt:903
val autostart = "\r\nC:\\" + GameBundle.DOS_GAMES_DIR + "\\" +
    pendingLaunch + "\\RUN.BAT\r\n"
```

![8.6 `RUN.BAT` and the `[autoexec]`](diagrams/tr22-8-6-run-bat-and-the-autoexec.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 dosbox.conf (from the asset, regenerated every boot)
 ─────────────────────────────────────────────────────
 [autoexec]
 mount c .
 c:
 C:\GAMES\G1\RUN.BAT          ◄── appended by the app
```

</details>

▸ The configuration is **regenerated from the asset on every boot**
(`writeConfig()`, `WhereqDosboxActivity.kt:886`), so the shipped settings always
take effect and the file cannot drift. The one thing that survives is the CPU
core the user picked in the settings, which is written into the template by
`applyCpuCore()` (a `(?m)^core=.*$` replacement). A hand-edited `dosbox.conf`
would be lost — that is a deliberate trade, documented in the method's comment.

### 8.7 ▸ Why a relaunch, and not "type the command"

`play()` (`WhereqDosboxActivity.kt:754`) does **not** type into the running
DOSBox. It tears the process down and boots a fresh one:

![8.7 ▸ Why a relaunch, and not "type the command"](diagrams/tr23-8-7-why-a-relaunch-and-not-type-the-command.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  play(bundle)
      │
      ▼
  relaunch(folder)
      ├─ SharedPreferences "launch":
      │     pending      = "G1"     ← the folder to auto-run
      │     pending_state= 0        ← no slot to restore
      │     last_game    = "G1"     ← what "Resume" will offer after a cold start
      │  .commit()   ◄── synchronous: it has to be on disk before the process dies
      │
      ├─ DosboxForegroundService.stop(this)
      │     the keep-alive service lives in this process; it is START_NOT_STICKY,
      │     so the OS will not revive it
      │
      ├─ startActivity(launchIntent + CLEAR_TASK + NEW_TASK)
      │
      └─ Runtime.getRuntime().exit(0)
            the new process boots DOSBox from scratch
```

</details>

Two reasons, both about DOSBox:

1. **A DOS program cannot be terminated from the outside.** Typing a second
   game's command just feeds it to whatever is already running.
2. **DOSBox mounts C: once and caches its directory listing.** A bundle
   installed after the boot is invisible to an installer that enumerates its
   files (§8.4). A fresh process gets a fresh mount whose cache includes the
   bundle.

The DOS files live in the persistent app directory, so the *game's* save files
survive the relaunch. Only the machine is thrown away.

▸ The pending launch is **read and cleared immediately** in `onCreate()`
(`WhereqDosboxActivity.kt:141`), so a crash or a force-quit lands back at a
clean `C:\>` prompt instead of looping into the game forever.

### 8.8 The save-state slots

| | |
|---|---|
| Directory | `noBackupFilesDir/states/<folder>/` (`SaveStore.localSlot()`) |
| File | `slot<n>.sav`, n = 1..3 (`STATE_SLOTS = 3`) |
| Written by | `DosboxNative.nativeSaveState(path)` — the §5 machine |
| Read by | `DosboxNative.nativeLoadState(path)` |
| "newest" | the slot with the greatest `lastModified()`; missing and zero-length files are skipped (`newestSlot()`, `WhereqDosboxActivity.kt:361`) |
| label | *"just now"* under a minute, otherwise a relative span |
| durable mirror | the same slot, gzipped, in the folder the user picked — §8.9 |

▸ **Save is only offered for the game this process is actually running.**
`GameLauncherView.setCurrentGame(folder)` holds the folder the current boot
auto-started; the "Save" button of every other bundle is disabled. Without that
rule a slot could be overwritten with an unrelated machine — the bare DOS
prompt, say — and the slot would silently become useless.

▸ Overwriting a slot that already holds a save asks first
(`"Overwrite slot 2?"`), because a slot is the only copy of that moment in the
game.

### 8.9 ▸ The durable saves folder

The save-state slots of §8.8 live in the app's private storage. Android deletes
that when the app is uninstalled, so "reinstall the app" used to mean "lose
every save". `SaveStore` (`SaveStore.kt`) adds a second layer outside the
sandbox: a folder the **user** picks on their phone, reached through the
Storage Access Framework.

![8.9 ▸ The durable saves folder](diagrams/tr24-8-9-the-durable-saves-folder.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  the app                        the phone's own storage
  ──────                        ─────────────────────
  noBackupFilesDir/states/G4/    <the folder the user picked>/
    slot1.sav   ── gzip ──▶        whereq-opl-test-bundle/
    slot2.sav                        slot1.gz
                                     slot2.gz
                                     bundle.zip      (the game itself)
```

</details>

| | |
|---|---|
| The local copy | `noBackupFilesDir/states/<folder>/slot<n>.sav` — the file the native engine reads and writes (`DosboxNative` works with plain paths) |
| The durable mirror | `<the picked folder>/<game name>/slot<n>.gz` — gzip-compressed; a 16 MB RAM snapshot is mostly zeros and shrinks to a few MB |
| The game bundle | `<the picked folder>/<game name>/bundle.zip` — the unpacked game, re-zipped, with `__meta.json` as its first entry |
| Who chooses the folder | the user, through `ACTION_OPEN_DOCUMENT_TREE`; the URI is kept in the shared preferences and the permission is taken persistently |
| When it is written | after every successful save (`mirrorToDurable`), after every install (`mirrorGame`), and once for every existing slot when the folder is first connected (`backUpAllSlotsToDurable`) |
| When it is read | when a slot's local copy is missing — on load (`restoreFromDurable`) and on the boot-time auto-restore (`restoreAfterBoot`) |

▸ **The mirror is keyed by the game's *name*, not by its folder.** The folder
index (`G1`, `G2`, …) is handed out at install time and is **not** stable across
a reinstall: the same game installed again becomes `G3` where it was `G1`. The
name always is, so the durable side is `<sanitised display name>/`. A name with
characters a file system will not take becomes `_` (`sanitize()`), capped at 64
characters.

▸ **The name is therefore unique.** Two archives can carry the same file name
— the same game installed twice, or two different zips called `game.zip` — and
one durable folder cannot hold two games' saves. So the display name is made
unique when the bundle is installed (`GameLibrary.install()`): the second one
becomes *game (2)*. A manifest that already holds duplicates is repaired once,
at startup (`GameLibrary.makeNamesUnique()`), where the **oldest** game keeps
the plain name so the saves it already mirrored stay where they are. The
launcher shows the name too, and two identical rows there are just as
confusing.

▸ **The storage provider may append an extension.** `createFile(mime, name)`
lets the provider add a mime-derived extension when the name's extension does
not match the mime — a slot asked for as `slot1.gz` with `application/gzip` can
land as `slot1.gz.gz`. So a child is found tolerantly: `<base>` or
`<base>.<anything>`, but not `slot10` for the base `slot1` (`findChild()`).
That is why the slot is *created* with the extension already matching the mime
and *found* tolerantly.

▸ **A restored bundle is written verbatim.** The files inside `bundle.zip` are
already in DOS 8.3 form — they were unpacked that way the first time — so
`GameLibrary.restore()` writes them as they are, with no renaming. That
reproduces the exact working game, `RUN.BAT` included, and sidesteps the
install-time path mangling of §8.4. Every entry still goes through
`resolveSafely()` (the §8.3 guard against `..` and absolute paths), because the
zip comes from the user's storage and not from the app's own installer.

▸ **The offer is asked once.** The first successful save shows *"Keep your
saves safe?"*; the answer is remembered in `saves_folder_offered`, so the app
does not nag. The choice stays reachable from **Config → Saves folder**, which
also shows whether a folder is set.

### 8.10 The launch editor

Some DOS games need several resident drivers before the main program (the
graphics and font TSRs of the Chinese and KOEI releases). The "Launch…" editor
(`GameLauncherView.showLaunchEditor()`, `GameLauncherView.kt:359`) lets the user
write the DOS commands that run after the `CD`:

![8.10 The launch editor](diagrams/tr25-8-10-the-launch-editor.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  Launch STARFALL                          ┌──────────────────────────┐
  ┌──────────────────────────────┐        │ Clear   A:   C:          │
  │ GRPDRV                       │        ├──────────────────────────┤
  │ MAIN                         │        │ GRPDRV  MAIN  INSTALL   │
  └──────────────────────────────┘        └──────────────────────────┘
   Tap a file below to add it as a line.      ← horizontally scrolling
   A:/B: are already mounted to the game.
```

</details>

The chips are **inserts**, not replacements: tapping one appends the file's name
(without its extension) as a new line. Typing on a phone is painful, so the
common case — load a driver, then start the game — is two taps.

The editor's text field is fixed at two lines (`minLines = maxLines = 2`) so it
never grows as lines are added: the chips below never shift, and the dialog
stays short enough for a landscape screen.

The result is stored in the manifest as the bundle's `launch` field and written
into `RUN.BAT` as the custom script (`setLaunch()`, `GameLibrary.kt:351`):

```
@ECHO OFF
MOUNT A GAMES/G1 >NUL
MOUNT B GAMES/G1 >NUL
C:
CD \GAMES\G1
GRPDRV
MAIN
```

▸ Picking a single start file again (`setEntryPoint()`) clears the custom
script — the two are alternatives, and the entry point wins.

### 8.11 The manifest

`games.json` sits next to the games directory, so `GAMES/` holds nothing but
games:

```json
[
  {
    "folder": "G1",
    "name": "三国志英杰传",
    "entry": "PLAY.BAT",
    "archive": "三国志英杰传",
    "installedAt": 1764000000000,
    "size": 10485760,
    "ownConfig": false
  }
]
```

| Field | Meaning |
|---|---|
| `folder` | the directory name — the DOS path of the game |
| `name` | the archive's name, shown in the launcher (may be non-ASCII) |
| `entry` | the entry point, relative to the game folder, with `\` separators |
| `archive` | the name of the archive it came from |
| `installedAt` | ms since the epoch — the list is sorted by it, newest first |
| `size` | the unpacked size in bytes |
| `ownConfig` | the archive ships its own `dosbox.conf` |
| `launch` | the custom launch script, if the user wrote one |

▸ `ownConfig` is only *reported*: the launcher shows *"ships its own
dosbox.conf"* in the row. The app cannot use that configuration without a
restart of the *configuration*, so it says so instead of pretending. The
bundle's `dosbox.conf` is not copied into the app's configuration — the app
regenerates `dosbox.conf` from its own asset on every boot (§8.6).

---

## 9. The on-screen keyboard

`VirtualKeyboardView.kt` (1101 lines) is a transparent full-screen overlay on
top of the SDL surface. It is the only part of the app that turns touches into
DOS input.

### 9.1 The overlay

![9.1 The overlay](diagrams/tr26-9-1-the-overlay.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │                                                                          │
 │   ┌────────┐      ┌──────────────────────────────┐      ┌────────┐      │
 │   │        │      │                              │      │        │      │
 │   │  left  │      │        the DOS picture       │      │  right │      │
 │   │  pad   │      │        (the SDL surface)     │      │  pad   │      │
 │   │        │      │                              │      │        │      │
 │   │        │      │   a tap here is NOT         │      │        │      │
 │   │        │      │   intercepted — it falls     │      │        │      │
 │   │        │      │   through as a mouse click   │      │        │      │
 │   └────────┘      └──────────────────────────────┘      └────────┘      │
 │                                                                          │
 │   the overlay is not clickable above the picture, so the picture keeps     │
 │   its own mouse input                                                      │
 └──────────────────────────────────────────────────────────────────────────┘
```

</details>

Three properties of the overlay view (`VirtualKeyboardView.kt:134-146`):

| Property | Value | Why |
|---|---|---|
| `isClickable` | `false` | a tap on the empty part of the overlay must fall through to the SDL surface below and become a DOS mouse click |
| `isMotionEventSplittingEnabled` | `true` | two thumbs on two different keys are two independent touches — without it, the second finger cancels the first |
| `rebuildControls()` | on `onSizeChanged` | the layout depends on the geometry, which is only known after the first layout pass |

▸ **The SDL surface is never resized to make room for the keyboard.**
`WhereqDosboxActivity.onCreate()` says so explicitly: changing the
`SurfaceView` size breaks DOSBox's Android texture renderer and the screen
goes black. The keyboard only uses the letterbox bars that the 4:3 DOS picture
leaves on either side.

### 9.2 ▸ Three layouts, chosen by geometry

The DOS picture is 4:3 and fills the height in landscape, so the width of each
side bar is:

```kotlin
// VirtualKeyboardView.kt:161
val barWidth = if (w > h) maxOf(0, (w - h * 4 / 3) / 2) else 0
```

and the layout follows from it:

![9.2 ▸ Three layouts, chosen by geometry](diagrams/tr27-9-2-three-layouts-chosen-by-geometry.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  barWidth  (dp)
      │
      ├─ ≥ 116 ─────────────────────────► MODE_PADS
      │     two thumb pads beside the picture
      │
      ├─ ≥ 56 ──────────────────────────► MODE_COLUMNS
      │     one column of keys per side + a fold-up QWERTY
      │
      └─ < 56 ──────────────────────────► MODE_BAR
            a slim bar at the bottom + the fold-up QWERTY
```

</details>

| Constant | Value | Meaning |
|---|---|---|
| `DP_MIN_PAD_WIDTH` | 116 dp | below this a bar cannot hold five keys in a row |
| `DP_MAX_PAD_WIDTH` | 210 dp | a wider pad puts its inner keys out of a thumb's reach |
| `DP_MIN_COLUMN_WIDTH` | 56 dp | below this a bar cannot hold even a column of keys |
| `DP_MAX_COLUMN_WIDTH` | 132 dp | |

![9.2 ▸ Three layouts, chosen by geometry](diagrams/tr28-9-2-three-layouts-chosen-by-geometry.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 MODE_PADS — the left pad                    the right pad
 ┌───────────────────────┐                     ┌───────────────────────┐
 │ File  Esc  Tab  RMB   │                     │ #+=  Fn               │
 ├───────────────────────┤                     ├───────────────────────┤
 │ 1  2  3  4  5         │                     │ 6  7  8  9  0         │
 │ q  w  e  r  t         │                     │ y  u  i  o  p         │
 │ a  s  d  f  g         │                     │ h  j  k  l  ;         │
 │ z  x  c  v  b         │                     │ n  m  .  /  \         │
 ├───────────────────────┤                     ├───────────────────────┤
 │                       │                     │                       │
 │  ←   ↑   ↓   →        │                     │ Ctrl   Alt  Shift     │
 │        ⊕              │                     │ Space  Enter          │
 └───────────────────────┘                     └───────────────────────┘
             ▲──── the DOS picture sits between the two pads ───────▲


 MODE_BAR (portrait, or a 4:3 tablet)
 ┌──────────────────────────────────────────────────────────┐
 │                    the DOS picture                       │
 └──────────────────────────────────────────────────────────┘
 ┌──────────────────────────────────────────────────────────┐
 │  ←  ↑  ↓  →      Ctrl  Alt   Space   Enter               │
 │  Esc  Tab  RMB     ⌨  (folds the full QWERTY up)         │
 └──────────────────────────────────────────────────────────┘


 MODE_COLUMNS is the same idea, one key column per side:
 ┌──────┬──────────────────────┬──────┐
 │  ←   │                      │ Ctrl │
 │  ↑   │   the DOS picture    │ Alt  │
 │  ↓   │                      │Shift│
 │  →   │                      │Space│
 │      │                      │Enter│
 └──────┴──────────────────────┴──────┘
```

</details>

The left column carries **File**, **⌨**, **Esc**, **Tab** and **RMB** above the
arrows; the right one carries the action keys. **RMB** is in all three layouts,
so a right click is always one tap away.

The rebuild happens **only when the mode or the bar width actually changed**
(`if (newMode == mode && barWidth == builtBarWidth) return`), so a rotation
that lands on the same geometry does not rebuild the keys.

### 9.3 The key model

A key is described by a string, `label:keyName:kind`:

```
  "a"                  → the label "a", the key name "a", an ordinary key
  "lshift:lshift:m"    → the label "lshift", a modifier
  "!::s"               → the label "!", the key name ":" (the scancode name),
                         a key that needs Shift
  "period:period"      → the label "period", the key name "period"
```

| Kind | Code | Behaviour |
|---|---|---|
| `k` | `KIND_KEY` | an ordinary key: down on touch, up on release |
| `m` | `KIND_MODIFIER` | Shift / Ctrl / Alt — the armed/held model of §9.5 |
| `s` | `KIND_SHIFTED` | a key whose character is shifted — Shift is wrapped around it |

Three control keys sit outside that model, because they are not keys at all:
**File** opens the launcher, **⌨** folds the QWERTY up, and **RMB** is a mouse
button — RIGHT down on touch, up on release, at the current DOS cursor (§4.7).

The three layers are the two halves of a split QWERTY:

![9.3 The key model](diagrams/tr29-9-3-the-key-model.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  LAYER_LETTERS (0)              LAYER_SYMBOLS (1)             LAYER_FUNCTIONS (2)
  ┌─────────────────┐            ┌─────────────────┐           ┌─────────────────┐
  │ 1 2 3 4 5       │            │ 1 2 3 4 5       │           │ F1 F2 F3        │
  │ q w e r t       │            │ ! : 1 : s  @ 2  │           │ F4 F5 F6        │
  │ a s d f g       │            │ -  =  [  ]  `   │           │ F7 F8 F9        │
  │ z x c v b       │            │ _ +  {  }  ~    │           │ F10 F11 F12     │
  └─────────────────┘            └─────────────────┘           └─────────────────┘
  ┌─────────────────┐            ┌─────────────────┐           ┌─────────────────┐
  │ 6 7 8 9 0       │            │ 6 7 8 9 0       │           │ Ins Home PgUp   │
  │ y u i o p       │            │ ^ & * ( )       │           │ Del End PgDn    │
  │ h j k l ;       │            │ ; ' , \ |       │           │ Caps PrtSc Pause│
  │ n m . / \       │            │ : " < > ?       │           │ _               │
  └─────────────────┘            └─────────────────┘           └─────────────────┘
```

</details>

▸ **The letters carry `: . / \` along.** The right-hand third row is
`h j k l ;` and the bottom row is `n m . / \`, so a DOS path — `C:\GAMES\G1` —
needs no layer switch at all. That is the difference between a keyboard you can
type a path on and one you cannot.

▸ **The symbol mappings assume a US layout** (`!` = Shift+1, `:` = Shift+`;`).
DOSBox feeds scancodes to the BIOS and the US code page is the shipped default,
so the shifted character has to be produced the way the BIOS expects it.

The layer switch is a **toggle**: tapping `#+=` switches to the symbol layer,
tapping it again switches back (`showLayer()` is called with the other layer).
`Fn` does the same for the function layer.

### 9.4 ▸ The path of a key press

![9.4 ▸ The path of a key press](diagrams/tr30-9-4-the-path-of-a-key-press.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  the finger
      │
      ▼
  VirtualKeyboardView.makeKey()          a raw touch listener, not a click
      │  ACTION_DOWN ─► keyDown(keyName, kind)
      │  ACTION_UP   ─► keyUp(keyName, kind)
      ▼
  push(keyName, pressed)                 VirtualKeyboardView.kt:930
      │  scancode = scancodeOf(keyName)  ← cached in `scancodes`
      │      └─► DosboxNative.nativeScancodeFromName(name)   (the first time)
      │              └─► JNI: the key_name_map table → SDL_Scancode
      ▼
  DosboxNative.nativePushKey(scancode, pressed)
      │
      ▼  JNI (android/jni/dosbox_android.cpp:272)
  SDL_Event ev
      ev.type           = SDL_KEYDOWN / SDL_KEYUP
      ev.key.keysym.scancode = the scancode
      ev.key.keysym.sym     = SDL_GetKeyFromScancode(scancode)
      SDL_PushEvent(&ev)          ◄── thread safe: the UI thread may push
      ▼                              while the emulator runs
  DOSBox: GFX_Events()  →  sdl_mapper
      │  the mapper indexes its bind list by the *scancode*
      │  and activates the matching CKeyEvent
      ▼
  KEYBOARD_AddKey(KBD_KEYS, pressed)   src/hardware/keyboard.cpp:228
      │  the KBD_KEYS → PC scancode table, then the emulated 8042 buffer
      ▼
  PIC_ActivateIRQ(1)                   src/hardware/keyboard.cpp:55-60
      ▼
  IRQ 1 → INT 09h → IRQ1_Handler       src/ints/bios_keyboard.cpp:233
      │  DOS_LayoutKey() → BIOS_AddKeyToBuffer()
      ▼
  the BIOS keyboard buffer at 0x41A/0x41C + 0x41E
      ▼
  INT 16h AH=00h — the guest reads the keystroke
```

</details>

▸ **No DOSBox patch is involved.** The virtual keyboard is just another
producer of SDL key events, exactly like the hardware keyboard. That is why
the keyboard needs nothing from the emulator except the scancode table in the
JNI layer.

▸ **The keystroke takes the whole hardware path.** It does *not* shortcut
into the BIOS buffer: it goes through the emulated 8042, raises **IRQ 1**, and
is delivered by **INT 09h** like a real keystroke. A guest that installs its
own INT 09h hook, or that intercepts keys through INT 15h AH=4Fh, therefore
sees the virtual keys too. The interrupt-level version of this chain is in
`docs/INTERRUPTS.md` §6.

▸ **The Game Mode gamepad takes exactly the same path** — it pushes the same
SDL key events, so a thumb on the gamepad and a finger on the keyboard are
indistinguishable to the guest (§10.7).

▸ **A press before SDL is up is dropped.** `nativePushKey` checks
`SDL_WasInit(SDL_INIT_VIDEO)` and returns `false` when SDL has not started yet
(`dosbox_android.cpp:283`). The keyboard logs *"could not push <key> down"* and
carries on.

▸ **The keys use a raw touch listener, not a click listener.** A click cannot
express "pressed and held", which is what a game's Ctrl-fire needs. The
trade-off is that these keys are not exposed to TalkBack; the *action* buttons
(File, the layer keys, the panel toggle) use click listeners and stay
accessible.

▸ **The RMB key is not a key.** It takes the *mouse* path, not the scancode
path: `attachMouseButton()` → `DosboxNative.nativePushMouseButton()` →
`Mouse_ButtonPressed()` / `Mouse_ButtonReleased()` (§4.7). No scancode is
involved — a right mouse button is not a keyboard key, and the DOS side learns
about it through the INT 33h driver instead.

### 9.5 ▸ The modifier model

Shift, Ctrl and Alt have to work two ways on a touch screen:

* a **tap** arms the modifier — it stays pressed until the next ordinary key is
  typed, or until it is tapped again. That is one-finger `Ctrl`+`C`;
* a **hold** keeps it pressed for exactly as long as the finger is down, like a
  real key. That is what a game wants from Ctrl (fire) or Alt (strafe) while
  the other thumb is on the arrows.

The state is five pieces per modifier:

| Field | Type | Meaning |
|---|---|---|
| `armed[key]` | `Boolean` | the modifier is logically down |
| `heldCount[key]` | `Int` | how many fingers are on it (the same modifier is shown twice — in a column *and* in the panel) |
| `heldSince[key]` | `Long` | when the current hold started (`SystemClock.uptimeMillis()`) |
| `usedWhileHeld[key]` | `Set` | a key was typed while the finger was down |
| `disarmedByTouch[key]` | `Set` | the user tapped it a second time to cancel it |

![9.5 ▸ The modifier model](diagrams/tr31-9-5-the-modifier-model.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  DOWN on a modifier
      │
      ├─ heldCount > 1 ? ──► another finger is already on it: keep the count, do nothing
      │
      ├─ already armed ? ──► a second tap takes it back:
      │                       armed = false, push(up), disarmedByTouch += key
      │
      └─ otherwise ────────► armed = true, push(down), heldSince = now

  UP on a modifier
      │
      ├─ heldCount > 1 ? ──► another finger is still on it: decrement, keep it down
      │
      └─ the last finger leaves:
          │
          ├─ disarmedByTouch ? ──► it was cancelled by a second tap: nothing to do
          │
          └─ armed && (usedWhileHeld || heldFor ≥ 350 ms)
                 ├─ yes ──► release it with the finger (push(up), armed = false)
                 └─ no  ──► leave it ARMED: a short tap arms the next key
```

</details>

▸ **The 350 ms threshold (`HOLD_MILLIS`) is what separates the two gestures.**
A short tap arms the modifier for the next key; a longer hold behaves like a
physical key.

▸ **A modifier applies to exactly one key.** After an ordinary key is typed,
`releaseModifiers(force = false)` runs (`VirtualKeyboardView.kt:896`): every
armed modifier that no finger is holding is released, and one that *is* being
held is marked `usedWhileHeld` and stays down.

A **shifted** key (`kind = s`) does not go through the armed model at all: it
pushes Shift down, pushes the key, and on the release pushes the key up and
Shift up — but only if Shift was not armed or held independently
(`VirtualKeyboardView.kt:846-891`).

### 9.6 ▸ The stuck-key rule

The keys are rebuilt whenever the geometry changes (§9.2). The views that
carried the pressed keys are about to disappear, so their `ACTION_UP` will
**never arrive** — and a key that is never released stays down in DOSBox
forever. A rotation in the middle of a held key would therefore leave the
guest with a stuck key.

The rebuild releases everything first (`rebuildControls()`, `VirtualKeyboardView.kt:172`):

![9.6 ▸ The stuck-key rule](diagrams/tr32-9-6-the-stuck-key-rule.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  releaseModifiers(force = true)      every armed modifier is pushed up
      │
      ▼
  for (scancode in downScancodes)      every key that is down is pushed up
      nativePushKey(scancode, false)
      │
      ▼
  downScancodes.clear()
      │
      ▼
  removeAllViews(); the state maps are cleared; layer = LAYER_LETTERS
```

</details>

▸ `downScancodes` is a **set of scancodes**, not a list of keys. Two keys that
map to the same scancode are held once in DOSBox, so the set holds it once —
which is exactly what DOSBox wants.

▸ The order matters: the modifiers are released **first**, because
`releaseModifiers` pushes the modifier's own scancode, and the ordinary keys
follow. Forcing the release (`force = true`) also releases a modifier that a
finger is still holding — the finger's view is going away anyway, so the
modifier has to go with it.

### 9.7 The panel and the toggle

The `MODE_COLUMNS` and `MODE_BAR` layouts keep the full QWERTY out of the way
until it is needed: a toggle button folds it up from the bottom
(`buildPanel()`, `togglePanel()`). The panel is 92 % of the shorter screen
edge wide (`panelWidth = min(widthPixels, heightPixels) * 92 / 100`).

The "File" button on the left pad is the only key that is not a DOS key: it
reports to the activity, which opens the bundle launcher (§8). The keyboard
does not know what a bundle is — it only reports that the button was pressed
(`OnFilesRequestedListener`).

▸ The **back button** walks the app back out, one thing at a time: the
launcher closes first (it is on top of the keyboard), then **Game Mode** goes
back to the keyboard, then the keyboard panel folds, and only then does the
press leave the app. The decision is a pure function —
`resolveBackAction(launcherVisible, gameModeVisible, keyboardPanelVisible)`
in `BackAction.kt` — so it is unit-tested on the JVM, and the order is the
same on every device.

▸ On **Android 13 (API 33) and newer** the system no longer sends
`KEYCODE_BACK` to the activity, and it no longer calls `onBackPressed()`
either; it hands the back gesture to the **predictive-back dispatcher**.
SDL2's `SDLActivity.handleKeyEvent()` returns `true` for every key, so the
surface swallowed the back key and the button did nothing at all. The app
therefore registers an `OnBackInvokedCallback`
(`WhereqDosboxActivity.registerBackInvokedCallback()`) and handles the
gesture itself. The manifest must **not** opt out of predictive back
(`android:enableOnBackInvokedCallback="false"`): that restores the legacy
path, which SDL breaks. A unit test reads the manifest and fails if the
opt-out ever comes back.

▸ Older systems (Android 12 and before) have no such dispatcher, so the same
decision is made in `onKeyDown()` and in `onBackPressed()`. The behaviour is
the same everywhere; only the way the press arrives differs.

### 9.8 ▸ The measured key sizes

The layout constants are only half the story — what matters is how big a key
comes out on a real screen. Measured on the emulator at **420 dpi** (1 dp =
2.625 px):

| Screen | bar width | letter key | key height |
|---|---|---|---|
| **20:9** (2400×1080) | 183 dp | **32.8 dp** wide | 44 dp |
| **18:9** (2160×1080) | 137 dp | **23.6 dp** wide | 44 dp |

Both still use the **pads**: the 18:9 bar is 137 dp and the pads need 116 dp,
so nothing falls back to the columns. The keys do get smaller — and the
D-pad and the action keys are unaffected, keeping their full 44 dp height and
their double width.

▸ **23.6 dp is the number to decide on.** It is about 2.9 % of the screen
width, where a normal phone keyboard key is roughly twice that. It is still
tappable — the typing test on the 18:9 display confirmed that the keys hit
the right characters (`abc` came out as `abc`) — but it is at the edge of
what a fingertip can do reliably, and the answer to that is a layer switch,
not a bigger key.

The three layers, as they run:

| | a wide screen | 18:9 |
|---|---|---|
| Letters | [`android-n8-03-dos-boot-prompt.png`](screenshots/android-n8-03-dos-boot-prompt.png) | [`android-n8-29-keyboard-18x9-letters.png`](screenshots/android-n8-29-keyboard-18x9-letters.png) |
| Symbols (**#+=**) | [`android-n8-22-keyboard-symbols.png`](screenshots/android-n8-22-keyboard-symbols.png) | [`android-n8-29-keyboard-18x9-symbols.png`](screenshots/android-n8-29-keyboard-18x9-symbols.png) |
| Functions (**Fn**) | [`android-n8-23-keyboard-functions.png`](screenshots/android-n8-23-keyboard-functions.png) | [`android-n8-29-keyboard-18x9-functions.png`](screenshots/android-n8-29-keyboard-18x9-functions.png) |

All six are captures of the release build on the emulator: the wide column at
20:9 (2400×1080), the 18:9 column with the display resized to 2160×1080. The
earlier n7 series also kept a real-phone capture of the letter layer (22:9,
2376×1080), listed below.

---

## 10. The Game Mode gamepad

The on-screen keyboard (§9) is for **typing**. Game Mode is for **playing**:
the same letterbox bars, the same geometry, a different set of controls. It is
a **WhereQ-only** feature — nothing in `src/` or `include/` knows it exists,
and that is deliberate (§10.7).

The whole feature is one Kotlin file,
`android/app/src/main/java/com/whereq/dosbox/GameModeView.kt` (1042 lines),
plus the hosting in `WhereqDosboxActivity.kt`.

```
  10.1  The shape: three profiles over one skeleton
  10.2  The geometry: the same bars as the keyboard
  10.3  The D-pad: eight octants, three output modes
  10.4  The trackball: relative motion, tap = click
  10.5  The buttons: hold, tap, and FIRE
  10.6  The remapper: one map per game
  10.7  The key path: the gamepad is a keyboard
  10.8  The Konami code, in and out
  10.9  The per-game memory
```

### 10.1 The shape: three profiles over one skeleton

One layout is built for each pad — a top bar, an upper row, a big centre
control and a bottom row (`GameModeView.kt:184`) — and the **profile** only
changes the labels and the centre control. The DOS picture is never touched:
switching profile rebuilds the two pads and nothing else.

| profile | left thumb | right thumb | for |
|---|---|---|---|
| **HYBRID** (default) | 8-way D-pad | trackball, L/R-CLICK shoulders | games that mix keys and mouse — Dune II, X-COM, Doom with a mouse |
| **ACTION** | 8-way D-pad | face diamond: **FIRE** (Ctrl), **JUMP** (Alt), **USE** (Space), **ENT**; shoulders **SHIFT**/**TAB**, strafe **,** / **.** | keyboard arcade/shooters — Wolf3D, Keen, Prince of Persia |
| **POINT & CLICK** | big LEFT/RIGHT mouse buttons | trackball, DRAG lock, DBL, SHIFT/CTRL modifiers, pointer speed | mouse-only games — Monkey Island, Civilization, Lemmings |

The profile is chosen from the gold button in the top-left corner: a **tap**
cycles it, a **long press** opens a picker with the three profiles plus the
remap entry (`GameModeView.kt:324`). The D-pad output mode is a chip in the
bottom row of the left pad — **ARROWS**, **NUMPAD** or **WASD**. The chip
carries the name of the mode, so the bar is rebuilt when the mode changes
(`setDpad()`), and the mode remembered for the game is restored **before** the
profile, so the first build of the bar already uses it
(`showGameMode()`, `WhereqDosboxActivity.kt`).

The two face buttons at the top right are **Y** and **X**. They are not
remappable, and they are not decoration: they answer the *Quit? (Y/N)* prompt
of a DOS game, and they are the last two tokens of the exit code (§10.8).

### 10.2 The geometry: the same bars as the keyboard

The pads live in the letterbox bars beside the 4:3 DOS picture, and the bar
width is computed the same way as in §9.2:

```kotlin
val barWidth = if (w > h) maxOf(0, (w - h * 4 / 3) / 2) else 0   // GameModeView.kt:146
```

Two differences from the keyboard:

- the pad is **capped** at `DP_MAX_PAD_WIDTH` (230 dp) so the controls do not
  stretch across a wide screen (`GameModeView.kt:171`);
- below `DP_MIN_PAD_WIDTH` (120 dp) there is no room for a gamepad at all, so
  the view shows one line — *"Rotate to landscape for Game Mode — press Back
  for the keyboard"* (`GameModeView.kt:156-169`). Game Mode is a landscape
  feature; the hardware Back button returns to the keyboard.

### 10.3 The D-pad: eight octants, three output modes

The D-pad is a circle, not four buttons (`DpadView`, `GameModeView.kt:742`).
The touch point is reduced to one of **eight octants** — 0=E, 1=NE, 2=N, 3=NW,
4=W, 5=SW, 6=S, 7=SE — with a dead zone of 30 % of the radius
(`DEADZONE`, `GameModeView.kt:994`). A cardinal octant emits **one** key; a
diagonal emits **two** keys at once, because that is what a keyboard does when
you hold two arrows.

The three output modes differ exactly here:

| mode | E | NE | N | NW | W | SW | S | SE |
|---|---|---|---|---|---|---|---|---|
| **ARROWS** | right | up+right | up | up+left | left | down+left | down | down+right |
| **WASD** | d | w+d | w | w+a | a | s+a | s | s+d |
| **NUMPAD** | kp_6 | kp_9 | kp_8 | kp_7 | kp_4 | kp_1 | kp_2 | kp_3 |

(`ARROWS_OCT` / `WASD_OCT` / `NUMPAD_OCT`, `GameModeView.kt:1009-1020`.)

▸ **Why NUMPAD is not "arrows, but numeric".** A DOS game that was written for
a joystick reads the **numeric keypad**, not the arrow keys: Num 8 is up, Num 2
is down, and the diagonals are the single keys 7, 9, 1 and 3 — exactly how the
IBM PC's keyboard cluster was laid out so a joystick could be read through the
keypad. That is why the NUMPAD row has *one* key per diagonal where the other
two rows have two: the diagonals are single keys in the BIOS layout too, so
pressing kp_7 and kp_9 together would be wrong.

### 10.4 The trackball: relative motion, tap = click

The trackball (`TrackballView`, `GameModeView.kt:866`) is **relative**, the way
a laptop touchpad is: the pointer does not jump to where you touch, it moves by
the distance you drag. The motion is scaled by `trackSpeed` (the SPEED chip in
the POINT profile) and sent to DOSBox as an SDL mouse-motion event tagged with
the magic device id `WHEREQ_REL_MOUSE` (`android/jni/dosbox_android.cpp:400-436`).

That tag is what makes it work at all. DOSBox's own `HandleMouseMotion()` drops
relative motion while the phone's auto-capture is on, so `GFX_Events()` picks
the tagged event out of the queue and feeds it straight to the emulated mouse
(`src/gui/sdlmain.cpp:2850-2861`):

```c
if (event.motion.which == 0x57515242u /* WHEREQ_REL_MOUSE "WQRB" */) {
    Mouse_CursorMoved((float)event.motion.xrel, (float)event.motion.yrel, 0.0f, 0.0f, true);
    break;
}
```

A **tap** — down and up within `TAP_MS` (220 ms) and less than `TAP_SLOP_DP`
(10 dp) of movement — is a left click. On the DOS side that is the same
held-button semantics as §4.7: the button is a *level*, so the click is real
for as long as the finger is down.

### 10.5 The buttons: hold, tap, and FIRE

Three button kinds, one key path:

| kind | behaviour | used by |
|---|---|---|
| `holdKey` | the key is held down while the finger is down, released on lift | CTRL, ALT, SHIFT, TAB, SPACE, ENT, USE, JUMP, STR ◂/▸, and the POINT profile's SHIFT/CTRL click modifiers |
| `tapKey` | a press and a release, with a haptic tick | **Y**, **X**, ESC, the Fn/1–9 strip |
| `fireButton` | the fire key held down — and re-struck every `TURBO_PERIOD_MS` (70 ms) **when the TURBO toggle is on** | **FIRE** in the ACTION profile |

`holdKey` and `tapKey` differ only in that `tapKey` also feeds the exit-code
detector (§10.8). The FIRE button keeps its own name and shows the key it
sends as its sub-label, because "FIRE" is what the profile means by it — the
key underneath is Ctrl, unless a remap says otherwise
(`GameModeView.kt:419-452`).

▸ **Nothing sticks.** Every key the gamepad holds is tracked in `downKeys`, and
`releaseAll()` pushes the release for each one when the view is hidden or the
pads are rebuilt (`GameModeView.kt:90`, `:128-131`, `:149`). A rebuild happens
on a profile change, a D-pad change and a remap — so a thumb that was down
during a remap would otherwise leave a key stuck down inside DOSBox.

### 10.6 The remapper: one map per game

A DOS game wants a different key for "fire" than the next one, and the owner
should not have to pick a global compromise. So the gamepad's key-sending
buttons are **remappable**, per game.

The model is a flat map from a **slot** to a key name:

```
slot = "<profile>:<defaultKey>"        e.g. "ACTION:ctrl"  →  "f5"
```

(`slotFor`, `GameModeView.kt:546`.) One map covers all three profiles, because
the slot names the profile it belongs to.

The gesture is a **mode**, not a long press per button — a long press would
collide with hold-to-fire. It is entered from the gold profile button's
long-press menu, whose fourth entry is *"Remap buttons…"* (it becomes *"Done
remapping"* while the mode is on, so the mode always has an exit):

- while the mode is on, an **outline** marks the buttons that can be
  remapped (`markRemappable`, `GameModeView.kt`) — the key-sending buttons
  and FIRE, and *not* Y/X, ESC, the D-pad, the trackball, the mouse buttons or
  the function chips. The outline is **gold on the dark chips and dark brown on
  the gold FIRE chip**: it is chosen by the luminance of the chip it marks, so
  it is never invisible (gold on gold was, before);
- a tap on an outlined button opens a **picker**: *"Reset to default"* first,
  then the curated `REMAP_CHOICES` — the action keys, F1–F12, A–Z, 0–9, the
  four arrows and eight symbols (`GameModeView.kt:585-602`, `:982-992`);
- a **banner** at the top of the picture area shows *DONE* and *"Tap a button
  to remap"* (`GameModeView.kt:624`). It is at the top, mirroring the Fn/1–9
  strip at the bottom, so it can never cover a control.

The map is stored per game as a small JSON blob, `gm_remaps_<folder>`, in the
`LAUNCH_PREFS` shared preferences
(`WhereqDosboxActivity.kt:339-358`, `:1334`).

▸ **Two bugs that only driving the UI could find.** The first version of the
banner was added once, when remap mode was entered — so the rebuild that a
remap itself triggers destroyed it, and the user was stuck in remap mode with
no way out. The banner is now rebuilt with the pads whenever `remapMode` is on
(`GameModeView.kt:180`). The second: a long press on the profile button while
in remap mode *re-entered* remap mode instead of leaving it; the menu entry now
toggles.

### 10.7 The key path: the gamepad is a keyboard

This is the design decision that matters most, and it is the reason Game Mode
needs **nothing** from the emulator core.

The gamepad pushes the same SDL key events as the on-screen keyboard
(`GameModeView.kt:651`, `DosboxNative.nativePushKey`), so the whole path of
§9.4 applies unchanged — and that path ends in an *interrupt*, not in a
shortcut:

![10.7 The key path: the gamepad is a keyboard](diagrams/tr33-10-7-the-key-path-the-gamepad-is-a-keyboard.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  the thumb
      │
      ▼
  GameModeView.push(keyName, pressed)          GameModeView.kt:651
      │  scancode = nativeScancodeFromName(keyName)
      ▼
  SDL_PushEvent(SDL_KEYDOWN / SDL_KEYUP)       android/jni/dosbox_android.cpp:282-314
      ▼
  GFX_Events() → sdl_mapper
      │  the bind list is indexed by the *scancode*;
      │  the default bind for it is a CKeyEvent
      ▼
  KEYBOARD_AddKey(KBD_KEYS, pressed)           src/hardware/keyboard.cpp:228
      │  the KBD_KEYS → PC scancode table, then the emulated 8042 buffer
      ▼
  PIC_ActivateIRQ(1)                           src/hardware/keyboard.cpp:55-60
      ▼
  IRQ 1 → INT 09h → IRQ1_Handler               src/ints/bios_keyboard.cpp:233
      │  DOS_LayoutKey() → BIOS_AddKeyToBuffer()
      ▼
  the BIOS keyboard buffer at 0x41A/0x41C + 0x41E
      ▼
  INT 16h AH=00h — the guest reads the keystroke
```

</details>

The full chain, with the interrupt-level consequences, is in
`docs/INTERRUPTS.md` §6.

▸ **What the guest can tell, and what it cannot.** The guest sees a keyboard.
A game that reads the BIOS keyboard buffer, that installs its own INT 09h hook,
or that intercepts keys through INT 15h AH=4Fh cannot distinguish a thumb on
the gamepad from a finger on the virtual keyboard from a real Bluetooth
keyboard. That is a feature: a DOS game that was written for a keyboard works
with the gamepad, and one that was written for a joystick wants the **NUMPAD**
mode, which is the keyboard's own joystick-emulation layout.

▸ **The mouse is a separate path.** The trackball and the mouse buttons do not
go through the keyboard at all: the motion becomes a tagged SDL mouse-motion
event and the buttons become real `SDL_MOUSEBUTTONDOWN`/`UP` events, which is
the INT 33h driver's business, not INT 16h's (`docs/INTERRUPTS.md` §5).

### 10.8 The Konami code, in and out

Game Mode is entered by the **Konami code** on the keyboard, and left by the
reverse code on the gamepad — the same gesture, mirrored.

**In** (on the on-screen keyboard): `↑ ↑ ↓ ↓ ← → ← → B A`. The **B and A must
be capital**. The keyboard watches every key-down, and a letter contributes the
case it is *actually* typed in — Caps Lock on (double-tap Shift), or a tapped
or held Shift, makes it capital (`konamiToken`,
`VirtualKeyboardView.kt:1037-1041`; the sequence itself is
`GameModeView.KonamiDetector.KEYBOARD_ENTER_SEQUENCE`, `GameModeView.kt:975`).
A lowercase "ba" is just "ba" in the DOS prompt.

▸ **Why the case is decided in the app.** Caps Lock is *not* DOSBox's
CapsLock scancode: SDL only reports lock *states*, and DOSBox's handling of a
synthetic press+release does not survive it, so the app applies the case
itself by wrapping Shift around the letter (`VirtualKeyboardView.kt:936-941`).
The app therefore always knows the effective case — which is what lets the
detector match on capitals. The guest-side consequence is that the BIOS's own
caps flag stays clear: a capital letter reaches the guest as **Shift + a
letter**, not as a locked state (`docs/INTERRUPTS.md` §6).

**Out** (on the gamepad): the D-pad's `↑ ↑ ↓ ↓ ← → ← →` and then the **X, Y**
buttons, which stand in for B and A (`GAME_EXIT_SEQUENCE`,
`GameModeView.kt:967`). The D-pad feeds the detector on the cardinals only
(`cardinalToken`, `GameModeView.kt:1005`, fed from `setActive`, `:844`), and
the hardware Back button returns to the keyboard as well
(`WhereqDosboxActivity.kt:1147-1151`).

Both detectors are the same nested class — `KonamiDetector`, a 10-token
ring buffer that fires on an exact match (`GameModeView.kt:952-961`).

### 10.9 The per-game memory

Three things are remembered per game, each keyed by the game folder and stored
in `LAUNCH_PREFS`:

| prefix | value | written by |
|---|---|---|
| `gm_profile_<folder>` | the profile name | `onProfileChanged` |
| `gm_dpad_<folder>` | the D-pad output mode | `onDpadChanged` |
| `gm_remaps_<folder>` | the remap map, as a JSON blob | `onRemapsChanged` |

(`WhereqDosboxActivity.kt:306-358`, `:1330-1334`.) They are restored when the
game is shown and saved when they change, so a game that needs the keypad and
the fire key on F5 keeps that setup and another game is untouched.

---

## 11. Verification

This section records **how each claim in this document was proven**, so a
reviewer can tell a measured fact from an inference. The short version: the
native side is verified by the log lines the port itself writes; the app side
by driving the release APK on an emulator.

### 11.1 The verification matrix

| Claim | § | How it was proven | Evidence |
|---|---|---|---|
| The OPL chip is restored **exactly** | 6.3 | the same state file loaded twice, the two log lines compared field by field | `savestate: OPL chip saved:` vs `… restored:` — identical `state`, `rate`, `phase`, `vol`, `lfo` |
| The register replay is **not** exact | 6.8 | the same file loaded by a build with the exact snapshot disabled | `… OPL chip replayed: … attack … phase=80000000 … vol=000001ff … lfo=00000000` |
| The chip struct is 5176 bytes | 6.2 | a compile-time probe (`sizeof`) | see §11.4 |
| The OPL has **two ports** (388h/389h) | 6.1, App. A | a test program that wrote both bytes to 388h programmed nothing | the chip stayed idle in `release` — the bug that was found and fixed |
| The save-state file is a fixed-order block sequence | 5.4 | the source of `do_save()` / `do_load()` read against the payload-size function | `src/gui/savestate.cpp:257-294` vs `:147` |
| The restore is a **dependency order**, not an arbitrary one | 7.4 | the DOS block re-opens files through the drive it restores | `src/dos/dos_state.cpp` |
| The OPL block must be **consumed** even in the fallback | 6.6 | a fallback build mis-read the DOS block behind it | the file-cursor desync that was found and fixed |
| A **stuck key** survives a layout rebuild | 9.6 | the key-down/up state is tracked explicitly and released on a rebuild | `VirtualKeyboardView.kt:172-180` |
| The **mouse** is in the save state | 5.5 | a save → load round trip on the emulator with the new block in place | the OPL chip comes back field for field, the four DOS handles with it, no payload-size mismatch |
| The mouse block is **stable configuration only** | 5.5 | the block was slimmed in 1.0.0.12 after restoring the volatile half wedged mouse-driven games, and then grown again to the whole configuration the guest cannot rebuild | the event queue and the interrupt flags are cleared on load, not restored; the cursor shape and hotspot are restored |
| An **old state** is refused, not misread | 5.4 | loading a slot written by 1.0.0.10 (version 7) into the version-8 build | *"That save file is damaged"* |
| An **older slot** is refused by this build | 5.4 | loading a slot written by 1.0.0.11 (version 8) into 1.0.0.12 | *"That save file is damaged"* — the version is checked before the payload size |
| The three keyboard layouts follow from the geometry | 9.2 | the width formula and the two thresholds | `VirtualKeyboardView.kt:161-167`; measured on the emulator at 420 dpi (§9.8) |
| The bundle names are rewritten to DOS 8.3 | 8.4 | the naming function read against DOSBox's own name handling | `GameLibrary.kt:551` vs `localDrive.cpp` |
| A **relaunch** is needed, not a typed command | 8.7 | DOSBox caches the drive listing at mount time | `localDrive.cpp:184`, quoted in `WhereqDosboxActivity.kt:130-136` |
| The build produces **three ABIs** | 2.2 | the ABI list is verified in CI and the `.so` files are listed in the APK | `.github/workflows/ci.yml`, the ABI verification step |
| The APK is **signed** with the release key | 3.5 | `apksigner verify --print-certs` | cert SHA-256 `b16b480d5a1ff6d35baff0b15fa3fc4b0ee815e500861fbff1b062c9bf7d3933` |
| The **Konami code** enters Game Mode | 10.8 | the ten tokens typed on the emulator's keyboard, with the detector's own log line | `konami feed 'up' … 'B' 'A'` → `KONAMI MATCH`, and the pads flip to the gamepad |
| The code needs **capital** B and A | 10.8 | the same sequence typed twice, once with Caps Lock off and once on | lowercase: *"ba"* lands in the DOS prompt and **nothing** happens; Caps on (the key turns blue and reads CAPS): *"BA"* and Game Mode appears |
| The **reverse code** leaves Game Mode | 10.8 | the D-pad's eight cardinals plus X, Y | the app is back on the keyboard, and the X/Y keys land in the prompt as *"xy"* |
| A **remap** survives a restart | 10.6 | CTRL → F5, then the app was killed and relaunched | the stored blob `gm_remaps__` still holds the slot → key mapping, and the button is relabelled |
| Only the **remappable** buttons are outlined | 10.6 | remap mode on the ACTION profile | SHIFT/TAB/STR/ENT/USE/JUMP outlined; Y/X/ESC/D-pad/trackball/chips not |
| The gamepad is **a keyboard** to the guest | 10.7 | the whole path read in the source, and the keystrokes landing in the DOS prompt | SDL_KEYDOWN → mapper → `KEYBOARD_AddKey` → IRQ 1 → INT 09h → INT 16h (`docs/INTERRUPTS.md` §6) |

### 11.2 The emulator runs

| | |
|---|---|
| Device | `emulator-5554` — `sdk_gphone64_x86_64` (Android API 37, x86_64) |
| Build under test | the **release** APK, all three ABIs |
| APK | the published `whereq-dosbox-1.0.0.17.apk`, 16 510 522 bytes |
| SHA-256 | `796203db84f31e994c45a3965fe3919b5e7a030a5ad8e45e4a9eabbed3f4ac0a` |
| Published asset | `whereq-dosbox-1.0.0.17.apk` on the [v1.0.0.17 release](https://github.com/whereq/whereq-dosbox/releases/tag/v1.0.0.17), SHA-256 `796203db84f31e994c45a3965fe3919b5e7a030a5ad8e45e4a9eabbed3f4ac0a` |
| Previous builds | `whereq-dosbox-1.0.0.16.apk` — 16 510 058 bytes (`183f16ee…`). Before those: `whereq-dosbox-1.0.0.15.apk` — 16 500 506 bytes (`dbf776ab…`) |
| Version | `versionCode` 1000017, `versionName` 1.0.0.17 |
| Signature | release key, cert SHA-256 `b16b480d5a1ff6d35baff0b15fa3fc4b0ee815e500861fbff1b062c9bf7d3933` (§3.5) |
| ABIs in the APK | `lib/arm64-v8a/libdosbox.so`, `lib/armeabi-v7a/libdosbox.so`, `lib/x86_64/libdosbox.so` — all three |

The release APK is used rather than the debug one on purpose: the debug build
is signed with an auto-generated key and is `android:debuggable="true"`, which
changes nothing about the emulator but does change the install path (an update
signed with a different key has to be uninstalled first).

**What was exercised on it:**

| Scenario | Result |
|---|---|
| The app boots to a `C:\>` prompt | ✓ |
| A bundle is installed from a `.zip` | ✓ — the file picker opens at `primary:Download` |
| The bundle is unpacked and the entry point detected | ✓ |
| "Play" relaunches into the game | ✓ — the process restarts, `[autoexec]` runs `RUN.BAT` |
| The OPL test bundle plays a tone | ✓ |
| Save → load → the log lines are identical | ✓ (§11.3) |
| The DOS layer is restored (the open handles come back) | ✓ |
| The keyboard survives a rotation | ✓ — no stuck key |
| The **RMB** key is in all three layouts | ✓ — the left pad, the key column and the bottom bar |
| The **native version** is the release version | ✓ — `config -get sdl mapperfile` answers `mapper-1.0.0.17.map`. Until 1.0.0.12 the macro was a literal `1.0.0.1`, so every release answered `mapper-1.0.0.1.map` |
| A **save → load** round trip works with the mouse block | ✓ — the OPL chip comes back field for field, the four DOS handles with it, no payload-size mismatch |
| A state written by **1.0.0.10** is refused | ✓ — *"That save file is damaged"*, the version guard (§5.4) |
| A state written by **1.0.0.11** (version 8) is refused | ✓ — *"That save file is damaged"*; the version is checked before the payload size, so the older layout is never read |
| A **version-10** state round-trips | ✓ — the header of the file carries `WQSS` + `0a`, the OPL phase comes back unchanged (`phase=38780a16`) and the tone does not restart. (The `mode` in the log line is the VGA mode, not the state version) |
| The app is called **WhereQ-DOSBox** | ✓ — the launcher name, the notification and the system permission dialogs all read *WhereQ-DOSBox* |
| A **saves folder** is connected | ✓ — the Storage Access Framework picker; the app takes a persistent URI on the folder and writes into it |
| A slot is **mirrored** into that folder | ✓ — `slot1.gz`, 37 KB for a 16 MB snapshot, next to the game's `bundle.zip` |
| The app is **uninstalled** and installed again | ✓ — the local games and slots are gone; the durable copy is not |
| The games are **restored** from the folder | ✓ — *"Restore games? — This folder has 2 games that are not installed"*, and both come back with their names |
| The **save state** comes back with them | ✓ — loaded out of the mirror with the header version 10 and the OPL phase unchanged (`phase=38780a16`) |
| Two archives with the **same name** stay apart | ✓ — the second one installs as *whereq-opl-test-bundle (2)*, and the two keep separate durable folders and separate slots |

### 11.3 ▸ The back button, on the device

The back button is the one piece of the app that the **JVM cannot prove**:
from Android 13 on, the system does not deliver it to the activity as a key
event, it hands the gesture to the predictive-back dispatcher, and only a
device can show whether the app receives it at all. So there are two layers
of test:

| Test | Where it runs | What it proves |
|---|---|---|
| `BackActionTest` (7 cases) | the JVM, `:app:testDebugUnitTest` | the decision table: the launcher wins over the gamepad, the gamepad is left before the keyboard, the keyboard folds before the app leaves, and the four presses walk out in that order |
| `BackActionTest.theManifestDoesNotOptOutOfPredictiveBack` | the JVM | the manifest does not set `android:enableOnBackInvokedCallback="false"` — the opt-out restores the legacy path that SDL swallows |
| `BackHandlingTest` (2 cases) | the device, `:app:connectedDebugAndroidTest` | the **real** back button: with the launcher open it closes the launcher and the app stays alive; with nothing open it leaves the app |
| `LicensesActivityTest` (3 cases) | the device | the licence screen carries the GPL, the notices and the link to the source, and the two assets are in the installed app |
| `LauncherFooterTest` (3 cases) | the device | the foot of the launcher carries the link to the licences and to the privacy policy, and each of them asks the app for the right thing |
| `RemapOutlineTest` (5 cases) | the JVM | the outline that marks a remappable button contrasts with the chip it marks: the gold FIRE chip gets a dark outline, the dark chips get the gold one, and **the outline is never the colour of the chip** |
| `GameModeRemapOutlineTest` (2 cases) | the device | the **pixels** of the real gamepad: the outline the app draws on the FIRE button is the dark colour, and the outline on a dark button is the gold |
| `PrivacyPolicyTest` (3 cases) | the JVM | the privacy policy is in the repository, at `docs/PRIVACY.md`; the link the app hands to the browser names that exact file; and the README links to it |

The instrumented tests press the back button the way a user does — through the
system input pipeline (`input keyevent 4`) — and not by calling the activity,
because only the input pipeline reaches the back dispatcher.

**Measured on the emulator** (`emulator-5554`, API 37, x86_64), 1.0.0.16:

```
:app:testDebugUnitTest          7 tests, 0 failures
:app:connectedDebugAndroidTest   5 tests, 0 failures
```

**Measured on the emulator** (`emulator-5554`, API 37, x86_64), 1.0.0.17:

```
:app:testDebugUnitTest          15 tests, 0 failures
:app:connectedDebugAndroidTest  10 tests, 0 failures
```

### 11.4 ▸ The OPL test: the proof in two lines
`examples/opl-test-bundle/` is a small 16-bit DOS program
(`src/opltest.asm`) that programs **one sustained tone on channel 0** of the FM
chip and then waits for a key. It is a real guest program, not a unit test: it
goes through the BIOS, the DOS shell and the port I/O the way a game does.

The port writes one line per save and per load, and **the two lines have to be
identical**:

```
savestate: OPL chip saved:    ch0.op0 decay rate=0 phase=d96f49ac vol=00000000 ... lfo=000b228c
savestate: OPL chip restored: ch0.op0 decay rate=0 phase=d96f49ac vol=00000000 ... lfo=000b228c
```

Every field of that line is a field the register cache cannot carry:

| Field | What it shows |
|---|---|
| `ch0.op0` | **which** operator is keyed on — the log names every non-idle operator |
| `decay` | the envelope state |
| `rate=0` | the position inside the envelope |
| `phase=d96f49ac` | the operator's wave phase |
| `vol=00000000` | the current attenuation |
| `lfo=000b228c` | the LFO counter |

The two lines were produced **two minutes apart** in the run of 2026-11-17
(the machine ran on in between — the chip's LFO and phase kept moving) and the
numbers still match, field for field:

```
  saved    07:04:08   phase=d96f49ac   lfo=000b228c   active=36   opl3=0
  restored 07:06:04   phase=d96f49ac   lfo=000b228c   active=36   opl3=0
```

The module line and the machine fingerprint match as well:

```
  OPL module saved    exact=1 mode=2 regs=62/512 r20=31 r23=31 r40=3f ...
  OPL module restored exact=1 mode=2 regs=62/512 r20=31 r23=31 r40=3f ...
  saved    cs:0192/00001920 ip:000001b0 ... gdtck:811c9dc5 stckck:2143e05f
  loaded   cs:0192/00001920 ip:000001b0 ... gdtck:811c9dc5 stckck:2143e05f
```

and the DOS layer came back with its four handles (the two console devices,
the printer and the second console):

```
savestate: dos state: drive 2, code page 437, 4 open handle(s)
savestate: dos handle 0: device 2 (CON)
savestate: dos handle 1: device 2 (CON)
savestate: dos handle 2: device 4 (LPT1)
savestate: dos handle 3: device 2 (CON)
savestate: dos state restored: drive 2, code page 437, 4 handle(s)
```

That is the whole claim of §6.3.

▸ The log names **every** operator that is not idle, not just the first one of
the chip. The first operator of channel 0 stays in `release` from the chip's
own power-up clearing — `Chip::Setup()` writes `0xff` and then `0x00` to every
register, which keys every channel on and immediately off again — and a log
that only showed that would hide the real note.

### 11.5 The size probe

The struct sizes quoted in §6.2 were **measured**, not estimated. The probe is
a tiny program compiled against the *generated* Android `config.h`, so the
sizes are the ones the NDK build actually sees:

```sh
cd /home/whereq/git/whereq-dosbox
. toolchain/env.sh
g++ -std=c++17 -I build/android/x86_64/include -I include -I src /tmp/sz.cpp -o /tmp/sz
```

```
sizeof(DBOPL::Chip)     = 5176
sizeof(DBOPL::Operator) = 104
sizeof(DBOPL::Channel)  = 248
```

The `Chip` is `Channel chan[18]` plus the chip-wide counters, and 18 × 248 =
4464 — the rest is the LFO/noise counters, the rate tables and the mode
registers.

### 11.6 The contrast build

A claim that a fix works is only as good as the build that shows the old
behaviour. The exact snapshot was therefore **disabled in a scratch build**,
the *same* saved state was loaded, and the difference was read off the log:

![11.5 The contrast build](diagrams/tr34-11-5-the-contrast-build.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
 exact snapshot (this build)      register replay (the old path)
 ────────────────────────────     ──────────────────────────────
 ch0.op0 decay                    ch0.op0 attack
        phase=d96f49ac                   phase=80000000
        vol=00000000                     vol=000001ff
        lfo=0008774c                     lfo=00000000
```

</details>

| | register replay | exact snapshot |
|---|---|---|
| envelope state | `attack` — the note starts again | `decay` — where it was |
| wave phase | `80000000` — the start value | `d96f49ac` — the live phase |
| volume | `1ff` — silent until the attack gets through | `0` — full volume |
| LFO counter | `00000000` — never restored | `0008774c` — the saved value |

▸ The scratch patch was **reverted** afterwards and all three ABIs and the
release APK were rebuilt from the clean tree. The APK whose hash is quoted in
§11.2 is that clean build.

### 11.7 What was **not** verified

Stated explicitly, because a technical reference that hides its gaps is worse
than one that names them:

| Not verified | Why |
|---|---|
| A real commercial DOS game, end to end | the test bundles are the STARFALL demo and the OPL test; the Chinese KOEI titles are referenced in the project history but were not run for this milestone |
| The `arm64-v8a` and `armeabi-v7a` ABIs on a device | they were **built** and are in the APK, but the emulator is x86_64 |
| A save state across an **app update** | the version guard refuses it by design (`SS_VERSION`), and the behaviour was not exercised on a device |
| A save state across a **device reboot** | the slot file survives (it is in the app's data directory) and the code path is the same as the app-restart path, but it was not run on a device |
| The `frozen` guest CPU state | the snapshot restores the CPU and the RAM, but the "frozen" flag of DOSBox's own pause is not part of the snapshot |
| Timing accuracy of the restored PIT | the phase is re-based from the saved clock, but the drift over hours was not measured |

---

## 12. Known limitations and open items

### 12.1 Defects that are known and not fixed

These are **documented rather than fixed**, because the fix belongs to a
different milestone and each of them is currently harmless or is already
visible in the UI.

| # | What | Where | Effect |
|---|---|---|---|
| 1 | The `src/main/jniLibs` staging directory is vestigial | `android/app/src/main/` | the Gradle path stages the `.so` files from `build/android/<abi>/`; the old directory is only a leftover |
| 2 | The shipped asset sets `nosound=false` in the `[dosbox]` section | `android/app/src/main/assets/dosbox.conf` | DOSBox registers `nosound` in `[mixer]`, so the setting in `[dosbox]` is ignored and the mixer runs with its default. The line is harmless but does nothing |
| 3 | A bundle's own `dosbox.conf` is ignored | §8.11 | the app regenerates its configuration from its own asset on every boot; the launcher reports *"ships its own dosbox.conf"* instead of pretending to use it |
| 4 | `DosboxTyper.kt` is dead code | `android/app/src/main/java/com/whereq/dosbox/DosboxTyper.kt` | the real launch mechanism is the `[autoexec]` line in the generated configuration (§8.6). The typer is left in the tree but is not referenced |

Two defects that used to be listed here were fixed in **1.0.0.12**:

* The **native version** was a literal `1.0.0.1` in
  `android/cmake/config-android.h.in`, so the emulator reported *WhereQ-DOSBox
  version 1.0.0.1* in the DOS startup banner and in the window title whatever
  release was installed, the BIOS machine string said 1.0.0.1, and the mapper
  file was always `mapper-1.0.0.1.map`. It is now substituted from the
  `VERSION` file at the repo root — the same file the release script writes and
  the Gradle build reads.
* The **default ABI list** of `android/build-android.sh` (and of
  `android/build-sdl2.sh`) was two instead of three.

▸ **The baseline is the em-dosbox fork of DOSBox r4305**, not plain DOSBox
0.74. A fork-versus-plain diff is therefore **not available in this
repository** — the upstream core was taken verbatim, and the changes to `src/`
and `include/` are the list in §1.3, which also covers the Android layer the
import commit itself carried.

### 12.2 What the snapshot does not capture

| Not captured | Consequence |
|---|---|
| The BIOS's own mutable state | the BIOS runs from the ROM image, and the BIOS data area lives in guest RAM, so it *is* captured — but a patched BIOS is not |
| The **event queue** of the VGA draw pipeline | the rebuild drops the stale events and restarts the frame timer (§5.7) |
| The **PIC event queue** | the same: the pending events are dropped and the timing is re-based |
| The **mixer** state | the mixer's own buffers are not snapshotted; the guest's sound state is (the OPL chip, the speaker), the mixer's are not |
| The **directory listing caches** of the mounted drives | a bundle installed after the boot is invisible until a relaunch (§8.7) |
| The **EMS/XMS handle tables** and the **RTC** | a restore that lands in the Ultima VIII extender still ends in `Exit to error: RET from illegal descriptor type 13` about a second later — the guest diverges because host state it depends on is still missing. The same abort happens with a build from before the DOS-handles milestone, so it is **not** caused by the handles that *are* captured |

▸ The **mouse** left this list in 1.0.0.11: the INT 33h driver's state is now
in the snapshot — the cursor position and the game's event-callback
registration included (§5.5). **1.0.0.12 slimmed that block** to the stable
configuration only, because restoring the volatile half (the event queue and
the interrupt flags) wedged mouse-driven games on load, and then grew it again
to the whole configuration the guest cannot rebuild — the cursor shape and
hotspot included, so the visible cursor lines up with the click point after a
restore. The button state and the click counters are still *not* captured: a
restored game starts with the mouse released, and a click that was held down
across the save has to be pressed again.

### 12.3 The open items

| Item | Note |
|---|---|
| A **fork-versus-plain** diff | needs the plain 0.74 tree next to this one (§12.1) |
| The **bundle `dosbox.conf`** | using it would need a configuration that is not regenerated on every boot — a different design, not a patch |
| **More ABIs on a device** | the `arm64` and `armeabi-v7a` builds are untested on real hardware |
| A **frozen** guest | `SAVESTATE` does not restore the "frozen" flag; a frozen machine that is saved and loaded continues running |
| The **arrows are on the left pad** | the console convention puts them under the left thumb; that reverses the right-hand arrow cluster of 1.0.0.8. Swapping the two thumb blocks is a small change if the arrows are preferred on the right (§9.8) |
| The **letter keys are small on an 18:9 screen** | 23.6 dp wide against 32.8 dp on a 20:9 one (§9.8). The emulator says they hit the right characters; a fingertip says something else. More columns per half, or a smaller picture, would be the way out |
| **Nothing has been run on a real device** | every measurement in this document — the dp sizes, the OPL save/load, the layouts — comes from an emulator at 420 dpi |
| **ARM64 throughput numbers** | the desktop baseline measures 127 M instr/s on x86_64 (§2.12); the real-device ARM64 figure is still missing |
| The **feel of the Game Mode pads** | the D-pad's dead zone, the trackball's speed, the button sizes and the FIRE turbo rate are all measured on an emulator, never on a thumb (§10) |
| The **reverse Konami** on a finger | the exit code is verified end to end on the emulator (§10.8), but the D-pad's octant accuracy under a real thumb is not |

### 12.4 The house rules that keep coming back

The four rules that a reviewer will find most often in this codebase:

1. **A host pointer is never persisted.** Read the content state from the file,
   keep the live pointer (§5.6, §6.4).
2. **A block that is in the file is always consumed.** Skipping it shifts
   everything behind it (§6.6).
3. **Derived state is rebuilt, not restored.** The snapshot is the machine;
   everything that follows from it is derived (§5.7).
4. **A request is serviced at a safe point, or not at all.** The three gates
   of §5.3 are not an optimisation; they are the difference between a snapshot
   and a crash.

---

## 13. The licence and the notices

WhereQ-DOSBox embeds the DOSBox core, which is **GPLv2**. GPLv2 section 2 asks
for two things that have to travel with the *program*, not only with the
source tree:

1. the licence itself, and the copyright notices, and
2. a way for every recipient to get the complete corresponding source.

The first is a file that has to be **in** the app; the second is a thing that
has to be **reachable from** the app.

### 13.1 ▸ The licence screen

`LicensesActivity` is that screen. It is reached from the **footer of the
bundle launcher** (§8), next to the version and the privacy-policy link, and
it carries:

| | |
|---|---|
| **What the app is** | one paragraph: the core is the DOSBox r4305 core, imported with its notices intact, and the porting work on top is under the same licence |
| **The licence, named** | the GPL v2 grant, in the wording the FSF uses |
| **The complete source** | the link to the repository that holds this build |
| **The licence text** | the whole of `COPYING`, read from the assets and reflowed to the screen width |
| **The notices** | the whole of `NOTICE`: the DOSBox and Em-DOSBox attribution, the SDL2 zlib notice, and the trademark note |

The screen is built in code rather than from a layout file, in the same style
as the rest of the app: a `ScrollView` with a `LinearLayout` of headings
(accent colour) and paragraphs. The licence text is an 80-column file, which
is unreadable on a phone, so the **paragraphs** are reflowed — the words are
unchanged, only the line breaks are. The verbatim file is still what travels
in the APK.

### 13.2 ▸ The two assets

| Asset | What it is | Where it comes from |
|---|---|---|
| `assets/COPYING` | the GNU General Public License, version 2, verbatim | the `COPYING` file at the repository root — the same file that ships with the source |
| `assets/NOTICE` | the attribution notices: DOSBox, Em-DOSBox, SDL2, the trademark note | written for this port, describing exactly which upstream trees were imported and what was changed |

`NOTICE` is the honest record of the import: the DOSBox core in `src/`,
`include/`, `visualc_net/` and `scripts/` is DOSBox r4305, imported verbatim
from the Em-DOSBox web build; everything outside it is the WhereQ porting
work, distributed under the same licence because it is combined with the core
into a single work.

### 13.3 ▸ How it is verified

The licence is not a decoration — it is checked:

* the assets are in the APK (`unzip -l app-release.apk` lists `assets/COPYING`
  and `assets/NOTICE`);
* an instrumented test (`LicensesActivityTest`) opens the screen on a device
  and asserts that it carries the licence, the notices and the link to the
  source;
* a second test reads the two assets from the installed app and asserts that
  `NOTICE` names this port and **not** the sibling one.

### 13.4 ▸ The privacy policy

The licence says nothing about the user's data, so there is a second document:
the **privacy policy**, in [`docs/PRIVACY.md`](PRIVACY.md).

| | |
|---|---|
| **Where it lives** | in this repository, next to the code and the licences |
| **Why there** | WhereQ-DOSBox is free and open-source software, published from this repository. A private product has to publish its policy on a site that anyone can open; an open-source project can keep the authoritative copy in the repository, where its whole history is public. |
| **Where the app points** | the footer of the launcher opens `docs/PRIVACY.md` on the repository, in the browser |
| **What it says** | the app collects nothing and has no `INTERNET` permission; the tables say what is stored on the device, which permissions are asked for and why, and that the durable saves folder is the user's own |

The app has **no `INTERNET` permission** (see the manifest), so it cannot fetch
the page itself. It hands the URL to the browser — the normal way to do this,
and the reason the app itself carries no network code.

### 13.5 ▸ The man page

`docs/dosbox.1` is the **DOSBox manual page** — the roff source that
`man dosbox` renders — and it came in with the import, unmodified. It
documents the *command line* of the desktop `dosbox` binary: `-fullscreen`,
`-startmapper`, `-conf`, `-machine`, `-c`, `-exit`, `-printconf`, `-editconf`,
`-resetmapper`, the internal commands and the special keys.

| | |
|---|---|
| **Where it comes from** | DOSBox r4305's own `docs/` directory — the same `man_MANS = dosbox.1` line is in upstream's `docs/Makefile.am` |
| **What the build does with it** | `man_MANS` makes the automake build install it as a real man page: the native desktop build puts it at `$(mandir)/man1/dosbox.1` |
| **What the app does with it** | nothing — the APK is a Kotlin launcher over an SDLActivity and has no command line; no Android build step reads the file |
| **The one local change** | the `SEE ALSO` section pointed at `/usr/share/doc/dosbox`, a Debian path that this build never creates, so the section was removed and the date in the `.TH` line adjusted, as the page itself asks |

The change is marked inside the file (a roff comment in the header records
what was changed and that upstream's last revision was Sep 08, 2015) and in
`NOTICE`, because GPLv2 requires an altered file to be plainly marked as
altered — the rest of the text is upstream's, word for word.

---

## Appendix A — the OPL register map as implemented

The FM chip this build uses is the **DBOPL** handler (`src/hardware/dbopl.cpp`),
a port of the MAME OPL core. It is what DOSBox picks for `sbtype=3` / the
auto-detected OPL3; the log of a save shows `mode=2` = `MODE_OPL3`.

### A.1 ▸ The two ports

![A.1 ▸ The two ports](diagrams/tr35-a-1-the-two-ports.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  388h  ──►  the ADDRESS port: the value written here is the register number
  389h  ──►  the DATA port:   the value written here goes into the latched register
```

</details>

`Module::PortWrite()` (`src/hardware/adlib.cpp:555`) splits on `port & 1`:

```c
if ( port&1 ) {            /* 389h — the data port */
    ...
    handler->WriteReg( reg.normal, val );   /* the value goes to the latched register */
    CacheWrite( reg.normal, val );
} else {                   /* 388h — the address port */
    reg.normal = handler->WriteAddr( port, val ) & 0x1ff;
}
```

▸ **This is the trap the OPL test program fell into.** A value written to
`388h` only *selects* the register that the **next** write to `389h` goes to. The
first version of the test wrote both bytes to `388h`, so it programmed nothing
at all and the chip looked idle — every operator sat in `release` with its wave
phase at the start value, because `Chip::Setup()` clears the chip by writing
`0xff` and then `0x00` to every register, which keys every channel on and
immediately off again.

### A.2 The address latch and the second bank

`Chip::WriteAddr()` (`src/hardware/dbopl.cpp:1182`):

```c
switch ( port & 3 ) {
case 0:  return val;                                   /* the address port */
case 2:  if ( opl3Active || (val == 0x05) )             /* 389h */
             return 0x100 | val;                       /*   the second bank */
         else return val;
}
return 0;
```

The bit `0x100` is the **second bank** of the OPL3 — the channels 9..17. It is
selected automatically when the chip is in OPL3 mode, or explicitly by writing
`0x05` to the address port (which selects the second bank even on an OPL2).

### A.3 The register map

| Address | What it holds | Granularity |
|---|---|---|
| `00` | the test register / waveform enable (`0x20` enables the waveform select) | chip |
| `01` | the test register (`0x20` = the waveform select) | chip |
| `02` | timer 1 | chip |
| `03` | timer 2 | chip |
| `04` | `reg04` — unused in this core | chip |
| `05` | OPL3 enable — `0x01` turns the OPL3 mode on | chip |
| `08` | `reg08` — CSM / NTS | chip |
| `20`-`35` | AM / VIB / EG-TYPE / KSR / MULT | **operator** |
| `40`-`55` | KSL / total level | **operator** |
| `60`-`75` | attack rate / decay rate | **operator** |
| `80`-`95` | sustain level / release rate | **operator** |
| `A0`-`A8` | the frequency number, low 8 bits | **channel** |
| `B0`-`B8` | key-on / block / the frequency number, high bits | **channel** |
| `C0`-`C8` | feedback / connection / output (the `0x30` bits) | **channel** |
| `D0`-`D8` | unused | — |
| `E0`-`F5` | the waveform select | **operator** |
| `BD` | the rhythm / miscellaneous control register | chip |

The operator map follows the canonical YM3812 layout — the *same* addresses
cover different operators depending on the channel:

![A.3 The register map](diagrams/tr36-a-3-the-register-map.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
        $20  $21  $22  $23  $24  $25  $26  $27  $28  $29  $2A  $2B  $2C  $2D
        ───  ───  ───  ───  ───  ───  ───  ───  ───  ───  ───  ───  ───  ───
        C0o0 C1o0 C2o0 C0o1 C1o1 C2o1  —   —   C3o0 C4o0 C5o0 C3o1 C4o1 C5o1
```

</details>

and the same for `40`, `60`, `80` (the operator registers) and for `E0`-`F5`
(the waveform select).

### A.4 ▸ The decode arithmetic

The macros that implement the map (`src/hardware/dbopl.cpp:1095`):

```c
#define REGOP( _FUNC_ )                                                        \
    index = ( ( reg >> 3) & 0x20 ) | ( reg & 0x1f );                          \
    if ( OpOffsetTable[ index ] ) {                                           \
        Operator* regOp = (Operator*)( ((char *)this ) + OpOffsetTable[index]-1 ); \
        regOp->_FUNC_( this, val );                                           \
    }

#define REGCHAN( _FUNC_ )                                                     \
    index = ( ( reg >> 4) & 0x10 ) | ( reg & 0xf );                           \
    if ( ChanOffsetTable[ index ] ) {                                         \
        Channel* regChan = (Channel*)( ((char *)this ) + ChanOffsetTable[index]-1 ); \
        regChan->_FUNC_( this, val );                                         \
    }
```

The operator index:

![A.4 ▸ The decode arithmetic](diagrams/tr37-a-4-the-decode-arithmetic.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  index = (reg & 0x1f) | ((reg >> 3) & 0x20)      the 0x20 bit = the second bank
     │
     ├─ index % 8 < 6      → a real operator
     │     chNum = (index / 8) * 3 + (index % 8) % 3
     │     opNum = (index % 8) / 3              0 = the first operator
     │                                          1 = the second operator
     └─ index % 8 >= 6     → unused (0x26, 0x27 and their counterparts)
```

</details>

The channel index:

![A.4 ▸ The decode arithmetic](diagrams/tr38-a-4-the-decode-arithmetic.svg)

<details>
<summary>The same diagram as text (the SVG above is the aligned version)</summary>

```
  index = (reg & 0x0f) | ((reg >> 4) & 0x10)      the 0x10 bit = the second bank
     │
     ├─ index & 0x0f >= 9  → unused
     ├─ index & 0x0f < 6   → the slot is remapped:
     │        slot = (slot % 3) * 2 + (slot / 3)
     │        0→0  1→2  2→4  3→1  4→3  5→5
     └─ index >= 16       → the second bank adds 9 to the physical channel
```

</details>

The offset tables are built once, in `Chip::Setup()`
(`src/hardware/dbopl.cpp:1444`), and they point into the flat `chan[18]`
array. The `+1` in `OpOffsetTable[index]-1` is the sentinel: **0 means "this
register addresses nothing"**.

### A.5 The register cache

`Module::cache` is a `Bit8u[512]` (`src/hardware/adlib.h:118`):

* `cache[0x00..0xFF]` — the first bank,
* `cache[0x100..0x1FF]` — the second bank (`reg | 0x100`).

Every write the guest makes is mirrored into it (`CacheWrite()`), which is what
makes the register-replay fallback of §6.5 possible: the cache is the guest's
*intent*, and replaying it rebuilds the chip's configuration.

The cache is **not** the state: it records *what was programmed*, not *where the
envelope is*. §6 is about the difference.

### A.6 The control ports (OPL3 Gold)

Only in `MODE_OPL3GOLD`:

| Port | Write | Read |
|---|---|---|
| `38Ah` | `0xFF` activates the control chip, `0xFE` deactivates it, anything else selects a control register | `0` — "not busy" |
| `38Bh` | writes the selected control register | reads it |

The control registers are `0x00` (left FM volume), `0x01` (right FM volume),
`0x0A` (right FM volume, the duplicate) and `0x15` (the audio relocation, which
returns `0x388 >> 3` — the *"Cryo installer detection"* hack: a few 1990s
installers probe for a Sound Blaster by reading that register, and DOSBox
answers with the value they expect).

### A.7 The status port

Reading `388h` returns the chip's status register: the timer flags and the
"buffer empty" bit. In OPL2 mode the low bits are forced to `0x6`
(`chip[0].Read() | 0x6`), because an OPL2 status register always reads with
those bits set.

---

## Appendix B — the state file block table

The file is a fixed-order sequence of raw blocks. There is **no framing** — the
order *is* the format, and the total size is computed by
`expected_payload_size()` (`src/gui/savestate.cpp:147`).

### B.1 The header (24 bytes)

| Offset | Type | Field | Value in this build |
|---|---|---|---|
| 0 | `char[4]` | `magic` | `"WQSS"` |
| 4 | `uint32_t` | `version` | `7` |
| 8 | `uint32_t` | `machine` | `::machine` — `svga_s3` with the shipped config |
| 12 | `uint32_t` | `mem_pages` | `MEM_TotalPages()` — 4096 with `memsize=16` |
| 16 | `uint32_t` | `vmemsize` | `vga.vmemsize` |
| 20 | `uint32_t` | `svga_card` | `::svgaCard` |

The last four fields are the **configuration guard**: a state is only loadable
into a machine with the same memory size, the same video memory size and the
same SVGA card. A mismatch is `SAVESTATE_ERR_MISMATCH`.

### B.2 The payload

| # | Block | Size | Written by | § |
|---|---|---|---|---|
| 1 | `cpu_regs` | `sizeof(cpu_regs)` | `src/gui/savestate.cpp:259` | 5.5 |
| 2 | `cpu` (the `CPUBlock`) | `sizeof(cpu)` | `:260` | 5.5 |
| 3 | `Segs` | `sizeof(Segs)` | `:261` | 5.5 |
| 4 | `paging.cr3` | 4 | `:263` | 5.5 |
| 5 | `paging.cr2` | 4 | `:264` | 5.5 |
| 6 | `paging.base.page` | `sizeof(...)` | `:265` | 5.5 |
| 7 | `paging.base.addr` | `sizeof(...)` | `:266` | 5.5 |
| 8 | `paging.enabled` | `sizeof(...)` | `:267` | 5.5 |
| 9 | `paging.firstmb` | `sizeof(paging.firstmb)` | `:272` | 5.5 |
| 10 | guest RAM | `mem_pages * 4096` | `:274` | 5.5 |
| 11 | `vga` | `sizeof(vga)` | `:276` | 5.5, 5.6 |
| 12 | `vga.mem.linear` | `vmemsize` | `:278` | 5.5 |
| 13 | `vga.fastmem` | `vmemsize * 2` | `:282` | 5.5 |
| 14 | the clock | `sizeof(double)` | `:285` | 5.5 |
| 15 | PIC | `PIC_StateSize()` | `:287` | 5.5 |
| 16 | TIMER | `TIMER_StateSize()` | `:288` | 5.5 |
| 17 | `fpu` | `sizeof(fpu)` | `:290` | 5.5 |
| 18 | SPKR (the PC speaker) | `SPKR_StateSize()` | `:291` | 6.7 |
| 19 | OPL — the module | `sizeof(mode)+sizeof(reg)+sizeof(ctrl)+512` | `adlib.cpp:743` | 6.5 |
| 20 | OPL — the chip | `sizeof(DBOPL::Chip)` = 5176 | `dbopl.cpp:1594` | 6.3 |
| 21 | DOS | `DOS_StateSize()` | `:294` | 7 |

The OPL module block (19) is the register cache plus the addressing latch and
the run-time mode; the chip block (20) is the exact snapshot, **written
verbatim** — `fwrite(&chip, sizeof(chip), 1, f)`.

▸ **The chip block is always present in the file**, even when the handler
cannot restore itself. The load path therefore has to *consume* it in every
case — §6.6.

### B.3 The version history

| `SS_VERSION` | Added | Slots written by |
|---|---|---|
| 1 | the CPU, the RAM, the VGA | — |
| 2 | the clock, the PIC, the TIMER | — |
| 3 | the FPU, the PC speaker | — |
| 4 | `paging.firstmb` | — |
| 5 | the open DOS file handles | the 1.0.0.9 release |
| 6 | the OPL register cache | — |
| **7** | **the exact OPL chip snapshot** | — |
| 8 | the whole INT 33h mouse driver state | the 1.0.0.11 release |
| 9 | the mouse block slimmed to callback + range + position | — |
| **10** | **the mouse block grown to the whole driver configuration** — the cursor shape and hotspot as well | this build |

A slot written by an older build is **refused** (`SAVESTATE_ERR_FORMAT`) rather
than misread. ▸ The 1.0.0.9 slots are version 5, the 1.0.0.10 slots are
version 7 and the 1.0.0.11 slots are version 8 — none of them can be loaded by
this build, and neither can the version-9 slots of the earlier 1.0.0.12 cuts.
The version is checked **before** the payload size, so an old file is rejected
whole rather than half-read.

### B.4 The DOS block

`DOS_StateSize()` (`src/dos/dos_state.cpp`) covers:

| What | Size |
|---|---|
| the current drive | 4 |
| the code page | 4 |
| the number of open handles | 4 |
| the handle table | `DOSSTATE_HANDLE_SIZE` × the handle count |
| the current directories | one entry per drive |

and a handle is:

```c
#define DOSSTATE_HANDLE_SIZE (9 * 4 + DOS_PATHLENGTH)
```

— nine 32-bit fields (the device, the drive, the position, the reference
count, the attributes, …) plus the path of the file. §7 explains what the
fields are and why the *file* handles need a whole subsystem.

---

## Appendix C — the log-line reference

Every line the port can print, and where it comes from. The tag is
`whereq-dosbox` for the native layer (`android/jni/dosbox_log.h`) and
`whereq-dosbox` for the Kotlin layer.

### C.1 The save-state machine (`src/gui/savestate.cpp`)

| Line | When |
|---|---|
| `savestate: waiting for the guest to start` | gate 1 blocked the request (logged **once** per request) |
| `savestate: waiting for the CPU to leave the interrupt stub (cs:f000 ip:00001234)` | gate 3 blocked the request (logged **once** per request) |
| `savestate: cannot open %s for writing` | the file could not be created |
| `savestate: write failed, removing %s` | a block could not be written; the partial file is removed |
| `savestate: saved %s (cs:%04x ip:%08x mode %d)` | the save succeeded |
| `savestate: cannot open %s for reading` | the file does not exist or is unreadable |
| `savestate: config mismatch (state m=%u pages=%u vmem=%u card=%u)` | the header guard rejected the state |
| `savestate: payload size mismatch, refusing load` | the file is truncated or has the wrong size |
| `savestate: load of %s failed (%d) - machine may be unstable` | a block failed to load |
| `savestate: loaded %s (cs:%04x ip:%08x mode %d)` | the load succeeded |
| `savestate: %s cs:%04x/%08x ip:%08x sp:%08x ss:%04x/%08x ds:%04x es:%04x fs:%04x gs:%04x flags:%08x cpl:%u pmode:%d cr0:%08x cr3:%08x paging:%d gdt:%08x/%08x idt:%08x/%08x ldt:%04x gdtck:%08x stckck:%08x` | the fingerprint — printed after every save **and** every load |

The fingerprint line is the one that proves a restore: the `saved` and the
`loaded` lines have to be identical.

### C.2 The OPL chip (`src/hardware/dbopl.cpp`)

| Line | When |
|---|---|
| `savestate: OPL chip saved: <operators>\| active=%u lfo=%08x opl3=%d` | the exact snapshot was written |
| `savestate: OPL chip restored: <operators>\| active=%u lfo=%08x opl3=%d` | the exact snapshot was applied |
| `savestate: OPL chip replayed: <operators>\| active=%u lfo=%08x opl3=%d` | the handler could not restore itself and the registers were replayed |

The operator list holds every operator that is **not** idle:

```
ch%d.op%d %s rate=%u phase=%08x vol=%08x
```

with the state one of `off`, `release`, `sustain`, `decay`, `attack`.

### C.3 The OPL module (`src/hardware/adlib.cpp`)

| Line | When |
|---|---|
| `savestate: OPL module saved: exact=%d mode=%d regs=%u/%u r20=%02x r23=%02x r40=%02x r43=%02x r60=%02x r63=%02x r80=%02x r83=%02x rA0=%02x rB0=%02x` | the register cache was written |
| `savestate: OPL module restored: …` | the register cache was read |

`regs=%u/%u` is the number of non-zero cache entries out of 512 — it says
whether the guest programmed the chip at all. The listed registers are what it
programmed: `r20`/`r23` are the two operators of channel 0, `rB0` the key-on
register.

### C.4 The DOS layer (`src/dos/dos_state.cpp`)

| Line | When |
|---|---|
| `savestate: dos state: drive %u, code page %u, %u open handle(s)` | the DOS block was written |
| `savestate: device handle %u is gone (device %u)` | a device handle could not be restored — it is skipped |
| `savestate: dos handle %u: device %u (%s)` | a handle was restored |
| `savestate: file handle %u has no drive (%u)` | a file handle has no drive — it is skipped |
| `savestate: file handle %u has no name - skipped` | a file handle has no name — it is skipped |
| `savestate: could not reopen %s on drive %u` | the file could not be re-opened — the handle is skipped |
| `savestate: dos handle %u: %s (drive %u, pos %u, refs %u)` | a file handle was restored |
| `savestate: dos state restored: drive %u, code page %u, %u handle(s)` | the DOS block was applied |

▸ The "skipped" cases are **not** errors: a game that closed a file between the
save and the load simply loses that handle. The count in the last line is what
was actually restored.

### C.5 The JNI layer (`android/jni/dosbox_android.cpp`)

| Line | When |
|---|---|
| `could not chdir to %s` | the app's files directory could not be entered |
| `environment prepared: HOME=%s` | the sandbox was set up |
| `unknown key name "%s"` | the keyboard asked for a key the table does not have |
| `could not push key event: %s` | `SDL_PushEvent()` failed |

### C.6 The Kotlin layer (`android/app/src/main/java/com/whereq/dosbox/`)

| Line | When |
|---|---|
| `prepared environment, config dir = …` | the boot finished the setup |
| `installed <name> (<entry>)` | a bundle was installed |
| `could not install <name>` | the install failed |
| `skipping unsafe entry <name>` | a zip entry was rejected (§8.3) |
| `could not read the game manifest` | the manifest is corrupt |
| `could not write the game manifest` | the manifest could not be written |
| `launching <name> (<folder>) via relaunch` | "Play" |
| `exiting to a clean prompt via relaunch` | "Exit to prompt" |
| `resume <name> from slot <n>` | "Resume" |
| `auto-restore of <folder> slot <n> done` | the slot was restored after the boot |
| `auto-restore: <path> is gone - starting the game instead` | the slot vanished |
| `auto-restore of <folder> slot <n> failed (<rc>) - starting the game instead` | the restore failed |
| `wrote <path> (autostart <folder>) (core <core>)` | the configuration was regenerated |
| `could not write dosbox.conf` | the configuration could not be written |
| `ignoring unknown key <name>` | the keyboard asked for an unknown key |
| `could not push <key> down` / `up` | the key could not be pushed into SDL |
| `unknown key <name>` | the **gamepad** asked for an unknown key (§10.7) |
| `a command is already being typed` | the (dead) typer was busy |
| `cannot type '<c>'` | the (dead) typer could not type a character |

### C.7 The DOSBox layer

Everything DOSBox itself prints — the banner, the autoexec's output, the
`LOG_MSG` diagnostics of the upstream code — arrives in logcat under the same
tag, because the JNI layer pipes DOSBox's `stdout`/`stderr` into logcat
(`android/jni/dosbox_android.cpp`, the log pump described in §1.2). That is why
a DOS program's own output is visible in the same stream as the port's
diagnostics.

---

## Appendix D — the upstream behaviours that will bite you

A short list of DOSBox behaviours that cost real debugging time on this port.
Each one is a *silent* failure — no error, just something that does not work.

| Item | Fact | Consequence |
|---|---|---|
| `core` / `cycles` config keys | live in the **`[cpu]`** section, not in `[dosbox]` | putting them in `[dosbox]` is silently ignored → the default 3000 cycles/ms throttle stays on → the benchmark measures the throttle, not the core (§4.4) |
| `mount c <dir>` | does **not** switch the current drive | DOS file names must carry an explicit drive letter (`C:\OUT.TXT`); without one they land on the unmounted A: drive and the creation fails silently |
| `int 21h AH=09` | the string must be `$`-terminated | a missing `$` makes DOSBox scan memory until it finds one → the emulator appears to hang with no CPU usage |
| the cycle limiter | `TIMER_AddTick()` (`src/hardware/pic.cpp:539`) gives the CPU `CPU_CycleMax` cycles and advances the virtual clock by one emulated millisecond | the emulated clock advances **per `CPU_CycleMax` instructions**, not per host millisecond |
| the default budget | `CPU_CycleMax = 3000` (`src/cpu/cpu.cpp:53`) | real-time DOS needs **3 000 000 instructions/s** (§2.12) |
| `execvp` / `execlp` | `src/gui/sdlmain.cpp:2839, 2863, 2870, 2902` — the `-host`, `-editconf` and restart paths | these re-exec the binary and must be compiled out on Android; the CMake project does not link them |
| `C_DIRECTSERIAL` | `configure.ac` defines `LINUX` for `*-*-linux*`, which matches `aarch64-linux-android` | the termios/ioctl serial support has to be disabled **explicitly** — the autoconf build would enable it on Android (§2.6) |
| `C_DYNREC` | `configure.ac:480` enables it whenever `c_targetcpu == "arm"` (which includes aarch64 and armv7l) | it must be **off** on Android: it needs a GCC plugin (not buildable with NDK clang) and Android 10+ forbids executing writable memory (§2.7) |
| the drive listing cache | a local drive caches its directory listing at mount time and only re-scans a floppy on `FindFirst` (`src/dos/localDrive.cpp:184`) | a bundle installed after the boot is invisible to a program that enumerates the drive — which is why a launch is a **relaunch** (§8.7) |
| the interrupt stubs | the host handler of an "extra callback" (`FE 38`) runs **inside** that instruction, and the `IRET` behind it pops the shadow registers | a restore that lands between the two replaces the stack the `IRET` pops from (§5.3, gate 3) |
| `hlt.old_decoder` | a **function pointer** into the CPU core | it is meaningless in another process and must not be restored from a file (§5.5, §5.6) |

---

## Appendix E — the screenshot gallery

Every picture the port produced, with what it shows. All of them were taken on
the **x86_64 emulator (API 37)** running a release APK — that is a limit of the
evidence, not of the app (§11.6).

| Picture | What it shows |
|---|---|
| [`android-dos-prompt.png`](screenshots/android-dos-prompt.png) | the `C:\>` prompt after the boot |
| [`android-dos-prompt-dir.png`](screenshots/android-dos-prompt-dir.png) | a `DIR` listing of the mounted C: drive |
| [`android-dos-prompt-ver.png`](screenshots/android-dos-prompt-ver.png) | the version banner |
| [`android-n3-texture-boot.png`](screenshots/android-n3-texture-boot.png) | the first frame after the boot, through the texture renderer |
| [`android-n3-texture-letterbox.png`](screenshots/android-n3-texture-letterbox.png) | the letterboxed picture in the landscape window |
| [`android-n4-toolbar.png`](screenshots/android-n4-toolbar.png) | the folded keyboard toolbar |
| [`android-n4-virtual-keyboard.png`](screenshots/android-n4-virtual-keyboard.png) | the on-screen keyboard with the `ver` output |
| [`android-n4-virtual-keyboard-panel.png`](screenshots/android-n4-virtual-keyboard-panel.png) | the fold-up full QWERTY panel |
| [`android-n6-game-launcher.png`](screenshots/android-n6-game-launcher.png) | the bundle launcher with the installed games |
| [`android-n6-keyboard-layout.png`](screenshots/android-n6-keyboard-layout.png) | the keyboard layout of that build |
| [`android-n6-toolbar.png`](screenshots/android-n6-toolbar.png) | the toolbar with the launcher button |
| [`android-n6-starfall-title.png`](screenshots/android-n6-starfall-title.png) | STARFALL's title screen |
| [`android-n6-starfall-playing.png`](screenshots/android-n6-starfall-playing.png) | STARFALL running |
| [`android-n6-starfall-gameover.png`](screenshots/android-n6-starfall-gameover.png) | the game-over screen |
| [`android-n6-f1-running.png`](screenshots/android-n6-f1-running.png) | F1 Formula One |
| [`android-n6-ultima8-running.png`](screenshots/android-n6-ultima8-running.png) | Ultima VIII — the extender whose abort is §12.2 |
| [`android-n7-pads-letters.png`](screenshots/android-n7-pads-letters.png) | the console pads, **letter layer**, on a 22:9 phone |
| [`android-n7-pads-symbols.png`](screenshots/android-n7-pads-symbols.png) | the console pads, **symbol layer** (`#+=`), 20:9 |
| [`android-n7-pads-functions.png`](screenshots/android-n7-pads-functions.png) | the console pads, **function layer** (`Fn`), 20:9 |
| [`android-n7-pads-18x9-letters.png`](screenshots/android-n7-pads-18x9-letters.png) | the same, on an **18:9** screen — the 23.6 dp keys of §9.8 |
| [`android-n7-pads-18x9-symbols.png`](screenshots/android-n7-pads-18x9-symbols.png) | the symbol layer on 18:9 |
| [`android-n7-pads-18x9-functions.png`](screenshots/android-n7-pads-18x9-functions.png) | the function layer on 18:9 |
| [`android-n7-keyboard-pads-design.png`](screenshots/android-n7-keyboard-pads-design.png) | the to-scale **design drawing** of the letter layer |
| [`android-n7-keyboard-pads-design-symbols.png`](screenshots/android-n7-keyboard-pads-design-symbols.png) | the design drawing of the symbol layer |
| [`android-n7-keyboard-pads-design-fn.png`](screenshots/android-n7-keyboard-pads-design-fn.png) | the design drawing of the function layer |
| [`android-n8-22-keyboard-symbols.png`](screenshots/android-n8-22-keyboard-symbols.png) | the console pads, **symbol layer** (`#+=`), on the release build |
| [`android-n8-23-keyboard-functions.png`](screenshots/android-n8-23-keyboard-functions.png) | the console pads, **function layer** (`Fn`), on the release build |
| [`android-n8-24-game-mode-hybrid.png`](screenshots/android-n8-24-game-mode-hybrid.png) | Game Mode, **Hybrid** profile — the D-pad and the trackball |
| [`android-n8-25-game-mode-action.png`](screenshots/android-n8-25-game-mode-action.png) | Game Mode, **Action** profile — FIRE, JUMP, USE and ENT |
| [`android-n8-26-game-mode-point.png`](screenshots/android-n8-26-game-mode-point.png) | Game Mode, **Point & click** profile — the big mouse buttons |
| [`android-n8-27-game-mode-profile-picker.png`](screenshots/android-n8-27-game-mode-profile-picker.png) | the **Game profile** picker, with *Remap buttons…* |
| [`android-n8-28-game-mode-remap.png`](screenshots/android-n8-28-game-mode-remap.png) | remap mode — the buttons you can change, outlined in gold |
| [`android-n8-29-keyboard-18x9-letters.png`](screenshots/android-n8-29-keyboard-18x9-letters.png) | the letter layer on an **18:9** display, captured on the release build |
| [`android-n8-29-keyboard-18x9-symbols.png`](screenshots/android-n8-29-keyboard-18x9-symbols.png) | the symbol layer on 18:9 |
| [`android-n8-29-keyboard-18x9-functions.png`](screenshots/android-n8-29-keyboard-18x9-functions.png) | the function layer on 18:9 |
| [`android-release-apk.png`](screenshots/android-release-apk.png) | the release APK in the file manager |
| [`android-release-install-boot.png`](screenshots/android-release-install-boot.png) | the release APK booting |
| [`n5-gradle-release.png`](screenshots/n5-gradle-release.png) | the Gradle release build |
| [`n5-gradle-release-ver.png`](screenshots/n5-gradle-release-ver.png) | the Gradle release build's version banner |
