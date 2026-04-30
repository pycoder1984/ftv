package com.vidking.firetv.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PageResult<T>(
    val page: Int,
    val results: List<T>,
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "total_results") val totalResults: Int = 0
)

@JsonClass(generateAdapter = true)
data class MediaItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double = 0.0,
    @Json(name = "media_type") val mediaType: String? = null
) {
    val displayTitle: String get() = title ?: name ?: "Untitled"
    val displayDate: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    fun isMovie(): Boolean = (mediaType == "movie") || (title != null && name == null)
}

@JsonClass(generateAdapter = true)
data class MovieDetails(
    val id: Int,
    val title: String,
    val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    val runtime: Int? = 0,
    @Json(name = "vote_average") val voteAverage: Double = 0.0,
    val genres: List<Genre> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvDetails(
    val id: Int,
    val name: String,
    val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int = 0,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int = 0,
    @Json(name = "vote_average") val voteAverage: Double = 0.0,
    val genres: List<Genre> = emptyList(),
    val seasons: List<Season> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Genre(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class Season(
    val id: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String?,
    @Json(name = "episode_count") val episodeCount: Int = 0,
    @Json(name = "poster_path") val posterPath: String? = null,
    val overview: String? = null
)

@JsonClass(generateAdapter = true)
data class SeasonDetails(
    val id: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String,
    val episodes: List<Episode> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Episode(
    val id: Int,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String,
    val overview: String? = null,
    @Json(name = "still_path") val stillPath: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    val runtime: Int? = 0,
    @Json(name = "vote_average") val voteAverage: Double = 0.0
)
