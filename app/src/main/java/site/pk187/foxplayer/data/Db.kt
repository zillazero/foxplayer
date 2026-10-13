package site.pk187.foxplayer.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "m3u" oder "xtream" */
    val type: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "channel",
    indices = [Index("playlistId", "groupName", "sort"), Index("tvgId"), Index("favorite")],
)
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val sort: Int,
    val groupName: String,
    val name: String,
    val logo: String,
    val url: String,
    val tvgId: String,
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val userAgent: String? = null,
    val favorite: Boolean = false,
)

@Fts4(contentEntity = Channel::class)
@Entity(tableName = "channel_fts")
data class ChannelFts(val name: String, val groupName: String)

@Entity(tableName = "programme", indices = [Index("channelKey", "start")])
data class Programme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
)

data class GroupRow(val groupName: String, val count: Int)

@Dao
interface FoxDao {
    @Query("SELECT * FROM playlist ORDER BY id") fun playlists(): Flow<List<Playlist>>
    @Query("SELECT * FROM playlist WHERE id = :id") suspend fun playlist(id: Long): Playlist?
    @Insert suspend fun insertPlaylist(p: Playlist): Long
    @Update suspend fun updatePlaylist(p: Playlist)
    @Query("DELETE FROM playlist WHERE id = :id") suspend fun deletePlaylist(id: Long)

    @Insert fun insertChannels(c: List<Channel>)
    @Query("DELETE FROM channel WHERE playlistId = :pid") fun deleteChannels(pid: Long)
    @Query("SELECT url FROM channel WHERE playlistId = :pid AND favorite = 1") fun favoriteUrls(pid: Long): List<String>
    @Query("UPDATE channel SET favorite = 1 WHERE playlistId = :pid AND url IN (:urls)") fun restoreFavorites(pid: Long, urls: List<String>)
    @Query("SELECT DISTINCT tvgId FROM channel WHERE playlistId = :pid") fun tvgIds(pid: Long): List<String>

    @Query("SELECT groupName, COUNT(*) AS count FROM channel WHERE playlistId = :pid GROUP BY groupName ORDER BY MIN(sort)")
    fun groups(pid: Long): Flow<List<GroupRow>>
    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :pid AND favorite = 1") fun favoriteCount(pid: Long): Flow<Int>

    @Query("SELECT * FROM channel WHERE playlistId = :pid AND (:group IS NULL OR groupName = :group) ORDER BY sort")
    fun channelsPaged(pid: Long, group: String?): PagingSource<Int, Channel>
    @Query("SELECT * FROM channel WHERE playlistId = :pid AND favorite = 1 ORDER BY sort")
    fun favoritesPaged(pid: Long): PagingSource<Int, Channel>
    @Query("SELECT channel.* FROM channel JOIN channel_fts ON channel.rowid = channel_fts.rowid WHERE channel_fts MATCH :q AND channel.playlistId = :pid ORDER BY channel.sort")
    fun searchPaged(pid: Long, q: String): PagingSource<Int, Channel>

    /** Nachbarkanal fuer CH+/CH- im Vollbild (innerhalb der aktuellen Gruppe/Favoriten). */
    @Query("SELECT * FROM channel WHERE playlistId = :pid AND (:group IS NULL OR groupName = :group) AND (:fav = 0 OR favorite = 1) AND sort > :sort ORDER BY sort LIMIT 1")
    suspend fun next(pid: Long, group: String?, fav: Boolean, sort: Int): Channel?
    @Query("SELECT * FROM channel WHERE playlistId = :pid AND (:group IS NULL OR groupName = :group) AND (:fav = 0 OR favorite = 1) AND sort < :sort ORDER BY sort DESC LIMIT 1")
    suspend fun prev(pid: Long, group: String?, fav: Boolean, sort: Int): Channel?
    @Query("SELECT * FROM channel WHERE id = :id") suspend fun channel(id: Long): Channel?

    @Query("UPDATE channel SET favorite = NOT favorite WHERE id = :id") suspend fun toggleFavorite(id: Long)

    @Insert fun insertProgrammes(p: List<Programme>)
    @Query("DELETE FROM programme WHERE playlistId = :pid") fun deleteProgrammes(pid: Long)
    @Query("DELETE FROM programme WHERE stop < :before") suspend fun pruneProgrammes(before: Long)
    @Query("SELECT * FROM programme WHERE playlistId = :pid AND channelKey IN (:keys) AND stop > :from AND start < :to ORDER BY start")
    suspend fun programmes(pid: Long, keys: List<String>, from: Long, to: Long): List<Programme>
}

@Database(entities = [Playlist::class, Channel::class, ChannelFts::class, Programme::class], version = 1, exportSchema = true)
abstract class FoxDb : RoomDatabase() {
    abstract fun dao(): FoxDao

    companion object {
        @Volatile private var inst: FoxDb? = null
        fun get(ctx: Context): FoxDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, FoxDb::class.java, "foxplayer.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { inst = it }
        }
    }
}
