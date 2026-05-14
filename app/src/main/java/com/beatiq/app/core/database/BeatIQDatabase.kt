package com.beatiq.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local Room database. Version bumps should ship [androidx.room.migration.Migration] objects.
 *
 * TODO(Phase-3): Enable exportSchema + CI schema checks when migrations stabilize.
 */
@Database(
    entities = [SongEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BeatIQDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var instance: BeatIQDatabase? = null

        fun get(context: Context): BeatIQDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BeatIQDatabase::class.java,
                    "beatiq.db",
                )
                    // TODO(Phase-3): Replace with incremental migrations once schema stabilizes.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
