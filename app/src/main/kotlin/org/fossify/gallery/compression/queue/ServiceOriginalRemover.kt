package org.fossify.gallery.compression.queue

import android.content.Context
import org.fossify.commons.extensions.deleteFromMediaStore
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.gallery.R
import org.fossify.gallery.compression.engine.OriginalRemover
import org.fossify.gallery.compression.ui.CompressionPrefs
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.helpers.RECYCLE_BIN
import java.io.File

/**
 * Remoção do original sem Activity, para o serviço da fila. Usa o acesso a todos os arquivos (já concedido):
 * a lixeira repete o que o Fossify faz em movePathsInRecycleBin (cópia para a pasta da lixeira + registro no banco),
 * e só então o original é apagado e tirado do MediaStore. Roda na thread do pipeline.
 */
class ServiceOriginalRemover(context: Context) : OriginalRemover {
    private val context = context.applicationContext

    override fun remove(path: String, size: Long, after: Int, onDone: (String) -> Unit) {
        val file = File(path)
        if (after == CompressionPrefs.AFTER_TRASH && !copyToRecycleBin(file)) {
            onDone(notRemoved(R.string.cmp_trash_failed))
            return
        }
        val deleted = runCatching { file.delete() }.getOrDefault(false) && !file.exists()
        if (!deleted) {
            onDone(notRemoved(if (after == CompressionPrefs.AFTER_TRASH) R.string.cmp_trashed_not_deleted else R.string.cmp_delete_failed))
            return
        }
        runCatching { context.deleteDBPath(path) }
        runCatching { context.deleteFromMediaStore(path) }
        onDone(context.getString(if (after == CompressionPrefs.AFTER_TRASH) R.string.cmp_result_original_trashed else R.string.cmp_result_original_deleted))
    }

    private fun copyToRecycleBin(file: File): Boolean {
        val source = file.absolutePath
        val internal = File(context.recycleBinPath, source)
        val lastModified = file.lastModified()
        return try {
            file.copyTo(internal, overwrite = true)
            if (internal.length() != file.length()) {
                internal.delete()
                return false
            }
            context.mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
            if (context.config.keepLastModified && lastModified != 0L) internal.setLastModified(lastModified)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun notRemoved(reason: Int) =
        context.getString(R.string.cmp_result_original_not_removed, context.getString(reason))
}
