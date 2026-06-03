package com.jcat.ringreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ring_reminder_service"
        const val CHANNEL_ALERTS_ID = "ring_reminder_alerts"
        const val CHANNEL_AUTOFIX_ID = "ring_reminder_autofix"
        const val NOTIFICATION_ID = 1
        const val PAUSED_NOTIFICATION_ID = 2
        const val ALERT_NOTIFICATION_ID = 3
        const val AUTOFIX_NOTIFICATION_ID = 4
        const val ACTION_FIX_NOW = "com.jcat.ringreminder.ACTION_FIX_NOW"
        const val ACTION_PAUSE = "com.jcat.ringreminder.ACTION_PAUSE"
        const val ACTION_RESUME = "com.jcat.ringreminder.ACTION_RESUME"
        const val ACTION_CANCEL_AUTO_FIX = "com.jcat.ringreminder.ACTION_CANCEL_AUTO_FIX"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS_ID,
            context.getString(R.string.notification_alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_alert_channel_desc)
            setSound(null, null)
            setShowBadge(true)
        }
        val autoFixChannel = NotificationChannel(
            CHANNEL_AUTOFIX_ID,
            context.getString(R.string.notif_autofix_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(alertChannel)
        notificationManager.createNotificationChannel(autoFixChannel)
    }

    fun buildServiceNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            context, 2,
            Intent(context, RingerMonitorService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentTitle(context.getString(R.string.notif_title_active))
            .setContentText(context.getString(R.string.notif_body_ok))
            .addAction(0, context.getString(R.string.action_pause), pauseIntent)
            .build()
    }

    fun buildAlertNotification(result: EvaluationResult, lockScreenVisible: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fixPendingIntent = PendingIntent.getService(
            context, 1,
            Intent(context, RingerMonitorService::class.java).apply { action = ACTION_FIX_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val (title, color) = when (result.primaryCondition) {
            AlertCondition.SILENT ->
                context.getString(R.string.notif_title_muted) to 0xFFEF5350.toInt()
            AlertCondition.DND ->
                context.getString(R.string.notif_title_dnd) to 0xFF8E24AA.toInt()
            AlertCondition.VIBRATE ->
                context.getString(R.string.notif_title_vibrate) to 0xFF546E7A.toInt()
            AlertCondition.LOW_VOLUME ->
                context.getString(R.string.notif_title_low_volume) to 0xFFEF6C00.toInt()
            null ->
                context.getString(R.string.notif_title_active) to 0xFF43A047.toInt()
        }
        val fixLabel = when (result.primaryCondition) {
            AlertCondition.SILENT -> context.getString(R.string.action_unmute)
            AlertCondition.DND -> context.getString(R.string.action_dismiss_dnd)
            AlertCondition.VIBRATE -> context.getString(R.string.action_enable_ringer)
            AlertCondition.LOW_VOLUME -> context.getString(R.string.action_raise_volume)
            null -> context.getString(R.string.action_fix_now)
        }
        val visibility = if (lockScreenVisible) NotificationCompat.VISIBILITY_PUBLIC
                         else NotificationCompat.VISIBILITY_PRIVATE
        return NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_notification_muted)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_body_fix))
            .setColor(color)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(visibility)
            .addAction(R.drawable.ic_fix, fixLabel, fixPendingIntent)
            .build()
    }

    fun buildPausedNotification(): Notification {
        val resumePendingIntent = PendingIntent.getForegroundService(
            context, 3,
            Intent(context, RingerMonitorService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title_paused))
            .setContentText(context.getString(R.string.notif_body_paused))
            .setContentIntent(resumePendingIntent)
            .addAction(0, context.getString(R.string.action_resume), resumePendingIntent)
            .setAutoCancel(false)
            .build()
    }

    fun buildAutoFixScheduledNotification(triggerMs: Long): Notification {
        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(triggerMs))
        val cancelIntent = PendingIntent.getService(
            context, 5,
            Intent(context, RingerMonitorService::class.java).apply {
                action = ACTION_CANCEL_AUTO_FIX
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_AUTOFIX_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_autofix_title))
            .setContentText(context.getString(R.string.autofix_scheduled, timeStr))
            .setOngoing(true)
            .addAction(0, context.getString(R.string.autofix_cancel), cancelIntent)
            .build()
    }

    fun cancelAlertNotification() {
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
    }

    fun cancelPausedNotification() {
        notificationManager.cancel(PAUSED_NOTIFICATION_ID)
    }

    fun cancelAutoFixNotification() {
        notificationManager.cancel(AUTOFIX_NOTIFICATION_ID)
    }
}
