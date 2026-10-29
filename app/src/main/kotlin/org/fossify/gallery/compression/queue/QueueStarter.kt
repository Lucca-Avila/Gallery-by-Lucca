package org.fossify.gallery.compression.queue

import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.compression.ui.CompressionPrefs

/**
 * Único caminho para iniciar a fila pela interface. Itens com "apagar de vez" cujo lote não foi confirmado neste processo
 * (fila retomada depois de o app ser encerrado) pedem a confirmação de novo antes de o serviço começar.
 */
@UnstableApi
object QueueStarter {
    fun start(activity: BaseSimpleActivity) {
        QueueRepository.io(activity, { dao ->
            dao.getPending().filter { it.after == CompressionPrefs.AFTER_DELETE && !QueueRepository.hasDeleteConsent(it.batchId) }
        }) { unconfirmed ->
            if (activity.isFinishing || activity.isDestroyed) return@io
            if (unconfirmed.isEmpty()) launch(activity) else askAgain(activity, unconfirmed)
        }
    }

    private fun askAgain(activity: BaseSimpleActivity, items: List<QueueItem>) {
        val message = activity.getString(R.string.cmp_q_reconfirm_delete_msg, items.size, items.sumOf { it.originalSize }.formatSize())
        activity.getAlertDialogBuilder()
            .setTitle(R.string.cmp_confirm_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.cmp_q_reconfirm_delete_ok) { _, _ ->
                QueueRepository.grantDeleteConsent(items.map { it.batchId }.toSet())
                launch(activity)
            }
            .setNegativeButton(R.string.cmp_q_reconfirm_keep) { _, _ ->
                QueueRepository.io(activity, { dao ->
                    items.forEach { item -> dao.get(item.id)?.let { dao.update(it.copy(after = CompressionPrefs.AFTER_KEEP)) } }
                }) {
                    QueueRepository.notifyChanged()
                    launch(activity)
                }
            }
            .setNeutralButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun launch(activity: BaseSimpleActivity) {
        if (!QueueService.start(activity)) activity.toast(R.string.cmp_q_start_failed, Toast.LENGTH_LONG)
    }
}
