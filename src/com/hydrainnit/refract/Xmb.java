package com.hydrainnit.refract;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * AS3 look and feel settings: palette presets (or a custom gradient), wave and particle tuning,
 * background mode and the first-run flags. One SharedPreferences file ("xmb"), read when the
 * XMB starts and whenever a setting changes. Light/Dark still lives in Theme.
 */
final class Xmb {
    static final String PREFS = "xmb";

    /** Palette presets: id, name, then top / middle / bottom of the background and the wave tint. */
    static final String[] PRESET_IDS = {"ps3", "original", "ocean", "violet", "crimson", "emerald", "sunset", "aurora", "mono", "gold", "rose", "midnight", "forest", "amber", "teal", "lime", "indigo", "sakura", "slate", "ember", "lavender", "custom"};
    static final String[] PRESET_NAMES = {"PS3 Purple", "Original Blue", "Ocean", "Violet", "Crimson", "Emerald", "Sunset", "Aurora", "Monochrome", "Gold", "Rose", "Midnight", "Forest", "Amber", "Teal", "Lime", "Indigo", "Sakura", "Slate", "Ember", "Lavender", "Custom"};
    private static final int[][] PRESETS = {
            {0xFF7C80B8, 0xFF5A4A92, 0xFF4A2C70, 0xFFDCD2FF},
            {0xFF3C8FAE, 0xFF1B4F8F, 0xFF060B3C, 0xFFDDEBFF},
            {0xFF12909A, 0xFF065075, 0xFF021A30, 0xFFD2F4FF},
            {0xFF7C5EB8, 0xFF4B2C82, 0xFF8E5EBC, 0xFFEADCFF},
            {0xFFB02838, 0xFF620C1C, 0xFF1C0409, 0xFFFFD9DC},
            {0xFF22A874, 0xFF0C5C48, 0xFF03231B, 0xFFD6FFEC},
            {0xFFF58052, 0xFFB43E70, 0xFF2B1152, 0xFFFFE6D2},
            {0xFF30DDAA, 0xFF2B5FCC, 0xFF3F1070, 0xFFD9FFF2},
            {0xFF555555, 0xFF222222, 0xFF030303, 0xFFE6E6E6},
            {0xFFD9A441, 0xFF8A4B1E, 0xFF24100A, 0xFFFFF0C8},
            {0xFFE87A9C, 0xFF9A3A78, 0xFF2A0F3A, 0xFFFFE0EC},
            {0xFF2A3F8F, 0xFF121A52, 0xFF03040F, 0xFFCFD8FF},
            {0xFF4DA06A, 0xFF1C5A44, 0xFF04180F, 0xFFD8F6DF},
            {0xFFE0A030, 0xFFA85A18, 0xFF2C1206, 0xFFFFEBC0},
            {0xFF2CB8B0, 0xFF0F6A78, 0xFF04242E, 0xFFCFFFF8},
            {0xFF9ACD3C, 0xFF3C7A2A, 0xFF0C2410, 0xFFEEFFC8},
            {0xFF5A62D0, 0xFF2C2E8C, 0xFF0A0A38, 0xFFD6D8FF},
            {0xFFF0A0C0, 0xFFB05C98, 0xFF40204C, 0xFFFFE4F0},
            {0xFF7A8794, 0xFF3C4856, 0xFF0C1016, 0xFFE2E8EE},
            {0xFFE8643C, 0xFF9C2A28, 0xFF2A0A12, 0xFFFFD8C8},
            {0xFFB8A0E8, 0xFF7456B4, 0xFF2A1A58, 0xFFF0E6FF}};

    /** Top / middle / bottom colours of a preset for its swatch, or null for Custom. */
    static int[] presetColors(int i) {
        return i >= 0 && i < PRESETS.length ? PRESETS[i] : null;
    }

    static final String[] WALL_NAMES = {"10 seconds", "30 seconds", "1 minute", "5 minutes", "15 minutes", "1 hour"};
    static final int[] WALL_SECS = {10, 30, 60, 300, 900, 3600};

    static final String[] BG_IDS = {"waves", "photo"};
    static final String[] BG_NAMES = {"XMB Waves", "Wallpaper (no waves)"};
    static final String[] DIR_IDS = {"ltr", "rtl"};
    static final String[] DIR_NAMES = {"Left to Right", "Right to Left"};

    private Xmb() {
    }

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int geti(Context c, String k, int d) {
        return p(c).getInt(k, d);
    }

    static boolean getb(Context c, String k, boolean d) {
        return p(c).getBoolean(k, d);
    }

    static String gets(Context c, String k, String d) {
        return p(c).getString(k, d);
    }

    static void put(Context c, String k, int v) {
        p(c).edit().putInt(k, v).apply();
    }

    static void put(Context c, String k, boolean v) {
        p(c).edit().putBoolean(k, v).apply();
    }

    static void put(Context c, String k, String v) {
        p(c).edit().putString(k, v).apply();
    }

    static boolean lowRam(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.isLowRamDevice();
    }

    static int idx(String[] ids, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return i;
        return 0;
    }

    static int blend(int a, int b, float t) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Custom gradient: hue 0-360 and brightness 0-100 for each end, one shared saturation. */
    static int customTop(Context c) {
        return android.graphics.Color.HSVToColor(new float[]{geti(c, "ct_h", 205), geti(c, "c_s", 80) / 100f, geti(c, "ct_v", 75) / 100f});
    }

    static int customBottom(Context c) {
        return android.graphics.Color.HSVToColor(new float[]{geti(c, "cb_h", 250), geti(c, "c_s", 80) / 100f, geti(c, "cb_v", 25) / 100f});
    }

    /** The four palette colours for the chosen preset, lightened when the Light appearance is on. */
    static int[] palette(Context c) {
        String id = gets(c, "preset", "ps3");
        int[] src;
        if ("custom".equals(id)) {
            int top = customTop(c), bot = customBottom(c);
            src = new int[]{top, blend(top, bot, 0.55f), bot, blend(top, 0xFFFFFFFF, 0.8f)};
        } else {
            src = PRESETS[Math.min(idx(PRESET_IDS, id), PRESETS.length - 1)];
        }
        int[] out = src.clone();
        if (Theme.isLight(c)) {
            for (int i = 0; i < 3; i++) out[i] = blend(src[i], src[3], i == 0 ? 0.45f : 0.55f);
        }
        return out;
    }

    /** Text colour on the XMB: white on dark, deep navy on light. */
    static int ink(Context c) {
        return Theme.isLight(c) ? 0xFF10213F : 0xFFFFFFFF;
    }

    // ------------------------------------------------------------------ waves
    static int waveAmp(Context c) { return geti(c, "wave_amp", 50); }
    static int waveSpeed(Context c) { return geti(c, "wave_speed", 40); }
    static int waveOpacity(Context c) { return geti(c, "wave_opacity", 60); }
    static int waveLayers(Context c) { return geti(c, "wave_layers", 3); }
    static String waveDir(Context c) { return gets(c, "wave_dir", "ltr"); }
    /** Static waves: drawn once, nothing animates. */
    static boolean reduceMotion(Context c) { return getb(c, "reduce_motion", false); }
    static String bgMode(Context c) {
        String m = gets(c, "bg_mode", "waves");
        return "blend".equals(m) ? "photo" : m;
    }

    // ------------------------------------------------------------------ particles
    static boolean particles(Context c) { return getb(c, "particles", true); }
    /** Animated waves and particles: off by default; the static baked wallpaper is used otherwise. */
    static boolean dynamic(Context c) { return getb(c, "dynamic", false) && !reduceMotion(c); }
    static int pDensity(Context c) { return geti(c, "p_density", 40); }
    static int pSpeed(Context c) { return geti(c, "p_speed", 40); }
    static int pSize(Context c) { return geti(c, "p_size", 40); }
    static int pOpacity(Context c) { return geti(c, "p_opacity", 60); }
    static int pReact(Context c) { return geti(c, "p_react", 50); }

    // ------------------------------------------------------------------ first run
    static boolean setupDone(Context c) { return getb(c, "setup_done", false); }
    /** Set only once the coldboot audio has actually started playing. */
    static boolean coldbootPlayed(Context c) { return getb(c, "coldboot_played", false); }
    static boolean coldbootEnabled(Context c) { return getb(c, "coldboot_enabled", true); }

    private static android.graphics.Typeface font;

    /** The interface font everywhere: Inter (OFL, bundled in assets/fonts). */
    static synchronized android.graphics.Typeface font(Context c) {
        if (font == null) font = Ui.font(c, "regular");
        return font;
    }

    static String fontName() {
        return "Inter";
    }
}
