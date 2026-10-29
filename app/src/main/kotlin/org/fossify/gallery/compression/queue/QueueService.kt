package org.fossify.gallery.compression.queue

import android.app.InvalidForegroundServiceTypeException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import org.fossify.gallery.compression.ui.CompressionPrefs

/**
 * Serviço em primeiro plano (mediaProcessing, ou dataSync onde aquele não existe) que mantém a fila rodando com a tela apagada.
 * Só é iniciado pela interface (adição, Iniciar, Retomar); não é reiniciado pelo sistema (START_NOT_STICKY):
 * se o processo morrer, a recuperação acontece na próxima abertura do app.
 */
@UnstableApi
class QueueService : Service(), QueueRunner.Callbacks {
    private var runner: QueueRunner? = null
    private var lastNotified = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground()
                val r = runner ?: QueueRunner(this, this).also { runner = it }
                r.start()
            }

            ACTION_PAUSE -> runner?.pause() ?: stopSelf()
            ACTION_PAUSE_NOW -> runner?.pauseNow() ?: stopSelf()
            ACTION_SKIP -> runner?.skipCurrent() ?: stopSelf()
            else -> if (runner == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Tenta mediaProcessing (o tipo indicado para transcodificação). O firmware do S23 (One UI 8.5) não registra esse tipo
     * na política de FGS: o sistema o trata como "none" e lança InvalidForegroundServiceTypeException. Nesse caso usa
     * dataSync, que também tem limite de 6 h por dia e também chama onTimeout.
     */
    private fun startInForeground() {
        val notification = QueueNotifications.progress(this, null, 0, 0, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceCompat.startForeground(this, QueueNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            return
        }
        try {
            ServiceCompat.startForeground(this, QueueNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } catch (e: InvalidForegroundServiceTypeException) {
            Log.w(TAG, "tipo mediaProcessing recusado pelo sistema; usando dataSync", e)
            ServiceCompat.startForeground(this, QueueNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    override fun onProgress(item: QueueItem, index: Int, total: Int, percent: Int?) {
        val key = "${item.id}/$index/$total/$percent"
        if (key == lastNotified) return
        lastNotified = key
        QueueNotifications.update(this, QueueNotifications.progress(this, item, index, total, percent))
    }

    override fun onStopped(reason: QueueRunner.StopReason, stats: QueueRunner.Stats) {
        runner = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        when (reason) {
            QueueRunner.StopReason.FINISHED -> if (stats.done + stats.failed + stats.discarded + stats.canceled > 0) QueueNotifications.summary(this, stats)
            QueueRunner.StopReason.TIME_LIMIT -> QueueNotifications.timeLimit(this)
            QueueRunner.StopReason.PAUSED -> Unit
        }
        stopSelf()
    }

    /** Limite diário do tipo de serviço (mediaProcessing ou dataSync): cancela o item atual (volta a aguardar), avisa e sai em poucos segundos. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "limite de tempo do serviço em primeiro plano")
        // Se estiver gravando/verificando, o item termina em segundo plano e a fila para depois dele; o serviço sai já.
        runner?.pauseNow(QueueRunner.StopReason.TIME_LIMIT)
        runner = null
        CompressionPrefs(this).queueInterrupted = CompressionPrefs.INTERRUPTED_TIME_LIMIT
        QueueNotifications.timeLimit(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runner?.pauseNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CompressionQueue"
        const val ACTION_START = "org.fossify.gallery.compression.queue.START"
        const val ACTION_PAUSE = "org.fossify.gallery.compression.queue.PAUSE"
        const val ACTION_PAUSE_NOW = "org.fossify.gallery.compression.queue.PAUSE_NOW"
        const val ACTION_SKIP = "org.fossify.gallery.compression.queue.SKIP"

        /** false se o sistema não deixou iniciar (ex.: limite diário do mediaProcessing já atingido). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, QueueService::class.java).setAction(ACTION_START))
            true
        } catch (e: Exception) {
            Log.e(TAG, "não foi possível iniciar o serviço da fila", e)
            false
        }

        fun send(context: Context, action: String) {
            if (!QueueRunner.running) return
            runCatching { context.startService(Intent(context, QueueService::class.java).setAction(action)) }
        }
    }
}
