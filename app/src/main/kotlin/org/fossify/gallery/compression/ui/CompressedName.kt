package org.fossify.gallery.compression.ui

/** Marca "_comp_q<N>" que a compressão põe no nome do arquivo (N = bpp do alvo × 1000). */
object CompressedName {
    private const val MARK = "_comp_q"
    private val QUALITY = Regex("_comp_q(\\d+)")

    /** "q40" se o nome tiver a marca, senão null. */
    fun badge(name: String): String? = QUALITY.findAll(name).lastOrNull()?.let { "q${it.groupValues[1]}" }

    /** Começo do trecho final que deve sobrar ao encurtar o nome: da marca até o fim, ou os últimos caracteres. */
    fun tailStart(name: String): Int {
        val mark = name.lastIndexOf(MARK)
        return if (mark >= 0) mark else (name.length - FALLBACK_TAIL).coerceAtLeast(0)
    }

    private const val FALLBACK_TAIL = 10
}
