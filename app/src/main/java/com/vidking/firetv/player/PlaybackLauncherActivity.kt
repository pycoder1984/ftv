package com.vidking.firetv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.R
import com.vidking.firetv.data.AppPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Entry point activity for "play this title". Shows a TV-friendly resolving
 * UI while [StreamResolver] tries Febbox + the embed sniffer, then routes:
 *
 *   - Resolver succeeded -> [ExoPlayerActivity] with the direct stream URL.
 *   - Resolver failed AND user has embed-fallback enabled (default true) ->
 *     [EmbedPlayerActivity] (legacy WebView iframe player).
 *   - Resolver failed AND embed fallback disabled -> error screen.
 *
 * This keeps native ExoPlayer the default on Fire TV while still giving the
 * user something playable when scraping breaks.
 */
class PlaybackLauncherActivity : AppCompatActivity() {

    private lateinit var rootView: FrameLayout
    private lateinit var hostForSniffer: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private var resolveJob: Job? = null

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0
    private var resumeSeconds: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
        titleArg = intent.getStringExtra(EXTRA_TITLE) ?: ""
        posterPath = intent.getStringExtra(EXTRA_POSTER)
        backdropPath = intent.getStringExtra(EXTRA_BACKDROP)
        season = intent.getIntExtra(EXTRA_SEASON, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        resumeSeconds = intent.getLongExtra(EXTRA_RESUME, 0L)

        buildUi()
        startResolve()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                resolveJob?.cancel()
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

        // Hidden host for the sniffer's WebView. Full-screen so the WebView
        // gets a realistic viewport — a 1×1 WebView is trivially detected by
        // Cloudflare and other anti-bot systems via window.innerWidth/height.
        hostForSniffer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
        }
        rootView.addView(hostForSniffer)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }

        titleText = TextView(this).apply {
            text = displayTitle()
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        column.addView(titleText)

        column.addView(ProgressBar(this).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.bottomMargin = dp(20)
            layoutParams = lp
        })

        statusText = TextView(this).apply {
            text = getString(R.string.loader_searching)
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        column.addView(statusText)

        hintText = TextView(this).apply {
            text = getString(R.string.launcher_hint)
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(0x33FFFFFF)
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        column.addView(hintText)

        rootView.addView(column)
        setContentView(rootView)
    }

    private fun displayTitle(): String =
        if (mediaType == "tv" && season > 0 && episode > 0) {
            "$titleArg • S${season}E${episode}"
        } else titleArg

    private fun startResolve() {
        resolveJob = lifecycleScope.launch {
            val outcome = StreamResolver.resolve(
                context = this@PlaybackLauncherActivity,
                host = hostForSniffer,
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                onProgress = { msg -> statusText.text = msg }
            )

            when (outcome) {
                is StreamResolver.Outcome.Success -> {
                    val s = outcome.stream
                    val intent = ExoPlayerActivity.intent(
                        context = this@PlaybackLauncherActivity,
                        streamUrl = s.url,
                        referer = s.referer,
                        userAgent = s.userAgent,
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        title = titleArg,
                        posterPath = posterPath,
                        backdropPath = backdropPath,
                        season = season,
                        episode = episode,
                        resumeSeconds = resumeSeconds,
                        subtitles = s.subtitles,
                        introStartMs = s.introStartMs,
                        introEndMs = s.introEndMs,
                        sourceLabel = outcome.source
                    )
                    startActivity(intent)
                    finish()
                }
                is StreamResolver.Outcome.Failure -> {
                    if (AppPrefs.embedFallbackEnabled(this@PlaybackLauncherActivity)) {
                        val intent = EmbedPlayerActivity.intent(
                            context = this@PlaybackLauncherActivity,
                            tmdbId = tmdbId,
                            mediaType = mediaType,
                            title = titleArg,
                            posterPath = posterPath,
                            backdropPath = backdropPath,
                            season = season,
                            episode = episode,
                            resumeSeconds = resumeSeconds
                        )
                        startActivity(intent)
                        finish()
                    } else {
                        showFailure(outcome)
                    }
                }
            }
        }
    }

    private fun showFailure(failure: StreamResolver.Outcome.Failure) {
        statusText.text = getString(
            R.string.launcher_failed,
            failure.attempted.joinToString(", ")
        )
        hintText.text = failure.lastError
            ?: getString(R.string.launcher_failed_hint)
    }

    override fun onDestroy() {
        resolveJob?.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_MEDIA_TYPE = "media_type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_BACKDROP = "backdrop"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_RESUME = "resume"

        fun intent(
            context: Context,
            tmdbId: Int,
            mediaType: String,
            title: String,
            posterPath: String?,
            backdropPath: String?,
            season: Int = 0,
            episode: Int = 0,
            resumeSeconds: Long = 0L
        ): Intent = Intent(context, PlaybackLauncherActivity::class.java).apply {
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_MEDIA_TYPE, mediaType)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSTER, posterPath)
            putExtra(EXTRA_BACKDROP, backdropPath)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
            putExtra(EXTRA_RESUME, resumeSeconds)
        }
    }
}
