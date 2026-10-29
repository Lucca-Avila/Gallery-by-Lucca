package org.fossify.gallery.compression.engine

import android.content.Context
import org.fossify.gallery.R
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.ui.CompressionPrefs
import java.io.File

/** Remove (ou manda para a lixeira) o original depois de verificado. Quem chama decide como (Activity, serviço). */
fun interface OriginalRemover {
    /** [after] é CompressionPrefs.AFTER_TRASH ou AFTER_DELETE. Chama [onDone] com a linha de resultado, em qualquer thread. */
    fun remove(path: String, size: Long, after: Int, onDone: (String) -> Unit)
}

/**
 * Tudo o que acontece depois de o Transformer gerar o temporário, sem UI: descarte por economia pequena,
 * gravação no destino, data, verificação e remoção do original. Deve ser chamado em thread de fundo.
 * Sempre apaga o temporário.
 */
class CompressionPipeline(
    private val context: Context,
    private val path: String,
    private val info: VideoInfo,
    private val options: CompressionOptions,
    private val destination: Destination,
    private val after: Int,
    private val remover: OriginalRemover,
) {
    enum class Stage { SAVING, VERIFYING }

    sealed class Outcome {
        data class Discarded(val newSize: Long, val originalSize: Long) : Outcome()
        data class Done(val newSize: Long, val published: PublishedFile, val dateCheck: DatePreserver.Check, val originalLine: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun run(result: CompressionResult, onStage: (Stage) -> Unit, onDone: (Outcome) -> Unit) {
        val newSize = result.file.length()
        val published: PublishedFile
        val dateCheck: DatePreserver.Check
        try {
            if (ResultVerifier.shouldDiscard(newSize, info.fileSize)) {
                onDone(Outcome.Discarded(newSize, info.fileSize))
                return
            }
            onStage(Stage.SAVING)
            val dates = DatePreserver.read(context, path)
            published = CompressionOutput.publish(context, result.file, destination, File(path).name, options.qualityTag)
            dateCheck = dates?.let { DatePreserver.apply(context, published.file, it) }
                ?: DatePreserver.Check(false, context.getString(R.string.cmp_date_unreadable))
        } catch (e: Exception) {
            onDone(Outcome.Failed(e.message ?: e.javaClass.simpleName))
            return
        } finally {
            result.file.delete()
        }

        fun done(line: String) = onDone(Outcome.Done(newSize, published, dateCheck, line))
        try {
            if (after == CompressionPrefs.AFTER_KEEP) {
                done(context.getString(R.string.cmp_result_original_kept))
                return
            }
            onStage(Stage.VERIFYING)
            val problem = ResultVerifier.verify(context, published.file, info, options)
            if (problem != null) {
                done(context.getString(R.string.cmp_result_original_not_removed, problem))
            } else {
                remover.remove(path, info.fileSize, after) { line -> done(line) }
            }
        } catch (e: Exception) {
            onDone(Outcome.Failed(e.message ?: e.javaClass.simpleName))
        }
    }
}
