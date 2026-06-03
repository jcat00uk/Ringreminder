package com.jcat.ringreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ring_reminder_service"
        const val NOTIFICATION_ID = 1
        const val PAUSED_NOTIFICATION_ID = 2
        const val ACTION_FIX_NOW = "com.jcat.ringreminder.ACTION_FIX_NOW"
        const val ACTION_PAUSE = "com.jcat.ringreminder.ACTION_PAUSE"
        const val ACTION_RESUME = "com.jcat.ringreminder.ACTION_RESUME"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildServiceNotification(result: EvaluationResult): Notification {
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.action_pause), pauseIntent)

        if (result.isAlertActive) {
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

            val fixPendingIntent = PendingIntent.getService(
                context, 1,
                Intent(context, RingerMonitorService::class.java).apply { action = ACTION_FIX_NOW },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val fixLabel = when (result.primaryCondition) {
                AlertCondition.SILENT -> context.getString(R.string.action_unmute)
                AlertCondition.DND -> context.getString(R.string.action_dismiss_dnd)
                AlertCondition.VIBRATE -> context.getString(R.string.action_enable_ringer)
                AlertCondition.LOW_VOLUME -> context.getString(R.string.action_raise_volume)
                null -> context.getString(R.string.action_fix_now)
            }

            builder
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notif_body_fix))
                .setColor(color)
                .addAction(R.drawable.ic_fix, fixLabel, fixPendingIntent)
        } else {
            builder
                .setContentTitle(context.getString(R.string.notif_title_active))
                .setContentText(context.getString(R.string.notif_body_ok))
        }

        return builder.build()
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

    fun updateNotification(result: EvaluationResult) {
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(result))
    }

    fun cancelPausedNotification() {
        notificationManager.cancel(PAUSED_NOTIFICATION_ID)
    }
}
