package com.whereq.dosbox

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The bundle launcher: a panel over the emulator that lists the installed
 * bundles and starts them.
 *
 * It is drawn on top of the SDL surface, so it covers the emulator while it is
 * open and hands the screen back when it is closed.
 */
class GameLauncherView(context: Context, private val listener: Listener) : FrameLayout(context) {

    /** What the user asked the launcher to do. */
    interface Listener {
        /** Open the system file picker for a zip archive. */
        fun onInstallRequested()

        /** Import every archive of a folder on the phone. */
        fun onBrowseRequested()

        /** Start the game. */
        fun onPlayRequested(bundle: GameBundle)

        /** Open the launch-command editor for this game. */
        fun onEditLaunchRequested(bundle: GameBundle)

        /** The launch command was edited; save it and run the game. */
        fun onLaunchChosen(bundle: GameBundle, script: String)

        /** Remove the game. */
        fun onDeleteRequested(bundle: GameBundle)

        /** Stop whatever is running and return to a clean DOS prompt. */
        fun onExitToPromptRequested()

        /** Close the panel. */
        fun onCloseRequested()
    }

    private val list: LinearLayout
    private val scroll: ScrollView
    private val status: TextView
    private val empty: TextView

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        isClickable = true

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val columnParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        columnParams.gravity = Gravity.CENTER
        addView(column, columnParams)

        // The panel itself is a card in the middle of the screen, so the
        // emulator stays visible around it.
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16), dp(14), dp(16), dp(14))
        panel.background = cardBackground()
        val panelParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        panelParams.gravity = Gravity.CENTER
        panelParams.leftMargin = dp(12)
        panelParams.rightMargin = dp(12)
        column.addView(panel, panelParams)

        // ---------------------------------------------------------- header ---
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        panel.addView(header)

        val title = TextView(context)
        title.text = "Files"
        title.setTextColor(COLOR_TEXT)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        // Escape hatch: stop a game that hung or went black and return to a
        // clean C:\> prompt. Always reachable, because this launcher is an
        // Android overlay that works even when the emulator is stuck.
        val exit = makeButton("⏏ Exit to prompt")
        exit.setTextColor(COLOR_ACCENT)
        exit.setOnClickListener { listener.onExitToPromptRequested() }
        header.addView(exit)

        val close = makeButton("Close")
        close.setOnClickListener { listener.onCloseRequested() }
        header.addView(close)

        // --------------------------------------------------------- install ---
        val install = makeButton("+  Install a bundle (.zip)")
        install.setTextColor(0xFF10202E.toInt())
        install.setBackgroundColor(COLOR_ACCENT)
        val installParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        installParams.topMargin = dp(12)
        installParams.bottomMargin = dp(10)
        panel.addView(install, installParams)
        install.setOnClickListener { listener.onInstallRequested() }

        // The shorter way in: every archive of a folder is installed at once.
        val browse = makeButton("Install all bundles from a folder")
        browse.setOnClickListener { listener.onBrowseRequested() }
        val browseParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        browseParams.bottomMargin = dp(10)
        browseParams.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(browse, browseParams)

        // ------------------------------------------------------ the bundles ---
        empty = TextView(context)
        empty.text = "No games installed yet.\n\nInstall a .zip bundle from your phone — it is unpacked once and stays here. Then tap Play: the emulator restarts into the game with a clean, freshly mounted C: drive."
        empty.setTextColor(COLOR_DIM)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(8), dp(24), dp(8), dp(24))
        panel.addView(empty)

        scroll = ScrollView(context)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)

        // ---------------------------------------------------------- status ---
        status = TextView(context)
        status.setTextColor(COLOR_DIM)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        status.setPadding(0, dp(10), 0, 0)
        panel.addView(status)
    }

    /** Rebuilds the list of bundles. */
    fun showFiles(bundles: List<GameBundle>) {
        list.removeAllViews()
        empty.visibility = if (bundles.isEmpty()) VISIBLE else GONE
        for (bundle in bundles) {
            list.addView(makeRow(bundle))
        }
        // The list is only as tall as it needs to be, so the card stays small —
        // but it is capped, so a long list cannot push the card off the screen.
        scroll.post {
            val wanted = list.measuredHeight
            val capped = minOf(wanted, dp(320))
            if (scroll.layoutParams.height != capped) {
                scroll.layoutParams.height = capped
                scroll.requestLayout()
            }
        }
    }

    /** Shows a message below the list. */
    fun setStatus(text: String?) {
        status.text = text ?: ""
    }

    private fun makeRow(bundle: GameBundle): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        row.setBackgroundColor(COLOR_SURFACE_ALT)
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rowParams.bottomMargin = dp(8)
        row.layoutParams = rowParams

        val name = TextView(context)
        name.text = bundle.displayName
        name.setTextColor(COLOR_TEXT)
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.isSingleLine = true
        row.addView(name)

        val info = StringBuilder()
        info.append(bundle.entry).append("   ·   ").append(formatSize(bundle.sizeBytes))
        if (bundle.hasOwnDosboxConfig) {
            info.append("   ·   ships its own dosbox.conf")
        }
        val detail = TextView(context)
        detail.text = info.toString()
        detail.setTextColor(COLOR_DIM)
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        row.addView(detail)

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END
        val actionsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        actionsParams.topMargin = dp(6)
        row.addView(actions, actionsParams)

        val play = makeButton("Play")
        play.setTextColor(0xFF10202E.toInt())
        play.setBackgroundColor(COLOR_ACCENT)
        play.setOnClickListener { listener.onPlayRequested(bundle) }
        actions.addView(play)

        val launch = makeButton("Launch…")
        launch.setOnClickListener { listener.onEditLaunchRequested(bundle) }
        actions.addView(launch)

        val delete = makeButton("Remove")
        delete.setTextColor(COLOR_ACCENT)
        delete.setOnClickListener { listener.onDeleteRequested(bundle) }
        actions.addView(delete)

        return row
    }

    /**
     * Opens the launch-command editor. The field holds the DOS commands that run
     * after switching into the game folder (one per line). Because typing on a
     * phone is painful, the drives and every runnable file in the bundle are
     * shown as chips that *insert* a command on tap — so the user builds the
     * sequence (e.g. load a graphics/font driver, then the game) by tapping.
     */
    fun showLaunchEditor(bundle: GameBundle, current: String, candidates: List<String>) {
        val input = EditText(context)
        input.setText(current)
        input.setTextColor(COLOR_TEXT)
        input.setBackgroundColor(COLOR_SURFACE_ALT)
        input.setPadding(dp(10), dp(8), dp(10), dp(8))
        input.isSingleLine = false
        input.setHorizontallyScrolling(false)
        // Fixed, short height (min == max): the box never grows as lines are
        // added — it scrolls internally — so the tap-to-add chips below never
        // shift, and the dialog stays short enough for a landscape screen.
        input.minLines = 2
        input.maxLines = 2
        input.typeface = Typeface.MONOSPACE
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        input.setSelection(input.text.length)

        // Appends a command on its own line and keeps the caret at the end.
        fun insert(command: String) {
            val existing = input.text.toString()
            val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            input.append(prefix + command + "\n")
            input.setSelection(input.text.length)
        }

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(18), dp(6), dp(18), dp(6))
        column.addView(input)

        val tip = TextView(context)
        tip.text = "Tap a file below to add it as a line. A:/B: are already mounted to the game."
        tip.setTextColor(COLOR_DIM)
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tip.setPadding(0, dp(6), 0, dp(6))
        column.addView(tip)

        // Row of common actions: clear + switch drive.
        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        val clear = makeButton("Clear")
        clear.setTextColor(COLOR_ACCENT)
        clear.setOnClickListener { input.setText(""); input.setSelection(0) }
        actions.addView(clear)
        for (drive in arrayOf("A:", "C:")) {
            val b = makeButton(drive)
            b.setOnClickListener { insert(drive) }
            actions.addView(b)
        }
        column.addView(actions)

        // One chip per runnable file, in a single horizontally-scrolling row so
        // the dialog stays short enough for a landscape screen. Tapping inserts
        // the command (file name without extension).
        val fileRow = LinearLayout(context)
        fileRow.orientation = LinearLayout.HORIZONTAL
        for (candidate in candidates) {
            val command = candidate.substringAfterLast('\\').substringBeforeLast('.')
            val chip = makeButton(candidate.substringAfterLast('\\'))
            chip.setOnClickListener { insert(command) }
            fileRow.addView(chip)
        }
        val fileScroll = HorizontalScrollView(context)
        fileScroll.isHorizontalScrollBarEnabled = true
        fileScroll.addView(fileRow)
        column.addView(fileScroll)

        AlertDialog.Builder(context)
            .setTitle("Launch " + bundle.displayName)
            .setView(column)
            .setPositiveButton("Play") { _, _ ->
                listener.onLaunchChosen(bundle, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The launcher panel, as a rounded card over the emulator. */
    private fun cardBackground(): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(COLOR_SURFACE)
        drawable.cornerRadius = dp(14).toFloat()
        drawable.setStroke(dp(1), 0xFF2E3542.toInt())
        return drawable
    }

    private fun makeButton(label: String): Button {
        val button = Button(context)
        button.text = label
        button.setTextColor(COLOR_TEXT)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        button.isAllCaps = false
        button.setBackgroundColor(COLOR_SURFACE_ALT)
        button.setPadding(dp(12), dp(6), dp(12), dp(6))
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = dp(6)
        button.layoutParams = params
        return button
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val COLOR_BACKGROUND = 0xF0111319.toInt()
        private const val COLOR_SURFACE = 0xFF1B1F27.toInt()
        private const val COLOR_SURFACE_ALT = 0xFF232833.toInt()
        private const val COLOR_TEXT = 0xFFE8EAED.toInt()
        private const val COLOR_DIM = 0xFF9AA0AA.toInt()
        private const val COLOR_ACCENT = 0xFFF5A623.toInt()

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
