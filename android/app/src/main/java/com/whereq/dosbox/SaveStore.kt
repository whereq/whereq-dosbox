package com.whereq.dosbox

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Where save-state slots live, and how they survive a reinstall.
 *
 * Two layers:
 *  - a **local working copy** under `noBackupFilesDir/states/<folder>/slotN.sav`.
 *    This is what the native engine reads and writes (it works with plain file
 *    paths), and it is fast. It is wiped when the app is uninstalled.
 *  - a **durable mirror** in a folder the user picked on their phone (via the
 *    Storage Access Framework). Each slot is gzip-compressed there (a 16 MB RAM
 *    snapshot is mostly zeros, so it shrinks to a few MB). This folder lives
 *    outside the app sandbox, so it survives uninstall/reinstall and can be
 *    copied to another phone.
 *
 * The mirror is keyed by the game's **display name**, not its `G<n>` folder: the
 * folder index is handed out at install time and is not stable across a
 * reinstall, whereas the same bundle always has the same name. So after a
 * reinstall (local copy gone), a slot is restored from the mirror by name the
 * first time it is loaded.
 */
class SaveStore(private val context: Context) {

    private fun prefs() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* --------------------------------------------------------- local copy */

    /** The local working file the native engine uses for a slot. */
    fun localSlot(folder: String, slot: Int): File =
        File(File(File(context.noBackupFilesDir, STATES_DIR), folder), "slot$slot.sav")

    /** Removes a game's local slots (the durable mirror is left for the user). */
    fun deleteLocal(folder: String) {
        val dir = File(File(context.noBackupFilesDir, STATES_DIR), folder)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /* ------------------------------------------------- the durable folder */

    /** The persisted URI of the user's saves folder, or null if none is set. */
    var folderUri: Uri?
        get() = prefs().getString(KEY_SAVES_FOLDER, null)?.let(Uri::parse)
        set(value) {
            prefs().edit().apply {
                if (value == null) remove(KEY_SAVES_FOLDER) else putString(KEY_SAVES_FOLDER, value.toString())
            }.apply()
        }

    /** @return true when a writable durable folder is configured. */
    fun hasFolder(): Boolean = root() != null

    private fun root(): DocumentFile? {
        val uri = folderUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory && it.canWrite() }
        } catch (e: Exception) {
            Log.w(TAG, "saves folder unavailable: ${e.message}")
            null
        }
    }

    /** Finds (or creates) a game's durable sub-folder, keyed by its name. It
     *  holds the game's save slots and, for reinstall recovery, its bundle. */
    private fun gameDir(displayName: String, create: Boolean): DocumentFile? {
        val root = root() ?: return null
        val dirName = sanitize(displayName)
        var dir = root.findFile(dirName)
        if (dir == null && create) dir = root.createDirectory(dirName)
        return dir
    }

    /**
     * Finds a child file by its base name, tolerating an extension the storage
     * provider may have appended.
     *
     * SAF's `createFile(mime, name)` lets the provider add a mime-derived
     * extension when the name's extension does not match the mime: a slot asked
     * for as `slot1.savz` with `application/gzip` can land as `slot1.savz.gz`.
     * So matching must accept `<base>` or `<base>.<anything>` (but not `slot10`
     * for base `slot1`).
     */
    private fun findChild(dir: DocumentFile, base: String): DocumentFile? {
        dir.findFile(base)?.takeIf { it.isFile }?.let { return it }
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            if (f.isFile && (n == base || n.startsWith("$base."))) return f
        }
        return null
    }

    /**
     * Finds (or creates) the durable slot file for a game, keyed by name.
     *
     * On create the extension is matched to the mime (`.gz` / `application/gzip`)
     * so the provider leaves the name alone; on read any already-present slot
     * file is found tolerantly (see [findChild]).
     *
     * @param create make the game sub-folder and the file if they are missing.
     */
    private fun durableSlot(displayName: String, slot: Int, create: Boolean): DocumentFile? {
        val dir = gameDir(displayName, create) ?: return null
        findChild(dir, "slot$slot")?.let { return it }
        if (!create) return null
        return dir.createFile("application/gzip", "slot$slot.gz")
    }

    /* ----------------------------------------------------- mirror / restore */

    /**
     * Copies a freshly written local slot into the durable folder, gzip-ped.
     * No-op when no folder is set. Call off the UI thread.
     */
    fun mirrorToDurable(displayName: String, folder: String, slot: Int) {
        val local = localSlot(folder, slot)
        if (!local.exists()) return
        val doc = durableSlot(displayName, slot, create = true) ?: return
        try {
            // "wt" truncates, so re-saving a slot overwrites the old mirror.
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                GZIPOutputStream(BufferedOutputStream(out)).use { gz ->
                    local.inputStream().use { it.copyTo(gz) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not mirror slot $slot of $displayName: ${e.message}")
        }
    }

    /**
     * Restores a slot's local working copy from the durable mirror (un-gzip).
     * Used when the local copy is gone (after a reinstall).
     *
     * @return true if the local copy now exists.
     */
    fun restoreFromDurable(displayName: String, folder: String, slot: Int): Boolean {
        val doc = durableSlot(displayName, slot, create = false)?.takeIf { it.exists() } ?: return false
        val local = localSlot(folder, slot)
        local.parentFile?.mkdirs()
        return try {
            val ok = context.contentResolver.openInputStream(doc.uri)?.use { input ->
                GZIPInputStream(BufferedInputStream(input)).use { gz ->
                    local.outputStream().use { gz.copyTo(it) }
                }
                true
            } ?: false
            // Keep the original SAVE time: restoring rewrites the local file, so
            // without this the slot would show "just now" after every load.
            if (ok) doc.lastModified().takeIf { it > 0L }?.let { local.setLastModified(it) }
            if (!ok) local.delete()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "could not restore slot $slot of $displayName: ${e.message}")
            local.delete()
            false
        }
    }

    /** @return the durable mirror's last-modified time for a slot, or null. */
    fun durableTimestamp(displayName: String, slot: Int): Long? {
        val doc = durableSlot(displayName, slot, create = false)?.takeIf { it.exists() } ?: return null
        val t = doc.lastModified()
        return if (t > 0L) t else null
    }

    /* ------------------------------------------------ game bundle recovery */

    /** A game bundle found in the durable folder, ready to re-install. */
    data class DurableGame(
        val name: String,
        val entry: String,
        val launch: String?,
        val ownConfig: Boolean,
        val archive: String,
        val uri: Uri,
    )

    /**
     * Mirrors an installed game's unpacked folder into the durable store as a
     * single zip (`bundle.adg`), with its metadata as the first entry, so the
     * whole game can be re-installed after a reinstall without the original zip.
     * No-op when no folder is set. Call off the UI thread.
     */
    fun mirrorGame(bundle: GameBundle, gameFiles: File) {
        val dir = gameDir(bundle.displayName, create = true) ?: return
        findChild(dir, BUNDLE_BASE)?.delete()
        val doc = dir.createFile("application/zip", "$BUNDLE_BASE.zip") ?: return
        try {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    zip.putNextEntry(ZipEntry(META_ENTRY))
                    zip.write(metaJson(bundle).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zipTree(gameFiles, gameFiles, zip)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not mirror game ${bundle.displayName}: ${e.message}")
        }
    }

    /** @return every game bundle stored in the durable folder. */
    fun durableGames(): List<DurableGame> {
        val root = root() ?: return emptyList()
        val out = ArrayList<DurableGame>()
        for (sub in root.listFiles()) {
            if (!sub.isDirectory) continue
            val adg = findChild(sub, BUNDLE_BASE) ?: continue
            readMeta(adg.uri)?.let { out.add(it) }
        }
        return out
    }

    /** Opens a durable bundle for reading (its zip stream). */
    fun openDurableGame(uri: Uri) = context.contentResolver.openInputStream(uri)

    private fun metaJson(bundle: GameBundle): String =
        JSONObject().apply {
            put("name", bundle.displayName)
            put("entry", bundle.entry)
            put("ownConfig", bundle.hasOwnDosboxConfig)
            put("archive", bundle.archiveName)
            bundle.launch?.let { put("launch", it) }
        }.toString()

    /** Reads just the `__meta.json` (first) entry of a durable bundle. */
    private fun readMeta(uri: Uri): DurableGame? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                var result: DurableGame? = null
                while (entry != null) {
                    if (entry.name == META_ENTRY) {
                        val json = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                        result = DurableGame(
                            name = json.optString("name"),
                            entry = json.optString("entry"),
                            launch = json.optString("launch", "").ifEmpty { null },
                            ownConfig = json.optBoolean("ownConfig", false),
                            archive = json.optString("archive", json.optString("name")),
                            uri = uri,
                        )
                        break
                    }
                    entry = zip.nextEntry
                }
                result
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read bundle meta: ${e.message}")
        null
    }

    /** Adds every file under [dir] to [zip], paths relative to [root]. */
    private fun zipTree(root: File, dir: File, zip: ZipOutputStream) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                zipTree(root, f, zip)
            } else {
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val TAG = "whereq-dosbox"
        private const val PREFS = "launch"
        private const val STATES_DIR = "states"
        private const val KEY_SAVES_FOLDER = "saves_folder"

        /**
         * Save-state slots offered per game.
         *
         * One number for the whole app: the States sheet, the "newest slot"
         * search and the backup-to-the-durable-folder walk all have to agree,
         * or a slot would exist that the sheet never shows and the backup
         * would silently skip.
         */
        const val STATE_SLOTS = 5

        /** Base name of the durable game bundle (a zip of the unpacked game).
         *  The actual file is `bundle.zip` (or an older `bundle.adg`), found
         *  tolerantly by [findChild]. */
        private const val BUNDLE_BASE = "bundle"

        /** The metadata entry inside a bundle (written first so a meta-only
         *  read stops early). */
        private const val META_ENTRY = "__meta.json"

        /**
         * Makes a game name safe for a folder/file name while keeping it
         * recognisable. Letters and digits (including CJK) are kept, and so are
         * the parentheses of the " (2)" that tells two same-named games apart;
         * everything else becomes '_'. Capped so a very long name cannot
         * overflow a path — from the **front**, because the " (2)" that keeps
         * two games apart is at the end.
         */
        private fun sanitize(name: String): String {
            val out = StringBuilder()
            for (c in name) {
                out.append(
                    if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' ||
                        c == '(' || c == ')'
                    ) c else '_'
                )
            }
            val clean = out.toString().trim().ifEmpty { "game" }
            return if (clean.length > 64) clean.takeLast(64) else clean
        }
    }
}
