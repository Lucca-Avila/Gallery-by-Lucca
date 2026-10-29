package org.fossify.gallery.compression.queue

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Camada C da marca de "já comprimido": tamanho exato + duração em segundos (como no `Medium` do Fossify), que sobrevivem a
 * renomear e mover. [quality] é o N do selo q<N>.
 */
@Entity(tableName = "compressed_keys", primaryKeys = ["size", "duration_s"])
data class CompressedKeyEntry(
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "duration_s") val durationS: Int,
    @ColumnInfo(name = "quality") val quality: Int,
    @ColumnInfo(name = "source") val source: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
) {
    companion object {
        /** Gravada pelo app após o scan do resultado. */
        const val SOURCE_RESULT = 0
        /** Adotada de um vídeo listado com _comp_q<N> no nome. */
        const val SOURCE_NAME = 1
        /** Vinda da marca dentro do MP4, lida ao tocar em Comprimir. */
        const val SOURCE_MARK = 2
    }
}

@Dao
interface CompressedKeyDao {
    @Query("SELECT * FROM compressed_keys")
    fun getAll(): List<CompressedKeyEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putAll(entries: List<CompressedKeyEntry>)
}
