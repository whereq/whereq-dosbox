package com.whereq.dosbox;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
 *   <li>put the on-screen keyboard on top of the emulator surface.</li>
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

    /** The on-screen keyboard, drawn on top of the DOSBox surface. */
    private VirtualKeyboardView keyboard;

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

        // HOME + working directory must be in place before SDL_main() runs;
        // the SDL thread is only started once the surface is ready, i.e.
        // after onResume(), so doing this here is early enough.
        DosboxNative.nativePrepareEnvironment(filesDir.getAbsolutePath());

        installVirtualKeyboard();

        Log.i(TAG, "prepared environment, config dir = " + DosboxNative.nativeConfigDir());
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

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        addContentView(keyboard, lp);
    }

    /**
     * The back button folds the keyboard away before it leaves the app.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboard != null
                && keyboard.isPanelVisible()) {
            keyboard.hidePanel();
            return true;
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
