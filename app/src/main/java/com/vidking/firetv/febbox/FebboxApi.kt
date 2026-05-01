package com.vidking.firetv.febbox

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Showbox/Febbox bridge. The deployment URL and
 * token are user-supplied via Settings. Two conventions are supported in
 * parallel: token as a `?token=` query param (default for showbox-febbox-api)
 * and as a `Cookie: ui=<token>` header (raw Febbox file API). The Repository
 * sends both so either deployment style works.
 */
interface FebboxApi {

    @GET("api/movie/{tmdbId}")
    suspend fun resolveMovie(
        @Path("tmdbId") tmdbId: Int,
        @Query("token") token: String,
        @Header("Cookie") cookie: String
    ): FebboxResolveResponse

    @GET("api/tv/{tmdbId}/{season}/{episode}")
    suspend fun resolveEpisode(
        @Path("tmdbId") tmdbId: Int,
        @Path("season") season: Int,
        @Path("episode") episode: Int,
        @Query("token") token: String,
        @Header("Cookie") cookie: String
    ): FebboxResolveResponse
}
