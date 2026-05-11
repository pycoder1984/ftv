# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Deploy to Fire TV or emulator over ADB
adb connect FIRE_TV_IP:5555          # physical device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Emulator (AVD name: firetv_avd, SDK path: C:\Users\obaid\dev-tools\android-sdk)
C:\Users\obaid\dev-tools\android-sdk\emulator\emulator.exe -avd firetv_avd -no-audio
```

No automated tests. Verification is manual: sideload and try a title. CI in `.github/workflows/build.yml` builds on every push to `main` and publishes `vidking-firetv.apk` to the `latest` GitHub release.

## Architecture Overview

Native Android TV / Fire TV app (Leanback UI) that browses TMDB, lets the user explicitly pick a stream source for each title, and plays the resolved HLS/MP4 through Media3 ExoPlayer. English subtitles come from OpenSubtitles (sidecar tracks attached to the MediaItem at prepare time).

The app is **stateless across launches** — no watch history, no resume, no Room DB. The `data/` package is just a thin SharedPreferences wrapper for the Febbox endpoint + token + crash payload.

### Stream Sources

The user picks one of the following per playback:

1. **Febbox** (only listed if configured in Settings) — one POST to a Vercel route that wraps the Showbox→Febbox chain. Returns the full HLS quality ladder (4K / 1080p / 720p / 360p / AUTO plus a few audio-only variants). See `febbox/FebboxRepository.kt`.
2. **WebView sniffer providers** — MoviesAPI, VidLink, Aether. Each loads its embed page in a hidden full-screen WebView; `StreamSniffer.shouldInterceptRequest` grabs the first `.m3u8` / `.mpd` / `.mp4` request and captures the *actual* Referer the WebView used (signed CDN tokens like MoviesAPI's `bx.netrocdn.site/...?srv=box` are bound to the inner-iframe origin, not the outer embed page). 25 s timeout per provider. No quality picker (the master playlist's variant selection happens later inside ExoPlayer).

### Playback Pipeline

```
DetailsFragment (TMDB metadata, Play / Browse Episodes actions)
  ↓ tap Play / episode
SourcePickerActivity (source list)
  ↓ pick provider
   ├─ Febbox path:    FebboxRepository.resolve → ResolvedStream (full ladder)
   │                  → QUALITY_LIST step → user picks variant
   └─ Sniffer path:   StreamSniffer.sniff(embedUrl) → ResolvedStream (single variant)
  ↓ subtitles fetched in parallel via OpenSubtitlesRepository
ExoPlayerActivity (Media3 SurfaceView, MediaSession, OpenSubtitles sidecar)
```

### Key Layers

| Layer | Files | Notes |
|---|---|---|
| Browse UI | `browse/MainBrowseFragment.kt` | Rows: Trending, Popular Movies, Popular TV, Settings |
| Details UI | `details/DetailsFragment.kt` | TMDB metadata + parallel season/episode loading |
| Search | `search/SearchFragment.kt` | TMDB search with 300 ms debounce |
| Settings | `settings/SettingsFragment.kt`, `TextEntryStepFragment.kt` | Febbox endpoint URL + auth token |
| Source picker | `player/SourcePickerActivity.kt` | Stage machine: SOURCE_LIST → RESOLVING → QUALITY_LIST → ExoPlayer |
| Embed providers | `player/StreamProviders.kt` | 6 embed URLs (VidSrc.to, MoviesAPI, VidLink, VidSrc.me, 2Embed.skin, Aether) |
| WebView sniffer | `player/StreamSniffer.kt` | Hidden-WebView m3u8/mp4 interceptor |
| Native playback | `player/ExoPlayerActivity.kt` | Media3, MediaSession, subtitle toggle, debug overlay |
| TMDB API | `tmdb/Tmdb.kt`, `TmdbApi.kt`, `TmdbModels.kt` | Retrofit + Moshi |
| Febbox client | `febbox/FebboxApi.kt`, `FebboxRepository.kt`, `FebboxModels.kt`, `ResolvedStream.kt` | POST JSON, parses qualities ladder |
| OpenSubtitles | `opensubtitles/OpenSubtitlesApi.kt`, `OpenSubtitlesRepository.kt`, `OpenSubtitlesModels.kt` | Anonymous search + download |
| Preferences | `data/AppPrefs.kt` | Febbox URL/token + crash payload only |

### Febbox Endpoint (current deployment)

POST `https://www.rnrvibe.com/api/siteadmin/febbox` with:
- Header `Cookie: rnrvibe_siteadmin=<SITEADMIN_TOKEN from lib/siteadmin.ts>`
- Body `{ "type": "movie"|"tv", "tmdbId": "<id>", "season"?, "episode"? }`

Returns `{ url, quality, codec, fid, mime, qualities: [{url, quality, codec, format}, ...] }`.

The upstream Febbox `ui` cookie that powers the route is set as `FEBBOX_UI_COOKIE` env var on Vercel (server-side only, never shipped to the app).

### OpenSubtitles

Base URL `https://api.opensubtitles.com/api/v1/`. Two calls per playback:

1. `GET /subtitles?tmdb_id=X[&parent_tmdb_id=X&season_number=N&episode_number=N]&languages=en` — sorted by `download_count desc`.
2. `POST /download` with `{ file_id }` — returns a temporary direct URL for the SRT/VTT.

Required headers: `Api-Key` (build-config) and a **custom User-Agent** (`Vidking FireTV v1.0`) — generic UAs get rate-limited. Anonymous quota is 5 downloads / IP / 24 h; to raise it, log in via `/login` for a JWT and pass `Authorization: Bearer <jwt>` (not wired up).

Subtitle fetch is best-effort — failures (network, no match, quota) return an empty list and playback proceeds without subs.

### ExoPlayerActivity Details

- Hardware SurfaceView playback via Media3 1.4.1
- MediaSession for Fire TV Now Playing card and hardware media keys
- Stateless: no watch history, no resume seek
- Subtitle controls: **Menu (≡)** key on Fire TV remote (also `CAPTIONS`) toggles all text tracks via `setTrackTypeDisabled(C.TRACK_TYPE_TEXT, ...)`. Style: white text on 50%-opaque black background, `setApplyEmbeddedStyles(false)` so source-defined styling can't override
- Long-press OK on remote → debug overlay (codec, bitrate, last error)

### Configuration

- TMDB API key: `buildConfigField TMDB_API_KEY` in `app/build.gradle.kts`
- OpenSubtitles API key: `buildConfigField OPENSUBTITLES_API_KEY` in `app/build.gradle.kts`
- Febbox base URL and token: user-entered via Settings → stored in `AppPrefs`

### Debug keystore is committed

`app/debug.keystore` is **intentionally committed** and wired into `signingConfigs.debug` in `app/build.gradle.kts` (storepass / keypass `android`, alias `androiddebugkey`). Reason: before pinning, every machine's auto-generated `~/.android/debug.keystore` had a different signing cert, so `adb install -r` between machines (or after CI builds) triggered `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, forcing an uninstall that wiped `SharedPreferences` (and therefore the Febbox endpoint/token). Don't replace or delete it without a migration plan for installed users — they'll lose their settings on the next update.

### Logging caveats

`FebboxRepository` uses `HttpLoggingInterceptor.Level.BASIC` (URL + status + duration). **Do not raise to `BODY`** — the request body includes the `Cookie: rnrvibe_siteadmin=<token>` header and the response body includes signed HLS URLs. Both end up in logcat where any `adb logcat` user can read them.

### Fire TV–Specific Constraints

- Leanback UI is required (D-Pad navigation, not touch)
- App is landscape-only
- WebView on Fire TV cannot use hardware decoders for HLS → reason for the sniffer + ExoPlayer approach
- `shouldInterceptRequest` is called on a background thread — never call WebView methods there
- The sniffer's host `FrameLayout` must be MATCH_PARENT (not 1×1) so anti-bot viewport checks pass

## Released APK

The `.github/workflows/build.yml` workflow builds and publishes `vidking-firetv.apk` to the `latest` GitHub release on every push to `main`. Direct download URL:

```
https://github.com/pycoder1984/ftv/releases/latest/download/vidking-firetv.apk
```
