/*
 * dosbox_android.cpp - Android glue for the DOSBox native library.
 *
 * Three things have to be arranged before DOSBox's main() runs:
 *
 *  1. Working directory. On Android an app process starts with CWD = "/",
 *     which is not writable. DOSBox resolves its config directory as
 *     "~/.dosbox" (src/misc/cross.cpp, Cross::GetPlatformConfigDir), so
 *     without help it would try to create "/~/.dosbox" and fail.
 *
 *  2. $HOME. Android apps normally have no HOME in their environment, so
 *     Cross::ResolveHomedir() (src/misc/cross.cpp) cannot expand the "~".
 *     We point HOME at the app's private files directory, which makes
 *     "~/.dosbox" resolve to "<filesDir>/.dosbox" - writable and private.
 *
 *  3. stdout/stderr. On Android both are connected to /dev/null, so all of
 *     DOSBox's LOG_MSG() diagnostics would be lost - and DOSBox's LOG_MSG is
 *     the only window into what the emulator is doing (which output back end
 *     was selected, whether the renderer could be created, ...). We pipe them
 *     into logcat under the tag "DOSBox".
 *
 * With 1. and 2. in place DOSBox needs no source patch at all: it finds its
 * config, creates ~/.dosbox, and the [autoexec] section can mount the
 * current directory as C:.
 */

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <strings.h>
#include <unistd.h>

#include <android/log.h>

#include "SDL.h"

#include "dosbox_log.h"

#define LOGCAT_TAG "DOSBox"

/* ------------------------------------------------------------------ logcat */

/*
 * A pipe that carries stdout/stderr into logcat. The reader runs on its own
 * thread; the writer end is dup2()ed onto fd 1 and 2.
 */
static int g_log_pipe[2] = {-1, -1};

static void *logcat_pump(void *unused)
{
    (void)unused;
    char buf[1024];
    ssize_t n;

    while ((n = read(g_log_pipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        /* strip a trailing newline, logcat adds its own */
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) {
            buf[--n] = '\0';
        }
        if (n > 0) {
            __android_log_write(ANDROID_LOG_INFO, LOGCAT_TAG, buf);
        }
    }
    return NULL;
}

static void redirect_stdout_to_logcat(void)
{
    if (pipe(g_log_pipe) != 0) {
        return;
    }

    dup2(g_log_pipe[1], STDOUT_FILENO);
    dup2(g_log_pipe[1], STDERR_FILENO);
    close(g_log_pipe[1]);

    /* stdout is a pipe now, so the C library would buffer it in 4K blocks
     * and we would only see the tail of DOSBox's output. */
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&thread, &attr, logcat_pump, NULL) != 0) {
        /* Logging is a nicety - if the thread cannot be started we simply
         * keep stdout on /dev/null. */
        dup2(g_log_pipe[0], STDOUT_FILENO);
        dup2(g_log_pipe[0], STDERR_FILENO);
    }
    pthread_attr_destroy(&attr);
}

/* --------------------------------------------------------------------- JNI */

extern "C" JNIEXPORT void JNICALL
Java_com_whereq_dosbox_DosboxNative_nativePrepareEnvironment(JNIEnv *env,
                                                              jclass clazz,
                                                              jstring files_dir)
{
    (void)clazz;

    /* Do this first: it is the earliest point we control that is still
     * before SDL_main() runs (SDL only starts its thread once the surface
     * is ready, i.e. after onResume()).
     *
     * Note: we deliberately do NOT define JNI_OnLoad here - SDL2's Android
     * back end already exports one and two definitions would collide at
     * link time. */
    redirect_stdout_to_logcat();

    if (files_dir == NULL) {
        return;
    }

    const char *dir = env->GetStringUTFChars(files_dir, NULL);
    if (dir == NULL) {
        return;
    }

    /* Cross::ResolveHomedir() expands "~" via $HOME. */
    setenv("HOME", dir, 1);

    /* The process CWD on Android is "/", which is not writable. */
    if (chdir(dir) != 0) {
        DOSBOX_LOG("could not chdir to %s", dir);
    }

    DOSBOX_LOG("environment prepared: HOME=%s", dir);

    env->ReleaseStringUTFChars(files_dir, dir);
}

/*
 * Returns the directory DOSBox will use for its per-user configuration.
 * Used by the Java side for diagnostics.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_whereq_dosbox_DosboxNative_nativeConfigDir(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    const char *home = getenv("HOME");
    return env->NewStringUTF(home ? home : "");
}

/* --------------------------------------------------------------- keyboard */

/*
 * The on-screen keyboard injects key presses into DOSBox's SDL event queue.
 *
 * DOSBox's SDL2 back end does not decode SDL_KEYDOWN itself: GFX_Events()
 * hands the event to the mapper (src/gui/sdl_mapper.cpp), which indexes its
 * bind list by the *scancode* and activates the matching CKeyEvent. That
 * event calls KEYBOARD_AddKey(), which fills the BIOS keyboard buffer that
 * the DOS shell reads. So a virtual key press is nothing but an SDL_KEYDOWN /
 * SDL_KEYUP event carrying the correct SDL_Scancode - no DOSBox patch needed.
 *
 * The scancode is what matters; the keysym is filled in for consistency but
 * is not looked at by DOSBox.
 */

/*
 * Key names used by the on-screen keyboard.
 *
 * They deliberately follow the naming of DOSBox's own key table
 * (DefaultKeys[] in src/gui/sdl_mapper.cpp), so every key of the virtual
 * keyboard has a 1:1 counterpart in the mapper's bind list.
 */
#define K(n, c) { n, c },
static const struct {
    const char *name;
    SDL_Scancode code;
} key_name_map[] = {
    K("a", SDL_SCANCODE_A) K("b", SDL_SCANCODE_B) K("c", SDL_SCANCODE_C)
    K("d", SDL_SCANCODE_D) K("e", SDL_SCANCODE_E) K("f", SDL_SCANCODE_F)
    K("g", SDL_SCANCODE_G) K("h", SDL_SCANCODE_H) K("i", SDL_SCANCODE_I)
    K("j", SDL_SCANCODE_J) K("k", SDL_SCANCODE_K) K("l", SDL_SCANCODE_L)
    K("m", SDL_SCANCODE_M) K("n", SDL_SCANCODE_N) K("o", SDL_SCANCODE_O)
    K("p", SDL_SCANCODE_P) K("q", SDL_SCANCODE_Q) K("r", SDL_SCANCODE_R)
    K("s", SDL_SCANCODE_S) K("t", SDL_SCANCODE_T) K("u", SDL_SCANCODE_U)
    K("v", SDL_SCANCODE_V) K("w", SDL_SCANCODE_W) K("x", SDL_SCANCODE_X)
    K("y", SDL_SCANCODE_Y) K("z", SDL_SCANCODE_Z)

    K("0", SDL_SCANCODE_0) K("1", SDL_SCANCODE_1) K("2", SDL_SCANCODE_2)
    K("3", SDL_SCANCODE_3) K("4", SDL_SCANCODE_4) K("5", SDL_SCANCODE_5)
    K("6", SDL_SCANCODE_6) K("7", SDL_SCANCODE_7) K("8", SDL_SCANCODE_8)
    K("9", SDL_SCANCODE_9)

    K("f1", SDL_SCANCODE_F1) K("f2", SDL_SCANCODE_F2) K("f3", SDL_SCANCODE_F3)
    K("f4", SDL_SCANCODE_F4) K("f5", SDL_SCANCODE_F5) K("f6", SDL_SCANCODE_F6)
    K("f7", SDL_SCANCODE_F7) K("f8", SDL_SCANCODE_F8) K("f9", SDL_SCANCODE_F9)
    K("f10", SDL_SCANCODE_F10) K("f11", SDL_SCANCODE_F11)
    K("f12", SDL_SCANCODE_F12)

    K("space", SDL_SCANCODE_SPACE) K("esc", SDL_SCANCODE_ESCAPE)
    K("enter", SDL_SCANCODE_RETURN) K("bspace", SDL_SCANCODE_BACKSPACE)
    K("tab", SDL_SCANCODE_TAB)

    K("lshift", SDL_SCANCODE_LSHIFT) K("rshift", SDL_SCANCODE_RSHIFT)
    K("lctrl", SDL_SCANCODE_LCTRL) K("rctrl", SDL_SCANCODE_RCTRL)
    K("lalt", SDL_SCANCODE_LALT) K("ralt", SDL_SCANCODE_RALT)

    K("capslock", SDL_SCANCODE_CAPSLOCK) K("numlock", SDL_SCANCODE_NUMLOCKCLEAR)
    K("scrolllock", SDL_SCANCODE_SCROLLLOCK) K("pause", SDL_SCANCODE_PAUSE)
    K("printscreen", SDL_SCANCODE_PRINTSCREEN)

    K("lbracket", SDL_SCANCODE_LEFTBRACKET)
    K("rbracket", SDL_SCANCODE_RIGHTBRACKET)
    K("minus", SDL_SCANCODE_MINUS) K("equals", SDL_SCANCODE_EQUALS)
    K("backslash", SDL_SCANCODE_BACKSLASH)
    K("semicolon", SDL_SCANCODE_SEMICOLON) K("quote", SDL_SCANCODE_APOSTROPHE)
    K("grave", SDL_SCANCODE_GRAVE) K("comma", SDL_SCANCODE_COMMA)
    K("period", SDL_SCANCODE_PERIOD) K("slash", SDL_SCANCODE_SLASH)

    K("up", SDL_SCANCODE_UP) K("down", SDL_SCANCODE_DOWN)
    K("left", SDL_SCANCODE_LEFT) K("right", SDL_SCANCODE_RIGHT)
    K("insert", SDL_SCANCODE_INSERT) K("delete", SDL_SCANCODE_DELETE)
    K("home", SDL_SCANCODE_HOME) K("end", SDL_SCANCODE_END)
    K("pageup", SDL_SCANCODE_PAGEUP) K("pagedown", SDL_SCANCODE_PAGEDOWN)

    { NULL, SDL_SCANCODE_UNKNOWN }
};
#undef K

/**
 * Translates a key name ("a", "enter", "lshift", "f5", ...) into the numeric
 * SDL scancode. Returns SDL_SCANCODE_UNKNOWN (0) for unknown names.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_whereq_dosbox_DosboxNative_nativeScancodeFromName(JNIEnv *env,
                                                            jclass clazz,
                                                            jstring name)
{
    (void)clazz;

    if (name == NULL) {
        return 0;
    }

    const char *n = env->GetStringUTFChars(name, NULL);
    if (n == NULL) {
        return 0;
    }

    SDL_Scancode code = SDL_SCANCODE_UNKNOWN;
    for (int i = 0; key_name_map[i].name != NULL; i++) {
        if (strcasecmp(n, key_name_map[i].name) == 0) {
            code = key_name_map[i].code;
            break;
        }
    }
    if (code == SDL_SCANCODE_UNKNOWN) {
        DOSBOX_LOG("unknown key name \"%s\"", n);
    }
    env->ReleaseStringUTFChars(name, n);

    return (jint)code;
}

/**
 * Injects a key press or release into DOSBox's event queue.
 *
 * SDL_PushEvent() is thread safe, so this may be called from the Java UI
 * thread while the emulator runs on its own thread.
 *
 * @return true if the event was queued.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_whereq_dosbox_DosboxNative_nativePushKey(JNIEnv *env,
                                                    jclass clazz,
                                                    jint scancode,
                                                    jboolean pressed)
{
    (void)env;
    (void)clazz;

    /* SDL has to be up before events can be queued. DOSBox starts SDL from
       GUI_StartUp(); before that a key press is simply dropped. */
    if ((SDL_WasInit(SDL_INIT_VIDEO) & SDL_INIT_VIDEO) == 0) {
        return JNI_FALSE;
    }

    SDL_Event ev;
    SDL_zero(ev);

    ev.type = pressed ? SDL_KEYDOWN : SDL_KEYUP;
    ev.key.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    ev.key.repeat = 0;
    ev.key.timestamp = SDL_GetTicks();
    ev.key.windowID = 0;
    ev.key.keysym.scancode = (SDL_Scancode)scancode;
    ev.key.keysym.sym = SDL_GetKeyFromScancode((SDL_Scancode)scancode);
    ev.key.keysym.mod = KMOD_NONE;

    if (SDL_PushEvent(&ev) < 0) {
        DOSBOX_LOG("could not push key event: %s", SDL_GetError());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
