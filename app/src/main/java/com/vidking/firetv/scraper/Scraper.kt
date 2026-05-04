package com.vidking.firetv.scraper

import com.vidking.firetv.febbox.ResolvedStream

/**
 * Pure-HTTP scraper that resolves a TMDB id to a [ResolvedStream]. No
 * WebView, no JS execution: each implementation makes one or more
 * `OkHttp` calls and parses the response.
 *
 * Implementations should:
 *   - Return null on any "this scraper has nothing for this id" outcome
 *     (404, missing token, decryption failure). [ScraperRegistry] handles
 *     diagnostics and falls through to the next scraper.
 *   - Throw only on programmer errors. Network exceptions are caught by
 *     the registry.
 *   - Set the correct Referer in the returned [ResolvedStream] — the CDN
 *     will 403 the manifest fetch otherwise.
 */
internal interface Scraper {
    val id: String
    val displayName: String

    suspend fun resolve(
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int
    ): ResolvedStream?
}
