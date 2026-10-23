package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * home_style "bubbles": a looping video wallpaper (home/video.mp4) with the apps as soap bubbles floating on the
 * right. The focused bubble swells and glows and its neighbours drift aside; OK pops it and it blows up again.
 * Meant for TVs with memory to spare: the video plays through Android's own MediaPlayer, silent, and the player is
 * released the moment Home is left, so another app never waits for the decoder. Without a video it shows
 * home/poster.jpg, or black.
 *
 * Positions are in a 1920 x 1080 design and scaled to the screen.
 */
final class BubbleHome extends FrameLayout implements StyledHome, TextureView.SurfaceTextureListener {
    private static final float W = 1920f, H = 1080f;
    /** Bubbles on screen at most; with more apps the last bubble opens the app library. */
    private static final int MAX_BUBBLES = 11;
    private static final float FOCUS_SCALE = 1.75f;
    private static final int TEAL = 0xFF7FF5DC;

    private final Host host;
    private final boolean calm;
    private final File video;
    private final TextureView texture;
    private final Layer layer;
    private MediaPlayer player;
    private Surface surface;
    private boolean resumed;

    private List<App> apps = new ArrayList<>();
    /** One per bubble: index into apps, or -1 for "all apps". */
    private final List<Bubble> bubbles = new ArrayList<>();
    /** Focus: an index into bubbles, or bubbles.size() + k for system button k. */
    private int focus = 0;

    static BubbleHome create(Context context, File dir, Host host, boolean calm) {
        return new BubbleHome(context, dir, host, calm);
    }

    private BubbleHome(Context context, File dir, Host host, boolean calm) {
        super(context);
        this.host = host;
        this.calm = calm;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setBackgroundColor(Color.BLACK);
        Bitmap posterBitmap = StyledHome.decode(dir, "poster.jpg", 1920, true);
        if (posterBitmap != null) {
            ImageView poster = new ImageView(context);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setImageBitmap(posterBitmap);
            addView(poster, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        File v = dir == null ? null : new File(dir, "video.mp4");
        video = v != null && v.isFile() ? v : null;
        texture = new TextureView(context);
        // Not opaque: until the first frame arrives it would paint black over the poster.
        texture.setOpaque(false);
        texture.setSurfaceTextureListener(this);
        if (video != null) addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        layer = new Layer(context);
        addView(layer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override public View view() { return this; }

    // ---- video -----------------------------------------------------------------------------------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        if (resumed) startVideo();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { fitVideo(); }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stopVideo();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private void startVideo() {
        if (video == null || surface == null || player != null) return;
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(video.getPath());
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (player != p || !resumed) return;
                fitVideo();
                p.start();
            });
            // A file this TV cannot decode: drop the video for good and leave the poster.
            mp.setOnErrorListener((p, what, extra) -> {
                stopVideo();
                texture.setVisibility(GONE);
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            mp.release();
            texture.setVisibility(GONE);
        }
    }

    private void stopVideo() {
        if (player == null) return;
        try {
            player.stop();
        } catch (IllegalStateException ignored) {
        }
        player.release();
        player = null;
    }

    /** Centre-crop the video into the screen, as the poster is. */
    private void fitVideo() {
        if (player == null) return;
        int vw, vh;
        try {
            vw = player.getVideoWidth();
            vh = player.getVideoHeight();
        } catch (IllegalStateException e) {
            return;
        }
        float w = texture.getWidth(), h = texture.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float s = Math.max(w / vw, h / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / w, vh * s / h, w / 2, h / 2);
        texture.setTransform(m);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    @Override
    public void resume() {
        resumed = true;
        startVideo();
        layer.kick();
    }

    @Override
    public void pause() {
        resumed = false;
        stopVideo();
    }

    @Override
    public void home() {
        focusOn(0);
    }

    @Override
    public void trim() { }

    // ---- apps ------------------------------------------------------------------------------------------

    private static final class Bubble {
        final int app;
        float x, y, r;
        float ox, oy, oxTarget, oyTarget;
        float scale = 1f, velocity;
        float phase, period;
        long poppedAt;

        Bubble(int app) { this.app = app; }
    }

    @Override
    public void setApps(List<App> list) {
        try {
            apps = new ArrayList<>(list);
            bubbles.clear();
            int n = apps.size();
            boolean more = n > MAX_BUBBLES;
            int shown = more ? MAX_BUBBLES - 1 : n;
            for (int i = 0; i < shown; i++) bubbles.add(new Bubble(i));
            if (more) bubbles.add(new Bubble(-1));
            place();
            focusOn(Math.min(focus, Math.max(0, bubbles.size() - 1)));
        } catch (RuntimeException e) {
            host.failed(e);
        }
    }

    /** Two staggered columns on the right, a little uneven so they read as bubbles and not as a grid. */
    private void place() {
        int n = bubbles.size();
        int rows = Math.max(1, (n + 1) / 2);
        float r = rows <= 4 ? 72f : Math.max(46f, 72f * 4f / rows * 1.05f);
        float spacing = rows > 1 ? Math.min(240f, (840f - 165f) / (rows - 1)) : 0f;
        float[] ax = {1470, 1420, 1490, 1440, 1475, 1425};
        float[] bx = {1762, 1790, 1752, 1786, 1768, 1795};
        Random jitter = new Random(7);
        for (int i = 0; i < n; i++) {
            Bubble b = bubbles.get(i);
            int rowIndex = i / 2;
            boolean colA = i % 2 == 0;
            b.x = colA ? ax[rowIndex % ax.length] : bx[rowIndex % bx.length];
            b.y = 165 + rowIndex * spacing + (colA ? 0 : 63);
            b.r = r;
            b.phase = jitter.nextFloat() * 6.28f;
            b.period = 3.2f + (i % 3) * 0.7f;
        }
    }

    // ---- keys and focus --------------------------------------------------------------------------------

    private static final float[] SYS_X = {134, 274, 414};
    private static final float SYS_Y = 1000;

    private float itemX(int i) { return i < bubbles.size() ? bubbles.get(i).x : SYS_X[i - bubbles.size()]; }
    private float itemY(int i) { return i < bubbles.size() ? bubbles.get(i).y : SYS_Y; }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    event.startTracking();
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        } catch (RuntimeException e) {
            host.failed(e);
            return true;
        }
    }

    private boolean longPressed;

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            longPressed = true;
            host.settingsMenu();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        try {
            if (isOk(keyCode)) {
                if (longPressed) { longPressed = false; return true; }
                if (!event.isCanceled()) activate();
                return true;
            }
            return super.onKeyUp(keyCode, event);
        } catch (RuntimeException e) {
            host.failed(e);
            return true;
        }
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** The nearest item in the pressed direction, by position on screen. */
    private void move(int dx, int dy) {
        int total = bubbles.size() + 3;
        float ax = itemX(focus), ay = itemY(focus);
        int best = -1;
        float score = Float.MAX_VALUE;
        for (int j = 0; j < total; j++) {
            if (j == focus) continue;
            float ddx = itemX(j) - ax, ddy = itemY(j) - ay;
            // The columns are deliberately uneven: a sideways step has to reach the other column, not the
            // neighbour that happens to sit a little further out.
            boolean ok = dx < 0 ? ddx < -120 : dx > 0 ? ddx > 120 : dy < 0 ? ddy < -40 : ddy > 40;
            if (!ok) continue;
            float s = dx != 0 ? Math.abs(ddx) + 2.2f * Math.abs(ddy) : Math.abs(ddy) + 1.4f * Math.abs(ddx);
            if (s < score) { score = s; best = j; }
        }
        if (best >= 0) focusOn(best);
    }

    private void focusOn(int i) {
        focus = Math.max(0, Math.min(bubbles.size() + 2, i));
        // The focused bubble swells; bubbles close to it drift aside so no name is covered.
        Bubble f = focus < bubbles.size() ? bubbles.get(focus) : null;
        for (Bubble b : bubbles) {
            b.oxTarget = 0;
            b.oyTarget = 0;
            // Still pushed aside with reduced motion, only without the drift: a covered name helps nobody.
            if (f == null || b == f) continue;
            float ddx = b.x - f.x, ddy = b.y - f.y;
            if (Math.abs(ddx) < 150) {
                // Same column: everything above moves up and everything below moves down by what the focus grew,
                // so the column opens around it and its other bubbles keep their spacing.
                float grown = f.r * (FOCUS_SCALE - 1f) + 8f;
                b.oyTarget = Math.signum(ddy) * grown;
            } else {
                // The other column only leans away where it is level with the focus.
                float level = Math.max(0, 220f - Math.abs(ddy));
                b.oxTarget = Math.max(1260 - b.x, Math.min(1840 - b.x, Math.signum(ddx) * level * 0.3f));
            }
        }
        if (f != null && !calm) f.velocity += 2.5f;   // a little wobble as it swells
        layer.kick();
    }

    private void activate() {
        if (focus >= bubbles.size()) {
            switch (focus - bubbles.size()) {
                case 0: host.inputs(); break;
                case 1: host.network(); break;
                default: host.settings(); break;
            }
            return;
        }
        Bubble b = bubbles.get(focus);
        if (!calm) b.poppedAt = SystemClock.uptimeMillis();
        layer.kick();
        if (b.app < 0) host.library();
        else host.launch(b.app);
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    private final class Layer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadeLeft = new Paint(), shadeBottom = new Paint();
        private final Matrix rimMatrix = new Matrix();
        private final SweepGradient rainbow = new SweepGradient(0, 0,
                new int[] {0xFFFF7AD9, 0xFFFFD36E, 0xFF7DFFB0, 0xFF6EC8FF, 0xFFB18CFF, 0xFFFF7AD9}, null);
        private final Typeface light, regular, medium;
        private final Drawable[] sysIcons;
        private final String[] sysLabels;
        private Bitmap glass;
        private float glassFor;
        private long last;
        private float hue;
        private final float[] moteX = new float[26], moteY = new float[26], moteR = new float[26],
                moteV = new float[26], moteW = new float[26], moteA = new float[26];
        private final Random random = new Random();
        private final RectF rect = new RectF();
        private String timeText = "", dateText = "";
        private int clockMinute = -1;

        Layer(Context c) {
            super(c);
            setWillNotDraw(false);
            light = UiFont.get(c, R.string.font_light);
            regular = UiFont.get(c, R.string.font_regular);
            medium = UiFont.get(c, R.string.font_medium);
            rim.setStyle(Paint.Style.STROKE);
            rim.setShader(rainbow);
            glow.setStyle(Paint.Style.STROKE);
            sysIcons = new Drawable[] {c.getDrawable(R.drawable.ic_input), c.getDrawable(R.drawable.ic_wifi), c.getDrawable(R.drawable.ic_settings)};
            sysLabels = new String[] {c.getString(R.string.inputs), c.getString(R.string.network), c.getString(R.string.settings)};
            for (int i = 0; i < moteX.length; i++) spawn(i, true);
        }

        void kick() {
            last = 0;
            postInvalidateOnAnimation();
        }

        private void spawn(int i, boolean anywhere) {
            moteX[i] = 1150 + random.nextFloat() * 770;
            moteY[i] = anywhere ? random.nextFloat() * H : H + 10;
            moteR[i] = 2 + random.nextFloat() * 6;
            moteV[i] = 18 + random.nextFloat() * 30;
            moteW[i] = random.nextFloat() * 6.28f;
            moteA[i] = 0.25f + random.nextFloat() * 0.45f;
        }

        /** A glass sphere: clear in the middle, brighter towards the rim, a highlight up and to the left. */
        private Bitmap glass(float radiusPx) {
            if (glass != null && Math.abs(glassFor - radiusPx) < 1) return glass;
            if (glass != null) glass.recycle();
            int size = Math.max(8, Math.round(radiusPx * 2));
            glass = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(glass);
            float r = size / 2f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(r, r * 1.1f, r, new int[] {0x00FFFFFF, 0x00FFFFFF, 0x14AAFFEB, 0x42FFFFFF},
                    new float[] {0f, 0.56f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(r, r, r, p);
            p.setShader(new RadialGradient(r * 1.25f, r * 0.7f, r * 0.9f, new int[] {0x00000000, 0x2978D2FF},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(r, r, r, p);
            c.save();
            c.rotate(-30, r * 0.7f, r * 0.44f);
            p.setShader(new RadialGradient(r * 0.7f, r * 0.44f, r * 0.3f, new int[] {0xD9FFFFFF, 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.scale(1f, 0.6f, r * 0.7f, r * 0.44f);
            c.drawCircle(r * 0.7f, r * 0.44f, r * 0.3f, p);
            c.restore();
            glassFor = radiusPx;
            return glass;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = w / W;
            shadeLeft.setShader(new LinearGradient(0, 0, w, 0, new int[] {0x6B000000, 0x00000000, 0x00000000, 0x61000000},
                    new float[] {0f, 0.26f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            shadeBottom.setShader(new LinearGradient(0, h, 0, h - 240 * s, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            try {
                long now = SystemClock.uptimeMillis();
                float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1000f);
                last = now;
                float s = getWidth() / W;
                float t = now / 1000f;
                c.drawRect(0, 0, getWidth(), getHeight(), shadeLeft);
                c.drawRect(0, 0, getWidth(), getHeight(), shadeBottom);
    
                c.save();
                c.scale(s, s);
                drawClock(c);
                if (!calm) drawMotes(c, dt);
    
                hue = (hue + dt * 360f / 14f) % 360f;
                float maxR = 72f * FOCUS_SCALE;
                Bitmap g = glass(maxR * s);
                // Unfocused first, the focused bubble last so it sits on top.
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < bubbles.size(); i++) {
                        boolean isFocus = i == focus;
                        if ((pass == 1) != isFocus) continue;
                        drawBubble(c, bubbles.get(i), isFocus, g, t, dt, now);
                    }
                }
                drawCallout(c);
                drawSystem(c);
                c.restore();
                if (resumed) postInvalidateDelayed(Math.max(1, 33 - (SystemClock.uptimeMillis() - now)));
            } catch (RuntimeException e) {
                host.failed(e);
            }
        }

        private void drawClock(Canvas c) {
            Calendar cal = Calendar.getInstance();
            int key = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            if (key != clockMinute) {
                clockMinute = key;
                Locale locale = getResources().getConfiguration().getLocales().get(0);
                timeText = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", cal).toString();
                dateText = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE"), cal).toString();
            }
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.WHITE);
            text.setShadowLayer(20, 0, 2, 0x66000000);
            text.setTypeface(light);
            text.setTextSize(128);
            text.setLetterSpacing(-0.01f);
            c.drawText(timeText, 90, 64 + 112, text);
            text.setTypeface(regular);
            text.setTextSize(30);
            text.setLetterSpacing(0.12f);
            text.setColor(0xEBFFFFFF);
            c.drawText(dateText, 92, 64 + 128 + 44, text);
            text.setLetterSpacing(0f);
        }

        private void drawMotes(Canvas c, float dt) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            for (int i = 0; i < moteX.length; i++) {
                moteY[i] -= moteV[i] * dt;
                moteW[i] += dt * 1.6f;
                if (moteY[i] < -20) spawn(i, false);
                float x = moteX[i] + (float) Math.sin(moteW[i]) * 6;
                int a = Math.round(moteA[i] * 255);
                paint.setColor(Color.argb(a, 200, 255, 245));
                c.drawCircle(x, moteY[i], moteR[i], paint);
            }
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < moteX.length; i++) {
                float x = moteX[i] + (float) Math.sin(moteW[i]) * 6;
                paint.setColor(Color.argb(Math.round(moteA[i] * 255), 255, 255, 255));
                c.drawCircle(x - moteR[i] * 0.35f, moteY[i] - moteR[i] * 0.35f, moteR[i] * 0.28f, paint);
            }
        }

        private void drawBubble(Canvas c, Bubble b, boolean isFocus, Bitmap g, float t, float dt, long now) {
            // Spring towards the focus size; drift towards the place the focus leaves it.
            float target = isFocus ? FOCUS_SCALE : 1f;
            if (calm) {
                b.scale = target;
                b.velocity = 0;
            } else {
                b.velocity += (target - b.scale) * 140f * dt - b.velocity * 11f * dt;
                b.scale += b.velocity * dt;
            }
            float k = calm ? 1f : Math.min(1f, dt * 7f);
            b.ox += (b.oxTarget - b.ox) * k;
            b.oy += (b.oyTarget - b.oy) * k;
            float bobX = calm ? 0 : (float) Math.sin(t * 6.283f / (b.period * 2) + b.phase) * 1.5f;
            float bobY = calm ? 0 : (float) Math.sin(t * 6.283f / (b.period * 2) + b.phase) * 7.5f;
            float x = b.x + b.ox + bobX, y = b.y + b.oy + bobY;
            float r = b.r * b.scale;
            float alpha = 1f;
            long popped = now - b.poppedAt;
            if (b.poppedAt != 0 && popped < 1000) {
                if (popped < 120) { r *= 1f + 0.18f * popped / 120f; alpha = 1f - popped / 120f; }
                else if (popped < 520) alpha = 0f;
                else { float p = (popped - 520) / 480f; r *= 0.2f + 0.8f * p; alpha = p; }
                drawDrops(c, x, y, b.r * b.scale, popped);
            }
            if (alpha <= 0f) return;

            if (isFocus) {
                for (int i = 3; i >= 1; i--) {
                    glow.setColor(Color.argb(Math.round(alpha * (12 + (3 - i) * 8)), 150, 255, 230));
                    glow.setStrokeWidth(r * 0.08f * i);
                    c.drawCircle(x, y, r * (0.98f + 0.04f * i), glow);
                }
            }
            paint.setAlpha(Math.round(alpha * 255));
            rect.set(x - r, y - r, x + r, y + r);
            c.drawBitmap(g, null, rect, paint);
            paint.setAlpha(255);

            rimMatrix.setRotate(hue);
            rimMatrix.postTranslate(x, y);
            rainbow.setLocalMatrix(rimMatrix);
            rim.setStrokeWidth(r * 0.08f);
            rim.setAlpha(Math.round(alpha * (isFocus ? 255 : 128)));
            c.drawCircle(x, y, r * 0.69f, rim);

            Drawable icon = iconOf(b);
            if (icon != null) {
                float half = r * 0.5f;
                icon.setBounds(Math.round(x - half), Math.round(y - half), Math.round(x + half), Math.round(y + half));
                icon.setAlpha(Math.round(alpha * 255));
                icon.draw(c);
                icon.setAlpha(255);
            }
            if (!isFocus) {
                text.setTextAlign(Paint.Align.CENTER);
                text.setTypeface(regular);
                text.setTextSize(26);
                text.setColor(Color.WHITE);
                text.setShadowLayer(8, 0, 1, 0xB3000000);
                c.drawText(TextUtils.ellipsize(labelOf(b), text, 200, TextUtils.TruncateAt.END).toString(),
                        x, y + b.r + 38, text);
            }
        }

        private void drawDrops(Canvas c, float x, float y, float r, long popped) {
            if (popped > 600) return;
            float p = popped / 600f;
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 12; i++) {
                double a = i / 12.0 * Math.PI * 2 + i * 0.37;
                float d = r * (0.9f + 0.6f * ((i * 7) % 5) / 5f) * e + r * 0.2f;
                paint.setColor(Color.argb(Math.round(220 * (1 - p)), 200, 255, 245));
                c.drawCircle(x + (float) Math.cos(a) * d, y + (float) Math.sin(a) * d, 5f * (1 - p * 0.5f), paint);
            }
        }

        private void drawCallout(Canvas c) {
            if (focus >= bubbles.size()) return;
            Bubble b = bubbles.get(focus);
            float y = b.y + b.oy;
            text.setTextAlign(Paint.Align.RIGHT);
            text.setShadowLayer(18, 0, 2, 0x99000000);
            text.setColor(Color.WHITE);
            text.setTypeface(medium);
            text.setTextSize(64);
            float right = Math.min(1280f, b.x - b.r * FOCUS_SCALE - 30);
            c.drawText(TextUtils.ellipsize(labelOf(b), text, 640, TextUtils.TruncateAt.END).toString(), right, y + 10, text);
            text.setTypeface(regular);
            text.setTextSize(22);
            text.setColor(0xC7FFFFFF);
            c.drawText(getContext().getString(R.string.press_ok_to_open), right, y + 48, text);
        }

        private void drawSystem(Canvas c) {
            for (int k = 0; k < 3; k++) {
                float cx = SYS_X[k];
                boolean f = focus == bubbles.size() + k;
                if (f) {
                    rect.set(cx - 60, SYS_Y - 66, cx + 60, SYS_Y + 46);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0x8C0A2824);
                    c.drawRoundRect(rect, 22, 22, paint);
                    glow.setStrokeWidth(2);
                    glow.setColor(0xCC7FF5DC);
                    c.drawRoundRect(rect, 22, 22, glow);
                }
                Drawable d = sysIcons[k];
                if (d != null) {
                    d.setBounds(Math.round(cx - 20), Math.round(SYS_Y - 52), Math.round(cx + 20), Math.round(SYS_Y - 12));
                    d.setTint(Color.WHITE);
                    d.draw(c);
                }
                text.setTextAlign(Paint.Align.CENTER);
                text.setTypeface(regular);
                text.setTextSize(24);
                text.setColor(f ? TEAL : Color.WHITE);
                text.setShadowLayer(8, 0, 1, 0xB3000000);
                c.drawText(sysLabels[k], cx, SYS_Y + 26, text);
            }
        }
    }

    private Drawable allApps;

    private Drawable iconOf(Bubble b) {
        if (b.app < 0) {
            if (allApps == null) allApps = getContext().getDrawable(R.drawable.ic_apps_grid);
            return allApps;
        }
        return b.app < apps.size() ? apps.get(b.app).icon : null;
    }

    private String labelOf(Bubble b) {
        if (b.app < 0) return getContext().getString(R.string.app_library);
        return b.app < apps.size() ? apps.get(b.app).label : "";
    }
}
