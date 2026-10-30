package org.fossify.gallery.compression.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime

class ThermalLogRotationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val header = "horario,evento,status_termico,bateria_c,carregando,tela_interativa,item_id,motivo"
    private val now = OffsetDateTime.parse("2026-10-30T12:00:00-03:00")

    private fun line(at: OffsetDateTime, event: String = "SAMPLE") = "$at,$event,0,36.0,0,0,1,"

    private fun log(vararg lines: String) = folder.newFile().apply { writeText((listOf(header) + lines).joinToString("\n", postfix = "\n")) }

    @Test
    fun `linhas de 2 dias continuam depois da rotacao`() {
        val lines = (0L until 48L).map { line(now.minusHours(it)) }.reversed()
        val file = log(*lines.toTypedArray())

        ThermalLog.rotate(file, now)

        assertEquals(listOf(header) + lines, file.readLines())
    }

    @Test
    fun `so saem as linhas com mais de 30 dias`() {
        val kept = listOf(line(now.minusDays(29)), line(now.minusDays(2)), line(now))
        val file = log(line(now.minusDays(40)), line(now.minusDays(31)), *kept.toTypedArray())

        ThermalLog.rotate(file, now)

        assertEquals(listOf(header) + kept, file.readLines())
    }

    @Test
    fun `linha com horario ilegivel nao e apagada`() {
        val kept = listOf("horario quebrado,SAMPLE,0,,0,0,,", line(now))
        val file = log(*kept.toTypedArray())

        ThermalLog.rotate(file, now)

        assertEquals(listOf(header) + kept, file.readLines())
    }

    @Test
    fun `migracao move o arquivo antigo inteiro`() {
        val lines = listOf(line(now.minusDays(1)), line(now))
        val legacy = log(*lines.toTypedArray())
        val target = File(folder.root, "novo.csv")

        ThermalLog.migrateLegacy(legacy, target)

        assertFalse(legacy.exists())
        assertEquals(listOf(header) + lines, target.readLines())
    }

    @Test
    fun `migracao com os dois arquivos preserva as linhas de ambos`() {
        val old = line(now.minusDays(1))
        val recent = line(now)
        val legacy = log(old)
        val target = log(recent)

        ThermalLog.migrateLegacy(legacy, target)

        assertFalse(legacy.exists())
        assertEquals(listOf(header, recent, old), target.readLines())
    }
}
