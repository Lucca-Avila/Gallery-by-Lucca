package org.fossify.gallery.compression.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY id")
    fun getAll(): List<QueueItem>

    @Query("SELECT * FROM queue_items WHERE id = :id")
    fun get(id: Long): QueueItem?

    @Query("SELECT * FROM queue_items WHERE state = ${QueueItem.STATE_WAITING} ORDER BY position, id LIMIT 1")
    fun firstWaiting(): QueueItem?

    @Query("SELECT * FROM queue_items WHERE state IN (${QueueItem.STATE_WAITING}, ${QueueItem.STATE_RUNNING}) ORDER BY position, id")
    fun getPending(): List<QueueItem>

    @Query("SELECT COALESCE(MAX(position), 0) FROM queue_items")
    fun maxPosition(): Long

    @Query("SELECT * FROM queue_items WHERE state = ${QueueItem.STATE_RUNNING}")
    fun getRunning(): List<QueueItem>

    @Query("SELECT path FROM queue_items WHERE state IN (${QueueItem.STATE_WAITING}, ${QueueItem.STATE_RUNNING})")
    fun getPendingPaths(): List<String>

    @Insert
    fun insertAll(items: List<QueueItem>): List<Long>

    /** Remove só o que pode sair sem efeito colateral: aguardando que ainda não gravou nada, ou já encerrado. */
    @Query(
        "DELETE FROM queue_items WHERE id = :id AND ((state = ${QueueItem.STATE_WAITING} AND phase = ${QueueItem.PHASE_NONE}) " +
            "OR state IN (${QueueItem.STATE_DONE}, ${QueueItem.STATE_FAILED}, ${QueueItem.STATE_DISCARDED}, ${QueueItem.STATE_CANCELED}))"
    )
    fun deleteRemovable(id: Long): Int

    /** "Limpar concluídos": as falhas ficam até serem removidas à mão. */
    @Query("DELETE FROM queue_items WHERE state IN (${QueueItem.STATE_DONE}, ${QueueItem.STATE_DISCARDED}, ${QueueItem.STATE_CANCELED})")
    fun deleteFinished(): Int

    @Update
    fun update(item: QueueItem)

    @Query("UPDATE queue_items SET progress = :progress WHERE id = :id")
    fun updateProgress(id: Long, progress: Int)
}
