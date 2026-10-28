package org.fossify.gallery.compression.analyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogVideoInfoBinding

class VideoInfoDialog(private val activity: BaseSimpleActivity, path: String) {
    @Volatile
    private var cancelled = false
    private var report: String? = null

    init {
        val binding = DialogVideoInfoBinding.inflate(activity.layoutInflater)
        binding.videoInfoText.text = activity.getString(R.string.vi_analyzing)

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.vi_copy, null)
            .setNegativeButton(org.fossify.commons.R.string.ok, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.vi_title) { alertDialog ->
                    alertDialog.setOnDismissListener { cancelled = true }
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        report?.let {
                            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("video-info", it))
                            activity.toast(R.string.vi_copied)
                        }
                    }
                    analyze(path, binding)
                }
            }
    }

    private fun analyze(path: String, binding: DialogVideoInfoBinding) {
        ensureBackgroundThread {
            val text = try {
                val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
                    ?: throw IllegalStateException("uri")
                VideoInfoFormatter.format(activity, VideoAnalyzer.analyze(activity, uri) { cancelled })
            } catch (e: Exception) {
                activity.getString(R.string.vi_error, e.message ?: e.javaClass.simpleName)
            }
            if (cancelled) return@ensureBackgroundThread
            report = text
            activity.runOnUiThread { binding.videoInfoText.text = text }
        }
    }
}
