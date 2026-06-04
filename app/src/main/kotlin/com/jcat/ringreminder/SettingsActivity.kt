package com.jcat.ringreminder

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.jcat.ringreminder.databinding.ActivitySettingsBinding
import android.Manifest
import android.content.pm.PackageManager

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCROLL_TO = "scroll_to"
        const val SCROLL_AUTO_FIX = "auto_fix"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsHelper
    private var billingManager: BillingManager? = null

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
        billingManager = BillingManager(this).also { bm ->
            bm.start { runOnUiThread { setupProFeatures() } }
        }
        setupMasterToggle()
        setupWidgetButton()
        setupTriggers()
        setupAlertBehaviour()
        setupFixActions()
        setupProFeatures()
        setupDebugTrialShortcut()
        updatePermissionsSection()

        if (intent.getStringExtra(EXTRA_SCROLL_TO) == SCROLL_AUTO_FIX) {
            binding.switchAutoFixEnabled.post {
                binding.nestedScrollView.smoothScrollTo(0, binding.switchAutoFixEnabled.top)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager?.destroy()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionsSection()
    }

    override fun onPause() {
        super.onPause()
        notifyService()
    }

    private fun notifyService() {
        if (RingerMonitorService.isRunning) {
            startService(Intent(this, RingerMonitorService::class.java).apply {
                action = RingerMonitorService.ACTION_REFRESH
            })
        }
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

    private fun setupWidgetButton() {
        binding.btnAddWidgetSmall.setOnClickListener { pinWidget(ReminderWidget::class.java) }
        binding.btnAddWidgetLarge.setOnClickListener { pinWidget(ReminderWidgetLarge::class.java) }
    }

    private fun pinWidget(providerClass: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, providerClass)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                appWidgetManager.requestPinAppWidget(provider, null, null)
            } else {
                Toast.makeText(this, getString(R.string.widget_not_supported), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.widget_not_supported), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTriggers() {
        binding.switchTriggerSilent.isChecked = prefs.triggerSilent
        binding.switchTriggerSilent.setOnCheckedChangeListener { _, v -> prefs.triggerSilent = v; notifyService() }

        binding.switchTriggerVibrate.isChecked = prefs.triggerVibrate
        binding.switchTriggerVibrate.setOnCheckedChangeListener { _, v -> prefs.triggerVibrate = v; notifyService() }

        binding.switchTriggerDnd.isChecked = prefs.triggerDnd
        binding.switchTriggerDnd.setOnCheckedChangeListener { _, v -> prefs.triggerDnd = v; notifyService() }

        binding.switchTriggerLowVolume.isChecked = prefs.triggerLowVolume
        binding.switchTriggerLowVolume.setOnCheckedChangeListener { _, v -> prefs.triggerLowVolume = v; notifyService() }

        listOf(20, 30, 40, 50).forEach { pct ->
            binding.chipGroupThreshold.addView(Chip(this).apply {
                text = "$pct%"
                isCheckable = true
                isChecked = prefs.thresholdVolumePercent == pct
                setOnClickListener { prefs.thresholdVolumePercent = pct; notifyService() }
            })
        }

        binding.switchShowAllConditions.isChecked = prefs.showAllConditions
        binding.switchShowAllConditions.setOnCheckedChangeListener { _, v -> prefs.showAllConditions = v; notifyService() }

        binding.switchHapticOnFix.isChecked = prefs.hapticOnFix
        binding.switchHapticOnFix.setOnCheckedChangeListener { _, v -> prefs.hapticOnFix = v }
    }

    private fun setupAlertBehaviour() {
        binding.switchNudgeEnabled.isChecked = prefs.nudgeEnabled
        binding.switchNudgeEnabled.setOnCheckedChangeListener { _, v ->
            prefs.nudgeEnabled = v
            refreshNudgeIntervalState()
            notifyService()
        }

        listOf(5, 10, 15, 30).forEach { min ->
            binding.chipGroupNudgeInterval.addView(Chip(this).apply {
                text = "$min min"
                isCheckable = true
                isChecked = prefs.nudgeIntervalMinutes == min
                setOnClickListener { prefs.nudgeIntervalMinutes = min; notifyService() }
            })
        }

        refreshNudgeIntervalState()
    }

    private fun refreshNudgeIntervalState() {
        binding.layoutNudgeInterval.alpha = if (prefs.nudgeEnabled) 1f else 0.4f
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

    private fun setupProFeatures() {
        val isPro = prefs.isPro

        // ── Alert schedule ──
        binding.switchScheduleEnabled.isChecked = prefs.scheduleEnabled
        binding.switchScheduleEnabled.isEnabled = isPro
        binding.switchScheduleEnabled.alpha = if (isPro) 1f else 0.4f
        binding.switchScheduleEnabled.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchScheduleEnabled.isChecked = false; return@setOnCheckedChangeListener }
            prefs.scheduleEnabled = checked
            refreshScheduleTimesState()
            notifyService()
        }
        updateScheduleTimeLabels()
        refreshScheduleTimesState()
        binding.btnScheduleFrom.setOnClickListener {
            if (!prefs.isPro) return@setOnClickListener
            TimePickerDialog(this, { _, h, m ->
                prefs.scheduleStartHour = h; prefs.scheduleStartMinute = m
                updateScheduleTimeLabels(); notifyService()
            }, prefs.scheduleStartHour, prefs.scheduleStartMinute, true).show()
        }
        binding.btnScheduleTo.setOnClickListener {
            if (!prefs.isPro) return@setOnClickListener
            TimePickerDialog(this, { _, h, m ->
                prefs.scheduleEndHour = h; prefs.scheduleEndMinute = m
                updateScheduleTimeLabels(); notifyService()
            }, prefs.scheduleEndHour, prefs.scheduleEndMinute, true).show()
        }

        // ── Auto-unmute: overlay quick-fix toggle (Pro) ──
        binding.switchAutoFixOverlayEnabled.isChecked = prefs.autoFixOverlayEnabled
        binding.switchAutoFixOverlayEnabled.isEnabled = isPro
        binding.switchAutoFixOverlayEnabled.alpha = if (isPro) 1f else 0.4f
        binding.switchAutoFixOverlayEnabled.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchAutoFixOverlayEnabled.isChecked = false; return@setOnCheckedChangeListener }
            prefs.autoFixOverlayEnabled = checked
            notifyService()
        }

        // ── Auto-unmute: daily recurring toggle (Pro) ──
        binding.switchAutoFixEnabled.isChecked = prefs.autoFixEnabled
        binding.switchAutoFixEnabled.isEnabled = isPro
        binding.switchAutoFixEnabled.alpha = if (isPro) 1f else 0.4f
        binding.switchAutoFixEnabled.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchAutoFixEnabled.isChecked = false; return@setOnCheckedChangeListener }
            prefs.autoFixEnabled = checked
            refreshAutoFixState()
            notifyService()
        }
        updateAutoFixTimeLabel()
        refreshAutoFixState()
        binding.btnAutoFixTime.setOnClickListener {
            if (!prefs.isPro || !prefs.autoFixEnabled) return@setOnClickListener
            val h = if (prefs.autoFixRecurringHour >= 0) prefs.autoFixRecurringHour else 7
            val m = prefs.autoFixRecurringMinute
            TimePickerDialog(this, { _, hour, min ->
                prefs.autoFixRecurringHour = hour
                prefs.autoFixRecurringMinute = min
                updateAutoFixTimeLabel()
                notifyService()
            }, h, m, true).show()
        }

        // ── Per-app suppression (Pro) ──
        binding.switchSuppressApps.isChecked = prefs.suppressAppsEnabled
        binding.switchSuppressApps.isEnabled = isPro
        binding.switchSuppressApps.alpha = if (isPro) 1f else 0.4f
        binding.switchSuppressApps.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchSuppressApps.isChecked = false; return@setOnCheckedChangeListener }
            if (checked && !hasUsageStatsPermission()) {
                binding.switchSuppressApps.isChecked = false
                showUsageStatsRationale()
                return@setOnCheckedChangeListener
            }
            prefs.suppressAppsEnabled = checked
            refreshSuppressAppsState()
            notifyService()
        }
        refreshSuppressAppsState()
        binding.btnManageSuppressApps.setOnClickListener {
            startActivity(Intent(this, AppSuppressActivity::class.java))
        }

        // ── Lock screen alert (Pro) ──
        binding.switchLockScreenAlert.isChecked = prefs.lockScreenAlert
        binding.switchLockScreenAlert.isEnabled = isPro
        binding.switchLockScreenAlert.alpha = if (isPro) 1f else 0.4f
        binding.switchLockScreenAlert.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchLockScreenAlert.isChecked = false; return@setOnCheckedChangeListener }
            prefs.lockScreenAlert = checked
            notifyService()
        }

        // ── Battery saver (Pro) ──
        binding.switchBatterySaver.isChecked = prefs.batterySaverEnabled
        binding.switchBatterySaver.isEnabled = isPro
        binding.switchBatterySaver.alpha = if (isPro) 1f else 0.4f
        binding.switchBatterySaver.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchBatterySaver.isChecked = false; return@setOnCheckedChangeListener }
            prefs.batterySaverEnabled = checked
        }

        // ── Pulse on muted alert (Pro) ──
        binding.switchPulseOnMutedAlert.isChecked = prefs.pulseOnMutedAlert
        binding.switchPulseOnMutedAlert.isEnabled = isPro
        binding.switchPulseOnMutedAlert.alpha = if (isPro) 1f else 0.4f
        binding.switchPulseOnMutedAlert.setOnCheckedChangeListener { _, checked ->
            if (!prefs.isPro) { binding.switchPulseOnMutedAlert.isChecked = false; return@setOnCheckedChangeListener }
            if (checked && !hasNotificationListenerPermission()) {
                binding.switchPulseOnMutedAlert.isChecked = false
                showNotificationListenerRationale()
                return@setOnCheckedChangeListener
            }
            prefs.pulseOnMutedAlert = checked
        }

        // ── Overlay theme chips (tinted to match each theme's SILENT color) ──
        val themeEntries = listOf(
            "default" to "Default", "dark" to "Dark", "mono" to "Mono", "vibrant" to "Vibrant",
            "pastel" to "Pastel", "amoled" to "Amoled", "warm" to "Warm", "cool" to "Cool"
        )
        binding.chipGroupTheme.removeAllViews()
        val paletteHelper = OverlayBadgeManager(this, {}, {})
        themeEntries.forEach { (id, label) ->
            val alertColor = paletteHelper.conditionColor(AlertCondition.DND, id)
            val isLight = id == "pastel"
            binding.chipGroupTheme.addView(Chip(this).apply {
                text = label
                isCheckable = true
                isEnabled = isPro
                isChecked = prefs.overlayTheme == id
                alpha = if (isPro) 1f else 0.4f
                chipBackgroundColor = ColorStateList.valueOf(alertColor)
                setTextColor(if (isLight) Color.parseColor("#333333") else Color.WHITE)
                setOnClickListener { if (prefs.isPro) { prefs.overlayTheme = id; notifyService() } }
            })
        }

        // Panel background mode chips
        val panelModes = listOf("light" to getString(R.string.panel_mode_light),
                                "dark"  to getString(R.string.panel_mode_dark),
                                "system" to getString(R.string.panel_mode_system))
        binding.chipGroupPanelMode.removeAllViews()
        panelModes.forEach { (id, label) ->
            binding.chipGroupPanelMode.addView(Chip(this).apply {
                text = label
                isCheckable = true
                isEnabled = isPro
                isChecked = prefs.overlayPanelMode == id
                alpha = if (isPro) 1f else 0.4f
                setOnClickListener { if (prefs.isPro) { prefs.overlayPanelMode = id; notifyService() } }
            })
        }

        // Pro upgrade row — visible whenever not purchased (including during trial)
        val upgradeVisible = if (!prefs.hasPurchasedPro) View.VISIBLE else View.GONE
        binding.dividerProUpgrade.visibility = upgradeVisible
        binding.layoutProUpgrade.visibility = upgradeVisible
        binding.txtProLocked.text = when {
            prefs.isInTrial -> {
                val days = prefs.trialDaysRemaining
                val dayWord = if (days == 1) "1 day" else "$days days"
                "$dayWord remaining in your free trial"
            }
            else -> getString(R.string.trial_expired)
        }
        binding.btnRestorePro.setOnClickListener {
            billingManager?.restorePurchases { restored ->
                runOnUiThread {
                    val msg = if (restored) getString(R.string.restore_pro_success)
                              else getString(R.string.restore_pro_not_found)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (restored) setupProFeatures()
                }
            }
        }
        binding.btnUpgradePro.setOnClickListener {
            billingManager?.launchPurchaseFlow(this) { runOnUiThread { setupProFeatures() } }
        }
    }

    private fun updateScheduleTimeLabels() {
        binding.btnScheduleFrom.text = String.format("%02d:%02d", prefs.scheduleStartHour, prefs.scheduleStartMinute)
        binding.btnScheduleTo.text = String.format("%02d:%02d", prefs.scheduleEndHour, prefs.scheduleEndMinute)
    }

    private fun refreshScheduleTimesState() {
        val active = prefs.isPro && prefs.scheduleEnabled
        binding.layoutScheduleTimes.alpha = if (active) 1f else 0.4f
        binding.btnScheduleFrom.isEnabled = active
        binding.btnScheduleTo.isEnabled = active
    }

    private fun updateAutoFixTimeLabel() {
        binding.btnAutoFixTime.text = if (prefs.autoFixRecurringHour >= 0) {
            String.format("%02d:%02d", prefs.autoFixRecurringHour, prefs.autoFixRecurringMinute)
        } else {
            getString(R.string.auto_fix_not_set)
        }
    }

    private fun refreshAutoFixState() {
        val active = prefs.isPro && prefs.autoFixEnabled
        binding.layoutAutoFixTime.alpha = if (active) 1f else 0.4f
        binding.btnAutoFixTime.isEnabled = active
    }

    private fun refreshSuppressAppsState() {
        val active = prefs.isPro && prefs.suppressAppsEnabled
        binding.layoutSuppressAppsManage.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun showUsageStatsRationale() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.perm_usage_stats))
            .setMessage(getString(R.string.perm_usage_stats_reason))
            .setPositiveButton("Grant") { _, _ ->
                settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasNotificationListenerPermission(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun showNotificationListenerRationale() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.perm_notification_listener))
            .setMessage(getString(R.string.perm_notification_listener_reason))
            .setPositiveButton("Grant") { _, _ ->
                settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePermissionsSection() {
        // POST_NOTIFICATIONS (Android 13+ only)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPostNotif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            binding.layoutPostNotificationsPerm.visibility = View.VISIBLE
            binding.dividerPostNotifications.visibility = View.VISIBLE
            binding.statusPostNotifications.text = if (hasPostNotif) getString(R.string.granted) else getString(R.string.not_granted)
            binding.btnFixPostNotifications.visibility = if (!hasPostNotif) View.VISIBLE else View.GONE
            binding.btnFixPostNotifications.setOnClickListener {
                settingsLauncher.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
        } else {
            binding.layoutPostNotificationsPerm.visibility = View.GONE
            binding.dividerPostNotifications.visibility = View.GONE
        }

        val hasOverlay = Settings.canDrawOverlays(this)
        val hasDnd = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
        val hasBattery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        val hasNotifListener = hasNotificationListenerPermission()
        val hasUsageStats = hasUsageStatsPermission()

        binding.statusOverlay.text = if (hasOverlay) getString(R.string.granted) else getString(R.string.not_granted)
        binding.statusDnd.text = if (hasDnd) getString(R.string.granted) else getString(R.string.not_granted)
        binding.statusBattery.text = if (hasBattery) getString(R.string.granted) else getString(R.string.not_granted)
        binding.statusNotificationListener.text = if (hasNotifListener) getString(R.string.granted) else getString(R.string.not_granted)
        binding.statusUsageStats.text = if (hasUsageStats) getString(R.string.granted) else getString(R.string.not_granted)

        binding.btnFixOverlay.visibility = if (!hasOverlay) View.VISIBLE else View.GONE
        binding.btnFixDndPerm.visibility = if (!hasDnd) View.VISIBLE else View.GONE
        binding.btnFixBattery.visibility = if (!hasBattery) View.VISIBLE else View.GONE
        binding.btnFixNotificationListener.visibility = if (!hasNotifListener) View.VISIBLE else View.GONE
        binding.btnFixUsageStats.visibility = if (!hasUsageStats) View.VISIBLE else View.GONE

        binding.btnFixOverlay.setOnClickListener {
            settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        binding.btnFixDndPerm.setOnClickListener {
            settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        binding.btnFixBattery.setOnClickListener {
            settingsLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        binding.btnFixNotificationListener.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.perm_notification_listener))
                .setMessage(getString(R.string.perm_notification_listener_reason))
                .setPositiveButton("Grant") { _, _ ->
                    settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnFixUsageStats.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.perm_usage_stats))
                .setMessage(getString(R.string.perm_usage_stats_reason))
                .setPositiveButton("Grant") { _, _ ->
                    settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupDebugTrialShortcut() {
        if (!BuildConfig.DEBUG) return
        binding.txtSectionPro.setOnLongClickListener {
            if (prefs.isInTrial) {
                prefs.trialStartMs = System.currentTimeMillis() - PrefsHelper.TRIAL_DURATION_MS - 86_400_000L
                Toast.makeText(this, "[Debug] Trial expired", Toast.LENGTH_SHORT).show()
            } else {
                prefs.trialStartMs = System.currentTimeMillis()
                Toast.makeText(this, "[Debug] Trial reset to today", Toast.LENGTH_SHORT).show()
            }
            setupProFeatures()
            true
        }
    }

    private fun startRingerService() {
        val intent = Intent(this, RingerMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}
