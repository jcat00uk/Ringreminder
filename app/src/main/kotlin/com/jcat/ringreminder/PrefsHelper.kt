package com.jcat.ringreminder

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PrefsHelper(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        const val PREFS_NAME = "ring_reminder_prefs"
        const val SECURE_PREFS_NAME = "ring_reminder_secure_prefs"
        const val TRIAL_DURATION_MS = 7L * 24 * 60 * 60 * 1000L
    }

    var triggerSilent: Boolean
        get() = prefs.getBoolean("trigger_silent", true)
        set(v) = prefs.edit().putBoolean("trigger_silent", v).apply()

    var triggerVibrate: Boolean
        get() = prefs.getBoolean("trigger_vibrate", true)
        set(v) = prefs.edit().putBoolean("trigger_vibrate", v).apply()

    var triggerDnd: Boolean
        get() = prefs.getBoolean("trigger_dnd", true)
        set(v) = prefs.edit().putBoolean("trigger_dnd", v).apply()

    var triggerLowVolume: Boolean
        get() = prefs.getBoolean("trigger_low_volume", true)
        set(v) = prefs.edit().putBoolean("trigger_low_volume", v).apply()

    var thresholdVolumePercent: Int
        get() = prefs.getInt("threshold_volume_percent", 30)
        set(v) = prefs.edit().putInt("threshold_volume_percent", v).apply()

    var fixUnmute: Boolean
        get() = prefs.getBoolean("fix_unmute", true)
        set(v) = prefs.edit().putBoolean("fix_unmute", v).apply()

    var fixDisableDnd: Boolean
        get() = prefs.getBoolean("fix_disable_dnd", true)
        set(v) = prefs.edit().putBoolean("fix_disable_dnd", v).apply()

    var fixRestoreVolume: Boolean
        get() = prefs.getBoolean("fix_restore_volume", true)
        set(v) = prefs.edit().putBoolean("fix_restore_volume", v).apply()

    var restoreVolumePercent: Int
        get() = prefs.getInt("restore_volume_percent", 60)
        set(v) = prefs.edit().putInt("restore_volume_percent", v).apply()

    var masterEnabled: Boolean
        get() = prefs.getBoolean("master_enabled", true)
        set(v) = prefs.edit().putBoolean("master_enabled", v).apply()

    var badgeX: Float
        get() = prefs.getFloat("badge_x", -1f)
        set(v) = prefs.edit().putFloat("badge_x", v).apply()

    var badgeY: Float
        get() = prefs.getFloat("badge_y", -1f)
        set(v) = prefs.edit().putFloat("badge_y", v).apply()

    var badgeDocked: Boolean
        get() = prefs.getBoolean("badge_docked", false)
        set(v) = prefs.edit().putBoolean("badge_docked", v).apply()

    var badgeDockedRight: Boolean
        get() = prefs.getBoolean("badge_docked_right", true)
        set(v) = prefs.edit().putBoolean("badge_docked_right", v).apply()

    var badgeDockedY: Float
        get() = prefs.getFloat("badge_docked_y", 900f)
        set(v) = prefs.edit().putFloat("badge_docked_y", v).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_complete", false)
        set(v) = prefs.edit().putBoolean("onboarding_complete", v).apply()

    var hasPurchasedPro: Boolean
        get() = prefs.getBoolean("is_pro", false)
        set(v) = prefs.edit().putBoolean("is_pro", v).apply()

    var trialStartMs: Long
        get() {
            val stored = prefs.getLong("trial_start_ms", -1L)
            if (stored == -1L) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("trial_start_ms", now).apply()
                return now
            }
            return stored
        }
        set(v) = prefs.edit().putLong("trial_start_ms", v).apply()

    val isInTrial: Boolean
        get() = !hasPurchasedPro && System.currentTimeMillis() - trialStartMs < TRIAL_DURATION_MS

    val trialDaysRemaining: Int
        get() {
            val remaining = TRIAL_DURATION_MS - (System.currentTimeMillis() - trialStartMs)
            return ((remaining + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
        }

    var devModeActive: Boolean
        get() = securePrefs.getBoolean("dev_mode_active", false)
        set(v) = securePrefs.edit().putBoolean("dev_mode_active", v).apply()

    var isPro: Boolean
        get() = BuildConfig.DEBUG || hasPurchasedPro || isInTrial || devModeActive
        set(v) { hasPurchasedPro = v }

    // Scheduling
    var scheduleEnabled: Boolean
        get() = prefs.getBoolean("schedule_enabled", false)
        set(v) = prefs.edit().putBoolean("schedule_enabled", v).apply()

    var scheduleStartHour: Int
        get() = prefs.getInt("schedule_start_hour", 8)
        set(v) = prefs.edit().putInt("schedule_start_hour", v).apply()

    var scheduleStartMinute: Int
        get() = prefs.getInt("schedule_start_minute", 0)
        set(v) = prefs.edit().putInt("schedule_start_minute", v).apply()

    var scheduleEndHour: Int
        get() = prefs.getInt("schedule_end_hour", 22)
        set(v) = prefs.edit().putInt("schedule_end_hour", v).apply()

    var scheduleEndMinute: Int
        get() = prefs.getInt("schedule_end_minute", 0)
        set(v) = prefs.edit().putInt("schedule_end_minute", v).apply()

    // Overlay theme: "default" | "dark" | "mono" | "vibrant"
    var overlayTheme: String
        get() = prefs.getString("overlay_theme", "default") ?: "default"
        set(v) = prefs.edit().putString("overlay_theme", v).apply()

    // Overlay panel background: "light" | "dark" | "system"
    var overlayPanelMode: String
        get() = prefs.getString("overlay_panel_mode", "system") ?: "system"
        set(v) = prefs.edit().putString("overlay_panel_mode", v).apply()

    // Feature 2: Haptic feedback
    var hapticOnFix: Boolean
        get() = prefs.getBoolean("haptic_on_fix", true)
        set(v) = prefs.edit().putBoolean("haptic_on_fix", v).apply()

    // Feature 3: Timed snooze
    var snoozeUntilMs: Long
        get() = prefs.getLong("snooze_until_ms", 0L)
        set(v) = prefs.edit().putLong("snooze_until_ms", v).apply()

    var snoozedCondition: String
        get() = prefs.getString("snoozed_condition", "") ?: ""
        set(v) = prefs.edit().putString("snoozed_condition", v).apply()

    // Feature 4: Repeat nudge
    var nudgeEnabled: Boolean
        get() = prefs.getBoolean("nudge_enabled", false)
        set(v) = prefs.edit().putBoolean("nudge_enabled", v).apply()

    var nudgeIntervalMinutes: Int
        get() = prefs.getInt("nudge_interval_minutes", 10)
        set(v) = prefs.edit().putInt("nudge_interval_minutes", v).apply()

    var alertFirstSeenMs: Long
        get() = prefs.getLong("alert_first_seen_ms", 0L)
        set(v) = prefs.edit().putLong("alert_first_seen_ms", v).apply()

    // Feature 5: Show all active conditions
    var showAllConditions: Boolean
        get() = prefs.getBoolean("show_all_conditions", false)
        set(v) = prefs.edit().putBoolean("show_all_conditions", v).apply()

    // Auto-fix (Pro): show "Auto-fix in…" row in the overlay badge
    var autoFixOverlayEnabled: Boolean
        get() = prefs.getBoolean("auto_fix_overlay_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_fix_overlay_enabled", v).apply()

    // Auto-fix (Pro): daily recurring unmute at a set time
    var autoFixEnabled: Boolean
        get() = prefs.getBoolean("auto_fix_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_fix_enabled", v).apply()

    var autoFixRecurringHour: Int
        get() = prefs.getInt("auto_fix_recurring_hour", -1)
        set(v) = prefs.edit().putInt("auto_fix_recurring_hour", v).apply()

    var autoFixRecurringMinute: Int
        get() = prefs.getInt("auto_fix_recurring_minute", 0)
        set(v) = prefs.edit().putInt("auto_fix_recurring_minute", v).apply()

    // Timestamp when a one-shot auto-fix is scheduled (0 = none scheduled)
    var autoFixScheduledMs: Long
        get() = prefs.getLong("auto_fix_scheduled_ms", 0L)
        set(v) = prefs.edit().putLong("auto_fix_scheduled_ms", v).apply()

    // Date string (yyyyMMdd) when recurring auto-fix last fired — prevents double-firing
    var autoFixLastFiredDate: String
        get() = prefs.getString("auto_fix_last_fired_date", "") ?: ""
        set(v) = prefs.edit().putString("auto_fix_last_fired_date", v).apply()

    // Per-app suppression (Pro)
    var suppressAppsEnabled: Boolean
        get() = prefs.getBoolean("suppress_apps_enabled", false)
        set(v) = prefs.edit().putBoolean("suppress_apps_enabled", v).apply()

    var suppressedApps: Set<String>
        get() = prefs.getStringSet("suppressed_apps", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("suppressed_apps", v).apply()

    // Lock screen alert visibility (Pro)
    var lockScreenAlert: Boolean
        get() = prefs.getBoolean("lock_screen_alert", false)
        set(v) = prefs.edit().putBoolean("lock_screen_alert", v).apply()

    // Battery saver: reduce polling when battery is low (Pro)
    var batterySaverEnabled: Boolean
        get() = prefs.getBoolean("battery_saver_enabled", false)
        set(v) = prefs.edit().putBoolean("battery_saver_enabled", v).apply()

    // Pulse overlay icon when ring/notification arrives while muted (Pro)
    var pulseOnMutedAlert: Boolean
        get() = prefs.getBoolean("pulse_on_muted_alert", false)
        set(v) = prefs.edit().putBoolean("pulse_on_muted_alert", v).apply()

    var devModeEnabled: Boolean
        get() = prefs.getBoolean("dev_mode_enabled", false)
        set(v) = prefs.edit().putBoolean("dev_mode_enabled", v).apply()
}
