package org.fossify.gallery.compression.ui

import android.widget.RadioButton
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.compression.analyzer.VideoAnalyzer
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.engine.CompressionOptions
import org.fossify.gallery.compression.engine.CompressionOutput
import org.fossify.gallery.compression.engine.Destination
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.engine.CompressionPlanner.Preset
import org.fossify.gallery.databinding.DialogCompressOptionsBinding
import org.fossify.gallery.dialogs.PickDirectoryDialog
import org.fossify.gallery.extensions.config
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class CompressOptionsDialog(
    private val activity: BaseSimpleActivity,
    private val path: String,
    private val onConfirm: (info: VideoInfo, options: CompressionOptions, destination: Destination, after: Int) -> Unit,
) {
    private val binding = DialogCompressOptionsBinding.inflate(activity.layoutInflater)
    private val prefs = CompressionPrefs(activity)
    private var dialog: AlertDialog? = null
    private var info: VideoInfo? = null
    private var sliderTouched = false
    private var updating = false

    @Volatile
    private var cancelled = false

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.cmp_start, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.cmp_title) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.setOnDismissListener { cancelled = true }
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = false
                        setOnClickListener { confirm() }
                    }
                    analyze()
                }
            }
    }

    private fun analyze() {
        ensureBackgroundThread {
            val result = try {
                val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
                    ?: throw IllegalStateException("uri")
                Result.success(VideoAnalyzer.analyze(activity, uri) { cancelled })
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (cancelled) return@ensureBackgroundThread
            activity.runOnUiThread {
                result.onSuccess { setup(it) }
                    .onFailure { binding.compressMessage.text = activity.getString(R.string.vi_error, it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun setup(info: VideoInfo) {
        this.info = info
        val decoder = info.videoDecoder
        if (decoder != null && !decoder.supported) {
            binding.compressMessage.text = activity.getString(R.string.cmp_err_no_decoder, info.videoMime ?: activity.getString(R.string.cmp_unknown))
            return
        }
        binding.compressMessage.visibility = android.view.View.GONE
        binding.compressContent.beVisible()

        val ref = CompressionPlanner.referenceBitrate(info)
        binding.compressSource.text = activity.getString(
            R.string.cmp_source,
            "${info.effectiveWidth}×${info.effectiveHeight}",
            info.fileSize.formatSize(),
            formatBitrate(ref)
        )
        binding.compressWarnings.text = warnings(info).joinToString("\n").also {
            binding.compressWarnings.visibility = if (it.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }

        setupResolution(info)
        setupPresets()
        setupDestination()
        setupAfter()
        refreshTarget()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
    }

    private fun warnings(info: VideoInfo): List<String> = buildList {
        if (info.audio.size > 1) add(activity.getString(R.string.cmp_warn_audio, info.audio.size))
        if (info.subtitles.isNotEmpty()) add(activity.getString(R.string.cmp_warn_subs))
        if (info.likely10Bit) add(activity.getString(R.string.cmp_warn_hdr))
        if (!info.hevcEncoder.supported) add(activity.getString(R.string.cmp_warn_no_hevc))
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
        binding.compressQualitySeek.min = SEEK_MIN
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
        val ref = CompressionPlanner.referenceBitrate(info)
        val size = CompressionPlanner.outputSize(info, selectedShortSide())
        val pct = (CompressionPlanner.presetBitrate(info, size, preset) * 100.0 / ref).roundToInt().coerceIn(SEEK_MIN, 100)
        updating = true
        binding.compressQualitySeek.progress = pct
        updating = false
    }

    private fun customBitrate(): Long {
        val info = info!!
        val ref = CompressionPlanner.referenceBitrate(info)
        val pct = binding.compressQualitySeek.progress.coerceAtLeast(SEEK_MIN)
        return CompressionPlanner.clampBitrate((ref * pct / 100.0).toLong(), ref)
    }

    private fun currentBitrate(): Long {
        val info = info!!
        val preset = presetOfSelection() ?: return customBitrate()
        return CompressionPlanner.presetBitrate(info, CompressionPlanner.outputSize(info, selectedShortSide()), preset)
    }

    /** Pedido "cru" usado para o aviso de eficiência: o do preset (antes do teto) ou o do slider. */
    private fun requestedForWarning(): Long {
        val info = info!!
        val preset = presetOfSelection() ?: return customBitrate()
        return CompressionPlanner.rawBitrate(info, CompressionPlanner.outputSize(info, selectedShortSide()), preset.bpp)
    }

    private fun refreshTarget() {
        val info = info ?: return
        val ref = CompressionPlanner.referenceBitrate(info)
        val size = CompressionPlanner.outputSize(info, selectedShortSide())
        val fps = CompressionPlanner.fpsOf(info)

        fun line(nameRes: Int, bitrate: Long, preset: Preset?): String {
            val q = CompressionPlanner.qualityTag(bitrate, size, fps)
            val est = CompressionPlanner.estimateSize(info, bitrate, preset)
            val delta = if (info.fileSize > 0) ((est - info.fileSize) * 100.0 / info.fileSize).roundToInt() else 0
            val pct = if (delta <= 0) "−${-delta}%" else "+$delta%"
            return activity.getString(R.string.cmp_preset_line, activity.getString(nameRes), est.formatSize(), pct, q)
        }
        binding.compressPresetQuality.text = line(R.string.cmp_preset_quality, CompressionPlanner.presetBitrate(info, size, Preset.QUALITY), Preset.QUALITY)
        binding.compressPresetBalanced.text = line(R.string.cmp_preset_balanced, CompressionPlanner.presetBitrate(info, size, Preset.BALANCED), Preset.BALANCED)
        binding.compressPresetSaving.text = line(R.string.cmp_preset_saving, CompressionPlanner.presetBitrate(info, size, Preset.MAX_SAVING), Preset.MAX_SAVING)
        binding.compressPresetCustom.text = line(R.string.cmp_preset_custom, customBitrate(), null)

        val bitrate = currentBitrate()
        binding.compressTargetInfo.text = activity.getString(
            R.string.cmp_target_info,
            formatBitrate(bitrate),
            (bitrate * 100.0 / ref).roundToInt(),
            String.format(Locale.US, "%.3f", CompressionPlanner.bitsPerPixel(bitrate, size, fps))
        ) + audioInfoLine(info)
        binding.compressEfficientWarning.visibility =
            if (CompressionPlanner.isAlreadyEfficient(info, requestedForWarning())) android.view.View.VISIBLE else android.view.View.GONE
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
        val custom = prefs.customPath
        binding.compressDestOther.text = if (custom != null) {
            activity.getString(R.string.cmp_dest_other_chosen, custom)
        } else {
            activity.getString(R.string.cmp_dest_other)
        }
    }

    private fun pickFolder() {
        PickDirectoryDialog(activity, File(path).parent ?: path, true, false, true, false) { picked ->
            prefs.customPath = picked
            updateCustomLabel()
            binding.compressDestOther.isChecked = true
        }
        // Se o seletor for cancelado sem escolha, volta para o destino salvo antes
        if (prefs.customPath == null) restoreCheckedDestination()
    }

    private fun restoreCheckedDestination() {
        val radio: RadioButton = when (prefs.destMode) {
            CompressionPrefs.DEST_COMPRIMIDOS -> binding.compressDestComprimidos
            else -> binding.compressDestSame
        }
        radio.isChecked = true
    }

    private fun confirm() {
        val info = info ?: return
        val after = when (binding.compressAfterGroup.checkedRadioButtonId) {
            R.id.compress_after_trash -> CompressionPrefs.AFTER_TRASH
            R.id.compress_after_delete -> CompressionPrefs.AFTER_DELETE
            else -> CompressionPrefs.AFTER_KEEP
        }
        if (after != CompressionPrefs.AFTER_DELETE) {
            prefs.afterMode = after
            finish(info, after)
            return
        }
        // Apagar de vez: confirmação do próprio app a cada uso (nome e tamanho do original).
        ConfirmationDialog(
            activity,
            activity.getString(R.string.cmp_confirm_delete_title) + "\n\n" +
                activity.getString(R.string.cmp_confirm_delete_msg, File(path).name, info.fileSize.formatSize()),
            positive = R.string.cmp_confirm_delete_ok,
            negative = org.fossify.commons.R.string.cancel,
        ) { finish(info, after) }
    }

    private fun finish(info: VideoInfo, after: Int) {
        val destination = when (binding.compressDestGroup.checkedRadioButtonId) {
            R.id.compress_dest_comprimidos -> {
                prefs.destMode = CompressionPrefs.DEST_COMPRIMIDOS
                Destination.MediaFolder(CompressionOutput.FALLBACK_RELATIVE_PATH)
            }
            R.id.compress_dest_other -> {
                prefs.destMode = CompressionPrefs.DEST_CUSTOM
                Destination.Direct(prefs.customPath ?: return)
            }
            else -> {
                prefs.destMode = CompressionPrefs.DEST_SAME
                val parent = File(path).parent ?: return
                Destination.Direct(parent)
            }
        }
        val size = CompressionPlanner.outputSize(info, selectedShortSide())
        val resized = selectedShortSide() != null
        val audioPlan = CompressionPlanner.audioPlan(info, presetOfSelection())
        val bitrate = currentBitrate()
        val options = CompressionOptions(
            outputWidth = if (resized) size.width else null,
            outputHeight = if (resized) size.height else null,
            videoBitrate = bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            useHevc = info.hevcEncoder.supported,
            qualityTag = CompressionPlanner.qualityTag(bitrate, size, CompressionPlanner.fpsOf(info)),
            audioReencodeBitrate = audioPlan.reencodeBitrate,
            audioDownmixToStereo = audioPlan.downmixToStereo,
        )
        dialog?.dismiss()
        onConfirm(info, options, destination, after)
    }

    private fun formatBitrate(bps: Long): String =
        if (bps >= 1_000_000) String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0) else "${bps / 1000} kbps"

    companion object {
        private const val SEEK_MIN = 10
    }
}
