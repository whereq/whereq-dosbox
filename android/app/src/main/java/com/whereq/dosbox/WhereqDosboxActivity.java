package com.whereq.dosbox;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Minimal SDL2 shell around the DOSBox emulator.
 *
 * <p>Responsibilities of this class (everything else stays inside DOSBox):
 * <ul>
 *   <li>load {@code libdosbox.so} instead of a separate {@code libSDL2.so} —
 *       SDL2 is statically linked into the DOSBox library, so asking SDL to
 *       load "SDL2" would fail with UnsatisfiedLinkError;</li>
 *   <li>point SDL at {@code libdosbox.so} and at the {@code SDL_main} entry
 *       point inside it;</li>
 *   <li>hand DOSBox a private, writable working directory and {@code $HOME};</li>
 *   <li>materialise a DOSBox configuration tuned for a phone;</li>
 *   <li>put the on-screen keyboard on top of the emulator surface;</li>
 *   <li>host the bundle launcher, which installs and starts DOS bundles.</li>
 * </ul>
 *
 * <p>Touch input needs no code here: SDL's Android back end already turns
 * touches into mouse events (see {@code SDLSurface.onTouch}), and DOSBox maps
 * the mouse back through the letterboxed clip rectangle. The on-screen
 * keyboard is the only thing that has to be added, because it has to produce
 * <em>key</em> events, and those are injected into SDL's event queue from
 * {@link VirtualKeyboardView} via {@link DosboxNative#nativePushKey}.
 */
public class WhereqDosboxActivity extends SDLActivity {

    private static final String TAG = "whereq-dosbox";

    /** Name of the native library (without lib prefix / .so suffix). */
    private static final String NATIVE_LIB = "dosbox";

    /** DOSBox configuration file, written into the app's files directory. */
    private static final String CONF_NAME = "dosbox.conf";

    /** The configuration template lives in assets/. */
    private static final String CONF_ASSET = "dosbox.conf";

    /** Request code of the system file picker. */
    private static final int REQUEST_PICK_ZIP = 1;

    /** The request code of the folder picker. */
    private static final int REQUEST_PICK_FOLDER = 2;

    /**
     * Opens a bundle archive for reading.
     *
     * <p>The archive comes from the system file picker, from a file manager
     * or from a folder, and each of them reads it in its own way.
     */
    private interface ArchiveSource {
        InputStream open() throws IOException;
    }

    /** The on-screen keyboard, drawn on top of the DOSBox surface. */
    private VirtualKeyboardView keyboard;

    /** The bundle launcher, drawn on top of everything. */
    private GameLauncherView launcher;

    /** The installed bundles. */
    private GameLibrary library;

    /** Types commands into the running DOSBox. */
    private DosboxTyper typer;

    @Override
    protected String[] getLibraries() {
        // SDL2 is statically linked into libdosbox.so, so "SDL2" must not be
        // requested here.
        return new String[] { NATIVE_LIB };
    }

    @Override
    protected String getMainSharedObject() {
        return getContext().getApplicationInfo().nativeLibraryDir + "/lib" + NATIVE_LIB + ".so";
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }

    @Override
    protected String[] getArguments() {
        // DOSBox reads its configuration from this file; the path is relative
        // to the working directory, which nativePrepareEnvironment() has set
        // to the app's files directory.
        return new String[] { "-conf", CONF_NAME };
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);   // loads the native library

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        File filesDir = getFilesDir();
        writeDefaultConfig(filesDir);

        library = new GameLibrary(filesDir);
        typer = new DosboxTyper();

        // HOME + working directory must be in place before SDL_main() runs;
        // the SDL thread is only started once the surface is ready, i.e.
        // after onResume(), so doing this here is early enough.
        DosboxNative.nativePrepareEnvironment(filesDir.getAbsolutePath());

        installVirtualKeyboard();

        // A bundle that was opened from a file manager is installed right
        // away - the user asked for it with a tap.
        handleOpenIntent(getIntent());

        Log.i(TAG, "prepared environment, config dir = " + DosboxNative.nativeConfigDir());
    }

    /**
     * Installs the bundle of an {@code ACTION_VIEW} intent.
     *
     * <p>That is how a bundle reaches the app when the user picks it in a
     * file manager and chooses WhereQ-DOSBox.
     */
    private void handleOpenIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        final Uri uri = intent.getData();
        if (uri == null) {
            return;
        }
        final String name = displayNameOf(uri);
        showLauncher();
        install(new ArchiveSource() {
            public InputStream open() throws IOException {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("the file could not be opened");
                }
                return in;
            }
        }, name);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    /**
     * Puts the on-screen keyboard on top of the DOSBox surface.
     *
     * <p>The keyboard is added to the activity's content view, i.e. it is
     * drawn above the SDL {@code SurfaceView}. It only occupies the strip at
     * the bottom of the screen, so touches anywhere else still reach the
     * emulator - which is what turns a tap into a click inside the DOS
     * picture (SDL turns the touch into a mouse event, DOSBox maps it back
     * through the letterboxed clip rectangle).
     */
    private void installVirtualKeyboard() {
        keyboard = new VirtualKeyboardView(this);
        keyboard.setOnFilesRequestedListener(new VirtualKeyboardView.OnFilesRequestedListener() {
            public void onFilesRequested() {
                showLauncher();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        addContentView(keyboard, lp);
    }

    /* --------------------------------------------------------- the launcher */

    /**
     * Opens the game launcher over the emulator.
     */
    private void showLauncher() {
        if (launcher == null) {
            launcher = new GameLauncherView(this, new GameLauncherView.Listener() {
                public void onInstallRequested() {
                    pickZipFile();
                }

                public void onBrowseRequested() {
                    browsePhoneFiles();
                }

                public void onPlayRequested(GameBundle bundle) {
                    play(bundle);
                }

                public void onEntryPointRequested(GameBundle bundle) {
                    List<String> candidates = library.listCandidates(bundle);
                    launcher.showEntryPointPicker(bundle, candidates);
                }

                public void onEntryPointChosen(GameBundle bundle, String relativePath) {
                    try {
                        library.setEntryPoint(bundle, relativePath);
                        launcher.showFiles(library.list());
                    } catch (IOException e) {
                        launcher.setStatus("Could not set the start file: " + e.getMessage());
                    }
                }

                public void onDeleteRequested(GameBundle bundle) {
                    confirmDelete(bundle);
                }

                public void onCloseRequested() {
                    closeLauncher();
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            addContentView(launcher, lp);
        }
        launcher.showFiles(library.list());
        launcher.setStatus("");
        launcher.setVisibility(View.VISIBLE);
    }

    private void closeLauncher() {
        if (launcher != null) {
            launcher.setVisibility(View.GONE);
        }
    }

    /**
     * Asks before a game is removed - the bundle is deleted from the phone.
     */
    private void confirmDelete(final GameBundle bundle) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + bundle.displayName + "?")
                .setMessage("The unpacked game is deleted from the phone.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        library.delete(bundle);
                        launcher.showFiles(library.list());
                        launcher.setStatus("Removed " + bundle.displayName);
                    }
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    /* ---------------------------------------------------------- installing */

    /**
     * Opens the system file picker for a zip archive.
     *
     * <p>The intent has to carry a MIME type: the filter of the system
     * picker declares one, so an intent without a type does not resolve at
     * all and the picker never opens. The type is therefore the general
     * one and the interesting types are handed over separately, which lets
     * the picker show every file but mark the archives.
     */
    private void pickZipFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // A lot of file providers do not report a zip as application/zip,
        // so the type list is wide on purpose.
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "application/x-compressed",
                "application/x-zip",
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // The bundles are usually in the download folder, so the picker opens
        // right there instead of in the "recent files" list.
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadRootUri());
        try {
            startActivityForResult(intent, REQUEST_PICK_ZIP);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no document picker, trying the content picker", e);
            pickZipFileLegacy();
        } catch (Exception e) {
            Log.w(TAG, "could not open the file picker", e);
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * @return the document of the download folder, so the picker can start
     *         there. A device that does not know it simply opens the picker
     *         where it would open anyway.
     */
    private static Uri downloadRootUri() {
        try {
            return DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Download");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The older way to ask for a file, used when the device has no document
     * picker.
     */
    private void pickZipFileLegacy() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_ZIP);
        } catch (Exception e) {
            Log.w(TAG, "could not open the content picker", e);
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Shows the zip archives of a folder on the phone.
     *
     * <p>The user picks a folder once and every archive in it is installed -
     * the quick way to get a whole collection of DOS programs onto the phone.
     */
    private void browsePhoneFiles() {
        BundleFolder.pickFolder(this, REQUEST_PICK_FOLDER);
    }

    /**
     * Installs every archive of the folder the user picked.
     */
    private void importFolder(final Uri treeUri) {
        if (launcher != null) {
            launcher.setStatus("Looking for bundles…");
        }
        new Thread(new Runnable() {
            public void run() {
                final List<BundleFolder.Entry> entries =
                        BundleFolder.collect(getContentResolver(), treeUri);
                final int total = entries.size();
                final int[] installed = new int[1];
                final int[] failed = new int[1];
                final String[] error = new String[1];

                if (total == 0) {
                    error[0] = "no .zip bundle found in that folder";
                }
                for (BundleFolder.Entry entry : entries) {
                    try {
                        InputStream in = entry.open(getContentResolver());
                        try {
                            library.install(in, entry.name);
                            installed[0]++;
                        } finally {
                            in.close();
                        }
                    } catch (Exception e) {
                        failed[0]++;
                        Log.e(TAG, "could not install " + entry.name, e);
                    }
                    final int done = installed[0] + failed[0];
                    final String name = entry.name;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (launcher != null) {
                                launcher.setStatus("Installing " + name + " ("
                                        + done + " of " + total + ")…");
                            }
                        }
                    });
                }

                final int doneInstalled = installed[0];
                final int doneFailed = failed[0];
                final String message = error[0];
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (launcher == null) {
                            return;
                        }
                        launcher.showFiles(library.list());
                        if (message != null) {
                            launcher.setStatus(message);
                        } else {
                            launcher.setStatus("Installed " + doneInstalled + " bundle"
                                    + (doneInstalled == 1 ? "" : "s")
                                    + (doneFailed > 0 ? ", " + doneFailed + " failed" : ""));
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            importFolder(data.getData());
            return;
        }
        if (requestCode != REQUEST_PICK_ZIP) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final String name = displayNameOf(uri);
        install(new ArchiveSource() {
            public InputStream open() throws IOException {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("the file could not be opened");
                }
                return in;
            }
        }, name);
    }

    /**
     * Unpacks a picked archive into the app.
     *
     * <p>The archive is read on a background thread - a bundle is a few
     * megabytes and the emulator must not freeze while it is unpacked.
     *
     * @param source   opens the archive
     * @param nameHint the file name, when it is already known
     */
    private void install(final ArchiveSource source, final String nameHint) {
        if (launcher != null) {
            launcher.setStatus("Unpacking…");
        }
        final String name = nameHint != null ? stripExtension(nameHint) : "game";
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                try {
                    InputStream in = source.open();
                    try {
                        library.install(in, name);
                    } finally {
                        in.close();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "could not install " + name, e);
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String message = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (launcher == null) {
                            return;
                        }
                        launcher.showFiles(library.list());
                        launcher.setStatus(message == null
                                ? "Installed " + name
                                : "Could not install " + name + ": " + message);
                    }
                });
            }
        }).start();
    }

    /**
     * @return the name of the picked file, without the .zip suffix.
     */
    private String displayNameOf(Uri uri) {
        String name = null;
        try {
            Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the name of the picked file", e);
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        if (name == null) {
            name = "game";
        }
        return stripExtension(name);
    }

    /**
     * @return the file name without its extension.
     */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }

    /* -------------------------------------------------------------- playing */

    /**
     * Starts a game by typing its start-up command into DOSBox.
     *
     * <p>The command is the DOS path of the bundle's {@code RUN.BAT}, which
     * switches to the game folder and starts the entry point. It is typed
     * through the same path the on-screen keyboard uses, so the emulator
     * needs no special support for it.
     *
     * <p>DOSBox has to be at the {@code C:\>} prompt when this happens: the
     * characters go to whatever is running. The launcher says so, and a game
     * that was started from here usually returns to the prompt when it ends.
     */
    private void play(GameBundle bundle) {
        closeLauncher();
        final String command = bundle.dosRunPath();
        Log.i(TAG, "starting " + bundle.displayName + " with " + command);
        typer.type(command, new DosboxTyper.Callback() {
            public void onTypingFinished(boolean ok) {
                if (!ok) {
                    Toast.makeText(WhereqDosboxActivity.this,
                            "Could not type the start command", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * The back button folds the keyboard away before it leaves the app, and
     * closes the launcher when it is open.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (launcher != null && launcher.getVisibility() == View.VISIBLE) {
                closeLauncher();
                return true;
            }
            if (keyboard != null && keyboard.isPanelVisible()) {
                keyboard.hidePanel();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    /** Full-screen, no status bar / navigation bar. */
    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Copies assets/dosbox.conf into the files directory if it is not there
     * yet. The file is regenerated on every start so that changes to the
     * template take effect, but an existing file is kept so that user
     * modifications survive.
     */
    private void writeDefaultConfig(File filesDir) {
        File target = new File(filesDir, CONF_NAME);
        if (target.exists()) {
            Log.i(TAG, "keeping existing " + target.getAbsolutePath());
            return;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getAssets().open(CONF_ASSET);
            out = new FileOutputStream(target);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            Log.i(TAG, "wrote " + target.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "could not write " + CONF_NAME, e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing we can do
            }
        }
    }
}
