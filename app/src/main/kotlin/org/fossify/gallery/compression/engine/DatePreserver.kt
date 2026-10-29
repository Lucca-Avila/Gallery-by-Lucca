package org.fossify.gallery.compression.engine

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Log
import org.fossify.gallery.extensions.dateTakensDB
import org.fossify.gallery.models.DateTaken
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** [takenMs] segue a regra do Fossify: banco de datas próprio > DATE_TAKEN do MediaStore > data de modificação. */
data class OriginalDates(val modifiedMs: Long, val takenMs: Long, val takenInFossifyDb: Boolean)

/** Copia a data do original para o comprimido (arquivo + MediaStore + banco de datas do Fossify). Roda em thread de fundo. */
object DatePreserver {
    private const val SCAN_TIMEOUT_SECONDS = 15L

    fun read(context: Context, path: String): OriginalDates? {
        val file = File(path)
        var modified = file.lastModified()
        var mediaTaken = 0L
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Video.Media.DATE_TAKEN),
                "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null
            )?.use {
                if (it.moveToFirst()) {
                    if (modified <= 0) modified = it.getLong(0) * 1000
                    mediaTaken = it.getLong(1)
                }
            }
        }
        val dbTaken = runCatching {
            context.dateTakensDB.getDateTakensFromPath(file.parent.orEmpty()).firstOrNull { it.fullPath == path }?.taken
        }.getOrNull()
        if (modified <= 0) return null
        val taken = dbTaken ?: mediaTaken.takeIf { it > 0 } ?: modified
        return OriginalDates(modified, taken, dbTaken != null)
    }

    /** [detail] vazio quando tudo bate; senão diz quais campos divergem (também vai para o logcat, tag DatePreserver). */
    data class Check(val ok: Boolean, val detail: String)

    /**
     * Acerta as datas e confere relendo. Depois do scan, o MediaProvider pode ainda reescrever a linha (outro scan disparado
     * pela gravação), então a releitura é repetida e a atualização reaplicada algumas vezes antes de declarar divergência.
     */
    fun apply(context: Context, target: File, dates: OriginalDates): Check {
        runCatching { target.setLastModified(dates.modifiedMs) }

        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4")) { _, _ -> scanned.countDown() }
        scanned.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (dates.takenInFossifyDb) {
            runCatching {
                context.dateTakensDB.insertAll(
                    listOf(DateTaken(null, target.absolutePath, target.name, target.parent.orEmpty(), dates.takenMs, 1, dates.modifiedMs))
                )
            }
        }

        var problems = emptyList<String>()
        repeat(RECHECKS) { attempt ->
            if (attempt > 0) Thread.sleep(RECHECK_DELAY_MS)
            problems = updateAndCompare(context, target, dates)
            if (problems.isEmpty()) return Check(true, "")
        }
        val detail = problems.joinToString("; ")
        Log.w(TAG, "data divergente em ${target.name}: $detail")
        return Check(false, detail)
    }

    private fun updateAndCompare(context: Context, target: File, dates: OriginalDates): List<String> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            val resolver = context.contentResolver
            val id = resolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?", arrayOf(target.absolutePath), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return listOf("MediaStore sem registro do arquivo")
            val uri = ContentUris.withAppendedId(collection, id)
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, dates.modifiedMs / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, dates.takenMs)
            }, null, null)

            val problems = mutableListOf<String>()
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Video.Media.DATE_TAKEN), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val modifiedSec = it.getLong(0)
                    val taken = it.getLong(1)
                    if (abs(modifiedSec - dates.modifiedMs / 1000) > TOLERANCE_MS / 1000) {
                        problems.add("DATE_MODIFIED esperado ${dates.modifiedMs / 1000} s, veio $modifiedSec s")
                    }
                    if (abs(taken - dates.takenMs) > TOLERANCE_MS) problems.add("DATE_TAKEN esperado ${dates.takenMs} ms, veio $taken ms")
                } else {
                    problems.add("MediaStore sem registro do arquivo")
                }
            } ?: problems.add("MediaStore não respondeu")
            val fileMs = target.lastModified()
            if (abs(fileMs - dates.modifiedMs) > TOLERANCE_MS) problems.add("arquivo esperado ${dates.modifiedMs} ms, veio $fileMs ms")
            problems
        } catch (e: Exception) {
            listOf("erro ao atualizar o MediaStore: ${e.message}")
        }
    }

    private const val TAG = "DatePreserver"
    private const val TOLERANCE_MS = 2000L
    private const val RECHECKS = 4
    private const val RECHECK_DELAY_MS = 750L
}
