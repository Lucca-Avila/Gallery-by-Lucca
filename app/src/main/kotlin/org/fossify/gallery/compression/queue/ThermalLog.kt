package org.fossify.gallery.compression.queue

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Registro persistente de temperatura em `files/thermal_log.csv`, para analisar a pausa por temperatura depois de dias de uso.
 * Só observa: não decide nada nem muda a fila. Toda leitura e escrita roda numa thread própria; qualquer falha é ignorada
 * (vai só para o logcat). Linhas com mais de [MAX_AGE_DAYS] dias são descartadas e o arquivo fica perto de [MAX_BYTES].
 */
object ThermalLog {
    const val START = "START"
    const val END = "END"
    const val PAUSE = "PAUSA"
    const val RESUME = "RETOMADA"
    const val EMERGENCY = "EMERGENCIA"
    const val GAVE_UP = "DESISTENCIA"
    const val STATUS = "STATUS"
    const val PREF = "PREF"
    const val MANUAL_PAUSE = "PAUSA_MANUAL"
    private const val SAMPLE = "SAMPLE"

    private const val TAG = "ThermalLog"
    private const val FILE_NAME = "thermal_log.csv"
    private const val HEADER = "horario,evento,status_termico,bateria_c,carregando,tela_interativa,item_id,motivo"
    private const val SAMPLE_SECONDS = 60L
    private const val MAX_AGE_DAYS = 30L
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** Ao passar de [MAX_BYTES], corta as linhas mais antigas até aqui (folga para não reescrever a cada linha). */
    private const val TRIM_TO_BYTES = MAX_BYTES * 3 / 4
    private const val ROTATE_EVERY_WRITES = 200

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "thermal-log").apply { isDaemon = true } }
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    // Acessados só na thread do executor.
    private var writes = 0
    private var rotatedOnce = false
    private var sampling: ScheduledFuture<*>? = null
    private var statusListener: Any? = null
    private var lastStatus = -1

    /** Grava um evento. [itemId] é lido por quem chama (na thread principal); o resto é lido na thread do registro. */
    fun event(context: Context, event: String, itemId: Long?, note: String = "") {
        val app = context.applicationContext
        val at = OffsetDateTime.now()
        submit { append(app, at, event, readStatus(app), itemId, note) }
    }

    /**
     * Fila rodando ou em pausa por temperatura: amostra a cada [SAMPLE_SECONDS] e grava cada mudança de status térmico.
     * [itemId] é chamado na thread do registro, então deve ler um campo volátil.
     */
    fun startSession(context: Context, itemId: () -> Long?) {
        val app = context.applicationContext
        submit {
            if (sampling != null) return@submit
            lastStatus = readStatus(app)
            sampling = executor.scheduleWithFixedDelay(
                { safely { append(app, OffsetDateTime.now(), SAMPLE, readStatus(app), itemId(), "") } },
                SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    safely {
                        if (status != lastStatus) append(app, OffsetDateTime.now(), STATUS, status, itemId(), "antes $lastStatus")
                        lastStatus = status
                    }
                }
                power(app).addThermalStatusListener(executor, listener)
                statusListener = listener
            }
        }
    }

    fun stopSession(context: Context) {
        val app = context.applicationContext
        submit {
            sampling?.cancel(false)
            sampling = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (statusListener as? PowerManager.OnThermalStatusChangedListener)?.let { power(app).removeThermalStatusListener(it) }
            }
            statusListener = null
        }
    }

    private fun submit(task: () -> Unit) {
        runCatching { executor.execute { safely(task) } }
    }

    private fun safely(task: () -> Unit) {
        try {
            task()
        } catch (e: Throwable) {
            Log.w(TAG, "falha no registro de temperatura", e)
        }
    }

    private fun power(context: Context) = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private fun readStatus(context: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power(context).currentThermalStatus else PowerManager.THERMAL_STATUS_NONE

    private fun append(context: Context, at: OffsetDateTime, event: String, status: Int, itemId: Long?, note: String) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val line = listOf(
            at.format(formatter),
            event,
            status.toString(),
            tenths?.let { String.format(Locale.US, "%.1f", it / 10.0) }.orEmpty(),
            if (charging) "1" else "0",
            if (power(context).isInteractive) "1" else "0",
            itemId?.toString().orEmpty(),
            csv(note),
        ).joinToString(",")

        val file = File(context.filesDir, FILE_NAME)
        if (!rotatedOnce || ++writes >= ROTATE_EVERY_WRITES || file.length() > MAX_BYTES) {
            rotatedOnce = true
            writes = 0
            rotate(file)
        }
        val isNew = !file.exists() || file.length() == 0L
        file.appendText(if (isNew) "$HEADER\n$line\n" else "$line\n")
    }

    private fun csv(value: String) =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\"" else value

    /** Tira as linhas com mais de [MAX_AGE_DAYS] dias e, se ainda passar de [MAX_BYTES], as mais antigas. Reescreve por arquivo temporário. */
    private fun rotate(file: File) {
        if (!file.exists()) return
        val cutoff = OffsetDateTime.now().minusDays(MAX_AGE_DAYS)
        val lines = file.readLines().drop(1).filter { line ->
            val at = runCatching { OffsetDateTime.parse(line.substringBefore(','), formatter) }.getOrNull()
            at != null && at.isAfter(cutoff)
        }.toMutableList()
        var size = lines.sumOf { it.length + 1L }
        val trimmed = file.length() > MAX_BYTES
        if (trimmed) {
            while (lines.isNotEmpty() && size > TRIM_TO_BYTES) size -= lines.removeAt(0).length + 1L
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(buildString {
            append(HEADER).append('\n')
            lines.forEach { append(it).append('\n') }
        })
        if (!tmp.renameTo(file)) tmp.delete()
    }
}
