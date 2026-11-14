package com.whereq.dosbox

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * The games the user has installed into the app.
 *
 * Layout inside the app's private files directory:
 * ```
 *   games/
 *     games.json        the manifest: what was installed, with what entry point
 *     G1/               one folder per bundle, named after its index
 *       RUN.BAT         the launcher batch file the app types into DOSBox
 *       ...             the unpacked bundle
 * ```
 *
 * The folder names are short and ASCII on purpose: the folder is part of the DOS
 * path of the game, and DOS file names cannot hold the Chinese name of the
 * archive. The Chinese name lives in the manifest and is shown in the launcher.
 *
 * Every bundle gets a `RUN.BAT` that switches to the game folder and starts the
 * entry point. The app launches a game by typing the DOS path of that file into
 * DOSBox, so the command it has to type is always the same length and always
 * ASCII.
 */
class GameLibrary(
    /** The app's private files directory — the root of the C: drive. */
    private val filesDir: File,
) {

    /** @return the directory that holds the installed games. */
    fun gamesDir(): File = File(filesDir, GAMES_DIR)

    /** @return the file that holds the list of installed games. */
    private fun manifestFile(): File = File(filesDir, MANIFEST)

    /** @return the folder of one bundle. */
    fun folderOf(bundle: GameBundle): File = File(gamesDir(), bundle.folder)

    /* --------------------------------------------------------- the manifest */

    /** @return every installed bundle, newest first. */
    fun list(): List<GameBundle> {
        val bundles = ArrayList<GameBundle>()
        val manifest = manifestFile()
        if (!manifest.exists()) {
            return bundles
        }
        try {
            val array = JSONArray(String(manifest.readBytes(), Charsets.UTF_8))
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                bundles.add(
                    GameBundle(
                        obj.getString("folder"),
                        obj.optString("name", obj.getString("folder")),
                        obj.optString("entry", ""),
                        obj.optString("archive", ""),
                        obj.optLong("installedAt", 0),
                        obj.optLong("size", 0),
                        obj.optBoolean("ownConfig", false),
                        obj.optString("launch", "").ifEmpty { null },
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not read the game manifest", e)
        }
        bundles.sortByDescending { it.installedAt }
        return bundles
    }

    @Throws(IOException::class)
    private fun writeManifest(bundles: List<GameBundle>) {
        val array = JSONArray()
        try {
            for (bundle in bundles) {
                val obj = JSONObject()
                obj.put("folder", bundle.folder)
                obj.put("name", bundle.displayName)
                obj.put("entry", bundle.entry)
                obj.put("archive", bundle.archiveName)
                obj.put("installedAt", bundle.installedAt)
                obj.put("size", bundle.sizeBytes)
                obj.put("ownConfig", bundle.hasOwnDosboxConfig)
                bundle.launch?.let { obj.put("launch", it) }
                array.put(obj)
            }
        } catch (e: JSONException) {
            throw IOException("could not build the game manifest", e)
        }
        FileOutputStream(manifestFile()).use { out ->
            out.write(array.toString().toByteArray(Charsets.UTF_8))
        }
    }

    /* ------------------------------------------------------------- installing */

    /**
     * Unpacks a zip archive into a new folder and registers it.
     *
     * @param zip         the archive.
     * @param displayName the name to show in the launcher.
     * @return the new bundle.
     * @throws IOException if the archive cannot be read or is refused.
     */
    @Throws(IOException::class)
    fun install(zip: InputStream, displayName: String): GameBundle {
        val games = gamesDir()
        if (!games.exists() && !games.mkdirs()) {
            throw IOException("could not create " + games.absolutePath)
        }

        // The index is never reused, so a deleted game never takes the folder
        // of an older one — the DOS paths in the manifest stay valid.
        val index = nextIndex()
        val folder = "G$index"
        val target = File(games, folder)
        if (!target.mkdirs()) {
            throw IOException("could not create " + target.absolutePath)
        }

        var unpacked = 0L
        var ownConfig = false
        val used = HashSet<String>()
        try {
            val input = java.util.zip.ZipInputStream(zip)
            var entry: java.util.zip.ZipEntry?
            while (input.nextEntry.also { entry = it } != null) {
                val zipEntry = entry!!
                val name = zipEntry.name
                if (zipEntry.isDirectory) {
                    continue
                }
                // The file is stored under the name DOSBox looks it up with.
                val out = resolveSafely(target, dosPath(name, used))
                if (out == null) {
                    Log.w(TAG, "skipping unsafe entry $name")
                    continue
                }
                val parent = out.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("could not create " + parent.absolutePath)
                }
                if (name.lowercase(Locale.US).endsWith("dosbox.conf")) {
                    ownConfig = true
                }
                unpacked += copy(input, out, zipEntry.size)
                if (unpacked > MAX_UNPACKED_BYTES) {
                    throw IOException(
                        "the archive unpacks to more than " +
                            (MAX_UNPACKED_BYTES / (1024 * 1024)) + " MB"
                    )
                }
                input.closeEntry()
            }
        } catch (e: IOException) {
            deleteRecursively(target)
            throw e
        }

        val entryPoint = detectEntryPoint(target)
        if (entryPoint == null) {
            deleteRecursively(target)
            throw IOException("no .bat, .exe or .com found in the archive")
        }
        // Default launch is the entry only. We deliberately do NOT auto-load
        // driver TSRs: a wrong guess (e.g. a GRPDRV that is a CONFIG.SYS device,
        // not a standalone loader) HANGS instead of exiting cleanly. Running the
        // game alone exits cleanly when it needs more, leaving the user at a
        // prompt to open the "Launch…" editor and add drivers deliberately.
        writeRunBat(target, folder, entryPoint, null)

        val bundle = GameBundle(
            folder, displayName, entryPoint,
            displayName, System.currentTimeMillis(), unpacked, ownConfig,
        )

        val bundles = ArrayList(list())
        bundles.add(bundle)
        try {
            writeManifest(bundles)
        } catch (e: Exception) {
            deleteRecursively(target)
            throw IOException("could not write the game manifest", e)
        }
        Log.i(TAG, "installed $bundle")
        return bundle
    }

    /** Removes a bundle and its folder. */
    fun delete(bundle: GameBundle) {
        val bundles = ArrayList(list())
        for (i in bundles.indices) {
            if (bundles[i].folder == bundle.folder) {
                bundles.removeAt(i)
                break
            }
        }
        try {
            writeManifest(bundles)
        } catch (e: IOException) {
            Log.e(TAG, "could not write the game manifest", e)
        }
        deleteRecursively(folderOf(bundle))
    }

    /** Changes the entry point of a bundle and rewrites its RUN.BAT. */
    @Throws(IOException::class)
    fun setEntryPoint(bundle: GameBundle, relativePath: String) {
        val folder = folderOf(bundle)
        val target = resolveSafely(folder, relativePath.replace('\\', '/'))
        if (target == null || !target.exists()) {
            throw IOException("no such file in the bundle: $relativePath")
        }
        // Picking a single start file clears any custom launch script.
        writeRunBat(folder, bundle.folder, relativePath, null)

        val bundles = ArrayList(list())
        for (i in bundles.indices) {
            if (bundles[i].folder == bundle.folder) {
                val old = bundles[i]
                bundles[i] = GameBundle(
                    old.folder, old.displayName, relativePath, old.archiveName,
                    old.installedAt, old.sizeBytes, old.hasOwnDosboxConfig, null,
                )
                break
            }
        }
        writeManifest(bundles)
    }

    /**
     * @return every file of a bundle that could be started, relative to the
     *         game folder, sorted so the best candidates come first.
     */
    fun listCandidates(bundle: GameBundle): List<String> {
        val candidates = ArrayList<String>()
        collectCandidates(folderOf(bundle), "", candidates)
        return candidates
    }

    private fun collectCandidates(dir: File, prefix: String, out: MutableList<String>) {
        val children = dir.listFiles() ?: return
        children.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        for (child in children) {
            if (child.isDirectory) {
                collectCandidates(child, prefix + child.name + "\\", out)
                continue
            }
            val lower = child.name.lowercase(Locale.US)
            if (lower.endsWith(".bat") || lower.endsWith(".exe") || lower.endsWith(".com")) {
                out.add(prefix + child.name)
            }
        }
    }

    /* ------------------------------------------------------ the entry point */

    /**
     * Picks the file that most likely starts the game.
     *
     * The order follows what the DOS game collections actually look like: a
     * batch file that starts the game comes first (`play.bat`, `run.bat`, ...),
     * then any other batch file, then an executable whose name matches the
     * archive, and finally the biggest executable — for the many bundles whose
     * only entry point is the main program.
     */
    private fun detectEntryPoint(folder: File): String? {
        val candidates = ArrayList<String>()
        collectCandidates(folder, "", candidates)

        // Only the root of the bundle is considered: a start-up file deeper in
        // the tree is data, not the entry point.
        val root = candidates.filter { it.indexOf('\\') < 0 }

        for (preferred in PREFERRED_BATCH_FILES) {
            for (candidate in root) {
                if (candidate.lowercase(Locale.US) == preferred) {
                    return candidate
                }
            }
        }

        val batches = ArrayList<String>()
        val executables = ArrayList<String>()
        for (candidate in root) {
            val lower = candidate.lowercase(Locale.US)
            if (lower.endsWith(".bat")) {
                batches.add(candidate)
            } else if (lower.endsWith(".exe") || lower.endsWith(".com")) {
                executables.add(candidate)
            }
        }
        if (batches.isNotEmpty()) {
            return batches[0]
        }
        if (executables.isNotEmpty()) {
            // Prefer the actual game over an installer or a TSR driver: a bundle
            // like 三国志英杰传 ships INSTALL.EXE and GRPDRV.EXE next to MAIN.EXE,
            // and auto-running the installer/driver just drops back to the
            // prompt. Fall back to the full list if nothing is left.
            val game = executables.filterNot { isSetupOrDriver(it) }
            return biggestOf(folder, if (game.isNotEmpty()) game else executables)
        }

        // Nothing in the root — fall back to the whole bundle.
        if (candidates.isNotEmpty()) {
            for (candidate in candidates) {
                if (candidate.lowercase(Locale.US).endsWith(".bat")) {
                    return candidate
                }
            }
            return biggestOf(folder, candidates)
        }
        return null
    }

    /**
     * @return the candidate with the largest file — the main program of a
     *         bundle that has no start-up script.
     */
    private fun biggestOf(folder: File, candidates: List<String>): String? {
        var best: String? = null
        var bestSize = -1L
        for (candidate in candidates) {
            val file = resolveSafely(folder, candidate.replace('\\', '/'))
            if (file == null || !file.exists()) {
                continue
            }
            val size = file.length()
            if (size > bestSize) {
                bestSize = size
                best = candidate
            }
        }
        return best
    }

    /** Changes the launch script of a bundle and rewrites its RUN.BAT. */
    @Throws(IOException::class)
    fun setLaunch(bundle: GameBundle, script: String?) {
        val clean = script?.takeIf { it.isNotBlank() }
        writeRunBat(folderOf(bundle), bundle.folder, bundle.entry, clean)

        val bundles = ArrayList(list())
        for (i in bundles.indices) {
            if (bundles[i].folder == bundle.folder) {
                val old = bundles[i]
                bundles[i] = GameBundle(
                    old.folder, old.displayName, old.entry, old.archiveName,
                    old.installedAt, old.sizeBytes, old.hasOwnDosboxConfig, clean,
                )
                break
            }
        }
        writeManifest(bundles)
    }

    /**
     * @return the launch script shown in the editor: the custom script if the
     *         bundle has one, otherwise just the entry's file name (RUN.BAT
     *         already CDs into the game folder). The editor lists the bundle's
     *         other files so the user can add drivers deliberately.
     */
    fun launchScriptFor(bundle: GameBundle): String {
        bundle.launch?.let { return it }
        val slash = bundle.entry.lastIndexOf('\\')
        return if (slash < 0) bundle.entry else bundle.entry.substring(slash + 1)
    }

    /**
     * Writes the batch file the app types into DOSBox.
     *
     * It switches to the game folder first, so the program finds its data files,
     * then either runs the custom launch [script] (one command per line — e.g.
     * load a graphics-driver TSR, then the game) or, when there is none, the
     * single entry point.
     */
    @Throws(IOException::class)
    private fun writeRunBat(folder: File, folderName: String, entry: String, script: String?) {
        val text = StringBuilder()
        text.append("@ECHO OFF\r\n")
        // Also expose the game folder as A: and B:. Many DOS games were shipped
        // on floppies and read their data with absolute A:/B: paths (verified:
        // 三国志英杰传's MAIN.EXE opens A:BAKDATA.R3, B:IPPAN0.R3, …). The host
        // path is relative to DOSBox's working directory, which is the app files
        // directory (see android/jni/dosbox_android.cpp), so GAMES/<folder>
        // resolves there. Harmless for games that do not use A:/B:.
        text.append("MOUNT A GAMES/").append(folderName).append(" >NUL\r\n")
        text.append("MOUNT B GAMES/").append(folderName).append(" >NUL\r\n")
        text.append("C:\r\n")

        if (script != null) {
            // Custom launch: CD to the game root and run the user's commands, so
            // they control any further CD and the order drivers are loaded in.
            text.append("CD \\").append(GAMES_DIR).append('\\').append(folderName).append("\r\n")
            for (line in script.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    text.append(trimmed).append("\r\n")
                }
            }
        } else {
            val slash = entry.lastIndexOf('\\')
            val directory = if (slash < 0) "" else entry.substring(0, slash)
            val file = if (slash < 0) entry else entry.substring(slash + 1)
            text.append("CD \\").append(GAMES_DIR).append('\\').append(folderName)
            if (directory.isNotEmpty()) {
                text.append('\\').append(directory)
            }
            text.append("\r\n")
            // A name with a space has to be quoted for the command interpreter.
            if (file.indexOf(' ') >= 0) {
                text.append('"').append(file).append('"')
            } else {
                text.append(file)
            }
            text.append("\r\n")
        }

        BufferedOutputStream(FileOutputStream(File(folder, RUN_BAT))).use { out ->
            out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        }
    }

    private fun nextIndex(): Int {
        var highest = 0
        val children = gamesDir().listFiles()
        if (children != null) {
            for (child in children) {
                if (!child.isDirectory || !child.name.startsWith("G")) {
                    continue
                }
                try {
                    highest = maxOf(highest, child.name.substring(1).toInt())
                } catch (ignored: NumberFormatException) {
                    // not one of ours
                }
            }
        }
        return highest + 1
    }

    companion object {
        private const val TAG = "whereq-dosbox"

        /**
         * Name of the games directory inside the app's files directory.
         *
         * The name is upper case on purpose: DOSBox runs on a case sensitive
         * file system here, and the app starts a game by typing the DOS path
         * `C:\GAMES\<folder>\RUN.BAT` into the emulator. The folder on disk
         * therefore has to carry exactly the same spelling as the path that is
         * typed — otherwise the emulator reports "Illegal command".
         */
        private const val GAMES_DIR = GameBundle.DOS_GAMES_DIR

        /**
         * Name of the manifest. It sits next to the games folder and not inside
         * it, so the games folder holds nothing but the games.
         */
        private const val MANIFEST = "games.json"

        /** Name of the batch file the app types into DOSBox to start a game. */
        private const val RUN_BAT = "RUN.BAT"

        /**
         * Refuse a bundle that unpacks to more than this. A DOS game is a few
         * megabytes; anything bigger is a broken archive or a zip bomb.
         */
        private const val MAX_UNPACKED_BYTES = 512L * 1024 * 1024

        /** Refuse a single file inside the archive bigger than this. */
        private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

        /**
         * Batch files that start a game, in the order they are preferred. These
         * are the names the DOS game collections of the nineties used.
         */
        private val PREFERRED_BATCH_FILES = arrayOf(
            "play.bat", "run.bat", "start.bat", "game.bat", "jogar.bat",
            "launch.bat", "go.bat", "dos.bat",
        )

        /**
         * @return true when the file is an installer, a configuration tool or a
         *         TSR driver rather than the game itself — these should not be
         *         auto-picked as the entry point.
         */
        private fun isSetupOrDriver(relativePath: String): Boolean {
            val fileName = relativePath.substringAfterLast('\\')
            val base = fileName.substringBeforeLast('.').uppercase(Locale.US)
            if (base in SETUP_NAMES) {
                return true
            }
            // Graphics / sound driver stubs (GRPDRV, SNDDRV, …).
            return base.endsWith("DRV")
        }

        private val SETUP_NAMES = setOf(
            "INSTALL", "SETUP", "UNINSTALL", "UNINSTAL", "CONFIG", "SETSOUND",
            "SOUND", "SETUPSB", "INST",
        )

        /* --------------------------------------------------- the DOS file names */

        /**
         * Brings a path of a bundle into the shape DOSBox looks names up with.
         *
         * DOSBox turns every name it is given into upper case, cuts it down to
         * eight characters plus three and drops the spaces. A program that asks
         * for `RESOURCE.CFG` therefore ends up at `RESOURC.CFG`, and a file that
         * is stored under any other name is not found.
         *
         * The app writes the files of a bundle under exactly that name. It
         * matters, because the emulator only sees a folder when it lists it, and
         * the listing of the drive was taken when DOSBox started — a bundle that
         * is installed afterwards is not in it. The name is the way around that:
         * DOSBox finds a file whose name it can spell without a listing.
         *
         * @param name the path inside the archive.
         * @param used the names that are taken already.
         * @return the path the file is stored under.
         */
        private fun dosPath(name: String, used: MutableSet<String>): String {
            val parts = name.split("/")
            val path = StringBuilder()
            for (part in parts) {
                if (part.isEmpty()) {
                    continue
                }
                if (path.isNotEmpty()) {
                    path.append('/')
                }
                path.append(dosName(part))
            }
            return uniqueName(path.toString(), used)
        }

        /** Brings a single name into the shape DOSBox looks it up with. */
        private fun dosName(name: String): String {
            val upper = name.uppercase(Locale.US)

            val cleaned = StringBuilder()
            for (c in upper) {
                if (c == ' ') {
                    // DOSBox skips the spaces of a name.
                    continue
                }
                cleaned.append(c)
            }
            val text = cleaned.toString()

            val dot = text.indexOf('.')
            if (dot < 0) {
                return if (text.length > 8) text.substring(0, 8) else text
            }
            if (text.indexOf('.', dot + 1) >= 0) {
                // DOSBox refuses a name with two dots. The file keeps its name,
                // it is simply out of reach of the emulator.
                return text
            }
            var base = text.substring(0, dot)
            var extension = text.substring(dot + 1)
            if (base.length > 8) {
                base = base.substring(0, 8)
            }
            if (extension.length > 3) {
                extension = extension.substring(0, 3)
            }
            return "$base.$extension"
        }

        /**
         * Gives a file a free name, the way DOS does it.
         *
         * Two files of a bundle can share the same short name — `LONG1.CFG` and
         * `LONG2.CFG` both become `LONG.CFG`. The second one gets a number, so
         * that both survive.
         */
        private fun uniqueName(relative: String, used: MutableSet<String>): String {
            if (used.add(relative)) {
                return relative
            }
            val slash = relative.lastIndexOf('/')
            val directory = if (slash < 0) "" else relative.substring(0, slash + 1)
            val file = if (slash < 0) relative else relative.substring(slash + 1)
            val dot = file.indexOf('.')
            var base = if (dot < 0) file else file.substring(0, dot)
            val extension = if (dot < 0) "" else file.substring(dot)
            if (base.length > 6) {
                base = base.substring(0, 6)
            }
            for (i in 1 until 100) {
                val candidate = "$directory$base~$i$extension"
                if (used.add(candidate)) {
                    return candidate
                }
            }
            return relative
        }

        /* --------------------------------------------------------------- helpers */

        /**
         * Maps an archive entry to a file inside the target folder, refusing
         * anything that would escape it.
         *
         * @return the file, or null if the name is not acceptable.
         */
        private fun resolveSafely(target: File, name: String): File? {
            val cleaned = name.replace('\\', '/')
            if (cleaned.startsWith("/") || cleaned.contains(":")) {
                return null
            }
            // No part of the name may walk out of the folder.
            val parts = cleaned.split("/")
            val safe = StringBuilder()
            for (part in parts) {
                if (part.isEmpty() || part == ".") {
                    continue
                }
                if (part == "..") {
                    return null
                }
                if (safe.isNotEmpty()) {
                    safe.append('/')
                }
                safe.append(part)
            }
            val out = File(target, safe.toString())
            val root = target.absolutePath
            val path = out.absolutePath
            if (!path.startsWith(root + File.separator)) {
                return null
            }
            return out
        }

        @Throws(IOException::class)
        private fun copy(input: InputStream, file: File, declaredSize: Long): Long {
            if (declaredSize > MAX_ENTRY_BYTES) {
                throw IOException("file in the archive is too big: $declaredSize")
            }
            var total = 0L
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                val buffer = ByteArray(16384)
                var n: Int
                while (input.read(buffer).also { n = it } > 0) {
                    total += n
                    if (total > MAX_ENTRY_BYTES) {
                        throw IOException("file in the archive is too big")
                    }
                    out.write(buffer, 0, n)
                }
            }
            return total
        }

        private fun deleteRecursively(file: File?) {
            if (file == null || !file.exists()) {
                return
            }
            file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        }
    }
}
