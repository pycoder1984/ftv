# Vidking Fire TV

A native Android TV app for Fire TV Stick that browses TMDB and plays content via the Vidking embed player.

## Features

- Leanback browse UI: Trending, Popular Movies, Popular TV, Continue Watching
- TMDB search
- Details screen with episode picker for TV shows
- Full-screen WebView player loading the Vidking iframe
- Local watch progress (resume where you left off) via Room

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
