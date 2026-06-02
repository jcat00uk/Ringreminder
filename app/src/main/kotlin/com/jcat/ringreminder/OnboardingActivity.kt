package com.jcat.ringreminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationManager
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jcat.ringreminder.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: PrefsHelper

    private var currentStep = 0

    private enum class Step { NOTIFICATION, OVERLAY, DND, BATTERY }

    private val steps = Step.values().toList()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> showStep(currentStep) }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> showStep(currentStep) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsHelper(this)

        binding.btnSkip.setOnClickListener { nextStep() }
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            finishOnboarding()
            return
        }
        currentStep = index

        binding.stepIndicator.text = getString(R.string.step_of, index + 1, steps.size)

        val (titleRes, descRes) = when (steps[index]) {
            Step.NOTIFICATION -> R.string.onboard_notif_title to R.string.onboard_notif_desc
            Step.OVERLAY -> R.string.onboard_overlay_title to R.string.onboard_overlay_desc
            Step.DND -> R.string.onboard_dnd_title to R.string.onboard_dnd_desc
            Step.BATTERY -> R.string.onboard_battery_title to R.string.onboard_battery_desc
        }
        binding.stepTitle.setText(titleRes)
        binding.stepDesc.setText(descRes)

        val alreadyGranted = isCurrentPermissionGranted()
        if (alreadyGranted) {
            binding.btnGrant.setText(R.string.next)
            binding.btnGrant.setOnClickListener { nextStep() }
        } else {
            binding.btnGrant.setText(R.string.grant_permission)
            binding.btnGrant.setOnClickListener { requestCurrentPermission() }
        }
        binding.btnGrant.visibility = View.VISIBLE
    }

    private fun isCurrentPermissionGranted(): Boolean = when (steps[currentStep]) {
        Step.NOTIFICATION -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        Step.OVERLAY -> Settings.canDrawOverlays(this)
        Step.DND -> (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
        Step.BATTERY -> (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestCurrentPermission() {
        when (steps[currentStep]) {
            Step.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    nextStep()
                }
            }
            Step.OVERLAY -> settingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Step.DND -> settingsLauncher.launch(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            )
            Step.BATTERY -> settingsLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun nextStep() = showStep(currentStep + 1)

    private fun finishOnboarding() {
        prefs.onboardingComplete = true
        val serviceIntent = Intent(this, RingerMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
