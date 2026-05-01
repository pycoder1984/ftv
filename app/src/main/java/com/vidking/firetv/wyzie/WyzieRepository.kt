package com.vidking.firetv.wyzie

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vidking.firetv.BuildConfig
import com.vidking.firetv.febbox.SubtitleTrack
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Fallback subtitle source. Used when Febbox returns no subs (or the user has
 * Febbox disabled) — same shared Wyzie key as the web build at
 * web/index.html. Failures resolve to an empty list so playback still starts.
 */
object WyzieRepository {

    private const val TAG = "WyzieRepository"
    private const val BASE_URL = "https://sub.wyzie.io/"
    private const val KEY = "wyzie-a43d54e1262d574cf0a2dc64d2cff52c"

    private val api: WyzieApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            })
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WyzieApi::class.java)
    }

    suspend fun fetch(tmdbId: Int, mediaType: String, season: Int, episode: Int): List<SubtitleTrack> {
        return try {
            val list = if (mediaType == "tv") {
                api.search(tmdbId, season, episode, "srt", KEY)
            } else {
                api.search(tmdbId, null, null, "srt", KEY)
            }
            list.asSequence()
                .filter { !it.url.isNullOrBlank() }
                .distinctBy { (it.language ?: "") + "|" + (it.display ?: "") }
                .take(30)
                .map { sub ->
                    SubtitleTrack(
                        url = sub.url!!,
                        label = sub.display ?: sub.language ?: "Subtitle",
                        language = sub.language ?: "und",
                        mimeType = SubtitleTrack.mimeFromUrlOrFormat(sub.url, sub.format)
                    )
                }
                .toList()
        } catch (t: Throwable) {
            Log.w(TAG, "Wyzie fetch failed: ${t.javaClass.simpleName} ${t.message}")
            emptyList()
        }
    }
}
