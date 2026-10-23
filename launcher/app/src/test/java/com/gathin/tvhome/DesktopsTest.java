package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DesktopsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void put(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void noDesktopsMeansAnEmptyList() throws IOException {
        assertTrue(Desktops.list(null).isEmpty());
        assertTrue(Desktops.list(tmp.newFolder("theme")).isEmpty());
    }

    @Test public void listsDesktopsWithAConfigByName() throws IOException {
        File dir = tmp.newFolder("theme");
        put(new File(dir, "desktops/glass/config.json"), "{}");
        put(new File(dir, "desktops/aurora/config.json"), "{}");
        put(new File(dir, "desktops/empty/wallpaper.jpg"), "x");          // no config.json: not a desktop
        put(new File(dir, "desktops/bad name/config.json"), "{}");        // refused name
        List<Desktops.Desktop> list = Desktops.list(dir);
        assertEquals(2, list.size());
        assertEquals("aurora", list.get(0).name);
        assertEquals("glass", list.get(1).name);
    }

    @Test public void labelsReadLikeWords() {
        assertEquals("Six columns", Desktops.label("six-columns"));
        assertEquals("Turntable", Desktops.label("turntable"));
        assertEquals("My theme", Desktops.label("my_theme"));
    }

    @Test public void theActiveDesktopIsTheOneWithTheSameConfig() throws IOException {
        File dir = tmp.newFolder("theme");
        put(new File(dir, "desktops/a/config.json"), "{\"columns\":4}");
        put(new File(dir, "desktops/b/config.json"), "{\"columns\":6}");
        List<Desktops.Desktop> list = Desktops.list(dir);
        assertEquals(-1, Desktops.activeIndex(dir, list));
        put(new File(dir, "config.json"), "{\"columns\":6}");
        assertEquals(1, Desktops.activeIndex(dir, list));
    }

    @Test public void applyReplacesConfigWallpaperCollectionAndRevision() throws IOException {
        File dir = tmp.newFolder("theme");
        put(new File(dir, "config.json"), "{\"old\":true}");
        put(new File(dir, "wallpaper.jpg"), "old-wallpaper");
        put(new File(dir, "wallpapers/old.jpg"), "old-image");
        put(new File(dir, "wallpapers/captions.txt"), "old captions");
        put(new File(dir, "desktops/scenes/config.json"), "{\"wallpaper_rotation_minutes\":60}");
        put(new File(dir, "desktops/scenes/wallpapers/01.jpg"), "one");
        put(new File(dir, "desktops/scenes/wallpapers/02.jpg"), "two");
        put(new File(dir, "desktops/scenes/preview.jpg"), "preview");

        Desktops.Desktop scenes = Desktops.list(dir).get(0);
        assertTrue(Desktops.apply(dir, scenes, "rev123"));

        assertEquals("{\"wallpaper_rotation_minutes\":60}", read(new File(dir, "config.json")));
        assertFalse(new File(dir, "wallpaper.jpg").exists());
        String[] images = new File(dir, "wallpapers").list();
        Arrays.sort(images);
        assertArrayEquals(new String[] {"01.jpg", "02.jpg"}, images);
        assertEquals("rev123", read(new File(dir, "theme_revision")));
        assertFalse(new File(dir, "preview.jpg").exists());                // the preview stays with the desktop
        assertEquals(0, Desktops.activeIndex(dir, Desktops.list(dir)));
    }

    @Test public void applyBringsTheDesktopsSingleWallpaper() throws IOException {
        File dir = tmp.newFolder("theme");
        put(new File(dir, "desktops/aurora/config.json"), "{\"wallpaper\":true}");
        put(new File(dir, "desktops/aurora/wallpaper.jpg"), "aurora");
        assertTrue(Desktops.apply(dir, Desktops.list(dir).get(0), "r"));
        assertEquals("aurora", read(new File(dir, "wallpaper.jpg")));
        assertNull(new File(dir, "wallpapers").list());
    }

    @Test public void applyReplacesTheHomeStyleFiles() throws IOException {
        File dir = tmp.newFolder("theme");
        put(new File(dir, "home/old.jpg"), "old");
        put(new File(dir, "desktops/bubbles/config.json"), "{\"home_style\":\"bubbles\"}");
        put(new File(dir, "desktops/bubbles/home/video.mp4"), "video");
        assertTrue(Desktops.apply(dir, Desktops.list(dir).get(0), "r"));
        assertArrayEquals(new String[] {"video.mp4"}, new File(dir, "home").list());
        put(new File(dir, "desktops/aaa/config.json"), "{}");
        assertTrue(Desktops.apply(dir, Desktops.list(dir).get(0), "r2"));
        assertEquals(0, new File(dir, "home").list().length);
    }

    @Test public void previewIsOptional() throws IOException {
        File dir = tmp.newFolder("theme");
        put(new File(dir, "desktops/plain/config.json"), "{}");
        assertNull(Desktops.list(dir).get(0).preview());
        put(new File(dir, "desktops/plain/preview.jpg"), "p");
        assertEquals("preview.jpg", Desktops.list(dir).get(0).preview().getName());
    }
}
