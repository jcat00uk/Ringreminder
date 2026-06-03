package com.jcat.ringreminder

import android.app.Notification
import android.content.Intent
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class RingerNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (sbn.notification.category != Notification.CATEGORY_CALL) return

        val prefs = PrefsHelper(this)
        if (!prefs.isPro || !prefs.pulseOnMutedAlert) return

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val mode = audioManager.ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT && mode != AudioManager.RINGER_MODE_VIBRATE) return

        sendBroadcast(
            Intent(RingerMonitorService.ACTION_PULSE_START).apply { setPackage(packageName) }
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.notification.category != Notification.CATEGORY_CALL) return

        sendBroadcast(
            Intent(RingerMonitorService.ACTION_PULSE_STOP).apply { setPackage(packageName) }
        )
    }
}
