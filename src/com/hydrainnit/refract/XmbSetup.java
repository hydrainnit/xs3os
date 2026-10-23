package com.hydrainnit.refract;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run setup as XMB categories: Welcome, Appearance, Colours, Movies, Photos, Screensaver,
 * Finish. Left/Right move between steps, Continue moves on, every optional step can be skipped,
 * and everything is applied (and stored) the moment it is chosen, so a visual change previews
 * live and an interrupted setup resumes with what was already picked.
 */
final class XmbSetup {
    private final XmbActivity a;

    XmbSetup(XmbActivity activity) {
        a = activity;
    }

    private void step(int i) {
        Xmb.put(a, "setup_step_" + i, true);
    }

    private XmbMenu.Node next(String label, int stepIndex) {
        return a.action(label, XmbIcons.PLAY, "", () -> {
            step(stepIndex);
            a.menu.goCategory(1);
        });
    }

    List<XmbMenu.Cat> categories() {
        List<XmbMenu.Cat> l = new ArrayList<>();
        l.add(new XmbMenu.Cat("welcome", "Welcome", XmbIcons.SETUP, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            n.add(next("Begin Setup", 0).desc("A few quick choices. Everything can be changed later in Settings."));
            n.add(a.action("Skip Setup", XmbIcons.POWER, "Use the defaults and go to Home", a::finishSetup));
            return n;
        }));
        l.add(new XmbMenu.Cat("appearance", "Appearance", XmbIcons.SUN, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            n.add(theme("Dark", false));
            n.add(theme("Light", true));
            n.add(next("Continue", 1));
            return n;
        }));
        l.add(new XmbMenu.Cat("colours", "Colours", XmbIcons.WAVES, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            for (int i = 0; i < Xmb.PRESET_IDS.length - 1; i++) {
                final int idx = i;
                XmbMenu.Node pn = new XmbMenu.Node(Xmb.PRESET_NAMES[i]).icon(XmbIcons.WAVES)
                        .desc("Previews behind the menu");
                pn.swatch = Xmb.presetColors(i);
                n.add(pn
                        .checked(() -> Xmb.PRESET_IDS[idx].equals(Xmb.gets(a, "preset", "ps3")))
                        .on(() -> {
                            Xmb.put(a, "preset", Xmb.PRESET_IDS[idx]);
                            a.waves.reload();
                            a.menu.invalidate();
                        }));
            }
            n.add(a.toggle("Reduce Motion", XmbIcons.WAVES, "Still waves, no particles", () -> Xmb.reduceMotion(a), v -> {
                Xmb.put(a, "reduce_motion", v);
                a.waves.reload();
            }));
            n.add(next("Continue", 2));
            return n;
        }));
        l.add(new XmbMenu.Cat("movies", "Movies", XmbIcons.FILM, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            XmbMenu.Node key = new XmbMenu.Node("TMDB API Key").icon(XmbIcons.KEY)
                    .desc("Optional. Adds movies, ratings and title logos to Video.");
            key.value = () -> a.tmdbMasked.isEmpty() ? "Not set" : a.tmdbMasked;
            key.action = () -> a.askText("TMDB API key", "Key", true, "", v -> {
                if (v.isEmpty()) return;
                Secrets.put(a, Secrets.TMDB, v);
                Suggestions.changed(a);
                a.refreshKeyStatus();
                a.toast("Key saved");
            });
            n.add(key);
            n.add(next(a.tmdbMasked.isEmpty() ? "Skip" : "Continue", 3));
            return n;
        }));
        l.add(new XmbMenu.Cat("photos", "Photos", XmbIcons.CAMERA, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            n.add(a.choice("Photo Quality", XmbIcons.CAMERA, "Photos download to the TV at this size", new String[]{"1080p", "4K"},
                    () -> "2160".equals(Wallpapers.quality(a)) ? 1 : 0, i -> Wallpapers.setQuality(a, i == 1 ? "2160" : "1080")));
            XmbMenu.Node key = new XmbMenu.Node("Pexels API Key").icon(XmbIcons.KEY)
                    .desc("Optional, for future Pexels support. Not checked or used today.");
            key.value = () -> a.pexelsMasked.isEmpty() ? "Not set" : a.pexelsMasked;
            key.action = () -> a.askText("Pexels API key", "Key", true, "", v -> {
                if (v.isEmpty()) return;
                Secrets.put(a, Secrets.PEXELS, v);
                a.refreshKeyStatus();
                a.toast("Key saved");
            });
            n.add(key);
            n.add(next(a.pexelsMasked.isEmpty() ? "Skip" : "Continue", 4));
            return n;
        }));
        l.add(new XmbMenu.Cat("screensaver", "Screensaver", XmbIcons.SCREEN, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            final String[] ids = {Ambient.WAVES, Ambient.WAVES_PHOTO, Ambient.PHOTOS, Ambient.MINIMAL};
            final String[] names = {"XMB Waves", "Waves over a Photo", "Photo Carousel", "Minimal Clock"};
            for (int i = 0; i < ids.length; i++) {
                final int idx = i;
                n.add(new XmbMenu.Node(names[i]).icon(XmbIcons.SCREEN).checked(
                        () -> ids[idx].equals(Ambient.prefs(a).getString("mode", Ambient.WAVES)))
                        .on(() -> {
                            Ambient.prefs(a).edit().putString("mode", ids[idx]).apply();
                            a.menu.invalidate();
                        }));
            }
            n.add(next("Continue", 5));
            return n;
        }));
        l.add(new XmbMenu.Cat("finish", "Finish", XmbIcons.STAR, () -> {
            List<XmbMenu.Node> n = new ArrayList<>();
            n.add(a.action("Finish Setup", XmbIcons.STAR, "Everything you chose is also under Settings", () -> {
                step(6);
                a.finishSetup();
            }));
            return n;
        }));
        return l;
    }

    private XmbMenu.Node theme(String name, boolean light) {
        return new XmbMenu.Node(name).icon(light ? XmbIcons.SUN : XmbIcons.USER)
                .desc(light ? "Bright waves, dark text" : "Deep colour, white text")
                .checked(() -> Theme.isLight(a) == light)
                .on(() -> {
                    Theme.set(a, light);
                    a.waves.reload();
                    a.menu.applyTheme();
                    a.menu.invalidate();
                    a.toast(name + " appearance selected");
                });
    }
}
