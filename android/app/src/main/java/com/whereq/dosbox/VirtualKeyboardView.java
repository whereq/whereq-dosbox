package com.whereq.dosbox;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * On-screen keyboard for the DOSBox shell.
 *
 * <p>How a key press reaches DOSBox:
 *
 * <ol>
 *   <li>the key is turned into an SDL key event carrying the right
 *       {@code SDL_Scancode} and pushed into SDL's event queue by
 *       {@link DosboxNative#nativePushKey(int, boolean)};</li>
 *   <li>DOSBox's {@code GFX_Events()} hands the event to the mapper
 *       ({@code src/gui/sdl_mapper.cpp}), which indexes its bind list by the
 *       scancode and activates the matching {@code CKeyEvent};</li>
 *   <li>that event calls {@code KEYBOARD_AddKey()}, which fills the BIOS
 *       keyboard buffer the DOS shell reads.</li>
 * </ol>
 *
 * <p>So no DOSBox patch is needed - the virtual keyboard is just another
 * producer of SDL key events, exactly like the hardware keyboard.
 *
 * <p>The layout is a slim always-on toolbar (the keys a DOS program needs
 * most) plus a full QWERTY panel that can be folded away, because the panel
 * covers a large part of a phone screen.
 *
 * <p>Modifier keys (Shift, Ctrl, Alt) are <em>sticky</em>: tapping one arms
 * it, it stays pressed until the next ordinary key is typed, which is the
 * behaviour a touch screen can offer without a second finger.
 */
public class VirtualKeyboardView extends LinearLayout {

    private static final String TAG = "whereq-dosbox";

    /** Ordinary key: pressed and released again straight away. */
    private static final int KIND_KEY = 0;
    /** Sticky modifier: tapping it presses/releases the real modifier key. */
    private static final int KIND_MODIFIER = 1;
    /** Key that needs Shift, e.g. "!" - Shift is wrapped around it. */
    private static final int KIND_SHIFTED = 2;

    private static final int COLOR_PANEL = 0xD0202024;
    private static final int COLOR_TOOLBAR = 0xC0202024;
    private static final int COLOR_KEY = 0xFF3C4043;
    private static final int COLOR_KEY_ARMED = 0xFF1A73E8;
    private static final int COLOR_TEXT = 0xFFE8EAED;

    private static final int DP_KEY_HEIGHT = 34;
    private static final int DP_TOOL_KEY_HEIGHT = 30;
    private static final int DP_KEY_PADDING = 2;
    private static final int DP_FIXED_KEY_WIDTH = 52;

    /** Key name of the shift key - used to type the shifted characters. */
    private static final String SHIFT = "lshift";

    /** key name -> numeric SDL scancode. */
    private final Map<String, Integer> scancodes = new HashMap<String, Integer>();
    /** key name of a sticky modifier -> is it currently pressed. */
    private final Map<String, Boolean> armed = new HashMap<String, Boolean>();
    /** key name -> the view, so the armed state can be shown. */
    private final Map<String, TextView> modifierViews = new HashMap<String, TextView>();

    private LinearLayout panel;
    private HorizontalScrollView symbolScroll;
    private TextView toggleButton;
    private boolean panelVisible;

    public VirtualKeyboardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        buildPanel();
        buildToolbar();
    }

    /* ------------------------------------------------------------- layout */

    /**
     * Builds the foldable full-size keyboard.
     */
    private void buildPanel() {
        panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.setBackgroundColor(COLOR_PANEL);

        // The function keys sit in a scrollable row - 21 keys do not fit
        // next to each other on a phone.
        panel.addView(scrollableRow(new String[][]{
                {"Esc", "esc", "f", "k"},
                {"F1", "f1", "f", "k"},
                {"F2", "f2", "f", "k"},
                {"F3", "f3", "f", "k"},
                {"F4", "f4", "f", "k"},
                {"F5", "f5", "f", "k"},
                {"F6", "f6", "f", "k"},
                {"F7", "f7", "f", "k"},
                {"F8", "f8", "f", "k"},
                {"F9", "f9", "f", "k"},
                {"F10", "f10", "f", "k"},
                {"F11", "f11", "f", "k"},
                {"F12", "f12", "f", "k"},
                {"PrtSc", "printscreen", "f", "k"},
                {"Pause", "pause", "f", "k"},
                {"Ins", "insert", "f", "k"},
                {"Del", "delete", "f", "k"},
                {"Home", "home", "f", "k"},
                {"End", "end", "f", "k"},
                {"PgUp", "pageup", "f", "k"},
                {"PgDn", "pagedown", "f", "k"},
        }));

        panel.addView(weightedRow(new String[][]{
                {"`", "grave", "1", "k"},
                {"1", "1", "1", "k"},
                {"2", "2", "1", "k"},
                {"3", "3", "1", "k"},
                {"4", "4", "1", "k"},
                {"5", "5", "1", "k"},
                {"6", "6", "1", "k"},
                {"7", "7", "1", "k"},
                {"8", "8", "1", "k"},
                {"9", "9", "1", "k"},
                {"0", "0", "1", "k"},
                {"-", "minus", "1", "k"},
                {"=", "equals", "1", "k"},
                {"Bks", "bspace", "2", "k"},
        }));

        panel.addView(weightedRow(new String[][]{
                {"Tab", "tab", "1.5", "k"},
                {"q", "q", "1", "k"},
                {"w", "w", "1", "k"},
                {"e", "e", "1", "k"},
                {"r", "r", "1", "k"},
                {"t", "t", "1", "k"},
                {"y", "y", "1", "k"},
                {"u", "u", "1", "k"},
                {"i", "i", "1", "k"},
                {"o", "o", "1", "k"},
                {"p", "p", "1", "k"},
                {"[", "lbracket", "1", "k"},
                {"]", "rbracket", "1", "k"},
                {"\\", "backslash", "1.5", "k"},
        }));

        panel.addView(weightedRow(new String[][]{
                {"Caps", "capslock", "1.8", "k"},
                {"a", "a", "1", "k"},
                {"s", "s", "1", "k"},
                {"d", "d", "1", "k"},
                {"f", "f", "1", "k"},
                {"g", "g", "1", "k"},
                {"h", "h", "1", "k"},
                {"j", "j", "1", "k"},
                {"k", "k", "1", "k"},
                {"l", "l", "1", "k"},
                {";", "semicolon", "1", "k"},
                {"'", "quote", "1", "k"},
                {"Ent", "enter", "2", "k"},
        }));

        panel.addView(weightedRow(new String[][]{
                {"Shift", "lshift", "2.2", "m"},
                {"z", "z", "1", "k"},
                {"x", "x", "1", "k"},
                {"c", "c", "1", "k"},
                {"v", "v", "1", "k"},
                {"b", "b", "1", "k"},
                {"n", "n", "1", "k"},
                {"m", "m", "1", "k"},
                {",", "comma", "1", "k"},
                {".", "period", "1", "k"},
                {"/", "slash", "1", "k"},
                {"Shift", "rshift", "2.2", "m"},
                {"\u2191", "up", "1", "k"},
        }));

        panel.addView(weightedRow(new String[][]{
                {"Ctrl", "lctrl", "1.5", "m"},
                {"Alt", "lalt", "1.3", "m"},
                {"Space", "space", "6", "k"},
                {"\u2190", "left", "1", "k"},
                {"\u2193", "down", "1", "k"},
                {"\u2192", "right", "1", "k"},
        }));

        // The shifted characters of the US layout, for the keys that a
        // phone keyboard would otherwise hide.
        symbolScroll = scrollableRow(new String[][]{
                {"~", "grave", "f", "s"},
                {"!", "1", "f", "s"},
                {"@", "2", "f", "s"},
                {"#", "3", "f", "s"},
                {"$", "4", "f", "s"},
                {"%", "5", "f", "s"},
                {"^", "6", "f", "s"},
                {"&", "7", "f", "s"},
                {"*", "8", "f", "s"},
                {"(", "9", "f", "s"},
                {")", "0", "f", "s"},
                {"_", "minus", "f", "s"},
                {"+", "equals", "f", "s"},
                {"{", "lbracket", "f", "s"},
                {"}", "rbracket", "f", "s"},
                {"|", "backslash", "f", "s"},
                {":", "semicolon", "f", "s"},
                {"\"", "quote", "f", "s"},
                {"<", "comma", "f", "s"},
                {">", "period", "f", "s"},
                {"?", "slash", "f", "s"},
        });
        symbolScroll.setVisibility(GONE);
        panel.addView(symbolScroll);

        panel.setVisibility(GONE);
        addView(panel);
    }

    /**
     * Builds the always visible toolbar.
     */
    private void buildToolbar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(COLOR_TOOLBAR);

        toggleButton = makeButton("\u2328", "", "f", "k", DP_TOOL_KEY_HEIGHT);
        toggleButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                togglePanel();
            }
        });
        bar.addView(toggleButton);

        String[][] tools = {
                {"Esc", "esc", "f", "k"},
                {"Tab", "tab", "f", "k"},
                {"Ctrl", "lctrl", "f", "m"},
                {"Alt", "lalt", "f", "m"},
                {"Shift", "lshift", "f", "m"},
                {"Ent", "enter", "f", "k"},
                {"Bks", "bspace", "f", "k"},
                {"Space", "space", "f", "k"},
                {"\u2190", "left", "f", "k"},
                {"\u2193", "down", "f", "k"},
                {"\u2192", "right", "f", "k"},
                {"\u2191", "up", "f", "k"},
                {"F1", "f1", "f", "k"},
                {"F2", "f2", "f", "k"},
                {"F3", "f3", "f", "k"},
                {"F4", "f4", "f", "k"},
                {"F5", "f5", "f", "k"},
                {"F6", "f6", "f", "k"},
                {"F7", "f7", "f", "k"},
                {"F8", "f8", "f", "k"},
        };
        for (String[] tool : tools) {
            bar.addView(makeKey(tool[0], tool[1], tool[2], tool[3], DP_TOOL_KEY_HEIGHT));
        }

        // Switches the symbol row of the panel on and off.
        TextView symbols = makeButton("\u00ab", "", "f", "k", DP_TOOL_KEY_HEIGHT);
        symbols.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                toggleSymbols();
            }
        });
        bar.addView(symbols);

        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(bar);
        addView(scroll);
    }

    /* -------------------------------------------------------------- rows */

    /**
     * A row whose keys share the available width.
     */
    private LinearLayout weightedRow(String[][] keys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (String[] k : keys) {
            row.addView(makeKey(k[0], k[1], "w" + k[2], k[3], DP_KEY_HEIGHT));
        }
        return row;
    }

    /**
     * A row of fixed-width keys inside a horizontal scroll view, for the
     * function keys and the symbols.
     */
    private HorizontalScrollView scrollableRow(String[][] keys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (String[] k : keys) {
            row.addView(makeKey(k[0], k[1], k[2], k[3], DP_KEY_HEIGHT));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    /* -------------------------------------------------------------- keys */

    /**
     * Builds one key that reacts to touch.
     *
     * @param label   text shown on the key.
     * @param keyName DOSBox key name (see the key table in the JNI glue).
     * @param size    {@code "w<weight>"} for a weighted key, {@code "f"} for a
     *                fixed width key.
     * @param kind    {@code "k"} ordinary, {@code "m"} sticky modifier,
     *                {@code "s"} key that needs Shift.
     * @param heightDp key height in dp.
     */
    private TextView makeKey(final String label, final String keyName,
                             String size, final String kind, int heightDp) {
        int kindCode = kindOf(kind);
        TextView view = makeButton(label, keyName, size, kind, heightDp);

        view.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    keyDown(keyName, kindCode);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    keyUp(keyName, kindCode);
                    return true;
                }
                return true;
            }
        });
        return view;
    }

    private static int kindOf(String kind) {
        if ("m".equals(kind)) {
            return KIND_MODIFIER;
        }
        if ("s".equals(kind)) {
            return KIND_SHIFTED;
        }
        return KIND_KEY;
    }

    private TextView makeButton(String label, String keyName, String size,
                                String kind, int heightDp) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        // A key label must never wrap - the keys are narrow.
        view.setSingleLine(true);
        view.setPadding(dp(DP_KEY_PADDING), 0, dp(DP_KEY_PADDING), 0);
        view.setBackground(keyBackground(COLOR_KEY));
        view.setTag(keyName);

        float weight = 0;
        if (size.startsWith("w")) {
            weight = Float.parseFloat(size.substring(1));
        }
        LayoutParams lp = new LayoutParams(
                weight > 0 ? 0 : dp(DP_FIXED_KEY_WIDTH), dp(heightDp));
        if (weight > 0) {
            lp.weight = weight;
        }
        lp.rightMargin = dp(2);
        lp.leftMargin = dp(2);
        view.setLayoutParams(lp);

        if ("m".equals(kind)) {
            modifierViews.put(keyName, view);
            armed.put(keyName, Boolean.FALSE);
        }
        return view;
    }

    private GradientDrawable keyBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(Math.max(1, dp(1)), 0xFF000000);
        return drawable;
    }

    /* ------------------------------------------------------------ events */

    /**
     * A key went down.
     */
    private void keyDown(String keyName, int kind) {
        if (kind == KIND_MODIFIER) {
            boolean now = !isArmed(keyName);
            setArmed(keyName, now);
            push(keyName, now);
            refreshModifier(keyName);
            return;
        }
        if (kind == KIND_SHIFTED) {
            // Shift is wrapped around the key so that the character comes out
            // shifted.
            push(SHIFT, true);
            push(keyName, true);
            return;
        }
        push(keyName, true);
    }

    /**
     * A key went up.
     */
    private void keyUp(String keyName, int kind) {
        if (kind == KIND_MODIFIER) {
            // Sticky: the modifier stays pressed until the next key is typed.
            return;
        }
        if (kind == KIND_SHIFTED) {
            push(keyName, false);
            push(SHIFT, false);
            return;
        }
        push(keyName, false);
        // A sticky modifier applies to exactly one character.
        releaseModifiers();
    }

    private boolean isArmed(String keyName) {
        Boolean value = armed.get(keyName);
        return value != null && value.booleanValue();
    }

    private void setArmed(String keyName, boolean value) {
        armed.put(keyName, Boolean.valueOf(value));
    }

    private void releaseModifiers() {
        for (Map.Entry<String, Boolean> entry : armed.entrySet()) {
            if (entry.getValue().booleanValue()) {
                push(entry.getKey(), false);
                entry.setValue(Boolean.FALSE);
                refreshModifier(entry.getKey());
            }
        }
    }

    private void refreshModifier(String keyName) {
        TextView view = modifierViews.get(keyName);
        if (view != null) {
            view.setBackground(keyBackground(isArmed(keyName) ? COLOR_KEY_ARMED : COLOR_KEY));
        }
    }

    /**
     * Pushes a key press or release into DOSBox.
     */
    private void push(String keyName, boolean pressed) {
        int scancode = scancodeOf(keyName);
        if (scancode == 0) {
            Log.w(TAG, "ignoring unknown key " + keyName);
            return;
        }
        if (!DosboxNative.nativePushKey(scancode, pressed)) {
            Log.w(TAG, "could not push " + keyName + (pressed ? " down" : " up"));
        }
    }

    private int scancodeOf(String keyName) {
        Integer cached = scancodes.get(keyName);
        if (cached != null) {
            return cached.intValue();
        }
        int scancode = DosboxNative.nativeScancodeFromName(keyName);
        scancodes.put(keyName, Integer.valueOf(scancode));
        return scancode;
    }

    /* ------------------------------------------------------------ toggles */

    /**
     * @return true when the full keyboard panel is unfolded.
     */
    public boolean isPanelVisible() {
        return panelVisible;
    }

    /**
     * Folds the full keyboard panel away.
     */
    public void hidePanel() {
        if (!panelVisible) {
            return;
        }
        togglePanel();
    }

    private void togglePanel() {
        panelVisible = !panelVisible;
        panel.setVisibility(panelVisible ? VISIBLE : GONE);
        toggleButton.setBackground(keyBackground(panelVisible ? COLOR_KEY_ARMED : COLOR_KEY));
        toggleButton.setText(panelVisible ? "\u2328\u2715" : "\u2328");
        if (panelVisible) {
            requestLayout();
        }
    }

    private void toggleSymbols() {
        boolean visible = symbolScroll.getVisibility() == VISIBLE;
        symbolScroll.setVisibility(visible ? GONE : VISIBLE);
    }

    /**
     * Keeps the panel about as wide as the screen is short, so that the keys
     * do not become absurdly wide in landscape.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.min(metrics.widthPixels, metrics.heightPixels) * 92 / 100;
        if (panel.getLayoutParams().width != width) {
            panel.setLayoutParams(new LayoutParams(width, LayoutParams.WRAP_CONTENT));
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
