package org.fossify.gallery.compression.mark

import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.queue.CompressedKeyEntry
import org.fossify.gallery.compression.queue.QueueDatabase
import org.fossify.gallery.compression.ui.CompressedName
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Detecção de "já comprimido" pelas três camadas: A = sufixo _comp_q<N> no nome, B = [Mp4Mark] dentro do arquivo, C = chave
 * tamanho + duração no banco. O selo é sempre q<N>. A lista da galeria usa C e A (sem abrir arquivos); B só é lida ao tocar em
 * Comprimir (vem na análise) e, se divergir, vale a marca. As chaves do banco são carregadas uma vez e ficam em memória.
 */
object CompressedMarks {
    private const val TAG = "CompressedMarks"
    private const val MEDIASTORE_RETRIES = 4
    private const val MEDIASTORE_RETRY_MS = 500L

    private data class Key(val size: Long, val durationS: Int)

    private val keys = ConcurrentHashMap<Key, Int>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "compressed-marks").apply { isDaemon = true } }

    @Volatile
    private var loaded = false

    /** Duração como o Fossify a guarda no `Medium`: segundos arredondados. */
    fun durationSeconds(ms: Long) = (ms / 1000.0).roundToInt()

    /** Selo da lista: banco (C), senão nome (A). Não lê arquivos; antes de as chaves carregarem, só o nome. */
    fun badge(medium: Medium): String? {
        val fromKey = if (medium.isVideo()) lookup(medium.size, medium.videoDuration) else null
        return (fromKey ?: CompressedName.quality(medium.name))?.let { "q$it" }
    }

    /**
     * Detecção completa ao comprimir, com a análise já feita: marca no MP4 (B) > banco (C) > nome (A). Uma marca que o banco
     * ainda não tem (ou com outro N) passa a valer também para a lista. Bloqueante.
     */
    fun detect(context: Context, path: String, info: VideoInfo): Int? {
        ensureLoaded(context)
        val key = Key(info.fileSize, durationSeconds(info.durationMs))
        info.mark?.let { q ->
            if (key.size > 0 && key.durationS > 0 && keys[key] != q) store(context, listOf(entry(key, q, CompressedKeyEntry.SOURCE_MARK)))
            return q
        }
        return lookup(key.size, key.durationS) ?: CompressedName.quality(File(path).name)
    }

    /**
     * Chave de um resultado novo, com o tamanho e a duração que o MediaStore informa depois do scan (os mesmos que a galeria vai
     * listar). Se o MediaStore ainda não tiver a linha, usa o tamanho do arquivo e a duração lida dele. Bloqueante.
     */
    fun registerResult(context: Context, file: File, quality: Int) {
        ensureLoaded(context)
        var size = 0L
        var durationMs = 0L
        for (attempt in 0 until MEDIASTORE_RETRIES) {
            if (attempt > 0) Thread.sleep(MEDIASTORE_RETRY_MS)
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DURATION),
                    "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.absolutePath), null
                )?.use {
                    if (it.moveToFirst()) {
                        size = it.getLong(0)
                        durationMs = it.getLong(1)
                    }
                }
            }
            if (size > 0 && durationMs > 0) break
        }
        if (size <= 0 || durationMs <= 0) {
            Log.w(TAG, "MediaStore sem tamanho/duração de ${file.name}; usando o arquivo")
            size = file.length()
            durationMs = runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.absolutePath)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        release()
                    }
                }
            }.getOrDefault(0L)
        }
        val key = Key(size, durationSeconds(durationMs))
        if (key.size > 0 && key.durationS > 0) store(context, listOf(entry(key, quality, CompressedKeyEntry.SOURCE_RESULT)))
    }

    /**
     * A galeria listou estes itens: vídeos com _comp_q<N> no nome cuja chave ainda não está no banco ganham a chave com esse N
     * (adoção dos comprimidos antes da Etapa 7), em segundo plano e em lote, sem abrir os arquivos. [onKeysLoaded] roda (em
     * segundo plano) se as chaves acabaram de ser carregadas e mudam o selo de algum item listado.
     */
    fun onListed(context: Context, media: List<ThumbnailItem>, onKeysLoaded: () -> Unit) {
        val appContext = context.applicationContext
        val videos = media.filterIsInstance<Medium>().filter { it.isVideo() && it.size > 0 && it.videoDuration > 0 }
        executor.execute {
            val firstLoad = !loaded
            ensureLoaded(appContext)
            val adopted = videos.mapNotNull { medium ->
                val key = Key(medium.size, medium.videoDuration)
                val q = CompressedName.quality(medium.name)
                if (q == null || keys.containsKey(key)) null else entry(key, q, CompressedKeyEntry.SOURCE_NAME)
            }.distinctBy { it.size to it.durationS }
            if (adopted.isNotEmpty()) {
                store(appContext, adopted)
                Log.i(TAG, "${adopted.size} vídeo(s) adotado(s) pelo nome")
            }
            if (firstLoad && videos.any { lookup(it.size, it.videoDuration).let { q -> q != null && q != CompressedName.quality(it.name) } }) {
                onKeysLoaded()
            }
        }
    }

    private fun lookup(size: Long, durationS: Int): Int? = if (size > 0 && durationS > 0) keys[Key(size, durationS)] else null

    private fun entry(key: Key, quality: Int, source: Int) =
        CompressedKeyEntry(key.size, key.durationS, quality, source, System.currentTimeMillis())

    private fun store(context: Context, entries: List<CompressedKeyEntry>) {
        entries.forEach { keys[Key(it.size, it.durationS)] = it.quality }
        runCatching { QueueDatabase.getInstance(context).CompressedKeyDao().putAll(entries) }
            .onFailure { Log.w(TAG, "não foi possível gravar as chaves", it) }
    }

    /** Carrega as chaves do banco uma vez por processo. Bloqueante; não chamar na thread principal. */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { QueueDatabase.getInstance(context).CompressedKeyDao().getAll() }
                .onSuccess { all -> all.forEach { keys.putIfAbsent(Key(it.size, it.durationS), it.quality) } }
                .onFailure { Log.w(TAG, "não foi possível carregar as chaves", it) }
            loaded = true
        }
    }
}
