package com.vidking.firetv.wyzie

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Wyzie subtitle search API. Mirrors the same query shape used by the web build
 * at web/index.html (key, id, season, episode, format=srt). Free tier is fine
 * for this app — same key is shared with the web client.
 */
interface WyzieApi {

    @GET("search")
    suspend fun search(
        @Query("id") tmdbId: Int,
        @Query("season") season: Int?,
        @Query("episode") episode: Int?,
        @Query("format") format: String = "srt",
        @Query("key") key: String
    ): List<WyzieSubtitle>
}

@JsonClass(generateAdapter = false)
data class WyzieSubtitle(
    val id: String? = null,
    val url: String? = null,
    val flagUrl: String? = null,
    val format: String? = null,
    val display: String? = null,
    val language: String? = null,
    val isHearingImpaired: Boolean? = null,
    val source: String? = null
)
