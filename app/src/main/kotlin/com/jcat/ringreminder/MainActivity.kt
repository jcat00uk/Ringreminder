package com.jcat.ringreminder

import android.content.Intent
import android.media.AudioManager
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.jcat.ringreminder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsHelper(this)

        if (!prefs.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnRerunSetup.setOnClickListener {
            prefs.onboardingComplete = false
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
        binding.btnStartService.setOnClickListener {
            startRingerService()
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.onboardingComplete) return
        updateUI()
    }

    private fun updateUI() {
        val serviceRunning = RingerMonitorService.isRunning
        binding.serviceWarningBanner.visibility = if (serviceRunning) View.GONE else View.VISIBLE

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val state = RingerState(
            ringerMode = audioManager.ringerMode,
            ringerVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING),
            maxRingerVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),
            interruptionFilter = notificationManager.currentInterruptionFilter
        )

        val result = RingerStateEvaluator().evaluate(
            state,
            prefs.triggerSilent, prefs.triggerVibrate, prefs.triggerDnd, prefs.triggerLowVolume,
            prefs.thresholdVolumePercent
        )

        if (result.isAlertActive) {
            val issueText = result.activeConditions.joinToString(" · ") { cond ->
                when (cond) {
                    AlertCondition.SILENT -> "Phone is muted"
                    AlertCondition.DND -> "Do Not Disturb is on"
                    AlertCondition.VIBRATE -> "Vibrate only mode"
                    AlertCondition.LOW_VOLUME -> "Ringer volume is low"
                }
            }
            binding.statusText.text = issueText
            binding.statusCard.setCardBackgroundColor(getColor(R.color.status_alert))
        } else {
            binding.statusText.text = getString(R.string.status_ringer_ok)
            binding.statusCard.setCardBackgroundColor(getColor(R.color.status_ok))
        }
    }

    private fun startRingerService() {
        val intent = Intent(this, RingerMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
