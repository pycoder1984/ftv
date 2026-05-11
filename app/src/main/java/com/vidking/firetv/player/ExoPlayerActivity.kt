package com.vidking.firetv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.vidking.firetv.febbox.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Native Media3 ExoPlayer playback. Receives a fully-resolved stream URL from
 * [SourcePickerActivity] and plays it through a SurfaceView, which unlocks
 * Fire TV's hardware decoders for HLS/HEVC.
 *
 * Watch-history persistence was removed in 2026-05 per user request — playback
 * is stateless. Subtitles arrive as sidecar tracks (OpenSubtitles or a future
 * source) and are wired into the MediaItem at prepare time.
 */
@androidx.media3.common.util.UnstableApi
class ExoPlayerActivity : AppCompatActivity() {

    private lateinit var rootView: FrameLayout
    private lateinit var playerView: PlayerView
    private var errorOverlay: TextView? = null
    private var debugOverlay: TextView? = null
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var streamUrl: String = ""
    private var refererArg: String = ""
    private var userAgent: String = DESKTOP_USER_AGENT
    private var sourceLabel: String = ""

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0

    private var subtitles: List<SubtitleTrack> = emptyList()
    private var subtitlesEnabled: Boolean = true

    // Custom subtitle renderer for OpenSubtitles sidecars. We bypass
    // ExoPlayer's subtitle pipeline for these so the user can apply a
    // time offset (D-Pad Up / Down) without re-preparing the player on
    // every keypress, which would re-buffer HLS for several seconds.
    private var syncedSubsView: TextView? = null
    private var subtitleOffsetHint: TextView? = null
    private val syncedSubsRenderer: SyncedSubtitleRenderer by lazy {
        SyncedSubtitleRenderer(syncedSubsView!!)
    }
    private val offsetHintHider = Runnable {
        subtitleOffsetHint?.visibility = View.GONE
    }

    private var lastErrorMessage: String? = null
    private var debugVisible: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val debugTicker = object : Runnable {
        override fun run() {
            updateDebugOverlay()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        refererArg = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT) ?: DESKTOP_USER_AGENT
        sourceLabel = intent.getStringExtra(EXTRA_SOURCE_LABEL).orEmpty()
        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
        titleArg = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        posterPath = intent.getStringExtra(EXTRA_POSTER)
        backdropPath = intent.getStringExtra(EXTRA_BACKDROP)
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)

        @Suppress("DEPRECATION")
        subtitles = (intent.getParcelableArrayListExtra<SubtitleTrack>(EXTRA_SUBTITLES)
            ?: arrayListOf()).toList()

        buildUi()
        initPlayer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun buildUi() {
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(true)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        rootView.addView(playerView)

        errorOverlay = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(0xCC900000.toInt())
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        rootView.addView(errorOverlay)

        debugOverlay = TextView(this).apply {
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = dp(20)
            lp.rightMargin = dp(20)
            layoutParams = lp
        }
        rootView.addView(debugOverlay)

        // Custom subtitle view for OpenSubtitles sidecars. Anchored to the
        // bottom of the screen with the same default styling we apply to
        // ExoPlayer's built-in subtitleView so the two visually match.
        syncedSubsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(0x80000000.toInt())
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = dp(80)
            layoutParams = lp
        }
        rootView.addView(syncedSubsView)

        // Transient hint shown for ~2s after every Up/Down adjustment.
        subtitleOffsetHint = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(80)
            layoutParams = lp
        }
        rootView.addView(subtitleOffsetHint)

        setContentView(rootView)
    }

    private fun initPlayer() {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(userAgent)

        val headers = mutableMapOf<String, String>()
        if (refererArg.isNotEmpty()) {
            headers["Referer"] = refererArg
            headers["Origin"] = originOf(refererArg)
        }
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(
                androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
                    parameters = parameters.buildUpon()
                        .setMaxVideoSizeSd()
                        .build()
                }
            )
            .build()

        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1920, 1080)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && errorOverlay?.visibility == View.VISIBLE) {
                    errorOverlay?.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val httpDetail = describeHttpCause(error)
                val rootCause = generateSequence<Throwable>(error) { it.cause }
                    .lastOrNull()
                    ?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" }
                    .orEmpty()
                Log.e(
                    TAG,
                    "playback error: ${error.errorCodeName} ${error.message} | http=$httpDetail | rootCause=$rootCause",
                    error
                )
                lastErrorMessage = buildString {
                    append("${error.errorCodeName}\n")
                    append(error.message ?: "")
                    if (httpDetail.isNotEmpty()) append("\n\n").append(httpDetail)
                    if (rootCause.isNotEmpty() && error.message?.contains(rootCause) != true) {
                        append("\n\nCause: ").append(rootCause)
                    }
                }
                showError(
                    "Playback failed (${error.errorCodeName}).\n" +
                        "${error.message ?: "Unknown error"}\n" +
                        (if (httpDetail.isNotEmpty()) "\n$httpDetail\n" else "") +
                        "\nLong-press OK on remote for diagnostics."
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.d(TAG, "video size: ${videoSize.width}x${videoSize.height}")
            }
        })

        // Split sidecars: SRT/VTT go to our custom renderer (so offset can
        // be applied live without re-preparing the player); anything else
        // (SSA/ASS) falls through to ExoPlayer's normal subtitle pipeline.
        val (renderedBySelf, renderedByExo) = subtitles.partition {
            it.mimeType == "text/vtt" || it.mimeType == "application/x-subrip"
        }

        val subtitleConfigs = renderedByExo.mapNotNull { sub ->
            val mime = when (sub.mimeType) {
                "text/x-ssa" -> MimeTypes.TEXT_SSA
                else -> sub.mimeType
            }
            try {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(mime)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT.takeIf { sub === renderedByExo.firstOrNull() } ?: 0)
                    .build()
            } catch (t: Throwable) {
                Log.w(TAG, "skipping malformed subtitle ${sub.url}", t)
                null
            }
        }

        // Kick off the SRT/VTT fetch in the background. The first track that
        // parses successfully wins — most OpenSubtitles searches return a
        // single best candidate anyway.
        renderedBySelf.firstOrNull()?.let { sub ->
            lifecycleScope.launch(Dispatchers.IO) {
                syncedSubsRenderer.loadFromUrl(sub.url)
            }
        }

        val mimeTypeHint = guessMimeType(streamUrl)

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .apply { if (mimeTypeHint != null) setMimeType(mimeTypeHint) }
            .setSubtitleConfigurations(subtitleConfigs)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(titleArg.takeIf { it.isNotEmpty() } ?: "Now Playing")
                    .setSubtitle(
                        if (mediaType == "tv" && season > 0 && episode > 0)
                            "S${season} • E${episode}"
                        else null
                    )
                    .build()
            )
            .build()
        exo.setMediaItem(mediaItem)
        exo.prepare()
        exo.playWhenReady = true

        playerView.player = exo
        player = exo
        syncedSubsRenderer.attach(exo)
        playerView.requestFocus()

        // Subtitle styling: white text on opaque black box so captions stay
        // readable over bright scenes. setApplyEmbeddedStyles(false) discards
        // any cue-level styling that came from the SRT/VTT so our defaults win.
        playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setStyle(
                CaptionStyleCompat(
                    /* foregroundColor = */ Color.WHITE,
                    /* backgroundColor = */ 0x80000000.toInt(),
                    /* windowColor     = */ Color.TRANSPARENT,
                    /* edgeType        = */ CaptionStyleCompat.EDGE_TYPE_NONE,
                    /* edgeColor       = */ Color.BLACK,
                    /* typeface        = */ null
                )
            )
        }
        applySubtitleEnabled(subtitlesEnabled, announce = false)

        mediaSession = MediaSession.Builder(this, exo).build()
    }

    private fun applySubtitleEnabled(enabled: Boolean, announce: Boolean = true) {
        subtitlesEnabled = enabled
        val p = player ?: return
        // C.TRACK_TYPE_TEXT covers every sidecar SubtitleConfiguration we
        // attached at MediaItem-build time. Disabling the type hides them
        // without rebuilding the player.
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        playerView.subtitleView?.visibility = if (enabled) View.VISIBLE else View.GONE
        syncedSubsRenderer.setEnabled(enabled)
        if (announce) {
            Toast.makeText(
                this,
                if (enabled) "Subtitles ON" else "Subtitles OFF",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_SPACE -> {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); playerView.showController(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0))
                    playerView.showController()
                    return true
                }
                // Subtitle toggle. CAPTIONS is the standard CC key on TV
                // remotes; MENU is the Fire TV hamburger button (more reliable
                // since some Fire TV firmwares don't surface CAPTIONS).
                KeyEvent.KEYCODE_CAPTIONS,
                KeyEvent.KEYCODE_MENU -> {
                    applySubtitleEnabled(!subtitlesEnabled)
                    return true
                }
                // Subtitle sync: only intercept when the player controller
                // isn't taking focus (otherwise Up/Down navigates the bar)
                // and when our custom renderer actually has cues loaded —
                // no point shifting the offset if subtitles aren't ours.
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!playerView.isControllerFullyVisible &&
                        syncedSubsRenderer.hasLoadedCues()
                    ) {
                        adjustSubtitleOffset(+500L)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!playerView.isControllerFullyVisible &&
                        syncedSubsRenderer.hasLoadedCues()
                    ) {
                        adjustSubtitleOffset(-500L)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustSubtitleOffset(deltaMs: Long) {
        val next = syncedSubsRenderer.offsetMs() + deltaMs
        syncedSubsRenderer.setOffsetMs(next)
        val hint = subtitleOffsetHint ?: return
        val sign = if (next >= 0) "+" else ""
        hint.text = "Subtitle: $sign${"%.1f".format(next / 1000.0)} s  (▲ later  ▼ earlier)"
        hint.visibility = View.VISIBLE
        mainHandler.removeCallbacks(offsetHintHider)
        mainHandler.postDelayed(offsetHintHider, 2_000L)
    }

    private fun describeHttpCause(error: PlaybackException): String {
        var t: Throwable? = error
        while (t != null) {
            when (t) {
                is HttpDataSource.InvalidResponseCodeException ->
                    return "HTTP ${t.responseCode} on ${t.dataSpec.uri}"
                is HttpDataSource.HttpDataSourceException ->
                    return "Network: ${t.javaClass.simpleName} on ${t.dataSpec.uri}"
            }
            t = t.cause
        }
        return ""
    }

    private fun originOf(referer: String): String {
        return try {
            val u = Uri.parse(referer)
            val scheme = u.scheme ?: return referer
            val host = u.host ?: return referer
            val port = if (u.port > 0) ":${u.port}" else ""
            "$scheme://$host$port"
        } catch (_: Throwable) { referer }
    }

    private fun guessMimeType(url: String): String? {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            lower.endsWith(".m3u8") || lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    private fun showError(message: String) {
        errorOverlay?.let {
            it.text = message
            it.visibility = View.VISIBLE
        }
    }

    private fun updateDebugOverlay() {
        val overlay = debugOverlay ?: return
        if (!debugVisible) return
        val p = player ?: return
        val format = p.videoFormat
        val audio = p.audioFormat
        val text = buildString {
            append("source: $sourceLabel\n")
            append("url: ${streamUrl.take(60)}…\n")
            append("state: ${stateName(p.playbackState)}\n")
            append("playing: ${p.isPlaying}\n")
            append("pos: ${p.currentPosition / 1000}s / ${p.duration / 1000}s\n")
            append("buffered: ${p.bufferedPercentage}%\n")
            if (format != null) {
                append("video: ${format.codecs ?: format.sampleMimeType} ")
                append("${format.width}x${format.height}@${format.frameRate}fps\n")
                append("vbitrate: ${format.bitrate / 1000}kbps\n")
            }
            if (audio != null) {
                append("audio: ${audio.codecs ?: audio.sampleMimeType} ")
                append("${audio.channelCount}ch ${audio.sampleRate}Hz\n")
            }
            lastErrorMessage?.let {
                append("\nLAST ERROR:\n$it")
            }
        }
        overlay.text = text
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?"
    }

    private fun toggleDebug() {
        debugVisible = !debugVisible
        debugOverlay?.visibility = if (debugVisible) View.VISIBLE else View.GONE
        if (debugVisible) {
            mainHandler.post(debugTicker)
        } else {
            mainHandler.removeCallbacks(debugTicker)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
        ) {
            toggleDebug()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A) &&
            event?.repeatCount == 0
        ) {
            event.startTracking()
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(debugTicker)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(offsetHintHider)
        syncedSubsRenderer.detach()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExoPlayerActivity"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_SOURCE_LABEL = "source_label"
        const val EXTRA_TMDB_ID = "tmdb_id"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SUBTITLES = "subtitles"

        fun intent(
            context: Context,
            streamUrl: String,
            referer: String,
            userAgent: String,
            tmdbId: Int,
            mediaType: String,
            title: String,
            posterPath: String?,
            backdropPath: String?,
            season: Int,
            episode: Int,
            subtitles: List<SubtitleTrack> = emptyList(),
            sourceLabel: String = ""
        ): Intent = Intent(context, ExoPlayerActivity::class.java).apply {
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_REFERER, referer)
            putExtra(EXTRA_USER_AGENT, userAgent)
            putExtra(EXTRA_SOURCE_LABEL, sourceLabel)
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_MEDIA_TYPE, mediaType)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSTER, posterPath)
            putExtra(EXTRA_BACKDROP, backdropPath)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
            putParcelableArrayListExtra(EXTRA_SUBTITLES, ArrayList(subtitles))
        }
    }
}
