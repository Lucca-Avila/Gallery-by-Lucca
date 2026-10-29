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

    companion object {
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
