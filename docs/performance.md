# Performance

XS3OS is designed to stay responsive on low-end Android TV hardware: slow CPUs, small GPUs and
1 GB of RAM.

## Rules the code follows

- **Idle draws nothing.** No perpetual animation in the default configuration.
- **Do expensive work once.** Wave art, icon masks, trimmed text and the flattened background are
  prepared once and reused.
- **Keep the UI thread free.** Image decoding, downloads, baking and (optionally) animated wave
  painting run on background threads, with generation checks to discard stale results.
- **Avoid overdraw.** No window background, one opaque background bitmap, colour-filter dimming.
- **Respect low memory.** On low-RAM devices bitmaps are smaller, the animated waves render at a
  quarter size, and screensaver pictures cut instead of cross-fading so only one is decoded.

## Numbers

Measured on a developer machine's emulator (software graphics), so treat them as relative:

| What | Result |
|---|---|
| Menu `onDraw`, warm frame | about 0.2 to 0.45 ms |
| Menu `onDraw`, first frames after launch | about 1.7 ms |
| App memory (PSS) | about 35 to 46 MB |
| APK | about 1.7 MB |
| Idle | one frame in six seconds (the clock) |

Emulator frame-time histograms are dominated by software GPU emulation and do not predict a TV.

## Measuring yourself

Time the drawing on the device you care about by wrapping `XmbMenu.onDraw` with
`System.nanoTime()` and logging an average every 30 frames, then navigate with the remote. For
system-level numbers:

```bash
adb shell dumpsys gfxinfo com.hydrainnit.as3 reset
# ...navigate...
adb shell dumpsys gfxinfo com.hydrainnit.as3 | grep -E "Total frames|50th|90th"
adb shell dumpsys meminfo com.hydrainnit.as3 | grep "TOTAL PSS"
```

## If it feels slow on your TV

1. Leave **Dynamic Waves & Particles** off (the default).
2. Use a wallpaper instead of waves, or lower the wave layers.
3. Turn on **Reduce Motion** to cut cross-fades and zoom.
4. Keep the photo quality at 1080p unless your TV renders apps at 4K.
