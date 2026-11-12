# whereq-dosbox — Native Linux build & CPU-core baseline notes

Project layout (`/home/whereq/git/whereq-dosbox`, restructured 2026-11-10):

```
src/ include/ ...        upstream DOSBox tree (repo root, full em-dosbox history)
android/                 CMake/NDK project -> libdosbox.so
native/build-native.sh   desktop Linux baseline build
bench/                   CPU-core throughput probe (hand-written 16-bit DOS .COM)
third_party/SDL2-2.30.0/ upstream SDL2 tarball (NDK build of SDL2)
toolchain/               rootless dev toolchain (env.sh + local sysroot)
porting/                 this file + ANDROID-PORT.md
build/                   gitignored build outputs
```

## 1. Rootless toolchain (no sudo required)

`sudo` needs a password in this WSL box, so every dev dependency was installed by
downloading the Ubuntu `.deb` files and extracting them into a local sysroot:

```sh
cd toolchain/pkgs
apt-get download m4 autoconf automake pkg-config libsdl2-dev libsdl2-2.0-0 \
                   libncurses-dev libglu1-mesa-dev nasm strace
for d in *.deb; do dpkg-deb -x "$d" ../sysroot; done
```

`toolchain/env.sh` exports `PATH`, `CPPFLAGS`, `LDFLAGS`, `LD_LIBRARY_PATH`,
`PKG_CONFIG_PATH`, `ACLOCAL_PATH`, `AUTOMAKE_LIBDIR` for that sysroot.

Three fixes were needed to make the extracted autotools work:

1. Debian ships `automake`/`aclocal` only under versioned names → added
   `automake`/`aclocal` symlinks.
2. The extracted perl/shell wrappers hardcode `/usr/share/automake-1.16`,
   `/usr/share/autoconf` and `/usr/bin/autom4te` → patched in place with `sed` to
   point at the sysroot.
3. `libsdl2-dev` is a multiarch package: `SDL2/_real_SDL_config.h` lives in
   `usr/include/x86_64-linux-gnu/SDL2/`, so `-I$SYSROOT/usr/include/x86_64-linux-gnu`
   is required in `CPPFLAGS`, otherwise `<SDL2/_real_SDL_config.h>` is not found.

Also needed: a `python` → `python3` symlink (`src/Makefile.am` invokes `python`
for `version.py`).

## 2. Native build (Android-equivalent feature set)

```sh
./build-native.sh          # autogen.sh + configure + make + make install
```

configure flags:

```
--disable-dynamic-core   # no x86 dyn core, no GCC-plugin dynrec (Android has neither)
--disable-debug          # internal debugger needs curses
--without-curses         # no ncursesw on Android
```

Resulting `config.h`: `C_SDLGFX=1`, `C_OPENGL=1`, `C_FPU=1`, `C_CORE_INLINE=1`,
and **no** `C_DEBUG` / `C_CURSOUT` / `C_DYNREC` / `C_DYNAMIC_X86`.

Link line (proof that curses is not a hard blocker):

```
dosbox.o cpu/libcpu.a debug/libdebug.a dos/libdos.a fpu/libfpu.a hardware/libhardware.a
       gui/libgui.a ints/libints.a misc/libmisc.a shell/libshell.a
       hardware/mame/libmame.a hardware/serialport/libserial.a libs/gui_tk/libgui_tk.a
       -lSDL2 -lX11 -lGL
```

## 3. DOSBox-0.74 behaviours that will bite you on Android

| Item | Fact | Consequence |
|---|---|---|
| `core` / `cycles` config keys | live in the **`[cpu]`** section, not `[dosbox]` | putting them in `[dosbox]` is silently ignored → default 3000 cycles/ms throttle → misleading benchmarks |
| `mount c <dir>` | does **not** switch the current drive | DOS file names must carry an explicit drive letter (`C:\OUT.TXT`), otherwise they land on the unmounted A: drive and creation fails silently |
| `int 21h AH=09` | string must be `$`-terminated | a missing `$` makes DOSBox scan memory until it finds one → the emulator appears to hang with no CPU usage |
| cycle limiter | `TIMER_AddTick()` (src/hardware/pic.cpp:539) gives the CPU `CPU_CycleMax` cycles and advances the virtual clock by 1 emulated ms | the emulated clock advances **per CPU_CycleMax instructions**, not per host ms |
| default budget | `CPU_CycleMax = 3000` (src/cpu/cpu.cpp:53) | real-time DOS needs **3,000,000 instructions/s** |
| `execvp`/`execlp` | src/gui/sdlmain.cpp:2839,2863,2870,2902 (`-host`, `-editconf`, restart) | must be compiled out on Android |
| `C_DIRECTSERIAL` | configure.ac defines `LINUX` for `*-*-linux*`, so `aarch64-linux-android` matches | termios/ioctl serial support should be disabled explicitly |
| `C_DYNREC` | configure.ac:480 enables it whenever `c_targetcpu == "arm"` (which includes aarch64/armv7l) | **must** be disabled on Android: it needs a GCC plugin (not buildable with NDK clang) and Android 10+ forbids exec of writable memory |

## 4. Throughput benchmark (`bench/`)

`bench/bench.asm` is a 16-bit DOS `.COM` program (hand-assembled with nasm) that
executes a fixed number of x86 instructions in a tight loop. It measures elapsed
**BIOS ticks** (INT 1Ah AH=00) and writes `TICKS=<hex> <outer>` to `C:\BENCH.TXT`.

**Metric caveat (important):** the BIOS-tick interval is *not* a valid throughput
metric on its own, because DOSBox advances its virtual clock once per
`CPU_CycleMax` instructions — so "instructions per BIOS tick" just returns
`CPU_CycleMax * 1000`, i.e. the configured cycle budget. The valid metric is
**instructions per wall-clock second**, measured with a large cycle budget
(`cycles=fixed 2000000`) so the emulator is CPU-bound rather than throttled.

Run it:

```sh
./bench/run-bench.sh 3 normal simple auto
```

## 5. Results — host: Intel Core Ultra 7 155H (x86_64), 4 vCPU, WSL2 Ubuntu 24.04

| Core | wall (s) | CPU (s) | instructions | IPS (best of 3) | vs 3M real-time |
|---|---|---|---|---|---|
| normal | 1.88 | 1.82 | 240,060,000 | **127,691,489** | 4256 % |
| simple | 1.80 | 1.73 | 240,060,000 | **133,366,666** | 4446 % |
| auto   | 2.02 | 1.97 | 240,060,000 | **118,841,584** | 3961 % |

`CPU ≈ wall` ⇒ the emulator is genuinely CPU-bound, so these are raw core speeds
and not throttled numbers. The synthetic loop is ALU-only; real DOS code with
more memory traffic will measure lower (expect 30–60 M IPS), still ~10–20× above
the real-time budget.

### Real-time pacing check (`cycles=fixed 3000`, the DOSBox default budget)

```
WALL=82.41s   CPU=3.68s   BIOS ticks=0x5B1=1457 -> emulated 80.05s
```

Emulated time (80.05 s) tracks wall time (82.41 s) while the process only burns
3.68 s of CPU (4.5 % of one core). That means **core_normal sustains 1x
real-time DOS execution at the default 3000 cycles/ms budget with ~40x
headroom on this host**, and the emulator idles in between (it only needs
3 M instr/s and the core can do >120 M instr/s).

## 6. Android toolchain already present on this machine

```
/home/whereq/android-sdk/ndk/27.1.12297006      # NDK r27 (aarch64 + armeabi-v7a)
/home/whereq/android-sdk/cmake/3.22.1/bin/cmake
/home/whereq/android-sdk/build-tools, platforms, platform-tools
```

Verified: `aarch64-linux-android24-clang` produces a working arm64-v8a binary.

Still missing for the NDK port: **SDL2 built for Android** (either the
`libsdl-org/SDL` AAR, or building SDL2 from source with the NDK) and the
Gradle/Java application shell.

