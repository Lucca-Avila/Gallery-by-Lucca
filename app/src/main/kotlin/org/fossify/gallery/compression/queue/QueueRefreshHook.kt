package org.fossify.gallery.compression.queue

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Atualiza uma tela de mídia quando a fila conclui um item. [attach] em onResume e [detach] em onPause: se a tela estava em
 * segundo plano, o próprio onResume dela já recarrega. Espera um instante para o scanner de mídia terminar e respeita um
 * intervalo mínimo entre atualizações (vídeos curtos concluem em sequência).
 */
@OptIn(UnstableApi::class)
class QueueRefreshHook(private val refresh: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastRefreshAt = 0L
    private val run = Runnable {
        lastRefreshAt = SystemClock.elapsedRealtime()
        refresh()
    }
    private val listener: () -> Unit = { schedule() }

    fun attach() = QueueRepository.addCompletedListener(listener)

    fun detach() {
        QueueRepository.removeCompletedListener(listener)
        handler.removeCallbacks(run)
    }

    private fun schedule() {
        val sinceLast = SystemClock.elapsedRealtime() - lastRefreshAt
        handler.removeCallbacks(run)
        handler.postDelayed(run, maxOf(SETTLE_MS, MIN_INTERVAL_MS - sinceLast))
    }

    private companion object {
        const val SETTLE_MS = 1_500L
        const val MIN_INTERVAL_MS = 3_000L
    }
}
