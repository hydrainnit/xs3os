package com.hydrainnit.refract;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Static XMB wallpaper: the wave ribbons and scattered particles painted once, at the display's
 * own resolution (1080p or 4K), into a transparent bitmap. The gradient behind it is drawn live
 * by the view. Results are cached as PNG in files/xmbbg so a later start only decodes an image.
 * Runs off the main thread; nothing here animates.
 */
final class XmbBake {
    private static final int POINTS = 64;

    private XmbBake() {
    }

    /** Render size for this TV: the display size, capped for TVs with little memory. */
    static int[] size(Context c) {
        android.graphics.Point p = Ui.screen(c);
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        boolean roomy = am != null && !am.isLowRamDevice() && am.getMemoryClass() >= 192;
        int w = p.x, h = p.y;
        int cap = roomy && am.getMemoryClass() >= 256 ? 2560 : 1920;
        if (w > cap) {
            h = Math.round(h * (cap / (float) w));
            w = cap;
        }
        return new int[]{w, h};
    }

    static String key(int w, int h, int[] pal, int amp, int opacity, int layers, String dir, int particles, int density, int pSize, int pOpacity) {
        return Integer.toHexString(Arrays.hashCode(new int[]{w, h, pal[0], pal[1], pal[2], pal[3], amp, opacity, layers,
                "rtl".equals(dir) ? 1 : 0, particles, density, pSize, pOpacity}));
    }

    static Bitmap get(Context c, int w, int h, int[] pal, int amp, int opacity, int layers, String dir,
                      boolean particles, int density, int pSize, int pOpacity) {
        File dirF = new File(c.getFilesDir(), "xmbbg");
        dirF.mkdirs();
        File f = new File(dirF, key(w, h, pal, amp, opacity, layers, dir, particles ? 1 : 0, density, pSize, pOpacity) + ".png");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        Bitmap b = render(w, h, pal, amp / 100f, opacity / 100f, layers, "rtl".equals(dir), particles, density, pSize / 100f, pOpacity / 100f);
        File[] old = dirF.listFiles();
        if (old != null && old.length >= 4) {
            for (File o : old) o.delete();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
            f.delete();
        }
        return b;
    }

    static Bitmap render(int w, int h, int[] pal, float amp, float opacity, int layers, boolean flip,
                         boolean particles, int density, float pSize, float pOpacity) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (flip) {
            c.translate(w, 0);
            c.scale(-1, 1);
        }
        float u = w / 1920f;
        int tint = pal[3] & 0x00FFFFFF;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        Path ribbon = new Path(), edge = new Path(), edge2 = new Path();
        float[] top = new float[POINTS + 1], bot = new float[POINTS + 1];
        float a = (0.025f + amp * 0.08f) * h;
        for (int l = 0; l < layers; l++) {
            float baseY = h * (0.58f + (l - layers / 2f) * 0.06f);
            float lp = 1.3f + l * 2.1f;
            float thick = h * (0.07f + 0.04f * l);
            for (int i = 0; i <= POINTS; i++) {
                float x = i / (float) POINTS;
                float sweep = (0.5f - x) * h * 0.24f;
                float y = baseY + sweep + a * (0.62f * (float) Math.sin(x * 5.0f + lp) + 0.38f * (float) Math.sin(x * 8.7f - lp * 0.7f + l));
                float s = (float) Math.sin(x * 3.1f + lp * 0.6f + l * 1.2f);
                float t = thick * (0.30f + 0.70f * s * s);
                top[i] = y - t * 0.5f;
                bot[i] = y + t * 0.5f;
            }
            path(ribbon, top, bot, w, true);
            path(edge, top, bot, w, false);
            // soft body
            int a0 = Math.round(255 * opacity * (0.50f - l * 0.08f));
            fill.setShader(new LinearGradient(0, 0, w, 0, new int[]{tint, tint | (a0 / 2 << 24), tint | (a0 << 24), tint | (a0 / 3 << 24)},
                    new float[]{0f, 0.28f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(ribbon, fill);
            // glassy core: the middle third, brighter
            for (int i = 0; i <= POINTS; i++) {
                float mid = (top[i] + bot[i]) * 0.5f, hh = (bot[i] - top[i]) * 0.17f;
                top[i] = mid - hh;
                bot[i] = mid + hh;
            }
            path(ribbon, top, bot, w, true);
            int a1 = Math.min(255, Math.round(255 * opacity * (0.46f - l * 0.06f)));
            fill.setShader(new LinearGradient(0, 0, w, 0, new int[]{tint, tint | (a1 / 3 << 24), tint | (a1 << 24), tint | (a1 / 4 << 24)},
                    new float[]{0.05f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(ribbon, fill);
            // ridge line along the upper edge
            line.setStrokeWidth(Math.max(1.5f, 1.8f * u));
            line.setShader(new LinearGradient(0, 0, w, 0, new int[]{tint, tint | (Math.round(120 * opacity) << 24), tint | (Math.round(170 * opacity) << 24), tint},
                    new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(edge, line);
        }
        if (particles) {
            Random r = new Random(11);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            int n = 40 + Math.round(density / 100f * 260f);
            for (int i = 0; i < n; i++) {
                float x = r.nextFloat() * w;
                // half of them gather around the wave band
                float y = i % 2 == 0 ? r.nextFloat() * h : h * (0.42f + 0.34f * r.nextFloat());
                float z = 0.3f + 0.7f * r.nextFloat();
                float rad = u * (1.6f + pSize * 3.0f) * z * (r.nextFloat() < 0.04f ? 2.6f : 1f);
                int al = Math.min(255, Math.round(400 * pOpacity * z * (0.45f + 0.55f * r.nextFloat())));
                if (rad > 3.2f * u) {
                    dot.setShader(new RadialGradient(x, y, rad * 2.6f, new int[]{tint | (Math.min(255, al) << 24), tint | (al / 5 << 24), tint},
                            new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                    c.drawCircle(x, y, rad * 2.6f, dot);
                    dot.setShader(null);
                } else {
                    dot.setShader(null);
                    dot.setColor(tint | (Math.min(255, al) << 24));
                    c.drawCircle(x, y, Math.max(rad, 0.9f), dot);
                }
            }
        }
        return bmp;
    }

    private static void path(Path p, float[] top, float[] bot, int w, boolean closed) {
        p.reset();
        float step = w / (float) POINTS;
        p.moveTo(0, top[0]);
        for (int i = 1; i < POINTS; i++) {
            p.quadTo(i * step, top[i], i * step + step * 0.5f, (top[i] + top[i + 1]) * 0.5f);
        }
        p.lineTo(w, top[POINTS]);
        if (!closed) return;
        p.lineTo(w, bot[POINTS]);
        for (int i = POINTS - 1; i >= 1; i--) {
            p.quadTo(i * step, bot[i], i * step - step * 0.5f, (bot[i] + bot[i - 1]) * 0.5f);
        }
        p.lineTo(0, bot[0]);
        p.close();
    }
}
