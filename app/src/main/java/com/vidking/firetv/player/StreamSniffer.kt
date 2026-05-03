package com.vidking.firetv.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hidden-WebView m3u8/mp4 sniffer. Loads an embed provider page off-screen,
 * watches every request through `shouldInterceptRequest`, and resolves to the
 * first stream URL it sees. This is the bridge between unscriptable third
 * party embed pages and a real ExoPlayer pipeline.
 *
 * Why this is needed on Fire TV specifically:
 *   - WebView on Fire TV (esp. Stick gen 1/2) lacks reliable hardware decoder
 *     access for HLS/HEVC — using it as the actual player drops frames or
 *     fails entirely. Sniffing extracts the underlying URL so ExoPlayer can
 *     play it through the SurfaceView pipeline instead.
 *   - We capture the User-Agent + Referer the embed used and forward them to
 *     ExoPlayer's HttpDataSource, because providers gate the CDN behind those
 *     headers.
 */
class StreamSniffer(private val context: Context) {

    data class Result(
        val url: String,
        val referer: String,
        val userAgent: String,
        val mimeType: String
    )

    /**
     * Visits [embedUrl] off-screen and waits up to [timeoutMs] for an m3u8 /
     * mpd / mp4 request to fly past. Returns null if nothing useful is seen.
     * The provided [hostView] must be attached to a window — we add the hidden
     * WebView to it as a 1×1 invisible child.
     */
    suspend fun sniff(
        embedUrl: String,
        hostView: android.view.ViewGroup,
        timeoutMs: Long = 18_000L
    ): Result? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            startSniff(embedUrl, hostView, timeoutMs, cont)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startSniff(
        embedUrl: String,
        hostView: android.view.ViewGroup,
        timeoutMs: Long,
        cont: CancellableContinuation<Result?>
    ) {
        val handler = Handler(Looper.getMainLooper())
        var resolved = false
        var webView: WebView? = null

        fun finish(result: Result?) {
            if (resolved) return
            resolved = true
            try {
                webView?.let {
                    it.stopLoading()
                    it.webViewClient = WebViewClient()
                    hostView.removeView(it)
                    it.destroy()
                }
            } catch (_: Throwable) { }
            handler.removeCallbacksAndMessages(null)
            if (cont.isActive) cont.resume(result)
        }

        cont.invokeOnCancellation { handler.post { finish(null) } }

        val client = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: return null
                val (kind, mime) = classify(urlStr) ?: return null
                Log.d(TAG, "[$kind] sniffed $urlStr")
                // Do NOT call view.settings on this thread — shouldInterceptRequest
                // runs on a background thread; getSettings() would crash.
                handler.post {
                    finish(
                        Result(
                            url = urlStr,
                            referer = embedUrl,
                            userAgent = DESKTOP_USER_AGENT,
                            mimeType = mime
                        )
                    )
                }
                return null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Single-resource errors are normal on these embed pages
                // (ad blockers, dead trackers). Don't bail.
            }
        }

        val wv = WebView(context).apply {
            visibility = View.INVISIBLE
            isFocusable = false
            isFocusableInTouchMode = false
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_USER_AGENT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = client
        webView = wv
        hostView.addView(
            wv,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        wv.loadUrl(embedUrl)

        handler.postDelayed({
            if (!resolved) {
                Log.d(TAG, "sniff timed out after ${timeoutMs}ms for $embedUrl")
                finish(null)
            }
        }, timeoutMs)
    }

    private fun classify(url: String): Pair<String, String>? {
        val lower = url.lowercase()
        // Cut query strings before extension test so "foo.m3u8?token=…" matches.
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".m3u8") || lower.contains(".m3u8") ->
                "hls" to "application/vnd.apple.mpegurl"
            path.endsWith(".mpd") || lower.contains(".mpd") ->
                "dash" to "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4v") ->
                "mp4" to "video/mp4"
            path.endsWith(".webm") ->
                "webm" to "video/webm"
            else -> null
        }
    }

    companion object {
        private const val TAG = "StreamSniffer"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
