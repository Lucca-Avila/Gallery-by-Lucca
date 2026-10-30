package org.fossify.gallery.compression.ui

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.google.android.material.snackbar.Snackbar
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.queue.QueueActivity
import org.fossify.gallery.compression.queue.QueueItem
import org.fossify.gallery.compression.queue.QueueRepository
import org.fossify.gallery.compression.queue.QueueService
import org.fossify.gallery.compression.queue.QueueStarter
import java.io.File

/**
 * "Comprimir" de um ou vários vídeos. Um vídeo: diálogo de opções. Dois ou mais: [CompressBatchActivity]. Depois, confirmação
 * única de "apagar de vez" para o lote -> fim da fila. Não abre a tela da fila; mostra "Adicionado à fila (n.º X)" com a ação
 * "Ver fila" na tela de origem.
 */
@UnstableApi
class CompressFlow(
    private val activity: BaseSimpleActivity,
    private val paths: List<String>,
    /** Chamado depois de adicionar (a seleção pode ser encerrada). */
    private val onAdded: () -> Unit = {},
) {
    private val prefs = CompressionPrefs(activity)

    fun start() {
        if (paths.size > 1) {
            CompressBatchActivity.open(activity, this, paths)
        } else {
            CompressOptionsDialog(activity, paths, onConfirm = { planned, _ -> add(activity, planned) })
        }
    }

    /**
     * "Apagar de vez": uma confirmação para todo o lote, com a quantidade e o tamanho total dos originais marcados para isso
     * (cada vídeo tem o seu "depois de comprimir"). [host] é a tela onde os diálogos aparecem; [done] roda depois de adicionar.
     */
    fun add(host: BaseSimpleActivity, planned: List<PlannedVideo>, done: () -> Unit = {}) {
        val toDelete = planned.filter { it.after == CompressionPrefs.AFTER_DELETE }
        if (toDelete.isEmpty()) {
            enqueue(host, planned, consent = false, done)
            return
        }
        val total = toDelete.sumOf { it.info.fileSize }.formatSize()
        val message = if (planned.size == 1) {
            host.getString(R.string.cmp_confirm_delete_msg, File(planned.first().path).name, total)
        } else {
            host.getString(R.string.cmp_q_confirm_delete_batch_msg, toDelete.size, total)
        }
        ConfirmationDialog(
            host,
            host.getString(R.string.cmp_confirm_delete_title) + "\n\n" + message,
            positive = R.string.cmp_q_confirm_delete_ok,
            negative = org.fossify.commons.R.string.cancel,
        ) { enqueue(host, planned, consent = true, done) }
    }

    private fun enqueue(host: BaseSimpleActivity, planned: List<PlannedVideo>, consent: Boolean, done: () -> Unit) {
        val batchId = System.currentTimeMillis()
        if (consent) QueueRepository.grantDeleteConsent(listOf(batchId))
        val items = planned.map {
            QueueItem.create(
                batchId, it.path, it.info.fileSize, it.estimatedSize, it.options, it.destination, it.after,
                videoWidth = it.options.outputWidth ?: it.info.effectiveWidth,
                videoHeight = it.options.outputHeight ?: it.info.effectiveHeight,
                fps = CompressionPlanner.fpsOf(it.info),
                durationMs = it.info.durationMs,
                preset = it.presetCode,
                customPct = it.customPct,
                shortSide = it.shortSide,
            )
        }
        val add = {
            QueueRepository.add(host, items) { result ->
                // O aviso e o início da fila ficam na tela de origem, porque a tela de lote fecha em [done].
                val target = if (activity.isDestroyed || activity.isFinishing) host else activity
                showAdded(target, result)
                if (result.added > 0 && prefs.queueAutoStart && !QueueService.active) QueueStarter.start(target)
                onAdded()
                done()
            }
        }
        // A permissão de notificações só é pedida na primeira adição; negada, a fila roda do mesmo jeito.
        if (prefs.notificationAsked) {
            add()
        } else {
            prefs.notificationAsked = true
            host.handleNotificationPermission { add() }
        }
    }

    private fun showAdded(activity: BaseSimpleActivity, result: QueueRepository.AddResult) {
        val main = when {
            result.added == 0 -> activity.getString(R.string.cmp_q_already_queued)
            result.added == 1 -> activity.getString(R.string.cmp_q_added_one, result.firstPosition)
            else -> activity.getString(R.string.cmp_q_added_many, result.added, result.firstPosition, result.lastPosition)
        }
        val text = if (result.duplicates > 0 && result.added > 0) {
            main + " · " + activity.getString(R.string.cmp_q_duplicates, result.duplicates)
        } else {
            main
        }
        val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return activity.toast(text)
        Snackbar.make(root, text, Snackbar.LENGTH_LONG)
            .setAction(R.string.cmp_q_view) { activity.startActivity(Intent(activity, QueueActivity::class.java)) }
            .show()
    }
}
