package org.fossify.gallery.compression.queue

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MenuItem
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import org.fossify.gallery.R

/**
 * Ícone da fila na barra da tela inicial, com um contador sobre ele quando há itens aguardando ou em andamento.
 * [attach] em onResume e [detach] em onPause; [item] devolve o item do menu (a barra pode ser recriada).
 */
@OptIn(UnstableApi::class)
class QueueMenuBadge(private val context: Context, private val item: () -> MenuItem?, private val recolor: () -> Unit) {
    private val listener: () -> Unit = { refresh() }
    private var shown = -1

    fun attach() {
        QueueRepository.addListener(listener)
        shown = -1
        refresh()
    }

    fun detach() = QueueRepository.removeListener(listener)

    private fun refresh() {
        QueueRepository.io(context, { dao -> dao.getPending().size }) { count ->
            if (count == shown) return@io
            val menuItem = item() ?: return@io
            shown = count
            ContextCompat.getDrawable(context, R.drawable.ic_playlist_play_vector)?.let {
                menuItem.icon = BadgeDrawable(it.mutate(), count, context.resources.displayMetrics.density)
                recolor()
            }
        }
    }

    /** Desenha o ícone e, se [count] > 0, uma bolinha vermelha com o número no canto superior direito. */
    private class BadgeDrawable(private val base: Drawable, private val count: Int, private val density: Float) : Drawable() {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onBoundsChange(bounds: Rect) {
            base.bounds = bounds
        }

        override fun draw(canvas: Canvas) {
            base.draw(canvas)
            if (count <= 0) return
            val b = bounds
            val radius = b.width() * 0.25f
            val cx = b.right - radius
            val cy = b.top + radius
            canvas.drawCircle(cx, cy, radius, dot)
            text.textSize = radius * if (count > 9) 1.1f else 1.4f
            val label = if (count > 9) "9+" else count.toString()
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
        }

        // O tema da barra pinta o ícone: o filtro vale só para o desenho base, não para a bolinha.
        override fun setColorFilter(filter: ColorFilter?) {
            base.colorFilter = filter
            invalidateSelf()
        }

        override fun setAlpha(alpha: Int) {
            base.alpha = alpha
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = base.intrinsicWidth
        override fun getIntrinsicHeight() = base.intrinsicHeight
    }
}
