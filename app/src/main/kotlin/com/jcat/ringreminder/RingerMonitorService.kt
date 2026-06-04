package com.jcat.ringreminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.quicksettings.TileService
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

class RingerMonitorService : Service() {

    companion object {
        @Volatile var isRunning = false
        @Volatile var isPaused = false
        @Volatile var lastResult: EvaluationResult? = null
        @Volatile var instance: RingerMonitorService? = null

        const val ACTION_REFRESH = "com.jcat.ringreminder.ACTION_REFRESH"
        const val ACTION_AUTO_FIX = "com.jcat.ringreminder.ACTION_AUTO_FIX"
        const val ACTION_EXPAND_OVERLAY = "com.jcat.ringreminder.ACTION_EXPAND_OVERLAY"
        const val ACTION_PULSE_START = "com.jcat.ringreminder.ACTION_PULSE_START"
        const val ACTION_PULSE_STOP = "com.jcat.ringreminder.ACTION_PULSE_STOP"

        private const val POLL_INTERVAL_NORMAL = 30_000L
        private const val POLL_INTERVAL_BATTERY_SAVER = 120_000L
        private const val AUTOFIX_REQUEST_CODE = 101
    }

    private lateinit var prefs: PrefsHelper
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var overlayBadgeManager: OverlayBadgeManager

    private val evaluator = RingerStateEvaluator()
    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var suppressCheckRunnable: Runnable? = null
    private var receiverRegistered = false

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (prefs.isPro && prefs.pulseOnMutedAlert && isPhoneMuted()) {
                        overlayBadgeManager.startContinuousPulse()
                    }
                }
                TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_OFFHOOK -> {
                    overlayBadgeManager.stopPulse()
                }
            }
        }
    }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PULSE_START -> {
                    if (prefs.isPro && prefs.pulseOnMutedAlert && isPhoneMuted()) {
                        overlayBadgeManager.startContinuousPulse()
                    }
                }
                ACTION_PULSE_STOP -> overlayBadgeManager.stopPulse()
                else -> evaluateAndUpdate()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isPaused = false
        instance = this

        prefs = PrefsHelper(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationHelper = NotificationHelper(this)
        overlayBadgeManager = OverlayBadgeManager(this, ::applyFixes, ::scheduleAutoFix)

        notificationHelper.createChannel()

        val placeholder = notificationHelper.buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                placeholder,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, placeholder)
        }

        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(ACTION_PULSE_START)
            addAction(ACTION_PULSE_STOP)
        }
        ContextCompat.registerReceiver(this, ringerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        registerPhoneStateListener()
        notificationHelper.cancelPausedNotification()
        startPolling()
        evaluateAndUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationHelper.ACTION_FIX_NOW -> applyFixes()
            ACTION_EXPAND_OVERLAY -> overlayBadgeManager.expandCard()
            ACTION_REFRESH -> evaluateAndUpdate()
            ACTION_AUTO_FIX -> {
                applyFixes()
                prefs.autoFixScheduledMs = 0L
                notificationHelper.cancelAutoFixNotification()
            }
            NotificationHelper.ACTION_CANCEL_AUTO_FIX -> cancelAutoFix()
            NotificationHelper.ACTION_PAUSE -> {
                isPaused = true
                notificationManager.notify(
                    NotificationHelper.PAUSED_NOTIFICATION_ID,
                    notificationHelper.buildPausedNotification()
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        if (receiverRegistered) {
            unregisterReceiver(ringerReceiver)
            receiverRegistered = false
        }
        unregisterPhoneStateListener()
        stopPolling()
        stopSuppressCheck()
        overlayBadgeManager.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startPolling() {
        val interval = pollInterval()
        pollingRunnable = object : Runnable {
            override fun run() {
                evaluateAndUpdate()
                handler.postDelayed(this, pollInterval())
            }
        }
        handler.postDelayed(pollingRunnable!!, interval)
    }

    private fun stopPolling() {
        pollingRunnable?.let { handler.removeCallbacks(it) }
        pollingRunnable = null
    }

    private fun updateSuppressCheck() {
        if (prefs.isPro && prefs.suppressAppsEnabled) {
            if (suppressCheckRunnable == null) startSuppressCheck()
        } else {
            stopSuppressCheck()
        }
    }

    private fun startSuppressCheck() {
        suppressCheckRunnable = object : Runnable {
            override fun run() {
                val result = lastResult
                if (result != null && result.isAlertActive && !isSnoozed(result)) {
                    if (isSuppressedByForegroundApp()) {
                        overlayBadgeManager.hide()
                        notificationHelper.cancelAlertNotification()
                    } else {
                        overlayBadgeManager.show(result)
                        notificationManager.notify(
                            NotificationHelper.ALERT_NOTIFICATION_ID,
                            notificationHelper.buildAlertNotification(result, prefs.isPro && prefs.lockScreenAlert)
                        )
                    }
                }
                handler.postDelayed(this, 2000L)
            }
        }
        handler.post(suppressCheckRunnable!!)
    }

    private fun stopSuppressCheck() {
        suppressCheckRunnable?.let { handler.removeCallbacks(it) }
        suppressCheckRunnable = null
    }

    private fun pollInterval(): Long {
        if (prefs.isPro && prefs.batterySaverEnabled && isBatteryLow()) {
            return POLL_INTERVAL_BATTERY_SAVER
        }
        return POLL_INTERVAL_NORMAL
    }

    private fun isBatteryLow(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1..20
    }

    private fun isPhoneMuted(): Boolean {
        val mode = audioManager.ringerMode
        return mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE
    }

    private fun getCurrentRingerState(): RingerState {
        return RingerState(
            ringerMode = audioManager.ringerMode,
            ringerVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING),
            maxRingerVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),
            interruptionFilter = notificationManager.currentInterruptionFilter
        )
    }

    fun evaluateAndUpdate() {
        // Check recurring auto-unmute
        if (prefs.isPro && prefs.autoFixEnabled && prefs.autoFixRecurringHour >= 0) {
            checkRecurringAutoFix()
        }

        val state = getCurrentRingerState()
        val result = if (isWithinSchedule()) {
            evaluator.evaluate(
                state,
                prefs.triggerSilent,
                prefs.triggerVibrate,
                prefs.triggerDnd,
                prefs.triggerLowVolume,
                prefs.thresholdVolumePercent
            )
        } else {
            EvaluationResult(emptyList(), null)
        }

        notificationManager.notify(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildServiceNotification()
        )

        val now = System.currentTimeMillis()

        if (result.isAlertActive) {
            if (prefs.alertFirstSeenMs == 0L) prefs.alertFirstSeenMs = now

            var snoozed = isSnoozed(result)
            if (snoozed && prefs.nudgeEnabled && prefs.alertFirstSeenMs > 0L) {
                if (now - prefs.alertFirstSeenMs >= prefs.nudgeIntervalMinutes * 60_000L) {
                    clearSnooze()
                    snoozed = false
                }
            }

            if (snoozed || isSuppressedByForegroundApp()) {
                overlayBadgeManager.hide()
                notificationHelper.cancelAlertNotification()
            } else {
                notificationManager.notify(
                    NotificationHelper.ALERT_NOTIFICATION_ID,
                    notificationHelper.buildAlertNotification(result, prefs.isPro && prefs.lockScreenAlert)
                )
                overlayBadgeManager.show(result)
            }
        } else {
            prefs.alertFirstSeenMs = 0L
            clearSnooze()
            overlayBadgeManager.hide()
            notificationHelper.cancelAlertNotification()
        }

        lastResult = result

        updateSuppressCheck()
        TileService.requestListeningState(this, ComponentName(this, QuickSettingsTile::class.java))
        ReminderWidget.updateAll(this, result)
    }

    private fun isSuppressedByForegroundApp(): Boolean {
        if (!prefs.isPro || !prefs.suppressAppsEnabled) return false
        val suppressed = prefs.suppressedApps
        if (suppressed.isEmpty()) return false
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000L, now)
            val event = android.app.usage.UsageEvents.Event()
            var foreground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    foreground = event.packageName
                }
            }
            suppressed.contains(foreground)
        } catch (_: Exception) {
            false
        }
    }

    private fun checkRecurringAutoFix() {
        val cal = java.util.Calendar.getInstance()
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMin = cal.get(java.util.Calendar.MINUTE)
        val nowTotalMin = currentHour * 60 + currentMin
        val targetTotalMin = prefs.autoFixRecurringHour * 60 + prefs.autoFixRecurringMinute
        // Fire within a 2-minute window to survive poll drift; track date so it only fires once per day
        if (kotlin.math.abs(nowTotalMin - targetTotalMin) <= 1) {
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date())
            if (prefs.autoFixLastFiredDate != today) {
                prefs.autoFixLastFiredDate = today
                applyFixes()
            }
        }
    }

    fun scheduleAutoFix(durationMs: Long) {
        val triggerMs = System.currentTimeMillis() + durationMs
        prefs.autoFixScheduledMs = triggerMs

        val pendingIntent = PendingIntent.getService(
            this,
            AUTOFIX_REQUEST_CODE,
            Intent(this, RingerMonitorService::class.java).apply { action = ACTION_AUTO_FIX },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        } catch (_: SecurityException) {
            // SCHEDULE_EXACT_ALARM not granted — fall back to inexact alarm
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        }

        notificationManager.notify(
            NotificationHelper.AUTOFIX_NOTIFICATION_ID,
            notificationHelper.buildAutoFixScheduledNotification(triggerMs)
        )
        evaluateAndUpdate()
    }

    private fun cancelAutoFix() {
        prefs.autoFixScheduledMs = 0L
        val pendingIntent = PendingIntent.getService(
            this,
            AUTOFIX_REQUEST_CODE,
            Intent(this, RingerMonitorService::class.java).apply { action = ACTION_AUTO_FIX },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
        notificationHelper.cancelAutoFixNotification()
        evaluateAndUpdate()
    }

    private fun isSnoozed(result: EvaluationResult): Boolean {
        val snoozeUntil = prefs.snoozeUntilMs
        if (snoozeUntil == 0L) return false
        if (snoozeUntil == Long.MAX_VALUE) {
            return result.primaryCondition?.name == prefs.snoozedCondition
        }
        return System.currentTimeMillis() < snoozeUntil
    }

    private fun clearSnooze() {
        prefs.snoozeUntilMs = 0L
        prefs.snoozedCondition = ""
    }

    private fun isWithinSchedule(): Boolean {
        if (!prefs.isPro || !prefs.scheduleEnabled) return true
        val cal = java.util.Calendar.getInstance()
        val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val startMinutes = prefs.scheduleStartHour * 60 + prefs.scheduleStartMinute
        val endMinutes = prefs.scheduleEndHour * 60 + prefs.scheduleEndMinute
        return if (startMinutes <= endMinutes) {
            nowMinutes in startMinutes..endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes <= endMinutes
        }
    }

    fun applyFixes() {
        if (prefs.hapticOnFix) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Exception) {}
        }

        if (prefs.fixUnmute) {
            try { audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: SecurityException) {}
        }
        if (prefs.fixRestoreVolume) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val target = (maxVolume * prefs.restoreVolumePercent) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
        }
        if (prefs.fixDisableDnd && notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        evaluateAndUpdate()
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneStateListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {}
    }
}
