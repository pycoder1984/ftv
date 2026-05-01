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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.vidking.firetv.R
import com.vidking.firetv.db.AppDatabase
import com.vidking.firetv.db.WatchProgress
import com.vidking.firetv.febbox.SubtitleTrack
import kotlinx.coroutines.launch

/**
 * Native Media3 ExoPlayer playback. Fire TV's WebView can't reliably play HLS,
 * so we sniff the m3u8 from a hidden WebView (PlayerActivity) and hand it off
 * to this activity for hardware-accelerated playback with custom UA + Referer.
 */
@androidx.media3.common.util.UnstableApi
class ExoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var skipIntroButton: Button? = null
    private var player: ExoPlayer? = null

    private var streamUrl: String = ""
    private var refererArg: String = ""
    private var userAgent: String = DESKTOP_USER_AGENT

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0
    private var resumeSeconds: Long = 0L

    private var subtitles: List<SubtitleTrack> = emptyList()
    private var introStartMs: Long = -1L
    private var introEndMs: Long = -1L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSavedAt: Long = 0L
    private val progressTicker = object : Runnable {
        override fun run() {
            saveProgress(force = false)
            mainHandler.postDelayed(this, 10_000)
        }
    }
    private val introTicker = object : Runnable {
        override fun run() {
            updateSkipIntroVisibility()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        refererArg = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT) ?: DESKTOP_USER_AGENT
        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
        titleArg = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        posterPath = intent.getStringExtra(EXTRA_POSTER)
        backdropPath = intent.getStringExtra(EXTRA_BACKDROP)
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        resumeSeconds = intent.getLongExtra(EXTRA_RESUME, 0L)
        introStartMs = intent.getLongExtra(EXTRA_INTRO_START_MS, -1L)
        introEndMs = intent.getLongExtra(EXTRA_INTRO_END_MS, -1L)

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        subtitles = (intent.getParcelableArrayListExtra<SubtitleTrack>(EXTRA_SUBTITLES)
            ?: arrayListOf()).toList()

        val root = FrameLayout(this).apply {
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
        }
        root.addView(playerView)

        skipIntroButton = Button(this).apply {
            text = getString(R.string.player_skip_intro)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(0xCCE50914.toInt())
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                if (introEndMs > 0) player?.seekTo(introEndMs)
                visibility = View.GONE
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.bottomMargin = dp(40)
            lp.rightMargin = dp(40)
            layoutParams = lp
        }
        root.addView(skipIntroButton)

        setContentView(root)

        initPlayer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveProgress(force = true)
                finish()
            }
        })
    }

    private fun initPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val headers = mutableMapOf<String, String>()
        if (refererArg.isNotEmpty()) headers["Referer"] = refererArg
        headers["Origin"] = refererArg.substringBeforeLast("/", refererArg)
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveProgress(force = true, ended = true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback error: ${error.errorCodeName} ${error.message}", error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress(force = true)
            }
        })

        val subtitleConfigs = subtitles.mapNotNull { sub ->
            val mime = when (sub.mimeType) {
                "text/vtt" -> MimeTypes.TEXT_VTT
                "text/x-ssa" -> MimeTypes.TEXT_SSA
                "application/x-subrip" -> MimeTypes.APPLICATION_SUBRIP
                else -> sub.mimeType
            }
            try {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                    .setMimeType(mime)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT.takeIf { sub === subtitles.firstOrNull() } ?: 0)
                    .build()
            } catch (t: Throwable) {
                Log.w(TAG, "skipping malformed subtitle ${sub.url}", t)
                null
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
        exo.setMediaItem(mediaItem)
        exo.prepare()
        if (resumeSeconds > 0) exo.seekTo(resumeSeconds * 1000L)
        exo.playWhenReady = true

        playerView.player = exo
        player = exo
    }

    private fun updateSkipIntroVisibility() {
        val btn = skipIntroButton ?: return
        val p = player ?: return
        if (introStartMs < 0 || introEndMs <= introStartMs) {
            if (btn.visibility != View.GONE) btn.visibility = View.GONE
            return
        }
        val pos = p.currentPosition
        val show = pos in introStartMs..introEndMs
        if (show && btn.visibility != View.VISIBLE) {
            btn.visibility = View.VISIBLE
        } else if (!show && btn.visibility == View.VISIBLE) {
            btn.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        mainHandler.postDelayed(progressTicker, 10_000)
        if (introStartMs >= 0 && introEndMs > introStartMs) {
            mainHandler.post(introTicker)
        }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(introTicker)
        saveProgress(force = true)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun saveProgress(force: Boolean, ended: Boolean = false) {
        val p = player ?: return
        val durationMs = p.duration.takeIf { it > 0 } ?: return
        val positionMs = p.currentPosition.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        if (!force && (now - lastSavedAt) < 10_000) return
        lastSavedAt = now

        val durationSec = durationMs / 1000.0
        val currentSec = if (ended) durationSec else positionMs / 1000.0
        val pct = if (ended) 1.0 else (currentSec / durationSec).coerceIn(0.0, 1.0)

        val record = WatchProgress(
            key = WatchProgress.keyFor(tmdbId, mediaType, season, episode),
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = titleArg,
            posterPath = posterPath,
            backdropPath = backdropPath,
            season = season,
            episode = episode,
            episodeName = null,
            currentTime = currentSec,
            duration = durationSec,
            progressPct = pct,
            updatedAt = now
        )
        lifecycleScope.launch {
            AppDatabase.get(this@ExoPlayerActivity).watchProgressDao().upsert(record)
        }
    }

    companion object {
        private const val TAG = "ExoPlayerActivity"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_TMDB_ID = "tmdb_id"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_BACKDROP = "backdrop"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_SUBTITLES = "subtitles"
        const val EXTRA_INTRO_START_MS = "intro_start_ms"
        const val EXTRA_INTRO_END_MS = "intro_end_ms"

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
            resumeSeconds: Long,
            subtitles: List<SubtitleTrack> = emptyList(),
            introStartMs: Long = -1L,
            introEndMs: Long = -1L
        ): Intent = Intent(context, ExoPlayerActivity::class.java).apply {
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_REFERER, referer)
            putExtra(EXTRA_USER_AGENT, userAgent)
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_MEDIA_TYPE, mediaType)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSTER, posterPath)
            putExtra(EXTRA_BACKDROP, backdropPath)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
            putExtra(EXTRA_RESUME, resumeSeconds)
            putParcelableArrayListExtra(EXTRA_SUBTITLES, ArrayList(subtitles))
            putExtra(EXTRA_INTRO_START_MS, introStartMs)
            putExtra(EXTRA_INTRO_END_MS, introEndMs)
        }
    }
}
