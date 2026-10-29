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

    @Query("SELECT * FROM queue_items WHERE state = ${QueueItem.STATE_WAITING} ORDER BY id LIMIT 1")
    fun firstWaiting(): QueueItem?

    @Query("SELECT * FROM queue_items WHERE state IN (${QueueItem.STATE_WAITING}, ${QueueItem.STATE_RUNNING}) ORDER BY id")
    fun getPending(): List<QueueItem>

    @Query("SELECT * FROM queue_items WHERE state = ${QueueItem.STATE_RUNNING}")
    fun getRunning(): List<QueueItem>

    @Query("SELECT path FROM queue_items WHERE state IN (${QueueItem.STATE_WAITING}, ${QueueItem.STATE_RUNNING})")
    fun getPendingPaths(): List<String>

    @Insert
    fun insertAll(items: List<QueueItem>): List<Long>

    @Update
    fun update(item: QueueItem)

    @Query("UPDATE queue_items SET progress = :progress WHERE id = :id")
    fun updateProgress(id: Long, progress: Int)
}
