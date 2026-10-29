package org.fossify.gallery.compression.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Banco próprio da fila (compression_queue.db), separado do GalleryDatabase do Fossify. */
@Database(entities = [QueueItem::class], version = 2, exportSchema = false)
abstract class QueueDatabase : RoomDatabase() {

    abstract fun QueueDao(): QueueDao

    companion object {
        @Volatile
        private var db: QueueDatabase? = null

        /** Campos para a estimativa de tempo restante (Etapa 6b). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN video_width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN video_height INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN fps REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN started_at INTEGER")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN finished_at INTEGER")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN compress_ms INTEGER")
            }
        }

        fun getInstance(context: Context): QueueDatabase {
            if (db == null) {
                synchronized(QueueDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, QueueDatabase::class.java, "compression_queue.db")
                            .addMigrations(MIGRATION_1_2)
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
