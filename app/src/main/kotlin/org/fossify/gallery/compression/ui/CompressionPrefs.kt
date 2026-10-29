package org.fossify.gallery.compression.ui

import android.content.Context
import org.fossify.gallery.compression.engine.CompressionPlanner

/** Última escolha de destino. SharedPreferences próprio para não mexer no Config do Fossify. */
class CompressionPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("compression", Context.MODE_PRIVATE)

    var destMode: Int
        get() = prefs.getInt(KEY_MODE, DEST_SAME)
        set(value) = prefs.edit().putInt(KEY_MODE, value).apply()

    var customPath: String?
        get() = prefs.getString(KEY_PATH, null)
        set(value) = prefs.edit().putString(KEY_PATH, value).apply()

    /** Só AFTER_KEEP ou AFTER_TRASH; "apagar de vez" nunca é lembrado. */
    var afterMode: Int
        get() = prefs.getInt(KEY_AFTER, AFTER_KEEP).takeIf { it == AFTER_TRASH } ?: AFTER_KEEP
        set(value) = prefs.edit().putInt(KEY_AFTER, if (value == AFTER_TRASH) AFTER_TRASH else AFTER_KEEP).apply()

    /**
     * Última regra geral da tela de lote: qualidade, resolução e destino; o "depois de comprimir" vem de [afterMode]
     * (nunca "apagar de vez").
     */
    var lastRule: CompressRule
        get() {
            val code = prefs.getInt(KEY_RULE_PRESET, CompressionPlanner.Preset.BALANCED.ordinal)
            val dest = destMode.takeIf { it != DEST_CUSTOM || customPath != null } ?: DEST_SAME
            return CompressRule(
                preset = if (code == RulePlanner.PRESET_CUSTOM) null else CompressionPlanner.Preset.entries.getOrElse(code) { CompressionPlanner.Preset.BALANCED },
                customPct = prefs.getInt(KEY_RULE_PCT, DEFAULT_CUSTOM_PCT),
                shortSide = prefs.getInt(KEY_RULE_SHORT_SIDE, 0).takeIf { it > 0 },
                destMode = dest,
                customPath = customPath,
                after = afterMode,
            )
        }
        set(value) {
            prefs.edit().putInt(KEY_RULE_PRESET, RulePlanner.presetCode(value.preset)).putInt(KEY_RULE_PCT, value.customPct)
                .putInt(KEY_RULE_SHORT_SIDE, value.shortSide ?: 0).apply()
            destMode = value.destMode
            if (value.destMode == DEST_CUSTOM) customPath = value.customPath
            if (value.after != AFTER_DELETE) afterMode = value.after
        }

    /** Ao adicionar à fila, começa a processar sem precisar tocar em "Iniciar". */
    var queueAutoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** A permissão de notificações só é pedida na primeira adição à fila. */
    var notificationAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    /** INTERRUPTED_*: por que a fila parou sem o usuário pedir (mostra o aviso "Retomar"). */
    var queueInterrupted: Int
        get() = prefs.getInt(KEY_INTERRUPTED, INTERRUPTED_NONE)
        set(value) = prefs.edit().putInt(KEY_INTERRUPTED, value).apply()

    /** Últimas velocidades medidas (pixels por segundo), mais antiga primeiro; sobrevivem ao "Limpar concluídos". */
    var speedSamples: List<Double>
        get() = prefs.getString(KEY_SPEEDS, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_SPEEDS, value.joinToString(",")).apply()

    /** Resumo acumulado da execução da fila (ver [LastRun]). */
    var lastRun: LastRun?
        get() = prefs.getLong(KEY_RUN_AT, 0L).takeIf { it > 0 }?.let { at ->
            LastRun(
                done = prefs.getInt(KEY_RUN_DONE, 0), failed = prefs.getInt(KEY_RUN_FAILED, 0),
                discarded = prefs.getInt(KEY_RUN_DISCARDED, 0), canceled = prefs.getInt(KEY_RUN_CANCELED, 0),
                savedBytes = prefs.getLong(KEY_RUN_SAVED, 0L), updatedAt = at, reason = prefs.getInt(KEY_RUN_REASON, LastRun.REASON_FINISHED),
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.putLong(KEY_RUN_AT, 0L)
            } else {
                editor.putInt(KEY_RUN_DONE, value.done).putInt(KEY_RUN_FAILED, value.failed).putInt(KEY_RUN_DISCARDED, value.discarded)
                    .putInt(KEY_RUN_CANCELED, value.canceled).putLong(KEY_RUN_SAVED, value.savedBytes)
                    .putLong(KEY_RUN_AT, value.updatedAt).putInt(KEY_RUN_REASON, value.reason)
            }
            editor.apply()
        }

    /**
     * Resumo da execução: acumula entre pausas e retomadas e recomeça quando a fila esvaziou ([REASON_FINISHED]).
     * [updatedAt] é o último evento (término, pausa ou item concluído).
     */
    data class LastRun(
        val done: Int = 0, val failed: Int = 0, val discarded: Int = 0, val canceled: Int = 0,
        val savedBytes: Long = 0, val updatedAt: Long = System.currentTimeMillis(), val reason: Int = REASON_RUNNING,
    ) {
        companion object {
            const val REASON_RUNNING = 0
            const val REASON_FINISHED = 1
            const val REASON_PAUSED = 2
            const val REASON_TIME_LIMIT = 3
        }
    }

    companion object {
        private const val KEY_RULE_PRESET = "batch_rule_preset"
        private const val KEY_RULE_PCT = "batch_rule_pct"
        private const val KEY_RULE_SHORT_SIDE = "batch_rule_short_side"
        private const val DEFAULT_CUSTOM_PCT = 50
        private const val KEY_SPEEDS = "queue_speed_samples"
        private const val KEY_RUN_AT = "queue_run_at"
        private const val KEY_RUN_DONE = "queue_run_done"
        private const val KEY_RUN_FAILED = "queue_run_failed"
        private const val KEY_RUN_DISCARDED = "queue_run_discarded"
        private const val KEY_RUN_CANCELED = "queue_run_canceled"
        private const val KEY_RUN_SAVED = "queue_run_saved"
        private const val KEY_RUN_REASON = "queue_run_reason"
        const val INTERRUPTED_NONE = 0
        const val INTERRUPTED_KILLED = 1
        const val INTERRUPTED_TIME_LIMIT = 2
        private const val KEY_AUTO_START = "queue_auto_start"
        private const val KEY_NOTIF_ASKED = "queue_notification_asked"
        private const val KEY_INTERRUPTED = "queue_interrupted"

        const val AFTER_KEEP = 0
        const val AFTER_TRASH = 1
        const val AFTER_DELETE = 2
        private const val KEY_AFTER = "after_mode"

        const val DEST_SAME = 0
        const val DEST_COMPRIMIDOS = 1
        const val DEST_CUSTOM = 2
        private const val KEY_MODE = "dest_mode"
        private const val KEY_PATH = "dest_custom_path"
    }
}
