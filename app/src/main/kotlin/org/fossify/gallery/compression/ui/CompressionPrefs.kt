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

    companion object {
        const val DEST_SAME = 0
        const val DEST_COMPRIMIDOS = 1
        const val DEST_CUSTOM = 2
        private const val KEY_MODE = "dest_mode"
        private const val KEY_PATH = "dest_custom_path"
    }
}
