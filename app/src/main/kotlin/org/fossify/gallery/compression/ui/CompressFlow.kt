package org.fossify.gallery.compression.ui

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.google.android.material.snackbar.Snackbar
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.queue.QueueActivity
import org.fossify.gallery.compression.queue.QueueItem
import org.fossify.gallery.compression.queue.QueueRepository
import org.fossify.gallery.compression.queue.QueueRunner
import org.fossify.gallery.compression.queue.QueueStarter
import java.io.File

/**
 * "Comprimir" de um ou vários vídeos: opções -> aviso de vídeos já eficientes -> confirmação única de "apagar de vez"
 * para o lote -> fim da fila. Não abre a tela da fila; mostra "Adicionado à fila (n.º X)" com a ação "Ver fila".
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
        CompressOptionsDialog(activity, paths) { planned, after -> checkEfficient(planned, after) }
    }

    /** Só com vários vídeos: com um, o aviso já aparece no próprio diálogo de opções. */
    private fun checkEfficient(planned: List<PlannedVideo>, after: Int) {
        val efficient = planned.filter { it.efficient }
        if (planned.size < 2 || efficient.isEmpty()) {
            confirmDelete(planned, after)
            return
        }
        val names = efficient.take(MAX_NAMES).joinToString("\n") { "• " + File(it.path).name } +
            if (efficient.size > MAX_NAMES) "\n…" else ""
        activity.getAlertDialogBuilder()
            .setTitle(R.string.cmp_q_efficient_title)
            .setMessage(activity.getString(R.string.cmp_q_efficient_msg, efficient.size, planned.size, names))
            .setPositiveButton(R.string.cmp_q_efficient_all) { _, _ -> confirmDelete(planned, after) }
            .setNegativeButton(R.string.cmp_q_efficient_others) { _, _ ->
                val others = planned.filterNot { it.efficient }
                if (others.isEmpty()) activity.toast(R.string.cmp_q_nothing_added) else confirmDelete(others, after)
            }
            .setNeutralButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    /** "Apagar de vez": uma confirmação por lote, com a quantidade e o tamanho total. */
    private fun confirmDelete(planned: List<PlannedVideo>, after: Int) {
        if (after != CompressionPrefs.AFTER_DELETE) {
            enqueue(planned, after, consent = false)
            return
        }
        val total = planned.sumOf { it.info.fileSize }.formatSize()
        val message = if (planned.size == 1) {
            activity.getString(R.string.cmp_confirm_delete_msg, File(planned.first().path).name, total)
        } else {
            activity.getString(R.string.cmp_q_confirm_delete_batch_msg, planned.size, total)
        }
        ConfirmationDialog(
            activity,
            activity.getString(R.string.cmp_confirm_delete_title) + "\n\n" + message,
            positive = R.string.cmp_q_confirm_delete_ok,
            negative = org.fossify.commons.R.string.cancel,
        ) { enqueue(planned, after, consent = true) }
    }

    private fun enqueue(planned: List<PlannedVideo>, after: Int, consent: Boolean) {
        val batchId = System.currentTimeMillis()
        if (consent) QueueRepository.grantDeleteConsent(listOf(batchId))
        val items = planned.map {
            QueueItem.create(
                batchId, it.path, it.info.fileSize, it.estimatedSize, it.options, it.destination, after,
                videoWidth = it.options.outputWidth ?: it.info.effectiveWidth,
                videoHeight = it.options.outputHeight ?: it.info.effectiveHeight,
                fps = CompressionPlanner.fpsOf(it.info),
                durationMs = it.info.durationMs,
            )
        }
        val add = {
            QueueRepository.add(activity, items) { result ->
                showAdded(result)
                if (result.added > 0 && prefs.queueAutoStart && !QueueRunner.running) QueueStarter.start(activity)
                onAdded()
            }
        }
        // A permissão de notificações só é pedida na primeira adição; negada, a fila roda do mesmo jeito.
        if (prefs.notificationAsked) {
            add()
        } else {
            prefs.notificationAsked = true
            activity.handleNotificationPermission { add() }
        }
    }

    private fun showAdded(result: QueueRepository.AddResult) {
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

    companion object {
        private const val MAX_NAMES = 5
    }
}
