package org.fossify.gallery.compression.engine

import android.content.Context
import android.net.Uri
import org.fossify.gallery.R
import org.fossify.gallery.compression.analyzer.VideoAnalyzer
import org.fossify.gallery.compression.analyzer.VideoInfo
import java.io.File
import kotlin.math.abs

/** Decide se o resultado pode substituir o original. Roda em thread de fundo. */
object ResultVerifier {
    /** O resultado é descartado se tiver este percentual do tamanho do original ou mais (economia menor que 5%). */
    const val DISCARD_RATIO = 0.95
    const val MAX_DURATION_DIFF_MS = 1000L
    const val SIZE_TOLERANCE_PX = 2

    fun shouldDiscard(resultSize: Long, originalSize: Long): Boolean =
        originalSize > 0 && resultSize >= originalSize * DISCARD_RATIO

    /** null = pode apagar o original; senão o motivo (já em texto) para mantê-lo. */
    fun verify(context: Context, result: File, original: VideoInfo, options: CompressionOptions): String? {
        fun fail(reason: Int, vararg args: Any) = context.getString(R.string.cmp_verify_failed, context.getString(reason, *args))

        if (!result.isFile || result.length() <= 0) return fail(R.string.cmp_verify_missing)
        val info = try {
            VideoAnalyzer.analyze(context, Uri.fromFile(result))
        } catch (e: Exception) {
            return fail(R.string.cmp_verify_unreadable)
        }
        if (info.videoMime == null) return fail(R.string.cmp_verify_no_video)
        if (info.videoDecoder?.supported == false) return fail(R.string.cmp_verify_unreadable)
        if (original.audio.isNotEmpty() && info.audio.isEmpty()) return fail(R.string.cmp_verify_no_audio)

        val diff = abs(info.durationMs - original.durationMs)
        if (diff > MAX_DURATION_DIFF_MS) return fail(R.string.cmp_verify_duration, original.durationMs / 1000.0, info.durationMs / 1000.0)

        val expectedW = options.outputWidth ?: original.effectiveWidth
        val expectedH = options.outputHeight ?: original.effectiveHeight
        if (abs(info.effectiveWidth - expectedW) > SIZE_TOLERANCE_PX || abs(info.effectiveHeight - expectedH) > SIZE_TOLERANCE_PX) {
            return fail(R.string.cmp_verify_resolution, "${info.effectiveWidth}×${info.effectiveHeight}", "${expectedW}×${expectedH}")
        }
        return null
    }
}
