package com.whereq.dosbox

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libsdl.app.SDLActivity
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Minimal SDL2 shell around the DOSBox emulator.
 *
 * Responsibilities of this class (everything else stays inside DOSBox):
 *  - load `libdosbox.so` instead of a separate `libSDL2.so` — SDL2 is statically
 *    linked into the DOSBox library, so asking SDL to load "SDL2" would fail
 *    with UnsatisfiedLinkError;
 *  - point SDL at `libdosbox.so` and at the `SDL_main` entry point inside it;
 *  - hand DOSBox a private, writable working directory and `$HOME`;
 *  - materialise a DOSBox configuration tuned for a phone;
 *  - put the on-screen keyboard on top of the emulator surface;
 *  - host the bundle launcher, which installs and starts DOS bundles.
 *
 * Touch input needs no code here: SDL's Android back end already turns touches
 * into mouse events (see `SDLSurface.onTouch`), and DOSBox maps the mouse back
 * through the letterboxed clip rectangle. The on-screen keyboard is the only
 * thing that has to be added, because it has to produce *key* events, and those
 * are injected into SDL's event queue from [VirtualKeyboardView] via
 * [DosboxNative.nativePushKey].
 */
class WhereqDosboxActivity : SDLActivity() {

    /**
     * Opens a bundle archive for reading.
     *
     * The archive comes from the system file picker, from a file manager or from
     * a folder, and each of them reads it in its own way.
     */
    private fun interface ArchiveSource {
        @Throws(IOException::class)
        fun open(): InputStream
    }

    /** The on-screen keyboard, drawn on top of the DOSBox surface. */
    private var keyboard: VirtualKeyboardView? = null

    /** The bundle launcher, drawn on top of everything. */
    private var launcher: GameLauncherView? = null

    /**
     * The predictive-back callback (API 33+).
     *
     * It is kept in a field so it can be unregistered again in [onDestroy].
     */
    private var backInvokedCallback: OnBackInvokedCallback? = null

    /** The installed bundles. */
    private lateinit var library: GameLibrary

    /** Save-state storage: the local working copy + the durable folder mirror. */
    private val saveStore by lazy { SaveStore(this) }

    /** When the user connects a saves folder from a game's States dialog, the
     *  game to reopen that dialog for once the folder is set (its saves then
     *  show). */
    private var pendingStatesGame: GameBundle? = null

    /**
     * The folder of the game this process actually booted into (null at a clean
     * prompt). Set once in [onCreate] from the pending launch. Used so "Save
     * state" is only offered for the game that is really running.
     */
    private var currentGameFolder: String? = null

    /**
     * Scope for the background install/import work. Tied to the activity and
     * cancelled in [onDestroy], so a running unpack can never post to a
     * destroyed activity (the raw-thread version could). `Main.immediate` means
     * the UI updates in the coroutine body run on the main thread.
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Serialises installs: two archives must not unpack into the games
     * directory at the same time (the index is read, then a folder is created).
     */
    private val installMutex = Mutex()

    override fun getLibraries(): Array<String> {
        // SDL2 is statically linked into libdosbox.so, so "SDL2" must not be
        // requested here.
        return arrayOf(NATIVE_LIB)
    }

    override fun getMainSharedObject(): String =
        SDLActivity.getContext().applicationInfo.nativeLibraryDir + "/lib" + NATIVE_LIB + ".so"

    override fun getMainFunction(): String = "SDL_main"

    override fun getArguments(): Array<String> {
        // DOSBox reads its configuration from this file; the path is relative to
        // the working directory, which nativePrepareEnvironment() has set to the
        // app's files directory.
        return arrayOf("-conf", CONF_NAME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // loads the native library

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // On API 33+ the system stops sending KEYCODE_BACK to the activity and
        // calls the registered back callback instead, so it has to be in place
        // before anything can go wrong. See registerBackInvokedCallback().
        registerBackInvokedCallback()

        // NOTE: do NOT resize/pad the SDL surface here to make room for the
        // keyboard. Changing the SurfaceView size breaks DOSBox's Android
        // texture renderer (black screen) — the porting log warns the window
        // must not be recreated/resized. The surface stays full-screen and the
        // keyboard only uses the letterbox bars left and right of the picture.

        val filesDir = filesDir

        // A game is launched by relaunching the app into a fresh DOSBox boot
        // that auto-runs it (see play()): that is the only way to get a clean
        // slate and, crucially, a freshly mounted C: whose directory cache
        // includes the installed bundle. (DOSBox caches the C: listing at mount
        // time and only re-scans floppies on FindFirst — localDrive.cpp:184 —
        // so an installer that enumerates its files finds nothing otherwise.)
        // Read the pending launch and clear it immediately, so a crash or a
        // force-quit lands back at a clean C:\> prompt instead of looping.
        val prefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
        val pendingLaunch = prefs.getString(KEY_PENDING_LAUNCH, null)
        // A "resume" also carries the slot to restore: the game is not started
        // from the beginning, its saved machine is loaded instead.
        val pendingState = prefs.getInt(KEY_PENDING_STATE, 0)
        if (pendingLaunch != null) {
            prefs.edit().remove(KEY_PENDING_LAUNCH).remove(KEY_PENDING_STATE).commit()
        } else if (pendingState != 0) {
            prefs.edit().remove(KEY_PENDING_STATE).commit()
        }
        // This is the game actually running this boot (null = a clean prompt);
        // "Save state" is offered only for it, so a slot is never overwritten
        // with an unrelated machine.
        currentGameFolder = pendingLaunch
        // Regenerate the config from the asset on every boot: it keeps the
        // shipped settings current (e.g. core=auto reaches existing installs)
        // and appends the autostart line for the pending game. With a slot to
        // restore there is nothing to autostart - the restore brings the game
        // back by itself.
        writeConfig(filesDir, pendingLaunch, autostartGame = pendingState == 0)

        library = GameLibrary(filesDir)

        // Two games can carry the same archive name, and the durable save
        // store is keyed by the display name, so their saves would overwrite
        // each other. The names are made unique once, here.
        library.makeNamesUnique()

        // When a game is auto-starting, keep this process alive while the player
        // switches to another app, so SDL's paused emulator survives and resumes
        // the exact state on return (DOSBox has no save-state). A clean prompt
        // boot runs no service.
        if (pendingLaunch != null) {
            requestNotificationPermission()
            val name = library.list().firstOrNull { it.folder == pendingLaunch }?.displayName
                ?: pendingLaunch
            DosboxForegroundService.start(this, name)
        }

        // HOME + working directory must be in place before SDL_main() runs; the
        // SDL thread is only started once the surface is ready, i.e. after
        // onResume(), so doing this here is early enough.
        DosboxNative.nativePrepareEnvironment(filesDir.absolutePath)

        installVirtualKeyboard()

        // A bundle that was opened from a file manager is installed right away —
        // the user asked for it with a tap.
        handleOpenIntent(intent)

        // "Resume" asked for a slot to be restored: do it now. The native side
        // only services the request once the guest is running, so the call
        // simply waits for the emulator to come up.
        if (pendingLaunch != null && pendingState > 0) {
            restoreAfterBoot(pendingLaunch, pendingState)
        }

        Log.i(TAG, "prepared environment, config dir = " + DosboxNative.nativeConfigDir())
    }

    /**
     * Installs the bundle of an `ACTION_VIEW` intent.
     *
     * That is how a bundle reaches the app when the user picks it in a file
     * manager and chooses WhereQ-DOSBox.
     */
    private fun handleOpenIntent(intent: Intent?) {
        if (intent == null || Intent.ACTION_VIEW != intent.action) {
            return
        }
        val uri = intent.data ?: return
        val name = displayNameOf(uri)
        showLauncher()
        install(
            ArchiveSource {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("the file could not be opened")
            },
            name
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    /**
     * Puts the on-screen keyboard on top of the DOSBox surface.
     *
     * The keyboard is added to the activity's content view, i.e. it is drawn
     * above the SDL `SurfaceView`. Its keys only occupy the space next to the
     * DOS picture, so touches on the picture still reach the emulator — which is
     * what turns a tap into a click inside the DOS picture (SDL turns the touch
     * into a mouse event, DOSBox maps it back through the letterboxed clip
     * rectangle).
     */
    private fun installVirtualKeyboard() {
        val keyboard = VirtualKeyboardView(this)
        keyboard.setOnFilesRequestedListener { showLauncher() }
        // The Konami code (↑↑↓↓←→←→BA) on the keyboard flips into Game Mode.
        keyboard.setOnGameModeRequestedListener { showGameMode() }

        // Full-screen overlay: the keyboard positions its keys beside the DOS
        // picture itself and leaves the picture free (a tap there falls
        // through to DOSBox as a mouse click).
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        addContentView(keyboard, lp)
        this.keyboard = keyboard
    }

    /* --------------------------------------------------------- Game Mode */

    /** The touch gamepad overlay; created lazily the first time it is shown. */
    private var gameMode: GameModeView? = null

    /**
     * The slots with a save in flight. A save is written off the UI thread and
     * the file only appears when it is done, so a slot that is being written
     * already counts as taken.
     */
    private val savingSlots = mutableSetOf<Int>()

    /**
     * Swaps the keyboard pads for the Game Mode gamepad. The game's remembered
     * profile and D-pad output are restored; Hybrid is the default. Reached from
     * the keyboard's Konami code.
     */
    private fun showGameMode() {
        val view = gameMode ?: GameModeView(this).also { gm ->
            gm.setListener(object : GameModeView.Listener {
                override fun onExitToKeyboard() = showKeyboard()
                override fun onFilesRequested() = showLauncher()
                override fun onProfileChanged(profile: GameModeView.Profile) = saveGameProfile(profile)
                override fun onDpadChanged(dpad: GameModeView.Dpad) = saveGameDpad(dpad)
                override fun onRemapsChanged(remaps: Map<String, String>) = saveGameRemaps(remaps)

                override fun onQuickSaveRequested() = quickSaveFromGameMode()

                override fun onStatesRequested() = openStatesFromGameMode()
            })
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            addContentView(gm, lp)
            gameMode = gm
        }
        // Restore the per-game choice before showing, without notifying back, in
        // one shot (one layout pass).
        view.restoreState(loadGameProfile(), loadGameDpad(), loadGameRemaps())
        keyboard?.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    /** Back to the keyboard pads (Game Mode's KBD button or reverse Konami). */
    private fun showKeyboard() {
        gameMode?.let { it.exitRemapMode(); it.releaseAll(); it.visibility = View.GONE }
        keyboard?.visibility = View.VISIBLE
    }

    private fun isGameModeVisible(): Boolean = gameMode?.visibility == View.VISIBLE

    /* --- per-game memory of the profile and D-pad output (keyed by folder) --- */

    private fun gameKey(prefix: String): String = prefix + (currentGameFolder ?: "_")

    private fun saveGameProfile(profile: GameModeView.Profile) {
        getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .edit().putString(gameKey(KEY_PROFILE_PREFIX), profile.name).apply()
    }

    private fun loadGameProfile(): GameModeView.Profile {
        val name = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(gameKey(KEY_PROFILE_PREFIX), null) ?: return GameModeView.Profile.HYBRID
        return runCatching { GameModeView.Profile.valueOf(name) }.getOrDefault(GameModeView.Profile.HYBRID)
    }

    private fun saveGameDpad(dpad: GameModeView.Dpad) {
        getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .edit().putString(gameKey(KEY_DPAD_PREFIX), dpad.name).apply()
    }

    private fun loadGameDpad(): GameModeView.Dpad {
        val name = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(gameKey(KEY_DPAD_PREFIX), null) ?: return GameModeView.Dpad.ARROWS
        return runCatching { GameModeView.Dpad.valueOf(name) }.getOrDefault(GameModeView.Dpad.ARROWS)
    }

    /* --- per-game Game Mode button remaps (a small JSON blob) --- */

    /**
     * The button remaps of the current game, keyed by "<profile>:<default key>".
     * One map covers every profile, so a game remembers what each button sends
     * whichever profile it is played in.
     */
    private fun loadGameRemaps(): Map<String, String> {
        val raw = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(gameKey(KEY_REMAPS_PREFIX), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = HashMap<String, String>()
            for (slot in obj.keys()) map[slot] = obj.getString(slot)
            map
        } catch (e: JSONException) {
            Log.w(TAG, "cannot read the Game Mode remaps", e)
            emptyMap()
        }
    }

    private fun saveGameRemaps(remaps: Map<String, String>) {
        val obj = JSONObject()
        for ((slot, key) in remaps) obj.put(slot, key)
        getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .edit().putString(gameKey(KEY_REMAPS_PREFIX), obj.toString()).apply()
    }

    /* --------------------------------------------------------- the launcher */

    /**
     * Opens the game launcher over the emulator.
     *
     * Public so the instrumented test can open it and then press the real
     * back button on it.
     */
    @VisibleForTesting
    fun showLauncher() {
        var launcher = this.launcher
        if (launcher == null) {
            launcher = GameLauncherView(this, object : GameLauncherView.Listener {
                override fun onInstallRequested() = pickZipFile()

                override fun onBrowseRequested() = browsePhoneFiles()

                override fun onPlayRequested(bundle: GameBundle) = play(bundle)

                override fun onEditLaunchRequested(bundle: GameBundle) {
                    this@WhereqDosboxActivity.launcher?.showLaunchEditor(
                        bundle,
                        library.launchScriptFor(bundle),
                        library.listCandidates(bundle),
                    )
                }

                override fun onLaunchChosen(bundle: GameBundle, script: String) {
                    // Save the launch commands and boot straight into them.
                    // Multi-file games (driver TSRs + main exe) need a few tries
                    // to get the sequence right; relaunching on each save makes
                    // that loop fast.
                    try {
                        library.setLaunch(bundle, script)
                    } catch (e: IOException) {
                        this@WhereqDosboxActivity.launcher?.setStatus(
                            "Could not set the launch command: " + e.message
                        )
                        return
                    }
                    play(bundle)
                }

                override fun onDeleteRequested(bundle: GameBundle) = confirmDelete(bundle)

                override fun onExitToPromptRequested() = exitToPrompt()

                override fun onConfigRequested() = showConfigDialog()

                override fun onResumeRequested() = resumeLastGame()

                override fun onSaveStateRequested(bundle: GameBundle, slot: Int) =
                    // From Game Mode the launcher's status line is not on screen, so
                    // the save has to announce itself with a toast instead.
                    saveState(bundle, slot, announce = isGameModeVisible())

                override fun onLoadStateRequested(bundle: GameBundle, slot: Int) =
                    loadState(bundle, slot)

                override fun stateSlotLabel(bundle: GameBundle, slot: Int): String? =
                    slotLabel(bundle, slot)

                override fun hasSavesFolder(): Boolean = saveStore.hasFolder()

                override fun onConnectSavesFolderRequested(bundle: GameBundle) =
                    connectSavesFolder(bundle)

                override fun onCloseRequested() = closeLauncher()

                override fun onOpenSourceLicensesRequested() = openLicenses()

                override fun onPrivacyPolicyRequested() = openPrivacyPolicy()
            })

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addContentView(launcher, lp)
            this.launcher = launcher
        }
        launcher.showFiles(library.list())
        refreshResumeButton()
        launcher.setCurrentGame(currentGameFolder)
        launcher.setStatus("")
        launcher.visibility = View.VISIBLE
    }

    /**
     * Updates the "Resume" button: the last-played game and what it will
     * resume from (its newest save, if it has one).
     */
    private fun refreshResumeButton() {
        val folder = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_GAME, null)
        val bundle = library.list().firstOrNull { it.folder == folder }
        val slot = bundle?.let { newestSlot(it) }
        val detail = if (bundle != null && slot != null) {
            val age = slotLabel(bundle, slot)
            if (age == null) null else "slot $slot · $age"
        } else {
            null
        }
        launcher?.setResumeGame(bundle?.displayName, detail)
    }

    /**
     * Relaunches into the last-played game (the "Resume" button).
     *
     * "Resume" means *resume*: when the game has a saved slot, the newest one
     * is restored, so the player continues exactly where they left off instead
     * of starting the game from the beginning. Without a slot the game is
     * simply started again.
     *
     * The emulator keep-alive handles the common case of returning to a
     * backgrounded game; this covers the process actually having been killed.
     */
    private fun resumeLastGame() {
        val folder = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_GAME, null) ?: return
        val bundle = library.list().firstOrNull { it.folder == folder } ?: return
        val slot = newestSlot(bundle)
        Log.i(TAG, "resume " + bundle.displayName + " from slot " + (slot ?: "none"))
        relaunch(bundle.folder, slot)
    }

    /**
     * @return the slot with the most recent save for this game, or null when
     * the game has no save at all.
     */
    private fun newestSlot(bundle: GameBundle): Int? {
        var newest: Int? = null
        var newestTime = 0L
        for (slot in 1..SaveStore.STATE_SLOTS) {
            val local = saveStore.localSlot(bundle.folder, slot)
            val time = if (local.exists() && local.length() > 0L) {
                local.lastModified()
            } else {
                saveStore.durableTimestamp(bundle.displayName, slot) ?: continue
            }
            if (time > newestTime) {
                newestTime = time
                newest = slot
            }
        }
        return newest
    }

    private fun closeLauncher() {
        launcher?.visibility = View.GONE
    }

    /**
     * Whether the game launcher is on screen.
     *
     * The instrumented test needs this to check what a back press did: with
     * the launcher open the press must close it, with it closed the press must
     * leave the app.
     */
    @VisibleForTesting
    fun isLauncherVisible(): Boolean = launcher?.visibility == View.VISIBLE

    /**
     * Opens the privacy policy.
     *
     * WhereQ-DOSBox collects no data and has no INTERNET permission, but the
     * policy still has to be reachable from inside the app and not only from a
     * website. The app is published from its own repository, so the policy is
     * a file in that repository - docs/PRIVACY.md - and the launcher footer
     * links to it. The same text lives in the repository, which is the
     * authoritative copy.
     *
     * The app has no INTERNET permission of its own, so it cannot fetch the
     * page itself - it hands the URL to the browser, which is the normal way
     * to do this and keeps the app free of network code.
     */
    private fun openPrivacyPolicy() {
        val url = getString(R.string.privacy_policy_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the privacy policy", e)
            Toast.makeText(this, "Could not open $url", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the licence screen.
     *
     * WhereQ-DOSBox embeds the DOSBox core, which is GPLv2. The licence and the
     * notices have to travel with the program, and the complete source has to
     * be reachable from the app - the screen carries the first and links to
     * the second.
     */
    private fun openLicenses() {
        try {
            startActivity(Intent(this, LicensesActivity::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "could not open the licence screen", e)
            Toast.makeText(this, "Could not open the licences", Toast.LENGTH_LONG).show()
        }
    }

    /* ------------------------------------------------------------ save states */

    /** @return a short "time ago" label for a saved slot (local or the durable
     *  mirror), or null when the slot is empty everywhere. */
    private fun slotLabel(bundle: GameBundle, slot: Int): String? {
        val time = slotTime(bundle, slot) ?: return null
        return relativeSlotTime(time)
    }

    /**
     * When a slot was last written, or null when it holds nothing.
     *
     * The local copy is the working one; the durable mirror is only consulted
     * when the local copy is gone, which is what happens after a reinstall.
     */
    private fun slotTime(bundle: GameBundle, slot: Int): Long? {
        val local = saveStore.localSlot(bundle.folder, slot)
        if (local.exists() && local.length() > 0L) return local.lastModified()
        return saveStore.durableTimestamp(bundle.displayName, slot)
    }

    /**
     * How long ago a save was made, as a label for a slot.
     *
     * The relative wording ("5 min ago", "2 hours ago") is the friendliest thing
     * for a save made today. It stops working the moment the granularity
     * reaches a day, though: two slots saved yesterday both read "yesterday",
     * and there is no way to tell which of the two is the newer one. So
     * anything from a previous day carries the clock time as well - "Yesterday
     * 21:40", "Nov 25 09:12" - and the slots can be put in order by eye.
     */
    private fun relativeSlotTime(time: Long): String {
        val now = System.currentTimeMillis()
        if (now - time < DateUtils.MINUTE_IN_MILLIS) {
            return "just now"
        }
        if (isSameDay(time, now)) {
            return DateUtils.getRelativeTimeSpanString(
                time, now, DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
        val day = when {
            isSameDay(time, startOfYesterday(now)) -> "Yesterday"
            isSameYear(time, now) ->
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
            else -> SimpleDateFormat("MMM d yyyy", Locale.getDefault()).format(Date(time))
        }
        return "$day $clock"
    }

    /** Whether two instants fall on the same calendar day. */
    private fun isSameDay(a: Long, b: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = a
        val year = cal.get(Calendar.YEAR)
        val day = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = b
        return year == cal.get(Calendar.YEAR) && day == cal.get(Calendar.DAY_OF_YEAR)
    }

    /** Whether an instant falls in the same calendar year as another. */
    private fun isSameYear(a: Long, b: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = a
        val year = cal.get(Calendar.YEAR)
        cal.timeInMillis = b
        return year == cal.get(Calendar.YEAR)
    }

    /** Midnight at the start of the day before [now]. */
    private fun startOfYesterday(now: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Snapshots the running machine into a slot. The native call hands the work
     * to the DOSBox thread and blocks until it is done, so it runs off the UI
     * thread. If a durable saves folder is set, the slot is also mirrored there
     * (gzip-compressed) so it survives a reinstall.
     *
     * @param announce show the result in a toast as well - used when the
     *   launcher is not on screen, so the gamepad's own SAVE button can say
     *   what it did.
     */
    private fun saveState(bundle: GameBundle, slot: Int, announce: Boolean = false) {
        launcher?.setStatus("Saving slot $slot…")
        val target = saveStore.localSlot(bundle.folder, slot)
        // A save takes a moment to write, and the file only appears at the end.
        // Marking the slot as taken right away keeps a second quick save from
        // picking the slot that is still being written.
        savingSlots.add(slot)
        workScope.launch {
            try {
                val rc = withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    DosboxNative.nativeSaveState(target.absolutePath)
                }
                if (rc == DosboxNative.SAVESTATE_OK && saveStore.hasFolder()) {
                    launcher?.setStatus("Saved slot $slot · backing up…")
                    withContext(Dispatchers.IO) {
                        saveStore.mirrorToDurable(bundle.displayName, bundle.folder, slot)
                    }
                }
                val message = saveStateMessage(rc, slot)
                launcher?.setStatus(message)
                if (announce) {
                    Toast.makeText(this@WhereqDosboxActivity, message, Toast.LENGTH_SHORT).show()
                }
                // The slot that was just written is the newest one, so the "Resume"
                // button has to say so - otherwise it still names the slot it would
                // have used before.
                refreshResumeButton()
                // Offer, once, to keep saves across reinstalls.
                if (rc == DosboxNative.SAVESTATE_OK) {
                    maybeOfferSavesFolder()
                }
            } finally {
                savingSlots.remove(slot)
            }
        }
    }

    /**
     * The game that is running right now, or null when nothing is.
     */
    private fun runningBundle(): GameBundle? {
        val folder = currentGameFolder ?: return null
        return library.list().firstOrNull { it.folder == folder }
    }

    /**
     * The slot a quick save should write to: the first one that is still free,
     * and - when they are all full - the oldest, so the four newest saves are
     * the ones that survive.
     */
    private fun nextFreeSlot(bundle: GameBundle): Int {
        var oldestSlot = 1
        var oldestTime = Long.MAX_VALUE
        for (slot in 1..SaveStore.STATE_SLOTS) {
            if (savingSlots.contains(slot)) continue
            val time = slotTime(bundle, slot)
            if (time == null) return slot
            if (time < oldestTime) {
                oldestTime = time
                oldestSlot = slot
            }
        }
        return oldestSlot
    }

    /**
     * The gamepad's SAVE button: snapshot the running game into a slot without
     * going back to the keyboard pads for the Save states sheet.
     */
    private fun quickSaveFromGameMode() {
        val bundle = runningBundle()
        if (bundle == null) {
            Toast.makeText(
                this, "Start a game first — there is nothing to save yet", Toast.LENGTH_SHORT
            ).show()
            return
        }
        saveState(bundle, nextFreeSlot(bundle), announce = true)
    }

    /**
     * The gamepad's SAVE button, held: open the Save states sheet over the
     * gamepad, so a slot can be picked. Game Mode is still there behind it.
     */
    private fun openStatesFromGameMode() {
        val bundle = runningBundle()
        if (bundle == null) {
            Toast.makeText(
                this, "Start a game first — there is nothing to save yet", Toast.LENGTH_SHORT
            ).show()
            return
        }
        // The sheet is a dialog of its own, so it can float over the gamepad on
        // its own: the Files panel behind it would only hide the game the player
        // is in the middle of. The launcher view stays hidden, and the gamepad
        // is what the player sees again once the sheet is closed.
        if (launcher == null) showLauncher()
        launcher?.setCurrentGame(currentGameFolder)
        launcher?.visibility = View.GONE
        launcher?.openStates(bundle)
    }

    /**
     * Restores a slot, replacing the running machine (so it works from within a
     * game or from a fresh prompt). On success the launcher closes so the player
     * sees the restored game. If the local copy is gone (after a reinstall) it is
     * first pulled back from the durable mirror.
     */
    private fun loadState(bundle: GameBundle, slot: Int) {
        launcher?.setStatus("Loading slot $slot…")
        val target = saveStore.localSlot(bundle.folder, slot)
        workScope.launch {
            val rc = withContext(Dispatchers.IO) {
                if (!target.exists()) {
                    saveStore.restoreFromDurable(bundle.displayName, bundle.folder, slot)
                }
                if (!target.exists()) SAVESTATE_NO_SLOT
                else DosboxNative.nativeLoadState(target.absolutePath)
            }
            if (rc == DosboxNative.SAVESTATE_OK) {
                // The restored machine *is* that game now, so the launcher must
                // offer "Save" for it - otherwise a slot could only be written
                // after a normal launch, and a game that was only ever restored
                // could not be saved at all.
                currentGameFolder = bundle.folder
                launcher?.setCurrentGame(bundle.folder)
                // The restored game is also the one to resume after a restart.
                getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_LAST_GAME, bundle.folder).apply()
                refreshResumeButton()
                closeLauncher()
            } else if (rc == SAVESTATE_NO_SLOT) {
                launcher?.setStatus("Slot $slot is empty")
            } else {
                launcher?.setStatus(loadStateMessage(rc))
            }
        }
    }

    /**
     * Restores a saved slot right after the boot ("Resume").
     *
     * The saved machine *is* the game, so the boot does not start the game at
     * all - it only mounts C: - and the restore brings the game back. The
     * native side holds the request until the guest is running, so the call
     * can be made right away.
     *
     * If the slot cannot be restored (a save from another machine setup, a
     * damaged file) the game is started the normal way instead, so the player
     * is never left staring at a bare DOS prompt.
     */
    private fun restoreAfterBoot(folder: String, slot: Int) {
        val name = library.list().firstOrNull { it.folder == folder }?.displayName
        workScope.launch {
            val target = saveStore.localSlot(folder, slot)
            val ready = withContext(Dispatchers.IO) {
                if (!target.exists() && name != null) {
                    saveStore.restoreFromDurable(name, folder, slot)
                }
                target.exists()
            }
            if (!ready) {
                Log.w(TAG, "auto-restore: slot $slot of $folder is gone - starting the game instead")
                relaunch(folder, null)
                return@launch
            }
            val rc = withContext(Dispatchers.IO) {
                DosboxNative.nativeLoadState(target.absolutePath)
            }
            if (rc == DosboxNative.SAVESTATE_OK) {
                Log.i(TAG, "auto-restore of $folder slot $slot done")
            } else {
                Log.w(TAG, "auto-restore of $folder slot $slot failed ($rc) - " +
                    "starting the game instead")
                relaunch(folder, null)
            }
        }
    }

    /* ------------------------------------------------- the durable folder */

    /**
     * Offers, once, to set a durable saves folder the first time a save
     * succeeds. If the user declines it is not asked again (the choice is
     * reachable from Config → Saves folder).
     */
    private fun maybeOfferSavesFolder() {
        if (saveStore.hasFolder()) return
        val prefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SAVES_FOLDER_OFFERED, false)) return
        prefs.edit().putBoolean(KEY_SAVES_FOLDER_OFFERED, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep your saves safe?")
            .setMessage(
                "Pick a folder on your phone and WhereQ-DOSBox will also store your " +
                    "save-states there, compressed. They then survive " +
                    "reinstalling the app or moving to a new phone. You can set " +
                    "this later from Config → Saves folder."
            )
            .setPositiveButton("Choose folder") { _, _ -> pickSavesFolder() }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Connect a saves folder from a game's States dialog, then reopen it so the
     *  game's saves (from a previous install) show up. */
    private fun connectSavesFolder(bundle: GameBundle) {
        pendingStatesGame = bundle
        pickSavesFolder()
    }

    /** Opens the system folder picker to choose the durable saves folder. */
    private fun pickSavesFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadRootUri())
        try {
            startActivityForResult(intent, REQUEST_PICK_SAVES_FOLDER)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the folder picker", e)
            Toast.makeText(this, "No folder picker available", Toast.LENGTH_LONG).show()
        }
    }

    /** Mirrors every existing local slot into the (just chosen) durable folder. */
    private fun backUpAllSlotsToDurable() {
        val bundles = library.list()
        workScope.launch(Dispatchers.IO) {
            for (bundle in bundles) {
                for (slot in 1..SaveStore.STATE_SLOTS) {
                    if (saveStore.localSlot(bundle.folder, slot).exists()) {
                        saveStore.mirrorToDurable(bundle.displayName, bundle.folder, slot)
                    }
                }
                saveStore.mirrorGame(bundle, library.folderOf(bundle))
            }
        }
    }

    /** Mirrors a freshly installed game to the durable folder (called on the IO
     *  thread from the install paths). No-op when no folder is set. */
    private fun mirrorGameIfDurable(bundle: GameBundle) {
        if (saveStore.hasFolder()) {
            saveStore.mirrorGame(bundle, library.folderOf(bundle))
        }
    }

    /**
     * After a saves folder is chosen, offers to re-install any games that live
     * in it but are not installed locally (the one-tap recovery after a
     * reinstall). Matched by game name.
     */
    private fun offerRestoreGames() {
        workScope.launch {
            val durable = withContext(Dispatchers.IO) { saveStore.durableGames() }
            val have = library.list().mapTo(HashSet()) { it.displayName }
            val missing = durable.filter { it.name.isNotEmpty() && it.name !in have }
            if (missing.isEmpty()) {
                return@launch
            }
            val n = missing.size
            AlertDialog.Builder(this@WhereqDosboxActivity)
                .setTitle("Restore games?")
                .setMessage(
                    "This folder has $n game${if (n == 1) "" else "s"} that " +
                        (if (n == 1) "is" else "are") + " not installed. Restore " +
                        (if (n == 1) "it" else "them") + " now? Your saves for " +
                        (if (n == 1) "it" else "them") + " will work again too."
                )
                .setPositiveButton("Restore") { _, _ -> restoreGames(missing) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun restoreGames(games: List<SaveStore.DurableGame>) {
        launcher?.setStatus("Restoring ${games.size} game(s)…")
        workScope.launch {
            var done = 0
            for (g in games) {
                withContext(Dispatchers.IO) {
                    try {
                        saveStore.openDurableGame(g.uri)?.use { input ->
                            library.restore(g.name, g.entry, g.launch, g.ownConfig, g.archive, input)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "could not restore game ${g.name}", e)
                    }
                }
                done++
                launcher?.setStatus("Restoring games… ($done/${games.size})")
            }
            launcher?.showFiles(library.list())
            refreshResumeButton()
            launcher?.setStatus("Restored $done game(s). Your saves are ready.")
        }
    }

    /** Config → Saves folder: show the state and let the user set/change it. */
    private fun showSavesFolderDialog() {
        val status = if (saveStore.hasFolder()) {
            "A folder is set — your saves are backed up there (compressed)."
        } else {
            "No folder set — saves are kept only inside the app and are lost if it is uninstalled."
        }
        AlertDialog.Builder(this)
            .setTitle("Saves folder")
            .setMessage(
                status + "\n\nPick a folder and WhereQ-DOSBox mirrors every save-state " +
                    "there so it survives a reinstall or a new phone."
            )
            .setPositiveButton(if (saveStore.hasFolder()) "Change folder" else "Choose folder") { _, _ ->
                pickSavesFolder()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveStateMessage(rc: Int, slot: Int): String = when (rc) {
        DosboxNative.SAVESTATE_OK -> "Saved to slot $slot"
        DosboxNative.SAVESTATE_ERR_TIMEOUT -> "Save timed out — is a game running?"
        DosboxNative.SAVESTATE_ERR_BUSY -> "Busy — try again in a moment"
        else -> "Could not save (error $rc)"
    }

    private fun loadStateMessage(rc: Int): String = when (rc) {
        DosboxNative.SAVESTATE_ERR_MISMATCH -> "That save is for a different machine setup"
        DosboxNative.SAVESTATE_ERR_FORMAT -> "That save file is damaged"
        DosboxNative.SAVESTATE_ERR_TIMEOUT -> "Load timed out — is the emulator running?"
        DosboxNative.SAVESTATE_ERR_BUSY -> "Busy — try again in a moment"
        else -> "Could not load (error $rc)"
    }

    /** Removes a game's save-state slots (called when the game is deleted). */
    private fun deleteStateDir(bundle: GameBundle) {
        // Only the local working copies are removed; the durable mirror in the
        // user's folder is theirs to keep or delete.
        saveStore.deleteLocal(bundle.folder)
    }

    /** Asks before a game is removed — the bundle is deleted from the phone. */
    private fun confirmDelete(bundle: GameBundle) {
        AlertDialog.Builder(this)
            .setTitle("Remove " + bundle.displayName + "?")
            .setMessage("The unpacked game is deleted from the phone.")
            .setPositiveButton("Remove") { _, _ ->
                library.delete(bundle)
                deleteStateDir(bundle)
                // Forget the resume target if it was this game.
                val prefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
                if (prefs.getString(KEY_LAST_GAME, null) == bundle.folder) {
                    prefs.edit().remove(KEY_LAST_GAME).apply()
                }
                launcher?.showFiles(library.list())
                refreshResumeButton()
                launcher?.setStatus("Removed " + bundle.displayName)
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /* ---------------------------------------------------------- installing */

    /**
     * Opens the system file picker for a zip archive.
     *
     * The intent has to carry a MIME type: the filter of the system picker
     * declares one, so an intent without a type does not resolve at all and the
     * picker never opens. The type is therefore the general one and the
     * interesting types are handed over separately, which lets the picker show
     * every file but mark the archives.
     */
    private fun pickZipFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        // A lot of file providers do not report a zip as application/zip, so the
        // type list is wide on purpose.
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "application/x-compressed",
                "application/x-zip",
            )
        )
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The bundles are usually in the download folder, so the picker opens
        // right there instead of in the "recent files" list.
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadRootUri())
        try {
            startActivityForResult(intent, REQUEST_PICK_ZIP)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no document picker, trying the content picker", e)
            pickZipFileLegacy()
        } catch (e: Exception) {
            Log.w(TAG, "could not open the file picker", e)
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The older way to ask for a file, used when the device has no document
     * picker.
     */
    private fun pickZipFileLegacy() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQUEST_PICK_ZIP)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the content picker", e)
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Shows the zip archives of a folder on the phone.
     *
     * The user picks a folder once and every archive in it is installed — the
     * quick way to get a whole collection of DOS programs onto the phone.
     */
    private fun browsePhoneFiles() {
        BundleFolder.pickFolder(this, REQUEST_PICK_FOLDER)
    }

    /** Installs every archive of the folder the user picked. */
    private fun importFolder(treeUri: Uri) {
        launcher?.setStatus("Looking for bundles…")
        workScope.launch {
            installMutex.withLock {
                val entries = withContext(Dispatchers.IO) {
                    BundleFolder.collect(contentResolver, treeUri)
                }
                val total = entries.size
                var installed = 0
                var failed = 0
                val error = if (total == 0) "no .zip bundle found in that folder" else null

                for (entry in entries) {
                    try {
                        withContext(Dispatchers.IO) {
                            entry.open(contentResolver).use { input ->
                                mirrorGameIfDurable(library.install(input, entry.name))
                            }
                        }
                        installed++
                    } catch (e: Exception) {
                        failed++
                        Log.e(TAG, "could not install " + entry.name, e)
                    }
                    val done = installed + failed
                    // Back on the main thread (Main.immediate) for the UI update.
                    launcher?.setStatus("Installing ${entry.name} ($done of $total)…")
                }

                val launcher = this@WhereqDosboxActivity.launcher
                if (launcher != null) {
                    val bundles = withContext(Dispatchers.IO) { library.list() }
                    launcher.showFiles(bundles)
                    launcher.setStatus(
                        error ?: ("Installed " + installed + " bundle" +
                            (if (installed == 1) "" else "s") +
                            (if (failed > 0) ", $failed failed" else ""))
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_SAVES_FOLDER) {
            // Cleared here so a cancelled pick does not leave it dangling.
            val reopen = pendingStatesGame
            pendingStatesGame = null
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "could not persist the saves-folder permission", e)
                }
                saveStore.folderUri = uri
                backUpAllSlotsToDurable()
                // Refresh so slot labels pick up the newly connected folder.
                launcher?.showFiles(library.list())
                if (reopen != null) {
                    // Connected from a game's States dialog: reopen it so the
                    // game's saves from a previous install show up now.
                    Toast.makeText(
                        this, "Saves folder connected — your saves are back.",
                        Toast.LENGTH_LONG
                    ).show()
                    launcher?.openStates(reopen)
                } else {
                    Toast.makeText(
                        this, "Saves folder set — your games and saves are backed up here.",
                        Toast.LENGTH_LONG
                    ).show()
                    // If this folder already has games from a previous install,
                    // offer to bring them back.
                    offerRestoreGames()
                }
            }
            return
        }
        if (requestCode == REQUEST_PICK_FOLDER) {
            if (resultCode != RESULT_OK || data?.data == null) {
                return
            }
            importFolder(data.data!!)
            return
        }
        if (requestCode != REQUEST_PICK_ZIP) {
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) {
            return
        }
        val uri = data.data!!
        val name = displayNameOf(uri)
        install(
            ArchiveSource {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("the file could not be opened")
            },
            name
        )
    }

    /**
     * Unpacks a picked archive into the app.
     *
     * The archive is read on a background thread — a bundle is a few megabytes
     * and the emulator must not freeze while it is unpacked.
     *
     * @param source   opens the archive.
     * @param nameHint the file name, when it is already known.
     */
    private fun install(source: ArchiveSource, nameHint: String?) {
        launcher?.setStatus("Unpacking…")
        val name = if (nameHint != null) stripExtension(nameHint) else "game"
        workScope.launch {
            var installed: String? = null
            val error = installMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val bundle = source.open().use { input -> library.install(input, name) }
                        // The display name can differ from the archive name: a
                        // second archive with the same name is installed as
                        // "name (2)", and that is the name the launcher shows.
                        installed = bundle.displayName
                        mirrorGameIfDurable(bundle)
                        null
                    } catch (e: Exception) {
                        Log.e(TAG, "could not install $name", e)
                        if (e.message == null) e.toString() else e.message
                    }
                }
            }
            val launcher = this@WhereqDosboxActivity.launcher ?: return@launch
            val bundles = withContext(Dispatchers.IO) { library.list() }
            launcher.showFiles(bundles)
            launcher.setStatus(
                if (error == null) "Installed “${installed ?: name}”. Tap Play to launch — it stays installed, no need to add it again."
                else "Could not install $name: $error"
            )
        }
    }

    /** @return the name of the picked file, without the .zip suffix. */
    private fun displayNameOf(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the name of the picked file", e)
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        if (name == null) {
            name = "game"
        }
        return stripExtension(name!!)
    }

    /* -------------------------------------------------------------- playing */

    /**
     * Launches a game by relaunching the app into a fresh DOSBox boot that
     * auto-runs it.
     *
     * Why a relaunch and not "type the command into the running DOSBox": a DOS
     * program cannot be terminated from the outside, so typing a second game's
     * command just feeds it to whatever is already running — and, worse, DOSBox
     * mounts C: once and caches its directory listing, so a bundle installed
     * after boot is invisible to an installer that enumerates its files. A fresh
     * process gets a fresh mount (cache includes the bundle) and a guaranteed
     * clean slate. The DOS files live in the persistent app directory, so game
     * saves survive the relaunch.
     *
     * The target folder is recorded in SharedPreferences; [onCreate] reads it on
     * the next boot, writes it into the `[autoexec]` of the config, and clears
     * it (so a crash lands back at the prompt rather than looping).
     */
    private fun play(bundle: GameBundle) {
        Log.i(TAG, "launching " + bundle.displayName + " (" + bundle.folder + ") via relaunch")
        relaunch(bundle.folder)
    }

    /**
     * Exits whatever is running back to a clean `C:\>` prompt.
     *
     * This is the escape hatch for a game that hangs or leaves a black screen:
     * a DOS program cannot be stopped from the outside, so the only reliable way
     * out is a fresh boot with nothing queued. Always reachable from the File
     * launcher, which is an Android overlay independent of the DOSBox thread.
     */
    private fun exitToPrompt() {
        Log.i(TAG, "exiting to a clean prompt via relaunch")
        relaunch(null)
    }

    /**
     * Relaunches the app into a completely fresh process (and thus a fresh
     * DOSBox/SDL + a freshly mounted C:). [pendingFolder] is the games folder to
     * auto-run on the next boot, or null to land at the prompt. [stateSlot] is
     * a save-state slot to restore on the next boot instead of starting the
     * game from the beginning (null = start it normally). The values are
     * written synchronously so they survive the process tear-down below.
     */
    private fun relaunch(pendingFolder: String?, stateSlot: Int? = null) {
        val editor = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE).edit()
        if (pendingFolder != null) {
            editor.putString(KEY_PENDING_LAUNCH, pendingFolder)
            editor.putInt(KEY_PENDING_STATE, stateSlot ?: 0)
            // Remember it as the game to offer "Resume" for after a cold start.
            editor.putString(KEY_LAST_GAME, pendingFolder)
        } else {
            editor.remove(KEY_PENDING_LAUNCH)
            editor.remove(KEY_PENDING_STATE)
        }
        editor.commit() // synchronous: must be on disk before the process dies

        // The keep-alive service lives in this process; stop it before the tear
        // down (it is START_NOT_STICKY, so the OS will not revive it). The next
        // process starts a fresh one in onCreate only if a game is pending.
        DosboxForegroundService.stop(this)

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "Could not relaunch the emulator", Toast.LENGTH_LONG).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        // Tear the current process (and its DOSBox/SDL native state) down so the
        // new one boots completely fresh.
        Runtime.getRuntime().exit(0)
    }

    /**
     * Registers the predictive-back callback (API 33+).
     *
     * From Android 13 on, an app that targets API 33 or newer no longer gets
     * `onBackPressed()` called and no longer receives `KEYCODE_BACK` when the
     * back gesture or the back key is used. The system hands the gesture to the
     * callback registered here instead, which is why this is the path that
     * carries the app's own back handling on modern Android.
     *
     * Older systems have no such dispatcher and keep using the key event and
     * `onBackPressed()` paths below, so the behaviour is the same everywhere.
     */
    private fun registerBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val callback = OnBackInvokedCallback { handleBackOrFinish() }
        backInvokedCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
    }

    /**
     * Handles one back press.
     *
     * @return true if the press was used by the app (the launcher was closed or
     *   the keyboard folded away), false if there was nothing to dismiss and
     *   the press should leave the app.
     */
    private fun handleBack(): Boolean {
        val launcher = this.launcher
        val keyboard = this.keyboard
        val action = resolveBackAction(
            launcherVisible = launcher != null && launcher.visibility == View.VISIBLE,
            gameModeVisible = isGameModeVisible(),
            keyboardPanelVisible = keyboard != null && keyboard.isPanelVisible()
        )
        when (action) {
            BackAction.CLOSE_LAUNCHER -> {
                closeLauncher()
                return true
            }
            BackAction.EXIT_GAME_MODE -> {
                // The gamepad covers the keyboard pads, so it has to go back to
                // the keyboard before the keyboard can be folded.
                showKeyboard()
                return true
            }
            BackAction.FOLD_KEYBOARD -> {
                keyboard?.hidePanel()
                return true
            }
            BackAction.FINISH -> return false
        }
    }

    /**
     * Handles a back press that reached us through the predictive-back
     * callback: the app dismisses what it can, and leaves otherwise.
     */
    private fun handleBackOrFinish() {
        if (!handleBack()) {
            finish()
        }
    }

    /**
     * Forwards the hardware keyboard into DOSBox.
     *
     * SDL2's Android back end has no path for hardware keys at all: the Java
     * layer only feeds the IME, and the native side never sees an `AKEY_EVENT`.
     * A key pressed on a Bluetooth or USB keyboard therefore never reaches the
     * emulator - and neither does an injected `adb shell input keyevent`.
     *
     * [HardwareKeyboard] pushes those keys into DOSBox's event queue, the same
     * way the on-screen keyboard does, so a phone with a keyboard can play the
     * games whose start key is not on the on-screen keyboard face - Mortal
     * Kombat starts on F1.
     *
     * Keys DOSBox does not know - the back button, the volume keys - keep
     * travelling through the normal Android dispatch, so the app's own
     * handling of them is unchanged.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (forwardHardwareKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Hands one key event to DOSBox.
     *
     * @return true if DOSBox took the key.
     */
    private fun forwardHardwareKey(event: KeyEvent): Boolean {
        // The launch-script editor is a text field: its keystrokes belong to
        // the field and not to the game behind it.
        if (currentFocus is EditText) {
            return false
        }
        return HardwareKeyboard.forward(event)
    }

    /**
     * The legacy system back path, for Android 12 and older.
     *
     * The back button folds the keyboard away before it leaves the app, and
     * closes the launcher when it is open.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBack()) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * The legacy system back path, for Android 12 and older.
     *
     * SDLActivity's implementation would end the activity straight away, which
     * would leave the launcher and the keyboard on screen for good, so the
     * app's own handling gets the first turn.
     */
    @Deprecated("The predictive-back callback is the modern path; this keeps the old one working.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (handleBack()) {
            return
        }
        // Nothing of ours is open, so the press leaves the app. finish() is
        // called directly rather than through SDLActivity.onBackPressed(),
        // which would end up back in the back dispatcher on API 33+.
        finish()
    }

    override fun onDestroy() {
        // Stop keeping the process alive and cancel pending install/import work
        // so nothing touches the activity after it is gone.
        DosboxForegroundService.stop(this)
        workScope.cancel()
        // Let go of every key and mouse button the gamepad is holding, so
        // nothing stays down inside DOSBox after the activity is gone.
        gameMode?.releaseAll()
        backInvokedCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        backInvokedCallback = null
        super.onDestroy()
    }

    /** Requests the notification permission (API 33+) so the keep-alive service's
     *  ongoing notification is visible. The service runs either way. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    /** Full-screen, no status bar / navigation bar (immersive, swipe to peek). */
    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Writes the DOSBox configuration for this boot.
     *
     * The config is regenerated from the asset template every boot (so shipped
     * changes — e.g. core=auto — always take effect, and the file cannot drift).
     * The one exception is the CPU core the user picked in the settings, which
     * is written into the template; a hand-edited dosbox.conf would be lost.
     * The template's `[autoexec]` ends with `mount c .` + `c:`; when a game is
     * pending, its `RUN.BAT` is appended so DOSBox boots straight into it.
     *
     * [autostartGame] is false when a save-state slot is restored on this boot:
     * the saved machine *is* the game, so starting it as well would only flash
     * its intro before the restore replaces it.
     *
     * The DOS files live in the app's files directory, which is mounted as C:,
     * so this only rewrites settings — it never touches the games or saves.
     */
    private fun writeConfig(
        filesDir: File,
        pendingLaunch: String?,
        autostartGame: Boolean = true
    ) {
        val target = File(filesDir, CONF_NAME)
        try {
            val base = assets.open(CONF_ASSET).use { it.readBytes() }
            // The config is regenerated on every boot, so a hand-edited
            // dosbox.conf would be lost — the CPU core the user picked in the
            // settings is written into it instead (see showConfigDialog).
            val core = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
                .getString(KEY_CPU_CORE, null)
            val config = String(base, Charsets.ISO_8859_1)
            val text = if (core != null) applyCpuCore(config, core) else config
            FileOutputStream(target).use { out ->
                out.write(text.toByteArray(Charsets.ISO_8859_1))
                if (pendingLaunch != null && autostartGame) {
                    // DOS line endings; ISO-8859-1 matches how RUN.BAT is written.
                    val autostart = "\r\nC:\\" + GameBundle.DOS_GAMES_DIR + "\\" +
                        pendingLaunch + "\\RUN.BAT\r\n"
                    out.write(autostart.toByteArray(Charsets.ISO_8859_1))
                }
            }
            Log.i(
                TAG,
                "wrote " + target.absolutePath +
                    (if (pendingLaunch != null) " (autostart $pendingLaunch)" else "") +
                    (if (core != null) " (core $core)" else "")
            )
        } catch (e: IOException) {
            Log.e(TAG, "could not write $CONF_NAME", e)
        }
    }

    /**
     * Replaces the `core=` line of the shipped configuration with [core].
     *
     * Only a line that starts with `core=` is touched, so the comment block
     * above it keeps explaining the setting.
     */
    private fun applyCpuCore(config: String, core: String): String =
        config.replace(Regex("(?m)^core=.*$"), "core=" + core)

    /**
     * Lets the user pick the DOSBox CPU core.
     *
     * The app ships with `core=auto`: a real-mode program runs on the
     * interpreter, a protected-mode program on the recompiler. A few old DOS
     * programs misbehave on the recompiler, and the documented fallback
     * (`core=normal`) has to be reachable without a text editor — the config
     * file is regenerated on every boot, so a manual edit would be lost.
     *
     * The choice is stored and applied to the next boot; DOSBox cannot switch
     * its core while it runs.
     */
    private fun showConfigDialog() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf("CPU core", "Saves folder")) { _, which ->
                when (which) {
                    0 -> showCpuCoreDialog()
                    1 -> showSavesFolderDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCpuCoreDialog() {
        val current = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(KEY_CPU_CORE, DEFAULT_CPU_CORE) ?: DEFAULT_CPU_CORE
        val checked = CPU_CORES.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("CPU core")
            .setSingleChoiceItems(CPU_CORE_LABELS, checked) { dialog, which ->
                dialog.dismiss()
                val core = CPU_CORES[which]
                getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_CPU_CORE, core)
                    .commit()
                confirmRestartForCore(core)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks whether to restart now, so the new core takes effect. */
    private fun confirmRestartForCore(core: String) {
        AlertDialog.Builder(this)
            .setTitle("CPU core: " + core)
            .setMessage(
                "DOSBox picks its core when it starts, so the new setting " +
                    "takes effect after a restart."
            )
            .setPositiveButton("Restart") { _, _ -> relaunch(null) }
            .setNegativeButton("Later", null)
            .show()
    }

    companion object {
        private const val TAG = "whereq-dosbox"

        /** Name of the native library (without lib prefix / .so suffix). */
        private const val NATIVE_LIB = "dosbox"

        /** SharedPreferences that carry the pending launch across the relaunch. */
        private const val LAUNCH_PREFS = "launch"

        /** The games folder (e.g. "G1") to auto-run on the next boot, or absent. */
        private const val KEY_PENDING_LAUNCH = "pending"

        /** The save-state slot to restore on the next boot (1-based), or 0. */
        private const val KEY_PENDING_STATE = "pending_state"

        /** The last game Played — offered as "Resume" after a cold start. */
        private const val KEY_LAST_GAME = "last_game"

        /** Per-game Game Mode memory: the folder is appended to each prefix. */
        private const val KEY_PROFILE_PREFIX = "gm_profile_"
        private const val KEY_DPAD_PREFIX = "gm_dpad_"
        /** Per-game Game Mode button remaps, stored as a small JSON blob. */
        private const val KEY_REMAPS_PREFIX = "gm_remaps_"

        /** Whether the "keep saves across reinstalls" offer was already shown. */
        private const val KEY_SAVES_FOLDER_OFFERED = "saves_folder_offered"

        /** loadState() sentinel: the slot does not exist (local or durable). */
        private const val SAVESTATE_NO_SLOT = -1

        /** requestPermissions code for POST_NOTIFICATIONS (keep-alive notice). */
        private const val REQUEST_POST_NOTIFICATIONS = 42

        /** The CPU core the user picked in the settings, or absent for the default. */
        private const val KEY_CPU_CORE = "cpu_core"

        /** The CPU cores DOSBox understands, in the order they are offered. */
        private val CPU_CORES = arrayOf("auto", "normal", "dynamic", "simple", "prefetch")

        /** What the user sees for each core. */
        private val CPU_CORE_LABELS = arrayOf(
            "Auto — interpreter for real mode, JIT for protected mode",
            "Normal — the interpreter only",
            "Dynamic — the JIT only",
            "Simple — a small, fast interpreter",
            "Prefetch — the interpreter with a prefetch unit"
        )

        /** The core the app ships with: the one the documentation describes. */
        private const val DEFAULT_CPU_CORE = "auto"

        /** DOSBox configuration file, written into the app's files directory. */
        private const val CONF_NAME = "dosbox.conf"

        /** The configuration template lives in assets/. */
        private const val CONF_ASSET = "dosbox.conf"

        /** Request code of the system file picker. */
        private const val REQUEST_PICK_ZIP = 1

        /** The request code of the folder picker. */
        private const val REQUEST_PICK_FOLDER = 2

        /** The request code of the durable saves-folder picker. */
        private const val REQUEST_PICK_SAVES_FOLDER = 3

        /**
         * @return the document of the download folder, so the picker can start
         *         there. A device that does not know it simply opens the picker
         *         where it would open anyway.
         */
        private fun downloadRootUri(): Uri? = try {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download"
            )
        } catch (e: Exception) {
            null
        }

        /** @return the file name without its extension. */
        private fun stripExtension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
    }
}
