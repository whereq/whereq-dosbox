package com.whereq.dosbox

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Types an ASCII command into the running DOSBox.
 *
 * The emulator is driven through the same path the on-screen keyboard uses: an
 * SDL key event with the right scancode goes into SDL's event queue, DOSBox's
 * mapper turns it into `KEYBOARD_AddKey()`, and the DOS kernel reads it from the
 * BIOS keyboard buffer. Typing a command is therefore nothing but a sequence of
 * virtual key presses.
 *
 * Two details matter:
 *  - **Shifted characters.** DOSBox keeps the shift state in the keyboard
 *    buffer, so an upper case letter is a shift press, the key, and a shift
 *    release — exactly what the on-screen keyboard does for its symbol row.
 *  - **Pacing.** The BIOS keyboard buffer holds 32 scan codes (`KEYBUFSIZE` in
 *    `src/hardware/keyboard.cpp`) and drops what does not fit. The buffer is
 *    drained by the shell much faster than a human types, but the characters are
 *    still sent one after the other, so they are spaced out. Without the pause a
 *    long command would lose its tail.
 */
class DosboxTyper {

    /** Called when the whole command has been typed. */
    fun interface Callback {
        fun onTypingFinished(ok: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scancodes = HashMap<String, Int>()

    private var command = ""
    private var position = 0
    private var callback: Callback? = null
    private var running = false

    /**
     * Types a command and presses Enter.
     *
     * @param command  the command, in the characters [KEYS] knows.
     * @param callback told when the command is finished; may be null.
     */
    fun type(command: String, callback: Callback?) {
        if (running) {
            Log.w(TAG, "a command is already being typed")
            return
        }
        this.command = command
        this.position = 0
        this.callback = callback
        this.running = true
        handler.postDelayed({ step() }, START_DELAY_MS)
    }

    /** @return true while a command is being typed. */
    fun isTyping(): Boolean = running

    /**
     * Stops the current command. The characters that were already typed stay in
     * the DOS input buffer — there is no way to take them back.
     */
    fun cancel() {
        if (!running) {
            return
        }
        running = false
        handler.removeCallbacksAndMessages(null)
        callback?.onTypingFinished(false)
    }

    private fun step() {
        if (!running) {
            return
        }
        if (position < command.length) {
            val c = command[position++]
            if (!press(c)) {
                Log.w(TAG, "cannot type '$c'")
            }
            handler.postDelayed({ step() }, CHAR_DELAY_MS)
            return
        }

        // The command is typed — press Enter.
        press(' ')
        pressEnter()

        running = false
        callback?.onTypingFinished(true)
    }

    /**
     * Presses and releases one character.
     *
     * @return false if the character is not on the list.
     */
    private fun press(c: Char): Boolean {
        val key = KEYS[c] ?: return false
        val code = scancode(key[0]!!)
        if (code == 0) {
            return false
        }
        val shiftName = key[1]
        if (shiftName != null) {
            DosboxNative.nativePushKey(scancode(shiftName), true)
        }
        DosboxNative.nativePushKey(code, true)
        DosboxNative.nativePushKey(code, false)
        if (shiftName != null) {
            DosboxNative.nativePushKey(scancode(shiftName), false)
        }
        return true
    }

    private fun pressEnter() {
        val code = scancode("enter")
        if (code == 0) {
            return
        }
        DosboxNative.nativePushKey(code, true)
        DosboxNative.nativePushKey(code, false)
    }

    private fun scancode(keyName: String): Int =
        scancodes.getOrPut(keyName) { DosboxNative.nativeScancodeFromName(keyName) }

    companion object {
        private const val TAG = "whereq-dosbox"

        /** Pause between two characters, in milliseconds. */
        private const val CHAR_DELAY_MS = 45L

        /** Pause before the first character, so the shell can catch up. */
        private const val START_DELAY_MS = 120L

        /** character -> {key name, shift key name or null}. */
        private val KEYS: Map<Char, Array<String?>> = buildMap {
            for (c in 'a'..'z') {
                put(c, arrayOf(c.toString(), null))
            }
            for (c in 'A'..'Z') {
                put(c, arrayOf(c.toString().lowercase(), "lshift"))
            }
            for (c in '0'..'9') {
                put(c, arrayOf(c.toString(), null))
            }
            put(' ', arrayOf("space", null))
            put('\\', arrayOf("backslash", null))
            put('/', arrayOf("slash", null))
            put('.', arrayOf("period", null))
            put('-', arrayOf("minus", null))
            put('_', arrayOf("minus", "lshift"))
            put(':', arrayOf("semicolon", "lshift"))
            put(';', arrayOf("semicolon", null))
            put('=', arrayOf("equals", null))
            put('+', arrayOf("equals", "lshift"))
        }
    }
}
