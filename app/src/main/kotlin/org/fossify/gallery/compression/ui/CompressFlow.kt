package org.fossify.gallery.compression.ui

import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.compression.engine.CompressionException
import org.fossify.gallery.compression.engine.CompressionOptions
import org.fossify.gallery.compression.engine.CompressionOutput
import org.fossify.gallery.compression.engine.Destination
import org.fossify.gallery.compression.engine.PublishedFile
import org.fossify.gallery.compression.engine.CompressionResult
import org.fossify.gallery.compression.engine.VideoCompressor
import org.fossify.gallery.databinding.DialogCompressProgressBinding
import org.fossify.gallery.databinding.DialogVideoInfoBinding
import java.io.File
import java.util.Locale

/** Orquestra UM vídeo: diálogo de opções -> compressão -> gravação no destino -> resultado. */
@UnstableApi
class CompressFlow(private val activity: BaseSimpleActivity, private val path: String) {
    private var compressor: VideoCompressor? = null
    private var progressDialog: AlertDialog? = null
    private var canceled = false

    fun start() {
        CompressOptionsDialog(activity, path) { info, options, destination ->
            run(info.fileSize, options, destination)
        }
    }

    private fun run(originalSize: Long, options: CompressionOptions, destination: Destination) {
        val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
        if (uri == null) {
            showError(activity.getString(R.string.cmp_err_generic, "uri"))
            return
        }
        VideoCompressor.cleanOrphans(activity)
        if (!VideoCompressor.hasSpaceFor(activity, originalSize)) {
            showError(activity.getString(R.string.cmp_err_space))
            return
        }

        val binding = DialogCompressProgressBinding.inflate(activity.layoutInflater)
        binding.compressProgressName.text = File(path).name
        binding.compressProgressPct.text = activity.getString(R.string.cmp_progress_pct, 0)

        val c = VideoCompressor(activity).also { compressor = it }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cancel()
            }
        })

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .setCancelable(false)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.cmp_progress_title, cancelOnTouchOutside = false) { dialog ->
                    progressDialog = dialog
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        cancel()
                        dialog.dismiss()
                    }
                }
            }

        c.start(uri, options, object : VideoCompressor.Listener {
            override fun onProgress(percent: Int) {
                binding.compressProgressBar.progress = percent
                binding.compressProgressPct.text = activity.getString(R.string.cmp_progress_pct, percent)
            }

            override fun onSuccess(result: CompressionResult) {
                if (canceled) {
                    result.file.delete()
                    return
                }
                binding.compressProgressPct.text = activity.getString(R.string.cmp_saving)
                progressDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                publish(result, originalSize, destination, options.qualityTag)
            }

            override fun onError(error: CompressionException) {
                progressDialog?.dismiss()
                if (!canceled) showError(activity.getString(R.string.cmp_err_generic, error.message ?: ""))
            }
        })
    }

    private fun cancel() {
        if (canceled) return
        canceled = true
        compressor?.cancel()
    }

    private fun publish(result: CompressionResult, originalSize: Long, destination: Destination, qualityTag: Int) {
        ensureBackgroundThread {
            try {
                val published = CompressionOutput.publish(activity, result.file, destination, File(path).name, qualityTag)
                val newSize = result.file.length()
                activity.runOnUiThread {
                    progressDialog?.dismiss()
                    showResult(result, originalSize, newSize, published)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    progressDialog?.dismiss()
                    showError(activity.getString(R.string.cmp_err_generic, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                result.file.delete()
            }
        }
    }

    private fun showResult(result: CompressionResult, before: Long, after: Long, published: PublishedFile) {
        val delta = if (before > 0) (after - before) * 100.0 / before else 0.0
        val lines = mutableListOf(
            activity.getString(R.string.cmp_result_before, before.formatSize()),
            activity.getString(R.string.cmp_result_after, after.formatSize(), String.format(Locale.US, "%+.0f", delta)),
            activity.getString(R.string.cmp_result_time, formatElapsed(result.elapsedMs)),
            activity.getString(
                R.string.cmp_result_bitrate,
                formatBitrate(result.requestedVideoBitrate.toLong()),
                result.actualVideoBitrate?.let { formatBitrate(it) } ?: activity.getString(R.string.cmp_unknown)
            ),
            activity.getString(R.string.cmp_result_file, published.name),
            activity.getString(R.string.cmp_result_folder, published.location),
        )
        if (published.usedFallback) lines.add("\n" + activity.getString(R.string.cmp_result_fallback))
        if (after >= before) lines.add("\n" + activity.getString(R.string.cmp_result_bigger))

        val binding = DialogVideoInfoBinding.inflate(activity.layoutInflater)
        binding.videoInfoText.text = lines.joinToString("\n")
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .apply { activity.setupDialogStuff(binding.root, this, R.string.cmp_result_title) }
    }

    private fun showError(message: String) {
        activity.toast(message, android.widget.Toast.LENGTH_LONG)
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun formatBitrate(bps: Long): String =
        if (bps >= 1_000_000) String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0) else "${bps / 1000} kbps"
}
