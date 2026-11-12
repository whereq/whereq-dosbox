package com.whereq.dosbox;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The games the user has installed into the app.
 *
 * <p>Layout inside the app's private files directory:
 *
 * <pre>
 *   games/
 *     games.json        the manifest: what was installed, with what entry point
 *     G1/               one folder per bundle, named after its index
 *       RUN.BAT         the launcher batch file the app types into DOSBox
 *       ...             the unpacked bundle
 * </pre>
 *
 * <p>The folder names are short and ASCII on purpose: the folder is part of
 * the DOS path of the game, and DOS file names cannot hold the Chinese name
 * of the archive. The Chinese name lives in the manifest and is shown in the
 * launcher.
 *
 * <p>Every bundle gets a {@code RUN.BAT} that switches to the game folder and
 * starts the entry point. The app launches a game by typing the DOS path of
 * that file into DOSBox, so the command it has to type is always the same
 * length and always ASCII.
 */
public class GameLibrary {

    private static final String TAG = "whereq-dosbox";

    /**
     * Name of the games directory inside the app's files directory.
     *
     * <p>The name is upper case on purpose: DOSBox runs on a case sensitive
     * file system here, and the app starts a game by typing the DOS path
     * {@code C:\GAMES\<folder>\RUN.BAT} into the emulator. The folder on
     * disk therefore has to carry exactly the same spelling as the path that
     * is typed - otherwise the emulator reports "Illegal command".
     */
    private static final String GAMES_DIR = GameBundle.DOS_GAMES_DIR;

    /**
     * Name of the manifest.
     *
     * <p>It sits next to the games folder and not inside it, so the games
     * folder holds nothing but the games.
     */
    private static final String MANIFEST = "games.json";

    /** Name of the batch file the app types into DOSBox to start a game. */
    private static final String RUN_BAT = "RUN.BAT";

    /**
     * Refuse a bundle that unpacks to more than this. A DOS game is a few
     * megabytes; anything bigger is a broken archive or a zip bomb.
     */
    private static final long MAX_UNPACKED_BYTES = 512L * 1024 * 1024;

    /** Refuse a single file inside the archive bigger than this. */
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /**
     * Batch files that start a game, in the order they are preferred. These
     * are the names the DOS game collections of the nineties used.
     */
    private static final String[] PREFERRED_BATCH_FILES = {
            "play.bat", "run.bat", "start.bat", "game.bat", "jogar.bat",
            "launch.bat", "go.bat", "dos.bat",
    };

    /** The app's private files directory - the root of the C: drive. */
    private final File filesDir;

    public GameLibrary(File filesDir) {
        this.filesDir = filesDir;
    }

    /** @return the directory that holds the installed games. */
    public File gamesDir() {
        return new File(filesDir, GAMES_DIR);
    }

    /** @return the file that holds the list of installed games. */
    private File manifestFile() {
        return new File(filesDir, MANIFEST);
    }

    /** @return the folder of one bundle. */
    public File folderOf(GameBundle bundle) {
        return new File(gamesDir(), bundle.folder);
    }

    /* --------------------------------------------------------- the manifest */

    /**
     * @return every installed bundle, newest first.
     */
    public List<GameBundle> list() {
        List<GameBundle> bundles = new ArrayList<GameBundle>();
        File manifest = manifestFile();
        if (!manifest.exists()) {
            return bundles;
        }
        try {
            byte[] raw = readAll(new java.io.FileInputStream(manifest));
            JSONArray array = new JSONArray(new String(raw, "UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                bundles.add(new GameBundle(
                        object.getString("folder"),
                        object.optString("name", object.getString("folder")),
                        object.optString("entry", ""),
                        object.optString("archive", ""),
                        object.optLong("installedAt", 0),
                        object.optLong("size", 0),
                        object.optBoolean("ownConfig", false)));
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the game manifest", e);
        }
        Collections.sort(bundles, new Comparator<GameBundle>() {
            public int compare(GameBundle a, GameBundle b) {
                return Long.compare(b.installedAt, a.installedAt);
            }
        });
        return bundles;
    }

    private void writeManifest(List<GameBundle> bundles) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (GameBundle bundle : bundles) {
                JSONObject object = new JSONObject();
                object.put("folder", bundle.folder);
                object.put("name", bundle.displayName);
                object.put("entry", bundle.entry);
                object.put("archive", bundle.archiveName);
                object.put("installedAt", bundle.installedAt);
                object.put("size", bundle.sizeBytes);
                object.put("ownConfig", bundle.hasOwnDosboxConfig);
                array.put(object);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("could not build the game manifest", e);
        }
        File manifest = manifestFile();
        OutputStream out = new FileOutputStream(manifest);
        try {
            out.write(array.toString().getBytes("UTF-8"));
        } finally {
            out.close();
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
    public GameBundle install(InputStream zip, String displayName) throws IOException {
        File games = gamesDir();
        if (!games.exists() && !games.mkdirs()) {
            throw new IOException("could not create " + games.getAbsolutePath());
        }

        // The index is never reused, so a deleted game never takes the
        // folder of an older one - the DOS paths in the manifest stay valid.
        int index = nextIndex();
        String folder = "G" + index;
        File target = new File(games, folder);
        if (!target.mkdirs()) {
            throw new IOException("could not create " + target.getAbsolutePath());
        }

        long[] unpacked = new long[1];
        boolean[] ownConfig = new boolean[1];
        Set<String> used = new HashSet<String>();
        try {
            ZipInputStream in = new ZipInputStream(zip);
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                // The file is stored under the name DOSBox looks it up with.
                File out = resolveSafely(target, dosPath(name, used));
                if (out == null) {
                    Log.w(TAG, "skipping unsafe entry " + name);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("could not create " + parent.getAbsolutePath());
                }
                if (name.toLowerCase(Locale.US).endsWith("dosbox.conf")) {
                    ownConfig[0] = true;
                }
                long written = copy(in, out, entry.getSize());
                unpacked[0] += written;
                if (unpacked[0] > MAX_UNPACKED_BYTES) {
                    throw new IOException("the archive unpacks to more than "
                            + (MAX_UNPACKED_BYTES / (1024 * 1024)) + " MB");
                }
                in.closeEntry();
            }
        } catch (IOException e) {
            deleteRecursively(target);
            throw e;
        }

        String entryPoint = detectEntryPoint(target);
        if (entryPoint == null) {
            deleteRecursively(target);
            throw new IOException("no .bat, .exe or .com found in the archive");
        }
        writeRunBat(target, folder, entryPoint);

        GameBundle bundle = new GameBundle(folder, displayName, entryPoint,
                displayName, System.currentTimeMillis(), unpacked[0], ownConfig[0]);

        List<GameBundle> bundles = list();
        bundles.add(bundle);
        try {
            writeManifest(bundles);
        } catch (Exception e) {
            deleteRecursively(target);
            throw new IOException("could not write the game manifest", e);
        }
        Log.i(TAG, "installed " + bundle);
        return bundle;
    }

    /**
     * Removes a bundle and its folder.
     */
    public void delete(GameBundle bundle) {
        List<GameBundle> bundles = list();
        for (int i = 0; i < bundles.size(); i++) {
            if (bundles.get(i).folder.equals(bundle.folder)) {
                bundles.remove(i);
                break;
            }
        }
        try {
            writeManifest(bundles);
        } catch (IOException e) {
            Log.e(TAG, "could not write the game manifest", e);
        }
        deleteRecursively(folderOf(bundle));
    }

    /**
     * Changes the entry point of a bundle and rewrites its RUN.BAT.
     */
    public void setEntryPoint(GameBundle bundle, String relativePath) throws IOException {
        File folder = folderOf(bundle);
        File target = resolveSafely(folder, relativePath.replace('\\', '/'));
        if (target == null || !target.exists()) {
            throw new IOException("no such file in the bundle: " + relativePath);
        }
        writeRunBat(folder, bundle.folder, relativePath);

        List<GameBundle> bundles = list();
        for (int i = 0; i < bundles.size(); i++) {
            if (bundles.get(i).folder.equals(bundle.folder)) {
                GameBundle old = bundles.get(i);
                bundles.set(i, new GameBundle(old.folder, old.displayName,
                        relativePath, old.archiveName, old.installedAt,
                        old.sizeBytes, old.hasOwnDosboxConfig));
                break;
            }
        }
        writeManifest(bundles);
    }

    /**
     * @return every file of a bundle that could be started, relative to the
     *         game folder, sorted so the best candidates come first.
     */
    public List<String> listCandidates(GameBundle bundle) {
        List<String> candidates = new ArrayList<String>();
        collectCandidates(folderOf(bundle), "", candidates);
        return candidates;
    }

    private void collectCandidates(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File child : children) {
            if (child.isDirectory()) {
                collectCandidates(child, prefix + child.getName() + "\\", out);
                continue;
            }
            String lower = child.getName().toLowerCase(Locale.US);
            if (lower.endsWith(".bat") || lower.endsWith(".exe")
                    || lower.endsWith(".com")) {
                out.add(prefix + child.getName());
            }
        }
    }

    /* --------------------------------------------------- the DOS file names */

    /**
     * Brings a path of a bundle into the shape DOSBox looks names up with.
     *
     * <p>DOSBox turns every name it is given into upper case, cuts it down
     * to eight characters plus three and drops the spaces. A program that
     * asks for {@code RESOURCE.CFG} therefore ends up at
     * {@code RESOURC.CFG}, and a file that is stored under any other name
     * is not found.
     *
     * <p>The app writes the files of a bundle under exactly that name. It
     * matters, because the emulator only sees a folder when it lists it, and
     * the listing of the drive was taken when DOSBox started - a bundle that
     * is installed afterwards is not in it. The name is the way around that:
     * DOSBox finds a file whose name it can spell without a listing.
     *
     * @param name the path inside the archive
     * @param used the names that are taken already
     * @return the path the file is stored under
     */
    private static String dosPath(String name, Set<String> used) {
        String[] parts = name.split("/");
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) {
                continue;
            }
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(dosName(parts[i]));
        }
        return uniqueName(path.toString(), used);
    }

    /**
     * Brings a single name into the shape DOSBox looks it up with.
     */
    private static String dosName(String name) {
        String upper = name.toUpperCase(Locale.US);

        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == ' ') {
                // DOSBox skips the spaces of a name.
                continue;
            }
            cleaned.append(c);
        }
        String text = cleaned.toString();

        int dot = text.indexOf('.');
        if (dot < 0) {
            return text.length() > 8 ? text.substring(0, 8) : text;
        }
        if (text.indexOf('.', dot + 1) >= 0) {
            // DOSBox refuses a name with two dots. The file keeps its name,
            // it is simply out of reach of the emulator.
            return text;
        }
        String base = text.substring(0, dot);
        String extension = text.substring(dot + 1);
        if (base.length() > 8) {
            base = base.substring(0, 8);
        }
        if (extension.length() > 3) {
            extension = extension.substring(0, 3);
        }
        return base + "." + extension;
    }

    /**
     * Gives a file a free name, the way DOS does it.
     *
     * <p>Two files of a bundle can share the same short name - {@code LONG1.CFG}
     * and {@code LONG2.CFG} both become {@code LONG.CFG}. The second one gets
     * a number, so that both survive.
     */
    private static String uniqueName(String relative, Set<String> used) {
        if (used.add(relative)) {
            return relative;
        }
        int slash = relative.lastIndexOf('/');
        String directory = slash < 0 ? "" : relative.substring(0, slash + 1);
        String file = slash < 0 ? relative : relative.substring(slash + 1);
        int dot = file.indexOf('.');
        String base = dot < 0 ? file : file.substring(0, dot);
        String extension = dot < 0 ? "" : file.substring(dot);
        if (base.length() > 6) {
            base = base.substring(0, 6);
        }
        for (int i = 1; i < 100; i++) {
            String candidate = directory + base + "~" + i + extension;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        return relative;
    }

    /* ------------------------------------------------------ the entry point */
    /**
     * Picks the file that most likely starts the game.
     *
     * <p>The order follows what the DOS game collections actually look like:
     * a batch file that starts the game comes first ({@code play.bat},
     * {@code run.bat}, ...), then any other batch file, then an executable
     * whose name matches the archive, and finally the biggest executable -
     * for the many bundles whose only entry point is the main program.
     */
    private String detectEntryPoint(File folder) {
        List<String> candidates = new ArrayList<String>();
        collectCandidates(folder, "", candidates);

        // Only the root of the bundle is considered: a start-up file deeper
        // in the tree is data, not the entry point.
        List<String> root = new ArrayList<String>();
        for (String candidate : candidates) {
            if (candidate.indexOf('\\') < 0) {
                root.add(candidate);
            }
        }

        for (String preferred : PREFERRED_BATCH_FILES) {
            for (String candidate : root) {
                if (candidate.toLowerCase(Locale.US).equals(preferred)) {
                    return candidate;
                }
            }
        }

        List<String> batches = new ArrayList<String>();
        List<String> executables = new ArrayList<String>();
        for (String candidate : root) {
            String lower = candidate.toLowerCase(Locale.US);
            if (lower.endsWith(".bat")) {
                batches.add(candidate);
            } else if (lower.endsWith(".exe") || lower.endsWith(".com")) {
                executables.add(candidate);
            }
        }
        if (!batches.isEmpty()) {
            return batches.get(0);
        }
        if (!executables.isEmpty()) {
            return biggestOf(folder, executables);
        }

        // Nothing in the root - fall back to the whole bundle.
        if (!candidates.isEmpty()) {
            for (String candidate : candidates) {
                if (candidate.toLowerCase(Locale.US).endsWith(".bat")) {
                    return candidate;
                }
            }
            return biggestOf(folder, candidates);
        }
        return null;
    }

    /**
     * @return the candidate with the largest file - the main program of a
     *         bundle that has no start-up script.
     */
    private String biggestOf(File folder, List<String> candidates) {
        String best = null;
        long bestSize = -1;
        for (String candidate : candidates) {
            File file = resolveSafely(folder, candidate.replace('\\', '/'));
            if (file == null || !file.exists()) {
                continue;
            }
            long size = file.length();
            if (size > bestSize) {
                bestSize = size;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Writes the batch file the app types into DOSBox.
     *
     * <p>It switches to the game folder first, so the program finds its data
     * files, and then starts the entry point.
     */
    private void writeRunBat(File folder, String folderName, String entry) throws IOException {
        String directory = entry.lastIndexOf('\\') < 0
                ? "" : entry.substring(0, entry.lastIndexOf('\\'));
        String file = entry.lastIndexOf('\\') < 0
                ? entry : entry.substring(entry.lastIndexOf('\\') + 1);

        StringBuilder text = new StringBuilder();
        text.append("@ECHO OFF\r\n");
        text.append("C:\r\n");
        text.append("CD \\").append(GAMES_DIR).append('\\').append(folderName);
        if (directory.length() > 0) {
            text.append('\\').append(directory);
        }
        text.append("\r\n");
        // A name with a space has to be quoted for the command interpreter.
        if (file.indexOf(' ') >= 0) {
            text.append('"').append(file).append('"');
        } else {
            text.append(file);
        }
        text.append("\r\n");

        OutputStream out = new BufferedOutputStream(new FileOutputStream(new File(folder, RUN_BAT)));
        try {
            out.write(text.toString().getBytes("ISO-8859-1"));
        } finally {
            out.close();
        }
    }

    /* --------------------------------------------------------------- helpers */

    /**
     * Maps an archive entry to a file inside the target folder, refusing
     * anything that would escape it.
     *
     * @return the file, or null if the name is not acceptable.
     */
    private static File resolveSafely(File target, String name) {
        String cleaned = name.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains(":")) {
            return null;
        }
        // No part of the name may walk out of the folder.
        String[] parts = cleaned.split("/");
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0 || parts[i].equals(".")) {
                continue;
            }
            if (parts[i].equals("..")) {
                return null;
            }
            if (safe.length() > 0) {
                safe.append('/');
            }
            safe.append(parts[i]);
        }
        File out = new File(target, safe.toString());
        String root = target.getAbsolutePath();
        String path = out.getAbsolutePath();
        if (!path.startsWith(root + File.separator)) {
            return null;
        }
        return out;
    }

    private int nextIndex() {
        int highest = 0;
        File[] children = gamesDir().listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory() || !child.getName().startsWith("G")) {
                    continue;
                }
                try {
                    highest = Math.max(highest,
                            Integer.parseInt(child.getName().substring(1)));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        }
        return highest + 1;
    }

    private static long copy(InputStream in, File file, long declaredSize)
            throws IOException {
        if (declaredSize > MAX_ENTRY_BYTES) {
            throw new IOException("file in the archive is too big: " + declaredSize);
        }
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
        long total = 0;
        try {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IOException("file in the archive is too big");
                }
                out.write(buffer, 0, n);
            }
        } finally {
            out.close();
        }
        return total;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
