package org.fossify.gallery.compression.engine

import org.fossify.gallery.compression.analyzer.VideoInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cálculos puros (sem Android) usados pelo diálogo de opções. Os números são provisórios (calibrar na Etapa 4). */
object CompressionPlanner {
    const val BITRATE_FLOOR = 400_000L
    /** Teto do bitrate alvo em relação ao bitrate de vídeo do original. */
    const val MAX_RATIO_OF_ORIGINAL = 0.80

    /** Bits por pixel de cada preset (provisórios; ajustar aqui). */
    const val BPP_QUALITY = 0.055
    const val BPP_BALANCED = 0.040
    const val BPP_MAX_SAVING = 0.030

    /** AAC até este bitrate passa direto; acima disso (ou outro formato) é recodificado para AAC. */
    const val AUDIO_PASSTHROUGH_MAX = 128_000L
    const val AUDIO_TARGET_BITRATE = 128_000L
    const val AUDIO_TARGET_BITRATE_MAX_SAVING = 96_000L
    const val FALLBACK_FPS = 30f

    data class Size(val width: Int, val height: Int)

    /** Opções de altura "de lado menor" (1080/720/480) que realmente reduzem o vídeo. */
    fun resolutionChoices(info: VideoInfo): List<Int> =
        listOf(1080, 720, 480).filter { it < min(info.effectiveWidth, info.effectiveHeight) }

    /** null = manter. Escala pelo lado menor, mantém proporção, nunca aumenta, dimensões pares. */
    fun outputSize(info: VideoInfo, shortSideTarget: Int?): Size {
        val w = info.effectiveWidth
        val h = info.effectiveHeight
        val short = min(w, h)
        if (shortSideTarget == null || shortSideTarget >= short) return Size(w, h)
        val scale = shortSideTarget.toDouble() / short
        return Size(even(w * scale), even(h * scale))
    }

    private fun even(v: Double): Int = max(2, (v.roundToInt() / 2) * 2)

    fun referenceBitrate(info: VideoInfo): Long {
        info.videoBitrate?.bps?.takeIf { it > 0 }?.let { return it }
        info.overallBitrate?.let { overall ->
            val audio = info.audio.sumOf { it.bitrate?.bps ?: 0L }
            if (overall - audio > 0) return overall - audio
        }
        if (info.durationMs > 0) return info.fileSize * 8_000L / info.durationMs
        return 2_000_000L
    }

    fun fpsOf(info: VideoInfo): Float = info.fps?.takeIf { it > 0 } ?: FALLBACK_FPS

    fun bitsPerPixel(bitrate: Long, size: Size, fps: Float): Double =
        bitrate / (size.width.toDouble() * size.height * fps)

    enum class Preset(val bpp: Double) {
        QUALITY(BPP_QUALITY),
        BALANCED(BPP_BALANCED),
        MAX_SAVING(BPP_MAX_SAVING),
    }

    /** bpp bruto do preset, sem teto nem piso. */
    fun rawBitrate(info: VideoInfo, size: Size, bpp: Double): Long =
        (bpp * size.width * size.height * fpsOf(info)).toLong()

    fun ceilingBitrate(info: VideoInfo): Long = (referenceBitrate(info) * MAX_RATIO_OF_ORIGINAL).toLong()

    /** bpp × L × A × fps, limitado a 80% do original, com piso de 400 kbps (e nunca acima do original). */
    fun presetBitrate(info: VideoInfo, size: Size, preset: Preset): Long =
        clampBitrate(min(rawBitrate(info, size, preset.bpp), ceilingBitrate(info)), referenceBitrate(info))

    /** O alvo pedido passa do teto de 80% (ganho pequeno), ou o original já está num codec eficiente. */
    fun isAlreadyEfficient(info: VideoInfo, requestedBitrate: Long): Boolean =
        requestedBitrate > ceilingBitrate(info) || info.videoMime in EFFICIENT_CODECS

    private val EFFICIENT_CODECS = setOf("video/hevc", "video/x-vnd.on2.vp9", "video/av01")

    /** reencodeBitrate == null: passthrough (ou sem áudio); bitrate = o bitrate resultante usado na estimativa. */
    data class AudioPlan(
        val bitrate: Long,
        val reencodeBitrate: Int?,
        val hasAudio: Boolean,
        val downmixToStereo: Boolean = false,
        val inputChannels: Int? = null,
    )

    /**
     * Trilha padrão. Mais de 2 canais: mixa para estéreo e recodifica para AAC 128 kbps (96 no Máxima economia).
     * Até 2 canais: AAC <= 128 kbps passa direto; AAC maior ou outro formato vai a AAC no mesmo alvo.
     */
    fun audioPlan(info: VideoInfo, preset: Preset?): AudioPlan {
        val a = info.audio.firstOrNull() ?: return AudioPlan(0L, null, false)
        val target = if (preset == Preset.MAX_SAVING) AUDIO_TARGET_BITRATE_MAX_SAVING else AUDIO_TARGET_BITRATE
        val channels = a.channels
        if (channels != null && StereoDownmix.supports(channels)) {
            return AudioPlan(target, target.toInt(), true, downmixToStereo = true, inputChannels = channels)
        }
        val original = a.bitrate?.bps
        val isAac = a.mime == "audio/mp4a-latm"
        // AAC de bitrate desconhecido: não dá para saber se passa do limite; mantém em passthrough
        return if (isAac && (original == null || original <= AUDIO_PASSTHROUGH_MAX)) {
            AudioPlan(original ?: AUDIO_PASSTHROUGH_MAX, null, true)
        } else {
            AudioPlan(target, target.toInt(), true)
        }
    }

    /** (vídeo + áudio) × duração ÷ 8. */
    fun estimateSize(info: VideoInfo, videoBitrate: Long, preset: Preset?): Long =
        (videoBitrate + audioPlan(info, preset).bitrate) * info.durationMs / 8_000L

    /** qNN = bits por pixel × 1000, arredondado. */
    fun qualityTag(bitrate: Long, size: Size, fps: Float): Int =
        (bitsPerPixel(bitrate, size, fps) * 1000).roundToInt()

    fun clampBitrate(value: Long, reference: Long): Long = min(reference, max(BITRATE_FLOOR, value))
}
