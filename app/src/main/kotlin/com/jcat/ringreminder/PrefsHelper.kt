package com.jcat.ringreminder

import android.content.Context
import android.content.SharedPreferences

class PrefsHelper(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "ring_reminder_prefs"
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

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_complete", false)
        set(v) = prefs.edit().putBoolean("onboarding_complete", v).apply()

    // Pro status — default true for dev/testing; flip to false before Play Store release
    var isPro: Boolean
        get() = prefs.getBoolean("is_pro", true)
        set(v) = prefs.edit().putBoolean("is_pro", v).apply()

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
}
