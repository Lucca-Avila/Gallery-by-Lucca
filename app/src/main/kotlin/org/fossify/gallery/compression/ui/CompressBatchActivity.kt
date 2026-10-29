package org.fossify.gallery.compression.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.activities.SimpleActivity
import org.fossify.gallery.compression.analyzer.VideoInfo
import org.fossify.gallery.compression.analyzer.VideoInfoCache
import org.fossify.gallery.compression.engine.CompressionPlanner
import org.fossify.gallery.compression.engine.CompressionPlanner.Preset
import org.fossify.gallery.compression.queue.QueueEta
import org.fossify.gallery.compression.queue.QueueRepository
import org.fossify.gallery.databinding.ActivityCompressBatchBinding
import org.fossify.gallery.databinding.ItemCompressBatchBinding
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * Tela de lote (modo NOVO, 2 ou mais vídeos): regra geral no topo, um vídeo por linha com a sua estimativa e as sobrescritas,
 * total, tempo estimado e "Adicionar todos à fila" no rodapé. A lista e as escolhas ficam num [Session] em memória do processo
 * (não no Intent); se o processo morrer, a tela fecha.
 */
@UnstableApi
class CompressBatchActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCompressBatchBinding::inflate)
    private val adapter = Adapter()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { CompressionPrefs(this) }
    private lateinit var session: Session

    /** Cálculo das linhas fora da thread principal; só o resultado do pedido mais recente é mostrado. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "batch-estimates").apply { isDaemon = true } }
    private var generation = 0
    private var scheduled = false
    private var summary: Summary? = null
    private var speed = QueueEta.DEFAULT_SPEED

    /** Estado do lote: sobrevive a girar a tela, some com o processo. Acessado pela tela e pela thread de análise. */
    class Session(val flow: CompressFlow, val paths: List<String>, @Volatile var general: CompressRule) {
        val infos = ConcurrentHashMap<String, VideoInfo>()
        val errors = ConcurrentHashMap<String, String>()
        val overrides = ConcurrentHashMap<String, RuleOverride>()
        val removed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var analyzed = 0
        @Volatile var closed = false
        @Volatile var listener: (() -> Unit)? = null
        var started = false

        fun ruleFor(path: String) = overrides[path]?.applyTo(general) ?: general

        /** Vídeos legíveis que continuam no lote, na ordem da seleção. */
        fun videos(): List<Pair<String, VideoInfo>> = paths.filter { it !in removed }.mapNotNull { p -> infos[p]?.let { p to it } }

        /** Pede todas as análises ao [VideoInfoCache] (as do cache voltam na hora) e espera cada uma, na ordem. */
        fun analyze(activity: BaseSimpleActivity) {
            started = true
            val appContext = activity.applicationContext
            val pending = paths.map { path ->
                path to try {
                    val uri = activity.getFinalUriFromPath(path, BuildConfig.APPLICATION_ID) ?: throw IllegalStateException("uri")
                    VideoInfoCache.submit(activity, path, uri)
                } catch (e: Exception) {
                    null
                }
            }
            ensureBackgroundThread { await(appContext, pending) }
        }

        private fun await(context: Context, pending: List<Pair<String, Future<VideoInfo>?>>) {
            pending.forEach { (path, future) ->
                if (closed) return
                try {
                    val info = VideoInfoCache.await(future ?: throw IllegalStateException("uri")) { !closed } ?: return
                    val decoder = info.videoDecoder
                    if (decoder != null && !decoder.supported) {
                        errors[path] = context.getString(R.string.cmp_err_no_decoder, info.videoMime ?: context.getString(R.string.cmp_unknown))
                    } else {
                        infos[path] = info
                    }
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    errors[path] = context.getString(R.string.vi_error, cause.message ?: cause.javaClass.simpleName)
                }
                analyzed++
                listener?.invoke()
            }
        }
    }

    private data class Row(
        val path: String,
        val name: String,
        val detail: String,
        val efficient: Boolean,
        val custom: Boolean,
        val clickable: Boolean,
    )

    private class Summary(
        val rows: List<Row>,
        val originalTotal: Long,
        val estimateTotal: Long,
        val etaSeconds: Long,
        val ready: Int,
        val efficient: List<String>,
        val skipped: Int,
        val done: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = sessions[intent.getLongExtra(EXTRA_SESSION, -1L)]
        if (found == null) {
            // O processo morreu e a lista em memória sumiu.
            finish()
            return
        }
        session = found
        setContentView(binding.root)
        binding.batchList.adapter = adapter
        binding.batchRuleEdit.setOnClickListener { editGeneral() }
        binding.batchRemoveEfficient.setOnClickListener { removeEfficient() }
        binding.batchAddAll.setOnClickListener { addAll() }
        setupEdgeToEdge(padTopSystem = listOf(binding.batchAppbar), padBottomSystem = listOf(binding.batchFooter))
        setupMaterialScrollListener(binding.batchList, binding.batchAppbar)

        session.listener = { runOnUiThread { scheduleRecompute() } }
        if (!session.started) session.analyze(this)
        QueueRepository.io(this, { dao -> QueueEta.speed(prefs, dao.getAll()) }) {
            speed = it
            recompute()
        }
        refreshRule()
        recompute()
    }

    override fun onResume() {
        super.onResume()
        if (!::session.isInitialized) return
        setupTopAppBar(binding.batchAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.batchHolder)
        val primary = getProperPrimaryColor()
        listOf(binding.batchRuleEdit, binding.batchRemoveEfficient, binding.batchAddAll).forEach { it.setTextColor(primary) }
        val divider = getProperTextColor()
        binding.batchDividerTop.setBackgroundColor(divider)
        binding.batchDividerBottom.setBackgroundColor(divider)
        summary?.let { applySummary(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        if (::session.isInitialized && isFinishing) {
            session.listener = null
            session.closed = true
            sessions.values.remove(session)
        }
    }

    /** Durante a análise, as linhas são recalculadas no máximo a cada [THROTTLE_MS]. */
    private fun scheduleRecompute() {
        if (scheduled || isDestroyed) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            recompute()
        }, THROTTLE_MS)
    }

    private fun recompute() {
        if (isDestroyed || worker.isShutdown) return
        val gen = ++generation
        val speed = speed
        worker.execute {
            val result = compute(speed)
            runOnUiThread {
                if (gen == generation && !isDestroyed) applySummary(result)
            }
        }
    }

    /** Mesmas contas do diálogo de 1 vídeo ([RulePlanner]), com a regra efetiva de cada vídeo. */
    private fun compute(speed: Double): Summary {
        val rows = ArrayList<Row>(session.paths.size)
        var original = 0L
        var estimate = 0L
        var seconds = 0.0
        var ready = 0
        var skipped = 0
        val efficient = mutableListOf<String>()
        session.paths.forEach { path ->
            if (path in session.removed) return@forEach
            val name = File(path).name
            val info = session.infos[path]
            val error = session.errors[path]
            if (info == null) {
                if (error != null) skipped++
                val detail = if (error != null) getString(R.string.cmp_b_row_failed, error) else getString(R.string.cmp_b_row_analyzing)
                rows.add(Row(path, name, detail, efficient = false, custom = false, clickable = false))
                return@forEach
            }
            val rule = session.ruleFor(path)
            val shortSide = RulePlanner.effectiveShortSide(info, rule)
            val size = CompressionPlanner.outputSize(info, shortSide)
            val est = RulePlanner.estimateSize(info, rule)
            val isEfficient = RulePlanner.isEfficient(info, rule)
            val resolution = "${size.width}×${size.height}"
            val res = if (shortSide == null) getString(R.string.cmp_b_row_original_res, resolution) else resolution
            val detail = getString(R.string.cmp_b_row_estimate, res, est.formatSize(), formatDelta(est, info.fileSize))
            rows.add(Row(path, name, detail, isEfficient, custom = session.overrides.containsKey(path), clickable = true))
            original += info.fileSize
            estimate += est
            seconds += (QueueEta.pixels(size.width, size.height, CompressionPlanner.fpsOf(info), info.durationMs) ?: 0.0) / speed
            ready++
            if (isEfficient) efficient.add(path)
        }
        return Summary(rows, original, estimate, Math.round(seconds), ready, efficient, skipped, done = session.analyzed >= session.paths.size)
    }

    private fun applySummary(s: Summary) {
        summary = s
        adapter.submitList(s.rows)
        binding.batchToolbar.title = getString(R.string.cmp_b_title, s.rows.size)
        binding.batchTotal.text = if (!s.done) {
            getString(R.string.cmp_b_total_analyzing, session.analyzed + 1, session.paths.size)
        } else {
            getString(
                R.string.cmp_b_total, s.originalTotal.formatSize(), s.estimateTotal.formatSize(), formatDelta(s.estimateTotal, s.originalTotal),
                QueueEta.formatDuration(this, s.etaSeconds)
            )
        }
        binding.batchEfficient.text = if (s.efficient.isEmpty()) {
            getString(R.string.cmp_b_efficient_none)
        } else {
            getString(R.string.cmp_b_efficient_count, s.efficient.size)
        }
        binding.batchSkipped.beVisibleIf(s.skipped > 0)
        binding.batchSkipped.text = getString(R.string.cmp_b_skipped, s.skipped)
        setEnabled(binding.batchRuleEdit, s.ready > 0)
        setEnabled(binding.batchRemoveEfficient, s.efficient.isNotEmpty())
        setEnabled(binding.batchAddAll, s.done && s.ready > 0)
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun refreshRule() {
        val rule = session.general
        val quality = when (rule.preset) {
            Preset.QUALITY -> getString(R.string.cmp_b_rule_quality, getString(R.string.cmp_preset_quality))
            Preset.BALANCED -> getString(R.string.cmp_b_rule_quality, getString(R.string.cmp_preset_balanced))
            Preset.MAX_SAVING -> getString(R.string.cmp_b_rule_quality, getString(R.string.cmp_preset_saving))
            null -> getString(R.string.cmp_b_custom_each, rule.customPct.coerceAtLeast(RulePlanner.MIN_CUSTOM_PCT))
        }
        val resolution = rule.shortSide?.let { getString(R.string.cmp_b_res_max, it) } ?: getString(R.string.cmp_b_res_original)
        val dest = when (rule.destMode) {
            CompressionPrefs.DEST_COMPRIMIDOS -> getString(R.string.cmp_b_dest_comprimidos)
            CompressionPrefs.DEST_CUSTOM -> rule.customPath.orEmpty()
            else -> getString(R.string.cmp_b_dest_same)
        }
        val after = when (rule.after) {
            CompressionPrefs.AFTER_TRASH -> getString(R.string.cmp_b_after_trash)
            CompressionPrefs.AFTER_DELETE -> getString(R.string.cmp_b_after_delete)
            else -> getString(R.string.cmp_b_after_keep)
        }
        binding.batchRuleSummary.text = listOf(
            quality,
            getString(R.string.cmp_b_rule_resolution, resolution),
            getString(R.string.cmp_b_rule_dest, dest),
            getString(R.string.cmp_b_rule_after, after),
        ).joinToString("\n")
    }

    private fun editGeneral() {
        val videos = session.videos().ifEmpty { return }
        val mode = CompressOptionsDialog.RuleMode(videos, session.general, getString(R.string.cmp_b_rule_title), onApply = { rule, _ ->
            session.general = rule
            // Sobrescritas que passaram a coincidir com a regra geral deixam de existir.
            session.overrides.keys.toList().forEach { path ->
                val pruned = session.overrides[path]?.pruned(rule, session.infos[path])
                if (pruned == null) session.overrides.remove(path) else session.overrides[path] = pruned
            }
            refreshRule()
            recompute()
        })
        CompressOptionsDialog(this, videos.map { it.first }, { _, _ -> }, mode)
    }

    private fun editVideo(path: String) {
        val info = session.infos[path] ?: return
        val existing = session.overrides[path]
        val mode = CompressOptionsDialog.RuleMode(
            listOf(path to info),
            session.ruleFor(path),
            getString(R.string.cmp_b_override_title, File(path).name),
            onApply = { rule, changed -> applyOverride(path, info, rule, changed) },
            onReset = if (existing != null) {
                {
                    session.overrides.remove(path)
                    recompute()
                }
            } else {
                null
            },
        )
        CompressOptionsDialog(this, listOf(path), { _, _ -> }, mode)
    }

    /** Guarda só os campos que mudaram agora (somados aos já sobrescritos); os iguais à regra geral são descartados. */
    private fun applyOverride(path: String, info: VideoInfo, rule: CompressRule, changed: Set<RuleField>) {
        if (changed.isEmpty()) return
        val old = session.overrides[path]
        val merged = RuleOverride(RuleOverride.copyFields(rule, old?.rule ?: session.general, changed), (old?.fields ?: emptySet()) + changed)
            .pruned(session.general, info)
        if (merged == null) session.overrides.remove(path) else session.overrides[path] = merged
        recompute()
    }

    private fun removeEfficient() {
        val efficient = summary?.efficient.orEmpty()
        if (efficient.isEmpty()) return
        session.removed.addAll(efficient)
        efficient.forEach { session.overrides.remove(it) }
        toast(getString(R.string.cmp_b_removed, efficient.size))
        recompute()
    }

    private fun addAll() {
        setEnabled(binding.batchAddAll, false)
        worker.execute {
            val planned = session.videos().mapNotNull { (path, info) -> RulePlanner.plan(path, info, session.ruleFor(path)) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                summary?.let { applySummary(it) }
                if (planned.isEmpty()) {
                    toast(R.string.cmp_q_nothing_added)
                    return@runOnUiThread
                }
                session.flow.add(this, planned) {
                    prefs.lastRule = session.general
                    finish()
                }
            }
        }
    }

    private fun formatDelta(estimate: Long, original: Long): String {
        val delta = if (original > 0) ((estimate - original) * 100.0 / original).roundToInt() else 0
        return if (delta <= 0) "−${-delta}%" else "+$delta%"
    }

    private inner class Adapter : ListAdapter<Row, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(ItemCompressBatchBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = getItem(position)
            val textColor = getProperTextColor()
            ItemCompressBatchBinding.bind(holder.itemView).apply {
                batchItemName.text = row.name
                batchItemName.setTextColor(textColor)
                batchItemDetail.text = row.detail
                batchItemDetail.setTextColor(textColor)
                batchItemBadges.beVisibleIf(row.efficient || row.custom)
                batchItemBadgeEfficient.beVisibleIf(row.efficient)
                batchItemBadgeEfficient.background = badge(EFFICIENT_COLOR)
                batchItemBadgeEfficient.setTextColor(textColor)
                batchItemBadgeCustom.beVisibleIf(row.custom)
                batchItemBadgeCustom.background = badge(getProperPrimaryColor())
                batchItemBadgeCustom.setTextColor(textColor)
                batchItemHolder.isClickable = row.clickable
                batchItemHolder.setOnClickListener(if (row.clickable) View.OnClickListener { editVideo(row.path) } else null)
            }
        }

        private fun badge(color: Int) = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 6
            setColor(Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    companion object {
        private const val EXTRA_SESSION = "batch_session"
        private const val THROTTLE_MS = 200L
        private const val DISABLED_ALPHA = 0.4f
        private val EFFICIENT_COLOR = Color.parseColor("#FFB300")
        private val sessions = ConcurrentHashMap<Long, Session>()

        /** A lista vai por memória do processo; o Intent só leva a chave. */
        fun open(activity: BaseSimpleActivity, flow: CompressFlow, paths: List<String>) {
            val id = System.nanoTime()
            sessions[id] = Session(flow, paths, CompressionPrefs(activity).lastRule)
            activity.startActivity(Intent(activity, CompressBatchActivity::class.java).putExtra(EXTRA_SESSION, id))
        }
    }
}
