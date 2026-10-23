package com.hydrainnit.refract;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings, as XMB lists: every entry reads and writes the same stored preferences the rest of
 * the launcher uses, and visual settings preview live behind the bar (the waves are the
 * background, so moving a slider changes them as you hold the button).
 */
final class XmbSettings {
    private final XmbActivity a;

    XmbSettings(XmbActivity activity) {
        a = activity;
    }

    List<XmbMenu.Node> root() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.folder("Appearance", XmbIcons.SUN, "Light or dark, motion and font", this::appearance));
        l.add(a.folder("Waves", XmbIcons.WAVES, "Colours, gradient and wave animation", this::waves));
        l.add(a.folder("Particles", XmbIcons.DOTS, "Density, speed, size and how they react", this::particles));
        l.add(a.folder("Sounds", XmbIcons.SPEAKER, "Interface sounds, volume and the startup sound", this::sounds));
        l.add(a.folder("Screensaver", XmbIcons.SCREEN, "What the TV shows when idle", this::screensaver));
        l.add(a.folder("Wallpaper", XmbIcons.CAMERA, "Photo quality and downloads", this::wallpaper));
        l.add(a.folder("Clock & Weather", XmbIcons.CLOCK, "Units, location and updates", this::weather));
        l.add(a.folder("Movies (TMDB)", XmbIcons.FILM, "Optional key for movies, ratings and logos", this::tmdb));
        l.add(a.folder("Pexels", XmbIcons.KEY, "Optional key for future Pexels support", this::pexels));
        l.add(a.folder("Apps", XmbIcons.GRID, "Hidden apps, home screen and permissions", this::appsPage));
        l.add(a.folder("System", XmbIcons.INFO, "About XS3OS, credits and first-run setup", this::system));
        return l;
    }

    // ------------------------------------------------------------------ helpers

    private void reloadWaves() {
        a.waves.reload();
    }

    private XmbMenu.Node xToggle(String label, int icon, String desc, String key, boolean def) {
        return a.toggle(label, icon, desc, () -> Xmb.getb(a, key, def), v -> {
            Xmb.put(a, key, v);
            reloadWaves();
        });
    }

    private XmbMenu.Node xSlider(String label, int icon, String desc, String key, int def, int min, int max, int step, String unit) {
        return a.slider(label, icon, desc, min, max, step, unit, () -> Xmb.geti(a, key, def), v -> {
            Xmb.put(a, key, v);
            reloadWaves();
        });
    }

    // ------------------------------------------------------------------ Appearance

    private List<XmbMenu.Node> appearance() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.choice("Theme", XmbIcons.SUN, "Dark or light interface", new String[]{"Dark", "Light"},
                () -> Theme.isLight(a) ? 1 : 0, i -> {
                    Theme.set(a, i == 1);
                    a.waves.reload();
                    a.menu.applyTheme();
                }));
        l.add(xToggle("Reduce Motion", XmbIcons.WAVES, "Still waves, no particles. Saves power too.", "reduce_motion", false));
        l.add(a.info("Font", XmbIcons.INFO, "Inter", "Inter (SIL Open Font License) is used throughout XS3OS."));
        return l;
    }

    // ------------------------------------------------------------------ Waves

    private List<XmbMenu.Node> waves() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(xToggle("Dynamic Waves & Particles", XmbIcons.WAVES, "Animate the waves and particles. Uses more GPU; off keeps the static wallpaper.", "dynamic", false));
        final XmbMenu.Node preset = a.choice("Colour Preset", XmbIcons.WAVES, "PS3 Purple, Ocean, Sunset and more", Xmb.PRESET_NAMES,
                () -> Xmb.idx(Xmb.PRESET_IDS, Xmb.gets(a, "preset", "ps3")), i -> {
                    Xmb.put(a, "preset", Xmb.PRESET_IDS[i]);
                    reloadWaves();
                });
        final java.util.function.Supplier<List<XmbMenu.Node>> plain = preset.children;
        preset.children = () -> {
            List<XmbMenu.Node> kids = plain.get();
            for (int i = 0; i < kids.size(); i++) kids.get(i).swatch = Xmb.presetColors(i);
            return kids;
        };
        l.add(preset);
        l.add(a.folder("Custom Gradient", XmbIcons.SLIDERS, "Pick the two ends of the background", this::custom));
        l.add(a.choice("Background", XmbIcons.CAMERA, "Waves alone, or over your wallpaper", Xmb.BG_NAMES,
                () -> Xmb.idx(Xmb.BG_IDS, Xmb.bgMode(a)), i -> {
                    Xmb.put(a, "bg_mode", Xmb.BG_IDS[i]);
                    reloadWaves();
                    a.wall = null;
                    a.wallKey = "";
                    a.applyWallpaper();
                }));
        l.add(a.toggle("Wallpaper Carousel", XmbIcons.CAMERA, "Cycle the wallpaper: Pexels photos, or movie art with Movie Theme",
                () -> Xmb.getb(a, "wall_carousel", false), v -> {
                    Xmb.put(a, "wall_carousel", v);
                    a.restartCarousel();
                }));
        l.add(a.choice("Change Every", XmbIcons.CLOCK, "How long each picture stays", Xmb.WALL_NAMES,
                () -> Xmb.geti(a, "wall_interval", 2), i -> {
                    Xmb.put(a, "wall_interval", i);
                    a.restartCarousel();
                }));
        l.add(xSlider("Amplitude", XmbIcons.SLIDERS, "How tall the waves swell", "wave_amp", 50, 0, 100, 5, "%"));
        l.add(xSlider("Speed", XmbIcons.SLIDERS, "How fast they flow", "wave_speed", 40, 0, 100, 5, "%"));
        l.add(xSlider("Opacity", XmbIcons.SLIDERS, "How bright the ribbons are", "wave_opacity", 60, 5, 100, 5, "%"));
        l.add(xSlider("Layers", XmbIcons.SLIDERS, "Number of ribbons", "wave_layers", 3, 1, 5, 1, ""));
        l.add(a.choice("Direction", XmbIcons.WAVES, "Which way the waves flow", Xmb.DIR_NAMES,
                () -> Xmb.idx(Xmb.DIR_IDS, Xmb.waveDir(a)), i -> {
                    Xmb.put(a, "wave_dir", Xmb.DIR_IDS[i]);
                    reloadWaves();
                }));
        l.add(xToggle("Reduce Motion", XmbIcons.WAVES, "Always static, even if Dynamic is on", "reduce_motion", false));
        return l;
    }

    private XmbMenu.Node cSlider(String label, String key, int def, int min, int max, int step, String unit) {
        return a.slider(label, XmbIcons.SLIDERS, "Switches the colour preset to Custom", min, max, step, unit,
                () -> Xmb.geti(a, key, def), v -> {
                    Xmb.put(a, key, v);
                    Xmb.put(a, "preset", "custom");
                    reloadWaves();
                });
    }

    private List<XmbMenu.Node> custom() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(cSlider("Top Hue", "ct_h", 205, 0, 360, 5, "°"));
        l.add(cSlider("Top Brightness", "ct_v", 75, 10, 100, 5, "%"));
        l.add(cSlider("Bottom Hue", "cb_h", 250, 0, 360, 5, "°"));
        l.add(cSlider("Bottom Brightness", "cb_v", 25, 5, 100, 5, "%"));
        l.add(cSlider("Saturation", "c_s", 80, 0, 100, 5, "%"));
        return l;
    }

    // ------------------------------------------------------------------ Particles

    private List<XmbMenu.Node> particles() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(xToggle("Particles", XmbIcons.DOTS, "Scattered specks; they drift and react only with Dynamic Waves on", "particles", false));
        l.add(xSlider("Density", XmbIcons.SLIDERS, "How many (fewer on low-memory TVs)", "p_density", 40, 0, 100, 5, "%"));
        l.add(xSlider("Speed", XmbIcons.SLIDERS, "How fast they drift", "p_speed", 40, 0, 100, 5, "%"));
        l.add(xSlider("Size", XmbIcons.SLIDERS, "How big they are", "p_size", 40, 0, 100, 5, "%"));
        l.add(xSlider("Opacity", XmbIcons.SLIDERS, "How bright they are", "p_opacity", 60, 5, 100, 5, "%"));
        l.add(xSlider("Reaction", XmbIcons.SLIDERS, "How strongly they follow your remote", "p_react", 50, 0, 100, 5, "%"));
        return l;
    }

    // ------------------------------------------------------------------ Sounds

    private List<XmbMenu.Node> sounds() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.toggle("Interface Sounds", XmbIcons.SPEAKER, "Cursor, select and back sounds", () -> XmbSounds.enabled(a), v -> Xmb.put(a, "ui_sounds", v)));
        l.add(a.slider("Volume", XmbIcons.SPEAKER, "Relative to the TV's volume", 0, 100, 10, "%", () -> Xmb.geti(a, "ui_volume", 100), v -> {
            Xmb.put(a, "ui_volume", v);
            XmbSounds.play(a, XmbSounds.OK);
        }));
        l.add(a.toggle("Startup Sound in Setup", XmbIcons.SPEAKER, "Played once during first-time setup, never on ordinary launches",
                () -> Xmb.coldbootEnabled(a), v -> Xmb.put(a, "coldboot_enabled", v)));
        return l;
    }

    // ------------------------------------------------------------------ Screensaver

    private SharedPreferences ap() {
        return Ambient.prefs(a);
    }

    private XmbMenu.Node aChoice(String label, int icon, String desc, String key, String[] ids, String[] names, String def) {
        return a.choice(label, icon, desc, names, () -> Xmb.idx(ids, ap().getString(key, def)),
                i -> ap().edit().putString(key, ids[i]).apply());
    }

    private XmbMenu.Node aToggle(String label, String desc, String key, boolean def) {
        return a.toggle(label, XmbIcons.DOTS, desc, () -> ap().getBoolean(key, def), v -> ap().edit().putBoolean(key, v).apply());
    }

    private List<XmbMenu.Node> screensaver() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.action("Preview", XmbIcons.PLAY, "Start the screensaver now", a::enterAmbient));
        int[] idle = {0, 30, 60, 120, 300, 600};
        l.add(a.choice("Start After", XmbIcons.CLOCK, "Idle time on XS3OS before the screensaver starts",
                new String[]{"System setting only", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes"},
                () -> {
                    int v = a.homePrefs.getInt("idle_sec", 60);
                    for (int i = 0; i < idle.length; i++) if (idle[i] == v) return i;
                    return 2;
                }, i -> {
                    a.homePrefs.edit().putInt("idle_sec", idle[i]).apply();
                    a.resetIdle();
                }));
        l.add(aChoice("Style", XmbIcons.SCREEN, "Waves, photos, movies or a plain clock", "mode", Ambient.MODES, Ambient.MODE_NAMES, Ambient.PHOTOS));
        l.add(aChoice("Clock Style", XmbIcons.CLOCK, "XMB, cinematic, analog and more", "clock_style", ClockView.STYLES, ClockView.STYLE_NAMES, ClockView.XMB));
        l.add(aChoice("Clock Position", XmbIcons.CLOCK, "Includes Top Right", "clock_pos", Ambient.POSITIONS, Ambient.POSITION_NAMES, "top_right"));
        l.add(aChoice("Clock Format", XmbIcons.CLOCK, "12 or 24 hour", "clock_24", new String[]{"auto", "12", "24"},
                new String[]{"Automatic", "12-hour", "24-hour"}, "auto"));
        l.add(aToggle("Show Seconds", "", "clock_seconds", false));
        l.add(aToggle("Show Date", "", "clock_date", true));
        l.add(aToggle("Weather", "Temperature on the screensaver", "weather", true));
        l.add(aToggle("Photo Description", "Title under photos and movies", "description", true));
        l.add(aToggle("Photo Credit", "Photographer attribution", "attribution", true));
        l.add(aToggle("Burn-in Movement", "Slowly shifts the clock and text once a minute", "drift", true));
        l.add(aToggle("Reduce Motion", "Fade instead of glide", "reduce_motion", false));
        l.add(a.choice("Photo Interval", XmbIcons.CLOCK, "Time between photos", new String[]{"10 s", "20 s", "30 s", "1 min", "2 min"},
                () -> {
                    int v = ap().getInt("interval", 30);
                    return v <= 10 ? 0 : v <= 20 ? 1 : v <= 30 ? 2 : v <= 60 ? 3 : 4;
                }, i -> ap().edit().putInt("interval", new int[]{10, 20, 30, 60, 120}[i]).apply()));
        l.add(a.choice("Photo Category", XmbIcons.FOLDER, "Which photos the screensaver cycles", categoryNames(),
                () -> {
                    String c = Wallpapers.category(a);
                    List<Wallpapers.Category> cs = Wallpapers.categories(a);
                    for (int i = 0; i < cs.size(); i++) if (cs.get(i).id.equals(c)) return i;
                    return 0;
                }, i -> {
                    List<Wallpapers.Category> cs = Wallpapers.categories(a);
                    ap().edit().putBoolean("follow", false).putString("category", cs.get(i).id).apply();
                    a.photoFetchNow();
                }));
        l.add(a.toggle("Keep Screen On", XmbIcons.SCREEN, "Never let the TV's own screensaver start",
                () -> !a.getSharedPreferences("screensaver", android.content.Context.MODE_PRIVATE).getBoolean("allow", true),
                v -> a.getSharedPreferences("screensaver", android.content.Context.MODE_PRIVATE).edit().putBoolean("allow", !v).apply()));
        return l;
    }

    private String[] categoryNames() {
        List<Wallpapers.Category> cs = Wallpapers.categories(a);
        String[] n = new String[cs.size()];
        for (int i = 0; i < n.length; i++) n[i] = cs.get(i).name;
        return n;
    }

    // ------------------------------------------------------------------ Wallpaper

    private List<XmbMenu.Node> wallpaper() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.choice("Photo Quality", XmbIcons.CAMERA, "Photos are downloaded to the TV at this size (1080p about 0.4 MB, 4K about 1.5 MB each)",
                new String[]{"1080p", "4K"}, () -> "2160".equals(Wallpapers.quality(a)) ? 1 : 0, i -> {
                    Wallpapers.setQuality(a, i == 1 ? "2160" : "1080");
                    a.wall = null;
                    a.wallKey = "";
                    a.photoFetchNow();
                    a.applyWallpaper();
                }));
        l.add(a.info("Downloaded", XmbIcons.INFO, Math.round(Wallpapers.bytesOnDisk(a) / 1048576f) + " MB",
                "Photos download over Wi-Fi when you choose them and are kept for offline use. Without a connection XS3OS uses colour gradients."));
        l.add(a.info("Photos by", XmbIcons.CAMERA, "Pexels", "Each photographer is credited on the screensaver and in ATTRIBUTIONS.md."));
        return l;
    }

    // ------------------------------------------------------------------ Clock & Weather

    private SharedPreferences wp() {
        return Weather.prefs(a);
    }

    private List<XmbMenu.Node> weather() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.toggle("Weather", XmbIcons.CLOUD, "Show weather on the Network list and the screensaver", () -> Weather.enabled(a), v -> {
            wp().edit().putBoolean(Weather.K_ENABLED, v).apply();
            Weather.changed(a);
        }));
        l.add(a.choice("Units", XmbIcons.INFO, "Automatic picks by region", new String[]{"Automatic", "Celsius", "Fahrenheit"},
                () -> {
                    String u = wp().getString(Weather.K_UNITS, "auto");
                    return u.equals("c") ? 1 : u.equals("f") ? 2 : 0;
                }, i -> {
                    wp().edit().putString(Weather.K_UNITS, new String[]{"auto", "c", "f"}[i]).apply();
                    Weather.changed(a);
                }));
        l.add(a.action("Location", XmbIcons.GLOBE, "Search for a city", this::searchPlace).value(
                () -> Weather.manual(a) ? Weather.place(a) : "Automatic"));
        if (Weather.manual(a)) {
            l.add(a.action("Use Automatic Location", XmbIcons.GLOBE, "", () -> {
                Weather.setPlace(a, null);
                a.menu.refresh();
            }));
        }
        l.add(a.choice("Source", XmbIcons.CLOUD, "Where forecasts come from", new String[]{"Open-Meteo", "MET Norway", "wttr.in"},
                () -> Xmb.idx(Weather.SOURCES, Weather.source(a)), i -> {
                    wp().edit().putString(Weather.K_SOURCE, Weather.SOURCES[i]).apply();
                    Weather.changed(a);
                }));
        l.add(a.choice("Update Every", XmbIcons.REFRESH, "How often to fetch the forecast", new String[]{"10 minutes", "15 minutes", "30 minutes", "1 hour"},
                () -> {
                    int m = Weather.refreshMinutes(a);
                    return m <= 10 ? 0 : m <= 15 ? 1 : m <= 30 ? 2 : 3;
                }, i -> wp().edit().putInt(Weather.K_REFRESH, Weather.REFRESH_CHOICES[i]).apply()));
        if (!Perms.hasLocation(a)) {
            l.add(a.action("Allow Location", XmbIcons.GLOBE, "Lets XS3OS find the weather where you are", () -> Perms.requestLocation(a)));
        }
        return l;
    }

    private void searchPlace() {
        a.askText("Weather location", "City name", false, "", q -> {
            if (q.isEmpty()) return;
            a.io.execute(() -> {
                List<Weather.Place> found;
                try {
                    found = Weather.search(q);
                } catch (Exception e) {
                    a.handler.post(() -> a.toast("Could not search: check the connection"));
                    return;
                }
                final List<Weather.Place> res = found;
                a.handler.post(() -> {
                    if (res.isEmpty()) {
                        a.toast("No place called " + q);
                        return;
                    }
                    List<XmbMenu.Node> kids = new ArrayList<>();
                    for (Weather.Place p : res) {
                        kids.add(a.action(p.name, XmbIcons.GLOBE, p.detail, () -> {
                            Weather.setPlace(a, p);
                            a.refreshWeather();
                            a.menu.back();
                            a.menu.refresh();
                        }));
                    }
                    a.menu.push("Choose a place", kids);
                });
            });
        });
    }

    // ------------------------------------------------------------------ keys

    private XmbMenu.Node keyEntry(String label, String name, boolean tmdb) {
        XmbMenu.Node n = new XmbMenu.Node(label).icon(XmbIcons.KEY)
                .desc(tmdb ? "Paste a TMDB read-access token or API key" : "Paste your Pexels API key");
        n.value = () -> tmdb ? (a.tmdbMasked.isEmpty() ? "Not set" : a.tmdbMasked) : (a.pexelsMasked.isEmpty() ? "Not set" : a.pexelsMasked);
        n.action = () -> a.askText(label, "Key", true, "", v -> {
            if (v.isEmpty()) return;
            Secrets.put(a, name, v);
            if (tmdb) {
                Suggestions.changed(a);
                for (String[] c : Movies.COLLECTIONS) Movies.cacheFile(a, c[0]).delete();
            }
            a.refreshKeyStatus();
            a.toast("Key saved");
            a.handler.postDelayed(a.menu::refresh, 300);
        });
        return n;
    }

    private List<XmbMenu.Node> tmdb() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(keyEntry("API Key", Secrets.TMDB, true));
        l.add(a.info("Status", XmbIcons.INFO, null, "").value(() -> a.tmdbStatus));
        l.add(a.action("Test Key", XmbIcons.REFRESH, "Checks the key with TMDB now", () -> {
            if (a.tmdbMasked.isEmpty()) {
                a.toast("Add a key first");
                return;
            }
            a.io.execute(() -> {
                Suggestions.testKey(a);
                a.refreshKeyStatus();
                a.handler.post(() -> a.toast("TMDB: " + Secrets.describe(Secrets.status(a, Secrets.TMDB))));
            });
        }));
        l.add(a.action("Remove Key", XmbIcons.POWER, "Delete the saved key", () -> a.confirm("Remove TMDB key?", "Movie lists will stop until you add a key again.", () -> {
            Secrets.put(a, Secrets.TMDB, "");
            Suggestions.changed(a);
            a.refreshKeyStatus();
            a.handler.postDelayed(a.menu::refresh, 300);
        })));
        l.add(a.toggle("Movie Theme", XmbIcons.FILM, "Use the focused movie's artwork behind the bar", () -> Movies.homeTheme(a), v -> Movies.setHomeTheme(a, v)));
        l.add(a.info("About TMDB", XmbIcons.INFO, null, "This product uses the TMDB API but is not endorsed or certified by TMDB. Keys are stored encrypted on this TV and never shown in full."));
        return l;
    }

    private List<XmbMenu.Node> pexels() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(keyEntry("API Key", Secrets.PEXELS, false));
        l.add(a.info("Status", XmbIcons.INFO, null, "").value(() -> a.pexelsStatus));
        l.add(a.action("Remove Key", XmbIcons.POWER, "Delete the saved key", () -> a.confirm("Remove Pexels key?", "Nothing else changes: the bundled photo list keeps working.", () -> {
            Secrets.put(a, Secrets.PEXELS, "");
            a.refreshKeyStatus();
            a.handler.postDelayed(a.menu::refresh, 300);
        })));
        l.add(a.info("Pexels API", XmbIcons.INFO, "Paused", "XS3OS does not call the Pexels API right now, and a saved key is not checked. Wallpapers use the bundled photo list. Saving a key now just keeps it ready for when API support returns."));
        return l;
    }

    // ------------------------------------------------------------------ Apps

    private List<XmbMenu.Node> appsPage() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.folder("Hidden Apps", XmbIcons.GRID, "Bring hidden apps back", this::hiddenApps));
        l.add(a.action("Home Screen", XmbIcons.TV, "Make XS3OS the Home button's launcher", () -> {
            if (Perms.isDefaultHome(a)) a.toast("XS3OS is already your home screen");
            else Perms.requestDefaultHome(a);
        }).value(() -> Perms.isDefaultHome(a) ? "Default" : "Not default"));
        l.add(a.action("Notification Access", XmbIcons.INFO, "Lets Continue Watching and Now Playing see what is playing", () -> {
            if (MediaWatchService.granted(a)) a.toast("Already allowed");
            else Perms.requestListener(a);
        }).value(() -> MediaWatchService.granted(a) ? "Allowed" : "Off"));
        l.add(a.action("Sleep Control", XmbIcons.POWER, "Device admin, only used to put the screen to sleep", () -> {
            if (Perms.hasAdmin(a)) a.toast("Already allowed");
            else Perms.requestAdmin(a);
        }).value(() -> Perms.hasAdmin(a) ? "Allowed" : "Off"));
        l.add(a.action("Close Apps Fully", XmbIcons.MEMORY, "Accessibility switch that lets Clear Memory press Force Stop", () -> Perms.requestForceStop(a))
                .value(() -> PowerService.instance != null ? "On" : "Off"));
        return l;
    }

    private List<XmbMenu.Node> hiddenApps() {
        List<XmbMenu.Node> l = new ArrayList<>();
        Set<String> hidden = a.homePrefs.getStringSet("hidden", Collections.emptySet());
        for (XmbActivity.AppEntry e : a.apps) {
            if (!hidden.contains(e.key)) continue;
            XmbMenu.Node n = new XmbMenu.Node(e.label).icon(XmbIcons.GRID).desc("Show on Home again");
            n.art = e.icon;
            n.action = () -> {
                Set<String> h = new HashSet<>(a.homePrefs.getStringSet("hidden", Collections.emptySet()));
                h.remove(e.key);
                a.homePrefs.edit().putStringSet("hidden", h).apply();
                a.menu.back();
                a.menu.refresh();
            };
            l.add(n);
        }
        if (l.isEmpty()) l.add(a.info("No hidden apps", XmbIcons.GRID, null, "Press the menu button on an app to hide it."));
        return l;
    }

    // ------------------------------------------------------------------ System

    private List<XmbMenu.Node> system() {
        List<XmbMenu.Node> l = new ArrayList<>();
        l.add(a.info("XS3OS", XmbIcons.INFO, "Made by Hydrainnit",
                "XS3OS is an independent, unofficial XrossMediaBar-style launcher for Android TV by Hydrainnit. Not affiliated with Sony or PlayStation. Photos by their Pexels photographers; open-source credits are in NOTICE."));
        l.add(a.info("Memory", XmbIcons.MEMORY, null, "").value(() -> Math.round(a.memoryUsed() * 100) + "% used"));
        l.add(a.info("Interface Font", XmbIcons.INFO, Xmb.fontName(), ""));
        l.add(a.action("Run Setup Again", XmbIcons.SETUP, "Shows the welcome and setup again, including the startup sound", () ->
                a.confirm("Run setup again?", "XS3OS will show the welcome and setup. Your settings stay until you change them.", () -> {
                    Xmb.put(a, "setup_done", false);
                    Xmb.put(a, "coldboot_played", false);
                    a.recreate();
                })));
        return l;
    }
}
