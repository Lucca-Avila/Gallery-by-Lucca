package org.fossify.gallery.compression.engine

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import kotlin.math.sqrt

/**
 * Downmix para estéreo com o ChannelMixingAudioProcessor do Media3. Matrizes no estilo ITU-R BS.775
 * (centro e surrounds a -3 dB, LFE descartado), ordem de canais do PCM do Android (FL FR FC LFE BL BR SL SR),
 * normalizadas por saída para evitar clipping (fica um pouco mais baixo que o original).
 * Registra matrizes para 3 a 8 canais; o processador escolhe pela entrada real.
 */
@UnstableApi
object StereoDownmix {
    private const val MIN_CHANNELS = 3
    private const val MAX_CHANNELS = 8
    private val H = (1 / sqrt(2.0)).toFloat()

    fun supports(channels: Int) = channels in MIN_CHANNELS..MAX_CHANNELS

    fun newProcessor(): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (n in MIN_CHANNELS..MAX_CHANNELS) putChannelMixingMatrix(matrixFor(n))
    }

    /** Coeficientes [esquerda, direita] de cada canal de entrada, antes da normalização. */
    private fun weights(n: Int): List<Pair<Float, Float>> {
        val fl = 1f to 0f
        val fr = 0f to 1f
        val fc = H to H
        val lfe = 0f to 0f
        val bl = H to 0f
        val br = 0f to H
        val sl = H to 0f
        val sr = 0f to H
        return when (n) {
            3 -> listOf(fl, fr, fc)
            4 -> listOf(fl, fr, bl, br)
            5 -> listOf(fl, fr, fc, bl, br)
            6 -> listOf(fl, fr, fc, lfe, bl, br)
            7 -> listOf(fl, fr, fc, lfe, H to H, sl, sr) // 6.1: FL FR FC LFE BC SL SR
            else -> listOf(fl, fr, fc, lfe, bl, br, sl, sr) // 7.1
        }
    }

    private fun matrixFor(n: Int): ChannelMixingMatrix {
        val w = weights(n)
        val sumL = w.sumOf { it.first.toDouble() }.toFloat()
        val sumR = w.sumOf { it.second.toDouble() }.toFloat()
        // coeficientes[entrada * saídas + saída]
        val coefficients = FloatArray(n * 2)
        w.forEachIndexed { i, (l, r) ->
            coefficients[i * 2] = l / sumL
            coefficients[i * 2 + 1] = r / sumR
        }
        return ChannelMixingMatrix(n, 2, coefficients)
    }
}
