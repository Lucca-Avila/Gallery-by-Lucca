package org.fossify.gallery.compression.ui

import android.content.Context

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

    companion object {
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
