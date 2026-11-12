package com.whereq.dosbox;

/**
 * Bridge to the native side of the DOSBox library.
 *
 * <p>The library is loaded by {@link org.libsdl.app.SDLActivity} itself; the
 * static initialiser here is only a safety net so that this class can be
 * used before SDLActivity got a chance to load it.
 */
public final class DosboxNative {

    static {
        try {
            System.loadLibrary("dosbox");
        } catch (UnsatisfiedLinkError e) {
            // SDLActivity will load it later; ignore.
        }
    }

    private DosboxNative() {
    }

    /**
     * Prepares the process for DOSBox:
     * <ul>
     *   <li>sets {@code $HOME} to the app's private files directory, so that
     *       DOSBox resolves {@code ~/.dosbox} into writable storage;</li>
     *   <li>changes the working directory to the same place, so that
     *       relative paths in {@code dosbox.conf} and {@code [autoexec]}
     *       resolve inside the app sandbox.</li>
     * </ul>
     *
     * @param filesDir absolute path of {@code Context.getFilesDir()}
     */
    public static native void nativePrepareEnvironment(String filesDir);

    /**
     * @return the directory DOSBox uses for its per-user configuration.
     */
    public static native String nativeConfigDir();

    /**
     * Translates a key name into the numeric SDL scancode.
     *
     * <p>The names follow DOSBox's own key table (the {@code DefaultKeys[]}
     * array in {@code src/gui/sdl_mapper.cpp}), so every key of the virtual
     * keyboard has a 1:1 counterpart in the mapper's bind list:
     * {@code "a"}..{@code "z"}, {@code "0"}..{@code "9"},
     * {@code "f1"}..{@code "f12"}, {@code "space"}, {@code "esc"},
     * {@code "enter"}, {@code "bspace"}, {@code "tab"}, {@code "lshift"},
     * {@code "rshift"}, {@code "lctrl"}, {@code "rctrl"}, {@code "lalt"},
     * {@code "ralt"}, {@code "capslock"}, {@code "numlock"},
     * {@code "scrolllock"}, {@code "pause"}, {@code "printscreen"},
     * {@code "lbracket"}, {@code "rbracket"}, {@code "minus"},
     * {@code "equals"}, {@code "backslash"}, {@code "semicolon"},
     * {@code "quote"}, {@code "grave"}, {@code "comma"}, {@code "period"},
     * {@code "slash"}, {@code "up"}, {@code "down"}, {@code "left"},
     * {@code "right"}, {@code "insert"}, {@code "delete"}, {@code "home"},
     * {@code "end"}, {@code "pageup"}, {@code "pagedown"}.
     *
     * @param name key name.
     * @return the scancode, or 0 ({@code SDL_SCANCODE_UNKNOWN}) if unknown.
     */
    public static native int nativeScancodeFromName(String name);

    /**
     * Injects a key press or release into DOSBox's SDL event queue.
     *
     * <p>DOSBox's SDL2 back end routes key events through the mapper
     * ({@code src/gui/sdl_mapper.cpp}), which looks up the <em>scancode</em>
     * in its bind list and calls {@code KEYBOARD_AddKey()} - so a virtual key
     * press is exactly an SDL key event with the right scancode.
     *
     * @param scancode SDL scancode, see {@link #nativeScancodeFromName}.
     * @param pressed  true for a key press, false for a key release.
     * @return true if the event was queued.
     */
    public static native boolean nativePushKey(int scancode, boolean pressed);
}
