package org.fossify.gallery.compression.queue

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.compression.ui.CompressionPrefs
import org.fossify.gallery.databinding.ActivityCompressionQueueBinding
import org.fossify.gallery.databinding.ItemCompressionQueueBinding
import org.fossify.gallery.databinding.ItemQueueHeaderBinding
import org.fossify.gallery.databinding.ItemQueueRunningBinding
import org.fossify.gallery.databinding.ItemQueueSectionBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tela da fila: restante e resumo no topo, item em andamento em destaque, aguardando e histórico.
 * Mantém a tela ligada enquanto a fila roda.
 */
@UnstableApi
class QueueActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCompressionQueueBinding::inflate)
    private val adapter = Adapter()
    private var items = emptyList<QueueItem>()
    private val listener: () -> Unit = { reload() }
    private val touchHelper = ItemTouchHelper(DragCallback())

    /** Durante o arraste a lista não recarrega (o progresso da fila bagunçaria o gesto); recarrega ao soltar. */
    private var dragging = false
    private var orderBeforeDrag = emptyList<Long>()

    /** Linhas da lista; cada uma só muda quando o seu conteúdo muda (DiffUtil), então o progresso atualiza só o cartão do item atual. */
    private sealed interface Row {
        val key: String
    }

    private data class HeaderRow(val eta: String?, val runTitle: String?, val runLine: String?, val runFooter: String?) : Row {
        override val key get() = "header"
    }

    private data class RunningRow(val item: QueueItem, val state: String, val detail: String, val canCancel: Boolean) : Row {
        override val key get() = "running"
    }

    private data class SectionRow(val id: String, val title: String, val actionRes: Int?) : Row {
        override val key get() = "section_$id"
    }

    private data class EntryRow(val item: QueueItem, val state: String, val detail: String) : Row {
        override val key get() = "item_${item.id}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.queueList.adapter = adapter
        touchHelper.attachToRecyclerView(binding.queueList)
        binding.queueToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.queue_start -> QueueStarter.start(this)
                R.id.queue_pause -> QueueService.send(this, QueueService.ACTION_PAUSE)
                R.id.queue_pause_now -> QueueService.send(this, QueueService.ACTION_PAUSE_NOW)
                R.id.queue_dim -> startActivity(Intent(this, QueueDimActivity::class.java))
                R.id.queue_cancel_all -> confirmCancelAll()
                R.id.queue_thermal_pause -> toggleThermalPause()
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
        if (dragging) return
        QueueRepository.io(this, { dao -> dao.getAll() }) { all ->
            if (isDestroyed || dragging) return@io
            items = all
            adapter.submit(buildRows())
            refreshState()
        }
    }

    /** Aguardando que ainda não gravou nada: o único que pode mudar de lugar. */
    private fun isMovable(item: QueueItem) = item.state == QueueItem.STATE_WAITING && item.phase == QueueItem.PHASE_NONE

    private fun movableOrder() = adapter.rows.mapNotNull { (it as? EntryRow)?.item?.takeIf(::isMovable)?.id }

    private fun onDragEnded() {
        dragging = false
        val order = movableOrder()
        if (order == orderBeforeDrag) {
            reload()
            return
        }
        QueueRepository.reorder(this, order) { skipped ->
            if (skipped > 0 && !isDestroyed) toast(R.string.cmp_q_reorder_skipped)
        }
    }

    private inner class DragCallback : ItemTouchHelper.Callback() {
        private fun movableAt(holder: RecyclerView.ViewHolder) =
            (adapter.rows.getOrNull(holder.bindingAdapterPosition) as? EntryRow)?.item?.let(::isMovable) == true

        override fun isLongPressDragEnabled() = false

        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (movableAt(viewHolder)) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0

        override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = movableAt(target)

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                orderBeforeDrag = movableOrder()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (dragging) onDragEnded()
        }
    }

    /** Liga/desliga a pausa por temperatura; o serviço, se ativo, aplica na hora (desligar durante a pausa retoma). */
    private fun toggleThermalPause() {
        val prefs = CompressionPrefs(this)
        prefs.queueThermalPause = !prefs.queueThermalPause
        ThermalLog.event(this, ThermalLog.PREF, null, "pausar se esquentar: " + if (prefs.queueThermalPause) "ligada" else "desligada")
        QueueService.send(this, QueueService.ACTION_THERMAL_PREF)
        refreshState()
    }

    private fun refreshState() {
        val running = QueueRunner.running
        val cooling = QueueService.cooling
        val hasWaiting = items.any { it.state == QueueItem.STATE_WAITING }
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.queueToolbar.menu.apply {
            findItem(R.id.queue_start).apply {
                isVisible = !running && hasWaiting
                setTitle(if (cooling) R.string.cmp_q_resume_now else R.string.cmp_q_start)
            }
            findItem(R.id.queue_pause).isVisible = running || cooling
            findItem(R.id.queue_pause_now).isVisible = running
            findItem(R.id.queue_dim).isVisible = running
            findItem(R.id.queue_cancel_all).isVisible = items.any { it.isPending }
            findItem(R.id.queue_thermal_pause).isChecked = CompressionPrefs(this@QueueActivity).queueThermalPause
        }
        binding.queuePlaceholder.beVisibleIf(items.isEmpty())

        val interrupted = CompressionPrefs(this).queueInterrupted
        binding.queueBanner.beVisibleIf(cooling || !running && hasWaiting && interrupted != CompressionPrefs.INTERRUPTED_NONE)
        binding.queueBannerResume.setText(if (cooling) R.string.cmp_q_resume_now else R.string.cmp_q_resume)
        binding.queueBannerText.text = when {
            cooling -> getString(R.string.cmp_q_banner_thermal)
            interrupted == CompressionPrefs.INTERRUPTED_TIME_LIMIT -> getString(R.string.cmp_q_banner_time_limit)
            interrupted == CompressionPrefs.INTERRUPTED_THERMAL -> getString(R.string.cmp_q_banner_thermal_gave_up, (THERMAL_MAX_PAUSE_MS / 60_000).toInt())
            else -> getString(R.string.cmp_q_banner_killed)
        }
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val remaining = QueueEta.remaining(this, items)
        val eta = QueueEta.format(this, remaining)
        val run = CompressionPrefs(this).lastRun?.takeIf { it.done + it.failed + it.discarded + it.canceled > 0 }
        if (eta != null || run != null) {
            val running = QueueRunner.running
            rows.add(
                HeaderRow(
                    eta = eta,
                    runTitle = run?.let { getString(if (running) R.string.cmp_q_run_title_running else R.string.cmp_q_run_title_last) },
                    runLine = run?.let { getString(R.string.cmp_q_run_line, it.done, it.failed, it.discarded, it.canceled) },
                    runFooter = run?.let { runFooter(it, running) },
                )
            )
        }
        items.firstOrNull { it.state == QueueItem.STATE_RUNNING }?.let { item ->
            rows.add(
                RunningRow(
                    item, stateText(item), detailText(item),
                    canCancel = QueueRunner.running && item.phase == QueueItem.PHASE_COMPRESSING,
                )
            )
        }
        // Mesma ordem em que o runner os pega (QueueDao.firstWaiting).
        val waiting = items.filter { it.state == QueueItem.STATE_WAITING }.sortedWith(compareBy<QueueItem> { it.position }.thenBy { it.id })
        if (waiting.isNotEmpty()) {
            rows.add(SectionRow("waiting", getString(R.string.cmp_q_section_waiting, waiting.size), null))
            waiting.forEach { rows.add(EntryRow(it, stateText(it), detailText(it))) }
        }
        val history = items.filter { !it.isPending }
            .sortedWith(compareByDescending<QueueItem> { it.finishedAt ?: 0L }.thenByDescending { it.id })
        if (history.isNotEmpty()) {
            val clearable = history.any { it.state != QueueItem.STATE_FAILED }
            rows.add(SectionRow("history", getString(R.string.cmp_q_section_history, history.size), if (clearable) R.string.cmp_q_clear else null))
            history.forEach { rows.add(EntryRow(it, stateText(it), detailText(it))) }
        }
        return rows
    }

    private fun runFooter(run: CompressionPrefs.LastRun, running: Boolean): String {
        val saved = run.savedBytes.formatSize()
        val at = DateUtils.formatDateTime(this, run.updatedAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
        val footer = when {
            running -> getString(R.string.cmp_q_run_footer_running, saved)
            run.reason == CompressionPrefs.LastRun.REASON_FINISHED -> getString(R.string.cmp_q_run_footer_finished, saved, at)
            run.reason == CompressionPrefs.LastRun.REASON_PAUSED -> getString(R.string.cmp_q_run_footer_paused, saved, at)
            run.reason == CompressionPrefs.LastRun.REASON_TIME_LIMIT -> getString(R.string.cmp_q_run_footer_time_limit, saved, at)
            run.reason == CompressionPrefs.LastRun.REASON_THERMAL -> getString(R.string.cmp_q_run_footer_thermal, saved, at)
            else -> getString(R.string.cmp_q_run_footer_interrupted, saved, at)
        }
        if (run.thermalPauses == 0) return footer
        val thermalAt = DateUtils.formatDateTime(this, run.thermalAt, DateUtils.FORMAT_SHOW_TIME)
        return footer + "\n" + resources.getQuantityString(R.plurals.cmp_q_run_thermal_line, run.thermalPauses, thermalAt, run.thermalPauses)
    }

    private fun confirmCancelAll() {
        val others = items.count { it.state == QueueItem.STATE_WAITING }
        getAlertDialogBuilder()
            .setTitle(R.string.cmp_q_cancel_all_title)
            .setMessage(getString(R.string.cmp_q_cancel_all_msg, others))
            .setPositiveButton(R.string.cmp_q_cancel_all_ok) { _, _ ->
                if (QueueService.active) QueueService.send(this, QueueService.ACTION_CANCEL_ALL) else QueueRepository.cancelWaiting(this)
            }
            .setNegativeButton(R.string.cmp_q_cancel_all_back, null)
            .show()
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

    private fun isFinished(item: QueueItem) = !item.isPending

    private fun showDetails(item: QueueItem) {
        val lines = mutableListOf(getString(R.string.cmp_q_detail_original, item.name), getString(R.string.cmp_result_before, item.originalSize.formatSize()))
        val result = item.resultSize
        if (result != null) {
            val pct = if (item.originalSize > 0) ((result - item.originalSize) * 100.0 / item.originalSize).roundToInt() else 0
            lines.add(getString(R.string.cmp_result_after, result.formatSize(), (if (pct > 0) "+" else "") + pct))
        }
        if (item.state == QueueItem.STATE_DONE) {
            item.resultName?.let { lines.add(getString(R.string.cmp_result_file, it)) }
            item.resultLocation?.let { lines.add(getString(R.string.cmp_result_folder, it)) }
        }
        if (item.videoWidth > 0 && item.videoHeight > 0 && item.fps > 0f) {
            lines.add(getString(R.string.cmp_q_detail_resolution, item.videoWidth, item.videoHeight, String.format(Locale.US, "%.0f", item.fps)))
        }
        if (result != null && item.durationMs > 0) {
            val real = (result * 8000.0 / item.durationMs).toLong()
            lines.add(getString(R.string.cmp_q_detail_bitrate, formatBitrate(item.videoBitrate.toLong()), formatBitrate(real)))
        }
        item.compressMs?.let { compress ->
            val total = (item.finishedAt ?: 0L) - (item.startedAt ?: 0L)
            lines.add(getString(R.string.cmp_q_detail_time, formatElapsed(compress), if (total > 0) formatElapsed(total) else "—"))
        }
        if (item.state == QueueItem.STATE_DONE) {
            lines.add(if (item.dateOk) getString(R.string.cmp_result_date_ok) else getString(R.string.cmp_result_date_fail, item.dateDetail.orEmpty()))
        }
        item.message?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
        getAlertDialogBuilder()
            .setTitle(stateText(item))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.cmp_q_details_ok, null)
            .show()
    }

    private fun formatBitrate(bps: Long): String =
        if (bps >= 1_000_000) String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0) else "${bps / 1000} kbps"

    private fun formatElapsed(ms: Long): String {
        val seconds = (ms / 1000).toInt()
        return when {
            seconds < 60 -> getString(R.string.cmp_q_duration_s, seconds)
            seconds < 3600 -> getString(R.string.cmp_q_duration_ms, seconds / 60, seconds % 60)
            else -> getString(R.string.cmp_q_duration_hms, seconds / 3600, seconds % 3600 / 60)
        }
    }

    private fun showItemMenu(anchor: View, item: QueueItem) {
        PopupMenu(this, anchor).apply {
            if (QueueEdit.isEditable(item)) menu.add(0, MENU_EDIT, 0, R.string.cmp_q_edit)
            menu.add(0, MENU_REMOVE, 1, R.string.cmp_q_remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_EDIT -> QueueEdit.open(this@QueueActivity, item.id)
                    else -> QueueRepository.remove(this@QueueActivity, item.id)
                }
                true
            }
            show()
        }
    }

    private fun canRemove(item: QueueItem) = isFinished(item) || (item.state == QueueItem.STATE_WAITING && item.phase == QueueItem.PHASE_NONE)

    /** Diferenças calculadas na hora (lista pequena), para o arraste mover as linhas de forma síncrona. */
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows = emptyList<Row>()
            private set

        fun submit(newRows: List<Row>) {
            val old = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newRows.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key == newRows[newPos].key
                override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == newRows[newPos]
            })
            rows = newRows
            diff.dispatchUpdatesTo(this)
        }

        fun move(from: Int, to: Int) {
            rows = rows.toMutableList().apply { add(to, removeAt(from)) }
            notifyItemMoved(from, to)
        }

        private fun getItem(position: Int) = rows[position]

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is HeaderRow -> TYPE_HEADER
            is RunningRow -> TYPE_RUNNING
            is SectionRow -> TYPE_SECTION
            is EntryRow -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> object : RecyclerView.ViewHolder(ItemQueueHeaderBinding.inflate(inflater, parent, false).root) {}
                TYPE_RUNNING -> object : RecyclerView.ViewHolder(ItemQueueRunningBinding.inflate(inflater, parent, false).root) {}
                TYPE_SECTION -> object : RecyclerView.ViewHolder(ItemQueueSectionBinding.inflate(inflater, parent, false).root) {}
                else -> object : RecyclerView.ViewHolder(ItemCompressionQueueBinding.inflate(inflater, parent, false).root) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val textColor = getProperTextColor()
            val primary = getProperPrimaryColor()
            when (val row = getItem(position)) {
                is HeaderRow -> ItemQueueHeaderBinding.bind(holder.itemView).apply {
                    queueHeaderEta.beVisibleIf(row.eta != null)
                    queueHeaderEta.text = row.eta
                    queueHeaderRunTitle.beVisibleIf(row.runTitle != null)
                    queueHeaderRunTitle.text = row.runTitle
                    queueHeaderRunLine.beVisibleIf(row.runLine != null)
                    queueHeaderRunLine.text = row.runLine
                    queueHeaderRunFooter.beVisibleIf(row.runFooter != null)
                    queueHeaderRunFooter.text = row.runFooter
                    listOf(queueHeaderEta, queueHeaderRunTitle, queueHeaderRunLine, queueHeaderRunFooter).forEach { it.setTextColor(textColor) }
                }

                is RunningRow -> ItemQueueRunningBinding.bind(holder.itemView).apply {
                    queueRunningHolder.background = GradientDrawable().apply {
                        cornerRadius = resources.displayMetrics.density * 12
                        setColor(Color.argb(40, Color.red(primary), Color.green(primary), Color.blue(primary)))
                    }
                    queueRunningName.text = row.item.name
                    queueRunningState.text = row.state
                    queueRunningState.setTextColor(primary)
                    queueRunningName.setTextColor(textColor)
                    queueRunningProgress.isIndeterminate = row.item.phase != QueueItem.PHASE_COMPRESSING
                    queueRunningProgress.progress = row.item.progress
                    queueRunningDetail.beVisibleIf(row.detail.isNotEmpty())
                    queueRunningDetail.text = row.detail
                    queueRunningDetail.setTextColor(textColor)
                    queueRunningCancel.beVisibleIf(row.canCancel)
                    queueRunningCancel.setTextColor(primary)
                    queueRunningCancel.setOnClickListener { QueueService.send(this@QueueActivity, QueueService.ACTION_SKIP) }
                }

                is SectionRow -> ItemQueueSectionBinding.bind(holder.itemView).apply {
                    queueSectionTitle.text = row.title
                    queueSectionTitle.setTextColor(primary)
                    queueSectionAction.beVisibleIf(row.actionRes != null)
                    row.actionRes?.let { queueSectionAction.setText(it) }
                    queueSectionAction.setTextColor(primary)
                    queueSectionAction.setOnClickListener { QueueRepository.clearFinished(this@QueueActivity) }
                }

                is EntryRow -> ItemCompressionQueueBinding.bind(holder.itemView).apply {
                    val item = row.item
                    queueItemDrag.beVisibleIf(isMovable(item))
                    queueItemDrag.applyColorFilter(textColor)
                    queueItemDrag.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                        false
                    }
                    queueItemName.text = item.name
                    queueItemName.setTextColor(textColor)
                    queueItemState.text = row.state
                    queueItemState.setTextColor(if (item.state == QueueItem.STATE_FAILED) FAILED_COLOR else textColor)
                    queueItemDetail.beVisibleIf(row.detail.isNotEmpty())
                    queueItemDetail.text = row.detail
                    queueItemDetail.setTextColor(textColor)
                    queueItemMenu.beVisibleIf(canRemove(item))
                    queueItemMenu.setColorFilter(textColor)
                    queueItemMenu.setOnClickListener { showItemMenu(it, item) }
                    queueItemHolder.isClickable = isFinished(item)
                    queueItemHolder.setOnClickListener(if (isFinished(item)) View.OnClickListener { showDetails(item) } else null)
                }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_RUNNING = 1
        const val TYPE_SECTION = 2
        const val TYPE_ENTRY = 3
        const val MENU_EDIT = 1
        const val MENU_REMOVE = 2
        val FAILED_COLOR = Color.parseColor("#E53935")
    }
}
