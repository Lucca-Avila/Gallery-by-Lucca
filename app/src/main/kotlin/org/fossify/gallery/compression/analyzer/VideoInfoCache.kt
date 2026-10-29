package org.fossify.gallery.compression.analyzer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.fossify.gallery.compression.mark.Mp4Mark
import org.fossify.gallery.compression.queue.QueueDatabase
import org.fossify.gallery.compression.queue.VideoInfoDao
import org.fossify.gallery.compression.queue.VideoInfoEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Análise de vídeo com cache e fora do ciclo de vida das telas. [analyze] consulta o cache (caminho + tamanho + data de
 * modificação) antes de ler o arquivo; [submit] roda a análise numa fila própria do processo, que continua mesmo se o diálogo
 * ou a tela que a pediu for fechada (o resultado fica no cache para a próxima vez). Pedidos repetidos do mesmo arquivo
 * enquanto ele ainda está sendo analisado esperam pelo mesmo resultado.
 */
@OptIn(UnstableApi::class)
object VideoInfoCache {
    private const val TAG = "VideoInfoCache"
    private const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    private const val WAIT_SLICE_MS = 300L

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "video-analysis").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap<String, Future<VideoInfo>>()

    /** Em segundo plano, sem cancelamento; chamadas repetidas para o mesmo [path] compartilham a análise em andamento. */
    fun submit(context: Context, path: String, uri: Uri): Future<VideoInfo> {
        val appContext = context.applicationContext
        return inFlight.computeIfAbsent(path) {
            executor.submit<VideoInfo> {
                try {
                    analyze(appContext, path, uri)
                } finally {
                    inFlight.remove(path)
                }
            }
        }
    }

    /** Espera a análise de [future]; devolve null se [wanted] virar falso antes (a análise continua e vai para o cache). */
    fun await(future: Future<VideoInfo>, wanted: () -> Boolean): VideoInfo? {
        while (wanted()) {
            try {
                return future.get(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // continua esperando
            }
        }
        return null
    }

    /**
     * Varredura completa (bitrates e fps medidos), bloqueante e cancelável por [isCancelled]. Substitui no cache a análise
     * estimada; cancelada, não grava nada. Registra no log a comparação estimado × medido.
     */
    fun measure(context: Context, path: String, uri: Uri, isCancelled: () -> Boolean): VideoInfo {
        val file = File(path)
        val size = file.takeIf { it.isFile }?.length() ?: -1L
        val modified = file.lastModified()
        val dao = if (size > 0 && modified > 0) runCatching { QueueDatabase.getInstance(context).VideoInfoDao() }.getOrNull() else null
        val estimate = runCatching { lookup(dao?.get(path), size, modified) }.getOrNull()?.takeIf { !it.scanned }
            ?: VideoAnalyzer.analyze(context, uri, isCancelled)
        val measured = withMark(file, VideoAnalyzer.analyze(context, uri, isCancelled, precise = true))
        if (isCancelled()) return measured
        logComparison(file.name, estimate, measured)
        if (dao != null) store(dao, path, size, modified, measured)
        return measured
    }

    private fun logComparison(name: String, estimate: VideoInfo, measured: VideoInfo) {
        val eb = estimate.videoBitrate
        val mb = measured.videoBitrate
        val bitrateError = if (eb != null && mb != null && mb.bps > 0) (eb.bps - mb.bps) * 100.0 / mb.bps else null
        val fpsError = if (estimate.fps != null && measured.fps != null && measured.fps > 0) (estimate.fps - measured.fps) * 100.0 / measured.fps else null
        Log.i(
            TAG,
            "estimado x medido de $name: bitrate ${eb?.bps}(${eb?.source}) x ${mb?.bps}(${mb?.source}) erro=${bitrateError?.let { "%.1f%%".format(it) }} | " +
                "fps ${estimate.fps}(${estimate.fpsSource}) x ${measured.fps}(${measured.fpsSource}) erro=${fpsError?.let { "%.1f%%".format(it) }} | " +
                "declarado=${measured.declaredVideoBitrate}"
        )
    }

    private fun store(dao: VideoInfoDao, path: String, size: Long, modified: Long, info: VideoInfo) {
        runCatching {
            dao.put(VideoInfoEntry(path, size, modified, VideoInfoJson.SCHEMA, Build.VERSION.INCREMENTAL, VideoInfoJson.toJson(info), System.currentTimeMillis()))
            dao.prune(System.currentTimeMillis() - MAX_AGE_MS)
        }.onFailure { Log.w(TAG, "não foi possível gravar a análise no cache", it) }
    }

    /** Bloqueante. Só grava no cache análises completas (não canceladas). */
    fun analyze(context: Context, path: String, uri: Uri, isCancelled: () -> Boolean = { false }): VideoInfo {
        val file = File(path)
        // Caminhos sem arquivo real (ex.: OTG/SAF) não têm tamanho/data confiáveis para a chave: sem cache.
        val size = file.takeIf { it.isFile }?.length() ?: -1L
        val modified = file.lastModified()
        val usable = size > 0 && modified > 0
        val dao = if (usable) runCatching { QueueDatabase.getInstance(context).VideoInfoDao() }.getOrNull() else null
        if (dao != null) {
            val hit = runCatching { lookup(dao.get(path), size, modified) }.getOrNull()
            if (hit != null) {
                Log.i(TAG, "análise vinda do cache: ${file.name}")
                return hit
            }
        }
        val info = withMark(file, VideoAnalyzer.analyze(context, uri, isCancelled))
        if (dao != null && !isCancelled()) store(dao, path, size, modified, info)
        return info
    }

    /** A marca do MP4 é lida junto com a análise (só ao comprimir ou abrir as informações), nunca pela lista. */
    private fun withMark(file: File, info: VideoInfo): VideoInfo =
        if (file.isFile) info.copy(mark = Mp4Mark.read(file)) else info

    private fun lookup(entry: VideoInfoEntry?, size: Long, modified: Long): VideoInfo? =
        entry?.takeIf { it.size == size && it.modified == modified && it.schemaVersion == VideoInfoJson.SCHEMA && it.build == Build.VERSION.INCREMENTAL }
            ?.let { VideoInfoJson.fromJson(it.json) }
}
