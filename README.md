# Vidking Fire TV

A native Android TV app for Fire TV Stick that browses TMDB and plays content via the Vidking embed player.

## Features

- Leanback browse UI: Trending, Popular Movies, Popular TV, Continue Watching, Settings
- TMDB search
- Details screen with episode picker for TV shows
- **Native Media3 ExoPlayer playback** — HLS / DASH / MP4 with hardware decoders
- **MediaSession** integration so Fire TV's Now Playing card and remote keys work
- **Two-stage stream resolver**: Febbox bridge (if configured) → WebView m3u8 sniffer
- **Embed fallback**: when no direct stream can be resolved, falls back to the legacy WebView iframe player (toggle in Settings)
- Subtitle support (Wyzie + Febbox), with track selection
- Local watch progress (resume where you left off) via Room
- Long-press OK on the player remote toggles a diagnostic overlay (codec, bitrate, last error)

## Why native ExoPlayer (not WebView)

Per Amazon's [Fire TV web app FAQ](https://developer.amazon.com/docs/fire-tv/web-app-faq.html), Fire TV's WebView lacks reliable hardware decoder access for HLS/HEVC/multichannel audio. Loading an embed iframe full-screen *appears* to work on a phone but stutters, drops frames, or fails entirely on a Fire TV Stick. We therefore route every "play" action through:

1. `PlaybackLauncherActivity` — shows a "Resolving…" UI while…
2. `StreamResolver` — tries a Febbox HTTP bridge first, then sniffs m3u8/mp4 from a hidden WebView visiting each embed provider in turn.
3. `ExoPlayerActivity` — plays the resolved URL via Media3 ExoPlayer through a SurfaceView, so Fire TV uses its hardware decoders.

WebView is still used (a) inside the sniffer to extract the URL, and (b) as a last-resort fallback when no direct stream can be obtained. This fallback can be disabled in Settings ("Embed fallback when scrape fails").

## How to install on your Fire TV (no Android Studio needed)

You will build the APK in the cloud with GitHub Actions, then sideload it via the Downloader app.

### 1. Push this project to GitHub

1. Create a new **public** repo on GitHub (e.g. `vidking-firetv`).
2. From this folder:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/vidking-firetv.git
   git push -u origin main
   ```
3. Go to the **Actions** tab on GitHub. The `Build APK` workflow will run automatically and produce a release named `latest`.

### 2. Prepare your Fire TV

1. **Settings → My Fire TV → About** — click "Fire TV Stick" 7 times to enable Developer Options.
2. **Settings → My Fire TV → Developer options** — turn on **Install unknown apps** (or "Apps from Unknown Sources").
3. From the Fire TV home screen, search for and install the free **Downloader** app by AFTVnews.
4. Open Downloader → Settings → enable **JavaScript in Browser**.
5. In Downloader's main URL field, type your release URL:

   ```
   https://github.com/YOUR_USERNAME/vidking-firetv/releases/download/latest/vidking-firetv.apk
   ```

   (Or paste the public APK URL from the GitHub release page.)

6. Press **Go**. The APK downloads, then tap **Install**.

### 3. Launch

The app appears in the Fire TV "Your Apps & Channels" row as **Vidking**. Open it and enjoy.

## Updating

Push a new commit to `main` — the workflow rebuilds the APK and overwrites the `latest` release. On your Fire TV, just re-run the same Downloader URL to install the new version on top.

## Configuration

The TMDB API key is baked into `app/build.gradle.kts` (`buildConfigField "TMDB_API_KEY"`). It's a free demo key — replace it with your own from [themoviedb.org](https://www.themoviedb.org/settings/api) if you want.

The Vidking embed URL is built in `PlayerActivity.buildEmbedUrl()`. Tweak the `color`, `autoPlay`, `nextEpisode`, `episodeSelector` params there.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/vidking/firetv/
│   ├── MainActivity.kt              # hosts BrowseFragment
│   ├── browse/MainBrowseFragment.kt # rows: trending / popular / continue
│   ├── details/                     # TV/movie details + episode picker
│   ├── player/PlayerActivity.kt     # WebView + Vidking iframe + JS bridge
│   ├── search/                      # SearchSupportFragment
│   ├── tmdb/                        # Retrofit + Moshi models
│   ├── db/                          # Room watch progress
│   └── presenters/CardPresenter.kt  # Leanback card UI
└── res/
```

## Local build (optional, requires Android SDK)

If you ever do install Android Studio or just the command-line tools:

```bash
./gradlew assembleDebug
adb connect FIRE_TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run on your laptop (no install)

There's also a single-file web version at `web/index.html` that mirrors the same UI. Just open it in any modern browser:

- Windows: double-click `web/index.html`, or run `start web/index.html`
- macOS: `open web/index.html`
- Linux: `xdg-open web/index.html`

Watch progress is saved in the browser's `localStorage`. No build step, no server.

## Legal

This app is a thin frontend over public TMDB metadata and the Vidking embed player. You are responsible for what you stream. The author does not host content.
