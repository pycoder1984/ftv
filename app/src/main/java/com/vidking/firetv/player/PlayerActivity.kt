package com.vidking.firetv.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vidking.firetv.R
import com.vidking.firetv.db.AppDatabase
import com.vidking.firetv.db.WatchProgress
import kotlinx.coroutines.launch

/**
 * Full-screen WebView-based embed player. The previous "sniff m3u8 and hand
 * off to ExoPlayer" approach kept failing in 2026 because every embed encrypts
 * source URLs and injects the video element via JS in ways that bypass
 * shouldInterceptRequest.
 *
 * Now we just load the embed iframe at full screen and let the user interact
 * with the provider's own player using the Fire TV remote. D-Pad LEFT / RIGHT
 * cycles to a different provider; BACK exits.
 */
@Suppress("SetJavaScriptEnabled")
class PlayerActivity : AppCompatActivity() {

    private lateinit var rootView: FrameLayout
    private lateinit var statusLabel: TextView
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSavedAt: Long = 0L
    private val progressTicker = object : Runnable {
        override fun run() {
            saveProgress(force = false)
            mainHandler.postDelayed(this, 30_000)
        }
    }

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
        loadProvider(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveProgress(force = true)
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

        statusLabel = TextView(this).apply {
            setTextColor(0xFFE6E6E6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.topMargin = dp(20)
            lp.leftMargin = dp(20)
            layoutParams = lp
        }
        rootView.addView(statusLabel)

        setContentView(rootView)
    }

    private fun loadProvider(index: Int) {
        if (isFinishing) return
        providerIdx = ((index % providers.size) + providers.size) % providers.size
        val provider = providers[providerIdx]
        val embedUrl = provider.builder(tmdbId, mediaType, season, episode, resumeSeconds)
        Log.d(TAG, "loading ${provider.name} -> $embedUrl")

        statusLabel.text = getString(
            R.string.loader_trying, provider.name, providerIdx + 1, providers.size
        )
        statusLabel.bringToFront()

        // Tear down any prior WebView
        webView?.let {
            try {
                it.stopLoading()
                rootView.removeView(it)
                it.destroy()
            } catch (_: Exception) { }
        }

        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
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

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d(TAG, "[${provider.name}] ${msg?.message()}")
                return true
            }
        }
        wv.webViewClient = WebViewClient()

        rootView.addView(wv, 0)  // behind the status overlay
        webView = wv
        wv.requestFocus()

        wv.loadUrl(embedUrl)
    }

    override fun onStart() {
        super.onStart()
        if (resumeSeconds > 0) lastSavedAt = System.currentTimeMillis()
        mainHandler.postDelayed(progressTicker, 30_000)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressTicker)
        saveProgress(force = true)
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
        // Long-press MENU (= keycode 82) cycles providers. We avoid using
        // LEFT/RIGHT/UP/DOWN because the embed's own player may need them.
        // Most Fire TV remotes don't have MENU, so we also accept BUTTON_R1
        // (top-right button on game-controller-style inputs) as a fallback.
        if (event?.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    loadProvider(providerIdx + 1)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    loadProvider(providerIdx - 1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun saveProgress(force: Boolean) {
        // We can't read playback position from the embed, so we mark progress
        // by elapsed wall-clock time roughly. Better than nothing for the
        // Continue Watching row to surface recently-watched titles.
        val now = System.currentTimeMillis()
        if (!force && (now - lastSavedAt) < 30_000) return
        lastSavedAt = now

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
            currentTime = 0.0,
            duration = 0.0,
            progressPct = 0.1,
            updatedAt = now
        )
        lifecycleScope.launch {
            try {
                AppDatabase.get(this@PlayerActivity).watchProgressDao().upsert(record)
            } catch (t: Throwable) {
                Log.w(TAG, "progress save failed: ${t.message}")
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "PlayerActivity"

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
