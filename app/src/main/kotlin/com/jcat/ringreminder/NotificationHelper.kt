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
        const val ACTION_FIX_NOW = "com.jcat.ringreminder.ACTION_FIX_NOW"
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

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

            val fixIntent = Intent(context, RingerMonitorService::class.java).apply {
                action = ACTION_FIX_NOW
            }
            val fixPendingIntent = PendingIntent.getService(
                context, 1, fixIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notif_body_fix))
                .setColor(color)
                .addAction(R.drawable.ic_fix, context.getString(R.string.action_fix_now), fixPendingIntent)
        } else {
            builder
                .setContentTitle(context.getString(R.string.notif_title_active))
                .setContentText(context.getString(R.string.notif_body_ok))
        }

        return builder.build()
    }

    fun updateNotification(result: EvaluationResult) {
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification(result))
    }
}
