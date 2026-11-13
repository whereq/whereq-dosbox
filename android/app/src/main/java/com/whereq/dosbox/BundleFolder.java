package com.whereq.dosbox;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports the DOS bundles of a folder on the phone.
 *
 * <p>The user picks a folder once with the system folder picker, and the app
 * then unpacks every zip archive it finds in it. That is the quick way to get
 * a whole collection of programs onto the phone - one pick instead of one
 * pick per archive.
 *
 * <p>The access is the one the folder picker hands out: the app sees the
 * chosen folder and nothing else, and no storage permission is needed.
 */
public final class BundleFolder {

    private static final String TAG = "whereq-dosbox";

    /** How deep the chosen folder is searched. */
    private static final int MAX_DEPTH = 3;

    /** How many archives are imported at most. */
    private static final int MAX_ARCHIVES = 200;

    /** An archive inside the chosen folder. */
    public static final class Entry {
        /** The file name, for example {@code F1.zip}. */
        public final String name;
        /** The size of the archive in bytes. */
        public final long sizeBytes;
        /** The document of the archive. */
        public final Uri uri;

        Entry(String name, long sizeBytes, Uri uri) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.uri = uri;
        }

        /**
         * Opens the archive for reading.
         */
        InputStream open(ContentResolver resolver) throws IOException {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) {
                throw new IOException("the file could not be opened");
            }
            return in;
        }
    }

    /** Reports the progress of the import. */
    public interface Callback {
        /** One archive was unpacked. */
        void onBundleInstalled(String name, int done, int total);

        /** The import is over. */
        void onFinished(int installed, int failed, String error);
    }

    private BundleFolder() {
    }

    /**
     * Asks the user for a folder.
     *
     * <p>The result is delivered to {@code onFolderPicked}.
     */
    public static void pickFolder(Activity activity, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Log.w(TAG, "no folder picker available", e);
        }
    }

    /**
     * Collects the zip archives of a folder.
     *
     * @param resolver the content resolver
     * @param treeUri  the folder the user picked
     */
    public static List<Entry> collect(ContentResolver resolver, Uri treeUri) {
        List<Entry> entries = new ArrayList<Entry>();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            walk(resolver, treeUri, rootId, MAX_DEPTH, entries);
        } catch (Exception e) {
            Log.w(TAG, "could not read the folder", e);
        }
        return entries;
    }

    /**
     * Reads one folder and its sub-folders.
     */
    private static void walk(ContentResolver resolver, Uri treeUri, String documentId,
                             int depth, List<Entry> entries) {
        if (depth < 0 || entries.size() >= MAX_ARCHIVES) {
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        Cursor cursor = null;
        try {
            cursor = resolver.query(children, projection, null, null, null);
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext() && entries.size() < MAX_ARCHIVES) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                long size = sizeColumn >= 0 && !cursor.isNull(sizeColumn)
                        ? cursor.getLong(sizeColumn) : 0L;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(resolver, treeUri, id, depth - 1, entries);
                    continue;
                }
                if (!isZip(name)) {
                    continue;
                }
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                entries.add(new Entry(name, size, uri));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read a folder", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static boolean isZip(String name) {
        return name != null && name.toLowerCase().endsWith(".zip");
    }
}
