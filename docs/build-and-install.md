# Build and install

XS3OS builds with plain Android SDK command-line tools. There is no Gradle project.

## Prerequisites

- JDK 17 or newer (`JAVA_HOME` set, or `javac` on your path)
- Android SDK with **build-tools 34.0.0** and **platform android-34**
  (`sdkmanager "build-tools;34.0.0" "platforms;android-34"`)
- `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) pointing at the SDK; the script also tries
  `~/Library/Android/sdk` and `~/Android/Sdk`

Source is ASCII-only Java targeting Java 11 language level, minimum Android API 24.

## Build

```bash
./build.sh
```

The script compiles resources, compiles and dexes the sources, aligns the package and signs it. The
result is `build/as3.apk`. Optional environment variables:

| Variable | Meaning |
|---|---|
| `VERSION_CODE`, `VERSION_NAME` | Stamped into the manifest (defaults 1 and 1.0) |
| `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASS`, `RELEASE_KEY_ALIAS` | Sign with your own release key instead of the debug key |

Without a release key the script creates a local `debug.keystore` on first use. It is ignored by
git; never commit a signing key.

## Building with sounds and icons

The repository builds a complete, working launcher on its own (hand-drawn icons, silent interface).
To build with your own sounds and icon pack:

1. **Sounds.** Create `assets/sfx/` and copy your files in under the names listed in
   [custom-assets.md](custom-assets.md#ui-sounds) (`snd_option.wav`, `snd_system_ok.wav`,
   `snd_cancel.wav`, `snd_system_ng.wav`, `snd_trophy.wav`, `coldboot_stereo.m4a`,
   `gameboot_stereo.m4a`). Any subset works; missing ones are skipped.
2. **Icons.** Put your single-colour SVG icons in a folder, named as in the table in
   [custom-assets.md](custom-assets.md#icon-pack), then pack them:

   ```bash
   python3 -I tools/icons/pack_icons.py path/to/your-svgs assets/xmb_icons.txt
   ```

3. **Build and install.**

   ```bash
   ./build.sh
   adb install -r build/as3.apk
   ```

The new files are picked up automatically: `assets/sfx/` and `assets/xmb_icons.txt` are listed in
`.gitignore`, so they stay on your machine and out of commits. First-run plays the boot sound once,
and only when the TV's media volume is up.

## Install on a TV

```bash
adb connect 192.168.1.50:5555      # enable Developer options and network debugging on the TV
adb install -r build/as3.apk
```

Press **Home**; Android asks which launcher to use. Choose **XS3OS** and **Always**. To go back, pick
the stock launcher the same way, or uninstall with `adb uninstall com.hydrainnit.as3`.

## Run on an emulator

Create an Android TV (or Google TV) AVD with API 34, install the APK as above and press Home. On a
machine with little memory, start it with limited RAM and software graphics:

```bash
emulator -avd <name> -memory 1536 -cores 2 -gpu swiftshader_indirect -no-snapshot
```

Emulator frame statistics (`dumpsys gfxinfo`) come from software rendering and say little about a
real TV; use the in-app timing approach in [performance.md](performance.md).

## Test hooks

The home activity accepts a few extras so a screen can be opened directly:

```bash
adb shell am start -n com.hydrainnit.as3/com.hydrainnit.refract.XmbActivity \
    --es cat video                  # users, settings, photos, music, video, games, apps, network, power
adb shell "am start -n com.hydrainnit.as3/com.hydrainnit.refract.XmbActivity \
    --es cat settings --es open 'Waves'"     # open a folder by its label
adb shell am start -n ... --ez saver true    # start the screensaver
adb shell am start -n ... --es perf on       # hide the waves while measuring
```

Quote labels that contain parentheses, as above.

## Regenerating brand art

```bash
python3 -I tools/brand/make_logo.py <output-dir>     # needs Pillow
```

Writes the logo, banner and launcher icon; copy them into `res/`.
