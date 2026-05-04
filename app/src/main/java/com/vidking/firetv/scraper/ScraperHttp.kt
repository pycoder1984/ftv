package com.vidking.firetv.scraper

import com.vidking.firetv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client and convenience helpers for the scraper package. One
 * client instance for all scrapers — connection pool re-use matters since
 * each scraper makes 1-3 short requests.
 */
internal object ScraperHttp {

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    fun desktopHeaders(referer: String? = null): Map<String, String> = buildMap {
        put("User-Agent", DESKTOP_USER_AGENT)
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "en-US,en;q=0.9")
        if (referer != null) put("Referer", referer)
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = desktopHeaders()
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} on $url")
            res.body?.string().orEmpty()
        }
    }

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = desktopHeaders()
    ): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} on $url")
            res.body?.bytes() ?: ByteArray(0)
        }
    }
}
