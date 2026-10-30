package org.fossify.gallery.compression.queue

import android.app.InvalidForegroundServiceTypeException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import org.fossify.gallery.compression.ui.CompressionPrefs

/**
 * Serviço em primeiro plano (mediaProcessing, ou dataSync onde aquele não existe) que mantém a fila rodando com a tela apagada.
 * Só é iniciado pela interface (adição, Iniciar, Retomar); não é reiniciado pelo sistema (START_NOT_STICKY):
 * se o processo morrer, a recuperação acontece na próxima abertura do app.
 * Na pausa por temperatura o serviço continua em primeiro plano (sem runner), porque o Android não deixa iniciá-lo de novo
 * a partir do segundo plano; o [ThermalGuard] decide quando retomar.
 */
@UnstableApi
class QueueService : Service(), QueueRunner.Callbacks, ThermalGuard.Listener {
    @Volatile
    private var runner: QueueRunner? = null
    private val guard by lazy { ThermalGuard(this, this) }
    private var emergencyPause = false
    private var logOpen = false
    private var lastNotified = ""
    private var etaText: String? = null
    private var lastEtaAt = 0L
    private var lastArgs: Args? = null

    private class Args(val item: QueueItem, val index: Int, val total: Int, val percent: Int?)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground()
                resume(manual = cooling)
            }

            ACTION_PAUSE, ACTION_PAUSE_NOW -> when {
                cooling -> endCooldown(CompressionPrefs.LastRun.REASON_PAUSED, "pausa manual durante a pausa por temperatura")
                intent.action == ACTION_PAUSE -> runner?.pause() ?: stopSelf()
                else -> runner?.pauseNow() ?: stopSelf()
            }

            ACTION_SKIP -> runner?.skipCurrent() ?: run { if (!cooling) stopSelf() }
            ACTION_CANCEL_ALL -> when {
                cooling -> {
                    endCooldown(null, "fila cancelada durante a pausa por temperatura")
                    QueueRepository.cancelWaiting(this)
                }

                else -> runner?.cancelAll() ?: stopSelf()
            }

            ACTION_THERMAL_PREF -> when {
                CompressionPrefs(this).queueThermalPause -> if (runner != null) guard.watch()
                cooling -> resume(manual = true)
                else -> guard.stop()
            }

            else -> if (runner == null && !cooling) stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Inicia (ou continua) o runner e a vigilância térmica. [manual] = "Retomar agora" durante a pausa por temperatura. */
    private fun resume(manual: Boolean) {
        if (cooling) {
            val reading = guard.read()
            ThermalGuard.log(if (manual) "RETOMADA MANUAL" else "RETOMADA", reading, if (manual) "usuário pediu" else "esfriou")
            logEvent(ThermalLog.RESUME, if (manual) "manual: usuário pediu" else "automática: esfriou")
            QueueNotifications.thermalResumed(this, reading, manual)
            cooling = false
        }
        val r = runner ?: QueueRunner(this, this).also { runner = it }
        openLog()
        r.start()
        if (CompressionPrefs(this).queueThermalPause) guard.watch(disarm = manual) else guard.stop()
        QueueRepository.notifyChanged()
    }

    /** Sai da pausa por temperatura sem retomar: grava [reason] no resumo (null = não mexe) e encerra o serviço. */
    private fun endCooldown(reason: Int?, why: String) {
        ThermalGuard.log("FIM DA PAUSA SEM RETOMAR", guard.read(), why)
        if (reason == CompressionPrefs.LastRun.REASON_PAUSED) logEvent(ThermalLog.MANUAL_PAUSE, why)
        closeLog(why)
        cooling = false
        guard.stop()
        QueueNotifications.cancelThermal(this)
        if (reason != null) {
            val prefs = CompressionPrefs(this)
            prefs.lastRun?.let { prefs.lastRun = it.copy(reason = reason, updatedAt = System.currentTimeMillis()) }
        }
        QueueRepository.notifyChanged()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onHot(reading: ThermalGuard.Reading, emergency: Boolean) {
        val r = runner ?: return
        ThermalGuard.log(
            if (emergency) "PAUSA (EMERGÊNCIA)" else "PAUSA",
            reading,
            if (emergency) "limite de emergência; cancela o item atual" else "limite de pausa; termina o item atual",
        )
        logEvent(if (emergency) ThermalLog.EMERGENCY else ThermalLog.PAUSE, if (emergency) "cancela o item atual" else "pedida; termina o item atual")
        emergencyPause = emergencyPause || emergency
        if (emergency) r.pauseNow(QueueRunner.StopReason.THERMAL) else r.pause(QueueRunner.StopReason.THERMAL)
    }

    override fun onCooled(reading: ThermalGuard.Reading) = resume(manual = false)

    override fun onGaveUp(reading: ThermalGuard.Reading) {
        ThermalGuard.log("DESISTIU", reading, "não esfriou em ${THERMAL_MAX_PAUSE_MS / 60_000} min")
        logEvent(ThermalLog.GAVE_UP, "não esfriou em ${THERMAL_MAX_PAUSE_MS / 60_000} min")
        closeLog("desistência por temperatura")
        cooling = false
        guard.stop()
        CompressionPrefs(this).queueInterrupted = CompressionPrefs.INTERRUPTED_THERMAL
        QueueNotifications.thermalGaveUp(this, reading)
        QueueRepository.notifyChanged()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** O runner parou por temperatura: entra na pausa (ou encerra, se não sobrou nada para fazer). */
    private fun startCooldown(stats: QueueRunner.Stats) {
        val reading = guard.read()
        val emergency = emergencyPause
        emergencyPause = false
        cooling = true
        guard.onPaused()
        QueueRepository.io(this, { dao -> dao.getPending().isNotEmpty() }) { hasMore ->
            if (!cooling) return@io
            if (!hasMore) {
                endCooldown(CompressionPrefs.LastRun.REASON_FINISHED, "fila vazia ao pausar")
                QueueNotifications.summary(this, stats)
                return@io
            }
            ThermalGuard.log("EM PAUSA", reading, if (emergency) "item atual devolvido a aguardando" else "item atual terminado")
            logEvent(ThermalLog.PAUSE, if (emergency) "em pausa; item devolvido a aguardando" else "em pausa; item atual terminado")
            QueueNotifications.update(this, QueueNotifications.cooling(this, reading))
            QueueNotifications.thermalPaused(this, reading, emergency)
            QueueRepository.notifyChanged()
        }
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
        lastArgs = Args(item, index, total, percent)
        QueueNotifications.update(this, QueueNotifications.progress(this, item, index, total, percent, etaText))
        refreshEta()
    }

    /** Recalcula o tempo restante no máximo a cada 10 s (lê a fila no banco) e republica a notificação se o texto mudou. */
    private fun refreshEta() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEtaAt < ETA_REFRESH_MS) return
        lastEtaAt = now
        QueueRepository.io(this, { dao -> QueueEta.format(this, QueueEta.remaining(this, dao.getAll())) }) { text ->
            if (text == etaText) return@io
            etaText = text
            val args = lastArgs
            if (runner != null && args != null) {
                QueueNotifications.update(this, QueueNotifications.progress(this, args.item, args.index, args.total, args.percent, text))
            }
        }
    }

    override fun onStopped(reason: QueueRunner.StopReason, stats: QueueRunner.Stats) {
        runner = null
        lastArgs = null
        lastNotified = ""
        if (reason == QueueRunner.StopReason.THERMAL) {
            startCooldown(stats)
            return
        }
        guard.stop()
        emergencyPause = false
        closeLog("fila parou: $reason")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        when (reason) {
            QueueRunner.StopReason.FINISHED -> if (stats.done + stats.failed + stats.discarded + stats.canceled > 0) QueueNotifications.summary(this, stats)
            QueueRunner.StopReason.TIME_LIMIT -> QueueNotifications.timeLimit(this)
            QueueRunner.StopReason.PAUSED, QueueRunner.StopReason.THERMAL -> Unit
        }
        stopSelf()
    }

    /** Limite diário do tipo de serviço (mediaProcessing ou dataSync): cancela o item atual (volta a aguardar), avisa e sai em poucos segundos. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "limite de tempo do serviço em primeiro plano")
        closeLog("limite de tempo do Android")
        guard.stop()
        if (cooling) {
            cooling = false
            val prefs = CompressionPrefs(this)
            prefs.lastRun?.let { prefs.lastRun = it.copy(reason = CompressionPrefs.LastRun.REASON_TIME_LIMIT) }
        }
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
        guard.stop()
        cooling = false
        closeLog("serviço encerrado")
        super.onDestroy()
    }

    private fun logEvent(event: String, note: String) = ThermalLog.event(this, event, runner?.currentItemId, note)

    /** START e a sessão de amostras (a cada 60 s e a cada mudança de status), enquanto a fila roda ou está em pausa por calor. */
    private fun openLog() {
        if (logOpen) return
        logOpen = true
        logEvent(ThermalLog.START, "pausar se esquentar: " + if (CompressionPrefs(this).queueThermalPause) "ligada" else "desligada")
        ThermalLog.startSession(this) { runner?.currentItemId }
    }

    private fun closeLog(note: String) {
        if (!logOpen) return
        logOpen = false
        logEvent(ThermalLog.END, note)
        ThermalLog.stopSession(this)
    }

    companion object {
        private const val TAG = "CompressionQueue"
        const val ACTION_START = "org.fossify.gallery.compression.queue.START"
        const val ACTION_PAUSE = "org.fossify.gallery.compression.queue.PAUSE"
        const val ACTION_PAUSE_NOW = "org.fossify.gallery.compression.queue.PAUSE_NOW"
        const val ACTION_SKIP = "org.fossify.gallery.compression.queue.SKIP"
        const val ACTION_CANCEL_ALL = "org.fossify.gallery.compression.queue.CANCEL_ALL"
        const val ACTION_THERMAL_PREF = "org.fossify.gallery.compression.queue.THERMAL_PREF"
        private const val ETA_REFRESH_MS = 10_000L

        /** Pausada por temperatura neste processo: o serviço segue vivo, sem runner, esperando esfriar. */
        @Volatile
        var cooling = false
            private set

        /** Runner rodando ou pausa por temperatura em andamento: o serviço existe e aceita [send]. */
        val active get() = QueueRunner.running || cooling

        /** false se o sistema não deixou iniciar (ex.: limite diário do mediaProcessing já atingido). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, QueueService::class.java).setAction(ACTION_START))
            true
        } catch (e: Exception) {
            Log.e(TAG, "não foi possível iniciar o serviço da fila", e)
            false
        }

        fun send(context: Context, action: String) {
            if (!active) return
            runCatching { context.startService(Intent(context, QueueService::class.java).setAction(action)) }
        }
    }
}
