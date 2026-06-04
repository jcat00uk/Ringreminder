# Ring Reminder — Next Session Prompt

## Project

Native Android Kotlin app (`com.jcat.ringreminder`, minSdk 26, targetSdk 34). No third-party libs — AndroidX + Material Components only. ViewBinding enabled. Gradle 8.8, AGP 8.2.2, Java 21 target (system has Java 25 — daemon warning on build is harmless, always falls back to BUILD SUCCESSFUL).

Working directory: `s:\Coding\Ringreminder`

Build command (use gradlew — Gradle 8.14.5, Java 25 compatible):
```
S:\Coding\Ringreminder\gradlew.bat assembleDebug
```
Or use `1build.bat` for the full menu (debug/release APK/AAB with versioning).

---

## Current state (after Session 4)

### Core service
`RingerMonitorService` — foreground service, 30s polling + broadcasts. Evaluates SILENT/DND/VIBRATE/LOW_VOLUME. Owns overlay, notifications, QS tile, widget updates. Handles:
- `ACTION_REFRESH` — immediate re-evaluate (sent by Settings on every change)
- `ACTION_FIX_NOW` / `ACTION_CANCEL_AUTO_FIX` / `ACTION_AUTO_FIX`
- `ACTION_PULSE_START` / `ACTION_PULSE_STOP` (from NotificationListenerService)
- `ACTION_PAUSE` / `ACTION_RESUME`
- Per-app suppression: 2s fast-check loop via separate `suppressCheckRunnable` when `suppressAppsEnabled` is on
- Auto-fix scheduling via `AlarmManager.setExactAndAllowWhileIdle` (falls back to inexact if `SCHEDULE_EXACT_ALARM` not granted)
- Recurring daily unmute: checks within ±1 min window, tracks `autoFixLastFiredDate` to prevent double-fire
- Battery saver: polls at 120s instead of 30s when battery ≤ 20% (Pro toggle)
- Phone state listener (`PhoneStateListener`) for CALL_STATE_RINGING → `startContinuousPulse()` on overlay

### Overlay badge (`OverlayBadgeManager`)
**3 states:** COLLAPSED (pill) → EXPANDED (card) → DOCKED (edge nub). CONFIRMING state removed.

Expanded card contains (top to bottom):
1. Coloured header (title + settings gear + X)
2. Description text
3. Secondary condition chips (if `showAllConditions`)
4. **Unmute now** button (context-aware: Unmute / Dismiss Do Not Disturb / Enable Ringer / Raise Volume)
5. Scheduled unmute row — shows "Unmutes at 4:30pm + Cancel" when one-shot is pending (GONE otherwise)
6. Daily unmute row — shows "Auto-unmutes daily at 07:30 + Change" (GONE otherwise)
7. Snooze alerts for: dropdown row → AlertDialog (15m/30m/1hr/2hr/Custom/Until unmuted)
8. Unmute in: dropdown row → AlertDialog (15m/30m/1hr/2hr/Custom) — Pro only, shown when `autoFixOverlayEnabled`

Pill text shows "Unmute in Xm" when one-shot auto-fix is pending.

All dialogs set `window?.setType(TYPE_APPLICATION_OVERLAY)` — required when showing dialogs from Service context.

Continuous pulse: `startContinuousPulse()` / `stopPulse()` — alpha fade animation (0.55↔1.0 at 700ms), works on both COLLAPSED and DOCKED. Scale animation was clipped at window boundary hence alpha.

### Notification channels
- `ring_reminder_service` (IMPORTANCE_MIN) — persistent service notification, always on
- `ring_reminder_alerts` (IMPORTANCE_DEFAULT, no sound) — alert notification, auto-cancels when clear
- `ring_reminder_autofix` (IMPORTANCE_LOW) — auto-unmute scheduled notification with Cancel action

### Notifications
- Service notif: plain bell, always present
- Alert notif: **muted bell** (`ic_notification_muted`), `VISIBILITY_PUBLIC` when `lockScreenAlert` pref on
- Auto-fix scheduled notif: "Ringer will be unmuted at HH:mm", Cancel action sends `ACTION_CANCEL_AUTO_FIX`

### Icons
- `ic_notification.xml` — plain bell (white filled, 24dp)
- `ic_notification_muted.xml` — same bell + diagonal slash stroke (white, strokeWidth 2.2, rounded caps)
- `ic_tile.xml` — same bell for QS tile default
- Muted bell used on: alert notification, docked nub, small widget, large widget, QS tile (when alert active)

### Overlay themes (8 total, Pro)
default, dark, mono, vibrant, pastel, amoled, warm, cool
- Settings chips tinted with each theme's SILENT alert colour (pastel uses dark text)
- Widget background follows theme when Pro

### Pro features
| Feature | Key pref | Notes |
|---|---|---|
| Alert schedule | `schedule_enabled` + hours/minutes | Overnight-aware |
| Overlay theme | `overlay_theme` | 8 themes |
| Auto-unmute overlay | `auto_fix_overlay_enabled` | Shows "Unmute in" row in badge |
| Daily auto-unmute | `auto_fix_enabled` + `auto_fix_recurring_hour/minute` | ±1min window, once-per-day guard |
| Per-app suppression | `suppress_apps_enabled` + `suppressed_apps` | 2s fast-check loop, queryEvents |
| Lock screen alert | `lock_screen_alert` | VISIBILITY_PUBLIC on alert notif |
| Battery saver | `battery_saver_enabled` | 120s poll when battery ≤ 20% |
| Pulse on silent ring | `pulse_on_muted_alert` | Continuous alpha pulse on CATEGORY_CALL (needs NotificationListener permission) |

`isPro` defaults to `true` for dev/testing. Flip to `false` before Play Store release.

### New components (Session 4)
- `RingerNotificationListener.kt` — `NotificationListenerService`; watches `CATEGORY_CALL` notifications; sends `ACTION_PULSE_START` / `ACTION_PULSE_STOP` broadcasts
- `AppSuppressActivity.kt` — per-app suppression picker; filters to launcher-visible apps via `queryIntentActivities`; icons shown via `setCompoundDrawables`

### Permissions
```xml
RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, ACCESS_NOTIFICATION_POLICY,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, VIBRATE,
PACKAGE_USAGE_STATS, READ_PHONE_STATE, SCHEDULE_EXACT_ALARM
```
Also `<queries>` for launcher intent (enables full app list in AppSuppressActivity on Android 11+).
`BIND_NOTIFICATION_LISTENER_SERVICE` on RingerNotificationListener service element.

### Key files

| File | Role |
|---|---|
| `RingerMonitorService.kt` | Foreground service — polling, broadcasts, evaluates, snooze/nudge, auto-fix alarm, suppress fast-check, pulse, widget/tile updates |
| `RingerStateEvaluator.kt` | Pure logic — `EvaluationResult(activeConditions, primaryCondition)` |
| `OverlayBadgeManager.kt` | WindowManager overlay — 3 states, snooze/unmute dialogs, countdown display, continuous pulse |
| `NotificationHelper.kt` | All notification builders; 3 channels; auto-fix scheduled notif |
| `PrefsHelper.kt` | All SharedPreferences typed wrappers |
| `SettingsActivity.kt` | All settings — triggers, alert behaviour, fix actions, Pro features; EXTRA_SCROLL_TO support |
| `RingerNotificationListener.kt` | NotificationListenerService — CATEGORY_CALL → pulse broadcasts |
| `AppSuppressActivity.kt` | Per-app suppression app picker |
| `QuickSettingsTile.kt` | QS tile — muted bell when alert, plain bell otherwise |
| `ReminderWidget.kt` | Widget provider (small 1×1 + large 2×1) — theme colours + muted bell icon |
| `BootReceiver.kt` | Auto-start on boot if `masterEnabled` |
| `OnboardingActivity.kt` | Permission wizard (already existed) |

---

## Constraints / gotchas

1. **Service context inflation**: Always use `ContextThemeWrapper(context, R.style.Theme_RingReminder)` when inflating. Never cast to `MaterialButton` — use plain `Button`.
2. **Dialogs from Service**: Must call `dialog.window?.setType(TYPE_APPLICATION_OVERLAY)` before `dialog.show()`. Standard AlertDialog will crash with `BadTokenException`.
3. **RECEIVER_NOT_EXPORTED**: Android 14+ requires export flag on `registerReceiver` when filter includes any non-system action. Use `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.
4. **DND / SILENT**: `RingerStateEvaluator` only adds SILENT when `interruptionFilter == INTERRUPTION_FILTER_ALL`. DND causes `ringerMode = SILENT` as a side effect — don't revert this logic.
5. **Low volume + DND**: Low volume check skips when `ringerMode == SILENT` or `VIBRATE`.
6. **`isPro` default**: `true` for dev/testing. Do NOT change.
7. **ACTION_REFRESH**: SettingsActivity sends on every setting change. Any new setting must also call `notifyService()`.
8. **Scale animation clips**: On `TYPE_APPLICATION_OVERLAY` windows, scale transforms clip at window bounds. Use alpha animation instead for pulse effects.
9. **`SCHEDULE_EXACT_ALARM`**: Wrap `setExactAndAllowWhileIdle` in try/catch SecurityException, fall back to `alarmManager.set()`.
10. **Daemon warning**: `Daemon compilation failed: null` in build output is harmless. Always check last line says `BUILD SUCCESSFUL`.
11. **Widget colours**: Follow overlay theme when Pro, plain colours for free.
12. **Per-app suppression**: Uses `UsageStatsManager.queryEvents` (more reliable than `queryUsageStats`). Runs on 2s loop separate from 30s main poll when enabled.
13. **Recurring unmute**: Checks within ±1 min window; `autoFixLastFiredDate` (yyyyMMdd) prevents double-fire on same day.

---

## Roadmap (future sprints, do not implement yet)

- **Alert profiles** — named trigger presets (Work/Gym/Sleep), switch from QS tile
- **Auto-unmute schedule** — already partially built; recurring + one-shot work; future: smarter scheduling UI
- **Per-app suppression** — built; future: AccessibilityService for instant detection if UsageStats too slow
- **Mute history log** — in-app log of when/how long phone was muted
- **Billing integration** — Google Play Billing, wire to `isPro`, flip default to `false`
- **Widget preview image** — supply proper `previewImage` drawable for widget picker
- **Permission descriptions in Settings** — add a "why we need this" description line under each permission row in the Permissions card (overlay, DND, battery, notification listener, usage stats); reuse the plain-language rationale already written for onboarding
