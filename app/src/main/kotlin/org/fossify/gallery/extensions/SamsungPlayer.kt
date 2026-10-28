package org.fossify.gallery.extensions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.getUriMimeType
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig

const val SAMSUNG_VIDEO_PLAYER_PACKAGE = "com.samsung.android.video"

/** Opens the video in the Samsung player; calls [onFallback] on the UI thread if that is not possible. */
fun Activity.openInSamsungPlayer(path: String, onFallback: () -> Unit) {
    ensureBackgroundThread {
        val uri = try {
            getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
        } catch (e: Exception) {
            null
        }

        runOnUiThread {
            if (uri == null) {
                onFallback()
                return@runOnUiThread
            }

            try {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getUriMimeType(path, uri))
                    setPackage(SAMSUNG_VIDEO_PLAYER_PACKAGE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(this)
                }
            } catch (e: Exception) {
                // ActivityNotFoundException (package missing) or SecurityException
                onFallback()
            }
        }
    }
}
