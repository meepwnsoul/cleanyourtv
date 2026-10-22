package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Several complete themes ("desktops") kept on the TV, one of them active.
 *
 * tools/theme.sh --add themes/aurora themes/glass ... writes each theme into its own folder under
 * {@code <Config.dir>/desktops/<name>/} (config.json, wallpaper.jpg, wallpapers/, preview.jpg) without touching
 * the active theme. Switching copies one desktop into the active theme files that {@link Config#load} already
 * reads, then writes a new theme_revision, exactly as a theme push from the computer does. So the loader is
 * unchanged, and a TV with no desktops pushed behaves as before: nothing here runs until Launcher settings is
 * opened, and then only one directory listing.
 */
final class Desktops {
    static final String FOLDER = "desktops";
    static final String PREVIEW = "preview.jpg";

    private Desktops() {}

    static final class Desktop {
        final String name;
        final File folder;

        Desktop(String name, File folder) {
            this.name = name;
            this.folder = folder;
        }

        String label() { return Desktops.label(name); }

        /** The picture shown in the picker, or null when the theme came without one. */
        File preview() {
            File f = new File(folder, PREVIEW);
            return f.isFile() && f.length() > 0 ? f : null;
        }
    }

    /** Every desktop with a config.json, by name; empty when none was pushed. */
    static List<Desktop> list(File themeDir) {
        if (themeDir == null) return Collections.emptyList();
        File[] folders = new File(themeDir, FOLDER).listFiles(File::isDirectory);
        if (folders == null) return Collections.emptyList();
        Arrays.sort(folders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<Desktop> out = new ArrayList<>();
        for (File f : folders) {
            if (ThemeFiles.desktopName(f.getName()) && new File(f, Config.FILE_NAME).isFile()) {
                out.add(new Desktop(f.getName(), f));
            }
        }
        return out;
    }

    /** "six-columns" reads as "Six columns" in the picker. */
    static String label(String name) {
        String s = name.replace('-', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) return name;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** The desktop whose config.json is the active one, byte for byte; -1 when none matches. */
    static int activeIndex(File themeDir, List<Desktop> desktops) {
        if (themeDir == null) return -1;
        byte[] active = readSmall(new File(themeDir, Config.FILE_NAME));
        if (active == null) return -1;
        for (int i = 0; i < desktops.size(); i++) {
            if (Arrays.equals(active, readSmall(new File(desktops.get(i).folder, Config.FILE_NAME)))) return i;
        }
        return -1;
    }

    /**
     * Makes {@code d} the active theme: its config.json, its wallpaper.jpg (or none), its wallpapers/ collection
     * (or none), then {@code revision} as theme_revision so on-TV layout choices reset as after a push.
     * Every file is written to a temporary name and renamed into place, so Home never reads half a file.
     */
    static boolean apply(File themeDir, Desktop d, String revision) {
        try {
            copy(new File(d.folder, Config.FILE_NAME), new File(themeDir, Config.FILE_NAME));
            File wallpaper = new File(d.folder, Config.WALLPAPER_NAME);
            File activeWallpaper = new File(themeDir, Config.WALLPAPER_NAME);
            if (wallpaper.isFile()) copy(wallpaper, activeWallpaper);
            else if (activeWallpaper.exists() && !activeWallpaper.delete()) throw new IOException("delete " + activeWallpaper);

            File target = new File(themeDir, ThemeFiles.WALLPAPERS);
            File[] old = target.listFiles((dir, name) -> ThemeFiles.collectionImage(name)
                    || WallpaperCaptions.FILE_NAME.equals(name));
            if (old != null) for (File f : old) f.delete();
            File[] images = new File(d.folder, ThemeFiles.WALLPAPERS).listFiles((dir, name) ->
                    ThemeFiles.collectionImage(name) || WallpaperCaptions.FILE_NAME.equals(name));
            if (images != null && images.length > 0) {
                if (!target.isDirectory() && !target.mkdirs()) throw new IOException("mkdir " + target);
                for (File f : images) copy(f, new File(target, f.getName()));
            }
            write(new File(themeDir, "theme_revision"), revision.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void copy(File from, File to) throws IOException {
        File tmp = new File(to.getParentFile(), "." + to.getName() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        replace(tmp, to);
    }

    private static void write(File to, byte[] bytes) throws IOException {
        File tmp = new File(to.getParentFile(), "." + to.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        replace(tmp, to);
    }

    private static void replace(File tmp, File to) throws IOException {
        if (tmp.renameTo(to)) return;
        // A file another user (the adb shell) created may refuse to be replaced by a rename; remove it first.
        to.delete();
        if (!tmp.renameTo(to)) {
            tmp.delete();
            throw new IOException("rename " + tmp + " -> " + to);
        }
    }

    /** A config.json is at most 64 KB (Config ignores bigger ones); anything else reads as null. */
    private static byte[] readSmall(File f) {
        if (f == null || !f.isFile() || f.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            return off == b.length ? b : null;
        } catch (IOException e) {
            return null;
        }
    }
}
