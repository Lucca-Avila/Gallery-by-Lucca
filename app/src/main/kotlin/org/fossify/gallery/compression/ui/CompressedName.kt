package org.fossify.gallery.compression.ui

/** Marca "_comp_q<N>" que a compressão põe no nome do arquivo (N = bpp do alvo × 1000). */
object CompressedName {
    private const val MARK = "_comp_q"
    private val QUALITY = Regex("_comp_q(\\d+)")

    /** N da marca no nome, senão null. */
    fun quality(name: String): Int? = QUALITY.findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /** Começo do trecho final que deve sobrar ao encurtar o nome: da marca até o fim, ou os últimos caracteres. */
    fun tailStart(name: String): Int {
        val mark = name.lastIndexOf(MARK)
        return if (mark >= 0) mark else (name.length - FALLBACK_TAIL).coerceAtLeast(0)
    }

    private const val FALLBACK_TAIL = 10
}
