package org.fossify.gallery.compression.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Banco próprio da fila (compression_queue.db), separado do GalleryDatabase do Fossify. */
@Database(entities = [QueueItem::class, VideoInfoEntry::class], version = 3, exportSchema = false)
abstract class QueueDatabase : RoomDatabase() {

    abstract fun QueueDao(): QueueDao

    abstract fun VideoInfoDao(): VideoInfoDao

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

        /** Cache de análise de vídeo; a tabela da fila não é tocada. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `video_info_cache` (`path` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, " +
                        "`schema_version` INTEGER NOT NULL, `build` TEXT NOT NULL, `json` TEXT NOT NULL, `cached_at` INTEGER NOT NULL, PRIMARY KEY(`path`))"
                )
            }
        }

        fun getInstance(context: Context): QueueDatabase {
            if (db == null) {
                synchronized(QueueDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, QueueDatabase::class.java, "compression_queue.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
