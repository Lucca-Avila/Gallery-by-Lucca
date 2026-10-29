package org.fossify.gallery.compression.ui

import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.engine.CompressionOptions
import org.fossify.gallery.compression.engine.CompressionOutput
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.engine.CompressionPlanner.Preset
import org.fossify.gallery.compression.engine.Destination
import java.io.File
import kotlin.math.roundToInt

/** Um vídeo com as opções já resolvidas, pronto para entrar na fila. */
data class PlannedVideo(
    val path: String,
    val info: VideoInfo,
    val options: CompressionOptions,
    val destination: Destination,
    val estimatedSize: Long,
    /** O alvo passa do teto de 80% ou o original já está em codec eficiente (ganho pequeno). */
    val efficient: Boolean,
    /** Escolhas que geraram as opções, gravadas no item da fila para a edição: [RulePlanner.presetCode], % do Personalizado e lado menor aplicado. */
    val presetCode: Int,
    val customPct: Int?,
    val shortSide: Int?,
    /** CompressionPrefs.AFTER_* deste vídeo. */
    val after: Int,
)

/**
 * Escolhas de compressão, sem tela. A mesma regra vale para cada vídeo: a resolução só reduz quem é maior que ela, e o
 * Personalizado é um % do bitrate de cada vídeo.
 */
data class CompressRule(
    /** null = Personalizado. */
    val preset: Preset?,
    /** % do bitrate de referência; usado só no Personalizado. */
    val customPct: Int,
    /** Lado menor desejado (1080/720/480); null = manter a original. */
    val shortSide: Int?,
    /** CompressionPrefs.DEST_*. */
    val destMode: Int,
    /** Pasta de DEST_CUSTOM. */
    val customPath: String?,
    /** CompressionPrefs.AFTER_*. */
    val after: Int,
)

/** Cálculo das opções de um vídeo a partir de uma [CompressRule] (extraído do CompressOptionsDialog). */
object RulePlanner {
    /** Mínimo do slider do Personalizado (% do bitrate de referência). */
    const val MIN_CUSTOM_PCT = 10

    /** Valores da coluna `preset` da fila. */
    const val PRESET_UNKNOWN = -1
    const val PRESET_CUSTOM = 3

    fun presetCode(preset: Preset?): Int = preset?.ordinal ?: PRESET_CUSTOM

    /** A resolução da regra, só se reduzir este vídeo. */
    fun effectiveShortSide(info: VideoInfo, rule: CompressRule): Int? =
        rule.shortSide?.takeIf { it in CompressionPlanner.resolutionChoices(info) }

    fun outputSize(info: VideoInfo, rule: CompressRule) = CompressionPlanner.outputSize(info, rule.shortSide)

    fun customBitrate(info: VideoInfo, pct: Int): Long {
        val ref = CompressionPlanner.referenceBitrate(info)
        return CompressionPlanner.clampBitrate((ref * pct.coerceAtLeast(MIN_CUSTOM_PCT) / 100.0).toLong(), ref)
    }

    /** % do slider que corresponde ao preset, na resolução dada. */
    fun presetPct(info: VideoInfo, shortSide: Int?, preset: Preset): Int {
        val ref = CompressionPlanner.referenceBitrate(info)
        val size = CompressionPlanner.outputSize(info, shortSide)
        return (CompressionPlanner.presetBitrate(info, size, preset) * 100.0 / ref).roundToInt().coerceIn(MIN_CUSTOM_PCT, 100)
    }

    /** Bitrate de vídeo do alvo: o do preset (bpp, teto de 80%, piso de 400 kbps) ou o do Personalizado. */
    fun bitrate(info: VideoInfo, rule: CompressRule): Long {
        val preset = rule.preset ?: return customBitrate(info, rule.customPct)
        return CompressionPlanner.presetBitrate(info, outputSize(info, rule), preset)
    }

    /** Pedido "cru" usado para o aviso de eficiência: o do preset (antes do teto) ou o do Personalizado. */
    fun requestedForWarning(info: VideoInfo, rule: CompressRule): Long {
        val preset = rule.preset ?: return customBitrate(info, rule.customPct)
        return CompressionPlanner.rawBitrate(info, outputSize(info, rule), preset.bpp)
    }

    fun isEfficient(info: VideoInfo, rule: CompressRule): Boolean =
        CompressionPlanner.isAlreadyEfficient(info, requestedForWarning(info, rule))

    fun estimateSize(info: VideoInfo, rule: CompressRule): Long =
        CompressionPlanner.estimateSize(info, bitrate(info, rule), rule.preset)

    fun destination(path: String, rule: CompressRule): Destination? = when (rule.destMode) {
        CompressionPrefs.DEST_COMPRIMIDOS -> Destination.MediaFolder(CompressionOutput.FALLBACK_RELATIVE_PATH)
        CompressionPrefs.DEST_CUSTOM -> rule.customPath?.let { Destination.Direct(it) }
        else -> File(path).parent?.let { Destination.Direct(it) }
    }

    fun plan(path: String, info: VideoInfo, rule: CompressRule): PlannedVideo? {
        val destination = destination(path, rule) ?: return null
        val shortSide = effectiveShortSide(info, rule)
        val size = CompressionPlanner.outputSize(info, shortSide)
        val audioPlan = CompressionPlanner.audioPlan(info, rule.preset)
        val bitrate = bitrate(info, rule)
        val options = CompressionOptions(
            outputWidth = if (shortSide != null) size.width else null,
            outputHeight = if (shortSide != null) size.height else null,
            videoBitrate = bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            useHevc = info.hevcEncoder.supported,
            qualityTag = CompressionPlanner.qualityTag(bitrate, size, CompressionPlanner.fpsOf(info)),
            audioReencodeBitrate = audioPlan.reencodeBitrate,
            audioDownmixToStereo = audioPlan.downmixToStereo,
        )
        return PlannedVideo(
            path, info, options, destination,
            estimatedSize = CompressionPlanner.estimateSize(info, bitrate, rule.preset),
            efficient = isEfficient(info, rule),
            presetCode = presetCode(rule.preset),
            customPct = if (rule.preset == null) rule.customPct.coerceAtLeast(MIN_CUSTOM_PCT) else null,
            shortSide = shortSide,
            after = rule.after,
        )
    }

    /** A mesma qualidade: mesmo preset e, no Personalizado, o mesmo %. */
    fun sameQuality(a: CompressRule, b: CompressRule) =
        a.preset == b.preset && (a.preset != null || a.customPct.coerceAtLeast(MIN_CUSTOM_PCT) == b.customPct.coerceAtLeast(MIN_CUSTOM_PCT))

    /** Campos em que [a] e [b] diferem; com [info], a resolução só conta se o resultado neste vídeo for outro. */
    fun diff(a: CompressRule, b: CompressRule, info: VideoInfo? = null): Set<RuleField> = buildSet {
        if (!sameQuality(a, b)) add(RuleField.QUALITY)
        val resA = if (info != null) effectiveShortSide(info, a) else a.shortSide
        val resB = if (info != null) effectiveShortSide(info, b) else b.shortSide
        if (resA != resB) add(RuleField.RESOLUTION)
        if (a.destMode != b.destMode || (a.destMode == CompressionPrefs.DEST_CUSTOM && a.customPath != b.customPath)) add(RuleField.DESTINATION)
        if (a.after != b.after) add(RuleField.AFTER)
    }
}

/** Partes de uma [CompressRule] que um vídeo pode sobrescrever. */
enum class RuleField { QUALITY, RESOLUTION, DESTINATION, AFTER }

/** Sobrescrita de um vídeo na tela de lote: só os [fields] vêm de [rule]; o resto segue a regra geral. */
data class RuleOverride(val rule: CompressRule, val fields: Set<RuleField>) {
    fun applyTo(general: CompressRule): CompressRule = copyFields(rule, general, fields)

    /** Sem os campos que já dão o mesmo resultado que a regra geral neste vídeo; null se não sobrar nenhum. */
    fun pruned(general: CompressRule, info: VideoInfo?): RuleOverride? {
        val kept = fields intersect RulePlanner.diff(applyTo(general), general, info)
        return if (kept.isEmpty()) null else copy(fields = kept)
    }

    companion object {
        /** [into] com os [fields] copiados de [from]. */
        fun copyFields(from: CompressRule, into: CompressRule, fields: Set<RuleField>): CompressRule {
            var r = into
            if (RuleField.QUALITY in fields) r = r.copy(preset = from.preset, customPct = from.customPct)
            if (RuleField.RESOLUTION in fields) r = r.copy(shortSide = from.shortSide)
            if (RuleField.DESTINATION in fields) r = r.copy(destMode = from.destMode, customPath = from.customPath)
            if (RuleField.AFTER in fields) r = r.copy(after = from.after)
            return r
        }
    }
}
