package org.fossify.gallery.compression.analyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogVideoInfoBinding

/**
 * A análise padrão passa pela fila e pelo cache de [VideoInfoCache]: fechar o diálogo antes do fim não a interrompe nem a repete.
 * "Medir com precisão" varre o arquivo inteiro; fechar o diálogo cancela essa medição.
 */
@OptIn(UnstableApi::class)
class VideoInfoDialog(private val activity: BaseSimpleActivity, private val path: String) {
    /** O diálogo foi fechado: para de atualizar a tela e cancela a medição precisa em andamento. */
    @Volatile
    private var dismissed = false
    private var report: String? = null
    private var alertDialog: AlertDialog? = null
    private val binding = DialogVideoInfoBinding.inflate(activity.layoutInflater)

    init {
        binding.videoInfoText.text = activity.getString(R.string.vi_analyzing)

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.vi_copy, null)
            .setNeutralButton(R.string.vi_measure, null)
            .setNegativeButton(org.fossify.commons.R.string.ok, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.vi_title) { dialog ->
                    alertDialog = dialog
                    dialog.setOnDismissListener { dismissed = true }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        report?.let {
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("video-info", it))
                            activity.toast(R.string.vi_copied)
                        }
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { measure() }
                    analyze()
                }
            }
    }

    private fun show(info: VideoInfo) {
        val text = VideoInfoFormatter.format(activity, info)
        report = text
        activity.runOnUiThread {
            if (dismissed) return@runOnUiThread
            binding.videoInfoText.text = text
            // Já medido com precisão: não há o que medir de novo.
            alertDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                isEnabled = !info.scanned && info.videoMime != null
                visibility = if (info.scanned) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun fail(e: Exception) {
        val text = activity.getString(R.string.vi_error, (e.cause ?: e).message ?: e.javaClass.simpleName)
        activity.runOnUiThread { if (!dismissed) binding.videoInfoText.text = text }
    }

    private fun analyze() {
        ensureBackgroundThread {
            try {
                val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID) ?: throw IllegalStateException("uri")
                val info = VideoInfoCache.await(VideoInfoCache.submit(activity, path, uri)) { !dismissed } ?: return@ensureBackgroundThread
                show(info)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun measure() {
        val button = alertDialog?.getButton(AlertDialog.BUTTON_NEUTRAL) ?: return
        button.isEnabled = false
        binding.videoInfoText.text = activity.getString(R.string.vi_measuring)
        ensureBackgroundThread {
            try {
                val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID) ?: throw IllegalStateException("uri")
                val info = VideoInfoCache.measure(activity, path, uri) { dismissed }
                if (!dismissed) show(info)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
