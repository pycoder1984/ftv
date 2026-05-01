package com.vidking.firetv.player

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.R
import com.vidking.firetv.db.AppDatabase
import com.vidking.firetv.db.WatchProgress
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var hintView: TextView

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0
    private var resumeSeconds: Long = 0L
    private var autoExternal: Boolean = false

    private var lastSavedAt: Long = 0L

    @Volatile private var capturedStreamUrl: String? = null
    @Volatile private var capturedReferer: String? = null
    private var didAutoHandoff = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
        autoExternal = intent.getBooleanExtra(EXTRA_AUTO_EXTERNAL, false)

        WebView.setWebContentsDebuggingEnabled(true)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        root.addView(webView)

        hintView = TextView(this).apply {
            text = getString(R.string.external_hint_detecting)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 16, 24, 16)
            textSize = 14f
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.START
            lp.setMargins(32, 0, 0, 32)
            layoutParams = lp
        }
        root.addView(hintView)

        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = true
            userAgentString = DESKTOP_USER_AGENT
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.BLACK)
        webView.webViewClient = StreamSniffingWebViewClient()
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

        // Auto-fade hint after 12s once it's been showing for a while
        mainHandler.postDelayed({
            if (capturedStreamUrl == null) {
                hintView.alpha = 0.55f
            }
        }, 12_000)

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
        // MENU on Fire TV remote (3-line button) — hand the captured stream off to VLC/MX
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_M) {
            launchExternalPlayer()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class StreamSniffingWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            if (url.isNotEmpty() && looksLikeVideoStream(url)) {
                if (capturedStreamUrl == null) {
                    capturedStreamUrl = url
                    capturedReferer = request?.requestHeaders?.get("Referer")
                        ?: "https://www.vidking.net/"
                    Log.d(TAG, "captured stream url: $url (referer=$capturedReferer)")
                    mainHandler.post {
                        hintView.alpha = 1f
                        hintView.text = getString(R.string.external_hint_ready)
                        if (autoExternal && !didAutoHandoff) {
                            didAutoHandoff = true
                            // Slight delay to let the URL settle before handoff
                            mainHandler.postDelayed({ launchExternalPlayer() }, 600)
                        }
                    }
                }
            }
            return null
        }
    }

    private fun looksLikeVideoStream(url: String): Boolean {
        val lower = url.lowercase()
        // HLS, DASH, common direct video file extensions, or HLS-like paths
        return lower.contains(".m3u8")
            || lower.contains(".mpd")
            || lower.endsWith(".mp4")
            || lower.endsWith(".mkv")
            || lower.endsWith(".webm")
            || lower.contains("/master.m3u8")
            || lower.contains("/playlist.m3u8")
    }

    private fun launchExternalPlayer() {
        val streamUrl = capturedStreamUrl
        if (streamUrl.isNullOrEmpty()) {
            Toast.makeText(this, R.string.external_no_stream_yet, Toast.LENGTH_LONG).show()
            return
        }

        val mime = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            streamUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            streamUrl.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            streamUrl.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            streamUrl.endsWith(".webm", ignoreCase = true) -> "video/webm"
            else -> "video/*"
        }

        val referer = capturedReferer ?: "https://www.vidking.net/"
        val title = if (mediaType == "tv" && season > 0)
            "$titleArg S${season}E${episode}"
        else titleArg

        // Build a base intent with VIEW/data/type and a pile of extras that the popular
        // Android video players (VLC, MX Player, Just Player, etc.) all read.
        fun baseIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", title)
            // VLC
            putExtra("from_start", resumeSeconds <= 0L)
            if (resumeSeconds > 0L) putExtra("position", resumeSeconds * 1000L)
            // MX Player
            putExtra("decode_mode", 1)
            putExtra("return_result", true)
            // Headers (VLC + MX honor an array of "Header: value" pairs in this extra)
            val headers = arrayOf(
                "User-Agent", DESKTOP_USER_AGENT,
                "Referer", referer
            )
            putExtra("http-headers", headers)
            putExtra("headers", headers)
            putExtra("User-Agent", DESKTOP_USER_AGENT)
            putExtra("Referer", referer)
        }

        // Try VLC first, then MX Player, then any chooser.
        val pm = packageManager
        val targets = listOf(
            VLC_PACKAGE to VLC_ACTIVITY,
            MX_PRO_PACKAGE to MX_ACTIVITY,
            MX_FREE_PACKAGE to MX_ACTIVITY,
            JUST_PLAYER_PACKAGE to null
        )

        for ((pkg, cls) in targets) {
            if (!isPackageInstalled(pm, pkg)) continue
            val direct = baseIntent().apply {
                setPackage(pkg)
                if (cls != null) component = ComponentName(pkg, cls)
            }
            try {
                startActivity(direct)
                Log.d(TAG, "launched external player: $pkg")
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "package present but activity not found: $pkg/$cls", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "security exception launching $pkg", e)
            }
        }

        // Nothing matched — show a chooser; if user has any video app it will appear.
        try {
            val chooser = Intent.createChooser(baseIntent(), "Open with")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.external_no_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun onPlayerEvent(payload: String) {
            try {
                val json = org.json.JSONObject(payload)
                val event = json.optString("event")
                val currentTime = json.optDouble("currentTime", 0.0)
                val duration = json.optDouble("duration", 0.0)
                val progress = json.optDouble("progress", 0.0)
                val epName = json.optString("episodeName", null)

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
        private const val TAG = "PlayerActivity"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // Known external player packages
        private const val VLC_PACKAGE = "org.videolan.vlc"
        private const val VLC_ACTIVITY = "org.videolan.vlc.gui.video.VideoPlayerActivity"
        private const val MX_PRO_PACKAGE = "com.mxtech.videoplayer.pro"
        private const val MX_FREE_PACKAGE = "com.mxtech.videoplayer.ad"
        private const val MX_ACTIVITY = "com.mxtech.videoplayer.ad.ActivityScreen"
        private const val JUST_PLAYER_PACKAGE = "com.brouken.player"

        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_MEDIA_TYPE = "media_type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSTER = "poster"
        private const val EXTRA_BACKDROP = "backdrop"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_AUTO_EXTERNAL = "auto_external"

        fun intent(
            context: Context,
            tmdbId: Int,
            mediaType: String,
            title: String,
            posterPath: String?,
            backdropPath: String?,
            season: Int = 0,
            episode: Int = 0,
            resumeSeconds: Long = 0L,
            autoExternal: Boolean = false
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_MEDIA_TYPE, mediaType)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSTER, posterPath)
            putExtra(EXTRA_BACKDROP, backdropPath)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
            putExtra(EXTRA_RESUME, resumeSeconds)
            putExtra(EXTRA_AUTO_EXTERNAL, autoExternal)
        }
    }
}
