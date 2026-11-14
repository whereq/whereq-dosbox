package com.whereq.dosbox

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

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

    /** The installed bundles. */
    private lateinit var library: GameLibrary

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

        // NOTE: do NOT resize/pad the SDL surface here to make room for the
        // keyboard. Changing the SurfaceView size breaks DOSBox's Android
        // texture renderer (black screen) — the porting log warns the window
        // must not be recreated/resized. The keyboard toolbar overlapping the
        // bottom of the picture is handled a safe way instead (see the overlay
        // keyboard; a native sdl.clip inset is the planned proper fix).

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
        if (pendingLaunch != null) {
            prefs.edit().remove(KEY_PENDING_LAUNCH).commit()
        }
        // Regenerate the config from the asset on every boot: it keeps the
        // shipped settings current (e.g. core=auto reaches existing installs)
        // and appends the autostart line for the pending game.
        writeConfig(filesDir, pendingLaunch)

        library = GameLibrary(filesDir)

        // HOME + working directory must be in place before SDL_main() runs; the
        // SDL thread is only started once the surface is ready, i.e. after
        // onResume(), so doing this here is early enough.
        DosboxNative.nativePrepareEnvironment(filesDir.absolutePath)

        installVirtualKeyboard()

        // A bundle that was opened from a file manager is installed right away —
        // the user asked for it with a tap.
        handleOpenIntent(intent)

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
     * above the SDL `SurfaceView`. It only occupies the strip at the bottom of
     * the screen, so touches anywhere else still reach the emulator — which is
     * what turns a tap into a click inside the DOS picture (SDL turns the touch
     * into a mouse event, DOSBox maps it back through the letterboxed clip
     * rectangle).
     */
    private fun installVirtualKeyboard() {
        val keyboard = VirtualKeyboardView(this)
        keyboard.setOnFilesRequestedListener { showLauncher() }

        // Full-screen overlay: the keyboard positions its control keys in the
        // letterbox bars itself and leaves the DOS picture in the middle free
        // (a tap there falls through to DOSBox as a mouse click).
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        addContentView(keyboard, lp)
        this.keyboard = keyboard
    }

    /* --------------------------------------------------------- the launcher */

    /** Opens the game launcher over the emulator. */
    private fun showLauncher() {
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

                override fun onCloseRequested() = closeLauncher()
            })

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addContentView(launcher, lp)
            this.launcher = launcher
        }
        launcher.showFiles(library.list())
        launcher.setStatus("")
        launcher.visibility = View.VISIBLE
    }

    private fun closeLauncher() {
        launcher?.visibility = View.GONE
    }

    /** Asks before a game is removed — the bundle is deleted from the phone. */
    private fun confirmDelete(bundle: GameBundle) {
        AlertDialog.Builder(this)
            .setTitle("Remove " + bundle.displayName + "?")
            .setMessage("The unpacked game is deleted from the phone.")
            .setPositiveButton("Remove") { _, _ ->
                library.delete(bundle)
                launcher?.showFiles(library.list())
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
                                library.install(input, entry.name)
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
            val error = installMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        source.open().use { input -> library.install(input, name) }
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
                if (error == null) "Installed “$name”. Tap Play to launch — it stays installed, no need to add it again."
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
     * auto-run on the next boot, or null to land at the prompt. The value is
     * written synchronously so it survives the process tear-down below.
     */
    private fun relaunch(pendingFolder: String?) {
        val editor = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE).edit()
        if (pendingFolder != null) {
            editor.putString(KEY_PENDING_LAUNCH, pendingFolder)
        } else {
            editor.remove(KEY_PENDING_LAUNCH)
        }
        editor.commit() // synchronous: must be on disk before the process dies

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
     * The back button folds the keyboard away before it leaves the app, and
     * closes the launcher when it is open.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val launcher = this.launcher
            if (launcher != null && launcher.visibility == View.VISIBLE) {
                closeLauncher()
                return true
            }
            val keyboard = this.keyboard
            if (keyboard != null && keyboard.isPanelVisible()) {
                keyboard.hidePanel()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // Cancel pending install/import work so nothing touches the activity
        // after it is gone.
        workScope.cancel()
        super.onDestroy()
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
     * The template's `[autoexec]` ends with `mount c .` + `c:`; when a game is
     * pending, its `RUN.BAT` is appended so DOSBox boots straight into it.
     *
     * The DOS files live in the app's files directory, which is mounted as C:,
     * so this only rewrites settings — it never touches the games or saves.
     */
    private fun writeConfig(filesDir: File, pendingLaunch: String?) {
        val target = File(filesDir, CONF_NAME)
        try {
            val base = assets.open(CONF_ASSET).use { it.readBytes() }
            FileOutputStream(target).use { out ->
                out.write(base)
                if (pendingLaunch != null) {
                    // DOS line endings; ISO-8859-1 matches how RUN.BAT is written.
                    val autostart = "\r\nC:\\" + GameBundle.DOS_GAMES_DIR + "\\" +
                        pendingLaunch + "\\RUN.BAT\r\n"
                    out.write(autostart.toByteArray(Charsets.ISO_8859_1))
                }
            }
            Log.i(
                TAG,
                "wrote " + target.absolutePath +
                    (if (pendingLaunch != null) " (autostart $pendingLaunch)" else "")
            )
        } catch (e: IOException) {
            Log.e(TAG, "could not write $CONF_NAME", e)
        }
    }

    companion object {
        private const val TAG = "whereq-dosbox"

        /** Name of the native library (without lib prefix / .so suffix). */
        private const val NATIVE_LIB = "dosbox"

        /** SharedPreferences that carry the pending launch across the relaunch. */
        private const val LAUNCH_PREFS = "launch"

        /** The games folder (e.g. "G1") to auto-run on the next boot, or absent. */
        private const val KEY_PENDING_LAUNCH = "pending"

        /** DOSBox configuration file, written into the app's files directory. */
        private const val CONF_NAME = "dosbox.conf"

        /** The configuration template lives in assets/. */
        private const val CONF_ASSET = "dosbox.conf"

        /** Request code of the system file picker. */
        private const val REQUEST_PICK_ZIP = 1

        /** The request code of the folder picker. */
        private const val REQUEST_PICK_FOLDER = 2

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
