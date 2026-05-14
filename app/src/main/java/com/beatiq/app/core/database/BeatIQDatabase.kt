package com.beatiq.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local Room database per signed-in user ([databaseNameForUser]) so libraries stay isolated.
 */
@Database(
    entities = [
        SongEntity::class,
        UserDownloadEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class BeatIQDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun userDownloadDao(): UserDownloadDao

    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var instance: BeatIQDatabase? = null

        @Volatile
        private var openDbName: String? = null

        fun databaseNameForUser(userId: String): String {
            val safe = userId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "guest" }
            return "beatiq_$safe.db"
        }

        fun get(context: Context, userId: String): BeatIQDatabase {
            val name = databaseNameForUser(userId)
            synchronized(this) {
                if (instance != null && openDbName != name) {
                    instance?.close()
                    instance = null
                    openDbName = null
                }
                if (instance == null) {
                    instance =
                        Room.databaseBuilder(
                            context.applicationContext,
                            BeatIQDatabase::class.java,
                            name,
                        )
                            .fallbackToDestructiveMigration()
                            .build()
                    openDbName = name
                }
                return instance!!
            }
        }

        fun closeCurrent() {
            synchronized(this) {
                instance?.close()
                instance = null
                openDbName = null
            }
        }
    }
}
