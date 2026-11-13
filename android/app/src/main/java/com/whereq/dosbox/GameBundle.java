package com.whereq.dosbox;

/**
 * One DOS bundle that was installed into the app.
 *
 * <p>A bundle is a zip archive that was unpacked into its own folder under
 * the app's games directory. The folder name is deliberately ASCII and short
 * ({@code G1}, {@code G2}, ...) because the folder is also the DOS path of the
 * program - DOS cannot hold the Chinese name of the archive in a file name.
 * The Chinese name is kept here and shown in the launcher.
 */
public class GameBundle {

    /** Folder name inside the games directory, e.g. {@code G1}. */
    public final String folder;

    /** The name of the archive, without the .zip suffix. */
    public final String displayName;

    /**
     * The file that is started when the game is launched, relative to the
     * game folder, e.g. {@code PLAY.BAT} or {@code GAME\DOOM.EXE}.
     */
    public final String entry;

    /** The name of the archive the bundle came from. */
    public final String archiveName;

    /** When the bundle was installed (milliseconds since the epoch). */
    public final long installedAt;

    /** Size of the unpacked bundle in bytes. */
    public final long sizeBytes;

    /**
     * True when the archive ships its own {@code dosbox.conf}. Such a bundle
     * was meant to be started with that configuration, which the app cannot
     * do without a restart - the launcher says so instead of pretending.
     */
    public final boolean hasOwnDosboxConfig;

    public GameBundle(String folder, String displayName, String entry,
                      String archiveName, long installedAt, long sizeBytes,
                      boolean hasOwnDosboxConfig) {
        this.folder = folder;
        this.displayName = displayName;
        this.entry = entry;
        this.archiveName = archiveName;
        this.installedAt = installedAt;
        this.sizeBytes = sizeBytes;
        this.hasOwnDosboxConfig = hasOwnDosboxConfig;
    }

    /**
     * The name of the games folder on the C: drive.
     *
     * <p>It is upper case on purpose: DOSBox looks the file up on a case
     * sensitive file system, so the folder on disk and the path that is
     * typed into the emulator have to spell it the same way.
     */
    public static final String DOS_GAMES_DIR = "GAMES";

    /**
     * The DOS path of the launcher batch file of this game.
     *
     * <p>DOSBox mounts the app's files directory as C:, so the games live in
     * {@code C:\GAMES\<folder>}.
     */
    public String dosRunPath() {
        return "C:\\" + DOS_GAMES_DIR + "\\" + folder + "\\RUN.BAT";
    }

    /**
     * @return the folder of the entry point relative to the game folder, or
     *         an empty string when the entry point is in the root.
     */
    public String entryDirectory() {
        int slash = entry.lastIndexOf('\\');
        return slash < 0 ? "" : entry.substring(0, slash);
    }

    /**
     * @return the file name of the entry point.
     */
    public String entryFileName() {
        int slash = entry.lastIndexOf('\\');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    @Override
    public String toString() {
        return displayName + " (" + entry + ")";
    }
}
