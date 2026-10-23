# Custom assets: sounds and icons

**Quick path:** put your sound files in `assets/sfx/`, run the icon packer on your SVG folder, then
run `./build.sh`. Details for each step are below; a full walkthrough is in
[build-and-install.md](build-and-install.md#building-with-sounds-and-icons).

Two kinds of files are intentionally **not** in this repository because they belong to other people:
a PS3-style icon set and the system sounds. The launcher runs without them (hand-drawn icons, silent
interface); add your own to get the full effect.

## UI sounds

Put files in `assets/sfx/` before building. Names the code looks for:

| File | Plays when |
|---|---|
| `snd_option.wav` | Cursor moves (rate-limited; category moves play it lower) |
| `snd_system_ok.wav` | Confirm |
| `snd_cancel.wav` | Back or cancel |
| `snd_system_ng.wav` | Error |
| `snd_trophy.wav` | Completion |
| `coldboot_stereo.m4a` | Once, during first-run setup |
| `gameboot_stereo.m4a` | When a game starts |

Short effects are loaded into a sound pool in the background; the two long ones stream. Missing
files are skipped quietly. Use sounds you have the right to use.

## Icon pack

The packer turns any folder of single-colour SVG icons (absolute `M L H V C Z` path commands) into
the compact file the app reads:

```bash
python3 -I tools/icons/pack_icons.py <svg-folder> assets/xmb_icons.txt
```

The app looks icons up by the SVG file name (without `.svg`). Icons without a match keep the
hand-drawn glyph.

| Slot | File name expected |
|---|---|
| Users | `user` |
| Settings | `Layer 32` |
| Photos | `Layer 40` |
| Music | `music` |
| Video | `Layer 39` |
| Games | `game` |
| Network | `web` |
| Power | `power` |
| Apps | `suste, actovatopm` |
| Folder | `folder` |
| Sound | `Layer 8` |
| Key | `Layer 9` |
| Clock | `Layer 28` |
| Search | `Layer 36` |
| Voice | `headset` |
| Refresh | `Layer 19 copy` |
| TV / screensaver | `Layer 5` |
| Trophy | `trophy` |
| New user | `add user` |

To use differently named files, rename them to match or edit the table at the top of `XmbIcons.java`.
Make sure you have the right to use any files you add.
