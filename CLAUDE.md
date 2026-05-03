# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Deploy to Fire TV over ADB (replace IP with device IP)
adb connect FIRE_TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No automated tests exist. Verification is manual: sideload and test on a Fire TV device or emulator.

## Architecture Overview

This is a native Android TV / Fire TV app (Leanback UI) that browses TMDB and plays content via native Media3 ExoPlayer. The core architectural challenge is that WebView on Fire TV cannot access hardware decoders for HLS/DASH; the app solves this by sniffing stream URLs out of embed pages and handing them to ExoPlayer.

### Stream Resolution Pipeline

`PlaybackLauncherActivity` shows a "Resolving…" screen while `StreamResolver` works through two stages:

1. **Febbox bridge** (optional, user-configured): HTTP API call via `FebboxRepository` → returns `ResolvedStream` with URL, referer, subtitle tracks, and intro markers.
2. **WebView sniffer** (fallback): `StreamSniffer` loads each provider's embed URL in a hidden 1×1 WebView and intercepts `shouldInterceptRequest` for `.m3u8`, `.mpd`, or `.mp4` requests. Times out at 15 s per provider. `StreamProviders.ALL` holds the ordered list of 5 embed providers (VidSrc.to, MoviesAPI, VidLink, VidSrc.me, 2Embed.skin).

On success → `ExoPlayerActivity` (native playback). On total failure with embed fallback enabled → `EmbedPlayerActivity` (full-screen WebView iframe, last resort).

### Data Flow

```
MainBrowseFragment (Leanback rows: Continue, Trending, Popular, Settings)
  ↓ tap
DetailsFragment (metadata, episode picker) → DetailsActivity
  ↓ Play
PlaybackLauncherActivity → StreamResolver
  ├─ FebboxRepository (if configured)
  └─ StreamSniffer × StreamProviders.ALL → WyzieRepository (subtitles)
       ↓
ExoPlayerActivity (Media3, MediaSession, Room progress) or EmbedPlayerActivity (WebView)
```

### Key Layers

| Layer | Files | Notes |
|---|---|---|
| Browse UI | `browse/MainBrowseFragment.kt` | Leanback BrowseSupportFragment |
| Details UI | `details/DetailsFragment.kt` | Movie/TV metadata + episode list |
| Search | `search/SearchFragment.kt` | TMDB search with 300 ms debounce |
| Settings | `settings/SettingsFragment.kt`, `TextEntryStepFragment.kt` | GuidedStep for Febbox config |
| Stream resolution | `player/StreamResolver.kt`, `StreamSniffer.kt`, `StreamProviders.kt` | Core playback pipeline |
| Native playback | `player/ExoPlayerActivity.kt` | SurfaceView, MediaSession, skip-intro, debug overlay |
| Embed fallback | `player/EmbedPlayerActivity.kt` | WebView iframe |
| TMDB API | `tmdb/Tmdb.kt`, `TmdbApi.kt`, `TmdbModels.kt` | Retrofit singleton |
| Febbox bridge | `febbox/FebboxRepository.kt`, `FebboxApi.kt`, `ResolvedStream.kt` | Optional self-hosted proxy |
| Subtitles fallback | `wyzie/WyzieRepository.kt` | Silent fail if unavailable |
| Watch progress | `db/AppDatabase.kt`, `WatchProgress.kt`, `WatchProgressDao.kt` | Room, saved every 10 s |
| Preferences | `data/AppPrefs.kt` | Febbox URL/token, embed fallback flag, crash payload |

### ExoPlayerActivity Details

- Hardware SurfaceView playback with Media3
- MediaSession for Fire TV Now Playing card and hardware remote key handling
- Saves `WatchProgress` to Room every 10 seconds
- Shows skip-intro button if `ResolvedStream.introMarkers` is set
- Long-press OK on remote → debug overlay (codec, bitrate, last error)
- Picture-in-picture declared in manifest

### Configuration

- TMDB API key is a `buildConfigField` in `app/build.gradle.kts`
- Febbox base URL and token are user-entered at runtime via Settings (stored in `AppPrefs`)
- Embed fallback toggle defaults to `true`

### Fire TV–Specific Constraints

- Leanback UI is required (D-Pad navigation, not touch)
- App is landscape-only; no backup (`android:allowBackup="false"`)
- Leanback feature declared required in manifest
- WebView on Fire TV cannot use hardware decoders for HLS → reason for the sniffer + ExoPlayer approach
