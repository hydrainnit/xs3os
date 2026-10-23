package com.hydrainnit.refract;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/**
 * The first-run coldboot: black, then the waves rise, the AS3 mark fades in and the supplied
 * coldboot sound plays once. Any key skips it (and stops the sound).
 *
 * The "played" flag is written only when the audio has actually started, never before, and only
 * when the sound is enabled and the TV's volume is up; so a muted or failed start does not use up
 * the one-time coldboot.
 */
final class XmbIntro extends View {
    private final float u;
    private final Runnable done;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long start;
    private boolean audio, finished, running;
    private long length = 7500;
    private static boolean started;  // guards against a second intro in the same process

    XmbIntro(Context c, Runnable done) {
        super(c);
        this.done = done;
        u = Ui.unit(c);
        paint.setTypeface(Xmb.font(c));
        setWillNotDraw(false);
    }

    void begin() {
        start = SystemClock.uptimeMillis();
        Context c = getContext();
        if (!started && Xmb.coldbootEnabled(c) && !Xmb.coldbootPlayed(c) && XmbSounds.enabled(c)) {
            started = true;
            audio = XmbSounds.playLong(c, "coldboot_stereo.m4a", Math.max(0.2f, XmbSounds.volume(c)), () -> {
                Xmb.put(getContext(), "coldboot_played", true);
                android.util.Log.i("XS3OS", "coldboot audio started; one-time flag stored");
                post(() -> start = SystemClock.uptimeMillis());
                length = 10000;
            }, () -> post(this::finish));
            if (!audio) started = false;
        }
        android.util.Log.i("AS3", "intro: audio requested=" + audio + " played-before=" + Xmb.coldbootPlayed(c));
        running = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    /** Skip: silence the sound and move on at once. */
    void skip() {
        XmbSounds.stopLong();
        finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        running = false;
        done.run();
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) return;
            invalidate();
            if (SystemClock.uptimeMillis() - start > length) {
                finish();
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    private static float clamp(float t) {
        return Math.max(0f, Math.min(1f, t));
    }

    private android.graphics.Bitmap logo;
    private android.text.StaticLayout warning;
    private int warningW;

    private static final String WARNING = "PHOTOSENSITIVE EPILEPSY\nIF YOU HAVE A HISTORY OF EPILEPSY OR SEIZURES, CONSULT A DOCTOR BEFORE USE. "
            + "CERTAIN PATTERNS MAY TRIGGER SEIZURES WITH NO PRIOR HISTORY. BEFORE USING THIS PRODUCT, "
            + "CAREFULLY READ YOUR TV'S INSTRUCTION MANUAL.";

    private android.graphics.Bitmap scene;

    /** The boot backdrop, painted once: dark olive gradient, soft vignette and one smoky white ribbon. */
    private void buildScene(int w, int h) {
        scene = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(scene);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, new int[]{0xFF1B2310, 0xFF2D3218, 0xFF4B4527}, new float[]{0f, 0.55f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new android.graphics.RadialGradient(w * 0.5f, h * 0.5f, w * 0.75f, new int[]{0x00000000, 0x00000000, 0x66000000},
                new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        android.graphics.Path body = new android.graphics.Path(), edge = new android.graphics.Path();
        int n = 48;
        for (int layer = 0; layer < 3; layer++) {
            float[] top = new float[n + 1], bot = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float x = i / (float) n;
                float mid = h * (0.50f + 0.02f * layer) - h * 0.025f * (float) Math.sin(x * 6.0 + layer) + h * 0.17f * x * x * x + h * 0.02f * (float) Math.sin(x * 11 + layer * 2);
                float t = h * (0.035f + 0.018f * layer) * (0.6f + 0.4f * (float) Math.sin(x * 4.2 + layer * 1.7));
                top[i] = mid - t;
                bot[i] = mid + t;
            }
            body.reset();
            edge.reset();
            body.moveTo(0, top[0]);
            edge.moveTo(0, top[0]);
            for (int i = 1; i <= n; i++) {
                body.lineTo(i * w / (float) n, top[i]);
                edge.lineTo(i * w / (float) n, top[i]);
            }
            for (int i = n; i >= 0; i--) body.lineTo(i * w / (float) n, bot[i]);
            body.close();
            p.setStyle(Paint.Style.FILL);
            int a1 = 70 - layer * 14;
            p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, new int[]{0x00FFFFFF, (a1 << 24) | 0xEDEBD8, (a1 << 24) | 0xFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.35f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(body, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, u * 1.2f));
            p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, new int[]{0x00FFFFFF, 0x66FFFFFF, 0xAAFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.35f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(edge, p);
        }
    }

    /**
     * Two scenes, laid out like a PlayStation 3 boot: the logo sits right of centre on the smoky
     * ribbon (the coldboot sound plays here), then the photosensitivity warning on a dark screen.
     */
    @Override
    protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float total = length / 1000f;
        float split = Math.max(2.8f, total - 4.2f);
        int w = getWidth(), h = getHeight();
        if (scene == null || scene.getWidth() != w) buildScene(w, h);
        c.drawBitmap(scene, 0, 0, null);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        if (t < split) {
            float veil = 1f - clamp((t - 0.3f) / 2.0f);
            paint.setColor(((int) (255 * veil * veil) << 24));
            c.drawRect(0, 0, w, h, paint);
            float a = clamp((t - 0.9f) / 1.5f) * (1f - clamp((t - (split - 0.8f)) / 0.7f));
            if (logo == null) logo = android.graphics.BitmapFactory.decodeResource(getResources(), getResources().getIdentifier("xs3os_logo", "drawable", getContext().getPackageName()));
            if (logo != null) {
                float lw = w * 0.30f, lh = lw * logo.getHeight() / logo.getWidth();
                float right = w * 0.91f, cy = h * 0.515f;
                rectF.set(right - lw, cy - lh / 2f, right, cy + lh / 2f);
                paint.setAlpha(Math.round(255 * a));
                c.drawBitmap(logo, null, rectF, paint);
            }
        } else {
            float k = t - split;
            paint.setColor(((int) (255 * Math.min(0.9f, clamp(k / 0.5f))) << 24));
            c.drawRect(0, 0, w, h, paint);
            int tw = Math.round(w * 0.68f);
            if (warning == null || warningW != tw) {
                android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setTypeface(Xmb.font(getContext()));
                tp.setTextSize(15 * u);
                tp.setColor(0xFFFFFFFF);
                warning = android.text.StaticLayout.Builder.obtain(WARNING, 0, WARNING.length(), tp, tw)
                        .setLineSpacing(4 * u, 1f).build();
                warningW = tw;
            }
            float a = clamp((k - 0.3f) / 0.6f) * (1f - clamp((t - (total - 0.5f)) / 0.4f));
            warning.getPaint().setAlpha(Math.round(255 * a));
            c.save();
            c.translate(w * 0.157f, h * 0.45f);
            warning.draw(c);
            c.restore();
        }
    }

    private final android.graphics.RectF rectF = new android.graphics.RectF();
}
