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
 * Calls the rnrvibe `/api/siteadmin/febbox` route. One POST per resolution.
 * The `febbox_base_url` pref is the full endpoint URL and `febbox_token` is
 * the siteadmin cookie value.
 *
 * Returns the full quality ladder (`qualities[]`) — the SourcePicker uses it
 * to prompt the user for 4K / 1080p / 720p / 360p / AUTO. Audio-only variants
 * are kept but flagged so the quality picker hides them.
 *
 * No subtitles or intro markers (the route doesn't return them); those come
 * from OpenSubtitles instead.
 */
object FebboxRepository {

    private const val TAG = "FebboxRepository"
    private const val SITEADMIN_COOKIE = "rnrvibe_siteadmin"
    private const val FEBBOX_REFERER = "https://www.febbox.com/"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val DUMMY_BASE_URL = "https://www.rnrvibe.com/"

    private val api: FebboxApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                // BASIC = URL + status + duration. Avoid BODY here — the
                // request body includes the rnrvibe_siteadmin cookie and the
                // response body includes signed HLS URLs.
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            })
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        Retrofit.Builder()
            .baseUrl(DUMMY_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FebboxApi::class.java)
    }

    suspend fun resolve(
        context: Context,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): Result<ResolvedStream> {
        val endpoint = AppPrefs.febboxBaseUrl(context)
        val token = AppPrefs.febboxToken(context)
        if (endpoint.isEmpty() || token.isEmpty()) {
            return Result.failure(IllegalStateException("Febbox endpoint or token not configured"))
        }

        val isTv = mediaType == "tv"
        val body = FebboxResolveRequest(
            type = if (isTv) "tv" else "movie",
            tmdbId = tmdbId.toString(),
            season = if (isTv) season else null,
            episode = if (isTv) episode else null
        )

        return try {
            val response = api.resolve(
                url = endpoint,
                cookie = "$SITEADMIN_COOKIE=$token",
                body = body
            )

            // Prefer the full qualities ladder when present. Falls back to the
            // top-level url if the response is unexpectedly stripped down.
            val ladder = response.qualities
                ?.mapNotNull { q ->
                    val url = q.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val quality = q.quality?.trim().orEmpty()
                    StreamVariant(
                        url = url,
                        quality = quality.ifEmpty { "AUTO" },
                        codec = q.codec.orEmpty(),
                        isAudioOnly = quality.startsWith("audio", ignoreCase = true)
                    )
                }
                .orEmpty()

            val variants = if (ladder.isNotEmpty()) {
                ladder.sortedByDescending { it.qualityRank() }
            } else {
                val url = response.url
                if (url.isNullOrBlank()) {
                    val why = response.error ?: "no url in response"
                    return Result.failure(IllegalStateException("Febbox: $why"))
                }
                listOf(
                    StreamVariant(
                        url = url,
                        quality = response.quality?.ifBlank { "AUTO" } ?: "AUTO",
                        codec = response.codec.orEmpty()
                    )
                )
            }

            Log.d(
                TAG,
                "resolved ${variants.size} variants from $endpoint (video=${variants.count { !it.isAudioOnly }})"
            )
            Result.success(
                ResolvedStream(
                    variants = variants,
                    referer = FEBBOX_REFERER,
                    userAgent = DESKTOP_USER_AGENT,
                    subtitles = emptyList()
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed: ${t.javaClass.simpleName} ${t.message}")
            Result.failure(t)
        }
    }
}
