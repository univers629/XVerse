package com.xverse.app.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xverse.app.MainActivity
import com.xverse.app.R
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.core.util.Constants

/**
 * 下载通知：前台进度 + 完成/失败。
 * minSdk 31，运行期请求 POST_NOTIFICATIONS 即可（targetSdk 37）。
 */
object DownloadNotifier {

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(Constants.CHANNEL_DOWNLOAD)
        if (existing == null) {
            val ch = NotificationChannel(
                Constants.CHANNEL_DOWNLOAD,
                com.xverse.app.core.util.LocaleUtils.getString(context, R.string.download_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = com.xverse.app.core.util.LocaleUtils.getString(context, R.string.download_notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    /** 前台服务进度通知 */
    fun foreground(context: Context, id: Int, task: DownloadTask): Notification =
        base(context, id, task)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, task.progress, task.progress <= 0)
            .setContentTitle(com.xverse.app.core.util.LocaleUtils.getString(context, R.string.download_notif_running_title, task.fileName))
            .setContentText("${task.progress}%${task.resolution.ifBlank { "" }.let { if (it.isNotEmpty()) " · $it" else "" }}")
            .build()

    /** 完成 / 失败 通知（非 ongoing） */
    fun finish(context: Context, id: Int, task: DownloadTask) {
        val builder = base(context, id, task)
            .setOngoing(false)
            .setAutoCancel(true)
        if (task.status == DownloadStatus.DONE) {
            builder.setContentTitle(com.xverse.app.core.util.LocaleUtils.getString(context, R.string.download_notif_done_title))
                .setContentText(task.fileName)
                .setContentIntent(openApp(context))
        } else {
            builder.setContentTitle(com.xverse.app.core.util.LocaleUtils.getString(context, R.string.download_notif_failed_title))
                .setContentText(task.fileName + (task.error.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
        }
        notify(context, id, builder.build())
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun base(context: Context, id: Int, task: DownloadTask): NotificationCompat.Builder =
        NotificationCompat.Builder(context, Constants.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentIntent(openApp(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // 未授权 POST_NOTIFICATIONS：静默跳过（进度仍需 POST_NOTIFICATIONS）
        }
    }
}
