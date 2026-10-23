# Architecture

## The idea

The whole home screen is one custom `View` that draws a model: a list of categories, a stack of
lists, a selection and a few animation values. A second view sits underneath and draws only the
background. Everything else (settings, setup, screensaver) reuses the same model with different
nodes, which keeps the code small and the behaviour consistent.

## Source map (`src/com/hydrainnit/refract`)

| File | Role |
|---|---|
| `XmbActivity` | Home activity. Builds the categories and their lists, loads apps, owns wallpaper and carousel logic, idle and screensaver hand-off, test hooks |
| `XmbMenu` | The menu view and model: categories, nodes, level stack, slide and selection animation, status bar, detail panel, all drawing |
| `XmbWaves` | Background view. Static mode draws baked art and cross-fades wallpapers; dynamic mode paints animated ribbons on a worker thread and draws particles |
| `XmbBake` | Renders the wave and particle image once at display size and caches it as PNG |
| `XmbIcons` | Icon set: hand-drawn path glyphs, plus an optional packed icon file drawn through cached alpha masks |
| `XmbSettings`, `XmbSetup` | The settings tree and the first-run setup, expressed as menu nodes |
| `XmbIntro` | Boot sequence and notice screen |
| `XmbSounds` | Short effects through `SoundPool`; long boot sounds streamed through `MediaPlayer` |
| `Xmb` | Preferences, palette presets, small helpers |
| `Ambient`, `ClockView`, `RefractDream` | Screensaver, clock styles, and the system screensaver service |
| `Wallpapers`, `Movies`, `Suggestions`, `News`, `Weather` | Photo index and decoding, TMDB access and caches, recommendations, weather |
| `Secrets`, `Profiles`, `Perms`, `PowerService`, `Inputs` | Encrypted key storage, profiles, permission flows, power actions, TV inputs |
| `present/` | Presentation player and its small web server |

## Rendering model

1. `XmbMenu` posts a frame callback only while a value is still moving. At rest nothing is drawn.
2. Rows are culled to the visible window; labels and descriptions are trimmed once and cached;
   per-row values refresh at most once a second.
3. Icons are rasterised once into small alpha masks and drawn as textured quads.
4. The background is one opaque bitmap in the settled state. Wallpaper changes cross-fade between
   two bitmaps with an eased curve; the dimming for readability is a colour filter on the photo.
5. Animated waves (optional) are painted at half size on a worker thread into a back buffer and
   swapped in. Thin edge lines are built on that thread and drawn at full size on the main thread.

## Data and privacy

- Preferences live in ordinary app preferences. The TMDB key is encrypted with an Android Keystore
  AES-GCM key and shown only masked.
- Photos are downloaded on first use into the app's own storage. Movie images and metadata are cached
  with size limits and decoded off the UI thread, with a generation check so stale results are dropped.
- There are no analytics and no accounts.

## Adding a menu node

Nodes are built with small helpers (`folder`, `toggle`, `choice`, `slider`, `info`, `action`). A
settings entry is typically one line; see `XmbSettings.waves()` for examples including the colour
preset swatches.
