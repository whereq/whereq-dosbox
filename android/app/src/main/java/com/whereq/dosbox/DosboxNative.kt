package com.whereq.dosbox

/**
 * Bridge to the native side of the DOSBox library.
 *
 * The library is loaded by [org.libsdl.app.SDLActivity] itself; the init block
 * here is only a safety net so that this class can be used before SDLActivity
 * got a chance to load it.
 *
 * The functions are `@JvmStatic external`, so they keep the exact JNI symbol
 * names (`Java_com_whereq_dosbox_DosboxNative_*`) the native side registers by
 * name. The ProGuard keep rule in `proguard-rules.pro` covers this class.
 */
object DosboxNative {

    init {
        try {
            System.loadLibrary("dosbox")
        } catch (e: UnsatisfiedLinkError) {
            // SDLActivity will load it later; ignore.
        }
    }

    /**
     * Prepares the process for DOSBox:
     *  - sets `$HOME` to the app's private files directory, so that DOSBox
     *    resolves `~/.dosbox` into writable storage;
     *  - changes the working directory to the same place, so that relative
     *    paths in `dosbox.conf` and `[autoexec]` resolve inside the app sandbox.
     *
     * @param filesDir absolute path of `Context.getFilesDir()`.
     */
    @JvmStatic
    external fun nativePrepareEnvironment(filesDir: String)

    /** @return the directory DOSBox uses for its per-user configuration. */
    @JvmStatic
    external fun nativeConfigDir(): String

    /**
     * Translates a key name into the numeric SDL scancode.
     *
     * The names follow DOSBox's own key table (the `DefaultKeys[]` array in
     * `src/gui/sdl_mapper.cpp`), so every key of the virtual keyboard has a 1:1
     * counterpart in the mapper's bind list: `"a"`..`"z"`, `"0"`..`"9"`,
     * `"f1"`..`"f12"`, `"space"`, `"esc"`, `"enter"`, `"bspace"`, `"tab"`,
     * `"lshift"`, `"rshift"`, `"lctrl"`, `"rctrl"`, `"lalt"`, `"ralt"`,
     * `"capslock"`, `"numlock"`, `"scrolllock"`, `"pause"`, `"printscreen"`,
     * `"lbracket"`, `"rbracket"`, `"minus"`, `"equals"`, `"backslash"`,
     * `"semicolon"`, `"quote"`, `"grave"`, `"comma"`, `"period"`, `"slash"`,
     * `"up"`, `"down"`, `"left"`, `"right"`, `"insert"`, `"delete"`, `"home"`,
     * `"end"`, `"pageup"`, `"pagedown"`.
     *
     * @param name key name.
     * @return the scancode, or 0 (`SDL_SCANCODE_UNKNOWN`) if unknown.
     */
    @JvmStatic
    external fun nativeScancodeFromName(name: String): Int

    /**
     * Injects a key press or release into DOSBox's SDL event queue.
     *
     * DOSBox's SDL2 back end routes key events through the mapper
     * (`src/gui/sdl_mapper.cpp`), which looks up the *scancode* in its bind list
     * and calls `KEYBOARD_AddKey()` — so a virtual key press is exactly an SDL
     * key event with the right scancode.
     *
     * @param scancode SDL scancode, see [nativeScancodeFromName].
     * @param pressed  true for a key press, false for a key release.
     * @return true if the event was queued.
     */
    @JvmStatic
    external fun nativePushKey(scancode: Int, pressed: Boolean): Boolean
}
