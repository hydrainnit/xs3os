# Features

A tour of everything XS3OS does, in the order you meet it.

## First run

The first launch plays a short boot sequence (logo, then a photosensitivity notice), then opens a
setup that is itself a cross-bar. Each choice applies immediately, so you see the result behind the
menu as you go:

1. **Welcome**: begin or skip.
2. **Appearance**: dark or light, with a live preview.
3. **Colours**: pick a preset; the waves recolour behind you.
4. **Movies** *(optional)*: add a TMDB key.
5. **Photos** *(optional)*: choose a wallpaper style.
6. **Screensaver**: pick a style and delay.
7. **Finish**.

Everything can be changed later under **Settings**. The boot sound plays once, and only after audio
actually starts; with the TV muted it is skipped and tried again next time.

## The home screen

| Category | Contents |
|---|---|
| **Users** | Profiles, each with its own home and recommendations |
| **Settings** | Appearance, Waves, Particles, Sounds, Screensaver, Movies (TMDB), About |
| **Photos** | Wallpaper folders; press OK on a picture to set it |
| **Music** | Now-playing controls when notification access is allowed |
| **Video** | Continue Watching and TMDB lists (Trending, Popular, Top Rated, Coming Soon, genres) |
| **Games** | Installed games |
| **Apps** | Installed apps, inputs, presentation player |
| **Network** | Network settings, weather, voice search, refresh |
| **Power** | Sleep, sleep timer, clear memory, Do Not Disturb, system settings |

The bar at the top right shows date, time and connection. Long text is trimmed once and cached, and
a description that would run below the readable area is shortened to fit.

## Look

- **Colour presets (21 + custom gradient)**: PS3 Purple, Original Blue, Ocean, Violet, Crimson,
  Emerald, Sunset, Aurora, Monochrome, Gold, Rose, Midnight, Forest, Amber, Teal, Lime, Indigo,
  Sakura, Slate, Ember, Lavender. Each shows a swatch in the list.
- **Light and dark** appearance.
- **Waves**: amplitude, speed, opacity, layers, direction.
- **Particles**: density, size, speed, opacity, reaction to cursor movement.
- **Dynamic Waves & Particles** *(off by default)*: animate everything live instead of using the
  pre-rendered image. It costs more GPU and CPU, so it is a switch.
- **Typeface**: Inter throughout.

## Wallpapers and carousels

- **Background** is either **XMB Waves** or **Wallpaper (no waves)**. With a wallpaper, the waves
  and particles are not drawn at all; the photo is shown with a light scrim for legibility.
- **Wallpaper Carousel** changes the picture on a timer, with **Change Every** set to 10 seconds,
  30 seconds, 1 minute, 5 minutes, 15 minutes or 1 hour.
- Pictures **fade in** with an eased cross-fade; with a wallpaper expected, the screen starts from
  the plain gradient so the waves never flash first. *Reduce Motion* switches the fade to a cut.

## Movie Theme (optional)

Add a free TMDB key under **Settings → Movies (TMDB)** and **Video** fills with titles, posters,
logos, ratings and overviews. With **Movie Theme** on:

- the focused title's artwork becomes the backdrop while you browse movie lists, and the previous
  background returns when you leave;
- with the carousel on as well, the home backdrop cycles through trending movie artwork.

The key is stored encrypted on the device and can be tested or removed from the same page.

## Screensaver

Starts after your chosen idle time. Styles: **XMB Waves**, **XMB Waves (Animated)**,
**Photo Carousel**, **Movies**, **Single Photo**, **Minimal Clock**. Clock style and position are
adjustable, weather is optional, and everything drifts slowly to avoid burn-in. Picture changes use
an eased cross-fade.

## Sounds

Cursor ticks, confirm, cancel, error and completion sounds, all optional and adjustable under
**Sounds**. The sound files are not part of this repository; see
[custom assets](custom-assets.md).
