package com.vidking.firetv.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PageResult<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int? = null,
    @Json(name = "total_results") val totalResults: Int? = null
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
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "media_type") val mediaType: String? = null
) {
    val displayTitle: String get() = title ?: name ?: "Untitled"
    val displayDate: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    fun isMovie(): Boolean = (mediaType == "movie") || (title != null && name == null)
}

@JsonClass(generateAdapter = true)
data class MovieDetails(
    val id: Int,
    val title: String? = null,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    val genres: List<Genre> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvDetails(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    val genres: List<Genre> = emptyList(),
    val seasons: List<Season> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Genre(val id: Int, val name: String? = null)

@JsonClass(generateAdapter = true)
data class Season(
    val id: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    val overview: String? = null
)

@JsonClass(generateAdapter = true)
data class SeasonDetails(
    val id: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    val episodes: List<Episode> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Episode(
    val id: Int,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "still_path") val stillPath: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null
)
