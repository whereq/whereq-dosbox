package com.whereq.dosbox

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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

        /** Open the settings dialog (the DOSBox CPU core). */
        fun onConfigRequested()

        /** Resume the last-played game. */
        fun onResumeRequested()

        /** Save the currently running machine into [slot] (1-based) of this game. */
        fun onSaveStateRequested(bundle: GameBundle, slot: Int)

        /** Restore [slot] (1-based) of this game — replaces the running machine. */
        fun onLoadStateRequested(bundle: GameBundle, slot: Int)

        /** @return a short label for a saved slot (e.g. "5 min ago"), or null when empty. */
        fun stateSlotLabel(bundle: GameBundle, slot: Int): String?

        /** @return true when a durable saves folder is connected. */
        fun hasSavesFolder(): Boolean

        /** Connect a saves folder (to surface saves from a previous install). */
        fun onConnectSavesFolderRequested(bundle: GameBundle)

        /** Close the panel. */
        fun onCloseRequested()

        /**
         * Open the licence screen.
         *
         * WhereQ-DOSBox embeds the DOSBox core, which is GPLv2: the licence and
         * the copyright notices have to travel with the program, and the
         * complete source has to be reachable from the app. The screen carries
         * both.
         */
        fun onOpenSourceLicensesRequested()

        /**
         * Open the privacy policy.
         *
         * The app collects no data and asks for no network permission, but the
         * policy still has to be reachable from inside the app, not only from
         * a website. WhereQ-DOSBox is published from its own repository, so the
         * policy is a file in that repository and the footer links to it.
         */
        fun onPrivacyPolicyRequested()
    }

    private val panel: LinearLayout
    private val list: LinearLayout
    private val scroll: ScrollView
    private val status: TextView
    private val empty: TextView
    private val resume: Button

    /**
     * The folder of the game this process actually booted into (null at a clean
     * prompt). Save is only offered for that game, so a slot can never be
     * overwritten with the wrong machine (e.g. the bare prompt).
     */
    private var currentGame: String? = null

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
        panel = LinearLayout(context)
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

        // The settings: which CPU core DOSBox should use. The shipped default
        // (auto) is right for almost everything, but a few old DOS programs
        // misbehave on the recompiler, so the fallback has to be reachable
        // without a text editor.
        val config = makeButton("⚙ Config")
        config.setOnClickListener { listener.onConfigRequested() }
        header.addView(config)

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

        // -------------------------------------------------------- resume ---
        // Jump straight back into the last game played (shown only when there is
        // one). The emulator keep-alive usually brings a backgrounded game back
        // on its own; this is the fallback for when the process was killed.
        resume = makeButton("▶ Resume")
        resume.setTextColor(0xFF10202E.toInt())
        resume.setBackgroundColor(COLOR_RESUME)
        resume.visibility = GONE
        val resumeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        resumeParams.bottomMargin = dp(10)
        panel.addView(resume, resumeParams)
        resume.setOnClickListener { listener.onResumeRequested() }

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

        // ---------------------------------------------------------- footer ---
        // The version, the licence screen and the privacy policy.
        //
        // The GPL asks for the licence and the notices to travel with the
        // program, and for the complete source to be reachable from it. The
        // launcher is the one screen that is always reachable, so the link to
        // the licence screen lives in its footer - next to the link to the
        // privacy policy, which has to be reachable from inside the app too.
        val footer = LinearLayout(context)
        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        val footerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        footerParams.topMargin = dp(4)
        panel.addView(footer, footerParams)

        val version = TextView(context)
        version.text = appVersion()
        version.setTextColor(COLOR_DIM)
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        // The version gives way when the footer is narrow: the links to the
        // licences and to the privacy policy are the ones that have to stay.
        version.maxLines = 1
        version.ellipsize = TextUtils.TruncateAt.END
        footer.addView(
            version,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val licences = TextView(context)
        licences.text = "Licences"
        licences.setTextColor(COLOR_ACCENT)
        licences.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        licences.setPadding(dp(8), dp(6), 0, dp(2))
        licences.setOnClickListener { listener.onOpenSourceLicensesRequested() }
        footer.addView(licences)

        val privacy = TextView(context)
        privacy.text = "Privacy policy"
        privacy.setTextColor(COLOR_ACCENT)
        privacy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        privacy.setPadding(dp(8), dp(6), 0, dp(2))
        privacy.setOnClickListener { listener.onPrivacyPolicyRequested() }
        footer.addView(privacy)
    }

    /**
     * The version of the installed app, e.g. "WhereQ-DOSBox 1.0.0.16".
     *
     * Read from the package manager rather than from a constant, so the
     * footer can never claim a version the build does not have.
     */
    private fun appVersion(): String {
        val name = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        return if (name.isNullOrEmpty()) "WhereQ-DOSBox" else "WhereQ-DOSBox $name"
    }

    /** Rebuilds the list of bundles. */
    fun showFiles(bundles: List<GameBundle>) {
        list.removeAllViews()
        empty.visibility = if (bundles.isEmpty()) VISIBLE else GONE
        for (bundle in bundles) {
            list.addView(makeRow(bundle))
        }
        scroll.post { capScrollHeight() }
    }

    /**
     * Caps the height of the game-list scroll area so the whole card fits on
     * screen.
     *
     * The card is centred and wraps its content, and only the list scrolls — the
     * card itself does not. So a long list (three games or more) would push the
     * lower rows, and their Play/States/Remove buttons, off the bottom edge
     * where nothing could bring them back. The cap is the available screen
     * height minus the card's fixed parts (header, the install/resume buttons,
     * the status line, padding) and a small breathing margin; a short list stays
     * small because the cap is only a ceiling.
     */
    private fun capScrollHeight() {
        val wanted = list.measuredHeight
        val screenH = height
        // The card's fixed chrome is everything in it except the scroll area;
        // it does not depend on the scroll's current height.
        val chrome = (panel.height - scroll.height).coerceAtLeast(0)
        val maxScroll = if (screenH > 0) {
            (screenH - chrome - dp(24)).coerceAtLeast(dp(96))
        } else {
            dp(320) // not laid out yet; fall back to a safe fixed cap
        }
        val capped = minOf(wanted, maxScroll)
        if (scroll.layoutParams.height != capped) {
            scroll.layoutParams.height = capped
            scroll.requestLayout()
        }
    }

    // Re-cap when the overlay is resized (e.g. the device is rotated while the
    // panel is open): the available height changes, so a cap computed for the
    // old size could now overflow or waste space.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (visibility == VISIBLE) {
            scroll.post { capScrollHeight() }
        }
    }

    /** Shows a message below the list. */
    fun setStatus(text: String?) {
        status.text = text ?: ""
    }

    /**
     * Records which game this process is actually running (null at a clean
     * prompt). Only that game offers "Save", so a save-state slot cannot be
     * overwritten with an unrelated machine.
     */
    fun setCurrentGame(folder: String?) {
        currentGame = folder
    }

    /**
     * Shows (or hides) the "Resume" button for the last-played game.
     *
     * @param name the game's display name, or null to hide the button.
     * @param detail what the button will resume from (e.g. "slot 2 · 5 min
     *   ago"), or null when the game has no save yet.
     */
    fun setResumeGame(name: String?, detail: String? = null) {
        if (name == null) {
            resume.visibility = GONE
        } else {
            resume.text = if (detail == null) {
                "▶ Resume $name"
            } else {
                "▶ Resume $name  ·  $detail"
            }
            resume.visibility = VISIBLE
        }
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

        val states = makeButton("States")
        states.setOnClickListener { showStatesDialog(bundle) }
        actions.addView(states)

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

    /**
     * The save-state slots for one game.
     *
     * Save writes the whole running machine into a slot; Load restores it,
     * replacing whatever is running (so it works from inside the game or from a
     * fresh prompt). Save is only offered for the game this process is actually
     * running ([currentGame]) — loading another game's slot is always allowed
     * because it simply becomes that game.
     */
    /** Opens the save-states dialog for a game (used to reopen it after a
     *  saves folder is connected). */
    fun openStates(bundle: GameBundle) = showStatesDialog(bundle)

    private fun showStatesDialog(bundle: GameBundle) {
        val isRunning = currentGame != null && currentGame == bundle.folder
        var dialog: AlertDialog? = null

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(18), dp(10), dp(18), dp(6))

        val hint = TextView(context)
        hint.text = if (isRunning) {
            "Save the game exactly where it is now, or load a slot to jump back. A slot survives quitting the app and rebooting."
        } else {
            "Open States while this game is running to save. You can still load an existing slot now — it restores the game straight away."
        }
        hint.setTextColor(COLOR_DIM)
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        hint.setPadding(0, 0, 0, dp(8))
        column.addView(hint)

        for (slot in 1..SaveStore.STATE_SLOTS) {
            val saved = listener.stateSlotLabel(bundle, slot)

            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(6)
            row.layoutParams = rowParams

            val label = TextView(context)
            label.text = "Slot $slot   ·   " + (saved ?: "empty")
            label.setTextColor(if (saved != null) COLOR_TEXT else COLOR_DIM)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            row.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val save = makeButton("Save")
            save.isEnabled = isRunning
            save.alpha = if (isRunning) 1f else 0.4f
            save.setOnClickListener {
                dialog?.dismiss()
                if (saved != null) {
                    // Confirm before clobbering a slot that already holds a save.
                    AlertDialog.Builder(context)
                        .setTitle("Overwrite slot $slot?")
                        .setMessage("Slot $slot already holds a save ($saved).")
                        .setPositiveButton("Overwrite") { _, _ ->
                            listener.onSaveStateRequested(bundle, slot)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    listener.onSaveStateRequested(bundle, slot)
                }
            }
            row.addView(save)

            val load = makeButton("Load")
            load.setTextColor(COLOR_RESUME)
            load.isEnabled = saved != null
            load.alpha = if (saved != null) 1f else 0.4f
            load.setOnClickListener {
                dialog?.dismiss()
                listener.onLoadStateRequested(bundle, slot)
            }
            row.addView(load)

            column.addView(row)
        }

        // After a reinstall the durable folder link is gone, so saved slots show
        // as empty. Offer to reconnect it right here, where the user is looking
        // to load.
        if (!listener.hasSavesFolder()) {
            val connect = makeButton("📁  Load saves from a folder…")
            connect.setTextColor(COLOR_ACCENT)
            connect.setOnClickListener {
                dialog?.dismiss()
                listener.onConnectSavesFolderRequested(bundle)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            connect.layoutParams = lp
            column.addView(connect)
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("Save states — " + bundle.displayName)
            .setView(column)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
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
        private const val COLOR_RESUME = 0xFF4CAF50.toInt()

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
