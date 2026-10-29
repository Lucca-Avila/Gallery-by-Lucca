package org.fossify.gallery.compression.queue

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.analyzer.VideoInfoCache
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.engine.CompressionPlanner.Preset
import org.fossify.gallery.compression.ui.CompressOptionsDialog
import org.fossify.gallery.compression.ui.CompressRule
import org.fossify.gallery.compression.ui.CompressionPrefs
import org.fossify.gallery.compression.ui.PlannedVideo
import org.fossify.gallery.compression.ui.RulePlanner
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Editar" de um item aguardando: diálogo de opções em modo regra com os valores do item. O estado é checado ao abrir e a gravação
 * é condicional ([QueueRepository.edit]); o runner não é tocado. Virar "apagar de vez" sem confirmação válida pede uma nova, e o
 * item passa para um lote novo com o consentimento.
 */
@UnstableApi
object QueueEdit {
    fun isEditable(item: QueueItem) = item.state == QueueItem.STATE_WAITING && item.phase == QueueItem.PHASE_NONE

    fun open(activity: BaseSimpleActivity, id: Long) {
        QueueRepository.io(activity, { dao -> dao.get(id) }) { item ->
            if (item == null || !isEditable(item)) {
                activity.toast(R.string.cmp_q_edit_started_open)
            } else {
                analyzeAndShow(activity, id, item.path)
            }
        }
    }

    private fun analyzeAndShow(activity: BaseSimpleActivity, id: Long, path: String) {
        ensureBackgroundThread {
            val file = File(path)
            val info = try {
                if (!file.exists()) throw IllegalStateException(activity.getString(R.string.cmp_q_original_missing))
                VideoInfoCache.analyze(activity, path, Uri.fromFile(file))
            } catch (e: Exception) {
                activity.runOnUiThread { activity.toast(e.message ?: e.javaClass.simpleName) }
                return@ensureBackgroundThread
            }
            // Estado lido depois da análise, o mais perto possível de o diálogo aparecer.
            QueueRepository.io(activity, { dao -> dao.get(id) }) { item ->
                if (activity.isDestroyed || activity.isFinishing) return@io
                if (item == null || !isEditable(item)) {
                    activity.toast(R.string.cmp_q_edit_started_open)
                    return@io
                }
                show(activity, item, info)
            }
        }
    }

    private fun show(activity: BaseSimpleActivity, item: QueueItem, info: VideoInfo) {
        CompressOptionsDialog(
            activity, listOf(item.path), onConfirm = { _, _ -> },
            ruleMode = CompressOptionsDialog.RuleMode(
                videos = listOf(item.path to info),
                initial = ruleOf(item, info),
                title = item.name,
                onApply = { rule, changed -> if (changed.isNotEmpty()) apply(activity, item, info, rule) },
            ),
        )
    }

    /** Regra que reproduz as escolhas do item. Itens anteriores à migração 3→4 têm preset e resolução deduzidos das opções gravadas. */
    fun ruleOf(item: QueueItem, info: VideoInfo): CompressRule {
        val legacy = item.preset == RulePlanner.PRESET_UNKNOWN
        val shortSide = if (legacy) legacyShortSide(item, info) else item.shortSide
        val preset = when {
            legacy -> legacyPreset(item, info, shortSide)
            item.preset == RulePlanner.PRESET_CUSTOM -> null
            else -> Preset.entries.getOrNull(item.preset)
        }
        val ref = CompressionPlanner.referenceBitrate(info)
        val customPct = item.customPct
            ?: if (preset == null) (item.videoBitrate * 100.0 / ref).roundToInt().coerceIn(RulePlanner.MIN_CUSTOM_PCT, 100)
            else RulePlanner.presetPct(info, shortSide, preset)
        val parent = File(item.path).parent
        val (destMode, customPath) = when {
            item.destKind == QueueItem.DEST_MEDIA -> CompressionPrefs.DEST_COMPRIMIDOS to null
            item.destPath == parent -> CompressionPrefs.DEST_SAME to null
            else -> CompressionPrefs.DEST_CUSTOM to item.destPath
        }
        return CompressRule(preset, customPct, shortSide, destMode, customPath, item.after)
    }

    /** A opção de resolução (1080/720/480) mais próxima do lado menor gravado; null se o item mantinha a original. */
    private fun legacyShortSide(item: QueueItem, info: VideoInfo): Int? {
        val w = item.outputWidth ?: return null
        val h = item.outputHeight ?: return null
        val short = min(w, h)
        return CompressionPlanner.resolutionChoices(info).minByOrNull { abs(it - short) }
    }

    /** O preset cujo bitrate nessa resolução bate com o gravado (±1%); senão Personalizado (null). */
    private fun legacyPreset(item: QueueItem, info: VideoInfo, shortSide: Int?): Preset? {
        val size = CompressionPlanner.outputSize(info, shortSide)
        return Preset.entries.firstOrNull {
            val bitrate = CompressionPlanner.presetBitrate(info, size, it)
            abs(bitrate - item.videoBitrate) <= maxOf(1_000L, bitrate / 100)
        }
    }

    private fun apply(activity: BaseSimpleActivity, item: QueueItem, info: VideoInfo, rule: CompressRule) {
        val planned = RulePlanner.plan(item.path, info, rule) ?: return
        val toDelete = planned.after == CompressionPrefs.AFTER_DELETE
        val consented = item.after == CompressionPrefs.AFTER_DELETE && QueueRepository.hasDeleteConsent(item.batchId)
        if (!toDelete || consented) {
            save(activity, item, planned, item.batchId)
            return
        }
        // Confirmação nova (o item virou "apagar de vez" ou a anterior se perdeu): lote novo, consentido antes de gravar.
        ConfirmationDialog(
            activity,
            activity.getString(R.string.cmp_confirm_delete_title) + "\n\n" +
                activity.getString(R.string.cmp_confirm_delete_msg, item.name, info.fileSize.formatSize()),
            positive = R.string.cmp_q_confirm_delete_ok,
            negative = org.fossify.commons.R.string.cancel,
        ) {
            val batchId = System.currentTimeMillis()
            QueueRepository.grantDeleteConsent(listOf(batchId))
            save(activity, item, planned, batchId)
        }
    }

    private fun save(activity: BaseSimpleActivity, item: QueueItem, planned: PlannedVideo, batchId: Long) {
        val edited = QueueItem.create(
            batchId, item.path, item.originalSize, planned.estimatedSize, planned.options, planned.destination, planned.after,
            videoWidth = planned.options.outputWidth ?: planned.info.effectiveWidth,
            videoHeight = planned.options.outputHeight ?: planned.info.effectiveHeight,
            fps = item.fps,
            durationMs = item.durationMs,
            preset = planned.presetCode,
            customPct = planned.customPct,
            shortSide = planned.shortSide,
        ).copy(id = item.id)
        QueueRepository.edit(activity, edited) { applied ->
            if (!applied) activity.toast(R.string.cmp_q_edit_started)
        }
    }
}
