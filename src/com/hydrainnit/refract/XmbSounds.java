package com.hydrainnit.refract;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.util.Log;

/**
 * AS3's UI sounds, from the supplied archive (assets/sfx, see docs/as3-sounds.md).
 *
 * Short effects load into a SoundPool on a background thread at start-up; if one is pressed before
 * it has loaded it is simply skipped. The long ones (coldboot, gameboot) stream through a single
 * MediaPlayer so they never sit decoded in memory. Everything plays on the media stream at the
 * user's own volume setting (Sounds -> Volume) and is skipped when UI sounds are off.
 */
final class XmbSounds {
    static final String TAG = "AS3Sound";
    static final int MOVE = 0, OK = 1, CANCEL = 2, ERROR = 3, DONE = 4, OPTION = 5;
    // snd_option (a 39 ms tick) doubles as the cursor tick: the archive has no separate cursor sound.
    private static final String[] FILES = {"snd_option.wav", "snd_system_ok.wav", "snd_cancel.wav", "snd_system_ng.wav", "snd_trophy.wav", "snd_option.wav"};

    private static SoundPool pool;
    private static final int[] ids = new int[FILES.length];
    private static final boolean[] ready = new boolean[FILES.length];
    private static MediaPlayer long_;
    private static volatile boolean starting;
    private static long lastMove;

    private XmbSounds() {
    }

    static boolean enabled(Context c) {
        return Xmb.getb(c, "ui_sounds", true);
    }

    static float volume(Context c) {
        return Xmb.geti(c, "ui_volume", 100) / 100f;
    }

    /** Loads the short effects off the main thread. Safe to call more than once. */
    static synchronized void init(Context ctx) {
        if (pool != null) return;
        final Context c = ctx.getApplicationContext();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        final SoundPool sp = pool;
        sp.setOnLoadCompleteListener((p, id, status) -> {
            if (status != 0) return;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) ready[i] = true;
        });
        new Thread(() -> {
            for (int i = 0; i < FILES.length; i++) {
                if (i == OPTION) {
                    ids[i] = ids[MOVE];
                    continue;
                }
                try (AssetFileDescriptor fd = c.getAssets().openFd("sfx/" + FILES[i])) {
                    ids[i] = sp.load(fd, 1);
                } catch (Exception e) {
                    Log.w(TAG, "could not load " + FILES[i] + ": " + e);
                }
            }
        }, "as3-sounds").start();
    }

    /** One short effect. Cursor ticks are rate-limited so holding a direction stays soft. */
    static void play(Context c, int which) {
        if (pool == null || !enabled(c)) return;
        boolean tick = which == MOVE || which == OPTION;
        if (tick) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastMove < 30) return;
            lastMove = now;
        }
        if (!ready[which] && !(which == OPTION && ready[MOVE])) return;
        float v = Math.min(1f, volume(c) * (tick ? 0.9f : 1f));
        // The archive has one tick: category moves play it lower, item moves at normal pitch.
        pool.play(ids[which], v, v, 1, 0, which == OPTION ? 0.78f : 1f);
    }

    /** Plays assets/sfx/<file> once through MediaPlayer. Returns false if it could not start. */
    static synchronized boolean playLong(Context c, String file, float vol, Runnable onStarted, Runnable onDone) {
        stopLong();
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                Log.i(TAG, "media volume is 0: not playing " + file);
                return false;
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            try (AssetFileDescriptor fd = c.getAssets().openFd("sfx/" + file)) {
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
            mp.setVolume(vol, vol);
            long_ = mp;
            starting = true;
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            new Thread(() -> {
                try {
                    mp.prepare();
                } catch (Exception e) {
                    Log.w(TAG, "prepare failed: " + e);
                    synchronized (XmbSounds.class) {
                        if (long_ == mp) long_ = null;
                    }
                    starting = false;
                    mp.release();
                    if (onDone != null) main.post(onDone);
                    return;
                }
                synchronized (XmbSounds.class) {
                    if (long_ != mp) return;
                    try {
                        mp.start();
                    } catch (Exception e) {
                        Log.w(TAG, "start failed: " + e);
                        return;
                    }
                    starting = false;
                }
                Log.i(TAG, "playing " + file);
                if (onStarted != null) main.post(onStarted);
            }, "as3-long-sound").start();
            mp.setOnCompletionListener(m -> {
                synchronized (XmbSounds.class) {
                    if (long_ == m) long_ = null;
                }
                m.release();
                if (onDone != null) onDone.run();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "player error " + what + "/" + extra);
                synchronized (XmbSounds.class) {
                    if (long_ == m) long_ = null;
                }
                m.release();
                starting = false;
                if (onDone != null) onDone.run();
                return true;
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "playLong " + file + ": " + e);
            long_ = null;
            return false;
        }
    }

    static synchronized boolean longActive() {
        return long_ != null;
    }

    static synchronized void stopLong() {
        if (long_ != null) {
            try {
                long_.setOnCompletionListener(null);
                long_.stop();
            } catch (Exception ignored) {
            }
            long_.release();
            long_ = null;
        }
        starting = false;
    }

    static synchronized void release() {
        stopLong();
        if (pool != null) {
            pool.release();
            pool = null;
            java.util.Arrays.fill(ready, false);
        }
    }
}
