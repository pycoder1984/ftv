package com.vidking.firetv.tmdb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("trending/all/week")
    suspend fun trending(@Query("api_key") apiKey: String): PageResult<MediaItem>

    @GET("movie/popular")
    suspend fun popularMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): PageResult<MediaItem>

    @GET("tv/popular")
    suspend fun popularTv(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): PageResult<MediaItem>

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("include_adult") includeAdult: Boolean = false
    ): PageResult<MediaItem>

    @GET("movie/{id}")
    suspend fun movieDetails(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): MovieDetails

    @GET("tv/{id}")
    suspend fun tvDetails(
        @Path("id") id: Int,
        @Query("api_key") apiKey: String
    ): TvDetails

    @GET("tv/{id}/season/{season}")
    suspend fun tvSeason(
        @Path("id") id: Int,
        @Path("season") season: Int,
        @Query("api_key") apiKey: String
    ): SeasonDetails
}
