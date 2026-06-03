package com.jcat.ringreminder

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val result = RingerMonitorService.lastResult
        when {
            RingerMonitorService.isRunning && result?.isAlertActive == true -> {
                startService(
                    Intent(this, RingerMonitorService::class.java).apply {
                        action = NotificationHelper.ACTION_FIX_NOW
                    }
                )
            }
            !RingerMonitorService.isRunning -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(Intent(this, RingerMonitorService::class.java))
                } else {
                    startService(Intent(this, RingerMonitorService::class.java))
                }
            }
            else -> launchMain()
        }
        updateTile()
    }

    private fun launchMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val result = RingerMonitorService.lastResult
        when {
            !RingerMonitorService.isRunning -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Ring Reminder"
                tile.contentDescription = if (RingerMonitorService.isPaused) "Paused" else "Inactive"
            }
            result?.isAlertActive == true -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = when (result.primaryCondition) {
                    AlertCondition.SILENT -> "Muted"
                    AlertCondition.DND -> "Do Not Disturb"
                    AlertCondition.VIBRATE -> "Vibrate"
                    AlertCondition.LOW_VOLUME -> "Low Volume"
                    null -> "Alert"
                }
                tile.contentDescription = "Tap to unmute"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_notification_muted)
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Ring Reminder"
                tile.contentDescription = "Ringer OK"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
            }
        }
        tile.updateTile()
    }
}
