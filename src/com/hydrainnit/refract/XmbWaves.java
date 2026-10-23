package com.hydrainnit.refract;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * The XMB background: a vertical gradient, flowing layered wave ribbons and reactive particles,
 * drawn with plain Canvas primitives (a handful of paths and circles per frame, no allocation).
 *
 * Cost control: 30 fps, paused while detached or hidden, one frame only with Reduce Motion.
 * Palette changes glide over about a second. Navigation calls react() to nudge the particles.
 */
final class XmbWaves extends View {
    private static final int POINTS = 28, MAX_LAYERS = 5, MAX_PARTICLES = 110;
    private static final long FRAME_MS = 50;

    private final float unit;
    private final boolean lowRam;
    private final Random rnd = new Random(7);

    // palette (current glides toward target)
    private final int[] cur = new int[4], tgt = new int[4], from = new int[4];
    private long fadeStart;
    private static final long FADE_MS = 450;
    private boolean fading;

    // wave settings
    private float amp = 0.5f, speed = 0.4f, opacity = 0.6f, dir = 1f;
    private int layers = 3;
    private boolean animate = true;
    private String bgMode = "waves";

    // particles
    private boolean pOn = true;
    private int pCount;
    private float pSpeed = 0.4f, pSize = 0.4f, pOpacity = 0.6f, pReact = 0.5f;
    private final float[] px = new float[MAX_PARTICLES], py = new float[MAX_PARTICLES],
            pz = new float[MAX_PARTICLES], pt = new float[MAX_PARTICLES];
    private float windX, windY, energy;

    private final Paint bg = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG), ridge = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), photo = new Paint(Paint.FILTER_BITMAP_FLAG), shade = new Paint();
    private final Path ribbon = new Path();
    // The body is painted on a worker thread with its own paints; the thin edge lines are built there too
    // and copied into the live paths on the main thread when a frame is swapped in, so they are drawn sharp at full size.
    private final Paint bgT = new Paint(), fillT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] topPend = new Path[MAX_LAYERS], botPend = new Path[MAX_LAYERS],
            topLive = new Path[MAX_LAYERS], botLive = new Path[MAX_LAYERS];
    private int edgeLayers, edgePend;
    private float edgeGain;
    private volatile boolean renderBusy;
    private static final java.util.concurrent.ExecutorService RENDERER = java.util.concurrent.Executors.newSingleThreadExecutor();
    {
        for (int i = 0; i < MAX_LAYERS; i++) {
            topPend[i] = new Path();
            botPend[i] = new Path();
            topLive[i] = new Path();
            botLive[i] = new Path();
        }
    }
    private final float[] topY = new float[POINTS + 1], botY = new float[POINTS + 1];
    private final Shader[] layerShader = new Shader[MAX_LAYERS];
    private Shader bgShader;
    private int sizeW, sizeH;

    private Bitmap wall;
    private float wallAlpha;

    private long holdUntil;
    private double phase;
    private long last;
    private boolean running;
    private boolean visibleToUser = true;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            long gap = now < holdUntil ? 70 : FRAME_MS;
            if (now - last >= gap - 4) {
                float dt = Math.min(0.1f, (now - last) / 1000f);
                last = now;
                step(dt, now);
                if (now >= holdUntil) scheduleRender();
                invalidate();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    XmbWaves(Context c) {
        super(c);
        unit = Ui.unit(c);
        lowRam = Xmb.lowRam(c);
        setWillNotDraw(false);
        ridge.setStyle(Paint.Style.STROKE);
        ridge.setStrokeWidth(Math.max(1f, unit * 1.4f));
        System.arraycopy(Xmb.palette(c), 0, cur, 0, 4);
        System.arraycopy(cur, 0, tgt, 0, 4);
        for (int i = 0; i < MAX_PARTICLES; i++) seed(i, true);
        reload();
    }

    /** Re-reads every wave and particle setting (called after a setting changes). */
    private Boolean dynOverride;

    /** Forces animated (or static) waves for this view whatever the home setting is: used by the animated screensaver. */
    void setDynamicOverride(boolean on) {
        dynOverride = on;
        reload();
    }

    void reload() {
        Context c = getContext();
        amp = Xmb.waveAmp(c) / 100f;
        speed = Xmb.waveSpeed(c) / 100f;
        opacity = Xmb.waveOpacity(c) / 100f;
        layers = Math.max(1, Math.min(MAX_LAYERS, Xmb.waveLayers(c)));
        dir = "rtl".equals(Xmb.waveDir(c)) ? -1f : 1f;
        bgMode = Xmb.bgMode(c);
        dyn = dynOverride != null ? dynOverride && !Xmb.reduceMotion(c) : Xmb.dynamic(c);
        animate = dyn;
        pOn = Xmb.particles(c) && dyn;
        pSpeed = Xmb.pSpeed(c) / 100f;
        pSize = Xmb.pSize(c) / 100f;
        pOpacity = Xmb.pOpacity(c) / 100f;
        pReact = Xmb.pReact(c) / 100f;
        int max = lowRam ? 36 : MAX_PARTICLES;
        pCount = pOn ? Math.min(max, 8 + Math.round(Xmb.pDensity(c) / 100f * (MAX_PARTICLES - 8))) : 0;
        setPalette(Xmb.palette(c), true);
        rebuildShaders();
        applyRunning();
        if (dyn) prepareWall();
        else {
            buf = null;
            wallSmall = null;
            requestBake();
        }
        startWallFade();
        invalidate();
    }

    /** Wallpaper beneath the waves (modes "blend" and "photo"); null to drop it. */
    void setWallpaper(Bitmap b) {
        wall = b;
        if (dyn) prepareWall();
        startWallFade();
    }

    private float overlayOnly = -1f;

    /** Draw only the baked waves (no gradient or photo) at this strength: used over screensaver photos. */
    void setOverlayOnly(float alpha) {
        overlayOnly = alpha;
    }

    private String override;

    /** A temporary background mode (Movie Theme, screensaver), or null for the saved one. */
    void setBgOverride(String mode) {
        override = mode;
        startWallFade();
    }

    String bgMode() {
        return override != null ? override : bgMode;
    }

    void setPalette(int[] p, boolean glide) {
        boolean same = true;
        for (int i = 0; i < 4; i++) if (tgt[i] != p[i]) same = false;
        if (same) return;
        if (!glide || sizeW == 0) {
            System.arraycopy(p, 0, cur, 0, 4);
            System.arraycopy(p, 0, tgt, 0, 4);
            fading = false;
            rebuildShaders();
        } else {
            System.arraycopy(cur, 0, from, 0, 4);
            System.arraycopy(p, 0, tgt, 0, 4);
            fadeStart = SystemClock.uptimeMillis();
            fading = true;
            if (!dyn) postStaticFade();
        }
        applyRunning();
        invalidate();
    }

    /** Navigation feedback: dx/dy in -1..1 (category / item moves). Particles lean into the move. */
    void react(float dx, float dy) {
        if (!pOn || pReact <= 0f) return;
        windX += dx * 260f * unit * pReact;
        windY += dy * 200f * unit * pReact;
        energy = Math.min(1f, energy + 0.5f * pReact);
    }

    /** D-pad input in progress: redraw at a slower rate so the menu stays instant. */
    void hold(long ms) {
        holdUntil = SystemClock.uptimeMillis() + ms;
    }

    void setActive(boolean on) {
        visibleToUser = on;
        applyRunning();
    }

    private void applyRunning() {
        boolean want = visibleToUser && isAttachedToWindow() && animate;
        if (want && !running) {
            running = true;
            last = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(frame);
        } else if (!want && running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(frame);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        applyRunning();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        sizeW = w;
        sizeH = h;
        buf = null;
        if (dyn) prepareWall();
        if (!dyn) requestBake();
        rebuildShaders();
    }

    private void seed(int i, boolean anywhere) {
        px[i] = rnd.nextFloat();
        py[i] = anywhere ? rnd.nextFloat() : (rnd.nextBoolean() ? 1.02f : -0.02f);
        pz[i] = 0.35f + rnd.nextFloat() * 0.65f;
        pt[i] = rnd.nextFloat() * 6.283f;
    }

    private void rebuildShaders() {
        if (sizeW == 0) return;
        bgShader = new LinearGradient(0, 0, 0, sizeH, new int[]{cur[0], cur[1], cur[2]}, new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
        int tint = cur[3] & 0x00FFFFFF;
        for (int l = 0; l < MAX_LAYERS; l++) {
            int a = Math.round(255 * (0.55f - l * 0.07f));
            layerShader[l] = new LinearGradient(0, 0, sizeW, 0,
                    new int[]{tint, tint | (a / 2 << 24), tint | (a << 24), tint | (a / 3 << 24)},
                    new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    private void step(float dt, long now) {
        if (fading) {
            float t = Math.min(1f, (now - fadeStart) / (float) FADE_MS);
            float e = t * t * (3 - 2 * t);
            for (int i = 0; i < 4; i++) cur[i] = Xmb.blend(from[i], tgt[i], e);
            rebuildShaders();
            if (t >= 1f) fading = false;
        }
        phase += dt * (0.25 + speed * 1.6) * dir;
        float damp = (float) Math.exp(-dt * 2.2f);
        windX *= damp;
        windY *= damp;
        energy *= (float) Math.exp(-dt * 1.6f);
        if (pCount > 0) {
            float spd = (0.012f + pSpeed * 0.05f) * (1f + energy * 1.5f);
            for (int i = 0; i < pCount; i++) {
                float z = pz[i];
                px[i] += (spd * 0.6f * z + windX * z / Math.max(1, sizeW)) * dt * dir * 0.8f;
                py[i] -= (spd * 0.4f * z) * dt - windY * z / Math.max(1, sizeH) * dt;
                pt[i] += dt * (0.8f + z);
                if (px[i] > 1.03f) px[i] = -0.03f;
                else if (px[i] < -0.03f) px[i] = 1.03f;
                if (py[i] < -0.04f) py[i] = 1.04f;
                else if (py[i] > 1.04f) py[i] = -0.04f;
            }
        }
    }

    /**
     * The gradient, wallpaper and ribbons are painted into a small bitmap (a third of the screen,
     * a quarter on low-RAM TVs) only when a frame is due, then drawn as one scaled texture. The
     * GPU never sees the big translucent paths, and a key press only redraws that texture.
     */
    // ---- static mode: baked overlay (waves + particles) over a live gradient
    private boolean dyn;
    private Bitmap overlay, oldOverlay;
    private float ovAlpha = 1f;
    private long ovFadeStart;
    private int bakeGen;
    private String bakedKey = "";
    private boolean sFadePosted;
    private final Paint ovPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final java.util.concurrent.ExecutorService BAKER = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void requestBake() {
        if (sizeW == 0) return;
        final Context c = getContext();
        final int[] sz = XmbBake.size(c);
        final int[] pal = Xmb.palette(c).clone();
        final int amp = Xmb.waveAmp(c), op = Xmb.waveOpacity(c), ly = Math.max(1, Math.min(MAX_LAYERS, Xmb.waveLayers(c)));
        final String dr = Xmb.waveDir(c);
        final boolean pt = Xmb.particles(c);
        final int dens = Xmb.pDensity(c), psz = Xmb.pSize(c), pop = Xmb.pOpacity(c);
        final String key = XmbBake.key(sz[0], sz[1], pal, amp, op, ly, dr, pt ? 1 : 0, dens, psz, pop);
        if (key.equals(bakedKey) && overlay != null) return;
        bakedKey = key;
        final int gen = ++bakeGen;
        BAKER.execute(() -> {
            Bitmap b;
            try {
                b = XmbBake.get(c, sz[0], sz[1], pal, amp, op, ly, dr, pt, dens, psz, pop);
            } catch (Throwable t) {
                android.util.Log.w("XS3OS", "wallpaper bake failed: " + t);
                return;
            }
            final Bitmap nb = b;
            post(() -> {
                if (gen != bakeGen || dyn) return;
                oldOverlay = overlay;
                overlay = nb;
                ovAlpha = oldOverlay == null ? 1f : 0f;
                ovFadeStart = SystemClock.uptimeMillis();
                if (oldOverlay != null) postStaticFade();
                invalidate();
            });
        });
    }

    private void postStaticFade() {
        if (sFadePosted) return;
        sFadePosted = true;
        Choreographer.getInstance().postFrameCallback(sFade);
    }

    private final Choreographer.FrameCallback sFade = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            sFadePosted = false;
            long now = SystemClock.uptimeMillis();
            boolean more = false;
            if (fading) {
                float t = Math.min(1f, (now - fadeStart) / (float) FADE_MS);
                float e = t * t * (3 - 2 * t);
                for (int i = 0; i < 4; i++) cur[i] = Xmb.blend(from[i], tgt[i], e);
                rebuildShaders();
                if (t >= 1f) fading = false;
                else more = true;
            }
            if (oldOverlay != null) {
                float t = Math.min(1f, (now - ovFadeStart) / (float) FADE_MS);
                ovAlpha = t;
                if (t >= 1f) oldOverlay = null;
                else more = true;
            }
            invalidate();
            if (more && isAttachedToWindow()) postStaticFade();
        }
    };

    private Bitmap flat;
    private int flatKey;
    private boolean flatPending, flatFailed;

    private int stateKey() {
        return System.identityHashCode(overlay) * 31 + java.util.Arrays.hashCode(cur);
    }

    private void flatten(final int key) {
        final Bitmap ov = overlay;
        final int c0 = cur[0], c1 = cur[1], c2 = cur[2];
        flatPending = true;
        BAKER.execute(() -> {
            Bitmap f = null;
            try {
                f = Bitmap.createBitmap(ov.getWidth(), ov.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas fc = new Canvas(f);
                Paint p = new Paint();
                p.setDither(true);
                p.setShader(new LinearGradient(0, 0, 0, f.getHeight(), new int[]{c0, c1, c2}, new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
                fc.drawRect(0, 0, f.getWidth(), f.getHeight(), p);
                fc.drawBitmap(ov, 0, 0, null);
            } catch (Throwable t) {
                f = null;
            }
            final Bitmap done = f;
            post(() -> {
                flatPending = false;
                if (done == null) {
                    flatFailed = true;
                } else if (stateKey() == key && overlay == ov) {
                    flat = done;
                    flatKey = key;
                    invalidate();
                }
            });
        });
    }

    private void drawStatic(Canvas cv) {
        int w = sizeW, h = sizeH;
        if (overlayOnly >= 0f) {
            dst.set(0, 0, w, h);
            if (overlay != null) {
                ovPaint.setAlpha(Math.round(255 * overlayOnly * ovAlpha));
                cv.drawBitmap(overlay, null, dst, ovPaint);
            }
            return;
        }
        Bitmap nt = target();
        boolean gradientOnly = !"waves".equals(bgMode()) && nt == null && wallPending;
        if (wallT < 1f) {
            float e = wallT * wallT * (3f - 2f * wallT);
            if (nt != null) {
                if (fadeFrom != null) drawPhoto(cv, fadeFrom, w, h, 1f);
                else drawBase(cv, w, h, fadeBaseGradient);
                drawPhoto(cv, nt, w, h, e);
            } else {
                drawBase(cv, w, h, false);
                if (fadeFrom != null) drawPhoto(cv, fadeFrom, w, h, 1f - e);
            }
        } else if (nt != null) {
            drawPhoto(cv, nt, w, h, 1f);
        } else {
            drawBase(cv, w, h, gradientOnly);
        }
        lastTarget = nt;
    }

    /** The gradient, plus the waves unless a wallpaper is on its way (then just the gradient, so no waves flash first). */
    private void drawBase(Canvas cv, int w, int h, boolean gradientOnly) {
        if (!gradientOnly && overlay != null && oldOverlay == null && ovAlpha >= 1f && !fading && !flatFailed) {
            // Settled plain waves: gradient and ribbons are flattened once into one opaque bitmap, so a frame is one full-screen draw.
            int key = stateKey();
            if (flat != null && flatKey == key) {
                dst.set(0, 0, w, h);
                ovPaint.setAlpha(255);
                cv.drawBitmap(flat, null, dst, ovPaint);
                return;
            }
            flat = null;
            if (!flatPending) flatten(key);
        }
        bg.setShader(bgShader);
        cv.drawRect(0, 0, w, h, bg);
        if (gradientOnly) return;
        dst.set(0, 0, w, h);
        if (oldOverlay != null) {
            ovPaint.setAlpha(255);
            cv.drawBitmap(oldOverlay, null, dst, ovPaint);
        }
        if (overlay != null) {
            ovPaint.setAlpha(Math.round(255 * ovAlpha));
            cv.drawBitmap(overlay, null, dst, ovPaint);
        }
    }

    /** A wallpaper replaces the waves entirely: the photo, with a light scrim for legibility. */
    private LightingColorFilter darkFilter, lightFilter;

    private void drawPhoto(Canvas cv, Bitmap b, int w, int h, float a) {
        photo.setAlpha(Math.round(255 * a));
        // The legibility scrim is a colour filter on the photo itself: one pass instead of a second full-screen rectangle.
        if (Theme.isLight(getContext())) {
            if (lightFilter == null) lightFilter = new LightingColorFilter(0xFFCCCCCC, 0x00333333);
            photo.setColorFilter(lightFilter);
        } else {
            if (darkFilter == null) darkFilter = new LightingColorFilter(0xFF999999, 0x00000000);
            photo.setColorFilter(darkFilter);
        }
        cv.save();
        float sc = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        cv.translate((w - b.getWidth() * sc) / 2f, (h - b.getHeight() * sc) / 2f);
        cv.scale(sc, sc);
        cv.drawBitmap(b, 0, 0, photo);
        cv.restore();
        photo.setColorFilter(null);
    }

    // ------------------------------------------------------------------ wallpaper cross-fade

    private static final long WALL_FADE_MS = 700;
    private Bitmap lastTarget, fadeFrom;
    private float wallT = 1f;
    private long wallStart;
    private boolean wallPending, fadeBaseGradient, wallFadePosted;

    private Bitmap target() {
        return wall != null && !"waves".equals(bgMode()) ? wall : null;
    }

    /** A wallpaper is expected but not loaded yet: show only the gradient (no waves) until it fades in. */
    void setWallPending(boolean pending) {
        wallPending = pending;
        invalidate();
    }

    /** What should be on screen may have changed: ease from what was last drawn to the new picture (or waves). */
    private void startWallFade() {
        Bitmap nt = target();
        if (nt == lastTarget || dyn) {
            invalidate();
            return;
        }
        fadeFrom = lastTarget;
        if (nt != null && wallPending) {
            fadeBaseGradient = true;
            wallPending = false;
        }
        if (Xmb.reduceMotion(getContext())) {
            wallT = 1f;
            fadeFrom = null;
            fadeBaseGradient = false;
            invalidate();
            return;
        }
        wallT = 0f;
        wallStart = SystemClock.uptimeMillis();
        if (!wallFadePosted) {
            wallFadePosted = true;
            Choreographer.getInstance().postFrameCallback(wallFade);
        }
        invalidate();
    }

    private final Choreographer.FrameCallback wallFade = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            wallFadePosted = false;
            float t = Math.min(1f, (SystemClock.uptimeMillis() - wallStart) / (float) WALL_FADE_MS);
            wallT = t;
            if (t >= 1f) {
                fadeFrom = null;
                fadeBaseGradient = false;
            }
            invalidate();
            if (t < 1f && isAttachedToWindow()) {
                wallFadePosted = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    private Bitmap buf, back;
    private Canvas bufCanvas, backCanvas;
    private float bufScale = 1f;
    private Bitmap wallSmall;

    private void ensureBuf() {
        if (buf != null || sizeW == 0) return;
        // Half resolution is crisp enough for soft ribbons; low-memory TVs keep the cheaper quarter size.
        bufScale = lowRam ? 4f : 2f;
        int bw = Math.max(1, Math.round(sizeW / bufScale)), bh = Math.max(1, Math.round(sizeH / bufScale));
        buf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        bufCanvas = new Canvas(buf);
        back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        backCanvas = new Canvas(back);
    }

    private void prepareWall() {
        wallSmall = null;
        ensureBuf();
        if (wall == null || buf == null) return;
        wallSmall = Bitmap.createBitmap(buf.getWidth(), buf.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(wallSmall);
        float s = Math.max(buf.getWidth() / (float) wall.getWidth(), buf.getHeight() / (float) wall.getHeight());
        c.translate((buf.getWidth() - wall.getWidth() * s) / 2f, (buf.getHeight() - wall.getHeight() * s) / 2f);
        c.scale(s, s);
        c.drawBitmap(wall, 0, 0, photo);
    }

    /** Paints the next frame on the worker thread, then swaps it in; skipped while the previous one is still being painted. */
    private void scheduleRender() {
        if (renderBusy) return;
        ensureBuf();
        if (back == null || bgShader == null) return;
        renderBusy = true;
        final Bitmap target = back;
        RENDERER.execute(() -> {
            boolean ok = false;
            try {
                paintFrame(backCanvas);
                ok = true;
            } catch (Throwable ignored) {
            }
            final boolean done = ok;
            post(() -> {
                if (done && back == target) swapFrame();
                renderBusy = false;
                invalidate();
            });
        });
    }

    private void swapFrame() {
        Bitmap tb = buf;
        buf = back;
        back = tb;
        Canvas tc = bufCanvas;
        bufCanvas = backCanvas;
        backCanvas = tc;
        edgeLayers = edgePend;
        for (int l = 0; l < edgeLayers; l++) {
            topLive[l].set(topPend[l]);
            botLive[l].set(botPend[l]);
        }
    }

    /** Synchronous render for the very first frame, before the worker has produced anything. */
    private void render() {
        ensureBuf();
        if (back == null || bgShader == null) return;
        paintFrame(backCanvas);
        swapFrame();
    }

    private void paintFrame(Canvas cv) {
        int w = sizeW, h = sizeH;
        cv.save();
        cv.scale(1f / bufScale, 1f / bufScale);
        bgT.setShader(bgShader);
        cv.drawRect(0, 0, w, h, bgT);
        float waveGain = opacity;
        String mode = bgMode();
        Bitmap ws = wallSmall;
        edgePend = 0;
        if (ws != null && !"waves".equals(mode)) {
            // A wallpaper replaces the waves entirely: the photo, with a light scrim for legibility.
            photo.setAlpha(255);
            cv.save();
            cv.scale(bufScale, bufScale);
            cv.drawBitmap(ws, 0, 0, photo);
            cv.restore();
            shade.setColor(Theme.isLight(getContext()) ? 0x33FFFFFF : 0x66000000);
            cv.drawRect(0, 0, w, h, shade);
        } else {
            drawWaves(cv, w, h, waveGain);
        }
        edgeGain = waveGain;
        cv.restore();
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (sizeW == 0) return;
        if (!dyn) {
            drawStatic(cv);
            return;
        }
        if (buf == null || !running) render();
        if (buf == null) return;
        dst.set(0, 0, sizeW, sizeH);
        cv.drawBitmap(buf, null, dst, photo);
        for (int l = 0; l < edgeLayers; l++) {
            // Crisp full-size edges over the soft body.
            ridge.setColor((cur[3] & 0x00FFFFFF) | (Math.round(120 * edgeGain * (1f - l * 0.15f)) << 24));
            cv.drawPath(topLive[l], ridge);
            ridge.setColor((cur[3] & 0x00FFFFFF) | (Math.round(60 * edgeGain * (1f - l * 0.15f)) << 24));
            cv.drawPath(botLive[l], ridge);
        }
        if (pCount > 0 && (wallSmall == null || "waves".equals(bgMode()))) drawParticles(cv, sizeW, sizeH);
    }

    private final android.graphics.RectF dst = new android.graphics.RectF();

    private void drawWaves(Canvas cv, int w, int h, float gain) {
        float ph = (float) phase;
        float a = (0.02f + amp * 0.085f) * h;
        for (int l = 0; l < layers; l++) {
            float baseY = h * (0.56f + (l - layers / 2f) * 0.055f);
            float lp = ph * (1f + l * 0.22f) + l * 1.9f;
            float thick = h * (0.05f + 0.035f * l);
            for (int i = 0; i <= POINTS; i++) {
                float x = i / (float) POINTS;
                float sweep = (0.5f - x) * h * 0.2f;
                float y = baseY + sweep
                        + a * (0.62f * (float) Math.sin(x * 5.2f + lp) + 0.38f * (float) Math.sin(x * 9.1f - lp * 0.7f + l));
                float t = thick * (0.45f + 0.55f * (float) Math.sin(x * 3.3f + lp * 0.5f + l * 1.3f) * (float) Math.sin(x * 3.3f + lp * 0.5f + l * 1.3f));
                topY[i] = y - t * 0.5f;
                botY[i] = y + t * 0.5f;
            }
            ribbon.reset();
            Path te = topPend[l], be = botPend[l];
            te.reset();
            be.reset();
            float stepX = w / (float) POINTS;
            ribbon.moveTo(0, topY[0]);
            te.moveTo(0, topY[0]);
            for (int i = 1; i < POINTS; i++) {
                float x = i * stepX, mx = x + stepX * 0.5f, my = (topY[i] + topY[i + 1]) * 0.5f;
                ribbon.quadTo(x, topY[i], mx, my);
                te.quadTo(x, topY[i], mx, my);
            }
            ribbon.lineTo(w, topY[POINTS]);
            te.lineTo(w, topY[POINTS]);
            ribbon.lineTo(w, botY[POINTS]);
            be.moveTo(w, botY[POINTS]);
            for (int i = POINTS - 1; i >= 1; i--) {
                float x = i * stepX, mx = x - stepX * 0.5f, my = (botY[i] + botY[i - 1]) * 0.5f;
                ribbon.quadTo(x, botY[i], mx, my);
                be.quadTo(x, botY[i], mx, my);
            }
            ribbon.lineTo(0, botY[0]);
            be.lineTo(0, botY[0]);
            ribbon.close();
            fillT.setShader(layerShader[l]);
            fillT.setAlpha(Math.min(255, Math.round(255 * gain * (1f - l * 0.12f))));
            cv.drawPath(ribbon, fillT);
            edgePend = l + 1;
        }
    }

    private void drawParticles(Canvas cv, int w, int h) {
        int rgb = cur[3] & 0x00FFFFFF;
        float base = unit * (1.1f + pSize * 2.6f);
        for (int i = 0; i < pCount; i++) {
            float z = pz[i];
            float tw = 0.65f + 0.35f * (float) Math.sin(pt[i]);
            int a = Math.round(255 * pOpacity * z * tw * (0.7f + energy * 0.5f));
            dot.setColor(rgb | (Math.min(255, a) << 24));
            cv.drawCircle(px[i] * w, py[i] * h, base * z * (1f + energy * 0.35f), dot);
        }
    }
}
