package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        // A stored desktop (tools/theme.sh --add): desktops/<name>/config.json, wallpaper.jpg, preview.jpg,
        // and its own wallpapers/ collection.
        if (segments.size() >= 3 && Desktops.FOLDER.equals(segments.get(0)) && desktopName(segments.get(1))) {
            String slot = Desktops.FOLDER + "/" + segments.get(1) + "/";
            if (segments.size() == 3) {
                String name = segments.get(2);
                return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || Desktops.PREVIEW.equals(name)
                        ? slot + name : null;
            }
            if (segments.size() == 4 && WALLPAPERS.equals(segments.get(2))
                    && (collectionImage(segments.get(3)) || WallpaperCaptions.FILE_NAME.equals(segments.get(3)))) {
                return slot + WALLPAPERS + "/" + segments.get(3);
            }
        }
        return null;
    }

    private static final Pattern DESKTOP = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    /** A desktop's folder name: letters, digits, '-' and '_' only, so it can never climb out of desktops/. */
    static boolean desktopName(String name) {
        return name != null && DESKTOP.matcher(name).matches();
    }

    /** True for "desktops/<name>" alone: a delete of it removes that whole desktop. */
    static boolean isDesktop(List<String> segments) {
        return segments != null && segments.size() == 2 && Desktops.FOLDER.equals(segments.get(0))
                && desktopName(segments.get(1));
    }

    /** True for "wallpapers" alone: a delete of it clears the collection's images. */
    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0));
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
