package com.vidking.firetv.scraper

import android.util.Log
import com.vidking.firetv.febbox.ResolvedStream
import com.vidking.firetv.wyzie.WyzieRepository

/**
 * VidSrc (vidsrc.xyz family) — three-stage HTTP chain. The base domain of
 * the inner iframe (`cloudnestra.com` etc.) rotates over time, so we
 * discover it from the stage-1 iframe `src` instead of hard-coding it.
 *
 * 1. `GET https://vidsrc.xyz/embed/movie/{id}` (or `/tv/{id}/{s}-{e}`) →
 *    parse iframe src to get the BASEDOM/{rcp} URL.
 * 2. `GET <iframe src>` with Referer=vidsrc → parse `src: '/prorcp/{token}'`.
 * 3. `GET <BASEDOM>/prorcp/{token}` with Referer=<rcp> → regex out
 *    `file: '<m3u8>'`. ExoPlayer must use `Referer: <BASEDOM>/` on the
 *    m3u8 fetch or the CDN 403s.
 */
internal object VidSrcScraper : Scraper {

    private const val TAG = "VidSrcScraper"
    private const val BASE = "https://vidsrc.xyz"

    override val id: String = "vidsrc"
    override val displayName: String = "VidSrc"

    private val iframeRe = Regex(
        """<iframe[^>]+src=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val srcAttrRe = Regex("""src:\s*["']([^"']+)["']""")
    private val fileAttrRe = Regex("""file:\s*["']([^"']+)["']""")
    private val originRe = Regex("""^(https?://[^/]+)""")

    override suspend fun resolve(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): ResolvedStream? {
        val embedPath = if (mediaType == "tv") "/embed/tv/$tmdbId/$season-$episode"
        else "/embed/movie/$tmdbId"
        val embedUrl = "$BASE$embedPath"

        // Stage 1: vidsrc embed → rcp iframe
        val html1 = ScraperHttp.getText(embedUrl, ScraperHttp.desktopHeaders(referer = "$BASE/"))
        val iframeSrc = iframeRe.find(html1)?.groupValues?.getOrNull(1)?.let(::normalize)
            ?: run {
                Log.d(TAG, "stage 1: no iframe src")
                return null
            }
        val baseDom = originRe.find(iframeSrc)?.groupValues?.getOrNull(1) ?: run {
            Log.d(TAG, "stage 1: cannot extract BASEDOM from $iframeSrc")
            return null
        }

        // Stage 2: rcp page → prorcp src
        val html2 = ScraperHttp.getText(iframeSrc, ScraperHttp.desktopHeaders(referer = "$BASE/"))
        val prorcpRaw = srcAttrRe.find(html2)?.groupValues?.getOrNull(1) ?: run {
            Log.d(TAG, "stage 2: no src: attr")
            return null
        }
        val prorcpUrl = when {
            prorcpRaw.startsWith("http") -> prorcpRaw
            prorcpRaw.startsWith("//") -> "https:$prorcpRaw"
            prorcpRaw.startsWith("/") -> "$baseDom$prorcpRaw"
            else -> "$baseDom/$prorcpRaw"
        }

        // Stage 3: prorcp page → m3u8
        val html3 = ScraperHttp.getText(prorcpUrl, ScraperHttp.desktopHeaders(referer = iframeSrc))
        val m3u8 = fileAttrRe.find(html3)?.groupValues?.getOrNull(1) ?: run {
            Log.d(TAG, "stage 3: no file: attr")
            return null
        }

        val refererForPlayer = "$baseDom/"
        Log.d(TAG, "resolved m3u8 via baseDom=$baseDom")

        val subs = WyzieRepository.fetch(tmdbId, mediaType, season, episode)

        return ResolvedStream(
            url = m3u8,
            referer = refererForPlayer,
            userAgent = ScraperHttp.DESKTOP_USER_AGENT,
            subtitles = subs,
            introStartMs = -1L,
            introEndMs = -1L
        )
    }

    private fun normalize(raw: String): String = when {
        raw.startsWith("http") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$BASE$raw"
        else -> raw
    }
}
