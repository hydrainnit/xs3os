package com.hydrainnit.refract;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * XS3OS Home: the PlayStation 3 XrossMediaBar for Android TV. Waves and particles underneath, the
 * XMB menu on top, an intro and setup on first run, and the screensaver when idle.
 *
 * Categories: Users, Settings, Photos, Music, Video, Games, Apps, Network, Power. Every entry
 * does something real (launches an app, changes a stored setting, opens a system screen).
 */
public class XmbActivity extends Activity implements XmbMenu.Host {
    static final String TAG = "XS3OS";
    private static final String TV_SETTINGS = "com.android.tv.settings";

    static final class AppEntry {
        String key, pkg, label;
        Drawable icon;
        Intent launch;
        boolean game, system;
    }

    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final ExecutorService imageIo = Executors.newSingleThreadExecutor();
    FrameLayout root;
    XmbWaves waves;
    XmbMenu menu;
    Ambient ambient;
    XmbIntro intro;
    boolean resumed, setupMode, introActive;
    SharedPreferences homePrefs;
    final List<AppEntry> apps = new ArrayList<>();
    volatile boolean appsLoaded;
    List<Inputs.Input> inputs = new ArrayList<>();
    Bitmap wall;
    String wallKey = "";
    final ArrayList<XmbMenu.Node> artLoaded = new ArrayList<>();
    String tmdbStatus = "", pexelsStatus = "", tmdbMasked = "", pexelsMasked = "";
    int generation;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        homePrefs = Profiles.homePrefs(this);
        XmbSounds.init(this);
        root = new FrameLayout(this);
        waves = new XmbWaves(this);
        // A wallpaper is expected: start from the plain gradient so the waves never flash before it fades in.
        if (moviesCarousel()) waves.setBgOverride("photo");
        waves.setWallPending(!"waves".equals(Xmb.bgMode(this)) || moviesCarousel());
        menu = new XmbMenu(this, this);
        root.addView(waves, new FrameLayout.LayoutParams(-1, -1));
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        refreshKeyStatus();
        setupMode = !Xmb.setupDone(this);
        if (setupMode) {
            startIntro();
        } else {
            menu.setCategories(mainCategories(), startCategory());
            handler.postDelayed(menu::prewarm, 1500);
        }
        loadApps();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
        // Home pressed while we are showing: back to the top of the bar.
        if (Intent.ACTION_MAIN.equals(i.getAction()) && !setupMode && menu != null) {
            exitAmbient();
            while (menu.back()) { /* up to the category row */ }
        }
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        // adb test hook:  am start -n com.hydrainnit.refract/.XmbActivity --es cat video
        if (i.hasExtra("perf")) waves.setVisibility("off".equals(i.getStringExtra("perf")) ? View.GONE : View.VISIBLE);
        String cat = i.getStringExtra("cat");
        if (cat != null && menu != null && !setupMode) {
            int idx = indexOfCategory(cat);
            if (idx >= 0) menu.setCategories(mainCategories(), idx);
            String open = i.getStringExtra("open");
            if (open != null) {
                menu.select(open);
                menu.onKey(KeyEvent.KEYCODE_DPAD_CENTER, 0);
            }
        }
        if (i.getBooleanExtra("saver", false)) enterAmbient();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        waves.setActive(true);
        waves.reload();
        menu.applyTheme();
        refreshKeyStatus();
        if (getSharedPreferences("screensaver", MODE_PRIVATE).getBoolean("allow", true)) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        applyWallpaper();
        fetchPhotos();
        refreshWeather();
        AirPlayGuard.check(this, false);
        handler.removeCallbacks(guardTick);
        handler.postDelayed(guardTick, 15 * 60000L);
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (!setupMode && menu.currentCat() != null && nowMs - lastRefresh > 8000) {
            lastRefresh = nowMs;
            menu.refresh();
        }
        resetIdle();
        restartCarousel();
    }

    long lastRefresh;

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(idleRunnable);
        handler.removeCallbacks(carouselTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        waves.setActive(false);
        exitAmbient();
        dropArt();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            dropArt();
            if (level >= TRIM_MEMORY_RUNNING_LOW) {
                wall = null;
                wallKey = "";
                waves.setWallpaper(null);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        imageIo.shutdownNow();
        if (intro != null) XmbSounds.stopLong();
        XmbSounds.release();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        waves.reload();
        menu.applyTheme();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        resetIdle();
        if (ambient != null) {
            if (down) exitAmbient();
            return true;
        }
        if (introActive) {
            if (down && ev.getRepeatCount() == 0 && intro != null) intro.skip();
            return true;
        }
        if (voiceKey(k)) {
            if (down && ev.getRepeatCount() == 0) invokeAssistant(null);
            return true;
        }
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(ev);
        }
        if (!down) {
            return isNavKey(k) || super.dispatchKeyEvent(ev);
        }
        waves.hold(700);
        if (menu.onKey(k, ev.getRepeatCount())) return true;
        return super.dispatchKeyEvent(ev);
    }

    static boolean isNavKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    static boolean voiceKey(int k) {
        return k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST;
    }

    @Override
    public void onBackPressed() {
        // Home is the root: Back never leaves it.
    }

    // ------------------------------------------------------------------ Host

    @Override
    public void onCategory(XmbMenu.Cat c, int dir) {
        waves.react(dir, 0f);
        releaseMovieTheme();
    }

    @Override
    public void onItem(XmbMenu.Node n, int dirY) {
        if (dirY != 0) waves.react(0f, dirY * 0.7f);
    }

    @Override
    public void onActivate(XmbMenu.Node n) {
        waves.react(0f, -0.4f);
    }

    @Override
    public void onBack() {
        // at the root of a category: nothing to go back to
    }

    @Override
    public void onTick(int kind) {
        switch (kind) {
            case XmbMenu.TICK_MOVE: XmbSounds.play(this, XmbSounds.MOVE); break;
            case XmbMenu.TICK_MOVE_H: XmbSounds.play(this, XmbSounds.OPTION); break;
            case XmbMenu.TICK_OK: XmbSounds.play(this, XmbSounds.OK); break;
            case XmbMenu.TICK_BACK: XmbSounds.play(this, XmbSounds.CANCEL); break;
            default: XmbSounds.play(this, XmbSounds.ERROR); break;
        }
    }

    @Override
    public void onOptions(XmbMenu.Node n) {
        menu.push(n.label, n.options.get());
    }

    // ------------------------------------------------------------------ categories

    int indexOfCategory(String id) {
        List<XmbMenu.Cat> cs = mainCategories();
        for (int i = 0; i < cs.size(); i++) if (cs.get(i).id.equals(id)) return i;
        return -1;
    }

    int startCategory() {
        return Math.max(0, indexOfCategory(homePrefs.getString("last_cat", "apps")));
    }

    List<XmbMenu.Cat> mainCategories() {
        List<XmbMenu.Cat> l = new ArrayList<>();
        l.add(new XmbMenu.Cat("users", "Users", XmbIcons.USER, this::usersItems));
        l.add(new XmbMenu.Cat("settings", "Settings", XmbIcons.TOOLBOX, this::settingsItems));
        l.add(new XmbMenu.Cat("photos", "Photos", XmbIcons.CAMERA, this::photoItems));
        l.add(new XmbMenu.Cat("music", "Music", XmbIcons.NOTE, this::musicItems));
        l.add(new XmbMenu.Cat("video", "Video", XmbIcons.FILM, this::videoItems));
        l.add(new XmbMenu.Cat("games", "Games", XmbIcons.PAD, () -> appItems(true)));
        l.add(new XmbMenu.Cat("apps", "Apps", XmbIcons.GRID, () -> appItems(false)));
        l.add(new XmbMenu.Cat("network", "Network", XmbIcons.GLOBE, this::networkItems));
        l.add(new XmbMenu.Cat("power", "Power", XmbIcons.POWER, this::powerItems));
        return l;
    }

    // ------------------------------------------------------------------ node helpers

    XmbMenu.Node folder(String label, int icon, String desc, Supplier<List<XmbMenu.Node>> kids) {
        return new XmbMenu.Node(label).icon(icon).desc(desc).sub(kids);
    }

    XmbMenu.Node toggle(String label, int icon, String desc, BooleanSupplier get, Consumer<Boolean> set) {
        return new XmbMenu.Node(label).icon(icon).desc(desc).value(() -> get.getAsBoolean() ? "On" : "Off")
                .on(() -> {
                    set.accept(!get.getAsBoolean());
                    menu.invalidate();
                });
    }

    XmbMenu.Node choice(String label, int icon, String desc, String[] names, IntSupplier cur, IntConsumer set) {
        XmbMenu.Node n = new XmbMenu.Node(label).icon(icon).desc(desc);
        n.value = () -> names[Math.max(0, Math.min(names.length - 1, cur.getAsInt()))];
        n.children = () -> {
            List<XmbMenu.Node> kids = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                kids.add(new XmbMenu.Node(names[i]).icon(XmbIcons.DOTS).checked(() -> cur.getAsInt() == idx).on(() -> {
                    set.accept(idx);
                    menu.back();
                    menu.invalidate();
                }));
            }
            return kids;
        };
        return n;
    }

    XmbMenu.Node slider(String label, int icon, String desc, int min, int max, int step, String unit, IntSupplier get, IntConsumer set) {
        return new XmbMenu.Node(label).icon(icon).desc(desc).slider(min, max, step, unit, get, set);
    }

    XmbMenu.Node info(String label, int icon, String value, String detail) {
        XmbMenu.Node n = new XmbMenu.Node(label).icon(icon);
        if (value != null) n.value = () -> value;
        n.detail = detail == null ? "" : detail;
        return n;
    }

    XmbMenu.Node action(String label, int icon, String desc, Runnable r) {
        return new XmbMenu.Node(label).icon(icon).desc(desc).on(r);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ Users

    List<XmbMenu.Node> usersItems() {
        List<XmbMenu.Node> l = new ArrayList<>();
        Profiles.Profile cur = Profiles.current(this);
        for (Profiles.Profile p : Profiles.list(this)) {
            final Profiles.Profile pp = p;
            boolean me = p.id.equals(cur.id);
            XmbMenu.Node n = new XmbMenu.Node(p.name).icon(XmbIcons.USER).desc(me ? "Current user" : "Switch to this user");
            n.value = me ? () -> "Current" : null;
            n.on(() -> {
                if (!pp.id.equals(Profiles.current(this).id)) switchProfile(pp.id);
            });
            n.options = () -> profileOptions(pp);
            l.add(n);
        }
        if (Profiles.canAdd(this)) {
            l.add(action("New User", XmbIcons.PLUS, "Create another user with their own home and recommendations", () ->
                    askText("New user", "Name", false, "", v -> {
                        if (v.isEmpty()) return;
                        Profiles.Profile np = Profiles.add(this, v);
                        if (np != null) switchProfile(np.id);
                    })));
        }
        l.add(toggle("Ask Who's Watching on Wake", XmbIcons.INFO, "Show the user list when the TV wakes up",
                () -> Profiles.askOnWake(this), v -> Profiles.setAskOnWake(this, v)));
        return l;
    }

    List<XmbMenu.Node> profileOptions(Profiles.Profile p) {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(action("Rename", XmbIcons.INFO, "", () -> askText("Rename user", "Name", false, p.name, v -> {
            if (v.isEmpty()) return;
            p.name = v;
            Profiles.update(this, p);
            menu.back();
            menu.refresh();
        })));
        if (Profiles.list(this).size() > 1) {
            l.add(action("Delete", XmbIcons.POWER, "Removes this user and their Continue Watching", () -> confirm("Delete " + p.name + "?",
                    "Their home settings and Continue Watching will be removed.", () -> {
                        boolean wasCurrent = p.id.equals(Profiles.current(this).id);
                        Profiles.delete(this, p.id);
                        if (wasCurrent) switchProfile(Profiles.current(this).id);
                        menu.back();
                        menu.refresh();
                    })));
        }
        return l;
    }

    void switchProfile(String id) {
        Profiles.setCurrent(this, id);
        homePrefs = Profiles.homePrefs(this);
        wall = null;
        wallKey = "";
        waves.reload();
        applyWallpaper();
        menu.refresh();
        toast("Switched to " + Profiles.current(this).name);
    }

    // ------------------------------------------------------------------ Photos

    List<XmbMenu.Node> photoItems() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(action("Start Screensaver", XmbIcons.SCREEN, "Preview the screensaver now", this::enterAmbient));
        for (Wallpapers.Category cat : Wallpapers.categories(this)) {
            final Wallpapers.Category cc = cat;
            XmbMenu.Node n = folder(cat.name, XmbIcons.FOLDER, Wallpapers.inCategory(this, cat.id).size() + " photos",
                    () -> photoNodes(cc));
            l.add(n);
        }
        return l;
    }

    List<XmbMenu.Node> photoNodes(Wallpapers.Category cat) {
        List<XmbMenu.Node> l = new ArrayList<>();
        for (Wallpapers.Photo p : Wallpapers.inCategory(this, cat.id)) {
            final Wallpapers.Photo ph = p;
            XmbMenu.Node n = new XmbMenu.Node(p.title == null || p.title.isEmpty() ? p.id : p.title).icon(XmbIcons.CAMERA);
            n.meta = p.credit();
            n.tag = n.label;
            n.desc = "Set as background";
            Wallpapers.Photo sel = Wallpapers.selected(this);
            n.checked = () -> {
                Wallpapers.Photo s = Wallpapers.selected(this);
                return s != null && s.id.equals(ph.id);
            };
            n.onFocus = () -> loadPhotoArt(n, ph);
            n.action = () -> {
                Wallpapers.select(this, ph);
                wallKey = "";
                if (!"waves".equals(Xmb.bgMode(this))) applyWallpaper();
                else toast("Wallpaper chosen. Turn on a wallpaper background in Settings > Waves to see it behind the bar.");
                menu.invalidate();
            };
            l.add(n);
        }
        return l;
    }

    void loadPhotoArt(XmbMenu.Node n, Wallpapers.Photo p) {
        if (n.image != null) return;
        final int gen = generation;
        imageIo.execute(() -> {
            Bitmap b = Wallpapers.decode(this, p, Math.round(300 * Ui.unit(this)));
            handler.post(() -> {
                if (gen != generation || b == null) return;
                holdArt(n);
                n.image = b;
                menu.invalidate();
            });
        });
    }

    void holdArt(XmbMenu.Node n) {
        artLoaded.remove(n);
        artLoaded.add(n);
        while (artLoaded.size() > 6) {
            XmbMenu.Node old = artLoaded.remove(0);
            old.image = null;
            old.logo = null;
        }
    }

    void dropArt() {
        generation++;
        for (XmbMenu.Node n : artLoaded) {
            n.image = null;
            n.logo = null;
        }
        artLoaded.clear();
    }

    // ------------------------------------------------------------------ Music

    List<MediaController> sessions() {
        try {
            if (!MediaWatchService.granted(this)) return Collections.emptyList();
            MediaSessionManager sm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            return sm.getActiveSessions(new ComponentName(this, MediaWatchService.class));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    List<XmbMenu.Node> musicItems() {
        List<XmbMenu.Node> l = new ArrayList<>();
        MediaController best = null;
        for (MediaController mc : sessions()) {
            PlaybackState ps = mc.getPlaybackState();
            if (mc.getMetadata() == null) continue;
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                best = mc;
                break;
            }
            if (best == null) best = mc;
        }
        if (best != null) {
            final MediaController mc = best;
            CharSequence t = mc.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
            CharSequence a = mc.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            XmbMenu.Node n = new XmbMenu.Node(t == null ? "Now Playing" : t.toString()).icon(XmbIcons.NOTE)
                    .desc((a == null ? "" : a + "  ·  ") + appLabel(mc.getPackageName()));
            n.sub(() -> {
                List<XmbMenu.Node> c = new ArrayList<>();
                c.add(action("Play / Pause", XmbIcons.PLAY, "", () -> {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
                    else mc.getTransportControls().play();
                }));
                c.add(action("Next", XmbIcons.PLAY, "", () -> mc.getTransportControls().skipToNext()));
                c.add(action("Previous", XmbIcons.PLAY, "", () -> mc.getTransportControls().skipToPrevious()));
                c.add(action("Open " + appLabel(mc.getPackageName()), XmbIcons.GRID, "", () -> launchPackage(mc.getPackageName())));
                return c;
            });
            l.add(n);
        } else if (!MediaWatchService.granted(this)) {
            l.add(action("Show What's Playing", XmbIcons.NOTE, "Allow notification access so music and video controls can appear here",
                    () -> Perms.requestListener(this)));
        }
        for (AppEntry e : audioApps()) l.add(appNode(e));
        if (l.isEmpty()) l.add(info("No music apps found", XmbIcons.NOTE, null, "Install a music app and it will appear here."));
        return l;
    }

    List<AppEntry> audioApps() {
        List<AppEntry> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (AppEntry e : apps) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
                if (ai.category == ApplicationInfo.CATEGORY_AUDIO || e.pkg.contains("spotify") || e.pkg.contains("music")) out.add(e);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    String appLabel(String pkg) {
        for (AppEntry e : apps) if (e.pkg.equals(pkg)) return e.label;
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------ Video

    boolean movieTheme;

    List<XmbMenu.Node> videoItems() {
        List<XmbMenu.Node> l = new ArrayList<>();
        List<Suggestions.Item> cw = MediaWatchService.Store.list(this);
        if (!cw.isEmpty()) {
            l.add(folder("Continue Watching", XmbIcons.PLAY, cw.size() + " titles in progress", () -> continueNodes()));
        }
        if (Movies.configured(this)) {
            for (String[] col : Movies.COLLECTIONS) {
                final String key = col[0];
                l.add(folder(col[1], XmbIcons.FILM, "Movies from TMDB", () -> movieNodes(key)));
            }
            l.add(toggle("Movie Theme", XmbIcons.FILM, "Use the selected movie's artwork behind the bar",
                    () -> Movies.homeTheme(this), v -> {
                        Movies.setHomeTheme(this, v);
                        if (!v) releaseMovieTheme();
                    }));
        } else {
            l.add(action("Add a TMDB Key", XmbIcons.KEY, "Movies, ratings and title logos come from TMDB. It is free and optional.",
                    () -> openSettings("Movies (TMDB)")));
        }
        for (AppEntry e : streamingApps()) l.add(appNode(e));
        return l;
    }

    List<AppEntry> streamingApps() {
        List<AppEntry> out = new ArrayList<>();
        Set<String> pk = new HashSet<>();
        for (Suggestions.Provider p : Suggestions.PROVIDERS) pk.add(p.pkg);
        pk.add("com.google.android.youtube.tv");
        pk.add("org.jellyfin.androidtv");
        pk.add("com.plexapp.android");
        for (AppEntry e : apps) if (pk.contains(e.pkg)) out.add(e);
        return out;
    }

    List<XmbMenu.Node> continueNodes() {
        List<XmbMenu.Node> l = new ArrayList<>();
        for (Suggestions.Item it : MediaWatchService.Store.list(this)) {
            final Suggestions.Item item = it;
            XmbMenu.Node n = new XmbMenu.Node(it.title).icon(XmbIcons.PLAY).desc(it.overview.isEmpty() ? appLabel(it.pkg) : it.overview);
            n.tag = it.title;
            n.meta = appLabel(it.pkg) + (it.remainingMin > 0 ? "   ·   " + it.remainingMin + " min left" : "");
            n.action = () -> launchPackage(item.pkg);
            l.add(n);
        }
        return l;
    }

    List<XmbMenu.Node> movieNodes(String key) {
        List<XmbMenu.Node> l = new ArrayList<>();
        List<Suggestions.Item> items = Movies.cached(this, key, true);
        if (items == null) {
            l.add(info("Loading…", XmbIcons.REFRESH, null, "Fetching titles from TMDB. Go back and open this list again in a moment."));
            io.execute(() -> {
                try {
                    Movies.load(this, key);
                    handler.post(() -> {
                        if (menu.depth() > 1) menu.refresh();
                    });
                } catch (Exception e) {
                    final String why = Suggestions.TmdbException.describe(e);
                    handler.post(() -> toast("TMDB: " + why));
                }
            });
            return l;
        }
        for (Suggestions.Item it : items) l.add(movieNode(it));
        return l;
    }

    XmbMenu.Node movieNode(Suggestions.Item it) {
        XmbMenu.Node n = new XmbMenu.Node(it.title).icon(XmbIcons.PLAY);
        n.tag = it.title;
        n.meta = Movies.meta(it);
        n.detail = it.overview == null ? "" : it.overview;
        n.desc = Movies.releaseLabel(it);
        n.onFocus = () -> loadMovieArt(n, it);
        n.action = () -> invokeAssistant(it.title);
        return n;
    }

    void loadMovieArt(XmbMenu.Node n, Suggestions.Item it) {
        if (n.image != null) {
            themeWith(n.image);
            return;
        }
        final int gen = generation;
        final boolean lowRam = Xmb.lowRam(this);
        imageIo.execute(() -> {
            int w = lowRam ? 640 : 960;
            Bitmap art = it.backdrop == null || it.backdrop.isEmpty() ? null : Suggestions.image(this, it.backdrop, "w780", w);
            if (art == null && it.poster != null && !it.poster.isEmpty()) art = Suggestions.image(this, it.poster, "w500", 500);
            Bitmap logo = null;
            try {
                String lp = Movies.logoFor(this, it);
                if (lp != null && !lp.isEmpty()) logo = Suggestions.image(this, lp, "w500", 480);
            } catch (Exception ignored) {
            }
            if (it.runtime <= 0) Movies.details(this, it);
            final Bitmap fa = art, fl = logo;
            handler.post(() -> {
                if (gen != generation) return;
                holdArt(n);
                n.image = fa;
                n.logo = fl;
                n.meta = Movies.meta(it);
                if (menu.selected() == n) themeWith(fa);
                menu.invalidate();
            });
        });
    }

    /** Movie Theme: while a movie is focused its artwork replaces the background; leaving the movies restores waves or the Pexels photo. */
    void themeWith(Bitmap b) {
        if (b == null || !Movies.homeTheme(this)) return;
        movieTheme = true;
        waves.setBgOverride("photo");
        waves.setWallpaper(b);
    }

    void releaseMovieTheme() {
        if (!movieTheme) return;
        movieTheme = false;
        waves.setBgOverride(carouselOn() && carouselMovies ? "photo" : null);
        waves.setWallpaper(carouselOn() && carouselMovies ? wall : "waves".equals(Xmb.bgMode(this)) ? null : wall);
    }

    // ------------------------------------------------------------------ wallpaper carousel

    boolean carouselMovies;

    boolean moviesCarousel() {
        return carouselOn() && Movies.configured(this) && Movies.homeTheme(this);
    }
    private int carouselIdx = -1;
    private boolean carouselBusy;

    /** The carousel runs for Pexels photos (Wallpaper mode) or, with Movie Theme on, for movie artwork. */
    boolean carouselOn() {
        if (!Xmb.getb(this, "wall_carousel", false)) return false;
        return Movies.configured(this) && Movies.homeTheme(this) || !"waves".equals(Xmb.bgMode(this));
    }

    long carouselDelay() {
        int i = Math.max(0, Math.min(Xmb.WALL_SECS.length - 1, Xmb.geti(this, "wall_interval", 2)));
        return Xmb.WALL_SECS[i] * 1000L;
    }

    void restartCarousel() {
        handler.removeCallbacks(carouselTick);
        if (!resumed || !carouselOn()) return;
        // Movie artwork has no static first pick, so the first one is fetched straight away.
        if (moviesCarousel() && (wall == null || !carouselMovies)) handler.post(carouselTick);
        else handler.postDelayed(carouselTick, carouselDelay());
    }

    private final Runnable carouselTick = new Runnable() {
        @Override public void run() {
            if (!resumed || !carouselOn()) return;
            if (!movieTheme && !carouselBusy) advanceCarousel();
            handler.postDelayed(this, carouselDelay());
        }
    };

    private void advanceCarousel() {
        final boolean movies = Movies.configured(this) && Movies.homeTheme(this);
        final int gen = generation;
        final int lowW = Xmb.lowRam(this) ? 1280 : Math.min(1920, Ui.screen(this).x);
        carouselBusy = true;
        imageIo.execute(() -> {
            Bitmap b = null;
            try {
                if (movies) {
                    List<Suggestions.Item> items = Movies.cached(this, Movies.COLLECTIONS[0][0], true);
                    if (items == null) {
                        try {
                            items = Movies.load(this, Movies.COLLECTIONS[0][0]);
                        } catch (Exception ignored) {
                        }
                    }
                    if (items != null && !items.isEmpty()) {
                        for (int tries = 0; tries < items.size() && b == null; tries++) {
                            carouselIdx = (carouselIdx + 1) % items.size();
                            Suggestions.Item it = items.get(carouselIdx);
                            if (it.backdrop != null && !it.backdrop.isEmpty()) b = Suggestions.image(this, it.backdrop, "w780", lowW);
                        }
                    }
                } else {
                    List<Wallpapers.Photo> list = Wallpapers.inCategory(this, Wallpapers.category(this));
                    if (list.isEmpty()) list = Wallpapers.all(this);
                    if (!list.isEmpty()) {
                        carouselIdx = (carouselIdx + 1) % list.size();
                        b = Wallpapers.decode(this, list.get(carouselIdx), lowW);
                    }
                }
            } catch (Throwable ignored) {
            }
            final Bitmap nb = b;
            handler.post(() -> {
                carouselBusy = false;
                if (gen != generation) return;
                if (nb == null || !carouselOn()) {
                    if (wall == null) waves.setWallPending(false);
                    return;
                }
                carouselMovies = movies;
                wall = nb;
                wallKey = "carousel" + carouselIdx;
                if (!movieTheme) {
                    waves.setBgOverride(movies ? "photo" : null);
                    waves.setWallpaper(nb);
                }
            });
        });
    }

    // ------------------------------------------------------------------ Apps and Games

    List<XmbMenu.Node> appItems(boolean games) {
        List<XmbMenu.Node> l = new ArrayList<>();
        if (!appsLoaded) {
            l.add(info("Loading apps…", XmbIcons.REFRESH, null, ""));
            return l;
        }
        Set<String> hidden = homePrefs.getStringSet("hidden", Collections.emptySet());
        if (!games) {
            if (!inputs.isEmpty() && Inputs.showCard(this)) {
                l.add(folder("Inputs", XmbIcons.TV, "HDMI and other inputs", () -> {
                    List<XmbMenu.Node> k = new ArrayList<>();
                    for (Inputs.Input in : Inputs.list(this)) {
                        k.add(action(in.label, XmbIcons.TV, in.disconnected ? "Nothing connected" : "", () -> {
                            if (!Inputs.open(this, in)) toast("Could not open " + in.label);
                        }));
                    }
                    return k;
                }));
            }
            if (com.hydrainnit.refract.present.Presenter.enabled(this)) {
                l.add(action("Presentation", XmbIcons.SCREEN, "Full-screen presentation player", () -> startSafe(
                        new Intent(this, com.hydrainnit.refract.present.PresentationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
            }
        }
        for (AppEntry e : apps) {
            if (hidden.contains(e.key)) continue;
            if (e.game != games) continue;
            l.add(appNode(e));
        }
        if (l.isEmpty()) {
            l.add(info(games ? "No games installed" : "No apps", XmbIcons.GRID, null, "")
                    .desc(games ? "Games you install appear here" : ""));
        }
        return l;
    }

    XmbMenu.Node appNode(AppEntry e) {
        XmbMenu.Node n = new XmbMenu.Node(e.label).icon(XmbIcons.GRID);
        n.art = e.icon;
        n.action = () -> launch(e);
        n.options = () -> appOptions(e);
        return n;
    }

    List<XmbMenu.Node> appOptions(AppEntry e) {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(action("Hide from Home", XmbIcons.INFO, "Brings it back from Settings > Apps", () -> {
            Set<String> h = new HashSet<>(homePrefs.getStringSet("hidden", Collections.emptySet()));
            h.add(e.key);
            homePrefs.edit().putStringSet("hidden", h).apply();
            menu.back();
            menu.refresh();
        }));
        l.add(action("App Info", XmbIcons.INFO, "", () -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        if (!e.system) {
            l.add(action("Uninstall", XmbIcons.POWER, "", () -> startSafe(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        }
        return l;
    }

    void launch(AppEntry e) {
        noteLaunched(e.pkg);
        if (e.game) XmbSounds.playLong(this, "gameboot_stereo.m4a", XmbSounds.enabled(this) ? XmbSounds.volume(this) * 0.5f : 0f, null, null);
        startSafe(e.launch);
    }

    void launchPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("Could not open " + appLabel(pkg));
            return;
        }
        noteLaunched(pkg);
        startSafe(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void startSafe(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            XmbSounds.play(this, XmbSounds.ERROR);
            toast("Could not open that");
        }
    }

    void loadApps() {
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, AppEntry> found = new LinkedHashMap<>();
            Set<String> pkgs = new HashSet<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found, pkgs);
            collect(pm, Intent.CATEGORY_LAUNCHER, found, pkgs);
            if (!pkgs.contains(TV_SETTINGS)) {
                Intent s = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                AppEntry e = new AppEntry();
                e.key = "android.settings";
                e.pkg = "com.android.settings";
                e.label = "Android Settings";
                e.system = true;
                e.launch = s;
                try {
                    e.icon = rounded(pm.getApplicationIcon("com.android.settings"));
                } catch (Exception ignored) {
                }
                found.put(e.key, e);
            }
            List<AppEntry> list = new ArrayList<>(found.values());
            Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
            List<Inputs.Input> in = Inputs.list(this);
            handler.post(() -> {
                apps.clear();
                apps.addAll(list);
                inputs = in;
                appsLoaded = true;
                if (!setupMode && menu.currentCat() != null) menu.refresh();
            });
        });
    }

    /** App icons are rounded once here, so drawing a row never needs a clip path. */
    Drawable rounded(Drawable d) {
        try {
            int s = 96;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new android.graphics.RectF(0, 0, s, s), s * 0.22f, s * 0.22f, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            d.setBounds(0, 0, s, s);
            d.draw(c);
            return new android.graphics.drawable.BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    void collect(PackageManager pm, String category, Map<String, AppEntry> out, Set<String> pkgs) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName()) || pkgs.contains(ai.packageName)) continue;
            AppEntry e = new AppEntry();
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            e.pkg = ai.packageName;
            e.key = cn.flattenToShortString();
            e.label = ri.loadLabel(pm).toString();
            e.icon = rounded(ri.loadIcon(pm));
            e.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.game = ai.applicationInfo.category == ApplicationInfo.CATEGORY_GAME;
            e.launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.put(e.key, e);
            pkgs.add(e.pkg);
        }
    }

    // ------------------------------------------------------------------ Network

    List<XmbMenu.Node> networkItems() {
        List<XmbMenu.Node> l = new ArrayList<>();
        final String net = netName();
        l.add(action("Network", XmbIcons.WIFI, "Open the TV's network settings", () -> startSafe(
                new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))).value(() -> net));
        l.add(weatherNode());
        l.add(action("Voice Search", XmbIcons.MIC, "Say a title, app or question (also the remote's mic button)", () -> invokeAssistant(null)));
        l.add(action("Refresh", XmbIcons.REFRESH, "Reload weather, movies and the Continue Watching list", () -> {
            Weather.changed(this);
            refreshWeather();
            for (String[] c : Movies.COLLECTIONS) Movies.cacheFile(this, c[0]).delete();
            toast("Refreshing…");
            menu.refresh();
        }));
        return l;
    }

    String netName() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "Not connected";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            return "Connected";
        } catch (Exception e) {
            return "Connected";
        }
    }

    XmbMenu.Node weatherNode() {
        XmbMenu.Node n = new XmbMenu.Node("Weather").icon(XmbIcons.CLOUD);
        Weather.Data d = Weather.enabled(this) ? Weather.cached(this) : null;
        if (!Weather.enabled(this)) {
            n.desc = "Turned off in Settings > Clock & Weather";
            return n;
        }
        if (d == null) {
            n.desc = "No forecast yet";
            n.detail = "Weather loads when the TV is online.";
            return n;
        }
        String u = "f".equals(Weather.units(this)) ? "°F" : "°C";
        n.desc = d.place + "   " + d.temp + u + "   " + Weather.describe(d.code, d.day);
        n.tag = d.temp + u + "  " + Weather.describe(d.code, d.day);
        n.meta = d.place + "   H " + d.max + "°  L " + d.min + "°";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, d.days.size()); i++) {
            Weather.Day dy = d.days.get(i);
            sb.append(dy.label).append("   ").append(dy.max).append("° / ").append(dy.min).append("°");
            if (i < Math.min(5, d.days.size()) - 1) sb.append('\n');
        }
        n.detail = sb.toString();
        return n;
    }

    void refreshWeather() {
        if (Weather.enabled(this) && Weather.stale(this)) {
            io.execute(() -> Weather.refresh(this, false, () -> handler.post(() -> {
                if (menu.currentCat() != null && "network".equals(menu.currentCat().id) && menu.depth() == 1) menu.refresh();
            })));
        }
    }

    // ------------------------------------------------------------------ Power

    List<XmbMenu.Node> powerItems() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(action("Sleep", XmbIcons.POWER, "Turn the screen off now", () -> {
            if (!SleepReceiver.canSleep(this)) {
                Perms.requestAdmin(this);
                return;
            }
            SleepReceiver.sleepNow(this);
        }));
        l.add(choice("Sleep Timer", XmbIcons.CLOCK, "Put the TV to sleep after a while",
                new String[]{"Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes"},
                () -> {
                    int left = SleepReceiver.timerLeft(this);
                    return left <= 0 ? 0 : left <= 15 ? 1 : left <= 30 ? 2 : left <= 60 ? 3 : 4;
                },
                i -> {
                    if (i > 0 && !SleepReceiver.canSleep(this)) {
                        Perms.requestAdmin(this);
                        return;
                    }
                    SleepReceiver.setTimer(this, new int[]{0, 15, 30, 60, 90}[i]);
                }));
        l.add(action("Clear Memory", XmbIcons.MEMORY, "Close apps you opened from Home and free memory", this::cleanMemory)
                .value(() -> Math.round(memoryUsed() * 100) + "% used"));
        l.add(new XmbMenu.Node("Do Not Disturb").icon(XmbIcons.INFO).desc("Silence notifications").value(() -> dndOn() ? "On" : "Off")
                .on(this::toggleDnd));
        l.add(action("Android Settings", XmbIcons.GEAR, "Open the TV's own settings", () -> startSafe(
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        return l;
    }

    float memoryUsed() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 ? 1f - mi.availMem / (float) mi.totalMem : 0f;
    }

    boolean dndOn() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    void toggleDnd() {
        MediaWatchService svc = MediaWatchService.instance;
        if (svc == null) {
            Perms.requestListener(this);
            return;
        }
        svc.requestInterruptionFilter(dndOn() ? android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_ALL
                : android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_PRIORITY);
        handler.postDelayed(menu::invalidate, 400);
    }

    void noteLaunched(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return;
        SharedPreferences cp = getSharedPreferences("clean", MODE_PRIVATE);
        List<String> l = new ArrayList<>(Arrays.asList(cp.getString("recent", "").split(",")));
        l.remove(pkg);
        l.remove("");
        l.add(0, pkg);
        while (l.size() > 12) l.remove(l.size() - 1);
        cp.edit().putString("recent", String.join(",", l)).apply();
    }

    List<String> recentApps() {
        List<String> l = new ArrayList<>();
        for (String p : getSharedPreferences("clean", MODE_PRIVATE).getString("recent", "").split(",")) {
            if (!p.isEmpty() && Suggestions.installed(this, p)) l.add(p);
        }
        for (MediaController mc : sessions()) {
            String p = mc.getPackageName();
            if (!p.equals(getPackageName()) && !l.contains(p)) l.add(p);
        }
        return l;
    }

    void cleanMemory() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        List<String> list = recentApps();
        if (list.isEmpty()) {
            toast("No apps to close");
            return;
        }
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        final long before = mi.availMem;
        for (String pkg : list) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (Exception ignored) {
            }
        }
        final boolean force = PowerService.instance != null;
        Runnable done = () -> {
            getSharedPreferences("clean", MODE_PRIVATE).edit().remove("recent").apply();
            android.app.ActivityManager.MemoryInfo m2 = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(m2);
            long freed = Math.max(0, m2.availMem - before) / (1024 * 1024);
            String mb = freed > 0 ? " · freed " + freed + " MB" : "";
            XmbSounds.play(this, XmbSounds.DONE);
            if (force) {
                toast("Closed " + list.size() + (list.size() == 1 ? " app" : " apps") + mb);
            } else {
                toast("Freed background memory" + mb + ". To close apps fully, turn on Refract’s Power Off switch.");
                SharedPreferences cp = getSharedPreferences("clean", MODE_PRIVATE);
                if (!cp.getBoolean("asked_force", false)) {
                    cp.edit().putBoolean("asked_force", true).apply();
                    Perms.requestForceStop(this);
                }
            }
            menu.invalidate();
        };
        if (force) PowerService.forceStop(this, list, () -> handler.postDelayed(done, 600));
        else handler.postDelayed(done, 800);
    }

    // ------------------------------------------------------------------ assistant

    void invokeAssistant(String query) {
        if (!resumed) return;
        String[] actions = {Intent.ACTION_ASSIST, "android.intent.action.VOICE_ASSIST",
                android.speech.RecognizerIntent.ACTION_WEB_SEARCH, android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH};
        for (int i = query == null ? 0 : 2; i < actions.length; i++) {
            try {
                Intent in = new Intent(actions[i]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (query != null) in.putExtra(android.app.SearchManager.QUERY, query);
                startActivity(in);
                return;
            } catch (Exception ignored) {
                // not on this TV: try the next
            }
        }
        XmbSounds.play(this, XmbSounds.ERROR);
        toast("This TV has no voice search app");
    }

    // ------------------------------------------------------------------ dialogs

    interface Text {
        void got(String s);
    }

    void askText(String title, String hint, boolean secret, String current, Text done) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setText(current);
        if (secret) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(et)
                .setPositiveButton("OK", (x, w) -> done.got(et.getText().toString().trim()))
                .setNegativeButton("Cancel", null).create();
        d.show();
        et.requestFocus();
    }

    void confirm(String title, String message, Runnable yes) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(message)
                .setPositiveButton("OK", (x, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ keys (TMDB / Pexels)

    void refreshKeyStatus() {
        io.execute(() -> {
            String ts = Secrets.describe(Secrets.status(this, Secrets.TMDB));
            String ps = Secrets.describe(Secrets.status(this, Secrets.PEXELS));
            String tm = Secrets.masked(Secrets.get(this, Secrets.TMDB));
            String pm = Secrets.masked(Secrets.get(this, Secrets.PEXELS));
            handler.post(() -> {
                tmdbStatus = ts;
                pexelsStatus = ps;
                tmdbMasked = tm;
                pexelsMasked = pm;
                if (menu != null) menu.invalidate();
            });
        });
    }

    // ------------------------------------------------------------------ background

    void applyWallpaper() {
        if (carouselOn() && carouselMovies && wall != null) return;
        String mode = Xmb.bgMode(this);
        if ("waves".equals(mode)) {
            wall = null;
            wallKey = "";
            if (!moviesCarousel()) waves.setWallPending(false);
            if (!movieTheme) waves.setWallpaper(null);
            return;
        }
        if (carouselOn() && wall != null) return;
        final Wallpapers.Photo p = Wallpapers.selected(this);
        if (p == null) {
            waves.setWallPending(false);
            return;
        }
        final String key = p.id + Wallpapers.quality(this);
        if (key.equals(wallKey) && wall != null) {
            if (!movieTheme) waves.setWallpaper(wall);
            return;
        }
        final int gen = generation;
        imageIo.execute(() -> {
            Bitmap b = Wallpapers.decode(this, p, Math.min(1920, Ui.screen(this).x));
            handler.post(() -> {
                if (gen != generation) return;
                if (b == null) {
                    waves.setWallPending(false);
                    return;
                }
                wall = b;
                wallKey = key;
                if (!movieTheme) waves.setWallpaper(b);
            });
        });
    }

    void photoFetchNow() {
        fetchPhotos();
    }

    final ExecutorService photoFetch = Executors.newSingleThreadExecutor();
    boolean fetchQueued;

    void fetchPhotos() {
        if (fetchQueued) return;
        fetchQueued = true;
        photoFetch.execute(() -> {
            Wallpapers.prefetch(this);
            handler.post(() -> {
                fetchQueued = false;
                if (!"waves".equals(Xmb.bgMode(this)) && wall == null) applyWallpaper();
            });
        });
    }

    // ------------------------------------------------------------------ idle and screensaver

    final Runnable guardTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            AirPlayGuard.check(XmbActivity.this, false);
            handler.postDelayed(this, 15 * 60000L);
        }
    };

    final Runnable idleRunnable = this::enterAmbient;

    int idleSeconds() {
        int sec = homePrefs.getInt("idle_sec", 60);
        if (sec == 0 && !getSharedPreferences("screensaver", MODE_PRIVATE).getBoolean("allow", true)) sec = 60;
        return sec;
    }

    void resetIdle() {
        handler.removeCallbacks(idleRunnable);
        int sec = idleSeconds();
        if (sec > 0 && resumed && ambient == null && !introActive) handler.postDelayed(idleRunnable, sec * 1000L);
    }

    void enterAmbient() {
        if (ambient != null || !resumed || introActive) return;
        ambient = new Ambient(this);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        ambient.setAlpha(0f);
        ambient.start();
        ambient.animate().alpha(1f).setDuration(900).start();
        waves.setActive(false);
    }

    void exitAmbient() {
        if (ambient == null) return;
        final Ambient a = ambient;
        ambient = null;
        a.stop();
        a.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(a)).start();
        waves.setActive(resumed);
        resetIdle();
    }

    // ------------------------------------------------------------------ settings and setup (see XmbSettings / XmbIntro)

    List<XmbMenu.Node> settingsItems() {
        return new XmbSettings(this).root();
    }

    void openSettings(String folder) {
        int idx = indexOfCategory("settings");
        menu.setCategories(mainCategories(), idx);
        menu.select(folder);
        menu.onKey(KeyEvent.KEYCODE_DPAD_CENTER, 0);
    }

    void startIntro() {
        introActive = true;
        intro = new XmbIntro(this, this::finishIntro);
        root.addView(intro, new FrameLayout.LayoutParams(-1, -1));
        intro.begin();
    }

    void finishIntro() {
        introActive = false;
        if (intro != null) {
            final XmbIntro i = intro;
            intro = null;
            i.animate().alpha(0f).setDuration(500).withEndAction(() -> root.removeView(i)).start();
        }
        menu.setCategories(new XmbSetup(this).categories(), 0);
        resetIdle();
    }

    /** Called by the last setup step. */
    void finishSetup() {
        Xmb.put(this, "setup_done", true);
        setupMode = false;
        XmbSounds.play(this, XmbSounds.DONE);
        menu.setCategories(mainCategories(), indexOfCategory("apps"));
        if (!Perms.isDefaultHome(this)) {
            confirm("Make XS3OS your home screen?", "The Home button on the remote will open XS3OS.", () -> Perms.requestDefaultHome(this));
        }
    }
}
