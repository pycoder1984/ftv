package com.vidking.firetv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.vidking.firetv.R
import com.vidking.firetv.tmdb.Tmdb

/**
 * Stream loader. Loads each embed provider in a hidden WebView, sniffs the
 * m3u8/mpd URL, and hands off to [ExoPlayerActivity] for native playback.
 *
 * Fire TV's WebView is unreliable for HLS playback even when the embed loads;
 * this lets us reuse the providers' source-resolving JS while playing the
 * actual stream natively with hardware acceleration.
 *
 * Remote shortcuts:
 *   D-pad LEFT  — try previous provider
 *   D-pad RIGHT — try next provider
 *   BACK        — cancel and return
 */
@Suppress("SetJavaScriptEnabled")
class PlayerActivity : AppCompatActivity() {

    private lateinit var rootView: FrameLayout
    private lateinit var backdropView: ImageView
    private lateinit var titleLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var hintLabel: TextView
    private var webView: WebView? = null

    private var tmdbId: Int = -1
    private var mediaType: String = "movie"
    private var titleArg: String = ""
    private var posterPath: String? = null
    private var backdropPath: String? = null
    private var season: Int = 0
    private var episode: Int = 0
    private var resumeSeconds: Long = 0L

    private var providerIdx: Int = 0
    private val providers = StreamProviders.ALL
    @Volatile private var capturedStreamUrl: String? = null
    @Volatile private var capturedReferer: String? = null
    private var didHandoff: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var providerTimeout: Runnable? = null

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

        WebView.setWebContentsDebuggingEnabled(true)

        buildUi()
        loadBackdrop()
        startProvider(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
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

        backdropView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.35f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(backdropView)

        // Dim overlay
        val dim = View(this).apply {
            background = ColorDrawable(0xCC000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(dim)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(64), dp(64), dp(64))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }

        titleLabel = TextView(this).apply {
            text = titleArg + if (mediaType == "tv" && season > 0) "  •  S${season}E${episode}" else ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }
        column.addView(titleLabel)

        statusLabel = TextView(this).apply {
            text = getString(R.string.loader_searching)
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(20)
            layoutParams = lp
        }
        column.addView(statusLabel)

        hintLabel = TextView(this).apply {
            text = getString(R.string.loader_hint)
            setTextColor(0xFFAAAAAA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(28)
            layoutParams = lp
        }
        column.addView(hintLabel)

        rootView.addView(column)

        setContentView(rootView)
    }

    private fun loadBackdrop() {
        val url = Tmdb.backdropUrl(backdropPath) ?: Tmdb.posterUrl(posterPath, "w780")
        if (url != null) {
            Glide.with(this).load(url).into(backdropView)
        }
    }

    private fun startProvider(index: Int) {
        if (didHandoff || isFinishing) return

        providerIdx = ((index % providers.size) + providers.size) % providers.size
        val provider = providers[providerIdx]
        capturedStreamUrl = null
        capturedReferer = null

        val embedUrl = provider.builder(tmdbId, mediaType, season, episode, resumeSeconds)
        Log.d(TAG, "trying provider ${provider.name} -> $embedUrl")

        statusLabel.text = getString(
            R.string.loader_trying,
            provider.name,
            providerIdx + 1,
            providers.size
        )

        // Tear down any prior WebView
        webView?.let {
            try {
                it.stopLoading()
                rootView.removeView(it)
                it.destroy()
            } catch (_: Exception) { }
        }

        val wv = WebView(this).apply {
            // 1px invisible — we just need it to execute the embed JS
            layoutParams = FrameLayout.LayoutParams(1, 1)
            visibility = View.INVISIBLE
        }

        wv.settings.apply {
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
        cookies.setAcceptThirdPartyCookies(wv, true)

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = StreamSniffingWebViewClient(provider)

        rootView.addView(wv)
        webView = wv

        wv.loadDataWithBaseURL(
            provider.baseUrl,
            buildHtml(embedUrl),
            "text/html",
            "utf-8",
            null
        )

        // Per-provider timeout
        providerTimeout?.let { mainHandler.removeCallbacks(it) }
        providerTimeout = Runnable {
            if (didHandoff || isFinishing) return@Runnable
            if (capturedStreamUrl == null) {
                Log.w(TAG, "${provider.name} timed out, advancing")
                statusLabel.text = getString(R.string.loader_timeout, provider.name)
                mainHandler.postDelayed({ startProvider(providerIdx + 1) }, 600)
            }
        }
        mainHandler.postDelayed(providerTimeout!!, PROVIDER_TIMEOUT_MS)
    }

    private fun buildHtml(embedUrl: String): String = """
        <!doctype html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <style>html,body{margin:0;padding:0;background:#000;}</style>
        </head>
        <body>
            <iframe id="frame" src="$embedUrl"
                allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                allowfullscreen referrerpolicy="origin"
                style="width:1280px;height:720px;border:0;"></iframe>
        </body>
        </html>
    """.trimIndent()

    private inner class StreamSniffingWebViewClient(
        private val provider: StreamProvider
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            if (url.isNotEmpty() && looksLikeVideoStream(url) && capturedStreamUrl == null) {
                capturedStreamUrl = url
                capturedReferer = request?.requestHeaders?.get("Referer") ?: provider.baseUrl + "/"
                Log.d(TAG, "captured from ${provider.name}: $url")
                mainHandler.post { handoffToExoPlayer() }
            }
            return null
        }
    }

    private fun looksLikeVideoStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8")
            || lower.contains(".mpd")
            || lower.endsWith(".mp4")
            || lower.endsWith(".mkv")
            || lower.endsWith(".webm")
            || lower.contains("/master.m3u8")
            || lower.contains("/playlist.m3u8")
    }

    private fun handoffToExoPlayer() {
        if (didHandoff || isFinishing) return
        val url = capturedStreamUrl ?: return
        val referer = capturedReferer ?: providers[providerIdx].baseUrl + "/"
        didHandoff = true

        providerTimeout?.let { mainHandler.removeCallbacks(it) }
        statusLabel.text = getString(R.string.loader_starting, providers[providerIdx].name)

        val intent = ExoPlayerActivity.intent(
            this,
            streamUrl = url,
            referer = referer,
            userAgent = DESKTOP_USER_AGENT,
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
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        providerTimeout?.let { mainHandler.removeCallbacks(it) }
        webView?.let {
            try {
                it.stopLoading()
                rootView.removeView(it)
                it.destroy()
            } catch (_: Exception) { }
        }
        webView = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                providerTimeout?.let { mainHandler.removeCallbacks(it) }
                startProvider(providerIdx + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                providerTimeout?.let { mainHandler.removeCallbacks(it) }
                startProvider(providerIdx - 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "PlayerActivity"
        private const val PROVIDER_TIMEOUT_MS = 18_000L

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
