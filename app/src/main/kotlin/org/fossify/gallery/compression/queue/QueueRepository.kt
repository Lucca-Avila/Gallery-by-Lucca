package org.fossify.gallery.compression.queue

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import org.fossify.gallery.R
import org.fossify.gallery.compression.engine.VideoCompressor
import org.fossify.gallery.compression.ui.CompressionPrefs
import java.io.File
import java.util.concurrent.Executors

/**
 * Acesso à fila. Todo acesso ao banco passa por uma única thread ([io]), então as operações acontecem na ordem
 * em que foram pedidas, e a recuperação (feita uma vez por processo) sempre roda antes de qualquer outra.
 * Os ouvintes são chamados na thread principal quando algo muda.
 */
@UnstableApi
object QueueRepository {
    private const val TAG = "CompressionQueue"
    /** O app morreu este número de vezes com o mesmo item em andamento: o item é dado como falho. */
    private const val MAX_DEATHS = 2

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private val consentedBatches = mutableSetOf<Long>()

    @Volatile
    private var recovered = false

    data class AddResult(val firstPosition: Int, val lastPosition: Int, val added: Int, val duplicates: Int)

    fun <T> io(context: Context, work: (QueueDao) -> T, then: ((T) -> Unit)? = null) {
        val appContext = context.applicationContext
        executor.execute {
            val dao = QueueDatabase.getInstance(appContext).QueueDao()
            recoverIfNeeded(appContext, dao)
            val result = try {
                work(dao)
            } catch (e: Exception) {
                Log.e(TAG, "erro no banco da fila", e)
                return@execute
            }
            if (then != null) main.post { then(result) }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Pode ser chamado de qualquer thread. */
    fun notifyChanged() {
        main.post { listeners.toList().forEach { it() } }
    }

    /** Confirmação de "apagar de vez" dada neste processo; some se o processo morrer. */
    fun grantDeleteConsent(batchIds: Collection<Long>) = synchronized(consentedBatches) { consentedBatches.addAll(batchIds) }

    fun hasDeleteConsent(batchId: Long) = synchronized(consentedBatches) { batchId in consentedBatches }

    /** Adiciona no fim; vídeos já pendentes na fila são ignorados. [then] recebe as posições (n.º) na fila pendente. */
    fun add(context: Context, items: List<QueueItem>, then: (AddResult) -> Unit) {
        io(context, { dao ->
            val pending = dao.getPendingPaths().toSet()
            val fresh = items.filter { it.path !in pending }.distinctBy { it.path }
            dao.insertAll(fresh)
            val total = dao.getPending().size
            AddResult(total - fresh.size + 1, total, fresh.size, items.size - fresh.size)
        }) {
            notifyChanged()
            then(it)
        }
    }

    /**
     * Itens que estavam "comprimindo" quando o processo morreu: o temporário é apagado, o destino parcial também, e o item volta
     * a aguardar (ou falha na segunda morte). Itens já publicados só retomam a verificação e o tratamento do original.
     */
    private fun recoverIfNeeded(context: Context, dao: QueueDao) {
        if (recovered) return
        recovered = true
        val running = dao.getRunning()
        if (running.isEmpty()) return
        VideoCompressor.cleanOrphans(context)
        running.forEach { item ->
            item.partialPath?.takeIf { item.phase == QueueItem.PHASE_SAVING }?.let { partial ->
                val file = File(partial)
                if (file.exists() && file.delete()) {
                    MediaScannerConnection.scanFile(context, arrayOf(partial), null, null)
                }
            }
            val deaths = item.deaths + 1
            val published = item.phase == QueueItem.PHASE_PUBLISHED
            dao.update(
                if (deaths >= MAX_DEATHS) {
                    item.copy(
                        state = QueueItem.STATE_FAILED, deaths = deaths, partialPath = null,
                        message = context.getString(if (published) R.string.cmp_q_died_twice_published else R.string.cmp_q_died_twice),
                    )
                } else {
                    item.copy(
                        state = QueueItem.STATE_WAITING, deaths = deaths, progress = 0, partialPath = null,
                        phase = if (published) QueueItem.PHASE_PUBLISHED else QueueItem.PHASE_NONE,
                    )
                }
            )
            Log.w(TAG, "recuperado após o processo morrer: ${item.name} (fase ${item.phase}, mortes $deaths)")
        }
        CompressionPrefs(context).queueInterrupted = CompressionPrefs.INTERRUPTED_KILLED
    }
}
