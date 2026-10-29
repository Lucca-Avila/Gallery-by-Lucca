package org.fossify.gallery.compression.ui

import android.widget.RadioButton
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.compression.analyzer.VideoInfoCache
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.mark.CompressedMarks
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.engine.CompressionPlanner.Preset
import org.fossify.gallery.compression.queue.QueueEta
import org.fossify.gallery.compression.queue.QueueRepository
import org.fossify.gallery.databinding.DialogCompressOptionsBinding
import org.fossify.gallery.dialogs.PickDirectoryDialog
import org.fossify.gallery.extensions.config
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Opções de compressão de um ou vários vídeos; com vários, as mesmas escolhas valem para cada um e a estimativa é somada.
 * Em modo regra ([RuleMode]) não analisa nem grava preferências: abre com uma regra pronta e devolve a regra editada.
 */
@OptIn(UnstableApi::class)
class CompressOptionsDialog(
    private val activity: BaseSimpleActivity,
    private val paths: List<String>,
    private val onConfirm: (planned: List<PlannedVideo>, after: Int) -> Unit,
    private val ruleMode: RuleMode? = null,
) {
    /**
     * Edição de uma regra da tela de lote. [onApply] recebe a regra editada e os campos que mudaram em relação a [initial];
     * com [onReset], aparece "Usar regra geral".
     */
    class RuleMode(
        val videos: List<Pair<String, VideoInfo>>,
        val initial: CompressRule,
        val title: String,
        val onApply: (rule: CompressRule, changed: Set<RuleField>) -> Unit,
        val onReset: (() -> Unit)? = null,
    )

    private val binding = DialogCompressOptionsBinding.inflate(activity.layoutInflater)
    private val prefs = CompressionPrefs(activity)
    private val multi = paths.size > 1
    private var dialog: AlertDialog? = null
    /** Primeiro vídeo legível; referência do slider e, com um vídeo só, de toda a tela. */
    private var info: VideoInfo? = null
    private var videos = emptyList<Pair<String, VideoInfo>>()
    private var skipped = emptyList<String>()
    private var sliderTouched = false
    /** Pixels por segundo para a estimativa de tempo; o padrão vale até a velocidade medida chegar do banco. */
    private var speed = QueueEta.DEFAULT_SPEED
    private var updating = false
    /** Pasta de "Escolher outra pasta"; em modo regra começa na da regra. */
    private var customPath = ruleMode?.initial?.customPath ?: prefs.customPath
    /** Regra lida da tela logo depois de abrir em modo regra, para saber o que mudou. */
    private var initialRule: CompressRule? = null

    /** O diálogo foi fechado: para de atualizar a tela, mas a análise em andamento continua. */
    @Volatile
    private var dismissed = false

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(if (ruleMode != null) R.string.cmp_b_apply else R.string.cmp_start, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                ruleMode?.onReset?.let { reset -> setNeutralButton(R.string.cmp_b_use_general) { _, _ -> reset() } }
                val titleRes = if (ruleMode != null) 0 else if (multi) R.string.cmp_title_multi else R.string.cmp_title
                activity.setupDialogStuff(binding.root, this, titleRes, ruleMode?.title.orEmpty()) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.setCanceledOnTouchOutside(false)
                    alertDialog.setOnDismissListener { dismissed = true }
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = false
                        setOnClickListener { confirm() }
                    }
                    if (ruleMode != null) setup(ruleMode.videos, emptyList()) else analyze()
                }
            }
    }

    /**
     * A análise roda em [VideoInfoCache], fora desta tela: fechar o diálogo (voltar, Cancelar, girar a tela) não a interrompe, e o
     * resultado fica no cache para a próxima vez. Aqui só se espera pelos resultados e se atualiza a interface enquanto o diálogo existir.
     */
    private fun analyze() {
        ensureBackgroundThread {
            val ok = mutableListOf<Pair<String, VideoInfo>>()
            val failed = mutableListOf<String>()
            var lastError = ""
            val pending = paths.map { path ->
                val future = try {
                    val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID) ?: throw IllegalStateException("uri")
                    VideoInfoCache.submit(activity, path, uri)
                } catch (e: Exception) {
                    null
                }
                path to future
            }
            pending.forEachIndexed { i, (path, future) ->
                if (dismissed) return@ensureBackgroundThread
                if (multi) activity.runOnUiThread { binding.compressMessage.text = activity.getString(R.string.cmp_analyzing_n, i + 1, paths.size) }
                try {
                    val info = VideoInfoCache.await(future ?: throw IllegalStateException("uri")) { !dismissed } ?: return@ensureBackgroundThread
                    val decoder = info.videoDecoder
                    if (decoder != null && !decoder.supported) {
                        lastError = activity.getString(R.string.cmp_err_no_decoder, info.videoMime ?: activity.getString(R.string.cmp_unknown))
                        failed.add(File(path).name)
                    } else {
                        ok.add(path to info)
                    }
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    lastError = activity.getString(R.string.vi_error, cause.message ?: cause.javaClass.simpleName)
                    failed.add(File(path).name)
                }
            }
            if (dismissed) return@ensureBackgroundThread
            val compressed = if (!multi) ok.firstOrNull()?.let { (path, info) -> CompressedMarks.detect(activity, path, info) } else null
            activity.runOnUiThread {
                if (dismissed) return@runOnUiThread
                when {
                    ok.isEmpty() && !multi -> binding.compressMessage.text = lastError
                    ok.isEmpty() -> binding.compressMessage.text = activity.getString(R.string.cmp_multi_none_readable, failed.joinToString(", "))
                    compressed != null -> confirmRecompress(compressed) { setup(ok, failed) }
                    else -> setup(ok, failed)
                }
            }
        }
    }

    /** Vídeo já comprimido pelo app (qualquer camada): pergunta antes de mostrar as opções; Cancelar fecha tudo. */
    private fun confirmRecompress(quality: Int, onConfirm: () -> Unit) {
        dialog?.hide()
        var confirmed = false
        val message = DialogMessageBinding.inflate(activity.layoutInflater).apply {
            this.message.text = activity.getString(R.string.cmp_already_compressed, quality)
        }
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.cmp_compress_anyway) { _, _ ->
                confirmed = true
                dialog?.show()
                onConfirm()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(message.root, this) { warning ->
                    warning.setOnDismissListener { if (!confirmed) dialog?.dismiss() }
                }
            }
    }

    private fun setup(videos: List<Pair<String, VideoInfo>>, skipped: List<String>) {
        this.videos = videos
        this.skipped = skipped
        QueueRepository.io(activity, { dao -> QueueEta.speed(prefs, dao.getAll()) }) {
            speed = it
            refreshEta()
        }
        val info = videos.first().second
        this.info = info
        binding.compressMessage.visibility = android.view.View.GONE
        binding.compressContent.beVisible()

        val ref = CompressionPlanner.referenceBitrate(info)
        binding.compressSource.text = if (multi) {
            activity.getString(R.string.cmp_source_multi, videos.size, videos.sumOf { it.second.fileSize }.formatSize())
        } else {
            activity.getString(
                R.string.cmp_source,
                "${info.effectiveWidth}×${info.effectiveHeight}",
                info.fileSize.formatSize(),
                formatBitrate(ref)
            )
        }
        binding.compressWarnings.text = (if (multi) multiWarnings() else warnings(info)).joinToString("\n").also {
            binding.compressWarnings.visibility = if (it.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }

        if (multi) setupResolutionMulti() else setupResolution(info)
        setupPresets()
        setupDestination()
        setupAfter()
        ruleMode?.let { applyRule(it.initial) }
        refreshTarget()
        if (ruleMode != null) initialRule = readRule()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
    }

    private fun warnings(info: VideoInfo): List<String> = buildList {
        if (info.audio.size > 1) add(activity.getString(R.string.cmp_warn_audio, info.audio.size))
        if (info.subtitles.isNotEmpty()) add(activity.getString(R.string.cmp_warn_subs))
        if (info.likely10Bit) add(activity.getString(R.string.cmp_warn_hdr))
        if (!info.hevcEncoder.supported) add(activity.getString(R.string.cmp_warn_no_hevc))
    }

    private fun multiWarnings(): List<String> = buildList {
        val infos = videos.map { it.second }
        if (skipped.isNotEmpty()) add(activity.getString(R.string.cmp_warn_multi_skipped, skipped.size, skipped.joinToString(", ")))
        infos.count { it.audio.size > 1 }.takeIf { it > 0 }?.let { add(activity.getString(R.string.cmp_warn_multi_audio, it)) }
        infos.count { it.subtitles.isNotEmpty() }.takeIf { it > 0 }?.let { add(activity.getString(R.string.cmp_warn_multi_subs, it)) }
        infos.count { it.likely10Bit }.takeIf { it > 0 }?.let { add(activity.getString(R.string.cmp_warn_multi_hdr, it)) }
        if (infos.any { !it.hevcEncoder.supported }) add(activity.getString(R.string.cmp_warn_no_hevc))
    }

    /** Com vários vídeos, cada resolução só reduz os que são maiores que ela. */
    private fun setupResolutionMulti() {
        val buttons = mapOf(1080 to binding.compressRes1080, 720 to binding.compressRes720, 480 to binding.compressRes480)
        binding.compressResOriginal.text = activity.getString(R.string.cmp_res_original_multi)
        buttons.forEach { (target, button) ->
            val reduced = videos.count { target in CompressionPlanner.resolutionChoices(it.second) }
            if (reduced > 0) {
                button.text = activity.getString(R.string.cmp_res_target_multi, target, reduced)
            } else {
                button.visibility = android.view.View.GONE
            }
        }
        binding.compressResOriginal.isChecked = true
        binding.compressResolutionGroup.setOnCheckedChangeListener { _, _ ->
            if (binding.compressPresetCustom.isChecked && !sliderTouched) resetSliderToPreset(Preset.BALANCED)
            refreshTarget()
        }
    }

    private fun setupResolution(info: VideoInfo) {
        val choices = CompressionPlanner.resolutionChoices(info)
        val buttons = mapOf(1080 to binding.compressRes1080, 720 to binding.compressRes720, 480 to binding.compressRes480)
        binding.compressResOriginal.text = activity.getString(R.string.cmp_res_original, info.effectiveWidth.toString(), info.effectiveHeight.toString())
        buttons.forEach { (target, button) ->
            if (target in choices) {
                val size = CompressionPlanner.outputSize(info, target)
                button.text = activity.getString(R.string.cmp_res_target, target, size.width.toString(), size.height.toString())
            } else {
                button.visibility = android.view.View.GONE
            }
        }
        binding.compressResOriginal.isChecked = true
        binding.compressResolutionGroup.setOnCheckedChangeListener { _, _ ->
            if (binding.compressPresetCustom.isChecked && !sliderTouched) resetSliderToPreset(Preset.BALANCED)
            refreshTarget()
        }
    }

    /** Marca na tela as escolhas de [rule]; uma resolução que não reduz nenhum vídeo daqui fica como "Original". */
    private fun applyRule(rule: CompressRule) {
        val resolution = when (rule.shortSide) {
            1080 -> binding.compressRes1080
            720 -> binding.compressRes720
            480 -> binding.compressRes480
            else -> null
        }?.takeIf { it.visibility == android.view.View.VISIBLE } ?: binding.compressResOriginal
        resolution.isChecked = true
        when (rule.preset) {
            Preset.QUALITY -> binding.compressPresetQuality.isChecked = true
            Preset.BALANCED -> binding.compressPresetBalanced.isChecked = true
            Preset.MAX_SAVING -> binding.compressPresetSaving.isChecked = true
            null -> {
                sliderTouched = true
                binding.compressPresetCustom.isChecked = true
                updating = true
                binding.compressQualitySeek.progress = rule.customPct
                updating = false
            }
        }
        when {
            rule.destMode == CompressionPrefs.DEST_COMPRIMIDOS -> binding.compressDestComprimidos.isChecked = true
            rule.destMode == CompressionPrefs.DEST_CUSTOM && customPath != null -> binding.compressDestOther.isChecked = true
            else -> binding.compressDestSame.isChecked = true
        }
        when {
            rule.after == CompressionPrefs.AFTER_DELETE -> binding.compressAfterDelete.isChecked = true
            rule.after == CompressionPrefs.AFTER_TRASH && binding.compressAfterTrash.isEnabled -> binding.compressAfterTrash.isChecked = true
            else -> binding.compressAfterKeep.isChecked = true
        }
    }

    private fun selectedShortSide(): Int? = when (binding.compressResolutionGroup.checkedRadioButtonId) {
        R.id.compress_res_1080 -> 1080
        R.id.compress_res_720 -> 720
        R.id.compress_res_480 -> 480
        else -> null
    }

    private fun setupPresets() {
        binding.compressPresetBalanced.isChecked = true
        binding.compressPresetGroup.setOnCheckedChangeListener { _, id ->
            val custom = id == R.id.compress_preset_custom
            binding.compressQualitySeek.visibility = if (custom) android.view.View.VISIBLE else android.view.View.GONE
            if (custom && !sliderTouched) resetSliderToPreset(Preset.BALANCED)
            refreshTarget()
        }
        binding.compressQualitySeek.min = RulePlanner.MIN_CUSTOM_PCT
        binding.compressQualitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updating) {
                    sliderTouched = true
                    refreshTarget()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun presetOfSelection(): Preset? = when (binding.compressPresetGroup.checkedRadioButtonId) {
        R.id.compress_preset_quality -> Preset.QUALITY
        R.id.compress_preset_saving -> Preset.MAX_SAVING
        R.id.compress_preset_custom -> null
        else -> Preset.BALANCED
    }

    private fun resetSliderToPreset(preset: Preset) {
        val info = info ?: return
        updating = true
        binding.compressQualitySeek.progress = RulePlanner.presetPct(info, selectedShortSide(), preset)
        updating = false
    }

    /** Regra com as escolhas atuais da tela. O destino e o "depois de comprimir" só são lidos ao confirmar. */
    private fun currentRule(
        preset: Preset? = presetOfSelection(),
        destMode: Int = CompressionPrefs.DEST_SAME,
        after: Int = CompressionPrefs.AFTER_KEEP,
    ) = CompressRule(
        preset = preset,
        customPct = binding.compressQualitySeek.progress,
        shortSide = selectedShortSide(),
        destMode = destMode,
        customPath = customPath,
        after = after,
    )

    private fun refreshTarget() {
        val info = info ?: return
        if (multi) {
            refreshTargetMulti()
            return
        }
        val ref = CompressionPlanner.referenceBitrate(info)
        val size = CompressionPlanner.outputSize(info, selectedShortSide())
        val fps = CompressionPlanner.fpsOf(info)

        fun line(nameRes: Int, preset: Preset?): String {
            val rule = currentRule(preset)
            val q = CompressionPlanner.qualityTag(RulePlanner.bitrate(info, rule), size, fps)
            val est = RulePlanner.estimateSize(info, rule)
            val delta = if (info.fileSize > 0) ((est - info.fileSize) * 100.0 / info.fileSize).roundToInt() else 0
            val pct = if (delta <= 0) "−${-delta}%" else "+$delta%"
            return activity.getString(R.string.cmp_preset_line, activity.getString(nameRes), est.formatSize(), pct, q)
        }
        binding.compressPresetQuality.text = line(R.string.cmp_preset_quality, Preset.QUALITY)
        binding.compressPresetBalanced.text = line(R.string.cmp_preset_balanced, Preset.BALANCED)
        binding.compressPresetSaving.text = line(R.string.cmp_preset_saving, Preset.MAX_SAVING)
        binding.compressPresetCustom.text = line(R.string.cmp_preset_custom, null)

        val bitrate = RulePlanner.bitrate(info, currentRule())
        binding.compressTargetInfo.text = activity.getString(
            R.string.cmp_target_info,
            formatBitrate(bitrate),
            (bitrate * 100.0 / ref).roundToInt(),
            String.format(Locale.US, "%.3f", CompressionPlanner.bitsPerPixel(bitrate, size, fps))
        ) + audioInfoLine(info)
        binding.compressEfficientWarning.visibility =
            if (RulePlanner.isEfficient(info, currentRule())) android.view.View.VISIBLE else android.view.View.GONE
        refreshEta()
    }

    /** Tempo estimado de compressão dos vídeos escolhidos, com a resolução de saída atual. */
    private fun refreshEta() {
        if (videos.isEmpty()) return
        val rule = currentRule()
        val seconds = videos.sumOf { (_, info) ->
            val size = CompressionPlanner.outputSize(info, RulePlanner.effectiveShortSide(info, rule))
            (QueueEta.pixels(size.width, size.height, CompressionPlanner.fpsOf(info), info.durationMs) ?: 0.0) / speed
        }
        binding.compressEta.text = activity.getString(R.string.cmp_eta_dialog, QueueEta.formatDuration(activity, Math.round(seconds)))
    }

    /** Estimativas somadas: cada vídeo com o próprio alvo (bpp da sua resolução, teto e piso). */
    private fun refreshTargetMulti() {
        val total = videos.sumOf { it.second.fileSize }
        fun line(nameRes: Int, preset: Preset?): String {
            val rule = currentRule(preset)
            val est = videos.sumOf { (_, info) -> RulePlanner.estimateSize(info, rule) }
            val delta = if (total > 0) ((est - total) * 100.0 / total).roundToInt() else 0
            val pct = if (delta <= 0) "−${-delta}%" else "+$delta%"
            return activity.getString(R.string.cmp_preset_line_multi, activity.getString(nameRes), est.formatSize(), pct)
        }
        binding.compressPresetQuality.text = line(R.string.cmp_preset_quality, Preset.QUALITY)
        binding.compressPresetBalanced.text = line(R.string.cmp_preset_balanced, Preset.BALANCED)
        binding.compressPresetSaving.text = line(R.string.cmp_preset_saving, Preset.MAX_SAVING)
        binding.compressPresetCustom.text = line(R.string.cmp_preset_custom, null)
        val custom = presetOfSelection() == null
        binding.compressTargetInfo.text = if (custom) {
            activity.getString(R.string.cmp_b_custom_each, binding.compressQualitySeek.progress.coerceAtLeast(RulePlanner.MIN_CUSTOM_PCT))
        } else {
            activity.getString(R.string.cmp_target_multi)
        }

        val rule = currentRule()
        val efficient = videos.count { RulePlanner.isEfficient(it.second, rule) }
        binding.compressEfficientWarning.text = activity.getString(R.string.cmp_warn_efficient_multi, efficient, videos.size)
        binding.compressEfficientWarning.visibility = if (efficient > 0) android.view.View.VISIBLE else android.view.View.GONE
        refreshEta()
    }

    private fun audioInfoLine(info: VideoInfo): String {
        val plan = CompressionPlanner.audioPlan(info, presetOfSelection())
        return when {
            !plan.hasAudio -> ""
            plan.downmixToStereo -> "\n" + activity.getString(R.string.cmp_audio_downmix, formatBitrate(plan.bitrate), plan.inputChannels ?: 0)
            plan.reencodeBitrate == null -> "\n" + activity.getString(R.string.cmp_audio_passthrough, formatBitrate(plan.bitrate))
            else -> "\n" + activity.getString(R.string.cmp_audio_reencode, formatBitrate(plan.bitrate))
        }
    }

    private fun setupDestination() {
        when (prefs.destMode) {
            CompressionPrefs.DEST_COMPRIMIDOS -> binding.compressDestComprimidos.isChecked = true
            CompressionPrefs.DEST_CUSTOM -> if (prefs.customPath != null) {
                binding.compressDestOther.isChecked = true
            } else {
                binding.compressDestSame.isChecked = true
            }
            else -> binding.compressDestSame.isChecked = true
        }
        updateCustomLabel()
        binding.compressDestOther.setOnClickListener { pickFolder() }
    }

    private fun setupAfter() {
        val trashAvailable = activity.config.useRecycleBin
        binding.compressAfterTrash.isEnabled = trashAvailable
        binding.compressAfterNote.beVisible()
        binding.compressAfterNote.text = activity.getString(R.string.cmp_after_note) +
            if (trashAvailable) "" else "\n" + activity.getString(R.string.cmp_after_trash_off)
        // "Apagar de vez" nunca é o padrão: só manter ou lixeira são lembrados.
        if (prefs.afterMode == CompressionPrefs.AFTER_TRASH && trashAvailable) {
            binding.compressAfterTrash.isChecked = true
        } else {
            binding.compressAfterKeep.isChecked = true
        }
    }

    private fun updateCustomLabel() {
        val custom = customPath
        binding.compressDestOther.text = if (custom != null) {
            activity.getString(R.string.cmp_dest_other_chosen, custom)
        } else {
            activity.getString(R.string.cmp_dest_other)
        }
    }

    private fun pickFolder() {
        PickDirectoryDialog(activity, File(paths.first()).parent ?: paths.first(), true, false, true, false) { picked ->
            customPath = picked
            if (ruleMode == null) prefs.customPath = picked
            updateCustomLabel()
            binding.compressDestOther.isChecked = true
        }
        // Se o seletor for cancelado sem escolha, volta para o destino salvo antes
        if (customPath == null) restoreCheckedDestination()
    }

    private fun restoreCheckedDestination() {
        val radio: RadioButton = when (ruleMode?.initial?.destMode ?: prefs.destMode) {
            CompressionPrefs.DEST_COMPRIMIDOS -> binding.compressDestComprimidos
            else -> binding.compressDestSame
        }
        radio.isChecked = true
    }

    private fun selectedAfter() = when (binding.compressAfterGroup.checkedRadioButtonId) {
        R.id.compress_after_trash -> CompressionPrefs.AFTER_TRASH
        R.id.compress_after_delete -> CompressionPrefs.AFTER_DELETE
        else -> CompressionPrefs.AFTER_KEEP
    }

    /** null: "outra pasta" marcada sem pasta escolhida. */
    private fun selectedDestMode(): Int? = when (binding.compressDestGroup.checkedRadioButtonId) {
        R.id.compress_dest_comprimidos -> CompressionPrefs.DEST_COMPRIMIDOS
        R.id.compress_dest_other -> if (customPath != null) CompressionPrefs.DEST_CUSTOM else null
        else -> CompressionPrefs.DEST_SAME
    }

    private fun readRule(): CompressRule? = selectedDestMode()?.let { currentRule(destMode = it, after = selectedAfter()) }

    private fun confirm() {
        if (info == null) return
        if (ruleMode != null) {
            val rule = readRule() ?: return
            val initial = initialRule ?: rule
            dialog?.dismiss()
            ruleMode.onApply(rule, RulePlanner.diff(initial, rule, info.takeIf { !multi }))
            return
        }
        val after = selectedAfter()
        // "Apagar de vez" nunca é lembrado; a confirmação (uma por lote) é pedida por quem recebe o resultado.
        if (after != CompressionPrefs.AFTER_DELETE) prefs.afterMode = after
        val destMode = selectedDestMode() ?: return
        prefs.destMode = destMode
        val rule = currentRule(destMode = destMode, after = after)
        val planned = videos.mapNotNull { (path, info) -> RulePlanner.plan(path, info, rule) }
        dialog?.dismiss()
        onConfirm(planned, after)
    }

    private fun formatBitrate(bps: Long): String =
        if (bps >= 1_000_000) String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0) else "${bps / 1000} kbps"
}
