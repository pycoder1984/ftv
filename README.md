# Vidking Fire TV

A native Android TV / Fire TV app that browses TMDB and plays movies and TV via your choice of stream source, with English subtitles from OpenSubtitles.

## Download

Latest APK is built automatically by GitHub Actions on every push to `main`:

**[Download `vidking-firetv.apk`](https://github.com/pycoder1984/ftv/releases/latest/download/vidking-firetv.apk)**

## Features

- Leanback browse UI: **Trending**, **Popular Movies**, **Popular TV**, **Settings**
- TMDB search (D-Pad navigable, with the standard Leanback search orb)
- Details screen with parallel-loaded season / episode picker for TV shows
- **Explicit source picker.** When you hit Play you choose which provider to resolve through — Febbox first if configured, then MoviesAPI / VidLink / Aether (all WebView-sniffer-based)
- **Quality picker** for Febbox streams (4K / 1080p / 720p / 360p / AUTO)
- **Native Media3 ExoPlayer playback** — HLS / DASH / MP4 with Fire TV's hardware decoders via SurfaceView
- **MediaSession** integration — Now Playing card and remote media keys work
- **Subtitles from OpenSubtitles** — auto-fetched in parallel with the source resolve, white text on 50%-opaque black background
- **Subtitle on/off toggle** — press the **Menu (≡)** button (or `CAPTIONS` key) on the remote during playback
- Long-press OK on the remote during playback → diagnostic overlay (codec, bitrate, resolution, last error)

## Why native ExoPlayer (not WebView)

Per Amazon's [Fire TV web app FAQ](https://developer.amazon.com/docs/fire-tv/web-app-faq.html), Fire TV's WebView can't reliably access hardware decoders for HLS / HEVC / multichannel audio — loading an embed iframe full-screen drops frames or fails outright on a Fire TV Stick. So every Play action goes through:

1. **`SourcePickerActivity`** — you pick which provider to play from.
2. Source resolution:
   - **Febbox** path → one HTTP POST to your `/api/siteadmin/febbox` route, returns the full HLS quality ladder.
   - **Sniffer** path → loads the chosen embed provider in a hidden full-screen WebView and intercepts the first `.m3u8` / `.mpd` / `.mp4` request that flies past.
3. **`ExoPlayerActivity`** — plays the resolved URL through a SurfaceView so Fire TV uses its hardware decoders.

WebView is still used inside the sniffer (to extract URLs from embed pages) — but not as the actual player.

## How to install on your Fire TV (no Android Studio needed)

### 1. Prepare your Fire TV

1. **Settings → My Fire TV → About** — click "Fire TV Stick" 7 times to enable Developer Options.
2. **Settings → My Fire TV → Developer options** — turn on **Install unknown apps** (or "Apps from Unknown Sources").
3. From the Fire TV home screen, search for and install the free **Downloader** app by AFTVnews.
4. Open Downloader → Settings → enable **JavaScript in Browser**.

### 2. Sideload the APK

In Downloader's URL field, type:

```
https://github.com/pycoder1984/ftv/releases/latest/download/vidking-firetv.apk
```

Press **Go**, wait for the download, then tap **Install**.

The app appears in the Fire TV "Your Apps & Channels" row as **Vidking**.

### 3. Update later

Push a new commit to `main` — CI rebuilds and overwrites the `latest` release. On the Fire TV, just re-run the same Downloader URL.

## Configuration

Settings → Febbox endpoint URL + Siteadmin auth token enable the Febbox source. Without them, only the WebView-sniffer providers are listed.

- **Febbox endpoint URL** — full URL of your bridge route. Default deployment lives at `https://www.rnrvibe.com/api/siteadmin/febbox` (see `c:/projects/rnrvibe/app/api/siteadmin/febbox/route.ts`).
- **Siteadmin auth token** — value of the `rnrvibe_siteadmin` cookie from `lib/siteadmin.ts`. The default is `rnrvibe-siteadmin-c4f7a2e9b6d3a1f80d2e9b4c6a8e1f7d-2026`; override via `SITEADMIN_TOKEN` env var on Vercel.

API keys are baked in as `buildConfigField` entries in `app/build.gradle.kts`:
- `TMDB_API_KEY` — for TMDB metadata
- `OPENSUBTITLES_API_KEY` — for OpenSubtitles subtitle fetch

OpenSubtitles **anonymous mode** is limited to **5 downloads / IP / 24 h**. To raise this, log in via `POST /api/v1/login` and pass the JWT as `Authorization: Bearer <token>` (not wired up by default).

## Subtitle controls (during playback)

| Action | Key |
|---|---|
| Toggle subtitles on / off | **Menu (≡)** button on Fire TV remote, or `CAPTIONS` |
| Diagnostic overlay | Long-press OK / DPAD_CENTER |
| Seek ±10 s | RW / FF (or DPAD_LEFT / DPAD_RIGHT) |
| Play / pause | Play/Pause button, or SPACE on emulator |

## Project layout

```
app/src/main/java/com/vidking/firetv/
├── MainActivity.kt              # hosts MainBrowseFragment
├── App.kt                       # global crash handler -> AppPrefs
├── browse/MainBrowseFragment.kt # Trending / Popular / Settings rows
├── details/                     # TV/movie details + episode picker
├── search/                      # Leanback SearchSupportFragment
├── settings/                    # Febbox endpoint + token (GuidedStep UI)
├── tmdb/                        # Retrofit + Moshi TMDB client
├── febbox/                      # POST /api/siteadmin/febbox client + models
├── opensubtitles/               # OpenSubtitles search + download
├── player/
│   ├── SourcePickerActivity.kt  # source list -> (optional) quality list
│   ├── ExoPlayerActivity.kt     # Media3 ExoPlayer playback
│   ├── StreamSniffer.kt         # hidden-WebView m3u8 sniffer
│   └── StreamProviders.kt       # the 6 embed providers
├── data/AppPrefs.kt             # SharedPreferences (Febbox cfg, crash payload)
└── presenters/CardPresenter.kt  # Leanback card UI
```

## Local build (requires Android SDK)

```bash
./gradlew assembleDebug
adb connect FIRE_TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Legal

This app is a thin frontend over public TMDB metadata and third-party stream providers. The author does not host content. You are responsible for what you stream.
