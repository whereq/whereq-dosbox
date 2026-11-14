package com.whereq.dosbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On-screen keyboard for the DOSBox shell.
 *
 * How a key press reaches DOSBox:
 *  1. the key is turned into an SDL key event carrying the right
 *     `SDL_Scancode` and pushed into SDL's event queue by
 *     [DosboxNative.nativePushKey];
 *  2. DOSBox's `GFX_Events()` hands the event to the mapper
 *     (`src/gui/sdl_mapper.cpp`), which indexes its bind list by the scancode
 *     and activates the matching `CKeyEvent`;
 *  3. that event calls `KEYBOARD_AddKey()`, which fills the BIOS keyboard buffer
 *     the DOS shell reads.
 *
 * So no DOSBox patch is needed — the virtual keyboard is just another producer
 * of SDL key events, exactly like the hardware keyboard.
 *
 * **Layout.** This view is a transparent full-screen overlay on top of the SDL
 * surface. The DOS picture is 4:3 and letterboxed, so in landscape there are
 * black bars down the left and right; the always-on control keys live in those
 * bars (two vertical columns), leaving the whole DOS picture in the middle
 * unobstructed. When the bars are too narrow (portrait), the controls fall back
 * to a slim bottom bar. The empty centre does not intercept touches, so a tap
 * there still reaches DOSBox as a mouse click. A full QWERTY panel folds up from
 * the bottom for typing.
 *
 * Modifier keys (Shift, Ctrl, Alt) are *sticky*: tapping one arms it, it stays
 * pressed until the next ordinary key is typed, which is the behaviour a touch
 * screen can offer without a second finger.
 */
class VirtualKeyboardView(context: Context) : FrameLayout(context) {

    /**
     * Told when the user taps the launcher button.
     *
     * The keyboard does not know about the bundle launcher; it only reports that
     * the button was pressed.
     */
    fun interface OnFilesRequestedListener {
        fun onFilesRequested()
    }

    /** Set by the activity; may be null, then the button does nothing. */
    private var launcherListener: OnFilesRequestedListener? = null

    /** Installs the listener that is told about a tap on the launcher button. */
    fun setOnFilesRequestedListener(listener: OnFilesRequestedListener?) {
        launcherListener = listener
    }

    /** key name -> numeric SDL scancode. */
    private val scancodes = HashMap<String, Int>()

    /** key name of a sticky modifier -> is it currently pressed. */
    private val armed = HashMap<String, Boolean>()

    /** key name -> the view, so the armed state can be shown. */
    private val modifierViews = HashMap<String, TextView>()

    private lateinit var panel: LinearLayout
    private lateinit var symbolScroll: HorizontalScrollView
    private var toggleButton: TextView? = null
    private var panelVisible = false

    /** The current control views (edge columns or bottom bar), so they can be
     *  swapped when the orientation / letterbox changes. */
    private val controlViews = ArrayList<View>()

    /** null = not built yet; true = side columns; false = bottom bar. */
    private var usingSideBars: Boolean? = null

    init {
        // The overlay itself must never swallow a touch over the DOS picture:
        // it is not clickable, so a tap on the empty centre falls through to the
        // SDL surface below and becomes a DOS mouse click.
        isClickable = false
        buildPanel()
        // The control keys are laid out in onSizeChanged(), once the letterbox
        // geometry (and thus the black-bar width) is known.
    }

    /* ------------------------------------------------------------- layout */

    /** Builds the foldable full-size keyboard (a bottom-centre overlay). */
    private fun buildPanel() {
        panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(4), dp(4), dp(4), dp(4))
        panel.setBackgroundColor(COLOR_PANEL)

        panel.addView(
            scrollableRow(
                arrayOf(
                    arrayOf("Esc", "esc", "f", "k"),
                    arrayOf("F1", "f1", "f", "k"),
                    arrayOf("F2", "f2", "f", "k"),
                    arrayOf("F3", "f3", "f", "k"),
                    arrayOf("F4", "f4", "f", "k"),
                    arrayOf("F5", "f5", "f", "k"),
                    arrayOf("F6", "f6", "f", "k"),
                    arrayOf("F7", "f7", "f", "k"),
                    arrayOf("F8", "f8", "f", "k"),
                    arrayOf("F9", "f9", "f", "k"),
                    arrayOf("F10", "f10", "f", "k"),
                    arrayOf("F11", "f11", "f", "k"),
                    arrayOf("F12", "f12", "f", "k"),
                    arrayOf("PrtSc", "printscreen", "f", "k"),
                    arrayOf("Pause", "pause", "f", "k"),
                    arrayOf("Ins", "insert", "f", "k"),
                    arrayOf("Del", "delete", "f", "k"),
                    arrayOf("Home", "home", "f", "k"),
                    arrayOf("End", "end", "f", "k"),
                    arrayOf("PgUp", "pageup", "f", "k"),
                    arrayOf("PgDn", "pagedown", "f", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("`", "grave", "1", "k"),
                    arrayOf("1", "1", "1", "k"),
                    arrayOf("2", "2", "1", "k"),
                    arrayOf("3", "3", "1", "k"),
                    arrayOf("4", "4", "1", "k"),
                    arrayOf("5", "5", "1", "k"),
                    arrayOf("6", "6", "1", "k"),
                    arrayOf("7", "7", "1", "k"),
                    arrayOf("8", "8", "1", "k"),
                    arrayOf("9", "9", "1", "k"),
                    arrayOf("0", "0", "1", "k"),
                    arrayOf("-", "minus", "1", "k"),
                    arrayOf("=", "equals", "1", "k"),
                    arrayOf("Bks", "bspace", "2", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Tab", "tab", "1.5", "k"),
                    arrayOf("q", "q", "1", "k"),
                    arrayOf("w", "w", "1", "k"),
                    arrayOf("e", "e", "1", "k"),
                    arrayOf("r", "r", "1", "k"),
                    arrayOf("t", "t", "1", "k"),
                    arrayOf("y", "y", "1", "k"),
                    arrayOf("u", "u", "1", "k"),
                    arrayOf("i", "i", "1", "k"),
                    arrayOf("o", "o", "1", "k"),
                    arrayOf("p", "p", "1", "k"),
                    arrayOf("[", "lbracket", "1", "k"),
                    arrayOf("]", "rbracket", "1", "k"),
                    arrayOf("\\", "backslash", "1.5", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Caps", "capslock", "1.8", "k"),
                    arrayOf("a", "a", "1", "k"),
                    arrayOf("s", "s", "1", "k"),
                    arrayOf("d", "d", "1", "k"),
                    arrayOf("f", "f", "1", "k"),
                    arrayOf("g", "g", "1", "k"),
                    arrayOf("h", "h", "1", "k"),
                    arrayOf("j", "j", "1", "k"),
                    arrayOf("k", "k", "1", "k"),
                    arrayOf("l", "l", "1", "k"),
                    arrayOf(";", "semicolon", "1", "k"),
                    arrayOf("'", "quote", "1", "k"),
                    arrayOf("Ent", "enter", "2", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Shift", "lshift", "2.2", "m"),
                    arrayOf("z", "z", "1", "k"),
                    arrayOf("x", "x", "1", "k"),
                    arrayOf("c", "c", "1", "k"),
                    arrayOf("v", "v", "1", "k"),
                    arrayOf("b", "b", "1", "k"),
                    arrayOf("n", "n", "1", "k"),
                    arrayOf("m", "m", "1", "k"),
                    arrayOf(",", "comma", "1", "k"),
                    arrayOf(".", "period", "1", "k"),
                    arrayOf("/", "slash", "1", "k"),
                    arrayOf("Shift", "rshift", "2.2", "m"),
                    arrayOf("↑", "up", "1", "k"),
                )
            )
        )

        panel.addView(
            weightedRow(
                arrayOf(
                    arrayOf("Ctrl", "lctrl", "1.5", "m"),
                    arrayOf("Alt", "lalt", "1.3", "m"),
                    arrayOf("Space", "space", "6", "k"),
                    arrayOf("←", "left", "1", "k"),
                    arrayOf("↓", "down", "1", "k"),
                    arrayOf("→", "right", "1", "k"),
                )
            )
        )

        // The shifted characters of the US layout, for the keys that a phone
        // keyboard would otherwise hide.
        symbolScroll = scrollableRow(
            arrayOf(
                arrayOf("~", "grave", "f", "s"),
                arrayOf("!", "1", "f", "s"),
                arrayOf("@", "2", "f", "s"),
                arrayOf("#", "3", "f", "s"),
                arrayOf("$", "4", "f", "s"),
                arrayOf("%", "5", "f", "s"),
                arrayOf("^", "6", "f", "s"),
                arrayOf("&", "7", "f", "s"),
                arrayOf("*", "8", "f", "s"),
                arrayOf("(", "9", "f", "s"),
                arrayOf(")", "0", "f", "s"),
                arrayOf("_", "minus", "f", "s"),
                arrayOf("+", "equals", "f", "s"),
                arrayOf("{", "lbracket", "f", "s"),
                arrayOf("}", "rbracket", "f", "s"),
                arrayOf("|", "backslash", "f", "s"),
                arrayOf(":", "semicolon", "f", "s"),
                arrayOf("\"", "quote", "f", "s"),
                arrayOf("<", "comma", "f", "s"),
                arrayOf(">", "period", "f", "s"),
                arrayOf("?", "slash", "f", "s"),
            )
        )
        symbolScroll.visibility = GONE
        panel.addView(symbolScroll)

        panel.visibility = GONE
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        addView(panel, lp)
    }

    /* ----------------------------------------------------------- controls */

    /** Keys for the left edge column (modifiers + editing). */
    private val leftColumnKeys = arrayOf(
        arrayOf("⌨", "", "toggle"),
        arrayOf("Esc", "esc", "k"),
        arrayOf("Tab", "tab", "k"),
        arrayOf("Ctrl", "lctrl", "m"),
        arrayOf("Alt", "lalt", "m"),
        arrayOf("Shift", "lshift", "m"),
        arrayOf("Bks", "bspace", "k"),
        arrayOf("«", "", "symbols"),
    )

    /** Keys for the right edge column (launcher + enter/space + arrows). */
    private val rightColumnKeys = arrayOf(
        arrayOf("File", "", "file"),
        arrayOf("Ent", "enter", "k"),
        arrayOf("Space", "space", "k"),
        arrayOf("↑", "up", "k"),
        arrayOf("↓", "down", "k"),
        arrayOf("←", "left", "k"),
        arrayOf("→", "right", "k"),
    )

    /** All keys, for the portrait bottom-bar fallback. */
    private val bottomBarKeys = arrayOf(
        arrayOf("⌨", "", "toggle"),
        arrayOf("File", "", "file"),
        arrayOf("Esc", "esc", "k"),
        arrayOf("Tab", "tab", "k"),
        arrayOf("Ctrl", "lctrl", "m"),
        arrayOf("Alt", "lalt", "m"),
        arrayOf("Shift", "lshift", "m"),
        arrayOf("Ent", "enter", "k"),
        arrayOf("Bks", "bspace", "k"),
        arrayOf("Space", "space", "k"),
        arrayOf("←", "left", "k"),
        arrayOf("↓", "down", "k"),
        arrayOf("→", "right", "k"),
        arrayOf("↑", "up", "k"),
        arrayOf("«", "", "symbols"),
    )

    /**
     * Rebuilds the control keys for the current geometry: two vertical columns
     * in the left/right letterbox bars when they are wide enough (landscape), or
     * a slim bottom bar otherwise (portrait).
     */
    private fun rebuildControls(w: Int, h: Int) {
        // The DOS picture is 4:3 and fills the height in landscape, so each side
        // bar is this wide. (Matches DOSBox's own letterbox clip.)
        val barWidth = if (w > h) (w - h * 4 / 3) / 2 else 0
        val sideBars = barWidth >= dp(56)

        // Keep a column no wider than needed, so keys stay a comfortable size and
        // sit at the very edge (the rest of the bar stays black).
        val columnWidth = minOf(barWidth, dp(132))

        if (usingSideBars == sideBars && controlViews.isNotEmpty()) {
            if (sideBars) {
                controlViews.forEach { it.layoutParams = (it.layoutParams as LayoutParams).apply { width = columnWidth } }
            }
            return
        }

        // Rebuild: the modifier views are about to be discarded.
        controlViews.forEach { removeView(it) }
        controlViews.clear()
        modifierViews.clear()
        armed.clear()
        toggleButton = null

        if (sideBars) {
            val left = buildColumn(leftColumnKeys)
            val right = buildColumn(rightColumnKeys)
            addView(left, LayoutParams(columnWidth, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })
            addView(right, LayoutParams(columnWidth, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            })
            controlViews.add(left)
            controlViews.add(right)
        } else {
            val bar = buildBottomBar()
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            })
            controlViews.add(bar)
        }
        usingSideBars = sideBars
    }

    /** A vertical column of full-width keys, centred vertically for thumb reach. */
    private fun buildColumn(keys: Array<Array<String>>): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_VERTICAL
        column.setPadding(dp(3), dp(3), dp(3), dp(3))
        column.setBackgroundColor(COLOR_TOOLBAR)
        for (k in keys) {
            column.addView(makeControlKey(k[0], k[1], "c", k[2], DP_KEY_HEIGHT))
        }
        return column
    }

    /** The portrait fallback: all control keys weighted across a bottom strip. */
    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.setBackgroundColor(COLOR_TOOLBAR)
        for (k in bottomBarKeys) {
            bar.addView(makeControlKey(k[0], k[1], "w1", k[2], DP_TOOL_KEY_HEIGHT))
        }
        return bar
    }

    /**
     * Builds a control key. [kind] may be an ordinary DOS key (`k`), a sticky
     * modifier (`m`), or one of the actions: `toggle` (fold the QWERTY panel),
     * `symbols` (the shifted-symbol row) or `file` (open the bundle launcher).
     */
    private fun makeControlKey(
        label: String,
        keyName: String,
        size: String,
        kind: String,
        heightDp: Int,
    ): TextView {
        return when (kind) {
            "toggle" -> makeButton(label, "", size, "k", heightDp).also {
                it.setOnClickListener { togglePanel() }
                toggleButton = it
            }
            "symbols" -> makeButton(label, "", size, "k", heightDp).also {
                it.setOnClickListener { toggleSymbols() }
            }
            "file" -> makeButton(label, "", size, "k", heightDp).also {
                it.setTextColor(COLOR_TEXT_ON_ACCENT)
                it.background = keyBackground(COLOR_KEY_ACCENT)
                it.setOnClickListener { launcherListener?.onFilesRequested() }
            }
            else -> makeKey(label, keyName, size, kind, heightDp)
        }
    }

    /* -------------------------------------------------------------- rows */

    /** A row whose keys share the available width. */
    private fun weightedRow(keys: Array<Array<String>>): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in keys) {
            row.addView(makeKey(k[0], k[1], "w" + k[2], k[3], DP_KEY_HEIGHT))
        }
        return row
    }

    /**
     * A row of fixed-width keys inside a horizontal scroll view, for the function
     * keys and the symbols.
     */
    private fun scrollableRow(keys: Array<Array<String>>): HorizontalScrollView {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in keys) {
            row.addView(makeKey(k[0], k[1], k[2], k[3], DP_KEY_HEIGHT))
        }
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        return scroll
    }

    /* -------------------------------------------------------------- keys */

    /**
     * Builds one key that reacts to touch.
     *
     * @param size `"w<weight>"` weighted, `"c"` full column width, `"f"` fixed.
     * @param kind `"k"` ordinary, `"m"` sticky modifier, `"s"` key that needs Shift.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(
        label: String,
        keyName: String,
        size: String,
        kind: String,
        heightDp: Int,
    ): TextView {
        val kindCode = kindOf(kind)
        val view = makeButton(label, keyName, size, kind, heightDp)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> keyDown(keyName, kindCode)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> keyUp(keyName, kindCode)
            }
            true
        }
        return view
    }

    private fun makeButton(
        label: String,
        keyName: String,
        size: String,
        kind: String,
        heightDp: Int,
    ): TextView {
        val view = TextView(context)
        view.text = label
        view.setTextColor(COLOR_TEXT)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        // A key label must never wrap — the keys are narrow.
        view.isSingleLine = true
        view.setPadding(dp(DP_KEY_PADDING), 0, dp(DP_KEY_PADDING), 0)
        view.background = keyBackground(COLOR_KEY)
        view.tag = keyName

        var weight = 0f
        val width = when {
            size.startsWith("w") -> {
                weight = size.substring(1).toFloat()
                0
            }
            size == "c" -> LinearLayout.LayoutParams.MATCH_PARENT
            else -> dp(DP_FIXED_KEY_WIDTH)
        }
        val lp = LinearLayout.LayoutParams(width, dp(heightDp))
        if (weight > 0) {
            lp.weight = weight
        }
        lp.rightMargin = dp(2)
        lp.leftMargin = dp(2)
        lp.topMargin = dp(2)
        lp.bottomMargin = dp(2)
        view.layoutParams = lp

        if ("m" == kind) {
            modifierViews[keyName] = view
            armed[keyName] = false
        }
        return view
    }

    private fun keyBackground(color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(color)
        drawable.cornerRadius = dp(6).toFloat()
        drawable.setStroke(maxOf(1, dp(1)), 0xFF000000.toInt())
        return drawable
    }

    /* ------------------------------------------------------------ events */

    /** A key went down. */
    private fun keyDown(keyName: String, kind: Int) {
        if (kind == KIND_MODIFIER) {
            val now = !isArmed(keyName)
            setArmed(keyName, now)
            push(keyName, now)
            refreshModifier(keyName)
            return
        }
        if (kind == KIND_SHIFTED) {
            // Shift is wrapped around the key so that the character comes out
            // shifted.
            push(SHIFT, true)
            push(keyName, true)
            return
        }
        push(keyName, true)
    }

    /** A key went up. */
    private fun keyUp(keyName: String, kind: Int) {
        if (kind == KIND_MODIFIER) {
            // Sticky: the modifier stays pressed until the next key is typed.
            return
        }
        if (kind == KIND_SHIFTED) {
            push(keyName, false)
            push(SHIFT, false)
            return
        }
        push(keyName, false)
        // A sticky modifier applies to exactly one character.
        releaseModifiers()
    }

    private fun isArmed(keyName: String): Boolean = armed[keyName] == true

    private fun setArmed(keyName: String, value: Boolean) {
        armed[keyName] = value
    }

    private fun releaseModifiers() {
        for (key in armed.keys.toList()) {
            if (armed[key] == true) {
                push(key, false)
                armed[key] = false
                refreshModifier(key)
            }
        }
    }

    private fun refreshModifier(keyName: String) {
        modifierViews[keyName]?.background =
            keyBackground(if (isArmed(keyName)) COLOR_KEY_ARMED else COLOR_KEY)
    }

    /** Pushes a key press or release into DOSBox. */
    private fun push(keyName: String, pressed: Boolean) {
        val scancode = scancodeOf(keyName)
        if (scancode == 0) {
            Log.w(TAG, "ignoring unknown key $keyName")
            return
        }
        if (!DosboxNative.nativePushKey(scancode, pressed)) {
            Log.w(TAG, "could not push " + keyName + (if (pressed) " down" else " up"))
        }
    }

    private fun scancodeOf(keyName: String): Int =
        scancodes.getOrPut(keyName) { DosboxNative.nativeScancodeFromName(keyName) }

    /* ------------------------------------------------------------ toggles */

    /** @return true when the full keyboard panel is unfolded. */
    fun isPanelVisible(): Boolean = panelVisible

    /** Folds the full keyboard panel away. */
    fun hidePanel() {
        if (!panelVisible) {
            return
        }
        togglePanel()
    }

    private fun togglePanel() {
        panelVisible = !panelVisible
        panel.visibility = if (panelVisible) VISIBLE else GONE
        toggleButton?.background = keyBackground(if (panelVisible) COLOR_KEY_ARMED else COLOR_KEY)
        toggleButton?.text = if (panelVisible) "⌨✕" else "⌨"
        if (panelVisible) {
            requestLayout()
        }
    }

    private fun toggleSymbols() {
        val visible = symbolScroll.visibility == VISIBLE
        symbolScroll.visibility = if (visible) GONE else VISIBLE
    }

    /**
     * Rebuilds the edge controls for the new size, and keeps the QWERTY panel
     * about as wide as the screen is short so its keys do not become absurdly
     * wide in landscape.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Defer the hierarchy change: adding child views *during* onSizeChanged
        // (a layout pass) leaves them unmeasured and invisible. post() runs the
        // rebuild after this pass, so the new columns are laid out normally.
        post {
            rebuildControls(width, height)
            val metrics = resources.displayMetrics
            val panelWidth = minOf(metrics.widthPixels, metrics.heightPixels) * 92 / 100
            val lp = panel.layoutParams as LayoutParams
            if (lp.width != panelWidth) {
                lp.width = panelWidth
                panel.layoutParams = lp
            }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val TAG = "whereq-dosbox"

        /** Ordinary key: pressed and released again straight away. */
        private const val KIND_KEY = 0

        /** Sticky modifier: tapping it presses/releases the real modifier key. */
        private const val KIND_MODIFIER = 1

        /** Key that needs Shift, e.g. "!" — Shift is wrapped around it. */
        private const val KIND_SHIFTED = 2

        private const val COLOR_PANEL = 0xD0202024.toInt()
        private const val COLOR_TOOLBAR = 0xC0202024.toInt()
        private const val COLOR_KEY = 0xFF3C4043.toInt()
        private const val COLOR_KEY_ARMED = 0xFF1A73E8.toInt()

        /** The launcher button is an action, not a key — it gets its own colour. */
        private const val COLOR_KEY_ACCENT = 0xFF8A6D00.toInt()
        private const val COLOR_TEXT = 0xFFE8EAED.toInt()
        private const val COLOR_TEXT_ON_ACCENT = 0xFFFFF3D6.toInt()

        private const val DP_KEY_HEIGHT = 34
        private const val DP_TOOL_KEY_HEIGHT = 30
        private const val DP_KEY_PADDING = 2
        private const val DP_FIXED_KEY_WIDTH = 52

        /** Key name of the shift key — used to type the shifted characters. */
        private const val SHIFT = "lshift"

        private fun kindOf(kind: String): Int = when (kind) {
            "m" -> KIND_MODIFIER
            "s" -> KIND_SHIFTED
            else -> KIND_KEY
        }
    }
}
