package com.jcat.ringreminder

import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class RingerMonitorService : Service() {

    companion object {
        @Volatile var isRunning = false
    }

    private lateinit var prefs: PrefsHelper
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var overlayBadgeManager: OverlayBadgeManager

    private val evaluator = RingerStateEvaluator()
    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var receiverRegistered = false

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            evaluateAndUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        prefs = PrefsHelper(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationHelper = NotificationHelper(this)
        overlayBadgeManager = OverlayBadgeManager(this) { applyFixes() }

        notificationHelper.createChannel()

        // Start foreground immediately to satisfy Android 8+ requirement
        val placeholder = notificationHelper.buildServiceNotification(EvaluationResult(emptyList(), null))
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
        }
        registerReceiver(ringerReceiver, filter)
        receiverRegistered = true

        notificationHelper.cancelPausedNotification()
        startPolling()
        evaluateAndUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationHelper.ACTION_FIX_NOW -> applyFixes()
            NotificationHelper.ACTION_PAUSE -> {
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
        if (receiverRegistered) {
            unregisterReceiver(ringerReceiver)
            receiverRegistered = false
        }
        stopPolling()
        overlayBadgeManager.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startPolling() {
        pollingRunnable = object : Runnable {
            override fun run() {
                evaluateAndUpdate()
                handler.postDelayed(this, 30_000L)
            }
        }
        handler.postDelayed(pollingRunnable!!, 30_000L)
    }

    private fun stopPolling() {
        pollingRunnable?.let { handler.removeCallbacks(it) }
        pollingRunnable = null
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
        val state = getCurrentRingerState()
        val result = evaluator.evaluate(
            state,
            prefs.triggerSilent,
            prefs.triggerVibrate,
            prefs.triggerDnd,
            prefs.triggerLowVolume,
            prefs.thresholdVolumePercent
        )
        notificationHelper.updateNotification(result)
        if (result.isAlertActive) {
            overlayBadgeManager.show(result)
        } else {
            overlayBadgeManager.hide()
        }
    }

    fun applyFixes() {
        if (prefs.fixUnmute) {
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: SecurityException) {}
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
}
