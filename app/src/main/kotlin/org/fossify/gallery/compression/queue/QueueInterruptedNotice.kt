package org.fossify.gallery.compression.queue

import androidx.media3.common.util.UnstableApi
import com.google.android.material.snackbar.Snackbar
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.gallery.R
import org.fossify.gallery.compression.ui.CompressionPrefs

/**
 * Aviso "Fila interrompida: Retomar" ao abrir o app. O primeiro acesso ao banco no processo faz a recuperação
 * (itens que estavam comprimindo voltam a aguardar), então este aviso também a dispara.
 */
@UnstableApi
object QueueInterruptedNotice {
    private var shown = false

    fun showIfNeeded(activity: BaseSimpleActivity) {
        if (shown || QueueService.active) return
        QueueRepository.io(activity, { dao -> dao.getPending().size }) { waiting ->
            val reason = CompressionPrefs(activity).queueInterrupted
            if (shown || waiting == 0 || reason == CompressionPrefs.INTERRUPTED_NONE || QueueService.active) return@io
            if (activity.isFinishing || activity.isDestroyed) return@io
            shown = true
            val root = activity.findViewById<android.view.View>(android.R.id.content) ?: return@io
            val text = activity.getString(
                when (reason) {
                    CompressionPrefs.INTERRUPTED_TIME_LIMIT -> R.string.cmp_q_notice_time_limit
                    CompressionPrefs.INTERRUPTED_THERMAL -> R.string.cmp_q_notice_thermal
                    else -> R.string.cmp_q_notice_killed
                },
                waiting,
            )
            Snackbar.make(root, text, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.cmp_q_resume) { QueueStarter.start(activity) }
                .show()
        }
    }
}
