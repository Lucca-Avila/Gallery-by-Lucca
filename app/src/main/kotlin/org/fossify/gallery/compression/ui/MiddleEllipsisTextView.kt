package org.fossify.gallery.compression.ui

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Nome de arquivo em até [MAX_LINES] linhas. Se não couber, corta no MEIO com reticências e preserva o final do nome
 * (a marca "_comp_q<N>" e a extensão), porque o ellipsize="middle" do Android só funciona com uma linha.
 * O texto completo é o que o código atribui em `text`; o encurtado só é exibido.
 */
class MiddleEllipsisTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    private var fullText: CharSequence = ""
    private var fittedWidth = -1
    private var settingInternally = false

    init {
        maxLines = MAX_LINES
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!settingInternally) {
            fullText = text ?: ""
            fittedWidth = -1
        }
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        if (available > 0 && available != fittedWidth && fullText.isNotEmpty()) {
            fittedWidth = available
            val fitted = fit(fullText.toString(), available)
            if (fitted != text.toString()) {
                settingInternally = true
                super.setText(fitted, BufferType.NORMAL)
                settingInternally = false
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun lines(text: String, width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build().lineCount

    private fun fit(name: String, width: Int): String {
        if (lines(name, width) <= MAX_LINES) return name
        val tailStart = CompressedName.tailStart(name)
        val tail = name.substring(tailStart)
        fun candidate(head: Int) = name.substring(0, head) + ELLIPSIS + tail
        // Maior começo que ainda cabe, com o final intacto.
        var low = 0
        var high = tailStart
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lines(candidate(mid), width) <= MAX_LINES) low = mid else high = mid - 1
        }
        return candidate(low)
    }

    private companion object {
        const val MAX_LINES = 2
        const val ELLIPSIS = "…"
    }
}
