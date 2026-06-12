# Ring Reminder — Next Session Prompt

## Project

Native Android Kotlin app (`com.jcat.ringreminder`, minSdk 26, targetSdk 35). AndroidX + Material Components + Play Billing 7.0. ViewBinding + BuildConfig enabled. Gradle 8.8, AGP 8.2.2, Java 21 target (system has Java 25 — daemon warning on build is harmless).

Working directory: `s:\Coding\Ringreminder`

Build command:
```
S:\Coding\Ringreminder\gradlew.bat assembleDebug
```
Or use `1build.bat` for the full menu (debug APK / release APK / release AAB with date-stamped versioning). Output files are named `ring-reminder-debug-VN.apk`, `ring-reminder-release-VN.apk`, `ring-reminder-VN.aab`. Each build clears the output folder first.

---

## Current state (after Session 6)

### Core service
`RingerMonitorService` — foreground service, 30s polling + broadcasts. Evaluates SILENT/DND/VIBRATE/LOW_VOLUME. Owns overlay, notifications, QS tile, widget updates. Handles:
- `ACTION_REFRESH` — immediate re-evaluate (sent by Settings on every change)
- `ACTION_FIX_NOW` / `ACTION_CANCEL_AUTO_FIX` / `ACTION_AUTO_FIX`
- `ACTION_EXPAND_OVERLAY` — sets `overlayUserPinned = true`, calls `show(lastResult)` + `expandCard()`; allows overlay to appear even when schedule/snooze is suppressing it (user explicitly requested it via widget tap)
- `ACTION_PULSE_START` / `ACTION_PULSE_STOP` (from NotificationListenerService)
- `ACTION_PAUSE` / `ACTION_RESUME`
- Per-app suppression: 2s fast-check loop via separate `suppressCheckRunnable` when `suppressAppsEnabled` is on
- Auto-fix scheduling via `AlarmManager.setExactAndAllowWhileIdle` (falls back to inexact if `SCHEDULE_EXACT_ALARM` not granted); **both `scheduleAutoFix()` and `cancelAutoFix()` call `evaluateAndUpdate()` immediately**
- Recurring daily unmute: checks within ±1 min window, tracks `autoFixLastFiredDate` to prevent double-fire
- Battery saver: polls at 120s instead of 30s when battery ≤ 20% (Pro toggle)
- Phone state listener (`PhoneStateListener`) for CALL_STATE_RINGING → `startContinuousPulse()` on overlay

**`overlayUserPinned` flag** (`@Volatile` companion field):
- Set `true` by `ACTION_EXPAND_OVERLAY` (widget tap when alert active)
- Cleared `false` by: `onUserDismissed` callback (X button), `applyFixes()` (Unmute), and `applySnooze()` (via `onUserDismissed()` call)
- When `true` + outside schedule: overlay shows despite schedule suppression
- When `true` + snoozed: overlay shows despite snooze (intentional — user explicitly opened it)

**`applyFixes()`**: calls `clearSnooze()` + `overlayUserPinned = false` first, then fixes ringer, then `evaluateAndUpdate()`.

**Schedule gate**: evaluator **always runs** against real ringer state. `isWithinSchedule()` only gates whether overlay/notification are shown. Widget and `lastResult` always receive the real evaluation result regardless of schedule. `clearSnooze()` is only called when `!result.isAlertActive` (not when outside schedule).

**`OverlayBadgeManager` init**:
```kotlin
overlayBadgeManager = OverlayBadgeManager(this, ::applyFixes, ::scheduleAutoFix) {
    overlayUserPinned = false
    evaluateAndUpdate()  // immediately re-hides if outside schedule/snooze
}
```

### Overlay badge (`OverlayBadgeManager`)
**3 states:** COLLAPSED (pill) → EXPANDED (card) → DOCKED (edge nub).

Expanded card contains (top to bottom):
1. Coloured header (title + settings gear + X)
2. Description text
3. Secondary condition chips (if `showAllConditions`)
4. **Unmute now** button (context-aware label)
5. Scheduled unmute row — "Unmutes at 4:30pm · Cancel" when one-shot pending
6. Daily unmute row — "Auto-unmutes daily at 07:30 · Change"
7. Snooze alerts for: row → AlertDialog
8. Unmute in: row → AlertDialog (Pro, `autoFixOverlayEnabled`)

Pill text shows "Unmute in Xm" when one-shot auto-fix is pending.

**X button (`btn_close_card`)**: calls `onUserDismissed()` first (clears pin + triggers `evaluateAndUpdate()`), then `if (rootView != null) transitionTo(COLLAPSED)`. If outside schedule or snoozed, `evaluateAndUpdate()` calls `hide()` before `transitionTo` runs — no pill flash.

**`applySnooze()`**: calls `onUserDismissed()` before `hide()` + `notifyService()` — ensures pin is cleared so snooze correctly suppresses the badge even when opened via widget tap outside schedule.

**`onUserDismissed` callback** (4th constructor parameter, default `= {}`): called from X button and `applySnooze()`. Service provides `{ overlayUserPinned = false; evaluateAndUpdate() }`.

`expandCard()` — public method; transitions to EXPANDED if not already; no-op if already EXPANDED. Called by service on `ACTION_EXPAND_OVERLAY` after `show()`.

All dialogs set `window?.setType(TYPE_APPLICATION_OVERLAY)` — required for Service context.

### Notification channels
- `ring_reminder_service` (IMPORTANCE_MIN) — persistent service notification
- `ring_reminder_alerts` (IMPORTANCE_DEFAULT, no sound) — alert notification, auto-cancels
- `ring_reminder_autofix` (IMPORTANCE_LOW) — auto-unmute scheduled notif with Cancel action

### Pro features + Billing
| Feature | Key pref | Notes |
|---|---|---|
| Alert schedule | `schedule_enabled` + hours/minutes | Overnight-aware; active monitoring window (alerts fire within window, suppressed outside) |
| Overlay theme | `overlay_theme` | 8 themes: default/dark/mono/vibrant/pastel/amoled/warm/cool — chips show DND colour |
| Panel background | `overlay_panel_mode` | light/dark/system (default system) — card body + text colours |
| Auto-unmute overlay | `auto_fix_overlay_enabled` | Shows "Unmute in" row in badge |
| Daily auto-unmute | `auto_fix_enabled` + `auto_fix_recurring_hour/minute` | ±1min window, once-per-day guard |
| Per-app suppression | `suppress_apps_enabled` + `suppressed_apps` | 2s fast-check, queryEvents |
| Lock screen alert | `lock_screen_alert` | VISIBILITY_PUBLIC on alert notif |
| Battery saver | `battery_saver_enabled` | 120s poll when battery ≤ 20% |
| Pulse on silent ring | `pulse_on_muted_alert` | Continuous alpha pulse on CATEGORY_CALL |

**`isPro` logic** (in `PrefsHelper`):
- `BuildConfig.DEBUG` → always Pro (debug builds)
- `hasPurchasedPro` → set by BillingManager on confirmed purchase
- `isInTrial` → `System.currentTimeMillis() - trialStartMs < TRIAL_DURATION_MS` (7 days; `trialStartMs` auto-created on first access)

**BillingManager** (`BillingManager.kt`):
- Google Play Billing 7.0.0 (`com.android.billingclient:billing-ktx:7.0.0`)
- Product ID: `ring_reminder_pro`
- `start(onProStatus)` — connects, queries purchases, calls back with Pro status
- `refresh()` — re-queries if client already ready; called from `MainActivity.onResume()`
- `restorePurchases(onResult)` — manual query with toast feedback; "Restore" button in upgrade row
- `launchPurchaseFlow(activity, onProStatus)` — opens purchase sheet
- `destroy()` — disconnects billing client (call from `onDestroy`)
- `val status: String` — returns "Connected", "Connecting / Disconnected", or "Not started" (used by dev panel)
- Wired in both `MainActivity` (start + refresh on resume) and `SettingsActivity` (purchase flow)
- **Gap**: `onBillingServiceDisconnected()` does nothing — if connection drops, `refresh()` silently skips; needs reconnect logic

**Pro upgrade row** in Settings: visible whenever `!hasPurchasedPro`. Text shows:
- Trial active: "X days remaining in your free trial"
- Trial expired: "Your 7-day free trial has ended · Upgrade to keep Pro features"

Row contains two buttons: **Restore** (TextButton, calls `BillingManager.restorePurchases()`, toasts success/not-found) and **Upgrade to Pro** (filled). Both hidden once purchased.

**Known billing gap**: upgrade row (and Restore button) hidden when `hasPurchasedPro=true` — Pro users have no visible way to trigger a billing re-check. `onBillingServiceDisconnected()` also does nothing — if connection drops, `refresh()` silently skips. Both to be fixed.

**Debug build note**: The upgrade row and trial text are still visible in debug builds (controlled by `hasPurchasedPro`, not `isPro`). This is intentional — it previews the non-Pro UI. All Pro features are fully unlocked regardless because `isPro` always returns `true` when `BuildConfig.DEBUG = true`.

### Developer Panel (hidden diagnostic mode)

Activated by **tapping the "RING REMINDER PRO" section header 7 times** in Settings → toast "Developer mode enabled". Deactivated by **long-pressing the same header** → toast "Developer mode disabled". State persisted via `prefs.devModeEnabled`.

Panel appears at the bottom of Settings (orange border, `dev_panel_card`). Shows:

| Section | Fields |
|---|---|
| Device | Current time, Android API level, app version |
| Pro Status | `isPro`, `hasPurchasedPro`, `isInTrial`, trial start/expiry, days remaining, billing client status |
| Service | `isRunning`, `isPaused`, `overlayUserPinned`, primary condition, active conditions |
| Schedule | `scheduleEnabled`, window, `isWithinSchedule()` live value |
| Snooze | Active snooze state (expiry time + condition, or None) |
| Auto-fix | Next one-shot scheduled time, `autoFixLastFiredDate`, `alertFirstSeenMs` |
| Permissions | Notifications (API 33+), Overlay, DND, Battery exempt, Notif listener, Usage stats |

**Buttons**:
- **Expire Trial** (tap) — sets `trialStartMs` to 8 days ago → `isInTrial = false` immediately
- **Refresh** (tap) — re-reads all values; **(hold 5 seconds)** → resets `trialStartMs` to now → trial active again

**Billing diagnosis flow**: Tap "Expire Trial", then read `isPro` in the panel:
- `isPro = false` → billing already cleared `hasPurchasedPro` (refund propagated); trial was keeping Pro alive
- `isPro = true` → `hasPurchasedPro` still `true` (Play Store billing cache not yet synced with refund)

### Permissions section (Settings)
Now includes all 6 permissions, each with status text and a Fix button:
1. **Show notifications** (POST_NOTIFICATIONS) — Android 13+ only; Fix opens app notification settings
2. Draw over other apps
3. Do Not Disturb access
4. Battery optimisation exempt
5. Notification access (for pulse feature)
6. App usage access (for per-app suppression)

POST_NOTIFICATIONS row is hidden on Android < 13.

### Android 15 edge-to-edge
`app/src/main/res/values-v35/` — theme override to handle edge-to-edge enforcement on Android 15+.

### Key files

| File | Role |
|---|---|
| `RingerMonitorService.kt` | Foreground service — polling, broadcasts, evaluates, snooze/nudge, auto-fix alarm, suppress fast-check, pulse, widget/tile updates |
| `RingerStateEvaluator.kt` | Pure logic — `EvaluationResult(activeConditions, primaryCondition)` |
| `OverlayBadgeManager.kt` | WindowManager overlay — 3 states, snooze/unmute dialogs, countdown, pulse |
| `NotificationHelper.kt` | All notification builders; 3 channels |
| `PrefsHelper.kt` | All SharedPreferences typed wrappers; trial logic; `devModeEnabled` |
| `BillingManager.kt` | Google Play Billing 7.0 — purchase flow, Pro status, `status` property |
| `SettingsActivity.kt` | All settings UI; EXTRA_SCROLL_TO support; billing wired; dev panel (7-tap activate, long-press deactivate) |
| `RingerNotificationListener.kt` | NotificationListenerService — CATEGORY_CALL → pulse broadcasts |
| `AppSuppressActivity.kt` | Per-app suppression app picker |
| `QuickSettingsTile.kt` | QS tile — muted bell when alert, plain bell otherwise |
| `ReminderWidget.kt` | Widget provider (small 1×1 + large 2×1) — theme colours; tap expands overlay when alert active |
| `BootReceiver.kt` | Auto-start on boot if `masterEnabled` |
| `OnboardingActivity.kt` | Permission wizard |

---

## Constraints / gotchas

1. **Service context inflation**: Always use `ContextThemeWrapper(context, R.style.Theme_RingReminder)`. Never cast to `MaterialButton` — use plain `Button`.
2. **Dialogs from Service**: Must call `dialog.window?.setType(TYPE_APPLICATION_OVERLAY)` before `dialog.show()`.
3. **RECEIVER_NOT_EXPORTED**: Android 14+ requires export flag. Use `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.
4. **DND / SILENT**: Evaluator only adds SILENT when `interruptionFilter == INTERRUPTION_FILTER_ALL`. DND causes `ringerMode = SILENT` as side effect — don't revert.
5. **Low volume + DND**: Low volume check skips when `ringerMode == SILENT` or `VIBRATE`.
6. **`isPro` default**: `BuildConfig.DEBUG || hasPurchasedPro || isInTrial`. Always Pro in debug.
7. **ACTION_REFRESH**: Every new setting must call `notifyService()`.
8. **Scale animation clips**: On overlay windows, use alpha animation — scale transforms clip at window bounds.
9. **`SCHEDULE_EXACT_ALARM`**: Wrap in try/catch SecurityException, fall back to `alarmManager.set()`.
10. **Daemon warning**: Harmless. Always check last line says `BUILD SUCCESSFUL`.
11. **AGP/lint workaround**: `lint { checkReleaseBuilds false }` and `android.suppressUnsupportedCompileSdk=35` in gradle.properties are temporary workarounds until AGP is upgraded.
12. **Schedule is active-hours, not quiet-hours**: `isWithinSchedule() = true` means alerts fire; `false` means suppressed. A 12–22 window means alerts only show between noon and 10pm.
13. **`overlayUserPinned` and snooze/schedule**: Pin overrides both schedule and snooze suppression so users can inspect state via widget tap. `applySnooze()` calls `onUserDismissed()` to clear the pin before hiding — always do the same for any future action that should respect snooze/schedule.
14. **`onUserDismissed` callback**: Called from X button AND `applySnooze()`. Clears pin + calls `evaluateAndUpdate()`. Adding new dismissal paths (e.g. auto-dismiss after timeout) must call this too.

---

## Next session — start here

1. **AGP upgrade** — bump AGP `8.2.2` → `8.7.x` and Kotlin `1.9.22` → `2.0.x`; run build and fix any K2 compiler complaints; then remove `android.suppressUnsupportedCompileSdk=35` from `gradle.properties` and `lint { checkReleaseBuilds false }` from `app/build.gradle`
2. **Billing reconnect** — fix `onBillingServiceDisconnected()` to reconnect + retry, and add a "Verify purchase" option visible to Pro users (upgrade row is hidden when `hasPurchasedPro=true` so there's no manual re-check available). Note: dev panel's billing status field helps diagnose connection state.

---

## Roadmap (future sprints, do not implement yet)

### Near-term
- **Widget preview image** — supply proper `previewImage` drawable for widget picker

### Longer horizon
- **Alert profiles** — named trigger presets (Work/Gym/Sleep), switch from QS tile
- **Per-app suppression** — future: AccessibilityService for instant detection if UsageStats too slow
- **Mute history log** — in-app log of when/how long phone was muted
