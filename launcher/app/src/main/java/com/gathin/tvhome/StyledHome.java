package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A Home drawn as one scene instead of the tile grid (home_style in a theme): the turntable or the bubbles.
 * MainActivity keeps loading the apps, opening them and showing its Inputs and settings menus; the style only
 * draws and moves the focus. It reads its artwork from the theme's home/ folder, and {@link #create} returns
 * null whenever that is missing, so Home falls back to the grid instead of showing an empty scene.
 */
interface StyledHome {

    /** What the style asks of Home. */
    interface Host {
        void launch(int index);
        void inputs();
        void network();
        void settings();
        /** Hold OK on settings: the launcher's own menu (reorder, desktops, module cards). */
        void settingsMenu();
        /** All apps, when the style shows only some of them. */
        void library();
        /** Something in the style broke on this TV: Home drops it and shows the grid instead of crashing. */
        void failed(RuntimeException e);
    }

    /** One app as a style shows it. */
    final class App {
        final String label;
        final Drawable icon;
        /** A colour taken from the icon, for the turntable's sleeves and labels. */
        final int color;

        App(String label, Drawable icon, int color) {
            this.label = label;
            this.icon = icon;
            this.color = color;
        }
    }

    View view();

    /** The apps in Home's order; index i is Host.launch(i). Called on the main thread after every reload. */
    void setApps(List<App> apps);

    void resume();

    void pause();

    /** The Home button while Home is showing: back to the first app. */
    void home();

    /** Memory is short and Home is in the background: let go of what can be loaded again. */
    void trim();

    /** The style the theme asks for, or null for the grid or when its files are missing. */
    static StyledHome create(Context context, Config config, Host host) {
        boolean calm = config.reducedMotion || !config.animations;
        if (Config.HOME_TURNTABLE.equals(config.homeStyle)) return TurntableHome.create(context, config.homeDir, host, calm);
        if (Config.HOME_BUBBLES.equals(config.homeStyle)) return BubbleHome.create(context, config.homeDir, host, calm);
        return null;
    }

    /** Icons and colours for Home's entries. Runs off the main thread: icon loading is a binder call per app. */
    static List<App> apps(Context context, List<AppAdapter.Entry> entries) {
        PackageManager pm = context.getPackageManager();
        List<App> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            AppAdapter.Entry e = entries.get(i);
            Drawable icon = e.icon;
            if (icon == null) {
                try {
                    icon = pm.getActivityIcon(e.component);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                    icon = pm.getDefaultActivityIcon();
                }
            }
            // A copy: the grid's tiles hold the original, and a style sets bounds and alpha on its own.
            if (icon != null && icon.getConstantState() != null) icon = icon.getConstantState().newDrawable().mutate();
            out.add(new App(e.label, icon, colorOf(icon, i)));
        }
        return out;
    }

    /** Warm, muted sleeve colours for icons without a clear colour of their own (white or grey icons). */
    int[] FALLBACK = {0xFF4A2D8C, 0xFF7D1B25, 0xFFB3241D, 0xFFC8432A, 0xFF2C6A39, 0xFFD09614, 0xFF22559C, 0xFF4B5360};

    /** The icon's most saturated colour family, toned to sit in a dim room: saturation and value kept mid. */
    static int colorOf(Drawable icon, int index) {
        int fallback = FALLBACK[Math.floorMod(index, FALLBACK.length)];
        if (icon == null) return fallback;
        Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(b);
            icon.setBounds(0, 0, 24, 24);
            icon.draw(c);
        } catch (RuntimeException e) {
            b.recycle();
            return fallback;
        }
        float[] hsv = new float[3];
        double sx = 0, sy = 0, wsum = 0, vsum = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                int a = Color.alpha(p);
                if (a < 128) continue;
                Color.colorToHSV(p, hsv);
                double w = hsv[1] * hsv[1] * hsv[2];
                if (w <= 0.02) continue;
                double rad = Math.toRadians(hsv[0]);
                sx += Math.cos(rad) * w;
                sy += Math.sin(rad) * w;
                vsum += hsv[2] * w;
                wsum += w;
            }
        }
        b.recycle();
        if (wsum < 3) return fallback;
        float hue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
        return Color.HSVToColor(new float[] {hue, 0.62f, (float) Math.max(0.42, Math.min(0.66, vsum / wsum * 0.7))});
    }

    /** A theme image decoded no larger than needed, or null when the file is missing or broken. */
    static Bitmap decode(File dir, String name, int maxWidth, boolean opaque) {
        if (dir == null) return null;
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (maxWidth > 0 && o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Component key for an app, so a style can keep its focus across a reload. */
    static String key(ComponentName c) {
        return c == null ? "" : c.flattenToShortString();
    }
}
