# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Deploy to Fire TV or emulator over ADB
adb connect FIRE_TV_IP:5555          # physical device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Emulator (AVD name: firetv_avd, SDK path: C:\Users\obaid\dev-tools\android-sdk)
C:\Users\obaid\dev-tools\android-sdk\emulator\emulator.exe -avd firetv_avd -no-audio
C:\Users\obaid\dev-tools\android-sdk\platform-tools\adb.exe install -r app-debug.apk
```

No automated tests exist. Verification is manual: sideload and test on device/emulator. CI builds on every push to `main` and publishes `vidking-firetv.apk` to the `latest` GitHub release via `.github/workflows/build.yml`.

## Architecture Overview

Native Android TV / Fire TV app (Leanback UI) that browses TMDB, streams on-demand content via native Media3 ExoPlayer, and includes a Live TV row powered by M3U/IPTV playlists. WebView on Fire TV cannot access hardware decoders for HLS/DASH; the app solves this by sniffing stream URLs out of embed pages and handing them to ExoPlayer.

### Stream Resolution Pipeline (On-Demand)

`PlaybackLauncherActivity` shows a "Resolving…" screen while `StreamResolver` works through two stages:

1. **Febbox bridge** (optional, user-configured): HTTP API call via `FebboxRepository` → returns `ResolvedStream` with URL, referer, subtitle tracks, and intro markers.
2. **WebView sniffer** (fallback): `StreamSniffer` loads each provider's embed URL in a full-screen invisible WebView and intercepts `shouldInterceptRequest` for `.m3u8`, `.mpd`, or `.mp4` requests. Times out at 25 s per provider. `StreamProviders.ALL` holds the ordered list of 5 embed providers (VidSrc.to, MoviesAPI, VidLink, VidSrc.me, 2Embed.skin).

On success → `ExoPlayerActivity`. On total failure with embed fallback enabled → `EmbedPlayerActivity` (full-screen WebView iframe, last resort).

**Key sniffer constraint:** `shouldInterceptRequest` runs on a background thread — never call `WebView.getSettings()` or any other WebView method inside it. The sniffer's host `FrameLayout` must be MATCH_PARENT (not 1×1) so anti-bot viewport checks pass.

### Live TV Pipeline

Live channels bypass the sniffer entirely — the stream URL is already known from the M3U playlist. Tapping a channel goes directly to `ExoPlayerActivity`. `tmdbId = -1` and `mediaType = "live"` are passed; progress saving bails early because live streams have no finite duration.

### Data Flow

```
MainBrowseFragment (Continue Watching, Trending, Popular Movies, Popular TV, Live TV, Settings)
  ↓ tap MediaItem / WatchProgress
DetailsFragment (metadata, Play / Resume / Browse Episodes actions, season rows)
  ↓ Play / episode tap
PlaybackLauncherActivity → StreamResolver
  ├─ FebboxRepository (if configured)
  └─ StreamSniffer × StreamProviders.ALL → WyzieRepository (subtitles)
       ↓
ExoPlayerActivity (Media3, MediaSession, Room progress)
  or EmbedPlayerActivity (WebView fallback)

  ↓ tap Channel (Live TV row)
ExoPlayerActivity directly (no resolver, direct HLS URL)
```

### Key Layers

| Layer | Files | Notes |
|---|---|---|
| Browse UI | `browse/MainBrowseFragment.kt` | Rows: Continue, Trending, Movies, TV, Live TV, Settings |
| Details UI | `details/DetailsFragment.kt` | Metadata + parallel season/episode loading |
| Search | `search/SearchFragment.kt` | TMDB search with 300 ms debounce |
| Settings | `settings/SettingsFragment.kt`, `TextEntryStepFragment.kt` | Febbox config + IPTV URL |
| Stream resolution | `player/StreamResolver.kt`, `StreamSniffer.kt`, `StreamProviders.kt` | VOD pipeline |
| Native playback | `player/ExoPlayerActivity.kt` | SurfaceView, MediaSession, skip-intro, debug overlay |
| Embed fallback | `player/EmbedPlayerActivity.kt` | WebView iframe, last resort |
| Live TV | `livetv/Channel.kt`, `LiveTvRepository.kt`, `M3uParser.kt` | M3U parser + 10 built-in free channels |
| TMDB API | `tmdb/Tmdb.kt`, `TmdbApi.kt`, `TmdbModels.kt` | Retrofit singleton |
| Febbox bridge | `febbox/FebboxRepository.kt`, `FebboxApi.kt`, `ResolvedStream.kt` | Optional self-hosted proxy |
| Subtitles fallback | `wyzie/WyzieRepository.kt` | Best-effort, silent fail |
| Watch progress | `db/AppDatabase.kt`, `WatchProgress.kt`, `WatchProgressDao.kt` | Room, saved every 10 s |
| Preferences | `data/AppPrefs.kt` | Febbox URL/token, embed fallback flag, IPTV URL, crash payload |

### DetailsFragment — Season/Episode Picker

All seasons load in parallel via `async`/`awaitAll` inside `supervisorScope`. Each season becomes a `ListRow` added to `rowsAdapter` after all fetches complete. The "Browse Episodes ↓" action button scrolls to position 1 (first season row). Failures per season are logged but don't block others.

### ExoPlayerActivity Details

- Hardware SurfaceView playback with Media3
- MediaSession for Fire TV Now Playing card and hardware remote key handling
- Saves `WatchProgress` to Room every 10 s (skipped for live: duration is unset)
- Shows skip-intro button if `ResolvedStream.introMarkers` is set
- Long-press OK on remote → debug overlay (codec, bitrate, last error)
- Picture-in-picture declared in manifest

### Configuration

- TMDB API key is a `buildConfigField` in `app/build.gradle.kts`
- Febbox base URL and token: user-entered via Settings → stored in `AppPrefs`
- IPTV Playlist URL: user-entered M3U URL; when blank, `LiveTvRepository.BUILTIN_CHANNELS` is used
- Embed fallback toggle defaults to `true`

### Fire TV–Specific Constraints

- Leanback UI is required (D-Pad navigation, not touch)
- App is landscape-only
- WebView on Fire TV cannot use hardware decoders for HLS → reason for the sniffer + ExoPlayer approach
- `shouldInterceptRequest` is called on a background thread — never call WebView methods there
