package com.beatiq.music.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(ps.songId) AS trackCount
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
        """,
    )
    fun observePlaylistSummaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylist(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePlaylistMeta(id: String, name: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.sortIndex ASC, s.title COLLATE NOCASE ASC
        """,
    )
    fun observeSongsInPlaylist(playlistId: String): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(link: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: String, songId: String)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun maxSortIndex(playlistId: String): Int?

    @Transaction
    suspend fun addSongToPlaylist(playlistId: String, songId: String, now: Long) {
        val next = (maxSortIndex(playlistId) ?: -1) + 1
        insertPlaylistSong(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                sortIndex = next,
            ),
        )
        touchPlaylistUpdated(playlistId, now)
    }

    @Transaction
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String, now: Long) {
        removeSong(playlistId, songId)
        touchPlaylistUpdated(playlistId, now)
    }

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchPlaylistUpdated(id: String, updatedAt: Long)
}

/** Room query projection; not a table entity. */
data class PlaylistSummaryRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
)
