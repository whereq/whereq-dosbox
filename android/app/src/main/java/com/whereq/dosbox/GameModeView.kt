package com.whereq.dosbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Game Mode: a touch gamepad drawn in the letterbox bars beside the 4:3 DOS
 * picture, the counterpart to [VirtualKeyboardView]. Three profiles share one
 * skeleton (top bar · upper row · a big centre control · bottom row); switching
 * profile only changes the labels and the centre control, never the DOS screen.
 *
 *  - **HYBRID** (default): 8-way D-pad + trackball; L/R-CLICK on the left
 *    shoulder so a held L-CLICK + a trackball move is a drag-select.
 *  - **ACTION**: D-pad + a face-button diamond (FIRE=Ctrl, JUMP=Alt, USE=Space,
 *    ENTER); shoulders SHIFT/TAB and strafe `,`/`.`; TURBO auto-fires Ctrl.
 *  - **POINT & CLICK**: no D-pad — big LEFT/RIGHT mouse buttons + trackball,
 *    DRAG lock, DBL click, SHIFT/CTRL click-modifiers, pointer-speed cycle.
 *
 * The D-pad output (arrows, numpad or WASD) and the active profile are reported
 * to the [Listener] so the activity can remember them per game.
 *
 * Input reaches DOSBox exactly as the keyboard's does — [DosboxNative.nativePushKey]
 * for keys, [DosboxNative.nativePushMouseButton] for clicks, and
 * [DosboxNative.nativePushMouseMotion] for the trackball.
 */
class GameModeView(context: Context) : FrameLayout(context) {

    enum class Profile { HYBRID, ACTION, POINT }

    /** What the D-pad emits. Numpad diagonals are single keys (7/9/1/3); the
     *  arrow and WASD diagonals press two keys at once. */
    enum class Dpad { ARROWS, NUMPAD, WASD }

    /** The activity listens so it can host the launcher, remember the per-game
     *  profile/D-pad, and swap back to the keyboard pads. */
    interface Listener {
        fun onExitToKeyboard()
        fun onFilesRequested()
        fun onProfileChanged(profile: Profile)
        fun onDpadChanged(dpad: Dpad)
        /** The per-game button remaps changed (slot "profile:defaultKey" -> key). */
        fun onRemapsChanged(remaps: Map<String, String>)

        /**
         * The player tapped SAVE on the gamepad: save the running game into a
         * slot now, without leaving Game Mode for the keyboard.
         */
        fun onQuickSaveRequested()

        /**
         * The player held SAVE: open the Save states sheet, so a slot can be
         * picked. The sheet opens over the gamepad, and Game Mode is still
         * there when it closes.
         */
        fun onStatesRequested()
    }

    private var listener: Listener? = null
    fun setListener(l: Listener?) { listener = l }

    private var profile = Profile.HYBRID
    private var dpad = Dpad.ARROWS

    /** Button remaps, keyed by slot "<profile>:<defaultKey>" -> mapped key name.
     *  Spans all profiles; restored per game. */
    private val remaps = HashMap<String, String>()

    /** In remap mode a tap on a key button opens its key picker instead of
     *  sending the key (entered from the profile button's long-press menu). */
    private var remapMode = false

    /**
     * Restores a game's saved Game Mode state - profile, D-pad output and button
     * remaps - in one shot, then lays the gamepad out once. Used when the gamepad
     * is shown; the per-setter paths ([setProfile], [setDpad]) are for later
     * user-driven changes and notify the listener, which this does not.
     *
     * Setting every field before the single rebuild also fixes the ordering the
     * separate setters needed (the D-pad had to be in place before the bar was
     * built, or the chip would name the previous mode).
     */
    fun restoreState(profile: Profile, dpad: Dpad, remaps: Map<String, String>) {
        this.profile = profile
        this.dpad = dpad
        this.remaps.clear()
        this.remaps.putAll(remaps)
        // The DRAG lock only belongs to POINT; restoring another profile drops it
        // so the left mouse button is not left down inside DOSBox.
        if (profile != Profile.POINT) releaseDragLock()
        rebuild(force = true)
    }

    /** Trackball pointer speed multiplier, cycled by the POINT "speed" chip. */
    private var trackSpeed = 1.0f

    /** Scancodes currently held down, released on a rebuild so nothing sticks. */
    private val downKeys = HashSet<String>()
    private val scancodeCache = HashMap<String, Int>()

    /**
     * The TURBO auto-fire loop while the FIRE button is held, or null. It has to
     * live here, not inside the FIRE button's touch listener, because a rebuild
     * (a rotation, or the other thumb cycling a chip) detaches the FIRE view
     * mid-hold without an ACTION_UP - so the listener would never stop it and the
     * key would fire forever. [releaseKeys] cancels it, and rebuild/hide/destroy
     * all go through there.
     */
    private var autofire: Runnable? = null

    /** Mouse buttons a finger is holding down. They are released on a rebuild
     *  and when the view is hidden, so a button that was pressed does not stay
     *  down inside DOSBox. */
    private val downMouseButtons = HashSet<Int>()

    private val handler = Handler(Looper.getMainLooper())

    /** Width of the bar the current layout was built for (−1 = not built). */
    private var builtBarWidth = -1
    private var builtProfile: Profile? = null

    private val konami = KonamiDetector(GAME_EXIT_SEQUENCE) { listener?.onExitToKeyboard() }

    init {
        isClickable = false
        isMotionEventSplittingEnabled = true
    }

    /* --------------------------------------------------------- public API */

    /** Sets the profile without notifying the listener (used to restore the
     *  per-game choice); pass [notify] = true for a user-driven change. */
    fun setProfile(p: Profile, notify: Boolean = false) {
        if (p == profile && builtProfile == p) return
        profile = p
        // The DRAG lock belongs to the POINT profile: leaving it drops the
        // lock, so the left mouse button is not left down inside DOSBox.
        if (profile != Profile.POINT) releaseDragLock()
        if (notify) listener?.onProfileChanged(p)
        rebuild(force = true)
    }

    /**
     * Sets the D-pad output mode without notifying the listener (used to
     * restore the per-game choice); pass [notify] = true for a user-driven
     * change.
     *
     * The bar carries a chip that names the current mode, so a change rebuilds
     * the bar when it is already on screen - otherwise the chip would keep
     * naming the mode the bar was built with.
     */
    fun setDpad(d: Dpad, notify: Boolean = false) {
        if (d == dpad) return
        dpad = d
        if (notify) listener?.onDpadChanged(d)
        // A bar that has never been built picks the new mode up by itself.
        if (builtProfile != null) rebuild(force = true)
    }

    fun currentProfile(): Profile = profile
    fun currentDpad(): Dpad = dpad

    /** Releases every held key and mouse button — called when the view is
     *  hidden so a thumb that was down does not leave a key stuck inside
     *  DOSBox, and a locked mouse button does not stay down either. */
    fun releaseAll() {
        releaseKeys()
        releaseMouseButtons()
        releaseDragLock()
    }

    /** Releases every key the view holds down, and stops the TURBO auto-fire. */
    private fun releaseKeys() {
        stopAutofire()
        for (name in downKeys.toList()) push(name, false)
        downKeys.clear()
    }

    /** Stops the TURBO auto-fire loop if it is running. */
    private fun stopAutofire() {
        autofire?.let { handler.removeCallbacks(it); autofire = null }
    }

    /** Releases the mouse buttons a finger is on. */
    private fun releaseMouseButtons() {
        for (button in downMouseButtons.toList()) {
            DosboxNative.nativePushMouseButton(button, false)
        }
        downMouseButtons.clear()
    }

    /**
     * Releases the DRAG lock - the left mouse button that the POINT profile
     * holds down on purpose.
     *
     * The lock only makes sense while the POINT profile is on screen, so it is
     * dropped when the profile changes and when the view is hidden. Otherwise
     * the button would stay down inside DOSBox with no way to release it.
     */
    private fun releaseDragLock() {
        if (!dragLocked) return
        dragLocked = false
        DosboxNative.nativePushMouseButton(DosboxNative.SDL_BUTTON_LEFT, false)
    }

    /* ------------------------------------------------------------- layout */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { rebuild(force = false) }
    }

    private fun rebuild(force: Boolean) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        // The DOS picture is 4:3 and fills the height in landscape; the pads
        // live in the bars on either side. (Matches DOSBox's letterbox clip.)
        val barWidth = if (w > h) maxOf(0, (w - h * 4 / 3) / 2) else 0
        if (!force && barWidth == builtBarWidth && builtProfile == profile) return

        releaseKeys()
        releaseMouseButtons()
        removeAllViews()
        strip = null            // the transient Fn/number bar was just removed
        remapBanner = null      // and so was the remap banner
        builtBarWidth = barWidth
        builtProfile = profile

        if (barWidth < dp(DP_MIN_PAD_WIDTH)) {
            // Too little room for a gamepad; a hint keeps it from looking broken.
            // (Game Mode is a landscape feature; Back returns to the keyboard.)
            val hint = TextView(context)
            hint.text = "Rotate to landscape for Game Mode — press Back for the keyboard"
            hint.setTextColor(COLOR_TEXT_DIM)
            hint.gravity = Gravity.CENTER
            hint.setPadding(dp(16), dp(8), dp(16), dp(8))
            hint.background = keyBg(COLOR_TOOLBAR)
            addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            })
            return
        }

        val padWidth = minOf(barWidth, dp(DP_MAX_PAD_WIDTH))
        addView(buildPad(left = true), LayoutParams(padWidth, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
        })
        addView(buildPad(left = false), LayoutParams(padWidth, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        })
        // The remap banner is rebuilt with the pads, so it survives a remap (a
        // rebuild) and stays the way out of remap mode.
        if (remapMode) showRemapBanner()
    }

    /** One pad: a top bar, an upper row, the big centre control, a bottom row. */
    private fun buildPad(left: Boolean): LinearLayout {
        val pad = column().apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setBackgroundColor(COLOR_TOOLBAR)
        }
        pad.addView(weightV(topBar(left), 1.0f))
        pad.addView(weightV(upperRow(left), 1.1f))
        pad.addView(weightV(centre(left), 3.4f))
        pad.addView(weightV(bottomRow(left), 1.1f))
        return pad
    }

    /* ----------------------------------------------------------- top bar */

    private fun topBar(left: Boolean): LinearLayout {
        val row = row()
        if (left) {
            // The gold profile button: tap cycles, long-press opens a picker.
            val name = when (profile) {
                Profile.HYBRID -> "HYBRID ▾"; Profile.ACTION -> "ACTION ▾"; Profile.POINT -> "POINT ▾"
            }
            val gold = chip(name, "game mode", COLOR_GOLD, textColor = COLOR_TEXT_ON_GOLD)
            gold.setOnClickListener { cycleProfile() }
            gold.setOnLongClickListener { showProfilePicker(); true }
            row.addView(weight(gold, 1.5f))

            // SAVE: the gamepad's own way to reach the save states, so a
            // snapshot does not need the keyboard pads. Tap saves into a slot
            // right away; hold opens the Save states sheet to pick one.
            // It sits with the other app-level controls (the profile button
            // and ESC), and it is green because green is the colour this app
            // uses for a save state everywhere else.
            val save = chip("SAVE", "state", COLOR_SAVE,
                textColor = COLOR_TEXT_ON_SAVE, subTextColor = COLOR_SUB_ON_SAVE)
            save.setOnClickListener { listener?.onQuickSaveRequested() }
            save.setOnLongClickListener { listener?.onStatesRequested(); true }
            row.addView(weight(save, 1.1f))

            row.addView(weight(tapKey("ESC", "menu", "esc", COLOR_KEY_DARK), 0.8f))
        } else {
            // Nintendo-style face buttons: Y and X send the y / x keys. They also
            // finish the reverse Konami code (X,Y stand in for B,A) that returns
            // to the keyboard pads — ↑↑↓↓←→←→ then X,Y — the retro salute; there
            // is no KBD button on purpose. Hardware Back returns too.
            row.addView(weight(tapKey("Y", "", "y", COLOR_KEY, konamiToken = "y"), 1f))
            row.addView(weight(tapKey("X", "", "x", COLOR_KEY, konamiToken = "x"), 1f))
        }
        return row
    }

    /* --------------------------------------------------------- upper row */

    private fun upperRow(left: Boolean): LinearLayout {
        val row = row()
        when {
            left && profile == Profile.HYBRID -> {
                row.addView(weight(holdMouse("L-CLICK", "mouse", DosboxNative.SDL_BUTTON_LEFT, COLOR_KEY), 1f))
                row.addView(weight(holdMouse("R-CLICK", "mouse", DosboxNative.SDL_BUTTON_RIGHT, COLOR_KEY), 1f))
            }
            left && profile == Profile.ACTION -> {
                row.addView(weight(holdKey("SHIFT", "run", "lshift", COLOR_KEY), 1f))
                row.addView(weight(holdKey("TAB", "map", "tab", COLOR_KEY), 1f))
            }
            left && profile == Profile.POINT -> {
                row.addView(weight(dragLockChip(), 1f))
                row.addView(weight(dblClickChip(), 1f))
            }
            !left && profile == Profile.HYBRID -> {
                row.addView(weight(holdKey("CTRL", "fire", "lctrl", COLOR_KEY), 1f))
                row.addView(weight(holdKey("ALT", "strafe", "lalt", COLOR_KEY), 1f))
            }
            !left && profile == Profile.ACTION -> {
                row.addView(weight(holdKey("STR ◂", ", key", "comma", COLOR_KEY), 1f))
                row.addView(weight(holdKey("STR ▸", ". key", "period", COLOR_KEY), 1f))
            }
            !left && profile == Profile.POINT -> {
                row.addView(weight(fnChip(), 1f))
                row.addView(weight(speedChip(), 1f))
            }
        }
        return row
    }

    /* ---------------------------------------------------- centre control */

    private fun centre(left: Boolean): View = when {
        left && profile == Profile.POINT -> {
            // No D-pad: a big LEFT and a smaller RIGHT mouse button.
            column().apply {
                addView(weightV(holdMouse("LEFT", "mouse button", DosboxNative.SDL_BUTTON_LEFT, COLOR_GOLD,
                    textColor = COLOR_TEXT_ON_GOLD), 1.6f))
                addView(weightV(holdMouse("RIGHT", "mouse button", DosboxNative.SDL_BUTTON_RIGHT, COLOR_KEY), 1f))
            }
        }
        left -> DpadView(context)                              // HYBRID / ACTION
        !left && profile == Profile.ACTION -> faceDiamond()    // face buttons
        else -> TrackballView(context)                         // HYBRID / POINT
    }

    /** The ACTION face-button diamond: ENTER top, USE left, JUMP right, FIRE
     *  bottom (biggest, gold). Positioned in a 3×3 grid. */
    private fun faceDiamond(): View {
        val grid = column()
        val topR = row(); topR.addView(spacer()); topR.addView(weight(holdKey("ENT", "", "enter", COLOR_KEY_DARK), 1f)); topR.addView(spacer())
        val midR = row()
        midR.addView(weight(holdKey("USE", "Space", "space", COLOR_KEY), 1f))
        midR.addView(spacer())
        midR.addView(weight(holdKey("JUMP", "Alt", "lalt", COLOR_KEY), 1f))
        val botR = row(); botR.addView(spacer()); botR.addView(weight(fireButton(), 1f)); botR.addView(spacer())
        grid.addView(weightV(topR, 1f))
        grid.addView(weightV(midR, 1f))
        grid.addView(weightV(botR, 1f))
        return grid
    }

    /* --------------------------------------------------------- bottom row */

    private fun bottomRow(left: Boolean): LinearLayout {
        val row = row()
        when {
            left && profile == Profile.POINT -> {
                row.addView(weight(holdKey("SHIFT", "+click", "lshift", COLOR_KEY_DARK), 1f))
                row.addView(weight(holdKey("CTRL", "+click", "lctrl", COLOR_KEY_DARK), 1f))
            }
            left -> {   // HYBRID / ACTION: D-pad output cycle + a right-hand chip
                row.addView(weight(arrowsChip(), 1f))
                if (profile == Profile.ACTION) {
                    row.addView(weight(turboChip(), 1f))
                } else {
                    row.addView(weight(fnChip(), 1f))
                }
            }
            !left && profile == Profile.ACTION -> {
                row.addView(weight(fnChip(), 1f))
                row.addView(weight(numberChip(), 1f))
            }
            else -> {   // HYBRID / POINT right bottom: Space / Enter
                row.addView(weight(holdKey("SPACE", "", "space", COLOR_KEY), 1f))
                row.addView(weight(holdKey("ENT", "", "enter", COLOR_KEY_DARK), 1f))
            }
        }
        return row
    }

    /* -------------------------------------------------- special chips */

    private fun cycleProfile() {
        val next = when (profile) {
            Profile.HYBRID -> Profile.ACTION; Profile.ACTION -> Profile.POINT; Profile.POINT -> Profile.HYBRID
        }
        setProfile(next, notify = true)
    }

    private fun showProfilePicker() {
        // The last entry opens the remapper instead of picking a profile — and
        // turns into the way out of it, so remap mode always has an exit.
        val items = arrayOf(
            "Hybrid — D-pad + trackball",
            "Action — D-pad + buttons",
            "Point & click — mouse",
            if (remapMode) "Done remapping" else "Remap buttons…"
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("Game profile")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setProfile(Profile.HYBRID, notify = true)
                    1 -> setProfile(Profile.ACTION, notify = true)
                    2 -> setProfile(Profile.POINT, notify = true)
                    else -> if (remapMode) exitRemapMode() else enterRemapMode()
                }
            }
            .show()
    }

    private fun arrowsChip(): View {
        val label = when (dpad) { Dpad.ARROWS -> "ARROWS"; Dpad.NUMPAD -> "NUMPAD"; Dpad.WASD -> "WASD" }
        return tapChip(label, "⇄ d-pad", COLOR_KEY_DARK) {
            val next = when (dpad) { Dpad.ARROWS -> Dpad.NUMPAD; Dpad.NUMPAD -> Dpad.WASD; Dpad.WASD -> Dpad.ARROWS }
            setDpad(next, notify = true)   // rebuilds the bar, and relabels this chip
        }
    }

    private var turbo = false
    private fun turboChip(): View {
        val chip = chip("TURBO", "auto-fire", if (turbo) COLOR_KEY_ARMED else COLOR_KEY_DARK)
        chip.setOnClickListener { turbo = !turbo; rebuild(force = true) }
        return chip
    }

    private fun speedChip(): View =
        tapChip(speedLabel(), "speed", COLOR_KEY_DARK) {
            trackSpeed = when (trackSpeed) { 0.5f -> 1.0f; 1.0f -> 2.0f; else -> 0.5f }
            rebuild(force = true)
        }
    private fun speedLabel(): String = when (trackSpeed) { 0.5f -> "0.5×"; 2.0f -> "2×"; else -> "1×" }

    /** DRAG lock: tap to hold the left button, tap again to drop it. */
    private var dragLocked = false
    private fun dragLockChip(): View {
        val chip = chip("DRAG", "lock", if (dragLocked) COLOR_KEY_ARMED else COLOR_KEY)
        chip.setOnClickListener {
            dragLocked = !dragLocked
            DosboxNative.nativePushMouseButton(DosboxNative.SDL_BUTTON_LEFT, dragLocked)
            rebuild(force = true)
        }
        return chip
    }

    private fun dblClickChip(): View = tapChip("DBL", "click", COLOR_KEY) {
        clickLeftOnce(); handler.postDelayed({ clickLeftOnce() }, 60)
    }

    private fun clickLeftOnce() {
        DosboxNative.nativePushMouseButton(DosboxNative.SDL_BUTTON_LEFT, true)
        handler.postDelayed({ DosboxNative.nativePushMouseButton(DosboxNative.SDL_BUTTON_LEFT, false) }, 30)
    }

    /** Fn / 1–9 open a transient strip at the bottom of the screen. */
    private fun fnChip(): View = tapChip("Fn", "F1–F12", COLOR_KEY_DARK) {
        toggleStrip((1..12).map { "F$it" to "f$it" })
    }
    private fun numberChip(): View = tapChip("1–9", "weapons", COLOR_KEY_DARK) {
        toggleStrip((1..9).map { "$it" to "$it" })
    }

    private var strip: LinearLayout? = null
    private fun toggleStrip(keys: List<Pair<String, String>>) {
        strip?.let { removeView(it); strip = null; return }
        val bar = row().apply {
            setBackgroundColor(COLOR_PANEL)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((label, name) in keys) bar.addView(weight(tapKey(label, "", name, COLOR_KEY), 1f))
        bar.addView(weight(tapChip("✕", "", COLOR_KEY_DARK) { strip?.let { removeView(it) }; strip = null }, 0.6f))
        strip = bar
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Keep it clear of the pads by insetting to the centre picture width.
            val inset = minOf(builtBarWidth, dp(DP_MAX_PAD_WIDTH)) + dp(6)
            marginStart = inset; marginEnd = inset
        })
    }

    /* ------------------------------------------- the FIRE button + turbo */

    @SuppressLint("ClickableViewAccessibility")
    private fun fireButton(): View {
        val slot = slotFor("lctrl")
        val effKey = remaps[slot] ?: "lctrl"
        // FIRE keeps its name; the sub-label shows the key it fires.
        val chip = chip("FIRE", keyLabel(effKey), COLOR_GOLD, textColor = COLOR_TEXT_ON_GOLD)
        if (remapMode) {
            markRemappable(chip, COLOR_GOLD)
            chip.setOnClickListener { openRemapPicker(slot, "FIRE") }
            return chip
        }
        chip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    chip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (turbo) {
                        val r = object : Runnable {
                            override fun run() {
                                push(effKey, true); push(effKey, false)
                                handler.postDelayed(this, TURBO_PERIOD_MS)
                            }
                        }
                        // Replace any previous loop, so a stray one can never pile
                        // up (the field is cancelled by releaseKeys on a rebuild).
                        stopAutofire()
                        autofire = r; handler.post(r)
                    } else push(effKey, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopAutofire()
                    push(effKey, false)
                }
            }
            true
        }
        return chip
    }

    /* --------------------------------------------------- button factories */

    /**
     * A key held while the finger is down (most Game Mode controls). [defaultKey]
     * is the key it sends out of the box; a per-game remap can change it. In
     * remap mode a tap opens the key picker instead of sending anything.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdKey(label: String, sub: String, defaultKey: String, color: Int): View {
        val slot = slotFor(defaultKey)
        val effKey = remaps[slot] ?: defaultKey
        val shown = if (remaps.containsKey(slot)) keyLabel(effKey) else label
        val chip = chip(shown, sub, color)
        if (remapMode) {
            markRemappable(chip, color)
            chip.setOnClickListener { openRemapPicker(slot, label) }
            return chip
        }
        chip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    chip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    chip.background = keyBg(COLOR_KEY_PRESSED)
                    push(effKey, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    chip.background = keyBg(color)
                    push(effKey, false)
                }
            }
            true
        }
        return chip
    }

    /** A key for a quick tap (Y/N/ESC) that also feeds the Konami detector. */
    @SuppressLint("ClickableViewAccessibility")
    private fun tapKey(label: String, sub: String, keyName: String, color: Int, konamiToken: String? = null): View {
        val chip = chip(label, sub, color)
        chip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    chip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    chip.background = keyBg(COLOR_KEY_PRESSED)
                    push(keyName, true)
                    konamiToken?.let { konami.feed(it) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    chip.background = keyBg(color)
                    push(keyName, false)
                }
            }
            true
        }
        return chip
    }

    /** A mouse button held while the finger is down. */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdMouse(label: String, sub: String, button: Int, color: Int, textColor: Int = COLOR_TEXT): View {
        val chip = chip(label, sub, color, textColor)
        chip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    chip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    chip.background = keyBg(COLOR_KEY_PRESSED)
                    DosboxNative.nativePushMouseButton(button, true)
                    downMouseButtons.add(button)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    chip.background = keyBg(color)
                    DosboxNative.nativePushMouseButton(button, false)
                    downMouseButtons.remove(button)
                }
            }
            true
        }
        return chip
    }

    /** A button that runs an action on tap (not a DOS key — stays accessible). */
    private fun tapChip(label: String, sub: String, color: Int, action: () -> Unit): View {
        val chip = chip(label, sub, color)
        chip.setOnClickListener { action() }
        return chip
    }

    /* ------------------------------------------------------- remapping */

    /**
     * The remap slot of a button: "<profile>:<default key>". The default keys
     * are unique inside a profile, so a slot names exactly one button — and
     * the same slot in another profile is a different button.
     */
    private fun slotFor(defaultKey: String): String = "$profile:$defaultKey"

    /** A short display name for a key name ("lctrl" -> "CTRL", "comma" -> ","). */
    private fun keyLabel(keyName: String): String = when (keyName) {
        "lctrl", "rctrl" -> "CTRL"
        "lalt", "ralt" -> "ALT"
        "lshift", "rshift" -> "SHIFT"
        "space" -> "SPACE"
        "enter" -> "ENT"
        "esc" -> "ESC"
        "tab" -> "TAB"
        "bspace" -> "BKSP"
        "capslock" -> "CAPS"
        "comma" -> ","
        "period" -> "."
        "slash" -> "/"
        "minus" -> "-"
        "equals" -> "="
        "semicolon" -> ";"
        "quote" -> "'"
        "backslash" -> "\\"
        "grave" -> "`"
        "up" -> "↑"
        "down" -> "↓"
        "left" -> "←"
        "right" -> "→"
        else -> if (keyName.startsWith("kp_")) "KP" + keyName.substring(3)
                else keyName.uppercase()
    }

    /**
     * Marks a button as remappable while remap mode is on: a coloured outline.
     *
     * The outline has to stay visible on the chip it marks, so its colour
     * depends on the chip it goes on: gold on the dark chips, and a dark
     * brown on the gold FIRE chip. A gold outline on a gold chip would be
     * invisible - the button would look like any other one. [fill] is the
     * colour the chip is filled with.
     */
    private fun markRemappable(chip: View, fill: Int) {
        (chip.background as? GradientDrawable)?.setStroke(maxOf(2, dp(2)), outlineFor(fill))
    }

    /**
     * Opens the key picker for a remappable button. The first entry resets the
     * button back to the key it ships with; the rest are the curated choices.
     */
    private fun openRemapPicker(slot: String, label: String) {
        val items = ArrayList<String>(REMAP_CHOICES.size + 1)
        items.add("Reset to default")
        for ((disp, _) in REMAP_CHOICES) items.add(disp)
        android.app.AlertDialog.Builder(context)
            .setTitle("Remap $label")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) remaps.remove(slot)
                else remaps[slot] = REMAP_CHOICES[which - 1].second
                rebuild(force = true)
                listener?.onRemapsChanged(remaps)
            }
            .show()
    }

    /**
     * Enters remap mode: the remappable buttons get a gold outline and a banner
     * explains how to leave again. Reached from the profile button's long-press.
     */
    private fun enterRemapMode() {
        remapMode = true
        rebuild(force = true)
        showRemapBanner()
    }

    /**
     * Puts the gamepad into remap mode.
     *
     * The user reaches it from the profile button's long-press menu; this is
     * the same code path, exposed so that a test can put the gamepad into
     * that mode and look at the outline it draws.
     */
    @VisibleForTesting
    fun enterRemapModeForTest() {
        enterRemapMode()
    }

    /** Leaves remap mode — the buttons go back to sending their keys. */
    fun exitRemapMode() {
        if (!remapMode) return
        remapMode = false
        rebuild(force = true)
    }

    private var remapBanner: View? = null

    /**
     * The banner that explains remap mode and ends it. It sits at the top of the
     * picture area, mirroring the Fn/1-9 strip at the bottom, so it never covers
     * a control on either pad.
     */
    private fun showRemapBanner() {
        if (remapBanner != null) return
        val bar = row().apply {
            setBackgroundColor(COLOR_PANEL)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { exitRemapMode() }
        }
        bar.addView(weight(tapChip("DONE", "leave remap", COLOR_KEY_DARK) { exitRemapMode() }, 1f))
        bar.addView(weight(TextView(context).apply {
            text = "Tap a button to remap"
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            isSingleLine = true
        }, 3f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Keep it clear of the pads by insetting to the centre picture width.
            val inset = minOf(builtBarWidth, dp(DP_MAX_PAD_WIDTH)) + dp(6)
            marginStart = inset; marginEnd = inset
        })
        remapBanner = bar
    }

    /* ------------------------------------------------------- key plumbing */

    private fun push(keyName: String, pressed: Boolean) {
        val code = scancodeCache.getOrPut(keyName) { DosboxNative.nativeScancodeFromName(keyName) }
        if (code == 0) { Log.w(TAG, "unknown key $keyName"); return }
        if (!DosboxNative.nativePushKey(code, pressed)) return
        if (pressed) downKeys.add(keyName) else downKeys.remove(keyName)
    }

    /* ------------------------------------------------------ view helpers */

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isMotionEventSplittingEnabled = true
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isMotionEventSplittingEnabled = true
    }

    private fun spacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    }

    /** Weighted child of a HORIZONTAL row (shares the width). */
    private fun <T : View> weight(view: T, w: Float): T {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        view.layoutParams = lp
        return view
    }

    /** Weighted child of a VERTICAL column (shares the height). */
    private fun <T : View> weightV(view: T, w: Float): T {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, w)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        view.layoutParams = lp
        return view
    }

    /** A labelled key chip: a big label with an optional dim sub-label. */
    private fun chip(
        label: String,
        sub: String,
        color: Int,
        textColor: Int = COLOR_TEXT,
        subTextColor: Int? = null
    ): LinearLayout {
        val chip = LinearLayout(context)
        chip.orientation = LinearLayout.VERTICAL
        chip.gravity = Gravity.CENTER
        chip.background = keyBg(color)
        chip.isMotionEventSplittingEnabled = true
        val t = TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        chip.addView(t)
        if (sub.isNotEmpty()) {
            // On a light (gold or green) chip the dim grey sub-label is
            // unreadable; use a dark sub-colour there so "game mode" etc. stay
            // legible.
            val subColor = subTextColor
                ?: if (textColor == COLOR_TEXT) COLOR_TEXT_DIM else COLOR_SUB_ON_GOLD
            chip.addView(TextView(context).apply {
                text = sub
                setTextColor(subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = false
            })
        }
        return chip
    }

    private fun keyBg(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(8).toFloat()
        setStroke(maxOf(1, dp(1)), 0xFF000000.toInt())
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /* ================================================================ D-pad */

    /**
     * An 8-way D-pad. A touch inside the ring picks one of eight octants; the
     * four cardinals press one key, the diagonals press the pair for the current
     * output mode (numpad diagonals are the single keys 7/9/1/3). Cardinal
     * presses also feed the Konami detector.
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class DpadView(ctx: Context) : View(ctx) {
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_PLATE }
        private val armP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_KEY }
        private val hubP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_KEY_DARK }
        private val arrowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_TEXT }
        private val hiP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_GOLD }
        private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_GOLD_GLOW }
        private val cornerP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = COLOR_KEY; strokeCap = Paint.Cap.ROUND
        }
        private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_DIM; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private val rf = RectF()
        private val tri = Path()
        private var active = -1   // octant 0..7 or −1

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy) - dp(2)
            canvas.drawCircle(cx, cy, r, plate)

            val armHalf = r * 0.20f
            val reach = r * 0.82f
            val round = armHalf * 0.6f
            // The raised cross: a vertical and a horizontal rounded bar.
            rf.set(cx - armHalf, cy - reach, cx + armHalf, cy + reach); canvas.drawRoundRect(rf, round, round, armP)
            rf.set(cx - reach, cy - armHalf, cx + reach, cy + armHalf); canvas.drawRoundRect(rf, round, round, armP)

            // The active direction, lit gold with a soft glow — a cardinal arm
            // end, or (pulled a touch inward) a diagonal between two arms, so a
            // tap on up-right reads as clearly as a tap on up.
            if (active >= 0) {
                val a = Math.toRadians((active * 45).toDouble())
                val dist = if (active % 2 == 0) reach * 0.80f else reach * 0.66f
                val hx = cx + dist * Math.cos(a).toFloat()
                val hy = cy - dist * Math.sin(a).toFloat()
                canvas.drawCircle(hx, hy, armHalf * 1.55f, glowP)
                canvas.drawCircle(hx, hy, armHalf * 0.95f, hiP)
            }

            // Diagonal corner brackets — the 8-way hint (one L framing each corner).
            cornerP.strokeWidth = maxOf(2f, r * 0.035f)
            val cr = r * 0.90f; val len = r * 0.16f
            for (deg in intArrayOf(45, 135, 225, 315)) {
                val a = Math.toRadians(deg.toDouble())
                val px = cx + cr * Math.cos(a).toFloat(); val py = cy - cr * Math.sin(a).toFloat()
                val hx = if (px > cx) px - len else px + len
                val vy = if (py > cy) py - len else py + len
                canvas.drawLine(px, py, hx, py, cornerP)
                canvas.drawLine(px, py, px, vy, cornerP)
            }

            // Arrowheads on the four arms.
            val ah = armHalf * 0.80f
            arrowhead(canvas, cx, cy - reach + ah * 0.6f, ah, 0)   // ▲
            arrowhead(canvas, cx, cy + reach - ah * 0.6f, ah, 2)   // ▼
            arrowhead(canvas, cx - reach + ah * 0.6f, cy, ah, 3)   // ◀
            arrowhead(canvas, cx + reach - ah * 0.6f, cy, ah, 1)   // ▶

            // The centre hub with the "8-WAY" label.
            canvas.drawCircle(cx, cy, r * 0.28f, hubP)
            textP.textSize = r * 0.17f
            canvas.drawText("8-", cx, cy - r * 0.01f, textP)
            canvas.drawText("WAY", cx, cy + r * 0.18f, textP)
        }

        /** A filled triangle pointing in [dir] (0=up,1=right,2=down,3=left). */
        private fun arrowhead(c: Canvas, x: Float, y: Float, s: Float, dir: Int) {
            tri.reset()
            when (dir) {
                0 -> { tri.moveTo(x, y - s); tri.lineTo(x - s, y + s); tri.lineTo(x + s, y + s) }
                2 -> { tri.moveTo(x, y + s); tri.lineTo(x - s, y - s); tri.lineTo(x + s, y - s) }
                3 -> { tri.moveTo(x - s, y); tri.lineTo(x + s, y - s); tri.lineTo(x + s, y + s) }
                else -> { tri.moveTo(x + s, y); tri.lineTo(x - s, y - s); tri.lineTo(x - s, y + s) }
            }
            tri.close(); c.drawPath(tri, arrowP)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val cx = width / 2f; val cy = height / 2f
                    val dx = event.x - cx; val dy = event.y - cy
                    val r = minOf(cx, cy)
                    val oct = if (hypot(dx.toDouble(), dy.toDouble()) < r * DEADZONE) -1 else octantOf(dx, -dy)
                    if (oct != active) setActive(oct)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setActive(-1)
            }
            return true
        }

        private fun setActive(oct: Int) {
            if (oct == active) return
            // Release the old octant's keys, press the new one's.
            keysFor(active).forEach { push(it, false) }
            active = oct
            keysFor(oct).forEach { push(it, true) }
            // Buzz whenever a real direction is entered — cardinals AND diagonals,
            // so an up-right tap feels the same as an up tap.
            if (oct >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cardinalToken(oct)?.let { konami.feed(it) }
            invalidate()
        }

        private fun keysFor(oct: Int): List<String> {
            if (oct < 0) return emptyList()
            return when (dpad) {
                Dpad.ARROWS -> ARROWS_OCT[oct]
                Dpad.NUMPAD -> NUMPAD_OCT[oct]
                Dpad.WASD -> WASD_OCT[oct]
            }
        }
    }

    /* ============================================================ trackball */

    /**
     * Relative pointer: dragging moves the cursor like a laptop touchpad (via
     * [DosboxNative.nativePushMouseMotion]); a quick tap that barely moves is a
     * left click. The pointer speed is scaled by [trackSpeed].
     */
    @SuppressLint("ClickableViewAccessibility")
    private inner class TrackballView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_KEY_DARK }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = COLOR_KEY_PRESSED; strokeWidth = dp(2).toFloat() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TEXT_DIM; textAlign = Paint.Align.CENTER }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_GOLD }
        private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = COLOR_GOLD_GLOW }
        private var lastX = 0f; private var lastY = 0f
        private var downTime = 0L; private var moved = 0f

        // Reactive state: whether a finger is on the ball, where it is, and how
        // far the "ball" has rolled (spins the bezel, like the design's note).
        private var touching = false
        private var fx = 0f; private var fy = 0f
        private var spin = 0f

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy) - dp(2)
            // The ball lights up a little while a finger is on it.
            fill.color = if (touching) COLOR_KEY else COLOR_KEY_DARK
            canvas.drawCircle(cx, cy, r, fill)
            // The bezel ticks, rotated by how far the ball has been rolled.
            tick.color = if (touching) COLOR_GOLD else COLOR_KEY_PRESSED
            var a = spin.toDouble()
            val end = a + 360
            while (a < end) {
                val rad = Math.toRadians(a)
                val x1 = cx + (r * 0.86f) * Math.cos(rad).toFloat(); val y1 = cy + (r * 0.86f) * Math.sin(rad).toFloat()
                val x2 = cx + (r * 0.97f) * Math.cos(rad).toFloat(); val y2 = cy + (r * 0.97f) * Math.sin(rad).toFloat()
                canvas.drawLine(x1, y1, x2, y2, tick); a += 18
            }
            // A gold dot under the finger — the clearest "it reacted" signal.
            if (touching) {
                val d = hypot((fx - cx).toDouble(), (fy - cy).toDouble())
                val dotX: Float; val dotY: Float
                if (d > r * 0.9f && d > 0) {   // keep the dot inside the ball
                    dotX = cx + ((fx - cx) / d * r * 0.9f).toFloat(); dotY = cy + ((fy - cy) / d * r * 0.9f).toFloat()
                } else { dotX = fx; dotY = fy }
                canvas.drawCircle(dotX, dotY, r * 0.22f, glowP)
                canvas.drawCircle(dotX, dotY, r * 0.12f, dotP)
            }
            label.textSize = r * 0.22f
            canvas.drawText("TRACKBALL", cx, cy + label.textSize / 3, label)
            label.textSize = r * 0.14f
            canvas.drawText("tap = click", cx, cy + r * 0.4f, label)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y; downTime = System.currentTimeMillis(); moved = 0f
                    touching = true; fx = event.x; fy = event.y
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    fx = event.x; fy = event.y
                    moved += abs(dx) + abs(dy)
                    spin = (spin + (dx + dy) * 0.6f) % 360f
                    val mx = (dx * trackSpeed).roundToInt(); val my = (dy * trackSpeed).roundToInt()
                    if (mx != 0 || my != 0) DosboxNative.nativePushMouseMotion(mx, my)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val quick = System.currentTimeMillis() - downTime < TAP_MS
                    if (event.actionMasked == MotionEvent.ACTION_UP && quick && moved < dp(TAP_SLOP_DP)) {
                        clickLeftOnce()
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    touching = false; invalidate()
                }
            }
            return true
        }
    }

    /* -------------------------------------------------- Konami detector */

    /**
     * Fires [onMatch] when the last N fed tokens equal [sequence]. Used both
     * ways: the keyboard watches for the real code (↑↑↓↓←→←→BA) to enter Game
     * Mode, and here the D-pad + X/Y watch for ↑↑↓↓←→←→XY (X/Y standing in for
     * B/A) to go back.
     */
    class KonamiDetector(private val sequence: List<String>, private val onMatch: () -> Unit) {
        private val buffer = ArrayDeque<String>()
        fun feed(token: String) {
            buffer.addLast(token)
            while (buffer.size > sequence.size) buffer.removeFirst()
            if (buffer.size == sequence.size && buffer.toList() == sequence) {
                buffer.clear(); onMatch()
            }
        }
    }

    companion object {
        private const val TAG = "whereq-dosbox"

        /** Reverse Konami in Game Mode: arrows from the D-pad, then X,Y for B,A. */
        val GAME_EXIT_SEQUENCE = listOf("up", "up", "down", "down", "left", "right", "left", "right", "x", "y")

        /**
         * The real Konami code on the keyboard, to enter Game Mode. The B and A
         * must be *capital*: the keyboard feeds the detector the case the letter
         * is actually typed in, so Caps Lock has to be on (double-tap Shift)
         * before the code is typed.
         */
        val KEYBOARD_ENTER_SEQUENCE = listOf("up", "up", "down", "down", "left", "right", "left", "right", "B", "A")

        /**
         * The keys a Game Mode button can be remapped to, as (display, key name).
         * Curated down to the keys DOS games actually read — the action keys, the
         * function keys, the letters, the digits, the arrows and a few symbols.
         */
        private val REMAP_CHOICES: List<Pair<String, String>> = listOf(
            "SPACE" to "space", "ENT" to "enter", "ESC" to "esc", "TAB" to "tab",
            "CTRL" to "lctrl", "ALT" to "lalt", "SHIFT" to "lshift", "BKSP" to "bspace",
        ) + (1..12).map { "F$it" to "f$it" } +
            ('A'..'Z').map { "$it" to it.lowercase() } +
            ('0'..'9').map { "$it" to "$it" } +
            listOf("↑" to "up", "↓" to "down", "←" to "left", "→" to "right") +
            listOf(
                "," to "comma", "." to "period", "/" to "slash", "-" to "minus",
                "=" to "equals", ";" to "semicolon", "'" to "quote", "\\" to "backslash",
            )

        private const val DEADZONE = 0.30f
        private const val TAP_MS = 220L
        private const val TAP_SLOP_DP = 10
        private const val TURBO_PERIOD_MS = 70L

        // Octants: 0=E,1=NE,2=N,3=NW,4=W,5=SW,6=S,7=SE.
        private fun octantOf(dx: Float, dy: Float): Int {
            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            if (deg < 0) deg += 360.0
            return (((deg + 22.5) / 45).toInt()) % 8
        }
        private fun cardinalToken(oct: Int): String? = when (oct) {
            0 -> "right"; 2 -> "up"; 4 -> "left"; 6 -> "down"; else -> null
        }

        private val ARROWS_OCT = arrayOf(
            listOf("right"), listOf("up", "right"), listOf("up"), listOf("up", "left"),
            listOf("left"), listOf("down", "left"), listOf("down"), listOf("down", "right"),
        )
        private val WASD_OCT = arrayOf(
            listOf("d"), listOf("w", "d"), listOf("w"), listOf("w", "a"),
            listOf("a"), listOf("s", "a"), listOf("s"), listOf("s", "d"),
        )
        private val NUMPAD_OCT = arrayOf(
            listOf("kp_6"), listOf("kp_9"), listOf("kp_8"), listOf("kp_7"),
            listOf("kp_4"), listOf("kp_1"), listOf("kp_2"), listOf("kp_3"),
        )

        private const val COLOR_PANEL = 0xD0202024.toInt()
        private const val COLOR_TOOLBAR = 0xC0202024.toInt()
        @VisibleForTesting
        internal const val COLOR_KEY = 0xFF3C4043.toInt()
        @VisibleForTesting
        internal const val COLOR_KEY_DARK = 0xFF2A2D30.toInt()
        /** The D-pad base plate — a touch lighter than the black letterbox. */
        private const val COLOR_PLATE = 0xFF303338.toInt()
        private const val COLOR_KEY_PRESSED = 0xFF5F6368.toInt()
        @VisibleForTesting
        internal const val COLOR_KEY_ARMED = 0xFF1A73E8.toInt()
        @VisibleForTesting
        internal const val COLOR_GOLD = 0xFFE0A81E.toInt()

        /** The SAVE chip: the colour a save state carries everywhere else. */
        private const val COLOR_SAVE = 0xFF4CAF50.toInt()

        /** Dark text on the green SAVE chip. */
        private const val COLOR_TEXT_ON_SAVE = 0xFF0C2410.toInt()
        private const val COLOR_SUB_ON_SAVE = 0xCC0C2410.toInt()
        /** Translucent gold for the press/touch glow under the D-pad + trackball. */
        private const val COLOR_GOLD_GLOW = 0x66E0A81E.toInt()
        private const val COLOR_TEXT = 0xFFE8EAED.toInt()
        private const val COLOR_TEXT_DIM = 0xFF9AA0A6.toInt()
        /** Title / sub text on the gold chips — dark but high-contrast. */
        @VisibleForTesting
        internal const val COLOR_TEXT_ON_GOLD = 0xFF241700.toInt()
        private const val COLOR_SUB_ON_GOLD = 0xCC241700.toInt()

        /**
         * The outline colour that shows on a chip filled with [fill]: gold on a
         * dark chip, the dark text colour on a light one.
         *
         * The rule exists because the FIRE chip is filled with the gold, and a
         * gold outline on a gold chip is invisible. It is a companion function
         * so that the rule can be tested on the JVM, without a device.
         */
        @VisibleForTesting
        internal fun outlineFor(fill: Int): Int =
            if (isLight(fill)) COLOR_TEXT_ON_GOLD else COLOR_GOLD

        /**
         * Whether a colour is light enough to need a dark outline.
         *
         * The test is the perceived luminance (ITU-R BT.601 weights), which is
         * what the eye reads as "bright" - a plain average would call the amber
         * gold dark and give it a gold outline again.
         */
        @VisibleForTesting
        internal fun isLight(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000 > 140
        }

        private const val DP_MIN_PAD_WIDTH = 120
        private const val DP_MAX_PAD_WIDTH = 230
    }
}
