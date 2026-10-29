package org.fossify.gallery.compression.queue

import android.content.Context
import org.fossify.gallery.R
import org.fossify.gallery.compression.ui.CompressionPrefs

/**
 * Estimativa de tempo restante pela velocidade medida em pixels por segundo (largura × altura × fps × segundos de vídeo,
 * divididos pelo tempo de compressão). Itens antigos, sem resolução, fps ou duração, não entram na média nem na soma.
 */
object QueueEta {
    /** 720p a 24 fps, medido nos testes do usuário. */
    const val DEFAULT_SPEED = 270_000_000.0
    private const val RECENT = 5

    data class Remaining(val seconds: Long, val items: Int, val unknown: Int)

    /** Pixels a processar no vídeo inteiro, ou null se o item não tem os dados (itens anteriores à migração). */
    fun pixels(width: Int, height: Int, fps: Float, durationMs: Long): Double? =
        if (width > 0 && height > 0 && fps > 0f && durationMs > 0) width.toDouble() * height * fps * durationMs / 1000.0 else null

    fun pixels(item: QueueItem): Double? = pixels(item.videoWidth, item.videoHeight, item.fps, item.durationMs)

    /** Velocidade medida neste item (só concluído ou descartado, que passaram pela compressão inteira). */
    fun sample(item: QueueItem): Double? {
        if (item.state != QueueItem.STATE_DONE && item.state != QueueItem.STATE_DISCARDED) return null
        val ms = item.compressMs?.takeIf { it > 0 } ?: return null
        return pixels(item)?.let { it / (ms / 1000.0) }
    }

    /** Guarda a velocidade do item nas amostras recentes. */
    fun record(prefs: CompressionPrefs, item: QueueItem) {
        val sample = sample(item) ?: return
        prefs.speedSamples = (prefs.speedSamples + sample).takeLast(RECENT)
    }

    /** Média das amostras guardadas; se não houver, das últimas do banco; se também não, o padrão conservador. */
    fun speed(prefs: CompressionPrefs, items: List<QueueItem>): Double {
        val stored = prefs.speedSamples
        val samples = stored.ifEmpty {
            items.filter { sample(it) != null }.sortedBy { it.finishedAt ?: 0L }.takeLast(RECENT).mapNotNull { sample(it) }
        }
        return samples.takeIf { it.isNotEmpty() }?.average() ?: DEFAULT_SPEED
    }

    /** [pending] em andamento ou aguardando. O item em andamento conta só a parte que falta da compressão. */
    fun remaining(pending: List<QueueItem>, speed: Double): Remaining {
        var seconds = 0.0
        var unknown = 0
        pending.forEach { item ->
            val pixels = pixels(item)
            if (pixels == null) {
                unknown++
                return@forEach
            }
            val fraction = when {
                item.state != QueueItem.STATE_RUNNING -> if (item.phase == QueueItem.PHASE_PUBLISHED) 0.0 else 1.0
                item.phase == QueueItem.PHASE_COMPRESSING -> (100 - item.progress).coerceIn(0, 100) / 100.0
                else -> 0.0
            }
            seconds += pixels * fraction / speed
        }
        return Remaining(Math.round(seconds), pending.size, unknown)
    }

    fun remaining(context: Context, all: List<QueueItem>): Remaining =
        remaining(all.filter { it.isPending }, speed(CompressionPrefs(context), all))

    /** "≈ 12 min", "≈ 1 h 05 min"; sempre com o ≈. */
    fun formatDuration(context: Context, seconds: Long): String {
        val minutes = (seconds + 59) / 60
        return when {
            seconds <= 0 -> context.getString(R.string.cmp_eta_less_than_min)
            minutes < 60 -> context.getString(R.string.cmp_eta_min, minutes.toInt())
            else -> context.getString(R.string.cmp_eta_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    /** "Restante: ≈ 12 min (3 itens)", ou null se não há nada pendente. */
    fun format(context: Context, remaining: Remaining): String? {
        if (remaining.items == 0) return null
        if (remaining.unknown == remaining.items) {
            return context.getString(R.string.cmp_eta_none, context.resources.getQuantityString(R.plurals.cmp_eta_items, remaining.items, remaining.items))
        }
        val time = formatDuration(context, remaining.seconds)
        val count = context.resources.getQuantityString(R.plurals.cmp_eta_items, remaining.items, remaining.items)
        val base = context.getString(R.string.cmp_eta_remaining, time, count)
        return if (remaining.unknown > 0) base + context.getString(R.string.cmp_eta_unknown, remaining.unknown) else base
    }
}
