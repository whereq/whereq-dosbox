package com.whereq.dosbox

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * Imports the DOS bundles of a folder on the phone.
 *
 * The user picks a folder once with the system folder picker, and the app then
 * unpacks every zip archive it finds in it. That is the quick way to get a whole
 * collection of programs onto the phone — one pick instead of one pick per
 * archive.
 *
 * The access is the one the folder picker hands out: the app sees the chosen
 * folder and nothing else, and no storage permission is needed.
 */
object BundleFolder {

    private const val TAG = "whereq-dosbox"

    /** How deep the chosen folder is searched. */
    private const val MAX_DEPTH = 3

    /** How many archives are imported at most. */
    private const val MAX_ARCHIVES = 200

    /** An archive inside the chosen folder. */
    class Entry(
        /** The file name, for example `F1.zip`. */
        @JvmField val name: String,
        /** The size of the archive in bytes. */
        @JvmField val sizeBytes: Long,
        /** The document of the archive. */
        @JvmField val uri: Uri,
    ) {
        /** Opens the archive for reading. */
        @Throws(IOException::class)
        fun open(resolver: ContentResolver): InputStream =
            resolver.openInputStream(uri) ?: throw IOException("the file could not be opened")
    }

    /** Reports the progress of the import. */
    interface Callback {
        /** One archive was unpacked. */
        fun onBundleInstalled(name: String, done: Int, total: Int)

        /** The import is over. */
        fun onFinished(installed: Int, failed: Int, error: String?)
    }

    /**
     * Asks the user for a folder.
     *
     * The result is delivered to `onActivityResult`.
     */
    fun pickFolder(activity: Activity, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Log.w(TAG, "no folder picker available", e)
        }
    }

    /**
     * Collects the zip archives of a folder.
     *
     * @param resolver the content resolver.
     * @param treeUri  the folder the user picked.
     */
    fun collect(resolver: ContentResolver, treeUri: Uri): List<Entry> {
        val entries = ArrayList<Entry>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            walk(resolver, treeUri, rootId, MAX_DEPTH, entries)
        } catch (e: Exception) {
            Log.w(TAG, "could not read the folder", e)
        }
        return entries
    }

    /** Reads one folder and its sub-folders. */
    private fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        entries: MutableList<Entry>,
    ) {
        if (depth < 0 || entries.size >= MAX_ARCHIVES) {
            return
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        try {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && entries.size < MAX_ARCHIVES) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mime = cursor.getString(2)
                    val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn)
                    } else {
                        0L
                    }

                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        walk(resolver, treeUri, id, depth - 1, entries)
                        continue
                    }
                    if (!isZip(name)) {
                        continue
                    }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    entries.add(Entry(name, size, uri))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read a folder", e)
        }
    }

    private fun isZip(name: String?): Boolean =
        name != null && name.lowercase().endsWith(".zip")
}
