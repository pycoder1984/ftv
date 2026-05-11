package com.vidking.firetv.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
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
        // Diagnostics: track every request we see and the last navigation, so
        // a timeout produces a useful logcat summary instead of just "timed
        // out". Filter to https only — base64/data: noise is huge and rarely
        // points to the real failure cause.
        val seenHosts = LinkedHashSet<String>()
        var requestCount = 0
        var lastPageUrl = embedUrl
        var lastHttpError: String? = null

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
                // Track every request host for the timeout summary. Cheap
                // bookkeeping that turns "sniff timed out" into "loaded N
                // requests across hosts X, Y, Z but no media URL".
                val host = request.url.host
                if (host != null) {
                    synchronized(seenHosts) {
                        seenHosts.add(host)
                        requestCount++
                    }
                }
                val (kind, mime) = classify(urlStr) ?: return null
                // Capture the *actual* Referer the WebView used — providers
                // like MoviesAPI sign the m3u8 token against the inner player
                // iframe origin (e.g. ww2.moviesapi.to), not the outer embed
                // page (moviesapi.club). Forwarding the outer URL gets a 403
                // when ExoPlayer re-requests the m3u8. The WebResourceRequest
                // header map is case-insensitive in practice but vendors vary,
                // so check both spellings before falling back.
                val headers = request.requestHeaders.orEmpty()
                val capturedReferer = headers["Referer"]
                    ?: headers["referer"]
                    ?: embedUrl
                Log.d(TAG, "[$kind] sniffed $urlStr | referer=$capturedReferer")
                // Do NOT call view.settings on this thread — shouldInterceptRequest
                // runs on a background thread; getSettings() would crash.
                handler.post {
                    finish(
                        Result(
                            url = urlStr,
                            referer = capturedReferer,
                            userAgent = DESKTOP_USER_AGENT,
                            mimeType = mime
                        )
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Block any non-http(s) navigation. Without this, ad scripts
                // launch the Amazon Appstore / Silk browser via intent: URLs
                // and the Fire TV remote can't get back into our app.
                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                if (scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data") {
                    Log.d(TAG, "blocked navigation to non-http url: ${request?.url}")
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Single-resource errors are normal on these embed pages
                // (ad blockers, dead trackers). Don't bail — but capture the
                // first one as a hint for the timeout summary.
                if (lastHttpError == null && request?.isForMainFrame == true) {
                    lastHttpError = "mainframe err ${error?.errorCode}: ${error?.description} @ ${request.url}"
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) lastPageUrl = url
            }
        }

        // Suppresses every visible / blocking pop-up the embed pages can
        // generate: window.open, alert/confirm/prompt dialogs, file pickers,
        // permission requests, geolocation prompts. On Fire TV's D-Pad-only
        // remote, any of these would freeze the WebView with an un-clickable
        // dialog and the user would have to force-quit the app.
        val chromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: Message?
            ): Boolean = false

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean { result?.cancel(); return true }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean { result?.cancel(); return true }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean { result?.cancel(); return true }

            override fun onJsBeforeUnload(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean { result?.confirm(); return true }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean { filePathCallback?.onReceiveValue(null); return false }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) { callback?.invoke(origin, false, false) }
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
            // Pop-up hardening for Fire TV.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // 3p cookies must be ON: providers like vidsrc.to / vidsrc.me /
            // 2embed.skin load the actual player from a different origin
            // (cloudnestra.com, etc.) via iframe. The cross-origin player
            // sets auth cookies on its own domain that the subsequent m3u8
            // request needs — those are 3rd-party from the WebView's top
            // frame perspective. Blocking them breaks the auth chain and
            // the sniffer times out with no media URL ever requested.
            setAcceptThirdPartyCookies(wv, true)
        }
        // Block any DownloadManager prompt (some embed pages link the m3u8 as
        // a download to bait users into the system download UI).
        wv.setDownloadListener { url, _, _, _, _ ->
            Log.d(TAG, "blocked download attempt: $url")
        }
        wv.webViewClient = client
        wv.webChromeClient = chromeClient
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
                val hosts = synchronized(seenHosts) { seenHosts.joinToString(",") }
                Log.w(
                    TAG,
                    "sniff TIMEOUT after ${timeoutMs}ms\n" +
                        "  embed=$embedUrl\n" +
                        "  lastPage=$lastPageUrl\n" +
                        "  requests=$requestCount across hosts: [$hosts]\n" +
                        "  mainFrameErr=${lastHttpError ?: "(none)"}"
                )
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
