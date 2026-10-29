package org.fossify.gallery.compression.queue

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Análise de um vídeo guardada no banco da fila. Vale enquanto caminho, tamanho e data de modificação forem os mesmos, o
 * formato do JSON ([schemaVersion]) e a versão do sistema ([build], que muda os codecs) também.
 */
@Entity(tableName = "video_info_cache")
data class VideoInfoEntry(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "modified") val modified: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "build") val build: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)

@Dao
interface VideoInfoDao {
    @Query("SELECT * FROM video_info_cache WHERE path = :path")
    fun get(path: String): VideoInfoEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entry: VideoInfoEntry)

    @Query("DELETE FROM video_info_cache WHERE cached_at < :before")
    fun prune(before: Long): Int
}
