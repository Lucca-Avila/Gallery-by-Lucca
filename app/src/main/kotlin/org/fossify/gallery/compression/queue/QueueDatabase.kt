package org.fossify.gallery.compression.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Banco próprio da fila (compression_queue.db), separado do GalleryDatabase do Fossify. */
@Database(entities = [QueueItem::class, VideoInfoEntry::class, CompressedKeyEntry::class], version = 5, exportSchema = false)
abstract class QueueDatabase : RoomDatabase() {

    abstract fun QueueDao(): QueueDao

    abstract fun VideoInfoDao(): VideoInfoDao

    abstract fun CompressedKeyDao(): CompressedKeyDao

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

        /** Ordem e escolhas da adição (Etapa 6c). Itens existentes: posição = id (mesma ordem), preset desconhecido (-1). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue_items ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN preset INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN custom_pct INTEGER")
                db.execSQL("ALTER TABLE queue_items ADD COLUMN short_side INTEGER")
                db.execSQL("UPDATE queue_items SET position = id")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_state_position` ON `queue_items` (`state`, `position`)")
            }
        }

        /** Chaves da marca de "já comprimido" (Etapa 7); as outras tabelas não são tocadas. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `compressed_keys` (`size` INTEGER NOT NULL, `duration_s` INTEGER NOT NULL, " +
                        "`quality` INTEGER NOT NULL, `source` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, PRIMARY KEY(`size`, `duration_s`))"
                )
            }
        }

        fun getInstance(context: Context): QueueDatabase {
            if (db == null) {
                synchronized(QueueDatabase::class) {
                    if (db == null) {
                        db = Room.databaseBuilder(context.applicationContext, QueueDatabase::class.java, "compression_queue.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                            .build()
                    }
                }
            }
            return db!!
        }
    }
}
