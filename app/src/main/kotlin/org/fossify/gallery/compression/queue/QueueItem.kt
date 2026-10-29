package org.fossify.gallery.compression.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.fossify.gallery.compression.engine.CompressionOptions
import org.fossify.gallery.compression.engine.Destination
import org.fossify.gallery.compression.ui.RulePlanner
import java.io.File

/** Um vídeo na fila. Opções já calculadas na adição; o vídeo é analisado de novo ao começar o item. */
@Entity(tableName = "queue_items", indices = [Index(value = ["state"]), Index(value = ["state", "position"])])
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Itens adicionados juntos; a confirmação de "apagar de vez" vale por lote. */
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "original_size") val originalSize: Long,
    @ColumnInfo(name = "estimated_size") val estimatedSize: Long,
    @ColumnInfo(name = "state") val state: Int = STATE_WAITING,
    @ColumnInfo(name = "phase") val phase: Int = PHASE_NONE,
    @ColumnInfo(name = "progress") val progress: Int = 0,
    /** Quantas vezes o processo morreu com este item em andamento. */
    @ColumnInfo(name = "deaths") val deaths: Int = 0,

    @ColumnInfo(name = "out_width") val outputWidth: Int?,
    @ColumnInfo(name = "out_height") val outputHeight: Int?,
    @ColumnInfo(name = "video_bitrate") val videoBitrate: Int,
    @ColumnInfo(name = "use_hevc") val useHevc: Boolean,
    @ColumnInfo(name = "audio_bitrate") val audioReencodeBitrate: Int?,
    @ColumnInfo(name = "audio_downmix") val audioDownmixToStereo: Boolean,
    @ColumnInfo(name = "quality_tag") val qualityTag: Int,
    /** DEST_MEDIA (destPath = RELATIVE_PATH) ou DEST_DIRECT (destPath = pasta). */
    @ColumnInfo(name = "dest_kind") val destKind: Int,
    @ColumnInfo(name = "dest_path") val destPath: String,
    /** CompressionPrefs.AFTER_*. */
    @ColumnInfo(name = "after") val after: Int,

    /** Arquivo de destino sendo escrito (fase SAVING), para apagar se o processo morrer. */
    @ColumnInfo(name = "partial_path") val partialPath: String? = null,
    @ColumnInfo(name = "result_path") val resultPath: String? = null,
    @ColumnInfo(name = "result_name") val resultName: String? = null,
    @ColumnInfo(name = "result_location") val resultLocation: String? = null,
    @ColumnInfo(name = "result_size") val resultSize: Long? = null,
    @ColumnInfo(name = "used_fallback") val usedFallback: Boolean = false,
    @ColumnInfo(name = "date_ok") val dateOk: Boolean = false,
    @ColumnInfo(name = "date_detail") val dateDetail: String? = null,
    /** Motivo da falha/descarte/cancelamento, ou a linha do original quando concluído. */
    @ColumnInfo(name = "message") val message: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),

    /** Dados para medir a velocidade (pixels por segundo): resolução de SAÍDA, fps e duração do vídeo. */
    @ColumnInfo(name = "video_width") val videoWidth: Int = 0,
    @ColumnInfo(name = "video_height") val videoHeight: Int = 0,
    @ColumnInfo(name = "fps") val fps: Float = 0f,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    /** Início do processamento do item e fim (qualquer desfecho). */
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    /** Tempo só da compressão (Transformer), sem gravação e verificação. */
    @ColumnInfo(name = "compress_ms") val compressMs: Long? = null,

    /** Ordem de execução dos aguardando (menor primeiro; empate pelo id). Definida em [QueueRepository.add]. */
    @ColumnInfo(name = "position", defaultValue = "0") val position: Long = 0,
    /** Escolhas da adição, para a edição: RulePlanner.PRESET_* / ordinal do preset (-1 = item anterior à migração 3→4). */
    @ColumnInfo(name = "preset", defaultValue = "-1") val preset: Int = RulePlanner.PRESET_UNKNOWN,
    /** % do bitrate de referência no Personalizado. */
    @ColumnInfo(name = "custom_pct") val customPct: Int? = null,
    /** Lado menor aplicado; null = resolução original. */
    @ColumnInfo(name = "short_side") val shortSide: Int? = null,
) {
    val name: String get() = File(path).name
    val isPending: Boolean get() = state == STATE_WAITING || state == STATE_RUNNING

    fun options() = CompressionOptions(
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        videoBitrate = videoBitrate,
        useHevc = useHevc,
        audioReencodeBitrate = audioReencodeBitrate,
        audioDownmixToStereo = audioDownmixToStereo,
        qualityTag = qualityTag,
    )

    fun destination(): Destination =
        if (destKind == DEST_MEDIA) Destination.MediaFolder(destPath) else Destination.Direct(destPath)

    companion object {
        const val STATE_WAITING = 0
        const val STATE_RUNNING = 1
        const val STATE_DONE = 2
        const val STATE_FAILED = 3
        const val STATE_DISCARDED = 4
        const val STATE_CANCELED = 5

        /** Fase dentro do item, para recuperação depois de o processo morrer. */
        const val PHASE_NONE = 0
        const val PHASE_COMPRESSING = 1
        const val PHASE_SAVING = 2
        /** Já gravado no destino e com data; falta só verificar e tratar o original. */
        const val PHASE_PUBLISHED = 3

        const val DEST_MEDIA = 0
        const val DEST_DIRECT = 1

        fun create(
            batchId: Long, path: String, originalSize: Long, estimatedSize: Long, options: CompressionOptions, destination: Destination, after: Int,
            videoWidth: Int, videoHeight: Int, fps: Float, durationMs: Long,
            preset: Int, customPct: Int?, shortSide: Int?,
        ) =
            QueueItem(
                batchId = batchId,
                path = path,
                originalSize = originalSize,
                estimatedSize = estimatedSize,
                outputWidth = options.outputWidth,
                outputHeight = options.outputHeight,
                videoBitrate = options.videoBitrate,
                useHevc = options.useHevc,
                audioReencodeBitrate = options.audioReencodeBitrate,
                audioDownmixToStereo = options.audioDownmixToStereo,
                qualityTag = options.qualityTag,
                destKind = if (destination is Destination.MediaFolder) DEST_MEDIA else DEST_DIRECT,
                destPath = when (destination) {
                    is Destination.MediaFolder -> destination.relativePath
                    is Destination.Direct -> destination.folderPath
                },
                after = after,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                fps = fps,
                durationMs = durationMs,
                preset = preset,
                customPct = customPct,
                shortSide = shortSide,
            )
    }
}
