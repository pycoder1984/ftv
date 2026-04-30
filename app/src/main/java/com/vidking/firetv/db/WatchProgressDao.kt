package com.vidking.firetv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: WatchProgress)

    @Query("SELECT * FROM watch_progress WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): WatchProgress?

    @Query("SELECT * FROM watch_progress WHERE progressPct < 0.95 ORDER BY updatedAt DESC LIMIT 20")
    fun continueWatching(): Flow<List<WatchProgress>>

    @Query("DELETE FROM watch_progress WHERE `key` = :key")
    suspend fun delete(key: String)
}
