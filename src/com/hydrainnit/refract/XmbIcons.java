package com.hydrainnit.refract;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * XMB-style glyphs drawn with Canvas paths (original shapes, in the spirit of the reference
 * menus; no proprietary artwork). Every glyph fits a square of side s centred on (cx, cy) and is
 * drawn in the paint's colour, so the caller controls tint and alpha.
 */
final class XmbIcons {
    static final int USER = 0, TOOLBOX = 1, CAMERA = 2, NOTE = 3, FILM = 4, PAD = 5, GLOBE = 6, POWER = 7,
            GEAR = 8, SLIDERS = 9, SPEAKER = 10, DOTS = 11, KEY = 12, CLOCK = 13, CLOUD = 14, FOLDER = 15,
            WAVES = 16, SUN = 17, SEARCH = 18, MIC = 19, GRID = 20, INFO = 21, PLAY = 22, REFRESH = 23,
            SCREEN = 24, SHIELD = 25, WIFI = 26, MEMORY = 27, TV = 28, STAR = 29, SETUP = 30, PLUS = 31,
            CLOCK_PLAIN = 32;

    // Artwork from the Figma "XMB Icons" set, packed by tools/icons/pack_icons.py into assets/xmb_icons.txt.
    // Icons without a Figma counterpart keep the hand-drawn glyphs below.
    private static final String[] ART = new String[32];
    static {
        ART[USER] = "user";
        ART[TOOLBOX] = "Layer 32";
        ART[CAMERA] = "Layer 40";
        ART[NOTE] = "music";
        ART[FILM] = "Layer 39";
        ART[PAD] = "game";
        ART[GLOBE] = "web";
        ART[POWER] = "power";
        ART[SPEAKER] = "Layer 8";
        ART[KEY] = "Layer 9";
        ART[CLOCK] = "Layer 28";
        ART[FOLDER] = "folder";
        ART[SEARCH] = "Layer 36";
        ART[GRID] = "suste, actovatopm";
        ART[MIC] = "headset";
        ART[REFRESH] = "Layer 19 copy";
        ART[SCREEN] = "Layer 5";
        ART[TV] = "Layer 5";
        ART[STAR] = "trophy";
        ART[PLUS] = "add user";
    }
    private static java.util.HashMap<String, String[]> raw;
    private static final Path[] glyph = new Path[32];
    private static final float[] gw = new float[32], gh = new float[32];
    /** Each artwork icon is drawn once into a small alpha mask; a frame then costs one textured quad, not a path fill. */
    private static final Bitmap[] mask = new Bitmap[32];
    private static final int MASK = 192;

    private static final Path P = new Path();
    private static final RectF R = new RectF();

    private XmbIcons() {
    }

    /** Loads the packed artwork (about 100 KB). Safe to call repeatedly; the paths are built on first use. */
    static synchronized void init(Context ctx) {
        if (raw != null) return;
        java.util.HashMap<String, String[]> m = new java.util.HashMap<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                ctx.getAssets().open("xmb_icons.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", 4);
                if (f.length == 4) m.put(f[0], f);
            }
        } catch (Exception ignored) {
        }
        raw = m;
        new Thread(() -> {
            for (int i = 0; i < ART.length; i++) if (ART[i] != null) mask(i);
        }, "as3-icons").start();
    }

    private static Path art(int icon) {
        if (icon < 0 || icon >= ART.length || ART[icon] == null || raw == null) return null;
        Path g = glyph[icon];
        if (g != null) return g;
        String[] f = raw.get(ART[icon]);
        if (f == null) return null;
        g = parse(f[3]);
        gw[icon] = Float.parseFloat(f[1]);
        gh[icon] = Float.parseFloat(f[2]);
        glyph[icon] = g;
        return g;
    }

    /** SVG path data limited to absolute M L H V C Z, which is all the exported set uses. */
    private static Path parse(String d) {
        Path path = new Path();
        float[] v = new float[6];
        float x = 0, y = 0;
        int i = 0, n = d.length();
        char cmd = 'M';
        while (i < n) {
            char ch = d.charAt(i);
            if (ch == ' ' || ch == ',') {
                i++;
                continue;
            }
            if (Character.isLetter(ch) && ch != 'e' && ch != 'E') {
                cmd = ch;
                i++;
                if (cmd == 'Z') path.close();
                continue;
            }
            int need = cmd == 'C' ? 6 : cmd == 'H' || cmd == 'V' ? 1 : 2;
            for (int k = 0; k < need; k++) {
                while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
                int st = i;
                if (i < n && d.charAt(i) == '-') i++;
                while (i < n && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.'
                        || ((d.charAt(i) == 'e' || d.charAt(i) == 'E') && i + 1 < n))) {
                    if ((d.charAt(i) == 'e' || d.charAt(i) == 'E') && i + 1 < n && d.charAt(i + 1) == '-') i++;
                    i++;
                }
                v[k] = Float.parseFloat(d.substring(st, i));
            }
            switch (cmd) {
                case 'M': path.moveTo(v[0], v[1]); x = v[0]; y = v[1]; cmd = 'L'; break;
                case 'L': path.lineTo(v[0], v[1]); x = v[0]; y = v[1]; break;
                case 'H': x = v[0]; path.lineTo(x, y); break;
                case 'V': y = v[0]; path.lineTo(x, y); break;
                case 'C': path.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5]); x = v[4]; y = v[5]; break;
                default: break;
            }
        }
        return path;
    }

    private static Bitmap mask(int icon) {
        Bitmap b = mask[icon];
        if (b != null) return b;
        Path g = art(icon);
        if (g == null) return null;
        b = Bitmap.createBitmap(MASK, MASK, Bitmap.Config.ALPHA_8);
        Canvas bc = new Canvas(b);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.FILL);
        bp.setColor(0xFFFFFFFF);
        float k = 0.94f * MASK / Math.max(gw[icon], gh[icon]);
        bc.translate((MASK - gw[icon] * k) / 2f, (MASK - gh[icon] * k) / 2f);
        bc.scale(k, k);
        bc.drawPath(g, bp);
        mask[icon] = b;
        return b;
    }

    static void draw(Canvas c, int icon, float cx, float cy, float s, Paint p) {
        if (icon >= 0 && icon < ART.length && ART[icon] != null) {
            Bitmap m = mask(icon);
            if (m != null) {
                boolean f = p.isFilterBitmap();
                p.setFilterBitmap(true);
                R.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f);
                c.drawBitmap(m, null, R, p);
                p.setFilterBitmap(f);
                return;
            }
        }
        Paint.Style st = p.getStyle();
        float sw = p.getStrokeWidth();
        float u = s / 100f;
        c.save();
        c.translate(cx - s / 2f, cy - s / 2f);
        c.scale(u, u);
        p.setStrokeWidth(sw / u);
        Path g = art(icon);
        if (g != null) {
            float k = 94f / Math.max(gw[icon], gh[icon]);
            fill(p);
            c.translate(50 - gw[icon] * k / 2f, 50 - gh[icon] * k / 2f);
            c.scale(k, k);
            c.drawPath(g, p);
            c.restore();
            p.setStyle(st);
            p.setStrokeWidth(sw);
            return;
        }
        switch (icon) {
            case USER: user(c, p); break;
            case TOOLBOX: toolbox(c, p); break;
            case CAMERA: camera(c, p); break;
            case NOTE: note(c, p); break;
            case FILM: film(c, p); break;
            case PAD: pad(c, p); break;
            case GLOBE: globe(c, p); break;
            case POWER: power(c, p); break;
            case GEAR: gear(c, p); break;
            case SLIDERS: sliders(c, p); break;
            case SPEAKER: speaker(c, p); break;
            case DOTS: dots(c, p); break;
            case KEY: key(c, p); break;
            case CLOCK: clock(c, p); break;
            case CLOUD: cloud(c, p); break;
            case FOLDER: folder(c, p); break;
            case WAVES: waves(c, p); break;
            case SUN: sun(c, p); break;
            case SEARCH: search(c, p); break;
            case MIC: mic(c, p); break;
            case GRID: grid(c, p); break;
            case INFO: info(c, p); break;
            case PLAY: play(c, p); break;
            case REFRESH: refresh(c, p); break;
            case SCREEN: screen(c, p); break;
            case SHIELD: shield(c, p); break;
            case WIFI: wifi(c, p); break;
            case MEMORY: memory(c, p); break;
            case TV: tv(c, p); break;
            case STAR: star(c, p); break;
            case SETUP: setup(c, p); break;
            case PLUS: plus(c, p); break;
            case CLOCK_PLAIN: clock(c, p); break;
            default: p.setStyle(Paint.Style.FILL); c.drawCircle(50, 50, 16, p); break;
        }
        c.restore();
        p.setStyle(st);
        p.setStrokeWidth(sw);
    }

    private static void fill(Paint p) {
        p.setStyle(Paint.Style.FILL);
    }

    private static void line(Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(8);
    }

    private static void user(Canvas c, Paint p) {
        fill(p);
        c.drawCircle(50, 34, 17, p);
        R.set(20, 56, 80, 92);
        c.drawRoundRect(R, 18, 18, p);
    }

    private static void toolbox(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.setFillType(Path.FillType.EVEN_ODD);
        R.set(8, 34, 92, 84);
        P.addRoundRect(R, 9, 9, Path.Direction.CW);
        R.set(8, 54, 92, 58);
        P.addRect(R, Path.Direction.CW);
        c.drawPath(P, p);
        P.setFillType(Path.FillType.WINDING);
        line(p);
        p.setStrokeWidth(9);
        P.reset();
        P.moveTo(34, 32);
        P.lineTo(34, 20);
        P.lineTo(66, 20);
        P.lineTo(66, 32);
        c.drawPath(P, p);
        fill(p);
        R.set(42, 50, 58, 64);
        c.drawRoundRect(R, 3, 3, p);
    }

    private static void camera(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.setFillType(Path.FillType.EVEN_ODD);
        R.set(8, 28, 92, 84);
        P.addRoundRect(R, 11, 11, Path.Direction.CW);
        P.addCircle(50, 57, 21, Path.Direction.CW);
        c.drawPath(P, p);
        P.setFillType(Path.FillType.WINDING);
        c.drawCircle(50, 57, 13, p);
        R.set(30, 16, 54, 32);
        c.drawRoundRect(R, 4, 4, p);
    }

    private static void note(Canvas c, Paint p) {
        fill(p);
        R.set(18, 60, 46, 84);
        c.drawOval(R, p);
        R.set(58, 50, 86, 74);
        c.drawOval(R, p);
        c.drawRect(40, 16, 48, 70, p);
        c.drawRect(80, 6, 88, 60, p);
        P.reset();
        P.moveTo(40, 14);
        P.lineTo(88, 4);
        P.lineTo(88, 22);
        P.lineTo(40, 32);
        P.close();
        c.drawPath(P, p);
    }

    private static void film(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(7);
        R.set(10, 18, 90, 82);
        c.drawRoundRect(R, 8, 8, p);
        c.drawLine(28, 18, 28, 82, p);
        c.drawLine(72, 18, 72, 82, p);
        fill(p);
        for (int i = 0; i < 4; i++) {
            c.drawRect(14.5f, 25 + i * 14, 23, 32 + i * 14, p);
            c.drawRect(77, 25 + i * 14, 85.5f, 32 + i * 14, p);
        }
    }

    private static void pad(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.setFillType(Path.FillType.EVEN_ODD);
        R.set(6, 26, 94, 76);
        P.addRoundRect(R, 25, 25, Path.Direction.CW);
        P.moveTo(26, 40);
        P.lineTo(32, 40);
        P.lineTo(32, 46);
        P.lineTo(38, 46);
        P.lineTo(38, 52);
        P.lineTo(32, 52);
        P.lineTo(32, 58);
        P.lineTo(26, 58);
        P.lineTo(26, 52);
        P.lineTo(20, 52);
        P.lineTo(20, 46);
        P.lineTo(26, 46);
        P.close();
        P.addCircle(66, 43, 5, Path.Direction.CW);
        P.addCircle(77, 54, 5, Path.Direction.CW);
        c.drawPath(P, p);
        P.setFillType(Path.FillType.WINDING);
    }

    private static void globe(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(7);
        c.drawCircle(50, 50, 40, p);
        R.set(28, 10, 72, 90);
        c.drawOval(R, p);
        c.drawLine(10, 50, 90, 50, p);
        c.drawLine(16, 28, 84, 28, p);
        c.drawLine(16, 72, 84, 72, p);
    }

    private static void power(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(10);
        R.set(16, 16, 84, 84);
        c.drawArc(R, -60, 300, false, p);
        c.drawLine(50, 10, 50, 50, p);
    }

    private static void gear(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.setFillType(Path.FillType.EVEN_ODD);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 50, 50);
            c.drawRoundRect(41, 6, 59, 28, 4, 4, p);
            c.restore();
        }
        P.addCircle(50, 50, 33, Path.Direction.CW);
        P.addCircle(50, 50, 14, Path.Direction.CW);
        c.drawPath(P, p);
        P.setFillType(Path.FillType.WINDING);
    }

    private static void sliders(Canvas c, Paint p) {
        line(p);
        for (int i = 0; i < 3; i++) c.drawLine(14, 24 + i * 26, 86, 24 + i * 26, p);
        fill(p);
        c.drawCircle(34, 24, 9, p);
        c.drawCircle(66, 50, 9, p);
        c.drawCircle(44, 76, 9, p);
    }

    private static void speaker(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.moveTo(12, 38);
        P.lineTo(32, 38);
        P.lineTo(54, 18);
        P.lineTo(54, 82);
        P.lineTo(32, 62);
        P.lineTo(12, 62);
        P.close();
        c.drawPath(P, p);
        line(p);
        R.set(46, 32, 70, 68);
        c.drawArc(R, -55, 110, false, p);
        R.set(40, 18, 84, 82);
        c.drawArc(R, -55, 110, false, p);
    }

    private static void dots(Canvas c, Paint p) {
        fill(p);
        float[][] d = {{30, 34, 5}, {62, 24, 7}, {76, 52, 4}, {44, 60, 8}, {22, 74, 5}, {70, 78, 6}};
        for (float[] q : d) c.drawCircle(q[0], q[1], q[2], p);
    }

    private static void key(Canvas c, Paint p) {
        line(p);
        c.drawCircle(30, 50, 18, p);
        c.drawLine(48, 50, 90, 50, p);
        c.drawLine(76, 50, 76, 64, p);
        c.drawLine(86, 50, 86, 62, p);
    }

    private static void clock(Canvas c, Paint p) {
        line(p);
        c.drawCircle(50, 50, 38, p);
        c.drawLine(50, 50, 50, 26, p);
        c.drawLine(50, 50, 68, 58, p);
    }

    private static void cloud(Canvas c, Paint p) {
        fill(p);
        c.drawCircle(36, 56, 20, p);
        c.drawCircle(58, 46, 24, p);
        c.drawCircle(76, 62, 16, p);
        c.drawRect(36, 56, 76, 78, p);
    }

    private static void folder(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.moveTo(10, 24);
        P.lineTo(40, 24);
        P.lineTo(48, 34);
        P.lineTo(90, 34);
        P.lineTo(90, 80);
        P.lineTo(10, 80);
        P.close();
        c.drawPath(P, p);
    }

    private static void waves(Canvas c, Paint p) {
        line(p);
        for (int i = 0; i < 3; i++) {
            P.reset();
            float y = 30 + i * 22;
            P.moveTo(8, y);
            P.cubicTo(28, y - 22, 40, y + 22, 54, y);
            P.cubicTo(66, y - 18, 78, y + 12, 92, y - 6);
            c.drawPath(P, p);
        }
    }

    private static void sun(Canvas c, Paint p) {
        fill(p);
        c.drawCircle(50, 50, 19, p);
        line(p);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 50, 50);
            c.drawLine(50, 8, 50, 20, p);
            c.restore();
        }
    }

    private static void search(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(9);
        c.drawCircle(42, 42, 26, p);
        c.drawLine(62, 62, 88, 88, p);
    }

    private static void mic(Canvas c, Paint p) {
        fill(p);
        R.set(36, 8, 64, 58);
        c.drawRoundRect(R, 14, 14, p);
        line(p);
        R.set(24, 30, 76, 76);
        c.drawArc(R, 0, 180, false, p);
        c.drawLine(50, 76, 50, 92, p);
    }

    private static void grid(Canvas c, Paint p) {
        fill(p);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                R.set(12 + i * 28, 12 + j * 28, 32 + i * 28, 32 + j * 28);
                c.drawRoundRect(R, 5, 5, p);
            }
        }
    }

    private static void info(Canvas c, Paint p) {
        line(p);
        c.drawCircle(50, 50, 38, p);
        fill(p);
        c.drawCircle(50, 32, 5, p);
        c.drawRect(45, 44, 55, 72, p);
    }

    private static void play(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.moveTo(28, 14);
        P.lineTo(86, 50);
        P.lineTo(28, 86);
        P.close();
        c.drawPath(P, p);
    }

    private static void refresh(Canvas c, Paint p) {
        line(p);
        R.set(18, 18, 82, 82);
        c.drawArc(R, -30, 260, false, p);
        fill(p);
        P.reset();
        P.moveTo(62, 6);
        P.lineTo(92, 26);
        P.lineTo(60, 36);
        P.close();
        c.drawPath(P, p);
    }

    private static void screen(Canvas c, Paint p) {
        line(p);
        R.set(8, 16, 92, 70);
        c.drawRoundRect(R, 8, 8, p);
        c.drawLine(36, 86, 64, 86, p);
        c.drawLine(50, 70, 50, 86, p);
        fill(p);
        P.reset();
        P.moveTo(34, 52);
        P.lineTo(46, 36);
        P.lineTo(56, 48);
        P.lineTo(62, 42);
        P.lineTo(72, 52);
        P.close();
        c.drawPath(P, p);
    }

    private static void shield(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.setFillType(Path.FillType.EVEN_ODD);
        P.moveTo(50, 8);
        P.lineTo(86, 20);
        P.lineTo(84, 52);
        P.quadTo(80, 76, 50, 92);
        P.quadTo(20, 76, 16, 52);
        P.lineTo(14, 20);
        P.close();
        P.moveTo(34, 50);
        P.lineTo(45, 62);
        P.lineTo(68, 36);
        P.lineTo(63, 32);
        P.lineTo(45, 53);
        P.lineTo(39, 46);
        P.close();
        c.drawPath(P, p);
        P.setFillType(Path.FillType.WINDING);
    }

    private static void wifi(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(8);
        for (int i = 0; i < 3; i++) {
            float r = 18 + i * 18;
            R.set(50 - r, 80 - r, 50 + r, 80 + r);
            c.drawArc(R, -135, 90, false, p);
        }
        fill(p);
        c.drawCircle(50, 80, 6, p);
    }

    private static void memory(Canvas c, Paint p) {
        fill(p);
        P.reset();
        P.setFillType(Path.FillType.EVEN_ODD);
        R.set(12, 24, 88, 72);
        P.addRoundRect(R, 7, 7, Path.Direction.CW);
        P.addRect(22, 34, 40, 62, Path.Direction.CW);
        P.addRect(48, 34, 66, 62, Path.Direction.CW);
        c.drawPath(P, p);
        P.setFillType(Path.FillType.WINDING);
        for (int i = 0; i < 5; i++) c.drawRect(20 + i * 14, 76, 28 + i * 14, 88, p);
    }

    private static void tv(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(8);
        R.set(10, 18, 90, 72);
        c.drawRoundRect(R, 9, 9, p);
        c.drawLine(34, 86, 66, 86, p);
        c.drawLine(50, 72, 50, 86, p);
    }

    private static void star(Canvas c, Paint p) {
        fill(p);
        P.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? 42 : 18;
            float x = 50 + (float) Math.cos(a) * r, y = 52 + (float) Math.sin(a) * r;
            if (i == 0) P.moveTo(x, y);
            else P.lineTo(x, y);
        }
        P.close();
        c.drawPath(P, p);
    }

    private static void setup(Canvas c, Paint p) {
        line(p);
        c.drawCircle(50, 50, 38, p);
        fill(p);
        P.reset();
        P.moveTo(34, 52);
        P.lineTo(46, 64);
        P.lineTo(68, 36);
        line(p);
        c.drawPath(P, p);
    }

    private static void plus(Canvas c, Paint p) {
        line(p);
        p.setStrokeWidth(10);
        c.drawLine(50, 18, 50, 82, p);
        c.drawLine(18, 50, 82, 50, p);
    }
}
