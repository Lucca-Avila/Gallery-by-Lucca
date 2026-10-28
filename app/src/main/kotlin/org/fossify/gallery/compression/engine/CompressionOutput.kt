package org.fossify.gallery.compression.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** Onde gravar o vídeo comprimido. */
sealed class Destination {
    /** Pasta de mídia via MediaStore (RELATIVE_PATH terminando em "/"). */
    data class MediaFolder(val relativePath: String) : Destination()

    /** Gravação direta em qualquer pasta (requer acesso a todos os arquivos); depois avisa o MediaStore. */
    data class Direct(val folderPath: String) : Destination()
}

data class PublishedFile(val name: String, val location: String, val usedFallback: Boolean)

/**
 * Copia o temporário para o destino sem nunca sobrescrever. Gravação direta (acesso a todos os arquivos)
 * ou MediaStore; se a gravação direta falhar, cai em Movies/COMPRIMIDOS via MediaStore (IS_PENDING).
 * Roda em thread de fundo.
 */
object CompressionOutput {
    const val FALLBACK_RELATIVE_PATH = "Movies/COMPRIMIDOS/"

    fun publish(
        context: Context,
        temp: File,
        destination: Destination,
        originalFileName: String,
        qualityTag: Int,
    ): PublishedFile {
        val base = baseNameOf(originalFileName)
        val q = qualityTag
        when (destination) {
            is Destination.Direct -> publishDirect(context, temp, destination.folderPath, base, q)?.let { return it }
            is Destination.MediaFolder ->
                publishToMediaStore(context, temp, destination.relativePath, base, q)
                    ?.let { return PublishedFile(it, destination.relativePath, false) }
        }
        val name = publishToMediaStore(context, temp, FALLBACK_RELATIVE_PATH, base, q)
            ?: throw CompressionException("Não foi possível gravar em $FALLBACK_RELATIVE_PATH")
        return PublishedFile(name, FALLBACK_RELATIVE_PATH, true)
    }

    private val STACKED_SUFFIX = Regex("_comp(_q\\d+)?(_\\d+)?$")

    /** Nome sem extensão e sem sufixo de compressão anterior, para não empilhar (_comp, _comp_q40, _comp_q40_2). */
    fun baseNameOf(fileName: String): String {
        val noExt = fileName.substringBeforeLast('.', fileName)
        return noExt.replace(STACKED_SUFFIX, "").ifEmpty { noExt }
    }

    private fun candidate(base: String, n: Int, q: Int) =
        if (n == 1) "${base}_comp_q$q.mp4" else "${base}_comp_q${q}_$n.mp4"

    private fun publishToMediaStore(context: Context, temp: File, relative: String, base: String, q: Int): String? {
        val resolver = context.contentResolver
        val collection = if (relative.startsWith("Download/")) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        var n = 1
        while (existsInMediaStore(context, collection, candidate(base, n, q), relative)) n++
        val name = candidate(base, n, q)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            null
        } ?: return null

        try {
            val out = resolver.openOutputStream(uri) ?: throw CompressionException("Não foi possível gravar no destino")
            out.use { o -> temp.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is CompressionException) e else CompressionException("Falha ao gravar no destino: ${e.message}", e)
        }
        return name
    }

    private fun existsInMediaStore(context: Context, collection: Uri, name: String, relative: String): Boolean =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, relative),
            null
        )?.use { it.count > 0 } ?: false

    /** null = pasta inexistente ou sem permissão de escrita (chamador usa o fallback). Falha no meio da cópia apaga o parcial. */
    private fun publishDirect(context: Context, temp: File, folder: String, base: String, q: Int): PublishedFile? {
        val dir = File(folder)
        if (!dir.isDirectory || !dir.canWrite()) return null
        var n = 1
        while (File(dir, candidate(base, n, q)).exists()) n++
        val target = File(dir, candidate(base, n, q))
        try {
            target.outputStream().use { o -> temp.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            runCatching { target.delete() }
            return null
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
        return PublishedFile(target.name, folder, false)
    }
}
