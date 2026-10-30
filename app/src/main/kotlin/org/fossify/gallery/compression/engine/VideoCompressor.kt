package org.fossify.gallery.compression.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import org.fossify.gallery.R
import java.io.File
import java.util.UUID

/** Tempo máximo sem amostras no gravador antes do ERROR_CODE_MUXING_TIMEOUT (padrão do Media3: 10 s). */
const val MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS = 60_000L

/**
 * Comprime UM vídeo para um arquivo temporário no cache. Não conhece Activity: recebe Context, Uri e
 * opções. Deve ser iniciado numa thread com Looper (a principal); os callbacks voltam nessa thread.
 * Copiar o temporário para o destino final é responsabilidade de quem chama (ver CompressionOutput).
 */
@UnstableApi
class VideoCompressor(context: Context) {
    interface Listener {
        fun onProgress(percent: Int)
        fun onSuccess(result: CompressionResult)
        fun onError(error: CompressionException)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var tempFile: File? = null
    private var finished = false

    fun start(input: Uri, options: CompressionOptions, listener: Listener) {
        check(transformer == null) { "VideoCompressor já foi iniciado" }
        try {
            startInternal(input, options, listener)
        } catch (e: Exception) {
            // Qualquer falha ao montar/iniciar vira erro claro; nunca propaga e trava quem chamou
            finish()
            tempFile?.delete()
            listener.onError(CompressionException("Não foi possível iniciar a compressão: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private fun startInternal(input: Uri, options: CompressionOptions, listener: Listener) {
        val out = newTempFile(appContext).also { tempFile = it }
        val startedAt = SystemClock.elapsedRealtime()

        // Definir AudioEncoderSettings força a recodificação; sem isso o AAC passa direto
        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(options.videoBitrate).build()
            )
            .apply {
                options.audioReencodeBitrate?.let {
                    setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(it).build())
                }
            }
            .build()

        val videoEffects = if (options.outputWidth != null && options.outputHeight != null) {
            listOf(Presentation.createForWidthAndHeight(options.outputWidth, options.outputHeight, Presentation.LAYOUT_SCALE_TO_FIT))
        } else {
            emptyList()
        }
        val audioEffects: List<AudioProcessor> =
            if (options.audioDownmixToStereo) listOf(StereoDownmix.newProcessor()) else emptyList()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(audioEffects, videoEffects))
            .build()

        val t = Transformer.Builder(appContext)
            .setVideoMimeType(if (options.useHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setMaxDelayBetweenMuxerSamplesMs(MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS)
            .setLooper(Looper.getMainLooper())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    val bitrate = exportResult.averageVideoBitrate.takeIf { it > 0 }?.toLong()
                        ?: exportResult.fileSizeBytes.takeIf { it > 0 && exportResult.durationMs > 0 }
                            ?.let { it * 8_000L / exportResult.durationMs }
                    listener.onSuccess(
                        CompressionResult(out, SystemClock.elapsedRealtime() - startedAt, options.videoBitrate, bitrate)
                    )
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish()
                    out.delete()
                    if (exportException.errorCode == ExportException.ERROR_CODE_MUXING_TIMEOUT) {
                        val seconds = (MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS / 1000).toInt()
                        listener.onError(
                            CompressionException(appContext.getString(R.string.cmp_err_muxer_timeout, seconds), exportException, muxingTimeout = true)
                        )
                        return
                    }
                    val audio =exportException.errorCodeName.contains("AUDIO") || exportException.errorCodeName.contains("ENCODER")
                    val prefix = when {
                        audio && options.audioDownmixToStereo -> "Falha no áudio (mixagem para estéreo/AAC): "
                        audio && options.audioReencodeBitrate != null -> "Falha no áudio (recodificação para AAC): "
                        else -> ""
                    }
                    listener.onError(
                        CompressionException(
                            "$prefix${exportException.errorCodeName}: ${exportException.message ?: ""}".trim(),
                            exportException
                        )
                    )
                }
            })
            .build()
        transformer = t
        t.start(item, out.absolutePath)

        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                if (finished) return
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    listener.onProgress(holder.progress)
                }
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        })
    }

    /** Cancela e apaga o temporário. Não dispara callback. true se cancelou e o temporário não existe mais. */
    fun cancel(): Boolean {
        if (finished) return false
        finish()
        transformer?.cancel()
        tempFile?.delete()
        return tempFile?.exists() != true
    }

    private fun finish() {
        finished = true
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val DIR = "compress"

        private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

        fun newTempFile(context: Context): File = File(dir(context), "${UUID.randomUUID()}.mp4")

        /** Apaga temporários deixados por execuções interrompidas. Chamar quando nenhuma compressão está ativa. */
        fun cleanOrphans(context: Context) {
            dir(context).listFiles()?.forEach { it.delete() }
        }

        fun hasSpaceFor(context: Context, bytes: Long): Boolean = dir(context).usableSpace > bytes
    }
}
