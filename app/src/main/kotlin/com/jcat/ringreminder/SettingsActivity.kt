package com.jcat.ringreminder

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.jcat.ringreminder.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsHelper

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updatePermissionsSection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PrefsHelper(this)
        setupMasterToggle()
        setupTriggers()
        setupFixActions()
        updatePermissionsSection()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsSection()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupMasterToggle() {
        binding.switchMaster.isChecked = prefs.masterEnabled
        binding.switchMaster.setOnCheckedChangeListener { _, checked ->
            prefs.masterEnabled = checked
            if (checked) startRingerService()
            else stopService(Intent(this, RingerMonitorService::class.java))
        }
    }

    private fun setupTriggers() {
        binding.switchTriggerSilent.isChecked = prefs.triggerSilent
        binding.switchTriggerSilent.setOnCheckedChangeListener { _, v -> prefs.triggerSilent = v }

        binding.switchTriggerVibrate.isChecked = prefs.triggerVibrate
        binding.switchTriggerVibrate.setOnCheckedChangeListener { _, v -> prefs.triggerVibrate = v }

        binding.switchTriggerDnd.isChecked = prefs.triggerDnd
        binding.switchTriggerDnd.setOnCheckedChangeListener { _, v -> prefs.triggerDnd = v }

        binding.switchTriggerLowVolume.isChecked = prefs.triggerLowVolume
        binding.switchTriggerLowVolume.setOnCheckedChangeListener { _, v -> prefs.triggerLowVolume = v }

        listOf(20, 30, 40, 50).forEach { pct ->
            binding.chipGroupThreshold.addView(Chip(this).apply {
                text = "$pct%"
                isCheckable = true
                isChecked = prefs.thresholdVolumePercent == pct
                setOnClickListener { prefs.thresholdVolumePercent = pct }
            })
        }
    }

    private fun setupFixActions() {
        binding.switchFixUnmute.isChecked = prefs.fixUnmute
        binding.switchFixUnmute.setOnCheckedChangeListener { _, v -> prefs.fixUnmute = v }

        binding.switchFixDnd.isChecked = prefs.fixDisableDnd
        binding.switchFixDnd.setOnCheckedChangeListener { _, v -> prefs.fixDisableDnd = v }

        binding.switchFixVolume.isChecked = prefs.fixRestoreVolume
        binding.switchFixVolume.setOnCheckedChangeListener { _, v -> prefs.fixRestoreVolume = v }

        listOf(40, 50, 60, 75).forEach { pct ->
            binding.chipGroupRestore.addView(Chip(this).apply {
                text = "$pct%"
                isCheckable = true
                isChecked = prefs.restoreVolumePercent == pct
                setOnClickListener { prefs.restoreVolumePercent = pct }
            })
        }
    }

    private fun updatePermissionsSection() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasDnd = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
        val hasBattery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        binding.statusOverlay.text = if (hasOverlay) getString(R.string.granted) else getString(R.string.not_granted)
        binding.statusDnd.text = if (hasDnd) getString(R.string.granted) else getString(R.string.not_granted)
        binding.statusBattery.text = if (hasBattery) getString(R.string.granted) else getString(R.string.not_granted)

        binding.btnFixOverlay.visibility = if (!hasOverlay) View.VISIBLE else View.GONE
        binding.btnFixDndPerm.visibility = if (!hasDnd) View.VISIBLE else View.GONE
        binding.btnFixBattery.visibility = if (!hasBattery) View.VISIBLE else View.GONE

        binding.btnFixOverlay.setOnClickListener {
            settingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        binding.btnFixDndPerm.setOnClickListener {
            settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        binding.btnFixBattery.setOnClickListener {
            settingsLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun startRingerService() {
        val intent = Intent(this, RingerMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}
