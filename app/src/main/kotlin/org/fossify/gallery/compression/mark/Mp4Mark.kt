package org.fossify.gallery.compression.mark

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Camada B da marca de "já comprimido": um box `uuid` anexado ao fim do MP4 que o app gera, com o texto "q=<N>;v=1".
 * Players e o MediaStore ignoram boxes que não conhecem. Ler percorre só os cabeçalhos dos boxes do nível de cima (poucas
 * leituras de 8–16 bytes), sem extrator nem decoder. Só é escrito em resultados do próprio app.
 */
object Mp4Mark {
    /** "luccaglr" + 00 00 + "comp01". */
    private val UUID = byteArrayOf(
        0x6c, 0x75, 0x63, 0x63, 0x61, 0x67, 0x6c, 0x72, 0x00, 0x00, 0x63, 0x6f, 0x6d, 0x70, 0x30, 0x31
    )
    private const val UUID_TYPE = 0x75756964 // "uuid"
    private const val VERSION = 1
    private const val MAX_PAYLOAD = 64

    private class Scan(val payload: String?, val clean: Boolean)

    /** Anexa a marca. false se o arquivo não terminar num box completo (o que fosse anexado ficaria dentro dele) ou na falha. */
    fun append(file: File, quality: Int): Boolean {
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                if (!scan(raf).clean) return false
                val payload = "q=$quality;v=$VERSION".toByteArray(Charsets.US_ASCII)
                raf.seek(raf.length())
                raf.writeInt(8 + UUID.size + payload.size)
                raf.writeInt(UUID_TYPE)
                raf.write(UUID)
                raf.write(payload)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /** N da marca, ou null se não houver marca (ou o arquivo não puder ser lido). */
    fun read(file: File): Int? = try {
        RandomAccessFile(file, "r").use { raf -> scan(raf).payload?.let(::qualityOf) }
    } catch (e: IOException) {
        null
    }

    private fun qualityOf(payload: String): Int? =
        payload.split(';').firstOrNull { it.startsWith("q=") }?.substring(2)?.toIntOrNull()?.takeIf { it > 0 }

    /** Percorre os boxes do nível de cima; [Scan.clean] = o último termina exatamente no fim do arquivo. Vale a última marca. */
    private fun scan(raf: RandomAccessFile): Scan {
        val end = raf.length()
        var pos = 0L
        var found: String? = null
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = raf.readInt()
            var header = 8
            if (size == 1L) {
                if (pos + 16 > end) return Scan(found, false)
                size = raf.readLong()
                header = 16
            }
            // size 0 = "vai até o fim do arquivo"
            if (size < header || pos + size > end) return Scan(found, false)
            val payloadSize = size - header - UUID.size
            if (type == UUID_TYPE && payloadSize in 1..MAX_PAYLOAD) {
                val id = ByteArray(UUID.size)
                raf.readFully(id)
                if (id.contentEquals(UUID)) {
                    val payload = ByteArray(payloadSize.toInt())
                    raf.readFully(payload)
                    found = String(payload, Charsets.US_ASCII)
                }
            }
            pos += size
        }
        return Scan(found, pos == end)
    }
}
