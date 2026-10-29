package org.fossify.gallery.compression.queue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.compression.ui.CompressionPrefs
import org.fossify.gallery.databinding.ActivityCompressionQueueBinding
import org.fossify.gallery.databinding.ItemCompressionQueueBinding
import kotlin.math.roundToInt

/** Tela mínima da fila (a visual final é da Etapa 6b). Mantém a tela ligada enquanto a fila roda. */
@UnstableApi
class QueueActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCompressionQueueBinding::inflate)
    private val adapter = Adapter()
    private var items = emptyList<QueueItem>()
    private val listener: () -> Unit = { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.queueList.adapter = adapter
        binding.queueToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.queue_start -> QueueStarter.start(this)
                R.id.queue_pause -> QueueService.send(this, QueueService.ACTION_PAUSE)
                R.id.queue_pause_now -> QueueService.send(this, QueueService.ACTION_PAUSE_NOW)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        binding.queueBannerResume.setOnClickListener { QueueStarter.start(this) }
        setupEdgeToEdge(padTopSystem = listOf(binding.queueAppbar), padBottomSystem = listOf(binding.queueList))
        setupMaterialScrollListener(binding.queueList, binding.queueAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.queueAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.queueHolder)
        binding.queueBannerResume.setTextColor(getProperPrimaryColor())
        QueueRepository.addListener(listener)
        reload()
    }

    override fun onPause() {
        super.onPause()
        QueueRepository.removeListener(listener)
    }

    private fun reload() {
        QueueRepository.io(this, { dao -> dao.getAll() }) { all ->
            if (isDestroyed) return@io
            items = all
            adapter.notifyDataSetChanged()
            refreshState()
        }
    }

    private fun refreshState() {
        val running = QueueRunner.running
        val hasWaiting = items.any { it.state == QueueItem.STATE_WAITING }
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.queueToolbar.menu.apply {
            findItem(R.id.queue_start).isVisible = !running && hasWaiting
            findItem(R.id.queue_pause).isVisible = running
            findItem(R.id.queue_pause_now).isVisible = running
        }
        binding.queuePlaceholder.beVisibleIf(items.isEmpty())

        val interrupted = CompressionPrefs(this).queueInterrupted
        binding.queueBanner.beVisibleIf(!running && hasWaiting && interrupted != CompressionPrefs.INTERRUPTED_NONE)
        binding.queueBannerText.text = getString(
            if (interrupted == CompressionPrefs.INTERRUPTED_TIME_LIMIT) R.string.cmp_q_banner_time_limit else R.string.cmp_q_banner_killed
        )
    }

    private fun stateText(item: QueueItem): String = when (item.state) {
        QueueItem.STATE_WAITING -> getString(if (item.phase == QueueItem.PHASE_PUBLISHED) R.string.cmp_q_state_waiting_finish else R.string.cmp_q_state_waiting)
        QueueItem.STATE_RUNNING -> when (item.phase) {
            QueueItem.PHASE_COMPRESSING -> getString(R.string.cmp_q_state_compressing, item.progress)
            else -> getString(R.string.cmp_q_state_saving)
        }
        QueueItem.STATE_DONE -> getString(R.string.cmp_q_state_done)
        QueueItem.STATE_FAILED -> getString(R.string.cmp_q_state_failed)
        QueueItem.STATE_DISCARDED -> getString(R.string.cmp_q_state_discarded)
        else -> getString(R.string.cmp_q_state_canceled)
    }

    private fun detailText(item: QueueItem): String {
        val lines = mutableListOf<String>()
        val result = item.resultSize
        if (item.state == QueueItem.STATE_DONE && result != null) {
            val pct = if (item.originalSize > 0) ((result - item.originalSize) * 100.0 / item.originalSize).roundToInt() else 0
            lines.add(getString(R.string.cmp_q_sizes, item.originalSize.formatSize(), result.formatSize(), pct))
            item.resultName?.let { lines.add(getString(R.string.cmp_result_file, it)) }
        } else if (item.isPending) {
            lines.add(getString(R.string.cmp_q_estimate, item.originalSize.formatSize(), item.estimatedSize.formatSize()))
        }
        item.message?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        return lines.joinToString("\n")
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val binding: ItemCompressionQueueBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCompressionQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val textColor = getProperTextColor()
            holder.binding.apply {
                queueItemName.text = item.name
                queueItemName.setTextColor(textColor)
                queueItemState.text = stateText(item)
                queueItemState.setTextColor(if (item.state == QueueItem.STATE_RUNNING) getProperPrimaryColor() else textColor)
                queueItemProgress.beVisibleIf(item.state == QueueItem.STATE_RUNNING)
                queueItemProgress.isIndeterminate = item.state == QueueItem.STATE_RUNNING && item.phase != QueueItem.PHASE_COMPRESSING
                queueItemProgress.progress = item.progress
                val detail = detailText(item)
                queueItemDetail.beVisibleIf(detail.isNotEmpty())
                queueItemDetail.text = detail
                queueItemDetail.setTextColor(textColor)
            }
        }
    }
}
