package com.vidking.firetv.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_progress")
data class WatchProgress(
    @PrimaryKey val key: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterPath: String?,
    val backdropPath: String?,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String? = null,
    val currentTime: Double,
    val duration: Double,
    val progressPct: Double,
    val updatedAt: Long
) {
    val finished: Boolean get() = progressPct >= 0.95
    companion object {
        fun keyFor(tmdbId: Int, mediaType: String, season: Int = 0, episode: Int = 0): String =
            if (mediaType == "tv") "tv:$tmdbId:$season:$episode" else "movie:$tmdbId"
    }
}
