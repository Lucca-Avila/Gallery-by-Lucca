package org.fossify.gallery.compression.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import org.fossify.commons.extensions.formatSize
import org.fossify.gallery.R

/** Canal de progresso (silencioso, do serviço) e canal separado para o resumo final. */
@UnstableApi
object QueueNotifications {
    const val PROGRESS_ID = 4101
    private const val SUMMARY_ID = 4102
    private const val CHANNEL_PROGRESS = "compression_queue"
    private const val CHANNEL_SUMMARY = "compression_queue_summary"

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels(context: Context) {
        manager(context).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.cmp_q_channel_progress), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.cmp_q_channel_summary), NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    private fun openQueue(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, QueueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context, requestCode, Intent(context, QueueService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** [percent] = null: gravando/verificando (barra indeterminada). [item] = null: preparando. */
    fun progress(context: Context, item: QueueItem?, index: Int, total: Int, percent: Int?): Notification {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_compress_vector)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openQueue(context))
        if (item == null) {
            return builder.setContentTitle(context.getString(R.string.cmp_q_preparing)).setProgress(0, 0, true).build()
        }
        return builder
            .setContentTitle(
                if (percent != null) context.getString(R.string.cmp_q_notif_title, index, total, percent)
                else context.getString(R.string.cmp_q_notif_title_saving, index, total)
            )
            .setContentText(item.name)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, context.getString(R.string.cmp_q_pause), serviceAction(context, QueueService.ACTION_PAUSE, 1))
            .addAction(0, context.getString(R.string.cmp_q_skip), serviceAction(context, QueueService.ACTION_SKIP, 2))
            .build()
    }

    fun update(context: Context, notification: Notification) = manager(context).notify(PROGRESS_ID, notification)

    fun summary(context: Context, stats: QueueRunner.Stats) {
        val text = context.getString(R.string.cmp_q_summary_text, stats.done, stats.failed, stats.discarded, stats.savedBytes.formatSize())
        postSummary(context, context.getString(R.string.cmp_q_summary_title), text)
    }

    fun timeLimit(context: Context) =
        postSummary(context, context.getString(R.string.cmp_q_time_limit_title), context.getString(R.string.cmp_q_time_limit_text))

    private fun postSummary(context: Context, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_compress_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openQueue(context))
            .build()
        runCatching { manager(context).notify(SUMMARY_ID, notification) }
    }
}
