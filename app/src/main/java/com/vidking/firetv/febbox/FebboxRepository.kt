package com.vidking.firetv.febbox

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vidking.firetv.BuildConfig
import com.vidking.firetv.data.AppPrefs
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around FebboxApi. The base URL is user-supplied (Settings), so
 * the Retrofit instance is rebuilt whenever the URL changes. Returns
 * Result.failure on any error — PlayerActivity falls through to the WebView
 * sniffer in that case, so the app still plays content when the API is down.
 */
object FebboxRepository {

    private const val TAG = "FebboxRepository"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedApi: FebboxApi? = null

    private fun apiFor(baseUrl: String): FebboxApi {
        cachedApi?.let { if (cachedBaseUrl == baseUrl) return it }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            })
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        val api = Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FebboxApi::class.java)

        cachedBaseUrl = baseUrl
        cachedApi = api
        return api
    }

    suspend fun resolve(
        context: Context,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): Result<ResolvedStream> {
        val baseUrl = AppPrefs.febboxBaseUrl(context)
        val token = AppPrefs.febboxToken(context)
        if (baseUrl.isEmpty() || token.isEmpty()) {
            return Result.failure(IllegalStateException("Febbox base URL or token not configured"))
        }

        return try {
            val api = apiFor(baseUrl)
            val cookie = "ui=$token"
            val response = if (mediaType == "tv") {
                api.resolveEpisode(tmdbId, season, episode, token, cookie)
            } else {
                api.resolveMovie(tmdbId, token, cookie)
            }

            val sources = response.sources?.filter { !it.url.isNullOrBlank() }.orEmpty()
            if (sources.isEmpty()) {
                return Result.failure(IllegalStateException("Febbox returned no sources"))
            }

            val best = sources.maxByOrNull { it.qualityRank() } ?: sources.first()
            val streamUrl = best.url!!

            val subs = response.subtitles
                ?.filter { !it.url.isNullOrBlank() }
                ?.map { sub ->
                    SubtitleTrack(
                        url = sub.url!!,
                        label = sub.bestLabel(),
                        language = sub.bestLang(),
                        mimeType = SubtitleTrack.mimeFromUrlOrFormat(sub.url, sub.format)
                    )
                }
                .orEmpty()

            val introStartMs = response.intro?.start?.let { (it * 1000).toLong() } ?: -1L
            val introEndMs = response.intro?.end?.let { (it * 1000).toLong() } ?: -1L

            val referer = response.referer?.takeIf { it.isNotBlank() }
                ?: baseUrl.trimEnd('/') + "/"

            Log.d(TAG, "resolved ${best.quality ?: "unknown"} from $baseUrl, ${subs.size} subs")
            Result.success(
                ResolvedStream(
                    url = streamUrl,
                    referer = referer,
                    userAgent = DESKTOP_USER_AGENT,
                    subtitles = subs,
                    introStartMs = introStartMs,
                    introEndMs = introEndMs
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed: ${t.javaClass.simpleName} ${t.message}")
            Result.failure(t)
        }
    }
}
