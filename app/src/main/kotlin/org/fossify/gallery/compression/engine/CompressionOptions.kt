package org.fossify.gallery.compression.engine

/** Resolução de saída; null = manter a original. Valores já com proporção preservada e pares. */
data class CompressionOptions(
    val outputWidth: Int?,
    val outputHeight: Int?,
    val videoBitrate: Int,
    val useHevc: Boolean,
    /** null = áudio em passthrough; senão recodifica para AAC nesse bitrate (canais mantidos). */
    val audioReencodeBitrate: Int?,
    /** Áudio com mais de 2 canais: mixa para estéreo antes de recodificar (exige audioReencodeBitrate). */
    val audioDownmixToStereo: Boolean = false,
    /** bits por pixel REAL do alvo de vídeo × 1000, arredondado; vai no nome do arquivo (_comp_q<N>). */
    val qualityTag: Int = 0,
)

data class CompressionResult(
    val file: java.io.File,
    val elapsedMs: Long,
    val requestedVideoBitrate: Int,
    val actualVideoBitrate: Long?,
)

class CompressionException(message: String, cause: Throwable? = null) : Exception(message, cause)
