package com.vidking.firetv.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WatchProgress::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchProgressDao(): WatchProgressDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "vidking.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
