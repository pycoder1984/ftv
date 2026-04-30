package com.vidking.firetv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.db.AppDatabase
import com.vidking.firetv.db.WatchProgress
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0
    private var resumeSeconds: Long = 0L

    private var lastSavedAt: Long = 0L

    @Suppress("SetJavaScriptEnabled")
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

        webView = WebView(this)
        webView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = true
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(JsBridge(), "VidkingNative")

        webView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        webView.loadDataWithBaseURL(
            "https://www.vidking.net",
            buildHtml(),
            "text/html",
            "utf-8",
            null
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun buildEmbedUrl(): String {
        val base = if (mediaType == "movie")
            "https://www.vidking.net/embed/movie/$tmdbId"
        else
            "https://www.vidking.net/embed/tv/$tmdbId/$season/$episode"

        val params = mutableListOf(
            "color=e50914",
            "autoPlay=true"
        )
        if (mediaType == "tv") {
            params += "nextEpisode=true"
            params += "episodeSelector=true"
        }
        if (resumeSeconds > 0) params += "progress=$resumeSeconds"
        return "$base?${params.joinToString("&")}"
    }

    private fun buildHtml(): String {
        val embedUrl = buildEmbedUrl()
        return """
        <!doctype html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>
                html,body { margin:0; padding:0; height:100%; background:#000; overflow:hidden; }
                #frame { width:100%; height:100%; border:0; display:block; }
            </style>
        </head>
        <body>
            <iframe id="frame"
                src="$embedUrl"
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                allowfullscreen
                referrerpolicy="origin"></iframe>
            <script>
                window.addEventListener('message', function (e) {
                    try {
                        var msg = e.data;
                        if (!msg) return;
                        if (typeof msg === 'string') {
                            try { msg = JSON.parse(msg); } catch (err) { return; }
                        }
                        if (msg && msg.type === 'PLAYER_EVENT' && msg.data) {
                            VidkingNative.onPlayerEvent(JSON.stringify(msg.data));
                        }
                    } catch (err) { /* swallow */ }
                });
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Let WebView handle remote keys (D-pad, play/pause, etc.)
        return super.onKeyDown(keyCode, event)
    }

    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun onPlayerEvent(payload: String) {
            // payload is JSON: { event, currentTime, duration, progress, id, mediaType, season, episode, timestamp }
            try {
                val json = org.json.JSONObject(payload)
                val event = json.optString("event")
                val currentTime = json.optDouble("currentTime", 0.0)
                val duration = json.optDouble("duration", 0.0)
                val progress = json.optDouble("progress", 0.0)
                val epName = json.optString("episodeName", null)

                // Throttle saves: every 10s for timeupdate; always save on pause/seeked/ended
                val now = System.currentTimeMillis()
                val shouldSave = when (event) {
                    "timeupdate" -> (now - lastSavedAt) > 10_000
                    "play", "pause", "seeked", "ended" -> true
                    else -> false
                }
                if (!shouldSave) return
                if (duration <= 0) return
                lastSavedAt = now
                val pct = if (event == "ended") 1.0 else (progress / 100.0).coerceIn(0.0, 1.0)

                val record = WatchProgress(
                    key = WatchProgress.keyFor(tmdbId, mediaType, season, episode),
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    title = titleArg,
                    posterPath = posterPath,
                    backdropPath = backdropPath,
                    season = season,
                    episode = episode,
                    episodeName = epName,
                    currentTime = if (event == "ended") duration else currentTime,
                    duration = duration,
                    progressPct = pct,
                    updatedAt = now
                )

                lifecycleScope.launch {
                    AppDatabase.get(this@PlayerActivity).watchProgressDao().upsert(record)
                }
            } catch (_: Exception) { /* ignore parsing errors */ }
        }
    }

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
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
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
