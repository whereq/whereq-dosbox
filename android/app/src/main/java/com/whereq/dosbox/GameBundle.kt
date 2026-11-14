package com.whereq.dosbox

/**
 * One DOS bundle that was installed into the app.
 *
 * A bundle is a zip archive that was unpacked into its own folder under the
 * app's games directory. The folder name is deliberately ASCII and short
 * (`G1`, `G2`, ...) because the folder is also the DOS path of the program —
 * DOS cannot hold the Chinese name of the archive in a file name. The Chinese
 * name is kept here and shown in the launcher.
 *
 * @property folder             folder name inside the games directory, e.g. `G1`.
 * @property displayName        the name of the archive, without the .zip suffix.
 * @property entry              the file that is started when the game is
 *                              launched, relative to the game folder, e.g.
 *                              `PLAY.BAT` or `GAME\DOOM.EXE`.
 * @property archiveName        the name of the archive the bundle came from.
 * @property installedAt        when the bundle was installed (ms since epoch).
 * @property sizeBytes          size of the unpacked bundle in bytes.
 * @property hasOwnDosboxConfig true when the archive ships its own
 *                              `dosbox.conf`. Such a bundle was meant to be
 *                              started with that configuration, which the app
 *                              cannot do without a restart — the launcher says
 *                              so instead of pretending.
 */
class GameBundle(
    @JvmField val folder: String,
    @JvmField val displayName: String,
    @JvmField val entry: String,
    @JvmField val archiveName: String,
    @JvmField val installedAt: Long,
    @JvmField val sizeBytes: Long,
    @JvmField val hasOwnDosboxConfig: Boolean,
    /**
     * Optional custom launch script: the DOS commands to run after switching to
     * the game folder, one per line (e.g. load a graphics-driver TSR, then the
     * game exe). When null, RUN.BAT just runs [entry]. Games that need several
     * drivers resident before the main program (common in Chinese/KOEI DOS
     * games) set this via the launcher's "Launch…" editor.
     */
    @JvmField val launch: String? = null,
) {

    /**
     * The DOS path of the launcher batch file of this game.
     *
     * DOSBox mounts the app's files directory as C:, so the games live in
     * `C:\GAMES\<folder>`.
     */
    fun dosRunPath(): String = "C:\\$DOS_GAMES_DIR\\$folder\\RUN.BAT"

    /**
     * @return the folder of the entry point relative to the game folder, or an
     *         empty string when the entry point is in the root.
     */
    fun entryDirectory(): String {
        val slash = entry.lastIndexOf('\\')
        return if (slash < 0) "" else entry.substring(0, slash)
    }

    /** @return the file name of the entry point. */
    fun entryFileName(): String {
        val slash = entry.lastIndexOf('\\')
        return if (slash < 0) entry else entry.substring(slash + 1)
    }

    override fun toString(): String = "$displayName ($entry)"

    companion object {
        /**
         * The name of the games folder on the C: drive.
         *
         * It is upper case on purpose: DOSBox looks the file up on a case
         * sensitive file system, so the folder on disk and the path that is
         * typed into the emulator have to spell it the same way.
         */
        const val DOS_GAMES_DIR = "GAMES"
    }
}
