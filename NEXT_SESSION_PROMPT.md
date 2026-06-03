# Ring Reminder — Next Session Implementation Prompt

## Project

Native Android Kotlin app (package `com.jcat.ringreminder`, minSdk 26, targetSdk 34). No third-party libs — AndroidX + Material Components only. ViewBinding enabled. Gradle 8.8, AGP 8.2.2, Java 21.

Working directory: `s:\Coding\Ringreminder`

The app runs a persistent foreground service (`RingerMonitorService`) that monitors ringer state and shows a floating overlay badge + notification when the phone is muted, on DND, vibrate-only, or low volume.

---

## Key files

| File | Role |
|---|---|
| `app/src/main/kotlin/com/jcat/ringreminder/RingerMonitorService.kt` | Foreground service, 30s polling + broadcast receiver, evaluates state, updates overlay + notification |
| `app/src/main/kotlin/com/jcat/ringreminder/RingerStateEvaluator.kt` | Pure logic — returns `EvaluationResult(activeConditions, primaryCondition)` |
| `app/src/main/kotlin/com/jcat/ringreminder/OverlayBadgeManager.kt` | WindowManager overlay, 4 states: COLLAPSED/EXPANDED/CONFIRMING/DOCKED |
| `app/src/main/kotlin/com/jcat/ringreminder/NotificationHelper.kt` | Builds service + alert + paused notifications |
| `app/src/main/kotlin/com/jcat/ringreminder/PrefsHelper.kt` | Typed SharedPreferences wrapper |
| `app/src/main/kotlin/com/jcat/ringreminder/SettingsActivity.kt` | All settings UI |
| `app/src/main/kotlin/com/jcat/ringreminder/MainActivity.kt` | Status card + permission banners |
| `app/src/main/res/layout/overlay_badge.xml` | Overlay layout (FrameLayout with 4 child views for each state) |
| `app/src/main/res/layout/activity_settings.xml` | Settings layout |
| `app/src/main/res/values/strings.xml` | All string resources |
| `app/src/main/res/values/colors.xml` | Colour palette |

## Important existing behaviour to preserve

- `RingerMonitorService.ACTION_REFRESH` — sent by SettingsActivity on every setting change; calls `evaluateAndUpdate()` immediately so changes take effect in milliseconds
- `OverlayBadgeManager`: inflated from a Service context — use plain `Button` not `MaterialButton` (inflate bug); always set explicit `android:textSize` and `android:layout_height` on overlay buttons
- `RingerStateEvaluator`: SILENT only fires when DND is OFF (`interruptionFilter == INTERRUPTION_FILTER_ALL`) — DND causes `ringerMode=SILENT` as a side effect on most devices
- Overlay `btn_cancel_dismiss`: if `cameFromDocked == true`, re-docks on the same side instead of COLLAPSED
- `isPro` defaults to `true` in PrefsHelper for dev/testing — flip to `false` before Play Store release

---

## What to implement (in this order)

### 1. Split notification channels

**Goal:** Background service notification is silent/hidden; alert notification properly peeks as heads-up.

- Add `CHANNEL_ALERTS_ID = "ring_reminder_alerts"` to `NotificationHelper`
- Create alert channel with `IMPORTANCE_DEFAULT`, no sound (`setSound(null, null)`)
- Create service channel with `IMPORTANCE_MIN` (currently `IMPORTANCE_LOW`)
- Add `ALERT_NOTIFICATION_ID = 3` constant
- In `updateNotification(result)`: if alert active, post alert notif on alerts channel (ALERT_NOTIFICATION_ID) with fix action; if not active, cancel ALERT_NOTIFICATION_ID
- The service status notification (NOTIFICATION_ID=1) stays on service channel, always shows plain "Ring Reminder active / Ringer OK" — no fix action needed there
- Call `createChannel()` for both channels

---

### 2. Haptic feedback on fix

**Goal:** 50ms vibration when fix is applied, toggled by a settings switch.

- New pref key in `PrefsHelper`: `haptic_on_fix: Boolean` (default `true`)
- In `RingerMonitorService.applyFixes()`: if `prefs.hapticOnFix`, call `VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)` via `Vibrator` / `VibratorManager` (API 31+ uses `VibratorManager`)
- Add `<uses-permission android:name="android.permission.VIBRATE" />` to manifest if not already present
- Add toggle to Settings "Alert triggers" section: "Haptic feedback on fix"
- Add string: `haptic_on_fix` label + description

---

### 3. Timed snooze

**Goal:** Replace current single "Hide" button in confirmation card with 4 snooze chips. Fix bug where overlay re-appears on next tick after dismiss.

**New pref keys in PrefsHelper:**
- `snooze_until_ms: Long` (default `0L`)
- `snoozed_condition: String` (default `""`)

**Confirmation card layout change (`overlay_badge.xml`):**
- Replace the two-button row (Cancel / Hide) with:
  - Question text (keep)
  - A horizontal `ChipGroup` with chips: "30 min", "1 hour", "2 hours", "Until changed"
  - A plain "Cancel" text button below
- Wire chips in `OverlayBadgeManager.setupInteractions()`:
  - "Until changed" → `prefs.snoozeUntilMs = Long.MAX_VALUE; prefs.snoozedCondition = result.primaryCondition?.name ?: ""; hide()`
  - Timed chips → `prefs.snoozeUntilMs = System.currentTimeMillis() + durationMs; hide()`

**In `RingerMonitorService.evaluateAndUpdate()`:**
```
if result.isAlertActive:
  if isSnoozed(result): hide overlay, skip alert notif
  else: show overlay + alert notif as normal
else:
  clearSnooze()
  hide overlay
  cancel alert notif
```

**`isSnoozed(result)` logic:**
- If `snoozeUntilMs == 0L` → not snoozed
- If `snoozeUntilMs == Long.MAX_VALUE` → snoozed until `result.primaryCondition?.name != snoozedCondition`
- Else → snoozed until `System.currentTimeMillis() >= snoozeUntilMs`

**`clearSnooze()`:** sets `snoozeUntilMs = 0L`, `snoozedCondition = ""`

---

### 4. Repeat nudge

**Goal:** If alert is snoozed and X minutes pass without the user fixing it, re-show everything.

**New pref keys:**
- `nudge_enabled: Boolean` (default `false`)
- `nudge_interval_minutes: Int` (default `10`)
- `alert_first_seen_ms: Long` (default `0L`)

**In `evaluateAndUpdate()`:**
- When alert becomes active and `alertFirstSeenMs == 0L`, set `prefs.alertFirstSeenMs = now`
- When condition clears, reset `prefs.alertFirstSeenMs = 0L` and `clearSnooze()`
- In the `isSnoozed` branch: if `nudgeEnabled` AND `now - alertFirstSeenMs >= nudgeIntervalMs` → `clearSnooze()` → fall through to show normally

**Settings (new "Alert behaviour" section in `activity_settings.xml` and `SettingsActivity`):**
- Toggle: "Re-alert if not fixed" (`nudge_enabled`)
- Chip group (shown when toggle is on): [5 min] [10 min] [15 min] [30 min] (`nudge_interval_minutes`)
- Call `notifyService()` on all changes

---

### 5. Show all active conditions (toggle)

**Goal:** When multiple conditions are active, show secondary ones as chips in the expanded card.

**New pref key:** `show_all_conditions: Boolean` (default `false`)

**`overlay_badge.xml` change:** Add inside the expanded card body, between description and fix button:
```xml
<com.google.android.material.chip.ChipGroup
    android:id="@+id/chip_group_secondary_conditions"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:visibility="gone"
    app:chipSpacingHorizontal="4dp" />
```

**`OverlayBadgeManager.updateContent()`:** If `prefs.showAllConditions && result.hasMultipleIssues`:
- Clear chip group
- Add a chip for each condition in `result.activeConditions` except `primaryCondition`
- Label map: SILENT→"Muted", DND→"DND on", VIBRATE→"Vibrate", LOW_VOLUME→"Low volume"
- Set chips as non-checkable, tinted to a light version of `bgColor`
- Set visibility VISIBLE; otherwise GONE

**Settings:** Toggle in "Alert triggers" section: "Show all active issues"

---

### 6. Quick Settings tile

**Goal:** A tile in the notification shade with smart tap behaviour.

**New file:** `app/src/main/kotlin/com/jcat/ringreminder/QuickSettingsTile.kt`

Tap logic (option C):
- If `RingerMonitorService.isRunning` AND last result had active alert → send `ACTION_FIX_NOW` intent
- If NOT running OR paused notification is showing → send `ACTION_RESUME` foreground service intent
- Otherwise → launch `MainActivity`
- Long press → always launch `MainActivity`

Tile state tracking: `RingerMonitorService` stores `@Volatile var lastResult: EvaluationResult?` in companion object; tile reads it in `onStartListening()`. Service calls `TileService.requestListeningState(context, ComponentName(context, QuickSettingsTile::class.java))` at the end of `evaluateAndUpdate()`.

**New drawable:** `app/src/main/res/drawable/ic_tile.xml` — white bell vector, 24dp

**AndroidManifest.xml addition:**
```xml
<service
    android:name=".QuickSettingsTile"
    android:label="Ring Reminder"
    android:icon="@drawable/ic_tile"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
    <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="false" />
</service>
```

---

### 7. Home screen widget

**Goal:** 1×1 status circle and 2×1 status + fix button widget.

**New files:**
- `app/src/main/kotlin/com/jcat/ringreminder/ReminderWidget.kt` — `AppWidgetProvider` subclass
- `app/src/main/res/layout/widget_small.xml` — 1×1: coloured circle ImageView with bell icon
- `app/src/main/res/layout/widget_large.xml` — 2×1: icon + state TextView + action Button
- `app/src/main/res/xml/widget_info_small.xml` — `appwidget-provider` (minWidth=40dp, minHeight=40dp, updatePeriodMillis=0)
- `app/src/main/res/xml/widget_info_large.xml` — `appwidget-provider` (minWidth=80dp, minHeight=40dp)

**State → widget appearance:**
- Alert active: red/purple/grey/orange bg (match condition colour), bell icon, condition text, "Fix" button
- All OK: green bg, bell icon, "Ringer OK"
- Paused: grey bg, "Paused", "Resume" button
- Stopped: grey bg, "Inactive", "Start" button

**Service update:** At end of `evaluateAndUpdate()`, call a `updateWidget(context, result)` helper that uses `RemoteViews` + `AppWidgetManager.updateAppWidget()`.

**AndroidManifest.xml addition:**
```xml
<receiver android:name=".ReminderWidget" android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider"
        android:resource="@xml/widget_info_small" />
</receiver>
```
(Separate receiver entry for large widget with `widget_info_large`)

---

## Constraints / gotchas to keep in mind

1. **Service context inflation:** Never use `MaterialButton` in overlays — inflate from Service context gives you a plain `Button`. Always set explicit `android:textSize` and `android:layout_height` on overlay buttons.
2. **Overlay permission self-check:** `OverlayBadgeManager.show()` already checks `Settings.canDrawOverlays()` and hides if revoked — don't remove this.
3. **DND/SILENT:** `RingerStateEvaluator` only adds SILENT when `interruptionFilter == INTERRUPTION_FILTER_ALL` — don't revert this logic.
4. **ACTION_REFRESH:** `SettingsActivity` fires this on every setting change. Any new settings added must also call `notifyService()`.
5. **isPro default:** Currently `true` for dev testing. Do NOT change this — leave it for the user to flip before release.
6. **Snooze fix button:** When the user taps fix (Unmute/Dismiss DND etc.), `applyFixes()` runs and `evaluateAndUpdate()` is called again. If the condition clears, `clearSnooze()` should also be called so the snooze state is clean for the next alert.
7. **Discuss UI/UX changes** before implementing anything visual that isn't explicitly described above.
