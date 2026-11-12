cmake_minimum_required(VERSION 3.22.1)

# whereq-dosbox - Android native build (NDK + CMake)
#
# Produces libdosbox.so, a shared library that contains the whole DOSBox
# emulator (CPU cores, DOS kernel, hardware, shell) plus the SDL2 back end.
# The Java/Gradle side loads it through SDL2's SDLActivity; SDL_main.h
# redirects DOSBox's main() to SDL_main() with C linkage, so no source patch
# is required for the entry point.
#
# Usage (see build-sdl2.sh for the SDL2 prerequisite):
#   cmake -S android -B build/android/arm64-v8a \
#         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
#         -DCMAKE_BUILD_TYPE=Release \
#         -DCMAKE_PREFIX_PATH=$PWD/build/sdl2/arm64-v8a
#   cmake --build build/android/arm64-v8a -j
project(whereq-dosbox C CXX)

set(DOSBOX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(DOSBOX_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/include")

# ---------------------------------------------------------------- ABI/CPU --
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(DOSBOX_C_TARGETCPU ARMV8LE)
    set(DOSBOX_SIZEOF_INT_P 8)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(DOSBOX_C_TARGETCPU ARMV7LE)
    set(DOSBOX_SIZEOF_INT_P 4)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # x86_64 is only used for the emulator test rig (the SDK ships x86_64
    # system images); phones are arm.
    set(DOSBOX_C_TARGETCPU X86_64)
    set(DOSBOX_SIZEOF_INT_P 8)
elseif(ANDROID_ABI STREQUAL "x86")
    set(DOSBOX_C_TARGETCPU X86)
    set(DOSBOX_SIZEOF_INT_P 4)
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI '${ANDROID_ABI}'")
endif()

# --------------------------------------------------------------- config.h --
# The size of the C integer types has to follow the target ABI: "long" is 8
# bytes on arm64-v8a/x86_64 (LP64) but only 4 bytes on armeabi-v7a/x86
# (ILP32). Ask the compiler instead of guessing - DOSBox derives its
# Bit64u/Bit64s typedefs from it and a wrong answer truncates the 64 bit x86
# arithmetic in the CPU core.
include(CheckTypeSize)
check_type_size("long" DOSBOX_SIZEOF_UNSIGNED_LONG)
check_type_size("long long" DOSBOX_SIZEOF_UNSIGNED_LONG_LONG)
if(NOT DOSBOX_SIZEOF_UNSIGNED_LONG OR NOT DOSBOX_SIZEOF_UNSIGNED_LONG_LONG)
    message(FATAL_ERROR "could not determine the size of long / long long")
endif()
message(STATUS "long = ${DOSBOX_SIZEOF_UNSIGNED_LONG} bytes, long long = ${DOSBOX_SIZEOF_UNSIGNED_LONG_LONG} bytes, int* = ${DOSBOX_SIZEOF_INT_P} bytes")

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/config-android.h.in"
    "${DOSBOX_GENERATED}/config.h"
    @ONLY)

# -------------------------------------------------------------- version.h --
# src/version.py writes include/version.h (VERSION_TEXT). Fall back to a
# static string when python3 is unavailable.
find_package(Python3 COMPONENTS Interpreter QUIET)
if(Python3_Interpreter_FOUND)
    execute_process(
        COMMAND "${Python3_EXECUTABLE}" "${DOSBOX_ROOT}/src/version.py"
                "${DOSBOX_GENERATED}/version.h" "${DOSBOX_ROOT}" "${CMAKE_CXX_COMPILER}"
        RESULT_VARIABLE _ver_res
        OUTPUT_QUIET ERROR_QUIET)
    if(NOT _ver_res EQUAL 0)
        message(WARNING "version.py failed (${_ver_res}); writing fallback version.h")
        file(WRITE "${DOSBOX_GENERATED}/version.h"
             "/* fallback */\n#define VERSION_TEXT \"whereq-dosbox android\"\n")
    endif()
else()
    message(WARNING "python3 not found; writing fallback version.h")
    file(WRITE "${DOSBOX_GENERATED}/version.h"
         "/* fallback */\n#define VERSION_TEXT \"whereq-dosbox android\"\n")
endif()

# ----------------------------------------------------------------- SDL2 ----
# The SDL2 static library is built out-of-tree by android/build-sdl2.sh.
#
# Two wrinkles:
#  * The NDK toolchain file sets CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY,
#    which hides our SDL2 prefix from find_package() -> allow BOTH.
#  * A static-only SDL2 build exports the target as SDL2::SDL2-static
#    (SDL2::SDL2 is the shared-library target).
set(SDL2_PREFIX "" CACHE PATH "Prefix of the NDK-built SDL2 (contains lib/libSDL2.a)")

set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
find_package(SDL2 CONFIG QUIET)

if(TARGET SDL2::SDL2)
    set(DOSBOX_SDL2_TARGET SDL2::SDL2)
elseif(TARGET SDL2::SDL2-static)
    set(DOSBOX_SDL2_TARGET SDL2::SDL2-static)
else()
    # Fall back to an explicit prefix lookup that bypasses the NDK root path.
    if(NOT SDL2_PREFIX)
        message(FATAL_ERROR
            "SDL2 not found. Build it with android/build-sdl2.sh and pass "
            "-DSDL2_PREFIX=<sdl2-prefix> (or -DCMAKE_PREFIX_PATH=...)")
    endif()
    find_library(DOSBOX_SDL2_LIBRARY
        NAMES SDL2
        PATHS "${SDL2_PREFIX}/lib"
        NO_CMAKE_FIND_ROOT_PATH)
    find_path(DOSBOX_SDL2_INCLUDE_DIR
        NAMES SDL.h
        PATHS "${SDL2_PREFIX}/include/SDL2"
        NO_CMAKE_FIND_ROOT_PATH)
    if(NOT DOSBOX_SDL2_LIBRARY OR NOT DOSBOX_SDL2_INCLUDE_DIR)
        message(FATAL_ERROR "SDL2 not found under SDL2_PREFIX='${SDL2_PREFIX}'")
    endif()
    add_library(SDL2::SDL2 UNKNOWN IMPORTED)
    set_target_properties(SDL2::SDL2 PROPERTIES
        IMPORTED_LOCATION "${DOSBOX_SDL2_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${DOSBOX_SDL2_INCLUDE_DIR}")
    set(DOSBOX_SDL2_TARGET SDL2::SDL2)
endif()

# ---------------------------------------------------------------- sources --
# Mirrors the *_SOURCES lists of the upstream src/*/Makefile.am files.
# Android-relevant exclusions are marked below.
set(DOSBOX_SOURCES
    android/jni/dosbox_android.cpp
    src/dosbox.cpp

    src/cpu/callback.cpp
    src/cpu/cpu.cpp
    src/cpu/flags.cpp
    src/cpu/modrm.cpp
    src/cpu/core_full.cpp
    src/cpu/paging.cpp
    src/cpu/core_normal.cpp
    src/cpu/core_simple.cpp
    src/cpu/core_prefetch.cpp
    # excluded: core_dyn_x86.cpp (x86-only JIT)
    # excluded: core_dynrec.cpp  (needs a GCC plugin; NDK clang cannot build it)

    src/debug/debug.cpp
    src/debug/debug_disasm.cpp
    # excluded: debug_gui.cpp (curses), debug_win32.cpp (Windows)

    src/dos/dos.cpp
    src/dos/dos_devices.cpp
    src/dos/dos_execute.cpp
    src/dos/dos_files.cpp
    src/dos/dos_ioctl.cpp
    src/dos/dos_memory.cpp
    src/dos/dos_misc.cpp
    src/dos/dos_classes.cpp
    src/dos/dos_programs.cpp
    src/dos/dos_tables.cpp
    src/dos/drives.cpp
    src/dos/drive_virtual.cpp
    src/dos/drive_local.cpp
    src/dos/drive_cache.cpp
    src/dos/drive_fat.cpp
    src/dos/drive_iso.cpp
    src/dos/dos_mscdex.cpp
    src/dos/dos_keyboard_layout.cpp
    src/dos/cdrom.cpp
    src/dos/cdrom_image.cpp
    src/dos/drive_overlay.cpp
    # excluded: cdrom_ioctl_{linux,os2,win32}.cpp, cdrom_aspi_win32.cpp

    src/fpu/fpu.cpp

    src/gui/sdlmain.cpp
    src/gui/sdl_mapper.cpp
    src/gui/render.cpp
    src/gui/render_scalers.cpp
    src/gui/midi.cpp
    src/gui/sdl_gui.cpp
    src/gui/curstxt.cpp

    src/hardware/adlib.cpp
    src/hardware/dma.cpp
    src/hardware/gameblaster.cpp
    src/hardware/hardware.cpp
    src/hardware/iohandler.cpp
    src/hardware/joystick.cpp
    src/hardware/keyboard.cpp
    src/hardware/memory.cpp
    src/hardware/mixer.cpp
    src/hardware/pcspeaker.cpp
    src/hardware/pci_bus.cpp
    src/hardware/pic.cpp
    src/hardware/sblaster.cpp
    src/hardware/tandy_sound.cpp
    src/hardware/timer.cpp
    src/hardware/vga.cpp
    src/hardware/vga_attr.cpp
    src/hardware/vga_crtc.cpp
    src/hardware/vga_dac.cpp
    src/hardware/vga_draw.cpp
    src/hardware/vga_gfx.cpp
    src/hardware/vga_other.cpp
    src/hardware/vga_memory.cpp
    src/hardware/vga_misc.cpp
    src/hardware/vga_seq.cpp
    src/hardware/vga_xga.cpp
    src/hardware/vga_s3.cpp
    src/hardware/vga_tseng.cpp
    src/hardware/vga_paradise.cpp
    src/hardware/cmos.cpp
    src/hardware/disney.cpp
    src/hardware/gus.cpp
    src/hardware/mpu401.cpp
    src/hardware/ipx.cpp
    src/hardware/ipxserver.cpp
    src/hardware/dbopl.cpp

    src/hardware/mame/fmopl.cpp
    src/hardware/mame/saa1099.cpp
    src/hardware/mame/sn76496.cpp
    src/hardware/mame/ymdeltat.cpp
    src/hardware/mame/ymf262.cpp

    src/hardware/serialport/directserial.cpp
    src/hardware/serialport/libserial.cpp
    src/hardware/serialport/serialdummy.cpp
    src/hardware/serialport/serialport.cpp
    src/hardware/serialport/softmodem.cpp
    src/hardware/serialport/misc_util.cpp
    src/hardware/serialport/nullmodem.cpp

    src/ints/mouse.cpp
    src/ints/xms.cpp
    src/ints/ems.cpp
    src/ints/int10.cpp
    src/ints/int10_char.cpp
    src/ints/int10_memory.cpp
    src/ints/int10_misc.cpp
    src/ints/int10_modes.cpp
    src/ints/int10_vesa.cpp
    src/ints/int10_pal.cpp
    src/ints/int10_put_pixel.cpp
    src/ints/int10_video_state.cpp
    src/ints/int10_vptable.cpp
    src/ints/bios.cpp
    src/ints/bios_disk.cpp
    src/ints/bios_keyboard.cpp

    src/libs/gui_tk/gui_tk.cpp

    src/misc/cross.cpp
    src/misc/messages.cpp
    src/misc/programs.cpp
    src/misc/setup.cpp
    src/misc/support.cpp

    src/shell/shell.cpp
    src/shell/shell_batch.cpp
    src/shell/shell_cmds.cpp
    src/shell/shell_misc.cpp
)

# ------------------------------------------------------------------ build --
# The list above mirrors the upstream Makefile.am paths, which are relative to
# the DOSBox source root; CMake resolves them against this directory, so map
# them onto ${DOSBOX_ROOT}.
set(DOSBOX_SOURCES_ABS "")
foreach(_src ${DOSBOX_SOURCES})
    list(APPEND DOSBOX_SOURCES_ABS "${DOSBOX_ROOT}/${_src}")
endforeach()

add_library(dosbox SHARED ${DOSBOX_SOURCES_ABS})

target_include_directories(dosbox PRIVATE
    "${DOSBOX_ROOT}/include"
    "${DOSBOX_ROOT}/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni"
    "${DOSBOX_GENERATED}")

target_compile_definitions(dosbox PRIVATE
    _GNU_SOURCE
    HAVE_STRINGS_H)

target_compile_options(dosbox PRIVATE
    -Wall
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-unused-function
    -Wno-sign-compare
    -Wno-format-truncation
    -fno-strict-aliasing)


target_link_libraries(dosbox PRIVATE
    ${DOSBOX_SDL2_TARGET}
    OpenSLES
    log
    android
    m
    dl
    # SDL2's OpenGLES renderers call glViewport/glUseProgram/... directly.
    # They normally arrive through SDL2's INTERFACE_LINK_LIBRARIES, but that
    # is only true when find_package(SDL2 CONFIG) succeeded - when we fall
    # back to a manual lookup the interface is lost. List them explicitly so
    # the link works either way. (Static-link order matters: SDL2 first.)
    GLESv1_CM
    GLESv2)

set_target_properties(dosbox PROPERTIES
    OUTPUT_NAME "dosbox"
    C_VISIBILITY_PRESET default
    CXX_VISIBILITY_PRESET default)

message(STATUS "whereq-dosbox: ABI=${ANDROID_ABI} targetcpu=${DOSBOX_C_TARGETCPU} SDL2 target=${DOSBOX_SDL2_TARGET}")
