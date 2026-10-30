package org.fossify.gallery.compression.queue

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.compression.analyzer.VideoInfoCache
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.engine.CompressionException
import org.fossify.gallery.compression.engine.CompressionPipeline
import org.fossify.gallery.compression.engine.CompressionResult
import org.fossify.gallery.compression.engine.DatePreserver
import org.fossify.gallery.compression.engine.PublishedFile
import org.fossify.gallery.compression.engine.ResultVerifier
import org.fossify.gallery.compression.engine.VideoCompressor
import org.fossify.gallery.compression.ui.CompressionPrefs
import java.io.File
import kotlin.math.roundToInt

/**
 * Processa a fila, um item por vez. Vive dentro do QueueService e roda na thread principal (o Transformer exige um Looper);
 * análise e pós-processamento vão para threads de fundo. A falha de um item não para os demais.
 */
@UnstableApi
class QueueRunner(context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        /** [index] e [total] contam a partir do início desta execução ("item i de N"). [percent] = null na gravação/verificação. */
        fun onProgress(item: QueueItem, index: Int, total: Int, percent: Int?)
        fun onStopped(reason: StopReason, stats: Stats)
    }

    /** PAUSED = pausa manual; THERMAL = pausa automática por temperatura (ThermalGuard), a única que o app retoma sozinho. */
    enum class StopReason { FINISHED, PAUSED, TIME_LIMIT, THERMAL }

    data class Stats(
        var done: Int = 0, var failed: Int = 0, var discarded: Int = 0, var canceled: Int = 0, var savedBytes: Long = 0,
        var thermalPauses: Int = 0, var thermalAt: Long = 0,
    )

    private val context = context.applicationContext
    private val stats = Stats()
    private var processed = 0
    @Volatile
    private var current: QueueItem? = null
    private var compressor: VideoCompressor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pauseAfterCurrent = false
    private var pauseReason = StopReason.PAUSED
    private var stopReason: StopReason? = null
    private var skipRequested = false
    private var lastPercent = -1
    private var active = false

    fun start() {
        if (active) return
        active = true
        running = true
        val prefs = CompressionPrefs(context)
        prefs.queueInterrupted = CompressionPrefs.INTERRUPTED_NONE
        // O resumo acumula entre pausas e retomadas; recomeça quando a execução anterior esvaziou a fila.
        val previous = prefs.lastRun?.takeIf { it.reason != CompressionPrefs.LastRun.REASON_FINISHED } ?: CompressionPrefs.LastRun()
        stats.done = previous.done
        stats.failed = previous.failed
        stats.discarded = previous.discarded
        stats.canceled = previous.canceled
        stats.savedBytes = previous.savedBytes
        stats.thermalPauses = previous.thermalPauses
        stats.thermalAt = previous.thermalAt
        persistRun(CompressionPrefs.LastRun.REASON_RUNNING)
        QueueRepository.notifyChanged()
        next()
    }

    /** Item em andamento; pode ser lido de outra thread (ThermalLog). */
    val currentItemId get() = current?.id

    /** Pausa manual já pedida: prevalece sobre a térmica (a fila não retoma sozinha). */
    private val manualPauseRequested get() = pauseAfterCurrent && (stopReason ?: pauseReason) == StopReason.PAUSED

    /** Termina o item atual e para. */
    fun pause(reason: StopReason = StopReason.PAUSED) {
        if (reason == StopReason.THERMAL && pauseAfterCurrent) return
        pauseReason = reason
        if (reason == StopReason.PAUSED && stopReason == StopReason.THERMAL) stopReason = StopReason.PAUSED
        pauseAfterCurrent = true
        if (current == null) stop(reason)
    }

    /**
     * Cancela o item atual (que volta a aguardar) e para. Se já estiver gravando, espera terminar. Na compressão, o
     * Transformer só lê o original; o resultado parcial é o temporário em cache, apagado pelo cancel().
     */
    fun pauseNow(reason: StopReason = StopReason.PAUSED) {
        val effective = if (reason == StopReason.THERMAL && manualPauseRequested) StopReason.PAUSED else reason
        stopReason = effective
        pauseAfterCurrent = true
        val item = current ?: return stop(effective)
        val c = compressor ?: return
        val tempGone = c.cancel()
        compressor = null
        if (reason == StopReason.THERMAL) {
            Log.i(ThermalGuard.TAG, "item cancelado na emergência: ${item.name} | temporário apagado: $tempGone | original intocado: ${item.path}")
        }
        endItem(item.copy(state = QueueItem.STATE_WAITING, phase = QueueItem.PHASE_NONE, progress = 0, partialPath = null), counted = false)
    }

    /** Cancela só o item atual e segue para o próximo. Sem efeito durante a gravação/verificação. */
    fun skipCurrent() {
        val item = current ?: return
        val c = compressor
        if (c == null) {
            skipRequested = true
            return
        }
        c.cancel()
        compressor = null
        stats.canceled++
        endItem(item.copy(state = QueueItem.STATE_CANCELED, phase = QueueItem.PHASE_NONE, message = context.getString(R.string.cmp_q_skipped)))
    }

    /** Cancela todos os aguardando e o item atual; a fila para quando não sobrar nada. */
    fun cancelAll() {
        QueueRepository.cancelWaiting(context) { count ->
            stats.canceled += count
            persistRun(CompressionPrefs.LastRun.REASON_RUNNING)
        }
        skipCurrent()
    }

    private fun next() {
        if (pauseAfterCurrent) {
            stop(stopReason ?: pauseReason)
            return
        }
        // Pegar o próximo e marcá-lo como em andamento é uma operação só: remover ou cancelar na tela não o pega no meio.
        QueueRepository.io(context, { dao ->
            dao.firstWaiting()?.let { waiting ->
                val resume = waiting.phase == QueueItem.PHASE_PUBLISHED
                val item = if (resume) {
                    waiting.copy(state = QueueItem.STATE_RUNNING, progress = 0, phase = QueueItem.PHASE_PUBLISHED)
                } else {
                    waiting.copy(
                        state = QueueItem.STATE_RUNNING, progress = 0, phase = QueueItem.PHASE_COMPRESSING,
                        startedAt = System.currentTimeMillis(), finishedAt = null, compressMs = null,
                    )
                }
                dao.update(item)
                item to processed + dao.getPending().size
            }
        }) { found ->
            if (!active) return@io
            if (found == null) stop(StopReason.FINISHED) else process(found.first, found.second)
        }
    }

    private fun process(item: QueueItem, total: Int) {
        val resume = item.phase == QueueItem.PHASE_PUBLISHED
        current = item
        skipRequested = false
        lastPercent = -1
        acquireWakeLock()
        val index = processed + 1
        QueueRepository.notifyChanged()
        callbacks.onProgress(item, index, total, if (resume) null else 0)

        ensureBackgroundThread {
            val info = try {
                analyze(item)
            } catch (e: Exception) {
                runOnMain { fail(item, e.message ?: e.javaClass.simpleName) }
                return@ensureBackgroundThread
            }
            if (resume) {
                resumePublished(item, info, index, total)
                return@ensureBackgroundThread
            }
            if (!VideoCompressor.hasSpaceFor(context, info.fileSize)) {
                runOnMain { fail(item, context.getString(R.string.cmp_err_space)) }
                return@ensureBackgroundThread
            }
            runOnMain { compress(item, info, index, total) }
        }
    }

    private fun analyze(item: QueueItem): VideoInfo {
        val file = File(item.path)
        if (!file.exists()) throw CompressionException(context.getString(R.string.cmp_q_original_missing))
        val info = VideoInfoCache.analyze(context, item.path, Uri.fromFile(file))
        val decoder = info.videoDecoder
        if (info.videoMime == null || (decoder != null && !decoder.supported)) {
            throw CompressionException(context.getString(R.string.cmp_err_no_decoder, info.videoMime ?: context.getString(R.string.cmp_unknown)))
        }
        return info
    }

    private fun compress(item: QueueItem, info: VideoInfo, index: Int, total: Int) {
        if (current?.id != item.id) return
        if (skipRequested || pauseAfterCurrent && stopReason != null) {
            if (skipRequested) {
                stats.canceled++
                endItem(item.copy(state = QueueItem.STATE_CANCELED, phase = QueueItem.PHASE_NONE, message = context.getString(R.string.cmp_q_skipped)))
            } else {
                endItem(item.copy(state = QueueItem.STATE_WAITING, phase = QueueItem.PHASE_NONE, progress = 0), counted = false)
            }
            return
        }
        val c = VideoCompressor(context).also { compressor = it }
        c.start(Uri.fromFile(File(item.path)), item.options(), object : VideoCompressor.Listener {
            override fun onProgress(percent: Int) {
                if (percent == lastPercent) return
                lastPercent = percent
                QueueRepository.io(context, { dao -> dao.updateProgress(item.id, percent) }) { QueueRepository.notifyChanged() }
                callbacks.onProgress(item, index, total, percent)
            }

            override fun onSuccess(result: CompressionResult) {
                compressor = null
                callbacks.onProgress(item, index, total, null)
                postProcess(item, info, result)
            }

            override fun onError(error: CompressionException) {
                compressor = null
                fail(item, error.message ?: "")
            }
        })
    }

    /** "Apagar de vez" só vale com a confirmação dada neste processo; sem ela, o original é mantido. */
    private fun effectiveAfter(item: QueueItem): Int =
        if (item.after == CompressionPrefs.AFTER_DELETE && !QueueRepository.hasDeleteConsent(item.batchId)) CompressionPrefs.AFTER_KEEP else item.after

    private fun pipeline(item: QueueItem, info: VideoInfo) = CompressionPipeline(
        context, item.path, info, item.options(), item.destination(), effectiveAfter(item), ServiceOriginalRemover(context),
        object : CompressionPipeline.Hooks {
            override fun onTarget(file: File) {
                saveNow(item.copy(phase = QueueItem.PHASE_SAVING, partialPath = file.absolutePath, progress = 100))
            }

            override fun onPublished(newSize: Long, published: PublishedFile, dateCheck: DatePreserver.Check) {
                saveNow(
                    item.copy(
                        phase = QueueItem.PHASE_PUBLISHED, partialPath = null, progress = 100,
                        resultPath = published.file.absolutePath, resultName = published.name, resultLocation = published.location,
                        resultSize = newSize, usedFallback = published.usedFallback, dateOk = dateCheck.ok, dateDetail = dateCheck.detail,
                    )
                )
            }
        },
    )

    /** Grava a fase de forma síncrona (estamos na thread do pipeline): precisa estar no banco antes do próximo passo. */
    private fun saveNow(item: QueueItem) {
        val latch = java.util.concurrent.CountDownLatch(1)
        QueueRepository.io(context, { dao ->
            try {
                dao.update(item)
            } finally {
                latch.countDown()
            }
        })
        latch.await()
        QueueRepository.notifyChanged()
    }

    private fun postProcess(item: QueueItem, info: VideoInfo, result: CompressionResult) {
        val saving = item.copy(phase = QueueItem.PHASE_SAVING, progress = 100, compressMs = result.elapsedMs)
        ensureBackgroundThread {
            saveNow(saving)
            pipeline(saving, info).run(result, onStage = {}) { outcome -> runOnMain { finishOutcome(item, outcome) } }
        }
    }

    private fun resumePublished(item: QueueItem, info: VideoInfo, index: Int, total: Int) {
        val file = item.resultPath?.let { File(it) }
        val size = item.resultSize
        if (file == null || size == null || !file.exists()) {
            runOnMain { fail(item, context.getString(R.string.cmp_q_result_missing)) }
            return
        }
        runOnMain { callbacks.onProgress(item, index, total, null) }
        val published = PublishedFile(item.resultName ?: file.name, item.resultLocation ?: file.parent.orEmpty(), item.usedFallback, file)
        val dateCheck = DatePreserver.Check(item.dateOk, item.dateDetail.orEmpty())
        pipeline(item, info).finish(size, published, dateCheck, onStage = {}) { outcome -> runOnMain { finishOutcome(item, outcome) } }
    }

    private fun finishOutcome(item: QueueItem, outcome: CompressionPipeline.Outcome) {
        // Relê o item: os ganchos gravaram o resultado e a fase no banco.
        QueueRepository.io(context, { dao -> dao.get(item.id) ?: item }) { saved ->
            when (outcome) {
                is CompressionPipeline.Outcome.Discarded -> {
                    stats.discarded++
                    val pct = if (outcome.originalSize > 0) (outcome.newSize * 100.0 / outcome.originalSize).roundToInt() else 0
                    endItem(
                        saved.copy(
                            state = QueueItem.STATE_DISCARDED, phase = QueueItem.PHASE_NONE, resultSize = outcome.newSize,
                            message = context.getString(R.string.cmp_q_discarded, outcome.newSize.formatSize(), outcome.originalSize.formatSize(), pct),
                        )
                    )
                }

                is CompressionPipeline.Outcome.Failed -> fail(saved, outcome.message)
                is CompressionPipeline.Outcome.Done -> {
                    stats.done++
                    stats.savedBytes += (saved.originalSize - outcome.newSize).coerceAtLeast(0)
                    val consentLost = item.after == CompressionPrefs.AFTER_DELETE && effectiveAfter(item) == CompressionPrefs.AFTER_KEEP
                    val lines = listOfNotNull(
                        if (consentLost) context.getString(R.string.cmp_q_delete_consent_lost) else outcome.originalLine,
                        if (outcome.dateCheck.ok) null else context.getString(R.string.cmp_result_date_fail, outcome.dateCheck.detail),
                        if (outcome.published.usedFallback) context.getString(R.string.cmp_result_fallback) else null,
                    )
                    endItem(
                        saved.copy(
                            state = QueueItem.STATE_DONE, progress = 100, resultSize = outcome.newSize,
                            resultName = outcome.published.name, resultLocation = outcome.published.location,
                            resultPath = outcome.published.file.absolutePath, message = lines.joinToString("\n"),
                        )
                    )
                }
            }
        }
    }

    private fun fail(item: QueueItem, message: String) {
        stats.failed++
        endItem(item.copy(state = QueueItem.STATE_FAILED, partialPath = null, message = message))
    }

    /** Grava o desfecho do item, libera o wake lock e segue para o próximo. */
    private fun endItem(item: QueueItem, counted: Boolean = true) {
        if (current?.id != item.id) return
        current = null
        if (counted) processed++
        releaseWakeLock()
        val ended = if (item.state == QueueItem.STATE_WAITING) item else item.copy(finishedAt = System.currentTimeMillis())
        if (counted) persistRun(CompressionPrefs.LastRun.REASON_RUNNING)
        QueueEta.record(CompressionPrefs(context), ended)
        QueueRepository.io(context, { dao -> dao.update(ended) }) {
            QueueRepository.notifyChanged()
            if (ended.state == QueueItem.STATE_DONE) QueueRepository.notifyCompleted()
            next()
        }
    }

    private fun stop(reason: StopReason) {
        if (!active) return
        active = false
        running = false
        releaseWakeLock()
        if (reason == StopReason.TIME_LIMIT) CompressionPrefs(context).queueInterrupted = CompressionPrefs.INTERRUPTED_TIME_LIMIT
        if (reason == StopReason.THERMAL) {
            stats.thermalPauses++
            stats.thermalAt = System.currentTimeMillis()
        }
        persistRun(
            when (reason) {
                StopReason.FINISHED -> CompressionPrefs.LastRun.REASON_FINISHED
                StopReason.PAUSED -> CompressionPrefs.LastRun.REASON_PAUSED
                StopReason.TIME_LIMIT -> CompressionPrefs.LastRun.REASON_TIME_LIMIT
                StopReason.THERMAL -> CompressionPrefs.LastRun.REASON_THERMAL
            }
        )
        QueueRepository.notifyChanged()
        callbacks.onStopped(reason, stats)
    }

    /** Grava o resumo acumulado, para a tela da fila mostrar mesmo com as notificações bloqueadas. */
    private fun persistRun(reason: Int) {
        CompressionPrefs(context).lastRun = CompressionPrefs.LastRun(
            stats.done, stats.failed, stats.discarded, stats.canceled, stats.savedBytes, System.currentTimeMillis(), reason,
            stats.thermalPauses, stats.thermalAt,
        )
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "galeria:compression-item").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun runOnMain(block: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(block)

    companion object {
        /** Teto de segurança por item; o wake lock é solto antes ao terminar o item. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /** Há uma execução ativa neste processo (a tela da fila mantém a tela ligada enquanto for true). */
        @Volatile
        var running = false
            private set
    }
}
