package com.jcat.ringreminder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class ReminderWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val result = RingerMonitorService.lastResult
        ids.forEach { id -> manager.updateAppWidget(id, buildSmallViews(context, result)) }
    }

    companion object {

        fun updateAll(context: Context, result: EvaluationResult?) {
            val manager = AppWidgetManager.getInstance(context)

            manager.getAppWidgetIds(ComponentName(context, ReminderWidget::class.java))
                .forEach { id -> manager.updateAppWidget(id, buildSmallViews(context, result)) }

            manager.getAppWidgetIds(ComponentName(context, ReminderWidgetLarge::class.java))
                .forEach { id -> manager.updateAppWidget(id, buildLargeViews(context, result)) }
        }

        fun buildSmallViews(context: Context, result: EvaluationResult?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_small)
            val theme = PrefsHelper(context).overlayTheme
            val (bgColor, _) = widgetState(context, result, theme)
            views.setInt(R.id.widget_small_root, "setBackgroundColor", bgColor)
            views.setOnClickPendingIntent(R.id.widget_small_root, mainIntent(context))
            val iconRes = if (result?.isAlertActive == true) R.drawable.ic_notification_muted
                          else R.drawable.ic_notification
            views.setImageViewResource(R.id.widget_small_icon, iconRes)
            return views
        }

        fun buildLargeViews(context: Context, result: EvaluationResult?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_large)
            val theme = PrefsHelper(context).overlayTheme
            val (bgColor, statusText) = widgetState(context, result, theme)
            views.setInt(R.id.widget_large_root, "setBackgroundColor", bgColor)
            views.setTextViewText(R.id.widget_large_status, statusText)
            views.setOnClickPendingIntent(R.id.widget_large_root, mainIntent(context))
            val iconRes = if (result?.isAlertActive == true) R.drawable.ic_notification_muted
                          else R.drawable.ic_notification
            views.setImageViewResource(R.id.widget_large_icon, iconRes)

            val isRunning = RingerMonitorService.isRunning
            val alertActive = result?.isAlertActive == true
            val isPaused = RingerMonitorService.isPaused

            when {
                isRunning && alertActive -> {
                    views.setViewVisibility(R.id.widget_large_action_btn, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_large_action_btn, "Unmute")
                    views.setOnClickPendingIntent(R.id.widget_large_action_btn, fixIntent(context))
                }
                !isRunning && isPaused -> {
                    views.setViewVisibility(R.id.widget_large_action_btn, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_large_action_btn, "Resume")
                    views.setOnClickPendingIntent(R.id.widget_large_action_btn, startIntent(context))
                }
                !isRunning -> {
                    views.setViewVisibility(R.id.widget_large_action_btn, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_large_action_btn, "Start")
                    views.setOnClickPendingIntent(R.id.widget_large_action_btn, startIntent(context))
                }
                else -> {
                    views.setViewVisibility(R.id.widget_large_action_btn, android.view.View.GONE)
                }
            }

            return views
        }

        private fun widgetState(context: Context, result: EvaluationResult?, theme: String): Pair<Int, String> {
            val isRunning = RingerMonitorService.isRunning
            val isPaused = RingerMonitorService.isPaused
            val isPro = PrefsHelper(context).isPro
            // Only use theme colours when Pro is active
            val helper = if (isPro) OverlayBadgeManager(context, {}, {}) else null
            return when {
                isRunning && result?.isAlertActive == true -> {
                    val color = helper?.conditionColor(result.primaryCondition, theme)
                        ?: when (result.primaryCondition) {
                            AlertCondition.SILENT -> 0xFFE53935.toInt()
                            AlertCondition.DND -> 0xFF8E24AA.toInt()
                            AlertCondition.VIBRATE -> 0xFF546E7A.toInt()
                            AlertCondition.LOW_VOLUME -> 0xFFEF6C00.toInt()
                            null -> 0xFF43A047.toInt()
                        }
                    val label = when (result.primaryCondition) {
                        AlertCondition.SILENT -> "Muted"
                        AlertCondition.DND -> "DND On"
                        AlertCondition.VIBRATE -> "Vibrate"
                        AlertCondition.LOW_VOLUME -> "Low Volume"
                        null -> "Alert"
                    }
                    color to label
                }
                isRunning -> {
                    val color = helper?.conditionColor(null, theme) ?: 0xFF43A047.toInt()
                    color to "Ringer OK"
                }
                isPaused -> 0xFF757575.toInt() to "Paused"
                else -> 0xFF757575.toInt() to "Inactive"
            }
        }

        private fun mainIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun fixIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context, 1,
                Intent(context, RingerMonitorService::class.java).apply {
                    action = NotificationHelper.ACTION_FIX_NOW
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun startIntent(context: Context): PendingIntent {
            val intent = Intent(context, RingerMonitorService::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context, 2, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context, 2, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }
    }
}

class ReminderWidgetLarge : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val result = RingerMonitorService.lastResult
        ids.forEach { id -> manager.updateAppWidget(id, ReminderWidget.buildLargeViews(context, result)) }
    }
}
