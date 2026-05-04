package com.vidking.firetv.scraper

import android.util.Log
import com.vidking.firetv.febbox.ResolvedStream
import com.vidking.firetv.wyzie.WyzieRepository

/**
 * VixSrc (vixsrc.to) — the simplest of our HTTP-only sources. One GET to
 * the embed page, regex out `token`, `expires`, and the playlist URL from
 * inline JS, append them as query parameters. The vixcloud CDN gates the
 * resulting m3u8 behind `Referer: https://vixsrc.to/`.
 */
internal object VixSrcScraper : Scraper {

    private const val TAG = "VixSrcScraper"
    private const val BASE = "https://vixsrc.to"
    private const val REFERER = "$BASE/"

    override val id: String = "vixsrc"
    override val displayName: String = "VixSrc"

    private val tokenRe = Regex("""'token':\s*'([^']+)'""")
    private val expiresRe = Regex("""'expires':\s*'?(\d+)'?""")
    private val urlRe = Regex("""url:\s*'([^']+)'""")

    override suspend fun resolve(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): ResolvedStream? {
        val pageUrl = if (mediaType == "tv") "$BASE/tv/$tmdbId/$season/$episode"
        else "$BASE/movie/$tmdbId"

        val html = ScraperHttp.getText(pageUrl, ScraperHttp.desktopHeaders(referer = REFERER))

        val token = tokenRe.find(html)?.groupValues?.getOrNull(1)
        val expires = expiresRe.find(html)?.groupValues?.getOrNull(1)
        val playlistUrl = urlRe.find(html)?.groupValues?.getOrNull(1)

        if (token == null || expires == null || playlistUrl == null) {
            Log.d(TAG, "extraction failed: token=${token != null} expires=${expires != null} url=${playlistUrl != null}")
            return null
        }

        val sep = if ('?' in playlistUrl) '&' else '?'
        val canPlayFhd = "canPlayFHD" in html
        val masterUrl = buildString {
            append(playlistUrl)
            append(sep)
            append("token=").append(token)
            append("&expires=").append(expires)
            if (canPlayFhd) append("&h=1")
        }
        Log.d(TAG, "resolved master playlist (fhd=$canPlayFhd)")

        val subs = WyzieRepository.fetch(tmdbId, mediaType, season, episode)

        return ResolvedStream(
            url = masterUrl,
            referer = REFERER,
            userAgent = ScraperHttp.DESKTOP_USER_AGENT,
            subtitles = subs,
            introStartMs = -1L,
            introEndMs = -1L
        )
    }
}
