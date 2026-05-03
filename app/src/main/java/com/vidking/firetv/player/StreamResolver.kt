package com.vidking.firetv.player

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.vidking.firetv.data.AppPrefs
import com.vidking.firetv.febbox.FebboxRepository
import com.vidking.firetv.febbox.ResolvedStream
import com.vidking.firetv.febbox.SubtitleTrack
import com.vidking.firetv.wyzie.WyzieRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two-stage stream resolver. Returns a fully-formed [ResolvedStream] that
 * [ExoPlayerActivity] can play immediately.
 *
 * Stage 1: Febbox bridge (if configured in Settings). One HTTP call,
 *          returns direct CDN URLs + subtitle list + intro markers.
 *
 * Stage 2: WebView sniffer per embed provider in [StreamProviders.ALL]. We
 *          load each embed off-screen and intercept the first .m3u8 / .mpd /
 *          .mp4 request that flies past. Subtitles for sniffed streams are
 *          fetched from Wyzie as a best-effort overlay.
 *
 * The launcher activity provides a [ViewGroup] for hosting the hidden
 * WebView so we don't construct a stray WindowManager-attached view.
 */
object StreamResolver {

    private const val TAG = "StreamResolver"
    private const val PER_PROVIDER_TIMEOUT_MS = 25_000L

    sealed class Outcome {
        data class Success(val stream: ResolvedStream, val source: String) : Outcome()
        data class Failure(val attempted: List<String>, val lastError: String?) : Outcome()
    }

    suspend fun resolve(
        context: Context,
        host: ViewGroup,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int,
        onProgress: (String) -> Unit
    ): Outcome {
        val attempted = mutableListOf<String>()
        var lastError: String? = null

        // Stage 1: Febbox.
        if (AppPrefs.hasFebboxConfig(context)) {
            attempted += "Febbox"
            onProgress("Resolving via Febbox…")
            val result = FebboxRepository.resolve(context, tmdbId, mediaType, season, episode)
            result.fold(
                onSuccess = { stream ->
                    Log.d(TAG, "Febbox resolved")
                    return Outcome.Success(stream, "Febbox")
                },
                onFailure = { e ->
                    Log.w(TAG, "Febbox failed: ${e.message}")
                    lastError = e.message ?: e.javaClass.simpleName
                }
            )
        }

        // Stage 2: WebView sniff each provider in turn.
        val sniffer = StreamSniffer(context)
        for ((index, provider) in StreamProviders.ALL.withIndex()) {
            val embedUrl = provider.builder(tmdbId, mediaType, season, episode, 0L)
            attempted += provider.name
            onProgress("Probing ${provider.name} (${index + 1}/${StreamProviders.ALL.size})…")
            Log.d(TAG, "sniffing ${provider.name} -> $embedUrl")

            val sniffed = withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                sniffer.sniff(embedUrl, host, timeoutMs = PER_PROVIDER_TIMEOUT_MS - 1_000)
            }

            if (sniffed != null) {
                Log.d(TAG, "${provider.name} yielded ${sniffed.url}")
                onProgress("Got stream from ${provider.name} — preparing player…")

                val subs: List<SubtitleTrack> = try {
                    WyzieRepository.fetch(tmdbId, mediaType, season, episode)
                } catch (t: Throwable) {
                    Log.w(TAG, "Wyzie fetch failed: ${t.message}")
                    emptyList()
                }

                return Outcome.Success(
                    stream = ResolvedStream(
                        url = sniffed.url,
                        referer = sniffed.referer,
                        userAgent = sniffed.userAgent,
                        subtitles = subs,
                        introStartMs = -1L,
                        introEndMs = -1L
                    ),
                    source = provider.name
                )
            } else {
                lastError = "${provider.name} timed out"
                Log.w(TAG, lastError!!)
            }
        }

        return Outcome.Failure(attempted, lastError)
    }
}
