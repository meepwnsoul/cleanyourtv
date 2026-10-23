package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * home_style "turntable": a hi-fi cabinet at night. The focused app is the label of the record on the platter,
 * which turns at 33 1/3 rpm; the apps are sleeves standing in a crate, leaning towards the one pulled forward;
 * the amplifier below has the clock on its display and three knobs for Inputs, network and settings.
 *
 * The artwork comes from the theme's home/ folder (scene.jpg and the layers cut from it). Everything is drawn in
 * the scene's own 1672 x 941 coordinates and scaled to the screen. Three layers keep the work small: the scene
 * and the record are drawn once; only the little label view redraws while the record turns; the front layer
 * (arm, sleeves, display, knobs) redraws while something in it moves and then stops.
 */
final class TurntableHome extends FrameLayout implements StyledHome {
    static final float W = 1672f, H = 941f;
    private static final float CX = 440f, CY = 346f, SQUASH = 192f / 296f, LABEL_R = 124f;
    private static final float RPM_DEG = 360f * (100f / 3f) / 60f;
    private static final float ARM_UP = -27f, PIVOT_X = 758f, PIVOT_Y = 200f;
    private static final float SLEEVES_LEFT = 868f, SLEEVES_W = 776f, SLEEVES_BOTTOM = 532f;
    private static final float[] KNOB_X = {1215.5f, 1373.5f, 1532.5f};
    private static final float KNOB_Y = 778.5f, KNOB_R = 36f, LED_Y = 719f;
    private static final int MAX_VISIBLE = 9;
    private static final int AMBER = 0xFFFF9F35, VFD = 0xFFFFA040;
    /** Sleeve bitmaps are drawn at this many pixels per scene unit. */
    private static final float SLEEVE_PX = 1.25f;

    private final Host host;
    private final boolean calm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Bitmap scene, grooves, sheen, arm, spindle, paper;
    private final Bitmap[] knobs = new Bitmap[3];
    private final BackLayer back;
    private final LabelLayer label;
    private final FrontLayer front;

    private List<App> apps = new ArrayList<>();
    private final List<Bitmap> spines = new ArrayList<>();
    private Bitmap faceBitmap;
    private int faceFor = -1;

    /** Focus: row 0 the sleeves, row 1 the knobs. */
    private int row = 0, sleeve = 0, knob = 0;
    private int onRecord = -1;
    private boolean resumed;

    static TurntableHome create(Context context, File dir, Host host, boolean calm) {
        Bitmap scene = StyledHome.decode(dir, "scene.jpg", 1920, true);
        if (scene == null) return null;
        return new TurntableHome(context, dir, scene, host, calm);
    }

    private TurntableHome(Context context, File dir, Bitmap scene, Host host, boolean calm) {
        super(context);
        this.host = host;
        this.calm = calm;
        this.scene = scene;
        grooves = StyledHome.decode(dir, "grooves.webp", 0, false);
        sheen = StyledHome.decode(dir, "sheen.webp", 0, false);
        arm = StyledHome.decode(dir, "tonearm.webp", 0, false);
        spindle = StyledHome.decode(dir, "spindle.png", 0, false);
        paper = StyledHome.decode(dir, "paper.jpg", 400, false);
        for (int i = 0; i < 3; i++) knobs[i] = StyledHome.decode(dir, "knob" + i + ".webp", 0, false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);
        back = new BackLayer(context);
        label = new LabelLayer(context);
        front = new FrontLayer(context);
        addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(label, new LayoutParams(1, 1));
        addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override public View view() { return this; }

    /** Screen pixels per scene unit. */
    private float scale() { return getWidth() / W; }
    private float scaleY() { return getHeight() / H; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // The label layer covers only the label, so a turning record repaints that rectangle and nothing else.
        float sx = w / W, sy = h / H;
        LayoutParams lp = (LayoutParams) label.getLayoutParams();
        lp.width = Math.round(2 * LABEL_R * sx) + 2;
        lp.height = Math.round(2 * LABEL_R * SQUASH * sy) + 2;
        lp.leftMargin = Math.round((CX - LABEL_R) * sx) - 1;
        lp.topMargin = Math.round((CY - LABEL_R * SQUASH) * sy) - 1;
        label.setLayoutParams(lp);
    }

    // ---- apps ----------------------------------------------------------------------------------------

    @Override
    public void setApps(List<App> list) {
        try {
            String focused = sleeve < apps.size() ? apps.get(sleeve).label : null;
            apps = new ArrayList<>(list);
            for (Bitmap b : spines) if (b != null) b.recycle();
            spines.clear();
            for (int i = 0; i < apps.size(); i++) spines.add(null);
            if (faceBitmap != null) faceBitmap.recycle();
            faceBitmap = null;
            faceFor = -1;
            int keep = 0;
            if (focused != null) for (int i = 0; i < apps.size(); i++) if (apps.get(i).label.equals(focused)) keep = i;
            sleeve = Math.min(keep, Math.max(0, apps.size() - 1));
            onRecord = -1;
            front.layoutSleeves(true);
            play(sleeve, true);
            front.invalidate();
        } catch (RuntimeException e) {
            host.failed(e);
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    @Override
    public void resume() {
        resumed = true;
        if (!calm && armDown && onRecord >= 0) label.spinTo(RPM_DEG);
        front.tickClock();
        label.kick();
        front.kick();
    }

    @Override
    public void pause() {
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        label.stop();
        front.stopAnimations();
    }

    @Override
    public void home() {
        row = 0;
        focusSleeve(0);
    }

    @Override
    public void trim() {
        for (int i = 0; i < spines.size(); i++) {
            Bitmap b = spines.get(i);
            if (b != null) b.recycle();
            spines.set(i, null);
        }
        if (faceBitmap != null) faceBitmap.recycle();
        faceBitmap = null;
        faceFor = -1;
    }

    // ---- keys ------------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (row == 0) focusSleeve(sleeve - 1); else focusKnob(knob - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (row == 0) focusSleeve(sleeve + 1); else focusKnob(knob + 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (row == 0) { row = 1; focusKnob(knob); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row == 1) { row = 0; focusSleeve(sleeve); }
                    return true;
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

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            longPressed = true;
            host.settingsMenu();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private boolean longPressed;

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

    private void focusSleeve(int i) {
        if (apps.isEmpty()) return;
        row = 0;
        sleeve = Math.max(0, Math.min(apps.size() - 1, i));
        front.layoutSleeves(false);
        front.sweepFrom = SystemClock.uptimeMillis();
        play(sleeve, false);
        front.kick();
    }

    private void focusKnob(int k) {
        row = 1;
        knob = Math.max(0, Math.min(2, k));
        front.layoutSleeves(false);
        front.kick();
    }

    private void activate() {
        if (row == 0) {
            if (sleeve >= apps.size()) return;
            if (!calm) {
                label.spinTo(RPM_DEG * 3);
                handler.postDelayed(() -> label.spinTo(RPM_DEG), 900);
            }
            host.launch(sleeve);
            return;
        }
        front.pressKnob(knob);
        switch (knob) {
            case 0: front.message(getContext().getString(R.string.vfd_input), 0); host.inputs(); break;
            case 1: front.message(getContext().getString(R.string.vfd_network), 3); host.network(); break;
            default: front.message(getContext().getString(R.string.vfd_setup), 0); host.settings(); break;
        }
    }

    // ---- the record ------------------------------------------------------------------------------------

    private boolean armDown = true;

    /** Puts app i on the platter: arm up, label out, new label in, arm down, turning again. */
    private void play(int i, boolean now) {
        handler.removeCallbacksAndMessages(null);
        if (i >= apps.size()) return;
        if (calm || now) {
            onRecord = i;
            label.render(apps.get(i));
            armDown = true;
            front.armTarget = 0f;
            if (now) front.armAngle = 0f;
            if (!calm && resumed) label.spinTo(RPM_DEG);
            label.invalidate();
            front.kick();
            return;
        }
        if (i == onRecord && armDown) return;
        armDown = false;
        front.armTarget = ARM_UP;
        label.spinTo(0f);
        front.kick();
        handler.postDelayed(() -> {
            label.fadeOutThen(() -> {
                onRecord = i;
                label.render(apps.get(i));
            });
            handler.postDelayed(() -> {
                armDown = true;
                front.armTarget = 0f;
                front.kick();
                handler.postDelayed(() -> { if (resumed) label.spinTo(RPM_DEG); }, 550);
            }, 450);
        }, 380);
    }

    // ---- drawing helpers -------------------------------------------------------------------------------

    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private static void drawBitmap(Canvas c, Bitmap b, float l, float t, float r, float bottom, Paint p) {
        if (b == null) return;
        c.drawBitmap(b, null, new RectF(l, t, r, bottom), p);
    }

    /** A sleeve's front as a bitmap: the app's colour through worn card, its icon and its name. */
    private Bitmap renderSleeve(App app, float w, float h, float iconSize, float iconTop, float textSize, float gap) {
        int bw = Math.round(w * SLEEVE_PX), bh = Math.round(h * SLEEVE_PX);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(SLEEVE_PX, SLEEVE_PX);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(app.color);
        c.drawRoundRect(new RectF(0, 0, w, h + 6), 3, 3, p);
        if (paper != null) {
            Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
            over.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
            over.setAlpha(230);
            float side = Math.max(w, h);
            c.save();
            c.clipRect(0, 0, w, h);
            c.drawBitmap(paper, null, new RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2), over);
            c.restore();
        }
        Paint light = new Paint();
        light.setShader(new LinearGradient(0, 0, w, h, new int[] {0x38FFD6A0, 0x00FFD6A0, 0x00000000, 0x55000000},
                new float[] {0f, 0.4f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, light);
        if (app.icon != null) {
            Drawable icon = app.icon.getConstantState() != null ? app.icon.getConstantState().newDrawable().mutate() : app.icon;
            int l = Math.round((w - iconSize) / 2), t = Math.round(iconTop);
            icon.setBounds(l, t, l + Math.round(iconSize), t + Math.round(iconSize));
            icon.draw(c);
        }
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFF8EEDC);
        tp.setTextSize(textSize);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setShadowLayer(3, 0, 2, 0x59000000);
        String name = TextUtils.ellipsize(app.label, tp, w - 10, TextUtils.TruncateAt.END).toString();
        c.drawText(name, w / 2, iconTop + iconSize + gap + textSize * 0.8f, tp);
        return b;
    }

    private Bitmap spine(int i) {
        Bitmap b = spines.get(i);
        if (b == null) {
            b = renderSleeve(apps.get(i), 84, 296, 40, 86, 18, 16);
            spines.set(i, b);
        }
        return b;
    }

    private Bitmap face(int i) {
        if (faceFor != i || faceBitmap == null) {
            if (faceBitmap != null) faceBitmap.recycle();
            faceBitmap = renderSleeve(apps.get(i), 204, 326, 116, 88, 50, 22);
            faceFor = i;
        }
        return faceBitmap;
    }

    // ---- layer 1: scene and record, drawn once -----------------------------------------------------------

    private final class BackLayer extends View {
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint screen = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        BackLayer(Context c) {
            super(c);
            edge.setColor(0xFF050404);
            shadow.setColor(0xA6000000);
            screen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            screen.setAlpha(217);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            try {
                c.save();
                c.scale(getWidth() / W, getHeight() / H);
                c.drawBitmap(scene, null, new RectF(0, 0, W, H), FILTER);
                c.drawOval(new RectF(146, 167, 738, 553), shadow);
                c.drawOval(new RectF(144, 161, 736, 545), edge);
                drawBitmap(c, grooves, 144, 154, 736, 538, FILTER);
                drawBitmap(c, sheen, 144, 154, 736, 538, screen);
                c.restore();
            } catch (RuntimeException e) {
                host.failed(e);
            }
        }
    }

    // ---- layer 2: the label, the only thing redrawn while the record turns ---------------------------------

    private final class LabelLayer extends View {
        private Bitmap art;
        private float angle, speed, target;
        private long last;
        private float alpha = 1f, alphaTarget = 1f;
        private Runnable afterFade;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint screen = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF labelRect = new RectF(-LABEL_R, -LABEL_R, LABEL_R, LABEL_R);
        private final RectF sheenRect = new RectF(144, 154, 736, 538);
        private final RectF spindleRect = new RectF(428, 306, 452, 350);

        LabelLayer(Context c) {
            super(c);
            screen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            screen.setAlpha(217);
            setWillNotDraw(false);
        }

        void render(App app) {
            int size = 496;
            if (art == null) art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            art.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(art);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Path circle = new Path();
            circle.addCircle(248, 248, 244, Path.Direction.CW);
            c.save();
            c.clipPath(circle);
            p.setColor(app.color);
            c.drawRect(0, 0, size, size, p);
            if (paper != null) {
                Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
                over.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
                over.setAlpha(230);
                c.drawBitmap(paper, null, new RectF(0, 0, size, size), over);
            }
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4);
            ring.setColor(0x47000000);
            c.drawCircle(248, 248, 225, ring);
            if (app.icon != null) {
                Drawable icon = app.icon.getConstantState() != null ? app.icon.getConstantState().newDrawable().mutate() : app.icon;
                icon.setBounds(248 - 66, 248 - 168, 248 + 66, 248 - 36);
                icon.draw(c);
            }
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFF6EAD4);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            tp.setTextSize(64);
            c.drawText(TextUtils.ellipsize(app.label, tp, 360, TextUtils.TruncateAt.END).toString(), 248, 248 + 92, tp);
            tp.setTypeface(Typeface.DEFAULT);
            tp.setTextSize(22);
            tp.setColor(0xBFF6EAD4);
            tp.setLetterSpacing(0.08f);
            c.drawText("33⅓ RPM · SIDE A", 248, 248 + 140, tp);
            Paint light = new Paint();
            light.setShader(new LinearGradient(0, 0, size, size, new int[] {0x29FFBE78, 0x00000000, 0x59000000},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, size, size, light);
            c.restore();
            invalidate();
        }

        void spinTo(float degreesPerSecond) {
            target = calm ? 0 : degreesPerSecond;
            kick();
        }

        void fadeOutThen(Runnable then) {
            afterFade = then;
            alphaTarget = 0f;
            kick();
        }

        void kick() {
            last = 0;
            postInvalidateOnAnimation();
        }

        void stop() {
            speed = 0;
            target = 0;
            alpha = 1f;
            alphaTarget = 1f;
            afterFade = null;
        }

        @Override
        protected void onDraw(Canvas c) {
            try {
                drawLayer(c);
            } catch (RuntimeException e) {
                host.failed(e);
            }
        }

        private void drawLayer(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            speed += (target - speed) * Math.min(1f, dt * 2.5f);
            angle = (angle + speed * dt) % 360f;
            if (alphaTarget != alpha) {
                float step = dt / 0.2f;
                alpha = alphaTarget > alpha ? Math.min(alphaTarget, alpha + step) : Math.max(alphaTarget, alpha - step);
                if (alpha == 0f && afterFade != null) {
                    Runnable r = afterFade;
                    afterFade = null;
                    r.run();
                    alphaTarget = 1f;
                }
            }

            float sx = TurntableHome.this.getWidth() / W, sy = TurntableHome.this.getHeight() / H;
            c.save();
            // Back into scene coordinates: this view starts at the label's corner.
            c.translate(-getLeft(), -getTop());
            c.scale(sx, sy);
            if (clip.isEmpty()) {
                clip.addOval(new RectF(CX - LABEL_R, CY - LABEL_R * SQUASH, CX + LABEL_R, CY + LABEL_R * SQUASH), Path.Direction.CW);
            }
            c.clipPath(clip);
            if (art != null) {
                c.save();
                c.translate(CX, CY);
                c.scale(1f, SQUASH);
                c.rotate(angle);
                paint.setAlpha(Math.round(alpha * 255));
                c.drawBitmap(art, null, labelRect, paint);
                c.restore();
            }
            // The lamp's reflection stays where the lamp is, over the turning label.
            if (sheen != null) c.drawBitmap(sheen, null, sheenRect, screen);
            if (spindle != null) c.drawBitmap(spindle, null, spindleRect, FILTER);
            c.restore();

            boolean moving = speed > 0.5f || target > 0 || alpha != alphaTarget || afterFade != null;
            if (moving && resumed) postInvalidateDelayed(frameDelay(now));
        }
    }

    /** Frames at most 30 times a second: the record turns smoothly enough and a weak GPU keeps up. */
    private static long frameDelay(long frameStart) {
        long spent = SystemClock.uptimeMillis() - frameStart;
        return Math.max(1, 33 - spent);
    }

    // ---- layer 3: arm, sleeves, display, knobs -------------------------------------------------------------

    private final class FrontLayer extends View {
        float armAngle = 0f, armTarget = 0f;
        long sweepFrom = 0;
        private long last;
        // Per sleeve, the current and target placement: x, width, height, lean, lift.
        private float[] cx = new float[0], cw = new float[0], ch = new float[0], cr = new float[0], cl = new float[0];
        private float[] tx = new float[0], tw = new float[0], th = new float[0], tr = new float[0], tl = new float[0];
        private int first = 0, count = 0;
        private final float[] knobRot = new float[3], knobRotTarget = new float[3];
        private final float[] knobScale = {1, 1, 1};
        private final long[] rippleFrom = new long[3];
        private String message;
        private int messageBars;
        private long messageFrom;
        private Bitmap display;
        private int displayMinute = -1;
        private String displayFor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint armShadow = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final List<Integer> order = new ArrayList<>();
        private final Runnable minute = this::tickClock;

        FrontLayer(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            armShadow.setColorFilter(new PorterDuffColorFilter(0x8C000000, PorterDuff.Mode.SRC_IN));
            setWillNotDraw(false);
        }

        void kick() {
            last = 0;
            postInvalidateOnAnimation();
        }

        void stopAnimations() {
            removeCallbacks(minute);
            armAngle = armTarget;
            System.arraycopy(tx, 0, cx, 0, tx.length);
            System.arraycopy(tw, 0, cw, 0, tw.length);
            System.arraycopy(th, 0, ch, 0, th.length);
            System.arraycopy(tr, 0, cr, 0, tr.length);
            System.arraycopy(tl, 0, cl, 0, tl.length);
            message = null;
        }

        void tickClock() {
            removeCallbacks(minute);
            invalidate();
            if (resumed) {
                long now = System.currentTimeMillis();
                postDelayed(minute, 60_000 - now % 60_000 + 50);
            }
        }

        void message(String text, int bars) {
            message = text;
            messageBars = bars;
            messageFrom = SystemClock.uptimeMillis();
            displayFor = null;
            kick();
            postDelayed(() -> { message = null; displayFor = null; invalidate(); }, 2400);
        }

        void pressKnob(int k) {
            knobScale[k] = 0.88f;
            rippleFrom[k] = SystemClock.uptimeMillis();
            knobRotTarget[k] += k == 0 ? 40 : 30;
            kick();
        }

        /** Where every sleeve stands for the current focus; snap puts them there at once. */
        void layoutSleeves(boolean snap) {
            int n = apps.size();
            if (cx.length != n) {
                cx = new float[n]; cw = new float[n]; ch = new float[n]; cr = new float[n]; cl = new float[n];
                tx = new float[n]; tw = new float[n]; th = new float[n]; tr = new float[n]; tl = new float[n];
                snap = true;
            }
            if (n == 0) return;
            int f = sleeve;
            count = Math.min(n, MAX_VISIBLE);
            first = Math.max(0, Math.min(f - count / 2, n - count));
            boolean focused = row == 0;
            float wf = 204f, wn = Math.min(84f, count > 1 ? (SLEEVES_W - 10 - (focused ? 214 : 74)) / (count - 1) + 10 : 84f);
            float x = SLEEVES_LEFT + 10;
            for (int i = 0; i < n; i++) {
                boolean visible = i >= first && i < first + count;
                boolean isFocus = focused && i == f;
                float w = isFocus ? wf : wn;
                if (visible) {
                    x += isFocus ? 4 : -10;
                    tx[i] = x;
                    x += w + (isFocus ? 6 : 0);
                } else {
                    tx[i] = i < first ? SLEEVES_LEFT - 60 : SLEEVES_LEFT + SLEEVES_W + 60;
                }
                tw[i] = w;
                th[i] = isFocus ? 326f : 296f;
                tr[i] = isFocus ? 0f : (i < f || (!focused && i <= f) ? 26f : -26f);
                tl[i] = isFocus ? -30f : 0f;
                if (snap || calm) {
                    cx[i] = tx[i]; cw[i] = tw[i]; ch[i] = th[i]; cr[i] = tr[i]; cl[i] = tl[i];
                }
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            try {
                drawLayer(c);
            } catch (RuntimeException e) {
                host.failed(e);
            }
        }

        private void drawLayer(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = false;

            // Ease everything towards its target.
            float k = calm ? 1f : Math.min(1f, dt * 9f);
            for (int i = 0; i < cx.length; i++) {
                cx[i] += (tx[i] - cx[i]) * k; cw[i] += (tw[i] - cw[i]) * k; ch[i] += (th[i] - ch[i]) * k;
                cr[i] += (tr[i] - cr[i]) * k; cl[i] += (tl[i] - cl[i]) * k;
                if (Math.abs(tx[i] - cx[i]) > 0.3f || Math.abs(tw[i] - cw[i]) > 0.3f || Math.abs(tr[i] - cr[i]) > 0.2f) moving = true;
            }
            float ka = calm ? 1f : Math.min(1f, dt * 6f);
            armAngle += (armTarget - armAngle) * ka;
            if (Math.abs(armTarget - armAngle) > 0.05f) moving = true;
            for (int i = 0; i < 3; i++) {
                knobRot[i] += (knobRotTarget[i] - knobRot[i]) * (calm ? 1f : Math.min(1f, dt * 8f));
                float want = row == 1 && knob == i ? 1.06f : 1f;
                knobScale[i] += (want - knobScale[i]) * (calm ? 1f : Math.min(1f, dt * 10f));
                if (Math.abs(knobRotTarget[i] - knobRot[i]) > 0.1f || Math.abs(want - knobScale[i]) > 0.003f) moving = true;
                if (rippleFrom[i] != 0 && now - rippleFrom[i] < 700) moving = true;
            }
            if (message != null && now - messageFrom < 400) moving = true;
            if (!calm && row == 0 && now - sweepFrom < 1350) moving = true;

            c.save();
            c.scale(getWidth() / W, getHeight() / H);
            drawArm(c);
            drawSleeves(c, now);
            // The crate's front panel, cut from the scene, hides the sleeves' feet.
            Rect src = new Rect(Math.round(845 * scene.getWidth() / W), Math.round(497 * scene.getHeight() / H),
                    scene.getWidth(), Math.round(647 * scene.getHeight() / H));
            c.drawBitmap(scene, src, new RectF(845, 497, W, 647), FILTER);
            drawDisplay(c, now);
            drawKnobs(c, now);
            c.restore();

            if (moving && resumed) postInvalidateDelayed(frameDelay(now));
        }

        private void drawArm(Canvas c) {
            if (arm == null) return;
            c.save();
            c.rotate(armAngle, PIVOT_X, PIVOT_Y);
            c.drawBitmap(arm, null, new RectF(546, 199, 786, 499), armShadow);
            c.drawBitmap(arm, null, new RectF(540, 190, 780, 490), FILTER);
            c.restore();
        }

        private void drawSleeves(Canvas c, long now) {
            if (apps.isEmpty() || cx.length != apps.size()) return;
            int f = sleeve;
            // Outside in, so the ones nearer the focus overlap the ones further away; the focus last.
            order.clear();
            boolean focused = row == 0;
            for (int i = first; i < first + count && i < f; i++) order.add(i);
            // With the knobs focused nothing is pulled out: the last sleeve played leans with the ones on its left.
            if (!focused && f >= first && f < first + count) order.add(f);
            for (int i = Math.min(first + count, apps.size()) - 1; i > f; i--) order.add(i);
            if (focused) order.add(f);
            for (int i : order) {
                boolean isFocus = focused && i == f;
                Bitmap b = isFocus ? face(i) : spine(i);
                float w = cw[i], h = ch[i];
                float x = cx[i], bottom = SLEEVES_BOTTOM + cl[i];
                project(x + w / 2, bottom, w, h, cr[i], isFocus ? 70f : 0f, b.getWidth(), b.getHeight());
                c.save();
                c.concat(m);
                float bw = b.getWidth(), bh = b.getHeight();
                if (isFocus) {
                    // A soft shadow down and to the left, then a warm glow and a cream rim.
                    for (int s = 3; s >= 1; s--) {
                        shade.setColor(Color.argb(46, 0, 0, 0));
                        c.drawRoundRect(new RectF(-28 * SLEEVE_PX - s * 6, 40 * SLEEVE_PX - s * 6, bw - 22 * SLEEVE_PX + s * 6, bh + 34 * SLEEVE_PX + s * 6), 10, 10, shade);
                    }
                    for (int s = 4; s >= 1; s--) {
                        shade.setColor(Color.argb(18 + (4 - s) * 8, 255, 165, 80));
                        float g = s * 7 * SLEEVE_PX;
                        c.drawRoundRect(new RectF(-g, -g, bw + g, bh + g), 8 + g, 8 + g, shade);
                    }
                }
                paint.setColorFilter(null);
                paint.setAlpha(255);
                c.drawBitmap(b, 0, 0, paint);
                if (!isFocus) {
                    // The side turned away from the lamp is darker; the one next to the focus darker still.
                    boolean left = cr[i] > 0;
                    shade.setShader(new LinearGradient(left ? 0 : bw, 0, left ? bw : 0, 0,
                            new int[] {0x29FFCD96, 0x00000000, 0x80000000}, new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, bw, bh, shade);
                    shade.setShader(null);
                    boolean near = focused && Math.abs(i - f) == 1;
                    if (near || !focused) {
                        shade.setColor(near ? 0x4D000000 : 0x33000000);
                        c.drawRect(0, 0, bw, bh, shade);
                    }
                } else {
                    stroke.setColor(0xFFF3E3C6);
                    stroke.setStrokeWidth(2.5f * SLEEVE_PX);
                    c.drawRoundRect(new RectF(0, 0, bw, bh), 4, 4, stroke);
                    long t = now - sweepFrom;
                    if (!calm && t >= 250 && t < 1350) {
                        float p = (t - 250) / 1100f;
                        float e = 1f - (1f - p) * (1f - p);
                        float center = -0.3f * bw + e * 1.6f * bw;
                        shade.setShader(new LinearGradient(center - bw * 0.35f, 0, center + bw * 0.35f, bh * 0.3f,
                                new int[] {0x00FFFFFF, 0x61FFF4E2, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                        c.drawRect(0, 0, bw, bh, shade);
                        shade.setShader(null);
                    }
                }
                c.restore();
            }
        }

        /** Perspective of a sleeve turned by {@code lean} degrees about its bottom centre, as CSS rotateY would. */
        private void project(float cxBottom, float by, float w, float h, float lean, float lift, int bw, int bh) {
            double t = Math.toRadians(lean);
            float d = 900f;
            float[] dst = new float[8];
            float[][] corners = {{-w / 2, -h}, {w / 2, -h}, {w / 2, 0}, {-w / 2, 0}};
            for (int j = 0; j < 4; j++) {
                float x = corners[j][0], y = corners[j][1];
                float xr = (float) (x * Math.cos(t));
                float z = (float) (-x * Math.sin(t)) + lift;
                float f = d / (d - z);
                dst[j * 2] = cxBottom + xr * f;
                dst[j * 2 + 1] = by + y * f;
            }
            float[] src = {0, 0, bw, 0, bw, bh, 0, bh};
            m.reset();
            m.setPolyToPoly(src, 0, dst, 0, 4);
        }

        private void drawDisplay(Canvas c, long now) {
            Calendar cal = Calendar.getInstance();
            int minuteKey = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            String key = message != null ? "m" + message + messageBars : "t" + minuteKey;
            if (display == null || !key.equals(displayFor)) {
                renderDisplay(cal);
                displayFor = key;
                displayMinute = minuteKey;
            }
            paint.setColorFilter(null);
            float a = 1f;
            if (message != null && !calm) {
                float t = (now - messageFrom) / 380f;
                if (t < 1f) a = t < 0.2f ? t / 0.2f * 0.8f : t < 0.35f ? 0.2f : t < 0.55f ? 1f : t < 0.7f ? 0.6f : 1f;
            }
            paint.setAlpha(Math.round(a * 255));
            c.drawBitmap(display, null, new RectF(605, 692, 1075, 822), paint);
            paint.setAlpha(255);
        }

        /** The amplifier's display: seven-segment digits with their unlit ghosts, or a short message. */
        private void renderDisplay(Calendar cal) {
            float s = 1.5f;
            if (display == null) display = Bitmap.createBitmap(Math.round(470 * s), Math.round(130 * s), Bitmap.Config.ARGB_8888);
            display.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(display);
            c.scale(s, s);
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(VFD);
            glow.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL));
            Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
            lit.setColor(VFD);
            if (message != null) {
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setTypeface(Typeface.MONOSPACE);
                tp.setTextSize(58);
                tp.setLetterSpacing(0.1f);
                tp.setTextAlign(Paint.Align.CENTER);
                float x = messageBars > 0 ? 205 : 235;
                String text = TextUtils.ellipsize(message, tp, messageBars > 0 ? 330 : 430, TextUtils.TruncateAt.END).toString();
                for (Paint p : new Paint[] {glow, lit}) {
                    tp.setColor(VFD);
                    tp.setMaskFilter(p == glow ? glow.getMaskFilter() : null);
                    c.drawText(text, x, 86, tp);
                    for (int b = 0; b < messageBars; b++) {
                        c.drawRect(372 + b * 18, 92 - (b + 1) * 18, 384 + b * 18, 92, p);
                    }
                }
                return;
            }
            Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
            ghost.setColor(0x12FF8C28);
            boolean h24 = DateFormat.is24HourFormat(getContext());
            int h = cal.get(Calendar.HOUR_OF_DAY);
            int m = cal.get(Calendar.MINUTE);
            int shown = h24 ? h : (h % 12 == 0 ? 12 : h % 12);
            int[] digits = {shown >= 10 ? shown / 10 : -1, shown % 10, m / 10, m % 10};
            float[] xs = {135, 197, 274, 336};
            c.save();
            c.translate(8, 0);
            c.skew((float) Math.tan(Math.toRadians(-7)), 0);
            for (int d = 0; d < 4; d++) {
                Path[] seg = segments(xs[d], 28, 44, 74, 9);
                String on = digits[d] < 0 ? "" : SEGS[digits[d]];
                for (int i = 0; i < 7; i++) c.drawPath(seg[i], ghost);
                for (int i = 0; i < 7; i++) {
                    if (on.indexOf("abcdefg".charAt(i)) >= 0) {
                        c.drawPath(seg[i], glow);
                        c.drawPath(seg[i], lit);
                    }
                }
            }
            for (float cy : new float[] {49, 81}) {
                c.drawCircle(257, cy, 5, glow);
                c.drawCircle(257, cy, 5, lit);
            }
            c.restore();
            if (!h24) {
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(VFD);
                tp.setTextSize(26);
                tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                String ampm = h >= 12 ? "PM" : "AM";
                tp.setMaskFilter(glow.getMaskFilter());
                c.drawText(ampm, 398, 102, tp);
                tp.setMaskFilter(null);
                c.drawText(ampm, 398, 102, tp);
            }
        }

        private void drawKnobs(Canvas c, long now) {
            for (int i = 0; i < 3; i++) {
                float x = KNOB_X[i];
                boolean f = row == 1 && knob == i;
                if (knobs[i] != null) {
                    c.save();
                    c.translate(x, KNOB_Y);
                    c.rotate(knobRot[i]);
                    c.scale(knobScale[i], knobScale[i]);
                    c.drawBitmap(knobs[i], null, new RectF(-KNOB_R, -KNOB_R, KNOB_R, KNOB_R), FILTER);
                    // The pointer, so a turn shows.
                    shade.setColor(0xCCFFEED6);
                    c.drawRoundRect(new RectF(-2, -KNOB_R + 5, 2, -KNOB_R + 19), 2, 2, shade);
                    c.restore();
                }
                if (f) {
                    for (int g = 4; g >= 1; g--) {
                        stroke.setColor(Color.argb(30, 255, 150, 50));
                        stroke.setStrokeWidth(g * 5);
                        c.drawCircle(x, KNOB_Y, 52, stroke);
                    }
                    stroke.setColor(AMBER);
                    stroke.setStrokeWidth(3);
                    c.drawCircle(x, KNOB_Y, 52, stroke);
                    shade.setColor(0xF2FFBE5A);
                    c.drawCircle(x - 1.5f, LED_Y, 3.5f, shade);
                    shade.setColor(0x59FF8C28);
                    c.drawCircle(x - 1.5f, LED_Y, 10f, shade);
                }
                long t = now - rippleFrom[i];
                if (rippleFrom[i] != 0 && t < 700 && !calm) {
                    float p = t / 700f;
                    stroke.setColor(Color.argb(Math.round(190 * (1 - p)), 255, 165, 70));
                    stroke.setStrokeWidth(3);
                    c.drawCircle(x, KNOB_Y, 52 + 38 * p, stroke);
                }
            }
        }
    }

    private static final String[] SEGS = {"abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcfgd"};

    /** Segments a to g of one digit, in that order, as the prototype draws them. */
    private static Path[] segments(float x, float y, float w, float h, float t) {
        float m = h / 2;
        return new Path[] {
                horizontal(x, y, w, t), vertical(x + w - t / 2, y, m, t), vertical(x + w - t / 2, y + m, m, t),
                horizontal(x, y + h, w, t), vertical(x - t / 2, y + m, m, t), vertical(x - t / 2, y, m, t),
                horizontal(x, y + m, w, t)
        };
    }

    private static Path horizontal(float x, float y, float len, float t) {
        Path p = new Path();
        p.moveTo(x + t / 2, y);
        p.rLineTo(t / 2, -t / 2);
        p.rLineTo(len - 2 * t, 0);
        p.rLineTo(t / 2, t / 2);
        p.rLineTo(-t / 2, t / 2);
        p.rLineTo(-(len - 2 * t), 0);
        p.close();
        return p;
    }

    private static Path vertical(float x, float y, float len, float t) {
        Path p = new Path();
        p.moveTo(x, y + t / 2);
        p.rLineTo(t / 2, -t / 2);
        p.rLineTo(t / 2, t / 2);
        p.rLineTo(0, len - 2 * t);
        p.rLineTo(-t / 2, t / 2);
        p.rLineTo(-t / 2, -t / 2);
        p.close();
        return p;
    }
}
